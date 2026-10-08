//! Player event handling for the playback layer: the load brake for refused or failing audio
//! keys (`PLAYBACK_REFUSED`, docs/ARCHITECTURE.md §3.3), other unavailable tracks, and the fan-out
//! to the metadata cache and the offline queue.
//!
//! Load brake: Spirc skips an unavailable track and loads the next one, which fails again, and so
//! on through the whole queue (and autoplay). Only failed *loads* count (the Player's `Loading`
//! for that request and track came before); a failed preload while a track plays doesn't, it
//! never interrupts the playing track. Playback is paused, the Player stopped (cancelling the
//! in-flight load), an `error` event emitted and `lastError` set after
//! * 3 permanent denials (`PLAYBACK_REFUSED`), or
//! * 3 transient failures (audio key timeout / rate limit, network): `RATE_LIMITED` with
//!   `retryAfterMs`, or `NETWORK`. Each such load already costs several key requests.
//!
//! While latched, any further counted failure stops again (the skip chain may have queued one
//! more load). A successful `Playing` or a new user load resets the state.

use super::{hub, metadata, offline, snapshot};
use crate::error::{AppError, ErrorCode};
use crate::{engine, events};
use librespot_connect::{ConnectSnapshot, SnapshotPlayStatus};
use librespot_core::SpotifyUri;
use librespot_playback::player::{PlayerEvent, UnavailableReason};
use parking_lot::Mutex;
use std::time::{Duration, Instant};

/// Consecutive permanent denials before playback is stopped.
const REFUSALS_BEFORE_STOP: u32 = 3;
/// Consecutive transiently failed loads (key timeout / rate limit, network) before playback is
/// stopped.
const TRANSIENT_BEFORE_STOP: u32 = 3;
/// `retryAfterMs` of the error after transient key failures.
const TRANSIENT_RETRY_AFTER: Duration = Duration::from_secs(60);
/// An unavailable track counts for "queue exhausted" reporting for this long.
const UNAVAILABLE_WINDOW: Duration = Duration::from_secs(15);

pub(crate) const REFUSED_MESSAGE: &str =
    "Spotify refused to provide playback keys for this account. Playback was stopped.";
const THROTTLED_MESSAGE: &str = "Spotify is temporarily refusing playback. Playback was stopped, try again later.";
const NETWORK_MESSAGE: &str = "Tracks couldn't be loaded (network error). Playback was stopped.";

#[derive(Debug, Default)]
pub(crate) struct Refusal {
    consecutive: u32,
    transient: u32,
    /// Set when playback was stopped (the reason), until a track plays or the user loads.
    latched: Option<UnavailableReason>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RefusalAction {
    None,
    /// Stop playback; `notify`: first time (emit the error for `reason`).
    Stop { reason: UnavailableReason, notify: bool },
}

fn is_transient(reason: UnavailableReason) -> bool {
    matches!(reason, UnavailableReason::KeyTemporarilyDenied | UnavailableReason::NetworkError)
}

impl Refusal {
    /// A load failed for `reason` (only loads, not preloads).
    pub fn on_unavailable(&mut self, reason: UnavailableReason) -> RefusalAction {
        let (count, limit) = match reason {
            UnavailableReason::KeyDenied => (&mut self.consecutive, REFUSALS_BEFORE_STOP),
            r if is_transient(r) => (&mut self.transient, TRANSIENT_BEFORE_STOP),
            _ => return RefusalAction::None,
        };
        if let Some(latched) = self.latched {
            return RefusalAction::Stop { reason: latched, notify: false };
        }
        *count += 1;
        if *count >= limit {
            self.latched = Some(reason);
            RefusalAction::Stop { reason, notify: true }
        } else {
            RefusalAction::None
        }
    }

    pub fn is_latched(&self) -> bool {
        self.latched.is_some()
    }

    /// Returns true if a latched refusal was cleared.
    pub fn reset(&mut self) -> bool {
        let was = self.latched.is_some();
        *self = Refusal::default();
        was
    }
}

struct State {
    refusal: Refusal,
    last_unavailable: Option<(UnavailableReason, Instant)>,
    /// The request id and track of the load in progress (`Loading` until `Playing` / `Paused` /
    /// `Stopped`). An `Unavailable` for another track (or request) is a failed preload.
    loading: Option<(u64, SpotifyUri)>,
}

impl State {
    const fn new() -> Self {
        State { refusal: Refusal { consecutive: 0, transient: 0, latched: None }, last_unavailable: None, loading: None }
    }

    /// `brake`: the failure counts for the load brake.
    fn on_unavailable(&mut self, play_request_id: u64, track_id: &SpotifyUri, reason: UnavailableReason, brake: bool) -> RefusalAction {
        self.last_unavailable = Some((reason, Instant::now()));
        let load_failed = brake && self.loading.as_ref().is_some_and(|(id, uri)| *id == play_request_id && uri == track_id);
        if load_failed {
            self.refusal.on_unavailable(reason)
        } else {
            RefusalAction::None
        }
    }
}

static STATE: Mutex<State> = parking_lot::const_mutex(State::new());

pub(crate) fn unavailable_error(reason: UnavailableReason) -> AppError {
    let (code, message) = match reason {
        UnavailableReason::NotAvailable => (ErrorCode::Unavailable, "This track isn't available"),
        UnavailableReason::NetworkError => (ErrorCode::Network, "Couldn't load the track (network error)"),
        UnavailableReason::KeyDenied => (ErrorCode::PlaybackRefused, REFUSED_MESSAGE),
        UnavailableReason::KeyTemporarilyDenied => {
            (ErrorCode::RateLimited, "Spotify is temporarily refusing playback. Try again later")
        }
        UnavailableReason::DecodeError => (ErrorCode::Unavailable, "The audio couldn't be decoded"),
        UnavailableReason::OfflineFileError => (ErrorCode::Unavailable, "The downloaded file couldn't be read"),
        UnavailableReason::Other => (ErrorCode::Unavailable, "This track can't be played"),
    };
    AppError::new(code, message).with_context("playback")
}

/// The error emitted when the load brake stopped playback for `reason`.
pub(crate) fn halted_error(reason: UnavailableReason) -> AppError {
    let mut error = unavailable_error(reason);
    match reason {
        UnavailableReason::KeyTemporarilyDenied => {
            error.message = THROTTLED_MESSAGE.into();
            error.retry_after_ms = Some(TRANSIENT_RETRY_AFTER.as_millis() as u64);
        }
        UnavailableReason::NetworkError => error.message = NETWORK_MESSAGE.into(),
        _ => {}
    }
    error
}

/// A user started something new: give it a fresh chance.
pub(crate) fn on_user_load() {
    let cleared = STATE.lock().refusal.reset();
    if cleared {
        hub::HUB.lock().refused_error = None;
    }
}

/// Reason of the last unavailable item (if recent), consumed.
pub(crate) fn take_recent_unavailable() -> Option<UnavailableReason> {
    let mut st = STATE.lock();
    match st.last_unavailable.take() {
        Some((r, at)) if at.elapsed() < UNAVAILABLE_WINDOW => Some(r),
        _ => None,
    }
}

fn halt(reason: UnavailableReason, notify: bool) {
    if hub::local_active() {
        if let Some(spirc) = hub::spirc() {
            let _ = spirc.pause();
        }
    }
    if let Some(player) = engine::player_host::player() {
        player.stop();
    }
    if notify {
        let error = halted_error(reason);
        log::warn!("loads failed repeatedly ({reason:?}), stopping playback");
        hub::HUB.lock().refused_error = Some(error.message.clone());
        events::emit_error(&error);
        hub::publish();
    }
}

/// Entry point for every event of the Player (called by the engine's player event task).
pub(crate) fn on_player_event(event: PlayerEvent) {
    match &event {
        PlayerEvent::TrackChanged { audio_item } => metadata::on_track_changed(audio_item),
        PlayerEvent::Loading { play_request_id, track_id, .. } => {
            STATE.lock().loading = Some((*play_request_id, track_id.clone()));
        }
        PlayerEvent::Playing { .. } => {
            let cleared = {
                let mut st = STATE.lock();
                st.loading = None;
                st.last_unavailable = None;
                st.refusal.reset()
            };
            if cleared {
                hub::HUB.lock().refused_error = None;
                hub::publish();
            }
        }
        PlayerEvent::Paused { .. } | PlayerEvent::Stopped { .. } => STATE.lock().loading = None,
        PlayerEvent::Unavailable { reason, track_id, play_request_id } => {
            log::info!("unavailable: {} ({reason:?})", track_id.to_uri().unwrap_or_default());
            // The offline queue skips what it can't play itself (a queued track that isn't
            // downloaded) and reports its own end: not Spirc's skip chain, no brake.
            let brake = !offline::is_active();
            let action = STATE.lock().on_unavailable(*play_request_id, track_id, *reason, brake);
            if let RefusalAction::Stop { reason, notify } = action {
                halt(reason, notify);
            }
        }
        _ => {}
    }
    offline::on_player_event(&event);
}

/// Spirc ran out of playable tracks after unavailable ones: tell the user once (unless the load
/// brake already did).
pub(crate) fn check_exhausted(snap: &ConnectSnapshot) {
    // Without a track it is an activation (a load or the restore follows), not an exhausted queue.
    if !snap.is_active || snap.status != SnapshotPlayStatus::Stopped || snap.track.is_none() {
        return;
    }
    if !snapshot::visible_next_indices(&snap.next_tracks).is_empty() {
        return;
    }
    let reason = {
        let mut st = STATE.lock();
        match st.last_unavailable {
            Some((r, at)) if at.elapsed() < UNAVAILABLE_WINDOW && !st.refusal.is_latched() => {
                st.last_unavailable = None;
                r
            }
            _ => return,
        }
    };
    events::emit_error(&unavailable_error(reason));
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::SpotifyId;

    fn uri(n: u8) -> SpotifyUri {
        let mut raw = [0u8; 16];
        raw[15] = n;
        SpotifyUri::Track { id: SpotifyId::from_raw(&raw).unwrap() }
    }

    fn stop(reason: UnavailableReason, notify: bool) -> RefusalAction {
        RefusalAction::Stop { reason, notify }
    }

    #[test]
    fn three_denials_stop_once_then_latched() {
        use UnavailableReason::*;
        let mut r = Refusal::default();
        assert_eq!(r.on_unavailable(KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(NotAvailable), RefusalAction::None);
        assert_eq!(r.on_unavailable(KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(KeyDenied), stop(KeyDenied, true));
        assert_eq!(r.on_unavailable(KeyDenied), stop(KeyDenied, false));
        assert!(r.reset());
        assert_eq!(r.on_unavailable(KeyDenied), RefusalAction::None);
        assert!(!r.reset());
    }

    #[test]
    fn transient_failures_have_a_brake_too() {
        use UnavailableReason::*;
        let mut r = Refusal::default();
        assert_eq!(r.on_unavailable(KeyTemporarilyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(DecodeError), RefusalAction::None);
        assert_eq!(r.on_unavailable(NetworkError), RefusalAction::None);
        assert_eq!(r.on_unavailable(KeyTemporarilyDenied), stop(KeyTemporarilyDenied, true));
        // latched: any further counted failure stops again, quietly
        assert_eq!(r.on_unavailable(KeyDenied), stop(KeyTemporarilyDenied, false));
        assert_eq!(r.on_unavailable(NetworkError), stop(KeyTemporarilyDenied, false));
        assert_eq!(r.on_unavailable(NotAvailable), RefusalAction::None);
        // permanent and transient failures are counted separately
        r.reset();
        assert_eq!(r.on_unavailable(KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(NetworkError), RefusalAction::None);
        assert_eq!(r.on_unavailable(KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(NetworkError), RefusalAction::None);
        assert_eq!(r.on_unavailable(NetworkError), stop(NetworkError, true));
    }

    #[test]
    fn failed_preloads_never_stop_the_playing_track() {
        let mut st = State::new();
        // track 1 (request 5) loads and plays
        st.loading = Some((5, uri(1)));
        st.loading = None; // Playing
        // the preloads of the following tracks are refused (they carry the playing request id)
        for n in 2..6 {
            assert_eq!(st.on_unavailable(5, &uri(n), UnavailableReason::KeyDenied, true), RefusalAction::None);
        }
        assert!(st.last_unavailable.is_some(), "still reported when the queue runs out");
        // a preload while a track loads isn't the load either
        st.loading = Some((6, uri(6)));
        assert_eq!(st.on_unavailable(6, &uri(7), UnavailableReason::KeyDenied, true), RefusalAction::None);
        assert_eq!(st.on_unavailable(5, &uri(6), UnavailableReason::KeyDenied, true), RefusalAction::None);
        // failed loads count
        assert_eq!(st.on_unavailable(6, &uri(6), UnavailableReason::KeyDenied, true), RefusalAction::None);
        st.loading = Some((7, uri(7)));
        assert_eq!(st.on_unavailable(7, &uri(7), UnavailableReason::KeyDenied, true), RefusalAction::None);
        st.loading = Some((8, uri(8)));
        assert_eq!(st.on_unavailable(8, &uri(8), UnavailableReason::KeyDenied, true), stop(UnavailableReason::KeyDenied, true));
    }

    #[test]
    fn the_offline_queues_skips_dont_brake() {
        // queued tracks that aren't downloaded fail one after the other on the offline session
        let mut st = State::new();
        for n in 1..8u8 {
            st.loading = Some((u64::from(n), uri(n)));
            assert_eq!(st.on_unavailable(u64::from(n), &uri(n), UnavailableReason::NetworkError, false), RefusalAction::None);
        }
        assert!(!st.refusal.is_latched());
        assert!(st.last_unavailable.is_some(), "still the reason if the queue runs out");
    }

    #[test]
    fn error_codes() {
        assert_eq!(unavailable_error(UnavailableReason::KeyDenied).code, ErrorCode::PlaybackRefused);
        assert_eq!(unavailable_error(UnavailableReason::NetworkError).code, ErrorCode::Network);
        assert_eq!(unavailable_error(UnavailableReason::Other).context.as_deref(), Some("playback"));
        let throttled = halted_error(UnavailableReason::KeyTemporarilyDenied);
        assert_eq!(throttled.code, ErrorCode::RateLimited);
        assert_eq!(throttled.retry_after_ms, Some(60_000));
        assert_eq!(halted_error(UnavailableReason::KeyDenied).message, REFUSED_MESSAGE);
        assert_eq!(halted_error(UnavailableReason::NetworkError).code, ErrorCode::Network);
    }
}
