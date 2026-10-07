//! The session supervisor: one task per running engine (docs/ARCHITECTURE.md §4.2).
//!
//! Phases: `Gate` (offline mode / no network: no attempts) → `Connect` (one attempt at a time) →
//! `Online` (watch the Spirc task, `session.is_invalid()` and the Player every 5 s) → on loss
//! `Wait` (exponential backoff 1→60 s + jitter) → `Connect` … Terminal errors (BAD_CREDENTIALS,
//! PREMIUM_REQUIRED) park in `Halted`; more than 10 reconnects in 10 minutes park in `Throttled`
//! until the network changes. Every phase reacts to messages (stop, network, settings, player
//! death) without waiting for timers; the connect attempt itself is cancellable.
//!
//! The backoff is reset only once a connection stayed up for `backoff::STABLE_AFTER` (or the
//! network changes): a connection that fails right after connecting keeps backing off. The
//! reconnect limit is process-wide (`state::Shared::reconnects`), and the first attempt of a
//! new supervisor counts too, so Kotlin restarting the session can't undo the throttle.

use super::backoff::{Backoff, RateLimiter};
use super::connector::{self, Device, Live};
use super::state::{self, shared, update_status};
use super::{config, player_host};
use crate::error::{AppError, ErrorCode};
use crate::{connect, events};
use crate::models::{EngineSettings, SessionState, User};
use futures_util::FutureExt;
use librespot_connect::SnapshotPlayStatus;
use librespot_core::authentication::Credentials;
use librespot_core::Session;
use std::panic::AssertUnwindSafe;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{interval, interval_at, Instant, MissedTickBehavior};

const VERIFY_INTERVAL: Duration = Duration::from_millis(250);
/// 20 × 250 ms: wait at most 5 s for the ProductInfo attributes before declaring Online.
const VERIFY_TICKS: u32 = 20;
const HEALTH_INTERVAL: Duration = Duration::from_secs(5);
/// A network outage longer than this means librespot's connections are probably dead.
const OUTAGE_RECONNECT: Duration = Duration::from_secs(5);
/// Online but Android reports the network lost: after this long the session is torn down and
/// the engine goes Offline (downloads then play through the OfflineController, §4.6). A
/// suspended mobile network keeps the AP socket open, so librespot itself would only notice
/// after its 80 s keep-alive.
const NETWORK_LOSS_GRACE: Duration = Duration::from_secs(12);
/// While this device is still streaming from its buffer when the grace ran out, check again
/// this often (a short tunnel must not interrupt playback).
const NETWORK_LOSS_RECHECK: Duration = Duration::from_secs(5);
/// ... but for at most this long after the loss: the session then goes offline anyway (a Spirc
/// whose loop hangs on the dead network keeps reporting "playing").
const NETWORK_LOSS_MAX: Duration = Duration::from_secs(60);
const RECONNECTS_PER_WINDOW: usize = 10;
const RECONNECT_WINDOW: Duration = Duration::from_secs(10 * 60);
/// How long a stopping supervisor may take for its graceful teardown before it is aborted.
/// Its longest step is one connection teardown (`connector::TEARDOWN_BOUND`).
pub(crate) const STOP_TIMEOUT: Duration = Duration::from_secs(7);
/// After an abort: how long to wait for the aborted task to actually end.
pub(crate) const ABORT_GRACE: Duration = Duration::from_millis(500);
// A Stop that arrives during a reconnect teardown is handled right after it (the next connect
// attempt is abandoned at once), so one teardown must fit into the graceful stop.
const _: () = assert!(connector::TEARDOWN_BOUND.as_millis() < STOP_TIMEOUT.as_millis());

pub(crate) enum Msg {
    Network { available: bool, outage: Option<Duration> },
    /// Retry now (a repeated `session.start`).
    Reconnect,
    Settings { old: EngineSettings },
    PlayerDead(u64),
    /// No network: a load of downloads takes the Player (`engine::go_offline`), the session
    /// goes offline right away.
    GoOffline,
    Stop,
}

pub(crate) struct SupervisorHandle {
    tx: mpsc::UnboundedSender<Msg>,
    join: JoinHandle<()>,
}

impl SupervisorHandle {
    pub fn is_finished(&self) -> bool {
        self.join.is_finished()
    }

    pub fn send(&self, msg: Msg) {
        let _ = self.tx.send(msg);
    }

    /// Stops the supervisor and waits until its task ended: a graceful teardown of at most
    /// [`STOP_TIMEOUT`], then an abort (plus [`ABORT_GRACE`] for the task to end) and a forced
    /// cleanup. Nothing of this supervisor runs afterwards, so a new one can start safely.
    pub async fn stop(self) {
        let delivered = self.tx.send(Msg::Stop).is_ok();
        *shared().supervisor_tx.lock() = None;
        let mut join = self.join;
        if delivered && tokio::time::timeout(STOP_TIMEOUT, &mut join).await.is_ok() {
            return;
        }
        if join.is_finished() {
            return;
        }
        log::error!("supervisor did not stop in time, aborting it");
        join.abort();
        if tokio::time::timeout(ABORT_GRACE, &mut join).await.is_err() {
            log::error!("aborted supervisor did not end in time");
        }
        force_cleanup();
    }
}

/// Leaves no online state behind after an aborted supervisor (the dropped Spirc handle shuts
/// its task down by itself).
fn force_cleanup() {
    state::set_online(None);
    connect::detach_all();
    connect::clear_restore();
    player_host::detach_session();
}

/// The process-wide reconnect limit (`state::Shared::reconnects`).
pub(crate) fn reconnect_limiter() -> RateLimiter {
    RateLimiter::new(RECONNECTS_PER_WINDOW, RECONNECT_WINDOW)
}

pub(crate) fn spawn() -> SupervisorHandle {
    let (tx, rx) = mpsc::unbounded_channel();
    *shared().supervisor_tx.lock() = Some(tx.clone());
    let supervisor = Supervisor {
        rx,
        backoff: Backoff::default(),
        first: true,
        intentional: false,
        prefer_token: false,
        login_generation: state::login_generation(),
    };
    let join = crate::runtime::handle().spawn(async move {
        if AssertUnwindSafe(supervisor.run()).catch_unwind().await.is_err() {
            log::error!("session supervisor panicked");
            force_cleanup();
            update_status(|s| {
                s.state = SessionState::Error;
                s.error = Some(AppError::internal("The session supervisor crashed"));
                s.next_retry_ms = None;
                s.epoch += 1;
            });
        }
    });
    SupervisorHandle { tx, join }
}

enum Phase {
    Gate,
    Connect,
    Wait { delay: Duration, error: AppError },
    Online(Live),
    Halted(AppError),
    Throttled(AppError),
    Exit,
}

enum AttemptEnd {
    Done(Result<Live, AppError>),
    Abort(Phase),
}

struct Supervisor {
    rx: mpsc::UnboundedReceiver<Msg>,
    backoff: Backoff,
    /// No attempt finished yet (state `connecting` instead of `reconnecting`).
    first: bool,
    /// The next attempt is a deliberate reconnect (becoming visible to Spotify Connect, a device
    /// rename): it isn't counted by the reconnect limit.
    intentional: bool,
    /// Stored credentials were rejected: try the OAuth access token.
    prefer_token: bool,
    /// The login this supervisor was started with (`state::Login::generation`).
    login_generation: u64,
}

/// Online while Android reports the network lost: when to stop treating the session as online.
#[derive(Debug, Default)]
struct NetworkLoss {
    deadline: Option<Instant>,
    /// When the network was reported lost.
    since: Option<Instant>,
}

impl NetworkLoss {
    /// The network was reported lost at `now`; an earlier loss keeps its deadline.
    fn lost(&mut self, now: Instant) {
        self.since.get_or_insert(now);
        self.deadline.get_or_insert(now + NETWORK_LOSS_GRACE);
    }

    fn restored(&mut self) {
        self.deadline = None;
        self.since = None;
    }

    fn deadline(&self) -> Option<Instant> {
        self.deadline
    }

    /// The deadline passed at `now`. Returns true to go offline; while this device is still
    /// `streaming` a buffered track, the decision is deferred by [`NETWORK_LOSS_RECHECK`], up to
    /// [`NETWORK_LOSS_MAX`] after the loss.
    fn expired(&mut self, now: Instant, streaming: bool) -> bool {
        let cap = self.since.map(|since| since + NETWORK_LOSS_MAX);
        if streaming && cap.is_none_or(|cap| now < cap) {
            let next = now + NETWORK_LOSS_RECHECK;
            self.deadline = Some(cap.map_or(next, |cap| next.min(cap)));
            return false;
        }
        self.restored();
        true
    }
}

/// This device is the active Connect device and streams (from its buffer, as there is no
/// network): tearing the session down now would cut the music off. Not for a downloaded track:
/// that playback goes on in the OfflineController (`connect::hand_off_to_offline`).
fn streaming_locally(live: &Live) -> bool {
    live.device.as_ref().is_some_and(|device| {
        let state = device.spirc.subscribe_state();
        let snapshot = state.borrow();
        snapshot.is_active
            && matches!(snapshot.status, SnapshotPlayStatus::Playing)
            && snapshot.track.as_ref().is_some_and(|t| !crate::offline::is_downloaded(&t.uri))
    })
}

/// Resolves when the Spirc task of `device` ended; never for a hidden session.
async fn spirc_ended(device: &mut Option<Device>) {
    match device {
        Some(device) => {
            let _ = (&mut device.task).await;
        }
        None => std::future::pending().await,
    }
}

fn no_network() -> AppError {
    AppError::new(ErrorCode::Network, "No network connection")
}

/// The user, once the ProductInfo attributes arrived.
fn user_of(session: &Session) -> Option<User> {
    let product = session.get_user_attribute("type")?;
    let country = Some(session.country()).filter(|c| !c.is_empty()).or_else(|| session.get_user_attribute("country"));
    Some(User {
        username: session.username(),
        display_name: None,
        images: Vec::new(),
        product: Some(product),
        country,
        // The account's own filter; "Hide explicit content" may force the session's on.
        explicit_filter: super::explicit::account_filter(session),
    })
}

impl Supervisor {
    async fn run(mut self) {
        let mut phase = Phase::Gate;
        loop {
            phase = match phase {
                Phase::Gate => self.gate().await,
                Phase::Connect => self.connect().await,
                Phase::Wait { delay, error } => self.wait(delay, error).await,
                Phase::Online(live) => self.online(live).await,
                Phase::Halted(e) => self.halted(e, false).await,
                Phase::Throttled(e) => self.halted(e, true).await,
                Phase::Exit => break,
            };
        }
    }

    /// `None` when the supervisor must exit (stop requested, or the handle is gone).
    fn stop_requested(&mut self, msg: Option<Msg>) -> Option<Msg> {
        match msg {
            None | Some(Msg::Stop) => None,
            Some(other) => Some(other),
        }
    }

    /// Offline mode or no network: wait without attempts.
    async fn gate(&mut self) -> Phase {
        loop {
            let settings = super::settings();
            if settings.offline {
                connect::clear_restore();
                update_status(|s| {
                    s.state = SessionState::Offline;
                    s.offline_mode = true;
                    s.error = None;
                    s.next_retry_ms = None;
                });
            } else if !state::network_available() {
                update_status(|s| {
                    s.state = SessionState::Offline;
                    s.offline_mode = false;
                    s.error = Some(no_network());
                    s.next_retry_ms = None;
                });
            } else {
                return Phase::Connect;
            }
            let msg = self.rx.recv().await;
            match self.stop_requested(msg) {
                None => return Phase::Exit,
                Some(Msg::Network { available: true, .. }) => self.backoff.reset(),
                Some(_) => {}
            }
        }
    }

    fn credentials(&self) -> Option<(Credentials, bool)> {
        let (stored, token) = {
            let login = shared().login.lock();
            (login.credentials.clone(), login.access_token.clone())
        };
        if !self.prefer_token {
            if let Some(c) = stored.as_ref().and_then(|s| config::to_librespot(s).ok()) {
                return Some((c, true));
            }
        }
        if let Some(t) = token {
            return Some((Credentials::with_access_token(t), false));
        }
        stored.and_then(|s| config::to_librespot(&s).ok()).map(|c| (c, true))
    }

    fn retry_after(&mut self, error: AppError) -> Phase {
        let delay = self.backoff.next_delay(rand::random::<f64>());
        Phase::Wait { delay, error }
    }

    async fn connect(&mut self) -> Phase {
        if super::settings().offline || !state::network_available() {
            return Phase::Gate;
        }
        let intentional = std::mem::take(&mut self.intentional);
        let allowed = {
            let mut limiter = shared().reconnects.lock();
            let now = std::time::Instant::now();
            if intentional {
                true
            } else if self.first {
                // Always allowed (an explicit start), but counted.
                limiter.record(now);
                true
            } else {
                limiter.try_acquire(now)
            }
        };
        if !allowed {
            return Phase::Throttled(AppError::new(
                ErrorCode::Network,
                "Too many reconnects; retrying when the network changes",
            ));
        }
        let first = self.first;
        update_status(|s| {
            s.state = if first { SessionState::Connecting } else { SessionState::Reconnecting };
            s.offline_mode = false;
            s.next_retry_ms = None;
        });
        let settings = super::settings();
        let Some((credentials, used_stored)) = self.credentials() else {
            return Phase::Halted(AppError::new(ErrorCode::NotLoggedIn, "Not logged in"));
        };
        let session = match connector::prepare(&settings).await {
            Ok(s) => s,
            Err(e) => {
                self.first = false;
                return self.retry_after(e);
            }
        };
        let visible = settings.connect_visible;
        let end = {
            let attempt = async {
                if visible {
                    connector::connect(&session, credentials, &settings).await
                } else {
                    connector::connect_hidden(&session, credentials).await
                }
            };
            tokio::pin!(attempt);
            loop {
                tokio::select! {
                    r = &mut attempt => break AttemptEnd::Done(r),
                    msg = self.rx.recv() => match self.stop_requested(msg) {
                        None => break AttemptEnd::Abort(Phase::Exit),
                        Some(Msg::Network { available: false, .. }) => break AttemptEnd::Abort(Phase::Gate),
                        Some(Msg::GoOffline) if !state::network_available() => break AttemptEnd::Abort(Phase::Gate),
                        Some(Msg::Settings { .. }) if super::settings().offline => break AttemptEnd::Abort(Phase::Gate),
                        Some(_) => {}
                    },
                }
            }
        };
        match end {
            AttemptEnd::Abort(next) => {
                connector::abandon(session).await;
                next
            }
            AttemptEnd::Done(Ok(live)) => Phase::Online(live),
            AttemptEnd::Done(Err(e)) => {
                log::warn!("connect failed: {e}");
                connector::abandon(session).await;
                self.first = false;
                if e.code == ErrorCode::BadCredentials && used_stored && shared().login.lock().access_token.is_some() {
                    self.prefer_token = true;
                    return Phase::Connect;
                }
                if connector::is_terminal(&e) {
                    return Phase::Halted(e);
                }
                self.retry_after(e)
            }
        }
    }

    async fn wait(&mut self, delay: Duration, error: AppError) -> Phase {
        update_status(|s| {
            s.state = SessionState::Reconnecting;
            s.error = Some(error);
            s.next_retry_ms = Some(delay.as_millis() as u64);
            s.epoch += 1;
        });
        let sleep = tokio::time::sleep(delay);
        tokio::pin!(sleep);
        loop {
            tokio::select! {
                _ = &mut sleep => return Phase::Connect,
                msg = self.rx.recv() => match self.stop_requested(msg) {
                    None => return Phase::Exit,
                    Some(Msg::Network { available: false, .. }) => return Phase::Gate,
                    Some(Msg::Network { available: true, .. }) => {
                        self.backoff.reset();
                        return Phase::Connect;
                    }
                    Some(Msg::Reconnect) => return Phase::Connect,
                    Some(Msg::GoOffline) if !state::network_available() => return Phase::Gate,
                    Some(Msg::Settings { .. }) if super::settings().offline => return Phase::Gate,
                    Some(_) => {}
                },
            }
        }
    }

    fn declare_online(&mut self, live: &Live, user: Option<User>) {
        state::record_username(self.login_generation, &live.session);
        state::set_online(Some(live.session.clone()));
        // ProductInfo is in (that's what declares): now the account's filter is known.
        super::sync_explicit_filter();
        // No backoff reset here: only a connection that proves stable resets it (`online`).
        self.first = false;
        self.prefer_token = false;
        update_status(|s| {
            s.state = SessionState::Online;
            s.error = None;
            s.next_retry_ms = None;
            s.offline_mode = false;
            if user.is_some() {
                s.user = user;
            }
            s.epoch += 1;
        });
        log::info!("session online{}", if live.device.is_some() { "" } else { " (hidden from Spotify Connect)" });
        if live.device.is_some() {
            connect::after_online(live.generation);
        }
    }

    /// Intentional teardown followed by an immediate attempt (local playback is restored).
    async fn reconnect(&mut self, live: Live) -> Phase {
        log::info!("reconnecting");
        let restore = live.device.is_some();
        connector::teardown(live, restore).await;
        Phase::Connect
    }

    /// Brings the Spotify Connect side in line with the settings: hides (Spirc shut down, the
    /// Session kept) or asks for a reconnect (returns true) to become visible, or to apply a
    /// device rename once this device isn't the active one playing.
    async fn sync_device(&mut self, live: &mut Live, rename_pending: &mut bool) -> bool {
        let visible = super::settings().connect_visible;
        let Some(device) = live.device.as_ref() else {
            // A hidden session gets the current name when it becomes visible.
            *rename_pending = false;
            if visible {
                log::info!("becoming visible to Spotify Connect");
                self.intentional = true;
            }
            return visible;
        };
        if !visible {
            log::info!("hiding from Spotify Connect");
            *rename_pending = false;
            connector::hide(live).await;
            return false;
        }
        if !*rename_pending {
            return false;
        }
        let busy = {
            let state = device.spirc.subscribe_state();
            let snapshot = state.borrow();
            snapshot.is_active && !matches!(snapshot.status, SnapshotPlayStatus::Stopped)
        };
        if busy {
            return false; // applied once this device stops playing or isn't active any more
        }
        log::info!("reconnecting to apply the new device name");
        self.intentional = true;
        true
    }

    async fn online(&mut self, mut live: Live) -> Phase {
        if let Some(harvested) = connector::session_credentials(&live.session) {
            if state::store_harvested(self.login_generation, harvested.clone()) {
                events::emit(events::CREDENTIALS, &harvested);
            }
        }
        let connected_at = std::time::Instant::now();
        let mut stable = false;
        let mut rename_pending = false;
        let mut declared = false;
        let mut user_known = false;
        let mut verify_ticks = 0u32;
        let mut verify = interval(VERIFY_INTERVAL);
        verify.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut health = interval_at(Instant::now() + HEALTH_INTERVAL, HEALTH_INTERVAL);
        health.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut network_loss = NetworkLoss::default();
        if !state::network_available() {
            // Lost while the attempt finished.
            network_loss.lost(Instant::now());
        }
        loop {
            let loss_deadline = network_loss.deadline();
            tokio::select! {
                _ = tokio::time::sleep_until(loss_deadline.unwrap_or_else(Instant::now)), if loss_deadline.is_some() => {
                    if state::network_available() {
                        network_loss.restored();
                        continue;
                    }
                    if network_loss.expired(Instant::now(), streaming_locally(&live)) {
                        log::info!("network lost: going offline");
                        connector::teardown(live, true).await;
                        return Phase::Gate;
                    }
                    log::debug!("network lost, but this device is playing: staying online for now");
                }
                _ = spirc_ended(&mut live.device) => {
                    self.backoff.note_uptime(connected_at, std::time::Instant::now());
                    let premium = connector::premium_error(&live.session);
                    log::warn!("spirc task ended (session invalid: {})", live.session.is_invalid());
                    connector::teardown_finished(live, premium.is_none()).await;
                    return match premium {
                        Some(e) => Phase::Halted(e),
                        None => self.retry_after(AppError::new(ErrorCode::Network, "Connection to Spotify lost")),
                    };
                }
                _ = verify.tick(), if !declared => {
                    if let Some(e) = connector::premium_error(&live.session) {
                        connector::teardown(live, false).await;
                        return Phase::Halted(e);
                    }
                    verify_ticks += 1;
                    let user = user_of(&live.session);
                    if user.is_some() || verify_ticks >= VERIFY_TICKS {
                        declared = true;
                        user_known = user.is_some();
                        self.declare_online(&live, user);
                        // The visibility may have changed while connecting.
                        if self.sync_device(&mut live, &mut rename_pending).await {
                            return self.reconnect(live).await;
                        }
                    }
                }
                _ = health.tick(), if declared => {
                    if let Some(e) = connector::premium_error(&live.session) {
                        connector::teardown(live, false).await;
                        return Phase::Halted(e);
                    }
                    if !stable {
                        stable = self.backoff.note_uptime(connected_at, std::time::Instant::now());
                    }
                    if live.device.is_some() && player_host::dead_generation().is_some() {
                        return self.reconnect(live).await;
                    }
                    if live.session.is_invalid() {
                        if stable {
                            return self.reconnect(live).await;
                        }
                        // Lost right after connecting: back off like a failed attempt.
                        connector::teardown(live, true).await;
                        return self.retry_after(AppError::new(ErrorCode::Network, "Connection to Spotify lost"));
                    }
                    // Spirc may have overwritten the forced filter (a server attribute push).
                    super::sync_explicit_filter();
                    if self.sync_device(&mut live, &mut rename_pending).await {
                        return self.reconnect(live).await;
                    }
                    if !user_known {
                        if let Some(user) = user_of(&live.session) {
                            user_known = true;
                            update_status(|s| s.user = Some(user));
                        }
                    }
                }
                msg = self.rx.recv() => match self.stop_requested(msg) {
                    None => {
                        connector::teardown(live, false).await;
                        return Phase::Exit;
                    }
                    Some(Msg::Network { available: true, outage }) => {
                        network_loss.restored();
                        if outage.is_some_and(|d| d > OUTAGE_RECONNECT) || live.session.is_invalid() {
                            self.backoff.reset();
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::Network { available: false, .. }) => network_loss.lost(Instant::now()),
                    Some(Msg::Reconnect) => {
                        if live.session.is_invalid() {
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::Settings { old }) => {
                        let new = super::settings();
                        if new.offline {
                            // Downloaded playback of this device keeps playing in offline mode.
                            if live.device.is_some() && connect::hand_off_to_offline(live.generation) {
                                log::info!("offline mode: the downloads keep playing offline");
                            }
                            connector::teardown(live, false).await;
                            return Phase::Gate;
                        }
                        if old.autoplay != new.autoplay {
                            if let Some(device) = &live.device {
                                if let Err(e) = device.spirc.set_autoplay(new.autoplay) {
                                    log::warn!("autoplay setting not applied: {e}");
                                }
                            }
                        }
                        if old.device_name != new.device_name && live.device.is_some() {
                            rename_pending = true;
                        }
                        if declared && self.sync_device(&mut live, &mut rename_pending).await {
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::PlayerDead(generation)) => {
                        // This Spirc plays on that Player (or on an older one the offline path
                        // replaced meanwhile): rebuild it, also when the thread hasn't finished
                        // yet or the Player was already replaced.
                        if live.device.is_some() {
                            log::warn!("player {generation} died, rebuilding the Spirc");
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::GoOffline) => {
                        if !state::network_available() {
                            log::info!("downloads play without a network: going offline");
                            connector::teardown(live, false).await;
                            return Phase::Gate;
                        }
                    }
                    Some(Msg::Stop) => {}
                },
            }
        }
    }

    async fn halted(&mut self, error: AppError, throttled: bool) -> Phase {
        connect::clear_restore();
        log::warn!("session halted: {error}");
        update_status(|s| {
            s.state = SessionState::Error;
            s.error = Some(error);
            s.next_retry_ms = None;
            s.epoch += 1;
        });
        loop {
            let msg = self.rx.recv().await;
            match self.stop_requested(msg) {
                None => return Phase::Exit,
                Some(Msg::Network { available: true, .. }) if throttled => {
                    shared().reconnects.lock().reset();
                    self.backoff.reset();
                    return Phase::Connect;
                }
                Some(Msg::Reconnect) => {
                    shared().reconnects.lock().reset();
                    self.backoff.reset();
                    return Phase::Connect;
                }
                Some(_) => {}
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_network_loss_goes_offline_after_the_grace() {
        let t0 = Instant::now();
        let mut loss = NetworkLoss::default();
        assert_eq!(loss.deadline(), None);
        loss.lost(t0);
        assert_eq!(loss.deadline(), Some(t0 + NETWORK_LOSS_GRACE));
        // A second report keeps the first deadline.
        loss.lost(t0 + Duration::from_secs(5));
        assert_eq!(loss.deadline(), Some(t0 + NETWORK_LOSS_GRACE));
        assert!(loss.expired(t0 + NETWORK_LOSS_GRACE, false), "not playing: offline");
        assert_eq!(loss.deadline(), None);
    }

    #[test]
    fn the_network_coming_back_cancels_it() {
        let t0 = Instant::now();
        let mut loss = NetworkLoss::default();
        loss.lost(t0);
        loss.restored();
        assert_eq!(loss.deadline(), None);
        // A new loss gets a full grace of its own.
        loss.lost(t0 + Duration::from_secs(30));
        assert_eq!(loss.deadline(), Some(t0 + Duration::from_secs(30) + NETWORK_LOSS_GRACE));
    }

    #[test]
    fn streaming_from_the_buffer_defers_it() {
        let t0 = Instant::now();
        let mut loss = NetworkLoss::default();
        loss.lost(t0);
        let due = t0 + NETWORK_LOSS_GRACE;
        assert!(!loss.expired(due, true), "a short tunnel doesn't stop the music");
        assert_eq!(loss.deadline(), Some(due + NETWORK_LOSS_RECHECK));
        // Once playback stopped (the buffer ran out, paused), the next check goes offline.
        assert!(loss.expired(due + NETWORK_LOSS_RECHECK, false));
    }

    #[test]
    fn the_deferral_is_capped() {
        let t0 = Instant::now();
        let mut loss = NetworkLoss::default();
        loss.lost(t0);
        let mut now = t0 + NETWORK_LOSS_GRACE;
        // a Spirc stuck on the dead network keeps saying "playing"
        while !loss.expired(now, true) {
            now = loss.deadline().expect("deferred");
            assert!(now <= t0 + NETWORK_LOSS_MAX, "never past the cap");
        }
        assert_eq!(now, t0 + NETWORK_LOSS_MAX);
        assert_eq!(loss.deadline(), None);
        // a new loss starts over
        loss.lost(now + Duration::from_secs(1));
        assert!(!loss.expired(now + Duration::from_secs(1) + NETWORK_LOSS_GRACE, true));
    }
}
