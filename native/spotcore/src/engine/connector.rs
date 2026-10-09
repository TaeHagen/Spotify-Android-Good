//! One connection: Session + Spirc construction and teardown (docs/ARCHITECTURE.md §4.2,
//! research connect.md §13 lifecycle hazards).
//!
//! * A `Session` is single use: every attempt builds a new one; a failed or abandoned attempt is
//!   cleaned up with [`abandon`] (shutdown + dealer close + release from the Player).
//! * `Spirc::new` is bounded (30 s): DNS / TCP connect inside librespot have no timeout.
//! * The `Spirc` handle is kept until its task ended ([`teardown`]); the task join is bounded and
//!   on timeout the task is aborted and the dealer closed by hand (it holds a Session cycle).
//! * Connect visibility (`EngineSettings::connect_visible`): a hidden session is connected
//!   without Spirc ([`connect_hidden`]), so the phone is no Connect target, while catalog and
//!   downloads keep working. [`hide`] shuts Spirc down (the device leaves the cluster) and keeps
//!   the Session. Spirc can't be added to a connected Session again (`Spirc::new` performs the
//!   login, and a Session's dealer can only be launched once), so becoming visible reconnects.

use super::{config, player_host, state};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{EngineSettings, StoredCredentials};
use crate::connect;
use librespot_connect::Spirc;
use librespot_core::authentication::Credentials;
use librespot_core::session::SessionInvalidReason;
use librespot_core::Session;
use librespot_playback::mixer::Mixer;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::task::JoinHandle;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// How long a graceful Spirc shutdown may take (its disconnect, DELETE and dealer close are
/// each capped at 3 s, but on a working network the whole shutdown takes well under a second).
/// Afterwards the task is aborted and the dealer closed by hand. Kept short so that
/// `session.stop` stays within its 10 s bound (docs/ARCHITECTURE.md §4.2).
const TASK_JOIN_TIMEOUT: Duration = Duration::from_secs(4);
const DEALER_CLOSE_TIMEOUT: Duration = Duration::from_secs(2);
/// Upper bound of one connection teardown ([`teardown`], [`teardown_finished`], [`abandon`]).
pub(crate) const TEARDOWN_BOUND: Duration = TASK_JOIN_TIMEOUT.saturating_add(DEALER_CLOSE_TIMEOUT);

static GENERATION: AtomicU64 = AtomicU64::new(0);

/// A connected Session, with its Spirc while the device is visible to Spotify Connect.
pub(crate) struct Live {
    pub generation: u64,
    pub session: Session,
    pub device: Option<Device>,
}

/// The Spotify Connect side of a [`Live`] session.
pub(crate) struct Device {
    pub spirc: Arc<Spirc>,
    pub task: JoinHandle<()>,
}

fn next_generation() -> u64 {
    GENERATION.fetch_add(1, Ordering::Relaxed) + 1
}

/// Creates the Session for an attempt (not connected yet).
pub(crate) async fn prepare(settings: &EngineSettings) -> AppResult<Session> {
    let cache = config::build_cache(settings).await?;
    let session = Session::new(config::session_config(), Some(cache));
    // `connect` binds the Player to it before ProductInfo: filtered like the offline session.
    player_host::prepare_session(&session, settings);
    Ok(session)
}

/// Maps a failed `Spirc::new` (login) to the contract's error codes. AP login errors live in a
/// private librespot module, so they are recognised by their message (research core.md §2.4).
pub(crate) fn classify(session: &Session, e: librespot_core::Error) -> AppError {
    if let Some(err) = premium_error(session) {
        return err;
    }
    let msg = e.to_string();
    if msg.contains("Login failed with reason") {
        let code = if crate::error::is_rejected_credentials(&msg) {
            ErrorCode::BadCredentials
        } else if msg.contains("Premium account required") {
            ErrorCode::PremiumRequired
        } else if msg.contains("Try another access point") {
            ErrorCode::Network
        } else {
            ErrorCode::Unavailable
        };
        return AppError::new(code, msg);
    }
    let err = AppError::from(e);
    match err.code {
        ErrorCode::BadCredentials | ErrorCode::PremiumRequired => err,
        // `connect()` failures that aren't login failures are network trouble.
        ErrorCode::Internal | ErrorCode::Unavailable | ErrorCode::NotFound => AppError::new(ErrorCode::Network, err.message),
        _ => err,
    }
}

/// Login refusals that retrying with the same credentials can't fix.
pub(crate) fn is_terminal(e: &AppError) -> bool {
    match e.code {
        ErrorCode::BadCredentials | ErrorCode::PremiumRequired | ErrorCode::NotLoggedIn => true,
        ErrorCode::Unavailable => e.message.contains("Login failed with reason"),
        _ => false,
    }
}

/// Error if librespot invalidated the session because the account isn't Premium.
pub(crate) fn premium_error(session: &Session) -> Option<AppError> {
    match session.invalid_reason() {
        Some(SessionInvalidReason::NonPremiumAccount { account_type }) => Some(AppError::new(
            ErrorCode::PremiumRequired,
            format!("A Spotify Premium account is required ({account_type})"),
        )),
        _ => None,
    }
}

/// Connects `session` with `credentials` and starts Spirc. Does not clean up on failure (the
/// caller keeps the session and calls [`abandon`]).
pub(crate) async fn connect(
    session: &Session,
    credentials: Credentials,
    settings: &EngineSettings,
) -> AppResult<Live> {
    let player = player_host::bind(session, settings).await;
    let mixer = player_host::mixer_or_default();
    let connect_config = config::connect_config(settings, mixer.volume());
    let mixer: Arc<dyn Mixer> = mixer;
    let fut = Spirc::new(connect_config, session.clone(), credentials, player, mixer);
    let (spirc, task) = match tokio::time::timeout(CONNECT_TIMEOUT, fut).await {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => return Err(classify(session, e)),
        Err(_) => return Err(AppError::new(ErrorCode::Network, "Timed out connecting to Spotify")),
    };
    if let Some(e) = premium_error(session) {
        // Keep the handle alive until the task ends (it ends quickly on an invalid session).
        let spirc = Arc::new(spirc);
        let task = tokio::spawn(task);
        stop_spirc(session, &spirc, task).await;
        session.shutdown();
        return Err(e);
    }
    let generation = next_generation();
    let attachment = connect::Attachment {
        generation,
        session: session.clone(),
        state: spirc.subscribe_state(),
        cluster: spirc.subscribe_cluster(),
        errors: spirc.subscribe_errors(),
        spirc: Arc::new(spirc),
    };
    let spirc = attachment.spirc.clone();
    if let Err(e) = spirc.set_autoplay(settings.autoplay) {
        log::warn!("autoplay setting not applied: {e}");
    }
    let task = tokio::spawn(task);
    connect::attach(attachment);
    Ok(Live { generation, session: session.clone(), device: Some(Device { spirc, task }) })
}

/// Connects `session` without Spirc (not visible to Spotify Connect): the same login steps
/// `Spirc::new` performs. Does not clean up on failure (the caller calls [`abandon`]).
pub(crate) async fn connect_hidden(session: &Session, credentials: Credentials) -> AppResult<Live> {
    let login = async {
        let _ = session.spclient().client_token().await?;
        session.connect(credentials, true).await?;
        let _ = session.login5().auth_token().await?;
        Ok::<(), librespot_core::Error>(())
    };
    match tokio::time::timeout(CONNECT_TIMEOUT, login).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(classify(session, e)),
        Err(_) => return Err(AppError::new(ErrorCode::Network, "Timed out connecting to Spotify")),
    }
    if let Some(e) = premium_error(session) {
        return Err(e);
    }
    Ok(Live { generation: next_generation(), session: session.clone(), device: None })
}

/// Leaves Spotify Connect but keeps the Session: Spirc shuts down (it disconnects, deletes its
/// connect state and closes the dealer, so the device leaves the cluster) and the Player is
/// released from the session. Bounded like a teardown.
pub(crate) async fn hide(live: &mut Live) {
    let Some(Device { spirc, task }) = live.device.take() else { return };
    connect::clear_restore();
    connect::detach(live.generation);
    stop_spirc(&live.session, &spirc, task).await;
    drop(spirc);
    player_host::detach_session();
}

/// Cleans up a Session whose attempt failed or was abandoned.
pub(crate) async fn abandon(session: Session) {
    session.shutdown();
    if tokio::time::timeout(DEALER_CLOSE_TIMEOUT, session.dealer().close()).await.is_err() {
        log::warn!("dealer close timed out");
    }
    player_host::detach_session();
}

/// Shuts Spirc down and waits (bounded) for its task; the Session stays connected.
async fn stop_spirc(session: &Session, spirc: &Arc<Spirc>, mut task: JoinHandle<()>) {
    if let Err(e) = spirc.shutdown() {
        log::debug!("spirc already gone: {e}");
    }
    if tokio::time::timeout(TASK_JOIN_TIMEOUT, &mut task).await.is_err() {
        log::warn!("spirc task did not end in time, aborting it");
        task.abort();
        if tokio::time::timeout(DEALER_CLOSE_TIMEOUT, session.dealer().close()).await.is_err() {
            log::warn!("dealer close timed out");
        }
    }
}

/// Whether a teardown offers this device's playback to the OfflineController (see
/// [`keep_playing_offline`]): whenever the playback is meant to come back (`restore`), with or
/// without a network. A stop, a logout or offline mode doesn't restore (offline mode hands off by
/// itself).
fn hands_off(restore: bool) -> bool {
    restore
}

/// A downloaded or fully buffered track this device plays (or paused) goes on in the
/// OfflineController instead of being paused and frozen for the reconnect (decided before
/// anything pauses the Player, `connect::hand_off_to_offline`). Without a network that is the
/// only way it goes on. With one, a reconnect is still needed sometimes (the session died, or its
/// AP connection stopped answering after an outage), and the music must not stop for the new
/// login, first cluster and context resolve: the queue hands back to the new Spirc at the end of
/// its window. Anything else (a streamed track not all in the Player) is frozen and restored.
fn keep_playing_offline(live: &Live, restore: bool) -> bool {
    hands_off(restore) && connect::hand_off_to_offline(live.generation)
}

/// Shuts a live connection down. `restore`: remember the local playback for after the
/// reconnect (otherwise any pending restore is dropped); downloaded playback continues offline
/// when there is no network (see [`keep_playing_offline`]).
pub(crate) async fn teardown(live: Live, restore: bool) {
    if keep_playing_offline(&live, restore) {
        log::info!("the session goes away: the downloaded or buffered track keeps playing");
    } else if restore {
        connect::prepare_reconnect();
    } else {
        connect::clear_restore();
    }
    state::set_online(None);
    connect::detach(live.generation);
    let Live { session, device, .. } = live;
    if let Some(Device { spirc, task }) = device {
        stop_spirc(&session, &spirc, task).await;
        // The Spirc task has ended (or was aborted): now the handle may go.
        drop(spirc);
    }
    session.shutdown();
    player_host::detach_session();
    drop(session);
}

/// Cleans up after the Spirc task ended by itself (lost connection, invalid session).
pub(crate) async fn teardown_finished(live: Live, restore: bool) {
    if keep_playing_offline(&live, restore) {
        log::info!("the session goes away: the downloaded or buffered track keeps playing");
    } else if restore {
        connect::prepare_reconnect();
    } else {
        connect::clear_restore();
    }
    state::set_online(None);
    connect::detach(live.generation);
    let Live { session, device, .. } = live;
    session.shutdown();
    // The task's epilogue normally closed the dealer already; closing twice is a no-op.
    if tokio::time::timeout(DEALER_CLOSE_TIMEOUT, session.dealer().close()).await.is_err() {
        log::warn!("dealer close timed out");
    }
    drop(device);
    player_host::detach_session();
}

/// After `Spirc::new`: the reusable credentials of the connected session (`Session::connect`
/// sets its username and auth data from the APWelcome). Nothing is read from or written to disk
/// (the Cache has no credentials location). The supervisor stores them and emits the
/// `credentials` event (`state::store_harvested`).
pub(crate) fn session_credentials(session: &Session) -> Option<StoredCredentials> {
    config::from_session(session.username(), session.auth_data())
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::{Error, SessionConfig};

    #[test]
    fn playback_meant_to_come_back_is_offered_to_the_offline_queue() {
        // No network, or a reconnect with the network back (a dead session, an AP connection
        // that stopped answering): a downloaded or fully buffered track plays on.
        assert!(hands_off(true));
        // No restore (stop, logout): nothing is kept (offline mode hands off on its own).
        assert!(!hands_off(false));
    }

    #[tokio::test]
    async fn only_ap_refusals_are_terminal() {
        let session = Session::new(SessionConfig::default(), None);

        // An HTTP 401 (client-token / login5 / spclient) or a proxy 407 during Spirc::new.
        let http = classify(&session, Error::unauthenticated("Upstream responded with status code 401"));
        assert_eq!(http.code, ErrorCode::Network);
        assert!(!is_terminal(&http), "retried with backoff, never BAD_CREDENTIALS");

        let refused = classify(&session, Error::permission_denied("Login failed with reason: Bad credentials"));
        assert_eq!(refused.code, ErrorCode::BadCredentials);
        assert!(is_terminal(&refused));

        let premium = classify(&session, Error::permission_denied("Login failed with reason: Premium account required"));
        assert_eq!(premium.code, ErrorCode::PremiumRequired);
        assert!(is_terminal(&premium));

        let ap = classify(&session, Error::permission_denied("Login failed with reason: Try another access point"));
        assert_eq!(ap.code, ErrorCode::Network);
        assert!(!is_terminal(&ap));

        let io = classify(&session, Error::unavailable("connection reset"));
        assert_eq!(io.code, ErrorCode::Network);
        assert!(!is_terminal(&io));
    }
}
