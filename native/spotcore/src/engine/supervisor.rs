//! The session supervisor: one task per running engine (docs/ARCHITECTURE.md §4.2).
//!
//! Phases: `Gate` (offline mode / no network: no attempts) → `Connect` (one attempt at a time) →
//! `Online` (watch the Spirc task, `session.is_invalid()` and the Player every 5 s) → on loss
//! `Wait` (exponential backoff 1→60 s + jitter) → `Connect` … Terminal errors (BAD_CREDENTIALS,
//! PREMIUM_REQUIRED) park in `Halted`; more than 10 reconnects in 10 minutes park in `Throttled`
//! until the network changes. Every phase reacts to messages (stop, network, settings, player
//! death) without waiting for timers; the connect attempt itself is cancellable.

use super::backoff::{Backoff, RateLimiter};
use super::connector::{self, Live};
use super::state::{self, shared, update_status};
use super::{config, player_host};
use crate::connect;
use crate::error::{AppError, ErrorCode};
use crate::models::{EngineSettings, SessionState, User};
use futures_util::FutureExt;
use librespot_core::authentication::Credentials;
use librespot_core::Session;
use std::panic::AssertUnwindSafe;
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio::time::{interval, interval_at, Instant, MissedTickBehavior};

const VERIFY_INTERVAL: Duration = Duration::from_millis(250);
/// 20 × 250 ms: wait at most 5 s for the ProductInfo attributes before declaring Online.
const VERIFY_TICKS: u32 = 20;
const HEALTH_INTERVAL: Duration = Duration::from_secs(5);
/// A network outage longer than this means librespot's connections are probably dead.
const OUTAGE_RECONNECT: Duration = Duration::from_secs(5);
const RECONNECTS_PER_WINDOW: usize = 10;
const RECONNECT_WINDOW: Duration = Duration::from_secs(10 * 60);
const STOP_TIMEOUT: Duration = Duration::from_secs(20);

pub(crate) enum Msg {
    Network { available: bool, outage: Option<Duration> },
    /// Retry now (a repeated `session.start`).
    Reconnect,
    Settings { old: EngineSettings },
    PlayerDead(u64),
    Stop { reply: oneshot::Sender<()> },
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

    /// Stops the supervisor (graceful teardown, bounded), then forgets it.
    pub async fn stop(self) {
        let (reply_tx, reply_rx) = oneshot::channel();
        let delivered = self.tx.send(Msg::Stop { reply: reply_tx }).is_ok();
        *shared().supervisor_tx.lock() = None;
        let mut join = self.join;
        if delivered && matches!(tokio::time::timeout(STOP_TIMEOUT, reply_rx).await, Ok(Ok(()))) {
            let _ = tokio::time::timeout(Duration::from_secs(1), &mut join).await;
        }
        if !join.is_finished() {
            log::error!("supervisor did not stop in time, aborting it");
            join.abort();
            force_cleanup();
        }
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

pub(crate) fn spawn() -> SupervisorHandle {
    let (tx, rx) = mpsc::unbounded_channel();
    *shared().supervisor_tx.lock() = Some(tx.clone());
    let supervisor = Supervisor {
        rx,
        backoff: Backoff::default(),
        limiter: RateLimiter::new(RECONNECTS_PER_WINDOW, RECONNECT_WINDOW),
        first: true,
        prefer_token: false,
        stop_reply: None,
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
    limiter: RateLimiter,
    /// No attempt finished yet (state `connecting` instead of `reconnecting`).
    first: bool,
    /// Stored credentials were rejected: try the OAuth access token.
    prefer_token: bool,
    stop_reply: Option<oneshot::Sender<()>>,
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
        explicit_filter: session.filter_explicit_content(),
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
        if let Some(reply) = self.stop_reply.take() {
            let _ = reply.send(());
        }
    }

    fn stop_requested(&mut self, msg: Option<Msg>) -> Option<Msg> {
        match msg {
            None => None,
            Some(Msg::Stop { reply }) => {
                self.stop_reply = Some(reply);
                None
            }
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
        let stored = shared().credentials.lock().clone();
        let token = shared().access_token.lock().clone();
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
        if !self.first && !self.limiter.try_acquire(std::time::Instant::now()) {
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
        let end = {
            let attempt = connector::connect(&session, credentials, &settings);
            tokio::pin!(attempt);
            loop {
                tokio::select! {
                    r = &mut attempt => break AttemptEnd::Done(r),
                    msg = self.rx.recv() => match self.stop_requested(msg) {
                        None => break AttemptEnd::Abort(Phase::Exit),
                        Some(Msg::Network { available: false, .. }) => break AttemptEnd::Abort(Phase::Gate),
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
                if e.code == ErrorCode::BadCredentials && used_stored && shared().access_token.lock().is_some() {
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
                    Some(Msg::Settings { .. }) if super::settings().offline => return Phase::Gate,
                    Some(_) => {}
                },
            }
        }
    }

    fn declare_online(&mut self, live: &Live, user: Option<User>) {
        state::set_online(Some(live.session.clone()));
        self.backoff.reset();
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
        log::info!("session online");
        connect::after_online(live.generation);
    }

    /// Intentional teardown followed by an immediate attempt (playback is restored).
    async fn reconnect(&mut self, live: Live) -> Phase {
        log::info!("reconnecting");
        connector::teardown(live, true).await;
        Phase::Connect
    }

    async fn online(&mut self, mut live: Live) -> Phase {
        let used = shared().credentials.lock().clone();
        if let Some(stored) = connector::harvest_credentials(&live.session, used.as_ref()).await {
            *shared().credentials.lock() = Some(stored);
        }
        let mut declared = false;
        let mut user_known = false;
        let mut verify_ticks = 0u32;
        let mut verify = interval(VERIFY_INTERVAL);
        verify.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut health = interval_at(Instant::now() + HEALTH_INTERVAL, HEALTH_INTERVAL);
        health.set_missed_tick_behavior(MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                _ = &mut live.task => {
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
                    }
                }
                _ = health.tick(), if declared => {
                    if let Some(e) = connector::premium_error(&live.session) {
                        connector::teardown(live, false).await;
                        return Phase::Halted(e);
                    }
                    if live.session.is_invalid() || player_host::dead_generation().is_some() {
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
                        if outage.is_some_and(|d| d > OUTAGE_RECONNECT) || live.session.is_invalid() {
                            self.backoff.reset();
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::Network { available: false, .. }) => {}
                    Some(Msg::Reconnect) => {
                        if live.session.is_invalid() {
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::Settings { old }) => {
                        let new = super::settings();
                        if new.offline {
                            connector::teardown(live, false).await;
                            return Phase::Gate;
                        }
                        if old.autoplay != new.autoplay {
                            if let Err(e) = live.spirc.set_autoplay(new.autoplay) {
                                log::warn!("autoplay setting not applied: {e}");
                            }
                        }
                    }
                    Some(Msg::PlayerDead(generation)) => {
                        if player_host::dead_generation() == Some(generation) {
                            return self.reconnect(live).await;
                        }
                    }
                    Some(Msg::Stop { .. }) => {}
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
                    self.limiter.reset();
                    self.backoff.reset();
                    return Phase::Connect;
                }
                Some(Msg::Reconnect) => {
                    self.limiter.reset();
                    self.backoff.reset();
                    return Phase::Connect;
                }
                Some(_) => {}
            }
        }
    }
}
