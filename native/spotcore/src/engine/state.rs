//! Process-wide engine state shared by the RPC handlers, the supervisor and the public API.
//!
//! `status` is the single source of truth of the session state machine; every change that is
//! visible in the `SessionEvent` JSON is emitted exactly once (`update_status`).

use super::backoff::RateLimiter;
use super::supervisor::{self, Msg};
use crate::error::AppError;
use crate::models::{EngineSettings, SessionEvent, SessionState, StoredCredentials, User};
use crate::{bridge, connect, events, runtime};
use librespot_core::Session;
use parking_lot::{Mutex, RwLock};
use std::sync::LazyLock;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, watch};

#[derive(Debug, Clone, Default)]
pub(crate) struct Status {
    pub state: SessionState,
    pub error: Option<AppError>,
    pub user: Option<User>,
    pub next_retry_ms: Option<u64>,
    /// Incremented whenever a connect attempt finished (success or failure).
    pub epoch: u64,
    /// `Offline` because of the user setting (not because the network is down).
    pub offline_mode: bool,
    /// A stop/logout is in progress: pending `session.start` calls give up.
    pub stopping: bool,
}

impl Status {
    fn event(&self) -> SessionEvent {
        SessionEvent {
            state: self.state,
            error: self.error.clone(),
            user: self.user.clone(),
            device_id: runtime::is_initialized().then(|| runtime::config().device_id.clone()),
            next_retry_ms: self.next_retry_ms,
        }
    }
}

/// What the supervisor logs in with (docs/ARCHITECTURE.md §4.2).
#[derive(Debug, Default)]
pub(crate) struct Login {
    /// The latest reusable credentials (from Kotlin or harvested after a login).
    pub credentials: Option<StoredCredentials>,
    /// The OAuth access token of a fresh login (until reusable credentials exist).
    pub access_token: Option<String>,
    /// Incremented whenever a supervisor is retired (stop, restart, logout). A supervisor
    /// stores and reports what it harvested, and records its username, only while the
    /// generation it was started with is current: one that is being stopped can never bring the
    /// previous account back (or hand its credentials to the next login).
    pub generation: u64,
}

#[derive(Debug, Clone)]
pub(crate) struct NetworkState {
    pub available: bool,
    pub metered: bool,
    pub lost_at: Option<Instant>,
    /// Android's handle of the default network (`Network.getNetworkHandle`), when Kotlin sent one.
    pub handle: Option<i64>,
}

impl NetworkState {
    /// Applies a report from Android (`session.setNetworkAvailable`) made at `now`. Returns what
    /// the supervisor must hear: `Msg::Network` when the availability flipped (with the outage
    /// when it came back), `Msg::NetworkChanged` when another network became the default while
    /// one stayed available (the connections opened on the previous one may be dead without
    /// any error, docs/ARCHITECTURE.md §4.2), else nothing (`metered` only sets the bitrate).
    pub(crate) fn report(&mut self, available: bool, metered: bool, handle: Option<i64>, now: Instant) -> Option<Msg> {
        self.metered = metered;
        let previous = std::mem::replace(&mut self.handle, handle);
        if available == self.available {
            let switched = available && previous.is_some() && handle.is_some() && previous != handle;
            return switched.then_some(Msg::NetworkChanged);
        }
        self.available = available;
        let outage = if available {
            Some(self.lost_at.take().map_or(Duration::MAX, |lost| now.saturating_duration_since(lost)))
        } else {
            self.lost_at = Some(now);
            None
        };
        Some(Msg::Network { available, outage })
    }
}

pub(crate) struct Shared {
    pub status: watch::Sender<Status>,
    status_json: Mutex<String>,
    pub online: watch::Sender<bool>,
    pub settings: RwLock<EngineSettings>,
    pub oauth: Mutex<Option<(String, i64)>>,
    pub username: RwLock<Option<String>>,
    /// The session while the engine is online.
    pub live_session: RwLock<Option<Session>>,
    pub login: Mutex<Login>,
    /// Reconnect attempts of all supervisors (a restart by Kotlin doesn't start a new burst).
    pub reconnects: Mutex<RateLimiter>,
    pub network: Mutex<NetworkState>,
    /// Marked changed whenever the network availability flipped (wakes `await_offline_index`).
    pub network_changes: watch::Sender<()>,
    /// Message channel of the running supervisor (quick access without the supervisor slot).
    pub supervisor_tx: Mutex<Option<mpsc::UnboundedSender<Msg>>>,
    /// The running supervisor; serialises start / stop / logout.
    pub supervisor: tokio::sync::Mutex<Option<super::supervisor::SupervisorHandle>>,
}

pub(crate) static SHARED: LazyLock<Shared> = LazyLock::new(|| Shared {
    status: watch::Sender::new(Status::default()),
    status_json: Mutex::new(String::new()),
    online: watch::Sender::new(false),
    settings: RwLock::new(EngineSettings::default()),
    oauth: Mutex::new(None),
    username: RwLock::new(None),
    live_session: RwLock::new(None),
    login: Mutex::new(Login::default()),
    reconnects: Mutex::new(supervisor::reconnect_limiter()),
    network: Mutex::new(NetworkState { available: true, metered: false, lost_at: None, handle: None }),
    network_changes: watch::Sender::new(()),
    supervisor_tx: Mutex::new(None),
    supervisor: tokio::sync::Mutex::new(None),
});

pub(crate) fn shared() -> &'static Shared {
    &SHARED
}

/// Applies `f` to the status, publishes it and emits a `session` event if the JSON changed.
pub(crate) fn update_status(f: impl FnOnce(&mut Status)) {
    let s = shared();
    let state_changed;
    {
        let mut last_json = s.status_json.lock();
        let mut next = s.status.borrow().clone();
        let previous_state = next.state;
        f(&mut next);
        state_changed = previous_state != next.state;
        match serde_json::to_string(&next.event()) {
            Ok(json) if json != *last_json => {
                bridge::post_event(events::SESSION, &json);
                *last_json = json;
            }
            Ok(_) => {}
            Err(e) => log::error!("session event serialisation failed: {e}"),
        }
        s.status.send_replace(next);
    }
    if state_changed && runtime::is_initialized() {
        connect::on_engine_state_changed();
    }
}

/// The current login generation (see [`Login::generation`]).
pub(crate) fn login_generation() -> u64 {
    shared().login.lock().generation
}

/// Retires the running supervisor (see [`Login::generation`]).
pub(crate) fn bump_login_generation() {
    shared().login.lock().generation += 1;
}

/// Stores credentials harvested by the supervisor of `generation`, unless it was retired
/// meanwhile. Returns whether they are new (the caller then emits the `credentials` event).
pub(crate) fn store_harvested(generation: u64, credentials: StoredCredentials) -> bool {
    let mut login = shared().login.lock();
    if login.generation != generation {
        log::info!("discarding credentials harvested by a retired supervisor");
        return false;
    }
    let changed = login.credentials.as_ref() != Some(&credentials);
    login.credentials = Some(credentials);
    changed
}

/// Records the username of the session of the supervisor of `generation` (unless retired).
pub(crate) fn record_username(generation: u64, session: &Session) {
    let username = session.username();
    if username.is_empty() {
        return;
    }
    // Checked under the login lock: `forget_account` clears the username after bumping.
    let login = shared().login.lock();
    if login.generation == generation {
        *shared().username.write() = Some(username);
    }
}

/// Forgets everything that belongs to the logged-in account: the login (credentials and access
/// token, with a new generation), the OAuth token and the username.
pub(crate) fn forget_account() {
    {
        let mut login = shared().login.lock();
        login.credentials = None;
        login.access_token = None;
        login.generation += 1;
        forget_account_state();
    }
    shared().reconnects.lock().reset();
}

/// The per-account state besides the login (a new account starts without it).
pub(crate) fn forget_account_state() {
    *shared().oauth.lock() = None;
    *shared().username.write() = None;
}

/// Sets (or clears) the online session.
pub(crate) fn set_online(session: Option<Session>) {
    let s = shared();
    let online = session.is_some();
    *s.live_session.write() = session;
    s.online.send_if_modified(|v| {
        let changed = *v != online;
        *v = online;
        changed
    });
}

pub(crate) fn network_available() -> bool {
    shared().network.lock().available
}

pub(crate) fn send_to_supervisor(msg: Msg) -> bool {
    match shared().supervisor_tx.lock().as_ref() {
        Some(tx) => tx.send(msg).is_ok(),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn creds(blob: &str) -> StoredCredentials {
        StoredCredentials { username: "a".into(), auth_type: 1, auth_data: blob.into() }
    }

    #[test]
    fn network_reports() {
        let t0 = Instant::now();
        let mut net = NetworkState { available: true, metered: false, lost_at: None, handle: Some(1) };
        // Nothing new for the supervisor: the same network, or only the metered flag changed.
        assert!(net.report(true, false, Some(1), t0).is_none());
        assert!(net.report(true, true, Some(1), t0).is_none());
        assert!(net.metered);
        // Another default network while one stayed available (Wi-Fi without internet -> LTE).
        assert!(matches!(net.report(true, true, Some(2), t0), Some(Msg::NetworkChanged)));
        assert_eq!(net.handle, Some(2));
        // Not knowing the previous network is no change.
        net.handle = None;
        assert!(net.report(true, true, Some(3), t0).is_none());
        // Lost, then back after 30 s on another network: a return with the outage.
        assert!(matches!(net.report(false, false, None, t0), Some(Msg::Network { available: false, outage: None })));
        assert!(net.report(false, false, None, t0 + Duration::from_secs(10)).is_none());
        let back = net.report(true, false, Some(4), t0 + Duration::from_secs(30));
        assert!(matches!(back, Some(Msg::Network { available: true, outage: Some(d) }) if d == Duration::from_secs(30)));
        assert!(net.lost_at.is_none());
    }

    #[test]
    fn a_retired_supervisor_cannot_store_credentials() {
        let mine = login_generation();
        assert!(store_harvested(mine, creds("A1")), "new credentials are reported");
        assert!(!store_harvested(mine, creds("A1")), "unchanged credentials are not");
        assert_eq!(shared().login.lock().credentials, Some(creds("A1")));

        // Stop / restart / logout retires the supervisor: what it harvests now is dropped (and
        // not reported, so Kotlin can't take it for the next login's credentials).
        bump_login_generation();
        assert!(!store_harvested(mine, creds("A2")));
        assert_eq!(shared().login.lock().credentials, Some(creds("A1")));

        // The next supervisor stores normally.
        assert!(store_harvested(login_generation(), creds("B1")));
        assert_eq!(shared().login.lock().credentials, Some(creds("B1")));
        shared().login.lock().credentials = None;
    }
}
