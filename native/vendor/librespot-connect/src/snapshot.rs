// SPOTIFYGOOD: this whole file is an addition. It exposes a read-only view of the otherwise
// private `ConnectState` (see `Spirc::subscribe_state`) and the command error events
// (see `Spirc::subscribe_errors`).

//! Read-only observation types published by [Spirc](crate::Spirc).

use crate::core::error::ErrorKind;
use std::collections::HashMap;

/// The playback status of the local connect device
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum SnapshotPlayStatus {
    /// Nothing is loaded, or the context ended
    #[default]
    Stopped,
    /// A track is loading and will start playing once loaded
    LoadingPlay,
    /// A track is loading and will be paused once loaded
    LoadingPause,
    /// A track is playing, the position advances
    Playing,
    /// A track is paused
    Paused,
}

/// Where a track in the connect state comes from
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrackProvider {
    /// A track of the playing context (album, playlist, ...)
    Context,
    /// A track added to the user queue
    Queue,
    /// A track from the autoplay continuation after the context ended
    Autoplay,
    /// A track that turned out to be unavailable and is skipped
    Unavailable,
    /// A smart shuffle suggestion interleaved into the shuffled context
    Suggestion,
    /// Any other provider string sent by another client
    Other(String),
}

/// A track as it is present in the connect state
#[derive(Debug, Clone, PartialEq)]
pub struct SnapshotTrack {
    /// The spotify uri, for example `spotify:track:...` or `spotify:episode:...`
    pub uri: String,
    /// The unique id of this entry, use it for the queue commands of [Spirc](crate::Spirc)
    pub uid: String,
    /// Where the track comes from
    pub provider: TrackProvider,
    /// The index in the (unshuffled) default context, if known.
    ///
    /// For a smart shuffle suggestion this is the index of the context track it follows.
    pub context_index: Option<usize>,
    /// Hidden entries are internal delimiters (uri `spotify:delimiter`), they mark where the
    /// context wraps (repeat) or where autoplay starts. They are never played and should not be
    /// displayed, but are included so that a consumer can see those boundaries.
    pub hidden: bool,
    /// The raw metadata of the entry
    pub metadata: HashMap<String, String>,
}

/// A snapshot of the local connect device state
///
/// ### Position
/// `position_ms` was the playback position at `position_timestamp_ms`. The timestamp is a unix
/// epoch timestamp in milliseconds on the **local device clock** (the same clock as
/// `System.currentTimeMillis()` on Android). Spirc itself stores server corrected timestamps
/// (`local + session.time_delta()`) in the state it puts to spotify; the snapshot converts them
/// back to the local clock. The current position is
/// `position_ms + (now_ms - position_timestamp_ms) * playback_speed`, where `playback_speed` is
/// `0.0` unless `status` is [SnapshotPlayStatus::Playing].
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ConnectSnapshot {
    /// Whether this device is the active connect device. Most commands of
    /// [Spirc](crate::Spirc) are ignored while not active.
    pub is_active: bool,
    /// The local playback status
    pub status: SnapshotPlayStatus,
    /// The position in milliseconds at `position_timestamp_ms`
    pub position_ms: i64,
    /// Unix epoch milliseconds (local clock) at which `position_ms` was the position
    pub position_timestamp_ms: i64,
    /// The factor with which the position advances, `0.0` unless playing
    pub playback_speed: f64,
    /// The duration of the current track in milliseconds, `0` until it is loaded
    pub duration_ms: i64,
    /// The uri of the playing context
    pub context_uri: String,
    /// The url of the playing context
    pub context_url: String,
    /// The metadata of the playing context
    pub context_metadata: HashMap<String, String>,
    /// Whether the playback continues in the autoplay context
    pub playing_autoplay: bool,
    /// The current track
    pub track: Option<SnapshotTrack>,
    /// The previous tracks, oldest first (the last item is the previous track)
    pub prev_tracks: Vec<SnapshotTrack>,
    /// The next tracks, the first item is the next track (queued items come first)
    pub next_tracks: Vec<SnapshotTrack>,
    /// Shuffle is enabled
    pub shuffle: bool,
    /// Smart shuffle is enabled (implies `shuffle`)
    pub smart_shuffle: bool,
    /// Repeat of the context is enabled
    pub repeat_context: bool,
    /// Repeat of the current track is enabled
    pub repeat_track: bool,
    /// Skipping to the previous track is possible (a `prev` beyond 3s always restarts the track)
    pub can_skip_prev: bool,
    /// There is a next playable track
    pub can_skip_next: bool,
    /// Shuffle (and smart shuffle) can currently be toggled
    pub can_toggle_shuffle: bool,
    /// Repeat (context and track) can currently be toggled
    pub can_toggle_repeat: bool,
    /// Changes whenever the next tracks change
    pub queue_revision: String,
    /// The volume of this device (`0..=u16::MAX`)
    pub volume: u16,
    /// The current playback session id
    pub session_id: String,
    /// The last failed command (`"<command>: <message>"`), cleared by the next successful one
    pub last_error: Option<String>,
    /// Set on the final snapshot, published when the spirc task ends (shutdown or lost
    /// connection). It describes the state when the task stopped handling events, before it
    /// disconnected: the disconnect itself (stopped, inactive) is not reflected. The task then
    /// no longer controls the player.
    pub ending: bool,
}

/// A command that failed to be handled, see [Spirc::subscribe_errors](crate::Spirc::subscribe_errors)
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpircCommandError {
    /// The command name, for local commands the snake_case name of the [Spirc](crate::Spirc)
    /// method (for example `shuffle` or `add_to_queue`), for remote commands the endpoint
    /// (for example `set_shuffling_context`)
    pub command: String,
    /// A human readable description of the failure
    pub message: String,
    /// The kind of the failure
    pub kind: ErrorKind,
    /// Whether the command was sent by another connect device
    pub remote: bool,
}
