//! Process-wide engine state shared by the RPC handlers, the supervisor and the public API.
//!
//! `status` is the single source of truth of the session state machine; every change that is
//! visible in the `SessionEvent` JSON is emitted exactly once (`update_status`).

use super::supervisor::Msg;
use crate::error::AppError;
use crate::models::{EngineSettings, SessionEvent, SessionState, StoredCredentials, User};
use crate::{bridge, connect, events, runtime};
use librespot_core::Session;
use parking_lot::{Mutex, RwLock};
use std::sync::LazyLock;
use std::time::Instant;
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

#[derive(Debug, Clone)]
pub(crate) struct NetworkState {
    pub available: bool,
    pub metered: bool,
    pub lost_at: Option<Instant>,
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
    /// The latest reusable credentials (from Kotlin or harvested after a login).
    pub credentials: Mutex<Option<StoredCredentials>>,
    /// The OAuth access token for the first login (until reusable credentials exist).
    pub access_token: Mutex<Option<String>>,
    pub network: Mutex<NetworkState>,
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
    credentials: Mutex::new(None),
    access_token: Mutex::new(None),
    network: Mutex::new(NetworkState { available: true, metered: false, lost_at: None }),
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

/// Sets (or clears) the online session.
pub(crate) fn set_online(session: Option<Session>) {
    let s = shared();
    let online = session.is_some();
    if let Some(session) = &session {
        let username = session.username();
        if !username.is_empty() {
            *s.username.write() = Some(username);
        }
    }
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
