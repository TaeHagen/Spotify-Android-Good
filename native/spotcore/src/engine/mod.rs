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
mod explicit;
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
use std::future::Future;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use supervisor::Msg;
use tokio::sync::watch;

/// How long `session.start` waits for the first outcome.
const START_TIMEOUT: Duration = Duration::from_secs(90);
const TOKEN_TIMEOUT: Duration = Duration::from_secs(10);
/// An OAuth token closer than this to its expiry is not handed out.
const TOKEN_EXPIRY_MARGIN_MS: i64 = 30_000;
/// `session.stop` is bounded to 10 s (docs/ARCHITECTURE.md §4.2; Kotlin waits 15 s): the
/// supervisor's graceful stop, an abort, the Player drop, and a margin for the rest.
const STOP_BOUND_MS: u128 = 10_000;
const _: () = assert!(
    supervisor::STOP_TIMEOUT.as_millis() + supervisor::ABORT_GRACE.as_millis() + player_host::PLAYER_DROP_TIMEOUT.as_millis()
        < STOP_BOUND_MS - 500
);

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

pub async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "session.start" => start(parse_args(args)?).await,
        "session.stop" => {
            let release_player = parse_args::<StopArgs>(args)?.release_player;
            to_completion("session.stop", stop(release_player)).await
        }
        "session.setNetworkAvailable" => set_network_available(parse_args(args)?),
        "session.updateSettings" => update_settings(parse_args(args)?),
        "session.logout" => to_completion("session.logout", logout()).await,
        "session.zeroconfLogin" => zeroconf::login(parse_args(args)?).await,
        "session.token" => token().await,
        "session.setOAuthToken" => set_oauth_token(parse_args(args)?),
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

// ---------------------------------------------------------------------------------------------
// session.start / stop
// ---------------------------------------------------------------------------------------------

/// Runs lifecycle work (start's configuration step, stop, logout) as its own task, so it always
/// runs to the end. `nativeCancel` aborts the RPC task, which then only stops waiting (the call
/// still answers CANCELLED). An aborted logout used to leave the account's credentials in memory
/// and its caches on disk, and an aborted stop left `stopping` set and the Player bound.
async fn to_completion<T, F>(what: &'static str, work: F) -> AppResult<T>
where
    T: Send + 'static,
    F: Future<Output = AppResult<T>> + Send + 'static,
{
    match tokio::spawn(work).await {
        Ok(result) => result,
        Err(e) => Err(AppError::internal(format!("{what}: {e}"))),
    }
}

/// `status.stopping` while a stop or logout runs: pending `session.start` calls give up. Reset
/// when dropped, so no exit path (an error, a panic) can leave it set.
struct Stopping;

impl Stopping {
    fn begin() -> Self {
        update_status(|s| s.stopping = true);
        Stopping
    }
}

impl Drop for Stopping {
    fn drop(&mut self) {
        update_status(|s| s.stopping = false);
    }
}

/// What a `session.start` does to the login (docs/ARCHITECTURE.md §4.2).
#[derive(Debug, PartialEq)]
struct LoginPlan {
    credentials: Option<StoredCredentials>,
    access_token: Option<String>,
    /// Credentials or token are new: the supervisor restarts with them.
    changed: bool,
    /// The account may differ from the previous one: its OAuth token and username are dropped.
    new_account: bool,
}

/// * token only: a fresh login. Stored reusable credentials (possibly of another account) are
///   dropped, so the token is what logs in.
/// * credentials only: an earlier access token is dropped, so a rejection of these credentials
///   can't fall back on a token that may belong to another account.
/// * both: both are kept (stored credentials first, the token as the fallback).
/// * neither: the login stays as it is (a repeated start).
///
/// `known_user`: the username of the last online session, if the stored credentials don't say.
fn plan_login(
    current: &state::Login,
    known_user: Option<&str>,
    credentials: Option<StoredCredentials>,
    token: Option<String>,
) -> LoginPlan {
    let fresh = credentials.is_none() && token.is_some();
    let (next_credentials, next_token) = match (credentials, token) {
        (None, None) => (current.credentials.clone(), current.access_token.clone()),
        (Some(c), None) => (Some(c), None),
        (None, Some(t)) => (None, Some(t)),
        (Some(c), Some(t)) => (Some(c), Some(t)),
    };
    let credentials_changed = next_credentials != current.credentials;
    let token_changed = next_token.is_some() && next_token != current.access_token;
    let changed = credentials_changed || token_changed;
    let previous_user = current.credentials.as_ref().map(|c| c.username.as_str()).or(known_user);
    let new_account = if fresh {
        changed
    } else {
        match (&next_credentials, previous_user) {
            (Some(next), Some(previous)) => next.username != previous,
            (Some(_), None) => credentials_changed,
            (None, _) => false,
        }
    };
    LoginPlan { credentials: next_credentials, access_token: next_token, changed, new_account }
}

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
    // Configuring (and restarting) the engine always completes; only the wait is cancellable.
    let epoch = to_completion("session.start", configure_start(args)).await?;
    await_outcome(epoch).await
}

/// The configuration step of `session.start`, under the supervisor lock. Returns the status
/// epoch to wait from.
async fn configure_start(args: StartArgs) -> AppResult<u64> {
    let token = args.access_token.filter(|t| !t.trim().is_empty());
    let mut slot = shared().supervisor.lock().await;

    if let Some(new) = args.settings {
        apply_settings(new);
    }
    if let Some(volume) = args.initial_volume {
        player_host::init_mixer(volume.min(u16::MAX as u32) as u16);
    }

    let known_user = shared().username.read().clone();
    let plan = plan_login(&shared().login.lock(), known_user.as_deref(), args.credentials, token);
    if plan.credentials.is_none() && plan.access_token.is_none() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "No credentials"));
    }

    let status = shared().status.borrow().clone();
    let restart = match slot.as_ref() {
        None => true,
        Some(h) => h.is_finished() || status.state == SessionState::Error || plan.changed,
    };
    // The previous supervisor is retired first (whatever it harvests while stopping is neither
    // kept nor reported) and gone before the new login is installed, so it never uses it.
    if restart {
        state::bump_login_generation();
        if let Some(old) = slot.take() {
            old.stop().await;
        }
    }
    {
        let mut login = shared().login.lock();
        if plan.changed {
            login.credentials = plan.credentials;
        }
        login.access_token = plan.access_token;
    }
    if plan.changed {
        // A new login isn't throttled by the previous one's failed reconnects.
        shared().reconnects.lock().reset();
    }
    if plan.new_account {
        state::forget_account_state();
    }
    if restart {
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
    Ok(epoch)
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

/// `session.stop` (run to completion, see [`to_completion`]). Bounded by
/// [`supervisor::STOP_TIMEOUT`] + [`supervisor::ABORT_GRACE`] + the Player drop, under 10 s,
/// plus the time a concurrent start / stop / logout holds the supervisor lock.
async fn stop(release_player: bool) -> AppResult<Value> {
    let stopping = Stopping::begin();
    let mut slot = shared().supervisor.lock().await;
    stop_locked(&mut slot, release_player).await;
    drop(stopping);
    drop(slot);
    ok()
}

/// Stops the supervisor, then leaves the engine Stopped. The caller holds the supervisor lock
/// for the whole time, so a following `session.start` waits until this teardown is complete
/// (nothing of the old session can unbind the Player from a new one).
async fn stop_locked(slot: &mut Option<supervisor::SupervisorHandle>, release_player: bool) {
    // Retire the supervisor: credentials it harvests while stopping are neither kept nor
    // reported (Kotlin could take them for the next login's).
    state::bump_login_generation();
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
}

/// `session.logout` (run to completion, see [`to_completion`]). The account is forgotten before
/// the teardown, and everything runs under the supervisor lock, so a following `session.start`
/// (the next login) waits until the caches are gone and starts from a clean state.
async fn logout() -> AppResult<Value> {
    let stopping = Stopping::begin();
    let mut slot = shared().supervisor.lock().await;
    // From here on no connect attempt can log in with this account, and nothing the stopping
    // supervisor harvests is kept (the login generation changed).
    state::forget_account();
    stop_locked(&mut slot, true).await;
    connect::reset();
    // Cached metadata carries the account's country / filter in `playable`.
    crate::catalog::metadata::clear_cache();
    *CATALOG_FILTER.lock() = None;
    let dirs = [runtime::credentials_dir(), runtime::streaming_cache_dir(), runtime::librespot_tmp_dir()];
    let cleanup = tokio::task::spawn_blocking(move || {
        for dir in dirs {
            match std::fs::remove_dir_all(&dir) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => log::warn!("could not delete {}: {e}", dir.display()),
            }
        }
    })
    .await;
    drop(stopping);
    drop(slot);
    cleanup.map_err(|e| AppError::internal(format!("logout cleanup: {e}")))?;
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
    if old.filter_explicit != new.filter_explicit {
        sync_explicit_filter();
    }
}

/// The explicit filter the catalog's cached metadata was computed with (`playable` is baked in).
static CATALOG_FILTER: parking_lot::Mutex<Option<bool>> = parking_lot::const_mutex(None);

/// Applies "Hide explicit content" ([`explicit`]) to the sessions the Player and the catalog use
/// (the live one, and the offline one the Player plays with while not online), tells the Player
/// when its filter changed (it then skips a loaded explicit track), and drops cached catalog
/// metadata computed with the other value. Cheap; idempotent.
pub(crate) fn sync_explicit_filter() {
    let filter = settings().filter_explicit;
    let live = try_session().filter(|_| is_online());
    let live_changed = live.as_ref().and_then(|s| explicit::apply(s, filter));
    let offline_changed = player_host::apply_explicit_filter_offline(filter);
    let player_changed = if live.is_some() { live_changed } else { offline_changed };
    if let Some(on) = player_changed {
        log::info!("explicit filter {}", if on { "on" } else { "off" });
        player_host::emit_explicit_filter(on);
    }
    if let Some(session) = &live {
        let effective = session.filter_explicit_content();
        let mut last = CATALOG_FILTER.lock();
        if last.is_some_and(|v| v != effective) {
            crate::catalog::metadata::clear_cache();
        }
        *last = Some(effective);
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
    let login = shared().login.lock();
    if login.credentials.is_none() && login.access_token.is_none() {
        // Logged out meanwhile (a late call of a login that was interrupted): not kept.
        log::info!("ignoring an OAuth token while logged out");
        return ok();
    }
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

    fn creds(username: &str, blob: &str) -> StoredCredentials {
        StoredCredentials { username: username.into(), auth_type: 1, auth_data: blob.into() }
    }

    fn login(credentials: Option<StoredCredentials>, access_token: Option<&str>) -> state::Login {
        state::Login { credentials, access_token: access_token.map(Into::into), generation: 0 }
    }

    #[test]
    fn a_fresh_token_login_never_uses_stored_credentials() {
        // Account A's credentials are still stored (e.g. an interrupted logout); B logs in.
        let plan = plan_login(&login(Some(creds("a", "A1")), None), Some("a"), None, Some("tokenB".into()));
        assert_eq!(plan.credentials, None, "A's credentials are dropped");
        assert_eq!(plan.access_token.as_deref(), Some("tokenB"));
        assert!(plan.changed && plan.new_account);

        // The same token again (a repeated start of the same login) changes nothing.
        let plan = plan_login(&login(None, Some("tokenB")), None, None, Some("tokenB".into()));
        assert!(!plan.changed && !plan.new_account);
    }

    #[test]
    fn stored_credentials_drop_an_earlier_token() {
        // A zeroconf login (C's credentials) after a token login of A: A's token must not be
        // the fallback if C's credentials are rejected.
        let plan = plan_login(&login(Some(creds("a", "A1")), Some("tokenA")), Some("a"), Some(creds("c", "C1")), None);
        assert_eq!(plan.credentials, Some(creds("c", "C1")));
        assert_eq!(plan.access_token, None);
        assert!(plan.changed && plan.new_account);

        // The same account's credentials (a restart): no restart for that alone, same account.
        let plan = plan_login(&login(Some(creds("a", "A1")), Some("tokenA")), Some("a"), Some(creds("a", "A1")), None);
        assert_eq!(plan.access_token, None);
        assert!(!plan.changed && !plan.new_account);

        // Newer credentials of the same account: restart, but the account state stays.
        let plan = plan_login(&login(Some(creds("a", "A1")), None), Some("a"), Some(creds("a", "A2")), None);
        assert!(plan.changed && !plan.new_account);

        // First start after a token login that never got online: compared with the last user.
        let plan = plan_login(&login(None, Some("tokenA")), Some("a"), Some(creds("b", "B1")), None);
        assert!(plan.changed && plan.new_account);
    }

    #[test]
    fn a_repeated_start_keeps_the_login() {
        let current = login(Some(creds("a", "A1")), Some("tokenA"));
        let plan = plan_login(&current, Some("a"), None, None);
        assert_eq!(plan.credentials, Some(creds("a", "A1")));
        assert_eq!(plan.access_token.as_deref(), Some("tokenA"));
        assert!(!plan.changed && !plan.new_account);

        let plan = plan_login(&login(None, None), None, None, None);
        assert!(plan.credentials.is_none() && plan.access_token.is_none(), "NOT_LOGGED_IN");

        // Both: stored credentials first, the token as the fallback.
        let plan = plan_login(&login(None, None), None, Some(creds("a", "A1")), Some("tokenA".into()));
        assert_eq!(plan.access_token.as_deref(), Some("tokenA"));
        assert!(plan.credentials.is_some() && plan.changed);
    }

    #[tokio::test]
    async fn lifecycle_work_survives_a_cancelled_call() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::Arc;

        let done = Arc::new(AtomicBool::new(false));
        let flag = done.clone();
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        // The RPC task, as rpc::dispatch spawns it.
        let call = tokio::spawn(to_completion("test", async move {
            let _ = started_tx.send(());
            tokio::time::sleep(Duration::from_millis(50)).await;
            flag.store(true, Ordering::SeqCst);
            ok()
        }));
        started_rx.await.expect("work started");
        // nativeCancel: the RPC task is aborted mid-way…
        call.abort();
        assert!(call.await.expect_err("aborted").is_cancelled());
        assert!(!done.load(Ordering::SeqCst));
        // …but the lifecycle work runs to the end.
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(done.load(Ordering::SeqCst), "the work was not aborted with the call");

        // A panic in the work is an INTERNAL error, not a lost call.
        let r: AppResult<Value> = to_completion("test", async { panic!("boom") }).await;
        assert_eq!(r.err().map(|e| e.code), Some(ErrorCode::Internal));
    }
}
