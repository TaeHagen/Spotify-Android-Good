//! Player event handling for the playback layer: Spotify's audio-key refusals
//! (`PLAYBACK_REFUSED`, docs/ARCHITECTURE.md §3.3), other unavailable tracks, and the fan-out to
//! the metadata cache and the offline queue.
//!
//! Refusals: Spirc skips an unavailable track and loads the next one, which is refused again,
//! and so on through the whole queue. After 3 consecutive permanent denials the playback is
//! paused and the Player stopped (cancelling the in-flight load), an `error` event is emitted and
//! `lastError` is set. While latched, any further denial stops again (the skip chain may have
//! queued one more load). A successful `Playing` or a new user load resets the state.

use super::{hub, metadata, offline, snapshot};
use crate::error::{AppError, ErrorCode};
use crate::{engine, events};
use librespot_connect::{ConnectSnapshot, SnapshotPlayStatus};
use librespot_playback::player::{PlayerEvent, UnavailableReason};
use parking_lot::Mutex;
use std::time::{Duration, Instant};

/// Consecutive permanent denials before playback is stopped.
const REFUSALS_BEFORE_STOP: u32 = 3;
/// An unavailable track counts for "queue exhausted" reporting for this long.
const UNAVAILABLE_WINDOW: Duration = Duration::from_secs(15);

pub(crate) const REFUSED_MESSAGE: &str =
    "Spotify refused to provide playback keys for this account. Playback was stopped.";

#[derive(Debug, Default)]
pub(crate) struct Refusal {
    consecutive: u32,
    latched: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RefusalAction {
    None,
    /// Stop playback; `notify`: first time (emit the error).
    Stop { notify: bool },
}

impl Refusal {
    pub fn on_unavailable(&mut self, reason: UnavailableReason) -> RefusalAction {
        if reason != UnavailableReason::KeyDenied {
            return RefusalAction::None;
        }
        if self.latched {
            return RefusalAction::Stop { notify: false };
        }
        self.consecutive += 1;
        if self.consecutive >= REFUSALS_BEFORE_STOP {
            self.latched = true;
            RefusalAction::Stop { notify: true }
        } else {
            RefusalAction::None
        }
    }

    /// Returns true if a latched refusal was cleared.
    pub fn reset(&mut self) -> bool {
        let was = self.latched;
        self.consecutive = 0;
        self.latched = false;
        was
    }
}

struct State {
    refusal: Refusal,
    last_unavailable: Option<(UnavailableReason, Instant)>,
}

static STATE: Mutex<State> =
    parking_lot::const_mutex(State { refusal: Refusal { consecutive: 0, latched: false }, last_unavailable: None });

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

fn refuse(notify: bool) {
    if hub::local_active() {
        if let Some(spirc) = hub::spirc() {
            let _ = spirc.pause();
        }
    }
    if let Some(player) = engine::player_host::player() {
        player.stop();
    }
    if notify {
        log::warn!("audio keys refused {REFUSALS_BEFORE_STOP} times in a row, stopping playback");
        hub::HUB.lock().refused_error = Some(REFUSED_MESSAGE.to_string());
        events::emit_error(&unavailable_error(UnavailableReason::KeyDenied));
        hub::publish();
    }
}

/// Entry point for every event of the Player (called by the engine's player event task).
pub(crate) fn on_player_event(event: PlayerEvent) {
    match &event {
        PlayerEvent::TrackChanged { audio_item } => metadata::on_track_changed(audio_item),
        PlayerEvent::Playing { .. } => {
            let cleared = {
                let mut st = STATE.lock();
                st.last_unavailable = None;
                st.refusal.reset()
            };
            if cleared {
                hub::HUB.lock().refused_error = None;
                hub::publish();
            }
        }
        PlayerEvent::Unavailable { reason, track_id, .. } => {
            log::info!("unavailable: {} ({reason:?})", track_id.to_uri().unwrap_or_default());
            let action = {
                let mut st = STATE.lock();
                st.last_unavailable = Some((*reason, Instant::now()));
                st.refusal.on_unavailable(*reason)
            };
            if let RefusalAction::Stop { notify } = action {
                refuse(notify);
            }
        }
        _ => {}
    }
    offline::on_player_event(&event);
}

/// Spirc ran out of playable tracks after unavailable ones: tell the user once.
pub(crate) fn check_exhausted(snap: &ConnectSnapshot) {
    if !snap.is_active || snap.status != SnapshotPlayStatus::Stopped {
        return;
    }
    if !snapshot::visible_next_indices(&snap.next_tracks).is_empty() {
        return;
    }
    let reason = {
        let mut st = STATE.lock();
        match st.last_unavailable {
            Some((r, at)) if at.elapsed() < UNAVAILABLE_WINDOW && r != UnavailableReason::KeyDenied => {
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

    #[test]
    fn three_denials_stop_once_then_latched() {
        let mut r = Refusal::default();
        assert_eq!(r.on_unavailable(UnavailableReason::KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(UnavailableReason::NetworkError), RefusalAction::None);
        assert_eq!(r.on_unavailable(UnavailableReason::KeyDenied), RefusalAction::None);
        assert_eq!(r.on_unavailable(UnavailableReason::KeyDenied), RefusalAction::Stop { notify: true });
        assert_eq!(r.on_unavailable(UnavailableReason::KeyDenied), RefusalAction::Stop { notify: false });
        assert!(r.reset());
        assert_eq!(r.on_unavailable(UnavailableReason::KeyDenied), RefusalAction::None);
        assert!(!r.reset());
    }

    #[test]
    fn error_codes() {
        assert_eq!(unavailable_error(UnavailableReason::KeyDenied).code, ErrorCode::PlaybackRefused);
        assert_eq!(unavailable_error(UnavailableReason::NetworkError).code, ErrorCode::Network);
        assert_eq!(unavailable_error(UnavailableReason::Other).context.as_deref(), Some("playback"));
    }
}
