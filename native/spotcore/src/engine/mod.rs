//! Engine: owns the librespot `Session`, `Spirc` and `Player` and their lifecycle
//! (docs/ARCHITECTURE.md §4).
//!
//! * [`supervisor`] — one task per running engine: connect, verify (Premium, user), watch, and
//!   reconnect with backoff; offline mode and network gating.
//! * [`connector`] — one Session + Spirc attempt and its bounded teardown.
//! * [`player_host`] — the Player (created once, re-bound per Session) and the mixer.
//! * [`zeroconf`] — `session.zeroconfLogin`.
//! * `session.*` RPC handlers live in this file.
//!
//! Public API used by the other modules (stable contract):
//! * [`session`] / [`try_session`] — the current connected session.
//! * [`username`], [`is_online`], [`settings`], [`online_watch`], [`oauth_token`].
//! * [`handle`] — `session.*` RPC methods.

mod backoff;
mod config;
mod connector;
pub(crate) mod player_host;
mod state;
mod supervisor;
mod zeroconf;

use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{EngineSettings, SessionState, StoredCredentials};
use crate::rpc::{ok, parse_args, to_value};
use crate::{connect, runtime};
use librespot_core::Session;
use serde::Deserialize;
use serde_json::{json, Value};
use state::{shared, update_status};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use supervisor::Msg;
use tokio::sync::watch;

/// How long `session.start` waits for the first outcome.
const START_TIMEOUT: Duration = Duration::from_secs(90);
const TOKEN_TIMEOUT: Duration = Duration::from_secs(10);
/// An OAuth token closer than this to its expiry is not handed out.
const TOKEN_EXPIRY_MARGIN_MS: i64 = 30_000;

/// The currently connected session, or `NotConnected`.
pub fn session() -> AppResult<Session> {
    try_session().ok_or_else(AppError::not_connected)
}

/// The currently connected session, if any.
pub fn try_session() -> Option<Session> {
    shared().live_session.read().clone().filter(|s| !s.is_invalid())
}

/// Canonical username of the logged-in user, if a session is (or was recently) online.
pub fn username() -> Option<String> {
    shared().username.read().clone()
}

pub fn is_online() -> bool {
    *shared().online.borrow() && try_session().is_some()
}

/// Becomes `true` whenever a session is online. Useful for work that must wait for a connection.
pub fn online_watch() -> watch::Receiver<bool> {
    shared().online.subscribe()
}

/// Whether a connect attempt is in flight: `Connecting`, or `Reconnecting` outside a backoff
/// wait, not stopping, with the network up and offline mode off, so the session may be Online
/// within seconds. `connect` holds playback commands briefly meanwhile instead of routing them
/// offline.
pub(crate) fn is_connecting() -> bool {
    let (session_state, retry_pending, stopping) = {
        let s = shared().status.borrow();
        (s.state, s.next_retry_ms.is_some(), s.stopping)
    };
    let attempt = match session_state {
        SessionState::Connecting => true,
        SessionState::Reconnecting => !retry_pending,
        _ => false,
    };
    attempt && !stopping && !settings().offline && state::network_available()
}

/// Changes with every session state change (subscribe before checking [`is_connecting`]).
pub(crate) fn status_watch() -> watch::Receiver<impl Send + Sync> {
    shared().status.subscribe()
}

pub fn settings() -> EngineSettings {
    shared().settings.read().clone()
}

/// The Connect device name (settings, with a fallback for an empty name).
pub(crate) fn device_name() -> String {
    config::device_name(&settings())
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// The OAuth access token handed over at login (`session.setOAuthToken`) and its expiry
/// (epoch ms), if still valid. Used by catalog as a pathfinder fallback token.
pub fn oauth_token() -> Option<(String, i64)> {
    shared().oauth.lock().clone().filter(|(_, expires)| *expires > now_ms() + TOKEN_EXPIRY_MARGIN_MS)
}

/// The reusable credentials the engine holds for the logged-in user: the stored ones Kotlin
/// passed, or, failing that, the ones harvested from the live session. Used by the ZeroConf
/// client to build the stored-credentials login blob (docs/ARCHITECTURE.md §8).
pub fn reusable_credentials() -> Option<StoredCredentials> {
    if let Some(c) = shared().credentials.lock().clone() {
        return Some(c);
    }
    let session = try_session()?;
    config::from_session(session.username(), session.auth_data())
}

/// Mints a fresh login5 access token (keymaster client, `streaming` scope) for the logged-in
/// user, bounded by [`TOKEN_TIMEOUT`]. Used for the ZeroConf `accesstoken` login path.
pub async fn access_token() -> AppResult<String> {
    let session = session()?;
    let token = tokio::time::timeout(TOKEN_TIMEOUT, session.login5().auth_token()).await??;
    Ok(token.access_token)
}

pub async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "session.start" => start(parse_args(args)?).await,
        "session.stop" => stop(parse_args::<StopArgs>(args)?.release_player).await,
        "session.setNetworkAvailable" => set_network_available(parse_args(args)?),
        "session.updateSettings" => update_settings(parse_args(args)?),
        "session.logout" => logout().await,
        "session.zeroconfLogin" => zeroconf::login(parse_args(args)?).await,
        "session.token" => token().await,
        "session.setOAuthToken" => set_oauth_token(parse_args(args)?),
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

// ---------------------------------------------------------------------------------------------
// session.start / stop
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StartArgs {
    #[serde(default)]
    credentials: Option<StoredCredentials>,
    #[serde(default)]
    access_token: Option<String>,
    #[serde(default)]
    settings: Option<EngineSettings>,
    #[serde(default)]
    initial_volume: Option<u32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StopArgs {
    #[serde(default)]
    release_player: bool,
}

async fn start(args: StartArgs) -> AppResult<Value> {
    if let Some(c) = &args.credentials {
        config::to_librespot(c)?; // validate before anything changes
    }
    let token = args.access_token.filter(|t| !t.trim().is_empty());
    let mut slot = shared().supervisor.lock().await;

    if let Some(new) = args.settings {
        apply_settings(new);
    }
    if let Some(volume) = args.initial_volume {
        player_host::init_mixer(volume.min(u16::MAX as u32) as u16);
    }

    let creds_changed = {
        let mut stored = shared().credentials.lock();
        let changed = args.credentials.is_some() && *stored != args.credentials;
        if args.credentials.is_some() {
            *stored = args.credentials.clone();
        }
        changed
    };
    let token_changed = {
        let mut current = shared().access_token.lock();
        let changed = token.is_some() && *current != token;
        if token.is_some() {
            *current = token;
        }
        changed
    };
    if shared().credentials.lock().is_none() && shared().access_token.lock().is_none() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "No credentials"));
    }

    let status = shared().status.borrow().clone();
    let restart = match slot.as_ref() {
        None => true,
        Some(h) => h.is_finished() || status.state == SessionState::Error || creds_changed || token_changed,
    };
    if restart {
        if let Some(old) = slot.take() {
            old.stop().await;
        }
        update_status(|s| {
            s.state = SessionState::Connecting;
            s.error = None;
            s.next_retry_ms = None;
            s.stopping = false;
        });
        *slot = Some(supervisor::spawn());
    } else if let Some(h) = slot.as_ref() {
        if matches!(status.state, SessionState::Reconnecting | SessionState::Offline) {
            h.send(Msg::Reconnect);
        }
    }
    let epoch = shared().status.borrow().epoch;
    drop(slot);
    await_outcome(epoch).await
}

/// Waits until the session is online (Ok), failed (Err) or stopped (CANCELLED).
async fn await_outcome(start_epoch: u64) -> AppResult<Value> {
    let mut rx = shared().status.subscribe();
    let deadline = tokio::time::Instant::now() + START_TIMEOUT;
    loop {
        {
            let s = rx.borrow_and_update();
            if s.stopping {
                return Err(AppError::new(ErrorCode::Cancelled, "The session was stopped"));
            }
            match s.state {
                SessionState::Online => return ok(),
                SessionState::Error => {
                    return Err(s.error.clone().unwrap_or_else(|| AppError::internal("session error")));
                }
                SessionState::Offline if s.offline_mode => return ok(),
                SessionState::Offline => {
                    return Err(s.error.clone().unwrap_or_else(|| AppError::new(ErrorCode::Network, "No network connection")));
                }
                SessionState::Reconnecting if s.epoch > start_epoch => {
                    return Err(s.error.clone().unwrap_or_else(|| AppError::new(ErrorCode::Network, "Connection failed")));
                }
                SessionState::Stopped => {
                    return Err(AppError::new(ErrorCode::Cancelled, "The session was stopped"));
                }
                _ => {}
            }
        }
        match tokio::time::timeout_at(deadline, rx.changed()).await {
            Ok(Ok(())) => {}
            Ok(Err(_)) => return Err(AppError::internal("status channel closed")),
            Err(_) => return Err(AppError::new(ErrorCode::Network, "Timed out connecting to Spotify")),
        }
    }
}

async fn stop(release_player: bool) -> AppResult<Value> {
    // Pending session.start calls give up right away.
    update_status(|s| s.stopping = true);
    let mut slot = shared().supervisor.lock().await;
    if let Some(handle) = slot.take() {
        handle.stop().await;
    }
    state::set_online(None);
    connect::on_engine_stopped(release_player);
    if release_player {
        player_host::release().await;
    } else {
        // Keep the Player for offline playback, without the dead online session.
        player_host::detach_session();
    }
    update_status(|s| {
        s.state = SessionState::Stopped;
        s.error = None;
        s.user = None;
        s.next_retry_ms = None;
        s.offline_mode = false;
        s.stopping = false;
    });
    drop(slot);
    ok()
}

async fn logout() -> AppResult<Value> {
    stop(true).await?;
    *shared().credentials.lock() = None;
    *shared().access_token.lock() = None;
    *shared().oauth.lock() = None;
    *shared().username.write() = None;
    connect::reset();
    let dirs = [runtime::credentials_dir(), runtime::streaming_cache_dir(), runtime::librespot_tmp_dir()];
    tokio::task::spawn_blocking(move || {
        for dir in dirs {
            match std::fs::remove_dir_all(&dir) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => log::warn!("could not delete {}: {e}", dir.display()),
            }
        }
    })
    .await
    .map_err(|e| AppError::internal(format!("logout cleanup: {e}")))?;
    ok()
}

// ---------------------------------------------------------------------------------------------
// Network, settings, tokens
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
struct NetworkArgs {
    available: bool,
    #[serde(default)]
    metered: bool,
}

fn set_network_available(args: NetworkArgs) -> AppResult<Value> {
    let outage = {
        let mut net = shared().network.lock();
        net.metered = args.metered;
        let outage = if args.available {
            if net.available { None } else { Some(net.lost_at.take().map(|t| t.elapsed()).unwrap_or(Duration::MAX)) }
        } else {
            if net.available {
                net.lost_at = Some(std::time::Instant::now());
            }
            None
        };
        let changed = net.available != args.available;
        net.available = args.available;
        if !changed {
            return ok();
        }
        outage
    };
    log::info!("network {}", if args.available { "available" } else { "unavailable" });
    state::send_to_supervisor(Msg::Network { available: args.available, outage });
    ok()
}

/// Stores new settings and applies what can change at runtime.
fn apply_settings(new: EngineSettings) {
    let old = std::mem::replace(&mut *shared().settings.write(), new.clone());
    if old == new {
        return;
    }
    player_host::apply_settings(&old, &new);
    if old.offline != new.offline || old.autoplay != new.autoplay {
        state::send_to_supervisor(Msg::Settings { old: old.clone() });
    }
    if old.device_name != new.device_name {
        log::info!("device name change applies at the next connect");
        connect::on_engine_state_changed();
    }
}

fn update_settings(new: EngineSettings) -> AppResult<Value> {
    apply_settings(new);
    ok()
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PlayerSettingsArgs {
    bitrate: Option<u32>,
    normalize: Option<bool>,
    normalize_pregain: Option<crate::models::NormalizePregain>,
    gapless: Option<bool>,
}

/// `player.applySettings`: the playback subset of `EngineSettings`.
pub(crate) fn apply_player_settings(args: Value) -> AppResult<Value> {
    let a: PlayerSettingsArgs = parse_args(args)?;
    let mut next = settings();
    if let Some(b) = a.bitrate {
        next.bitrate = b;
    }
    if let Some(n) = a.normalize {
        next.normalize = n;
    }
    if let Some(p) = a.normalize_pregain {
        next.normalize_pregain = p;
    }
    if let Some(g) = a.gapless {
        next.gapless = g;
    }
    apply_settings(next);
    ok()
}

async fn token() -> AppResult<Value> {
    let session = session()?;
    let token = tokio::time::timeout(TOKEN_TIMEOUT, session.login5().auth_token()).await??;
    let issued = token.timestamp.duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or_else(|_| now_ms());
    let expires_at = issued + token.expires_in.as_millis() as i64;
    to_value(&json!({ "accessToken": token.access_token, "expiresAtMs": expires_at }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OAuthArgs {
    access_token: String,
    expires_at_ms: i64,
}

fn set_oauth_token(args: OAuthArgs) -> AppResult<Value> {
    let token = args.access_token.trim().to_string();
    *shared().oauth.lock() = (!token.is_empty()).then_some((token, args.expires_at_ms));
    ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_args_parse() {
        let a: StartArgs = parse_args(serde_json::json!({
            "credentials": {"username": "u", "authType": 1, "authData": "YWJj"},
            "settings": {"bitrate": 320, "offline": true},
            "initialVolume": 70000
        }))
        .expect("parse");
        assert_eq!(a.credentials.map(|c| c.username), Some("u".to_string()));
        let s = a.settings.expect("settings");
        assert_eq!(s.bitrate, 320);
        assert!(s.offline);
        assert!(s.autoplay, "defaults for missing fields");
        assert_eq!(a.initial_volume, Some(70000));
        let empty: StopArgs = parse_args(serde_json::json!({})).expect("parse");
        assert!(!empty.release_player);
    }

    #[tokio::test]
    async fn start_outcome_follows_the_status() {
        update_status(|s| {
            s.state = SessionState::Online;
            s.stopping = false;
        });
        assert!(await_outcome(0).await.is_ok());

        update_status(|s| {
            s.state = SessionState::Error;
            s.error = Some(AppError::new(ErrorCode::PremiumRequired, "premium"));
        });
        assert_eq!(await_outcome(0).await.err().map(|e| e.code), Some(ErrorCode::PremiumRequired));

        update_status(|s| {
            s.state = SessionState::Offline;
            s.offline_mode = true;
            s.error = None;
        });
        assert!(await_outcome(0).await.is_ok(), "offline mode counts as started");

        // A stale failure (older epoch) is not the answer; the next attempt's outcome is.
        let epoch = shared().status.borrow().epoch;
        update_status(|s| {
            s.state = SessionState::Reconnecting;
            s.offline_mode = false;
            s.error = Some(AppError::new(ErrorCode::Network, "old"));
        });
        let waiter = tokio::spawn(await_outcome(epoch));
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(!waiter.is_finished());
        update_status(|s| {
            s.error = Some(AppError::new(ErrorCode::Network, "new"));
            s.epoch += 1;
        });
        let result = waiter.await.expect("join");
        assert_eq!(result.err().map(|e| e.message), Some("new".to_string()));

        // A stop makes a pending start give up.
        update_status(|s| s.state = SessionState::Connecting);
        let waiter = tokio::spawn(await_outcome(shared().status.borrow().epoch));
        tokio::time::sleep(Duration::from_millis(20)).await;
        update_status(|s| s.stopping = true);
        assert_eq!(waiter.await.expect("join").err().map(|e| e.code), Some(ErrorCode::Cancelled));
        update_status(|s| {
            s.state = SessionState::Stopped;
            s.stopping = false;
        });
    }

    #[test]
    fn oauth_token_expiry() {
        *shared().oauth.lock() = Some(("t".into(), now_ms() + 3_600_000));
        assert_eq!(oauth_token().map(|t| t.0), Some("t".to_string()));
        *shared().oauth.lock() = Some(("t".into(), now_ms() + 1_000));
        assert!(oauth_token().is_none(), "about to expire");
        *shared().oauth.lock() = None;
    }
}
