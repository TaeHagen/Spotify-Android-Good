//! Ownership of the librespot `Player` and the `AndroidMixer`.
//!
//! * The mixer is created once per process (first `session.start`, with the current Android
//!   media volume) and never dropped, so the volume survives player re-creation.
//! * The Player is created once and re-bound to every new Session (`Player::set_session`). It is
//!   dropped (on a blocking thread, bounded: its `Drop` joins the player thread) only on
//!   `session.stop {releasePlayer:true}`, or replaced when its thread died (`is_invalid`).
//! * Only the online bind (`bind`) and `detach_session` change the session of an existing
//!   Player; offline playback uses whatever Player exists.
//! * Without an online session the Player is bound to a never-connected "offline" Session, so no
//!   dead online session (and its sockets) is kept alive by the Player.
//! * One task per Player forwards its events to `connect`; when the channel closes while the
//!   Player is still current, its thread died and the supervisor rebuilds Player + Spirc.

use super::{config, state, supervisor::Msg};
use crate::audio::{AndroidMixer, AndroidSink};
use crate::connect;
use crate::error::AppResult;
use crate::models::EngineSettings;
use librespot_core::Session;
use librespot_playback::mixer::Mixer;
use librespot_playback::player::{Player, PlayerEventChannel};
use parking_lot::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

/// Longest wait for a dropped Player (part of the `session.stop` / `logout` bound).
pub(crate) const PLAYER_DROP_TIMEOUT: Duration = Duration::from_millis(1500);

struct Host {
    player: Arc<Player>,
    generation: u64,
}

static HOST: Mutex<Option<Host>> = parking_lot::const_mutex(None);
static MIXER: OnceLock<Arc<AndroidMixer>> = OnceLock::new();
static GENERATION: AtomicU64 = AtomicU64::new(0);
/// Serialises Player creation (online bind vs. offline playback). The offline path must never
/// rebind an existing Player: a connect attempt may have just bound it to the online session.
static CREATE: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// The process mixer, if a session was ever started.
pub(crate) fn mixer() -> Option<Arc<AndroidMixer>> {
    MIXER.get().cloned()
}

/// Creates the mixer on first use; later calls record the given (current Android) volume.
pub(crate) fn init_mixer(volume: u16) -> Arc<AndroidMixer> {
    let mixer = MIXER.get_or_init(|| Arc::new(AndroidMixer::with_initial(volume)));
    mixer.report_system_volume(volume);
    mixer.clone()
}

pub(crate) fn mixer_or_default() -> Arc<AndroidMixer> {
    MIXER.get_or_init(|| Arc::new(AndroidMixer::with_initial(u16::MAX / 2))).clone()
}

/// The current, healthy Player.
pub(crate) fn player() -> Option<Arc<Player>> {
    HOST.lock().as_ref().filter(|h| !h.player.is_invalid()).map(|h| h.player.clone())
}

/// Generation of the current Player if its thread died.
pub(crate) fn dead_generation() -> Option<u64> {
    HOST.lock().as_ref().filter(|h| h.player.is_invalid()).map(|h| h.generation)
}

/// A never-connected Session (offline playback needs no network). Needs the runtime context.
pub(crate) fn offline_session() -> Session {
    Session::new(config::session_config(), None)
}

/// Drops a Player that is no longer in `HOST`, waiting at most [`PLAYER_DROP_TIMEOUT`]: its
/// `Drop` joins the player thread, which joins the loader threads (bounded in the vendored
/// player, see PATCHES.md). A drop that takes longer finishes in the background; a new Player
/// can be created meanwhile (the old one was stopped first, so it no longer writes audio).
async fn drop_player(player: Arc<Player>) {
    player.stop();
    match tokio::time::timeout(PLAYER_DROP_TIMEOUT, tokio::task::spawn_blocking(move || drop(player))).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => log::warn!("dropping the player failed: {e}"),
        Err(_) => log::warn!("the player is still shutting down, continuing without waiting"),
    }
}

fn create(session: &Session, settings: &EngineSettings) -> Arc<Player> {
    let mixer = mixer_or_default();
    let player = Player::new(config::player_config(settings), session.clone(), mixer.get_soft_volume(), || {
        Box::new(AndroidSink::new())
    });
    let generation = GENERATION.fetch_add(1, Ordering::Relaxed) + 1;
    let events = player.get_player_event_channel();
    crate::runtime::handle().spawn(forward_events(generation, events));
    *HOST.lock() = Some(Host { player: player.clone(), generation });
    log::info!("player {generation} created");
    player
}

/// The healthy Player, created first if there is none (or it died), under `CREATE`.
/// `rebind`: bind an existing Player to this session (the online bind); a new Player gets it, or
/// an offline session when `None`. With `None` an existing Player keeps its session.
async fn get_or_create(rebind: Option<&Session>, settings: &EngineSettings) -> Arc<Player> {
    let _creating = CREATE.lock().await;
    let dead = {
        let mut host = HOST.lock();
        match host.as_ref() {
            Some(h) if !h.player.is_invalid() => {
                if let Some(session) = rebind {
                    h.player.set_session(session.clone());
                }
                return h.player.clone();
            }
            _ => host.take(),
        }
    };
    if let Some(dead) = dead {
        log::warn!("player {} died, creating a new one", dead.generation);
        drop_player(dead.player).await;
    }
    match rebind {
        Some(session) => create(session, settings),
        None => create(&offline_session(), settings),
    }
}

/// Binds `session` to the Player, creating it first if there is none (or it died).
pub(crate) async fn bind(session: &Session, settings: &EngineSettings) -> Arc<Player> {
    get_or_create(Some(session), settings).await
}

/// A Player for the OfflineController. An existing Player is used as it is (it may be bound to
/// the online session of a connect attempt that won `CREATE`); a new one gets an offline session.
pub(crate) async fn ensure_player_for_offline() -> AppResult<Arc<Player>> {
    if let Some(p) = player() {
        return Ok(p);
    }
    Ok(get_or_create(None, &super::settings()).await)
}

/// Releases the online session held by the Player (rebinds it to an offline session).
pub(crate) fn detach_session() {
    if let Some(p) = player() {
        p.set_session(offline_session());
    }
}

/// Drops the Player (`session.stop {releasePlayer:true}`).
pub(crate) async fn release() {
    let host = HOST.lock().take();
    if let Some(host) = host {
        log::info!("releasing player {}", host.generation);
        drop_player(host.player).await;
    }
}

/// Applies bitrate / normalisation / gapless to the running Player through its runtime setters
/// (no re-creation needed). A Player created later is built from the new settings.
pub(crate) fn apply_settings(old: &EngineSettings, new: &EngineSettings) {
    let Some(p) = player() else { return };
    if config::bitrate(old.bitrate) != config::bitrate(new.bitrate) {
        p.set_bitrate(config::bitrate(new.bitrate));
    }
    if old.normalize != new.normalize || old.normalize_pregain != new.normalize_pregain {
        p.set_normalisation(config::normalisation(new));
    }
    if old.gapless != new.gapless {
        p.set_gapless(new.gapless);
    }
}

async fn forward_events(generation: u64, mut events: PlayerEventChannel) {
    while let Some(event) = events.recv().await {
        connect::on_player_event(event);
    }
    let current = HOST.lock().as_ref().map(|h| h.generation) == Some(generation);
    if current {
        log::error!("player {generation} thread ended unexpectedly");
        state::send_to_supervisor(Msg::PlayerDead(generation));
    }
}
