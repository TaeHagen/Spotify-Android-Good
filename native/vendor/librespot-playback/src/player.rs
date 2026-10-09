use std::{
    collections::HashMap,
    fmt, fs,
    fs::File,
    future::Future,
    io::{self, Read, Seek, SeekFrom},
    mem,
    pin::Pin,
    // SPOTIFYGOOD: `process::exit` removed. No code path may end the host (Android app) process.
    // `MutexGuard` / `PoisonError` for `lock_load_handles()`.
    sync::{Mutex, MutexGuard, PoisonError},
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    task::{Context, Poll},
    thread,
    time::{Duration, Instant},
};

#[cfg(feature = "passthrough-decoder")]
use crate::decoder::PassthroughDecoder;
use crate::{
    // SPOTIFYGOOD: AudioFileError for the bounded wait of preload_data_before_playback
    audio::{AudioDecrypt, AudioFetchParams, AudioFile, AudioFileError, StreamLoaderController},
    audio_backend::Sink,
    // SPOTIFYGOOD: added NormalisationSettings.
    config::{Bitrate, NormalisationMethod, NormalisationSettings, NormalisationType, PlayerConfig},
    convert::Converter,
    core::{Error, Session, SpotifyId, SpotifyUri, util::SeqGenerator},
    // SPOTIFYGOOD: for the audio-key retry and for classifying load failures.
    core::{FileId, audio_key::AudioKey, audio_key::is_permanent_denial, session::SessionError},
    metadata::MetadataError,
    // SPOTIFYGOOD: DecoderError for the stall handling
    decoder::{AudioDecoder, AudioPacket, AudioPacketPosition, DecoderError, SymphoniaDecoder},
    local_file::{LocalFileLookup, create_local_file_lookup},
    metadata::audio::{AudioFileFormat, AudioFiles, AudioItem},
    mixer::VolumeGetter,
    offline::OfflineSourceRef, // SPOTIFYGOOD: offline source hook
};
use futures_util::{
    // SPOTIFYGOOD: FutureExt instead of TryFutureExt (the loader result now carries a reason).
    FutureExt, StreamExt, future, future::FusedFuture,
    stream::futures_unordered::FuturesUnordered,
};
use librespot_metadata::{audio::UniqueFields, track::Tracks};

use symphonia::core::io::MediaSource;
use symphonia::core::probe::Hint;
use tokio::sync::{mpsc, oneshot};

use crate::SAMPLES_PER_SECOND;

const PRELOAD_NEXT_TRACK_BEFORE_END_DURATION_MS: u32 = 30000;
pub const DB_VOLTAGE_RATIO: f64 = 20.0;
pub const PCM_AT_0DBFS: f64 = 1.0;

// Spotify inserts a custom Ogg packet at the start with custom metadata values, that you would
// otherwise expect in Vorbis comments. This packet isn't well-formed and players may balk at it.
const SPOTIFY_OGG_HEADER_END: u64 = 0xa7;

// SPOTIFYGOOD: `LOAD_HANDLES_POISON_MSG` removed. `load_handles` is locked with
// `lock_load_handles()`, which ignores poisoning: a panic elsewhere must never turn into a
// second panic in `PlayerInternal::drop` (a double panic aborts the host process).

// SPOTIFYGOOD: audio-key retry policy for transient failures (librespot #1649 / PR #1763).
const AUDIO_KEY_RETRIES: u32 = 3;
const AUDIO_KEY_RETRY_DELAY: Duration = Duration::from_secs(1);
// SPOTIFYGOOD: after the retries ran out on a transient failure (Spotify throttling keys, the AP
// struggling), key requests in the next AUDIO_KEY_COOLDOWN make a single attempt instead of
// 1 + AUDIO_KEY_RETRIES, so a run of skipped tracks doesn't multiply the requests. Shared by all
// loaders and Players of the process (AUDIO_KEY_BRAKE).
const AUDIO_KEY_COOLDOWN: Duration = Duration::from_secs(30);

// SPOTIFYGOOD: the key-retry cool-down of the process (see AUDIO_KEY_COOLDOWN).
static AUDIO_KEY_BRAKE: Mutex<KeyRetryBrake> = Mutex::new(KeyRetryBrake::new());

// SPOTIFYGOOD: when audio-key requests may retry (see AUDIO_KEY_COOLDOWN).
#[derive(Debug, Default)]
struct KeyRetryBrake {
    cooling_until: Option<Instant>,
}

impl KeyRetryBrake {
    const fn new() -> Self {
        Self { cooling_until: None }
    }

    /// Retries a key request that starts at `now` may make.
    fn retries(&self, now: Instant) -> u32 {
        match self.cooling_until {
            Some(until) if now < until => 0,
            _ => AUDIO_KEY_RETRIES,
        }
    }

    /// The retries (or a single attempt while cooling down) ran out on a transient failure.
    fn exhausted(&mut self, now: Instant) {
        self.cooling_until = Some(now + AUDIO_KEY_COOLDOWN);
    }

    /// A key arrived: requests may retry again.
    fn succeeded(&mut self) {
        self.cooling_until = None;
    }
}

fn audio_key_brake() -> MutexGuard<'static, KeyRetryBrake> {
    AUDIO_KEY_BRAKE.lock().unwrap_or_else(PoisonError::into_inner)
}

// SPOTIFYGOOD: the player's own tokio runtime only drives loader I/O (the loaders run their
// futures with `Handle::block_on` on their own threads, and hyper connection tasks are spawned
// on it). One worker is enough. It must stay a multi-thread runtime: with a current-thread
// runtime nothing drives I/O or timers while the player thread is blocked in `Sink::write`.
const PLAYER_RUNTIME_WORKER_THREADS: usize = 1;

// SPOTIFYGOOD: bounded player shutdown. Stock `PlayerInternal::drop` joins every loader thread
// (superseded loads included) and the runtime drop waits for its blocking tasks (hyper's
// getaddrinfo). Loaders have no network timeout, so a stalled request kept `Player::drop`
// (and the engine's stop/logout) waiting for minutes. Loaders still running after
// `LOADER_JOIN_TIMEOUT` are detached (their results are discarded anyway); the runtime is shut
// down with `PLAYER_RUNTIME_SHUTDOWN_TIMEOUT`, which also cancels the I/O of detached loaders.
const LOADER_JOIN_TIMEOUT: Duration = Duration::from_secs(1);
const LOADER_JOIN_POLL: Duration = Duration::from_millis(10);
const PLAYER_RUNTIME_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(250);

pub type PlayerResult = Result<(), Error>;

// SPOTIFYGOOD: why a track could not be loaded, carried by `PlayerEvent::Unavailable`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnavailableReason {
    /// Spotify says the item can't be played: regional or catalogue restriction, embargo,
    /// explicit content filtered, no playable alternative, no supported audio format, or an
    /// offline item whose `audio_item.availability` is `Err`.
    NotAvailable,
    /// Metadata or audio could not be fetched (network, CDN, HTTP or token error).
    NetworkError,
    /// Spotify refused the audio key for this account and file (permanent denial). Do not retry.
    /// Tell the user that Spotify refused playback.
    KeyDenied,
    /// The audio-key request failed transiently (timeout, rate limit, AP not connected) after
    /// all retries, and the file could not be played without a key.
    KeyTemporarilyDenied,
    /// The file was opened but could not be decoded (corrupt, wrong key, unsupported sample
    /// rate or channel count) or could not seek to the start position.
    DecodeError,
    /// The `OfflineSource` returned a track, but its file could not be opened.
    OfflineFileError,
    /// The URI can't be played by this player, a local file is missing, or the loader thread
    /// died.
    Other,
}

// SPOTIFYGOOD: how an audio-key failure is handled by `PlayerTrackLoader::request_audio_key`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeyFailure {
    /// Spotify refused the key permanently (`AesKeyError` code 0x0001): abort the load at once.
    Permanent,
    /// Throttled (`AesKeyError` code 0x0002 or another code), timeout or channel error: retry.
    Transient,
    /// No live access-point connection: retrying with this session cannot help.
    NoSession,
}

// SPOTIFYGOOD: classify an audio-key error with vendored librespot-core's `is_permanent_denial`.
fn classify_audio_key_error(err: &Error, session: &Session) -> KeyFailure {
    if is_permanent_denial(err) {
        return KeyFailure::Permanent;
    }
    if session.is_invalid()
        || matches!(
            err.error.downcast_ref::<SessionError>(),
            Some(SessionError::NotConnected { .. })
        )
    {
        return KeyFailure::NoSession;
    }
    KeyFailure::Transient
}

pub struct Player {
    commands: Option<mpsc::UnboundedSender<PlayerCommand>>,
    thread_handle: Option<thread::JoinHandle<()>>,
    // SPOTIFYGOOD: see Player::last_decoded
    decoded: SharedDecoded,
    // SPOTIFYGOOD: see Player::fully_buffered
    buffered: SharedBuffered,
}

// SPOTIFYGOOD: see Player::fully_buffered
type SharedBuffered = Arc<Mutex<Option<SpotifyUri>>>;

fn lock_buffered(buffered: &SharedBuffered) -> MutexGuard<'_, Option<SpotifyUri>> {
    buffered.lock().unwrap_or_else(PoisonError::into_inner)
}

// SPOTIFYGOOD: see Player::last_decoded
/// The last packet the player decoded of a track (or the position it was told to seek or load
/// it to)
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedPosition {
    pub track_id: SpotifyUri,
    pub position_ms: u32,
    /// when it was decoded (or told)
    pub at: Instant,
}

// SPOTIFYGOOD: written by the player thread (the packet loop, seek, load, pause), read by the
// engine without a command: the player thread may be blocked in a read for seconds
type SharedDecoded = Arc<Mutex<Option<DecodedPosition>>>;

fn lock_decoded(decoded: &SharedDecoded) -> MutexGuard<'_, Option<DecodedPosition>> {
    decoded.lock().unwrap_or_else(PoisonError::into_inner)
}

fn set_decoded(decoded: &SharedDecoded, track_id: &SpotifyUri, position_ms: u32) {
    let mut decoded = lock_decoded(decoded);
    match decoded.as_mut() {
        Some(d) if d.track_id == *track_id => {
            d.position_ms = position_ms;
            d.at = Instant::now();
        }
        _ => {
            *decoded = Some(DecodedPosition {
                track_id: track_id.clone(),
                position_ms,
                at: Instant::now(),
            })
        }
    }
}

#[derive(PartialEq, Eq, Debug, Clone, Copy)]
pub enum SinkStatus {
    Running,
    Closed,
    TemporarilyClosed,
}

pub type SinkEventCallback = Box<dyn Fn(SinkStatus) + Send>;

struct PlayerInternal {
    session: Session,
    config: PlayerConfig,
    commands: mpsc::UnboundedReceiver<PlayerCommand>,
    load_handles: Arc<Mutex<HashMap<thread::ThreadId, thread::JoinHandle<()>>>>,

    state: PlayerState,
    preload: PlayerPreload,
    sink: Box<dyn Sink>,
    sink_status: SinkStatus,
    sink_event_callback: Option<SinkEventCallback>,
    volume_getter: Box<dyn VolumeGetter + Send>,
    event_senders: Vec<mpsc::UnboundedSender<PlayerEvent>>,
    converter: Converter,

    normalisation_integrators: [f64; 2],
    normalisation_peaks: [f64; 2],
    normalisation_channel: usize,
    normalisation_knee_factor: f64,

    auto_normalise_as_album: bool,

    player_id: usize,
    play_request_id_generator: SeqGenerator<u64>,
    last_progress_update: Instant,

    local_file_lookup: Arc<LocalFileLookup>,

    // SPOTIFYGOOD: see Player::set_playback_speed and nominal_start_time
    playback_speed: f64,
    // SPOTIFYGOOD: see stall_action
    stream_stall: Option<StreamStall>,
    // SPOTIFYGOOD: see Player::last_decoded
    decoded: SharedDecoded,
    // SPOTIFYGOOD: see Player::fully_buffered
    buffered: SharedBuffered,
    // SPOTIFYGOOD: a track whose loader can't deliver (it stalled for STREAM_STALL_MAX, or its
    // loader is gone): its next play or load opens it again (load_track: a new CDN URL, a new
    // loader) at the position played, instead of reusing its decoder
    reopen: Option<SpotifyUri>,
}

// SPOTIFYGOOD: how long a playing streamed track waits for its data before it pauses, as long as
// the engine keeps the session up for a device that streams from its buffer
const STREAM_STALL_MAX: Duration = Duration::from_secs(60);

// SPOTIFYGOOD: a track whose reads timed out (see stall_action), until a packet past the
// position it stalled at comes
#[derive(Debug, Clone, Copy)]
struct StreamStall {
    play_request_id: u64,
    /// the first read that timed out while it played; `None` after a pause or a resume (it waits
    /// anew)
    since: Option<Instant>,
    /// the position played when it stalled
    position_ms: u32,
    /// waiting for the data at the read position (wait_for_stalled_data), the decoder isn't read
    /// meanwhile; once it is there the decoder is re-seeked to `position_ms`
    waiting: bool,
}

impl StreamStall {
    /// After a read that timed out (or a wait for the data that did): it waits for the data, since
    /// the first one, at the position it stalled at
    fn again(stall: Option<Self>, play_request_id: u64, position_ms: u32, now: Instant) -> Self {
        Self {
            play_request_id,
            since: Some(stall.and_then(|stall| stall.since).unwrap_or(now)),
            position_ms: stall.map_or(position_ms, |stall| stall.position_ms),
            waiting: true,
        }
    }

    /// After a packet at `position_ms` (`None`: the end of the track): over once it is past the
    /// position it stalled at, the one the re-seek decodes again doesn't count
    fn after_packet(self, position_ms: Option<u32>) -> Option<Self> {
        match position_ms {
            Some(position) if position <= self.position_ms => Some(Self {
                waiting: false,
                ..self
            }),
            _ => None,
        }
    }
}

// SPOTIFYGOOD: see wait_for_stalled_data
/// How much data at the read position a stalled stream waits for before it is decoded again (a
/// page or two)
const STALL_WAIT_BYTES: usize = 16 * 1024;

// SPOTIFYGOOD: see wait_for_data
/// How often a wait for the data of a stream looks whether it is there
const DATA_POLL: Duration = Duration::from_millis(100);

// SPOTIFYGOOD: what a wait for the data of a stream needs of its loader (a test fakes it)
trait DataSource {
    /// where the file is read; `None`: not streamed, its data is there
    fn read_position(&self) -> Option<usize>;
    /// requests the bytes once
    fn request(&self, start: usize, length: usize);
    fn available(&self, start: usize, length: usize) -> bool;
}

impl DataSource for StreamLoaderController {
    fn read_position(&self) -> Option<usize> {
        StreamLoaderController::read_position(self)
    }

    fn request(&self, start: usize, length: usize) {
        self.fetch_range(start, length)
    }

    fn available(&self, start: usize, length: usize) -> bool {
        self.range_available_at(start, length)
    }
}

// SPOTIFYGOOD: one bounded wait for the data at the read position: `request` bytes are requested
// once, then it looks every DATA_POLL whether `wait` bytes are there, until `deadline` from its
// start (nothing extends it, nothing is requested again). librespot-audio's
// `fetch_next_and_wait` requested a range again after every failure of it and started its
// timeout anew with every wake-up: with requests that fail at once (an expired CDN URL's 403, a
// 5xx, a refused connection) it never returned, the player thread handled no command, and it
// drained the per-domain rate limit, which ended the file's loader for good.
/// Whether the data came
fn wait_for_data(
    source: &impl DataSource,
    request: usize,
    wait: usize,
    deadline: Duration,
) -> bool {
    let Some(start) = source.read_position() else {
        return true;
    };
    if source.available(start, wait) {
        return true;
    }
    source.request(start, request.max(wait));
    let end = Instant::now() + deadline;
    loop {
        thread::sleep(DATA_POLL.min(end.saturating_duration_since(Instant::now())));
        if source.available(start, wait) {
            return true;
        }
        if Instant::now() >= end {
            return false;
        }
    }
}

// SPOTIFYGOOD: the wait of a stalled stream for its data (see wait_for_data), at the read position
// (where the read that timed out stopped) and without the decoder: the decoder's seek (to read
// again from the position played) bisects the whole Ogg file, and each of its probes past the
// data waited `download_timeout` again. One attempt is one request and one wait of at most
// `download_timeout`.
/// Whether the data came
fn wait_for_stalled_data(controller: &StreamLoaderController, bytes_per_second: usize) -> bool {
    let params = AudioFetchParams::get();
    let request =
        (params.read_ahead_during_playback.as_secs_f32() * bytes_per_second as f32) as usize;
    wait_for_data(
        controller,
        request,
        STALL_WAIT_BYTES,
        params.download_timeout,
    )
}

// SPOTIFYGOOD: what the packet loop does when the decoder fails
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StallAction {
    /// read again (re-seeked to the position played), the track keeps playing
    Retry,
    /// pause at the position played: the data didn't come for STREAM_STALL_MAX
    Pause,
    /// a broken track (or the session is gone): on to the next one, as stock
    Skip,
}

// SPOTIFYGOOD: a read of a streamed file times out after librespot-audio's `download_timeout`
// (8 s) without data. Stock took it as a broken track and skipped it (EndOfTrack): a stall of a
// few seconds past the buffer (a tunnel, a cell handover) ended the episode, and Spirc went on
// with the next one. A stall keeps the track: each attempt waits for the data at the read
// position (wait_for_stalled_data, which requests a range whose request failed once more), the
// commands are handled between the attempts (a pause ends the waiting: a paused track doesn't
// read), and after STREAM_STALL_MAX it pauses at the position played. A session that is gone
// (the engine restores the playback) and any other error skip as before.
/// What to do when the playing track can't go on: `stalled` its data didn't come (a timed-out
/// read, or the wait for it), else the decoder failed. Stalled since `stalled_since` (its first
/// timed-out read).
fn stall_action(
    stalled: bool,
    stalled_since: Option<Instant>,
    now: Instant,
    session_valid: bool,
) -> StallAction {
    if !stalled || !session_valid {
        return StallAction::Skip;
    }
    match stalled_since {
        Some(since) if now.saturating_duration_since(since) >= STREAM_STALL_MAX => {
            StallAction::Pause
        }
        _ => StallAction::Retry,
    }
}

// SPOTIFYGOOD: the line of a playback in media time. Stock assumed 1x: the nominal start time
// (`reported_nominal_start_time`) was `now - position`, and a position correction was reported
// when the stream fell 1 s behind that line. Above 1x the stream is always ahead of it, so a
// stall (a blocking read of a streamed file) was never reported, and after a fast part a slower
// speed kept the corrections away for long (Connect, the app's seek bar and resume points ran
// ahead of the audio). The line now follows the speed the sink plays at: position =
// (now - start) x speed. At 1x it is the stock line exactly.
/// The nominal start time of a playback at `position` now, at `speed`
fn nominal_start_time(now: Instant, position: Duration, speed: f64) -> Option<Instant> {
    let wall = if speed == 1. {
        position
    } else {
        position.div_f64(speed)
    };
    now.checked_sub(wall)
}

// SPOTIFYGOOD: see nominal_start_time
/// Whether the stream at `position` lags 1 s or more behind the line of `nominal_start_time` at
/// `speed` (being ahead is the sink's buffer, the audio is in time then)
fn lags_behind(now: Instant, nominal_start_time: Instant, position: Duration, speed: f64) -> bool {
    let Some(played) = now.checked_duration_since(nominal_start_time) else {
        return false;
    };
    let expected = if speed == 1. {
        played
    } else {
        played.mul_f64(speed)
    };
    expected
        .checked_sub(position)
        .is_some_and(|lag| lag >= Duration::from_secs(1))
}

// SPOTIFYGOOD: see Player::set_playback_speed
fn valid_playback_speed(speed: f64) -> f64 {
    if speed.is_finite() && speed > 0. {
        speed.clamp(0.05, 20.)
    } else {
        1.
    }
}

static PLAYER_COUNTER: AtomicUsize = AtomicUsize::new(0);

enum PlayerCommand {
    Load {
        track_id: SpotifyUri,
        play: bool,
        position_ms: u32,
    },
    Preload {
        track_id: SpotifyUri,
    },
    Play,
    Pause,
    Stop,
    Seek(u32),
    SetSession(Session),
    AddEventSender(mpsc::UnboundedSender<PlayerEvent>),
    SetSinkEventCallback(Option<SinkEventCallback>),
    EmitVolumeChangedEvent(u16),
    SetAutoNormaliseAsAlbum(bool),
    EmitSessionDisconnectedEvent {
        connection_id: String,
        user_name: String,
    },
    EmitSessionConnectedEvent {
        connection_id: String,
        user_name: String,
    },
    EmitSessionClientChangedEvent {
        client_id: String,
        client_name: String,
        client_brand_name: String,
        client_model_name: String,
    },
    EmitFilterExplicitContentChangedEvent(bool),
    EmitShuffleChangedEvent(bool),
    EmitRepeatChangedEvent {
        context: bool,
        track: bool,
    },
    EmitAutoPlayChangedEvent(bool),
    // SPOTIFYGOOD: settings that can change at runtime; they apply to loads started afterwards.
    SetOfflineSource(Option<OfflineSourceRef>),
    SetBitrate(Bitrate),
    SetNormalisation(NormalisationSettings),
    SetGapless(bool),
    SetPlaybackSpeed(f64),
}

#[derive(Debug, Clone)]
pub enum PlayerEvent {
    // Play request id changed
    PlayRequestIdChanged {
        play_request_id: u64,
    },
    // Fired when the player is stopped (e.g. by issuing a "stop" command to the player).
    Stopped {
        play_request_id: u64,
        track_id: SpotifyUri,
    },
    // The player is delayed by loading a track.
    Loading {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    // The player is preloading a track.
    Preloading {
        track_id: SpotifyUri,
    },
    // The player is playing a track.
    // This event is issued at the start of playback of whenever the position must be communicated
    // because it is out of sync. This includes:
    // start of a track
    // un-pausing
    // after a seek
    // after a buffer-underrun
    Playing {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    // The player entered a paused state.
    Paused {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    // The player thinks it's a good idea to issue a preload command for the next track now.
    // This event is intended for use within spirc.
    TimeToPreloadNextTrack {
        play_request_id: u64,
        track_id: SpotifyUri,
    },
    // The player reached the end of a track.
    // This event is intended for use within spirc. Spirc will respond by issuing another command.
    EndOfTrack {
        play_request_id: u64,
        track_id: SpotifyUri,
    },
    // The player was unable to load the requested track.
    Unavailable {
        play_request_id: u64,
        track_id: SpotifyUri,
        // SPOTIFYGOOD: why the load failed. Match with `..` to ignore it.
        reason: UnavailableReason,
    },
    // The mixer volume was set to a new level.
    VolumeChanged {
        volume: u16,
    },
    PositionCorrection {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    // SPOTIFYGOOD: see stall_action
    /// The stream of the playing track stalled: nothing plays from `position_ms` on until its data
    /// comes (a `PositionCorrection` then) or it pauses (`Paused`)
    Stalled {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    /// Requires `PlayerConfig::position_update_interval` to be set to Some.
    /// Once set this event will be sent periodically while playing the track to inform about the
    /// current playback position
    PositionChanged {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    Seeked {
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
    },
    TrackChanged {
        audio_item: Box<AudioItem>,
    },
    SessionConnected {
        connection_id: String,
        user_name: String,
    },
    SessionDisconnected {
        connection_id: String,
        user_name: String,
    },
    SessionClientChanged {
        client_id: String,
        client_name: String,
        client_brand_name: String,
        client_model_name: String,
    },
    ShuffleChanged {
        shuffle: bool,
    },
    RepeatChanged {
        context: bool,
        track: bool,
    },
    AutoPlayChanged {
        auto_play: bool,
    },
    FilterExplicitContentChanged {
        filter: bool,
    },
}

impl PlayerEvent {
    pub fn get_play_request_id(&self) -> Option<u64> {
        use PlayerEvent::*;
        match self {
            Loading {
                play_request_id, ..
            }
            | Unavailable {
                play_request_id, ..
            }
            | Playing {
                play_request_id, ..
            }
            | TimeToPreloadNextTrack {
                play_request_id, ..
            }
            | EndOfTrack {
                play_request_id, ..
            }
            | Paused {
                play_request_id, ..
            }
            | Stopped {
                play_request_id, ..
            }
            | PositionCorrection {
                play_request_id, ..
            }
            // SPOTIFYGOOD: Stalled
            | Stalled {
                play_request_id, ..
            }
            | Seeked {
                play_request_id, ..
            } => Some(*play_request_id),
            _ => None,
        }
    }
}

pub type PlayerEventChannel = mpsc::UnboundedReceiver<PlayerEvent>;

#[inline]
pub fn db_to_ratio(db: f64) -> f64 {
    f64::powf(10.0, db / DB_VOLTAGE_RATIO)
}

#[inline]
pub fn ratio_to_db(ratio: f64) -> f64 {
    ratio.log10() * DB_VOLTAGE_RATIO
}

pub fn duration_to_coefficient(duration: Duration) -> f64 {
    f64::exp(-1.0 / (duration.as_secs_f64() * SAMPLES_PER_SECOND as f64))
}

pub fn coefficient_to_duration(coefficient: f64) -> Duration {
    Duration::from_secs_f64(-1.0 / f64::ln(coefficient) / SAMPLES_PER_SECOND as f64)
}

#[derive(Clone, Copy, Debug)]
pub struct NormalisationData {
    // Spotify provides these as `f32`, but audio metadata can contain up to `f64`.
    // Also, this negates the need for casting during sample processing.
    pub track_gain_db: f64,
    pub track_peak: f64,
    pub album_gain_db: f64,
    pub album_peak: f64,
}

impl Default for NormalisationData {
    fn default() -> Self {
        Self {
            track_gain_db: 0.0,
            track_peak: 1.0,
            album_gain_db: 0.0,
            album_peak: 1.0,
        }
    }
}

impl NormalisationData {
    fn parse_from_ogg<T: Read + Seek>(mut file: T) -> io::Result<NormalisationData> {
        const SPOTIFY_NORMALIZATION_HEADER_START_OFFSET: u64 = 144;
        const NORMALISATION_DATA_SIZE: usize = 16;

        let newpos = file.seek(SeekFrom::Start(SPOTIFY_NORMALIZATION_HEADER_START_OFFSET))?;
        if newpos != SPOTIFY_NORMALIZATION_HEADER_START_OFFSET {
            error!(
                "NormalisationData::parse_from_file seeking to {SPOTIFY_NORMALIZATION_HEADER_START_OFFSET} but position is now {newpos}"
            );

            error!("Falling back to default (non-track and non-album) normalisation data.");

            return Ok(NormalisationData::default());
        }

        let mut buf = [0u8; NORMALISATION_DATA_SIZE];

        file.read_exact(&mut buf)?;

        let track_gain_db = f32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as f64;
        let track_peak = f32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as f64;
        let album_gain_db = f32::from_le_bytes([buf[8], buf[9], buf[10], buf[11]]) as f64;
        let album_peak = f32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]) as f64;

        Ok(Self {
            track_gain_db,
            track_peak,
            album_gain_db,
            album_peak,
        })
    }

    fn get_factor(config: &PlayerConfig, data: NormalisationData) -> f64 {
        if !config.normalisation {
            return 1.0;
        }

        let (gain_db, gain_peak) = if config.normalisation_type == NormalisationType::Album {
            (data.album_gain_db, data.album_peak)
        } else {
            (data.track_gain_db, data.track_peak)
        };

        // As per the ReplayGain 1.0 & 2.0 (proposed) spec:
        // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_1.0_specification#Clipping_prevention
        // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_2.0_specification#Clipping_prevention
        let normalisation_factor = if config.normalisation_method == NormalisationMethod::Basic {
            // For Basic Normalisation, factor = min(ratio of (ReplayGain + PreGain), 1.0 / peak level).
            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_1.0_specification#Peak_amplitude
            // https://wiki.hydrogenaud.io/index.php?title=ReplayGain_2.0_specification#Peak_amplitude
            // We then limit that to 1.0 as not to exceed dBFS (0.0 dB).
            let factor = f64::min(
                db_to_ratio(gain_db + config.normalisation_pregain_db),
                PCM_AT_0DBFS / gain_peak,
            );

            if factor > PCM_AT_0DBFS {
                info!(
                    "Lowering gain by {:.2} dB for the duration of this track to avoid potentially exceeding dBFS.",
                    ratio_to_db(factor)
                );

                PCM_AT_0DBFS
            } else {
                factor
            }
        } else {
            // For Dynamic Normalisation it's up to the player to decide,
            // factor = ratio of (ReplayGain + PreGain).
            // We then let the dynamic limiter handle gain reduction.
            let factor = db_to_ratio(gain_db + config.normalisation_pregain_db);
            let threshold_ratio = db_to_ratio(config.normalisation_threshold_dbfs);

            if factor > PCM_AT_0DBFS {
                let factor_db = gain_db + config.normalisation_pregain_db;
                let limiting_db = factor_db + config.normalisation_threshold_dbfs.abs();

                warn!(
                    "This track may exceed dBFS by {factor_db:.2} dB and be subject to {limiting_db:.2} dB of dynamic limiting at its peak."
                );
            } else if factor > threshold_ratio {
                let limiting_db = gain_db
                    + config.normalisation_pregain_db
                    + config.normalisation_threshold_dbfs.abs();

                info!(
                    "This track may be subject to {limiting_db:.2} dB of dynamic limiting at its peak."
                );
            }

            factor
        };

        debug!("Normalisation Data: {data:?}");
        debug!(
            "Calculated Normalisation Factor for {:?}: {:.2}%",
            config.normalisation_type,
            normalisation_factor * 100.0
        );

        normalisation_factor
    }
}

impl Player {
    pub fn new<F>(
        config: PlayerConfig,
        session: Session,
        volume_getter: Box<dyn VolumeGetter + Send>,
        sink_builder: F,
    ) -> Arc<Self>
    where
        F: FnOnce() -> Box<dyn Sink> + Send + 'static,
    {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        // SPOTIFYGOOD: see Player::last_decoded
        let decoded = SharedDecoded::default();
        let internal_decoded = decoded.clone();
        // SPOTIFYGOOD: see Player::fully_buffered
        let buffered = SharedBuffered::default();
        let internal_buffered = buffered.clone();

        if config.normalisation {
            debug!("Normalisation Type: {:?}", config.normalisation_type);
            debug!(
                "Normalisation Pregain: {:.1} dB",
                config.normalisation_pregain_db
            );
            debug!(
                "Normalisation Threshold: {:.1} dBFS",
                config.normalisation_threshold_dbfs
            );
            debug!("Normalisation Method: {:?}", config.normalisation_method);

            if config.normalisation_method == NormalisationMethod::Dynamic {
                // as_millis() has rounding errors (truncates)
                debug!(
                    "Normalisation Attack: {:.0} ms",
                    coefficient_to_duration(config.normalisation_attack_cf).as_secs_f64() * 1000.
                );
                debug!(
                    "Normalisation Release: {:.0} ms",
                    coefficient_to_duration(config.normalisation_release_cf).as_secs_f64() * 1000.
                );
                debug!("Normalisation Knee: {} dB", config.normalisation_knee_db);
            }
        }

        // SPOTIFYGOOD: named thread (shows up in traces and ANR dumps). Like `thread::spawn`,
        // this panics if the OS cannot create a thread.
        let handle = thread::Builder::new()
            .name("lrs-player".to_string())
            .spawn(move || {
            let player_id = PLAYER_COUNTER.fetch_add(1, Ordering::AcqRel);
            debug!("new Player [{player_id}]");

            let converter = Converter::new(config.ditherer);
            let normalisation_knee_factor = 1.0 / (8.0 * config.normalisation_knee_db);

            // TODO: it would be neat if we could watch for added or modified files in the
            // specified directories, and dynamically update the lookup. Currently, a new player
            // must be created for any new local files to be playable.
            let local_file_lookup =
                create_local_file_lookup(config.local_file_directories.as_slice());

            let internal = PlayerInternal {
                session,
                config,
                commands: cmd_rx,
                load_handles: Arc::new(Mutex::new(HashMap::new())),

                state: PlayerState::Stopped,
                preload: PlayerPreload::None,
                sink: sink_builder(),
                sink_status: SinkStatus::Closed,
                sink_event_callback: None,
                volume_getter,
                event_senders: vec![],
                converter,

                normalisation_peaks: [0.0; 2],
                normalisation_integrators: [0.0; 2],
                normalisation_channel: 0,
                normalisation_knee_factor,

                auto_normalise_as_album: false,

                player_id,
                play_request_id_generator: SeqGenerator::new(0),
                last_progress_update: Instant::now(),

                local_file_lookup: Arc::new(local_file_lookup),

                // SPOTIFYGOOD: see Player::set_playback_speed
                playback_speed: 1.,
                // SPOTIFYGOOD: see stall_action
                stream_stall: None,
                // SPOTIFYGOOD: see Player::last_decoded
                decoded: internal_decoded,
                // SPOTIFYGOOD: see Player::fully_buffered
                buffered: internal_buffered,
                // SPOTIFYGOOD: see PlayerInternal::reopen
                reopen: None,
            };

            // While PlayerInternal is written as a future, it still contains blocking code.
            // It must be run by using block_on() in a dedicated thread.
            // SPOTIFYGOOD: small, named multi-thread runtime instead of `Runtime::new()`, which
            // starts one worker per CPU core. See PLAYER_RUNTIME_WORKER_THREADS.
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(PLAYER_RUNTIME_WORKER_THREADS)
                .thread_name("lrs-player-rt")
                .enable_all()
                .build()
                .expect("Failed to create Tokio runtime");
            runtime.block_on(internal);
            // SPOTIFYGOOD: bounded (see PLAYER_RUNTIME_SHUTDOWN_TIMEOUT); a plain drop waits for
            // every blocking task without a limit.
            runtime.shutdown_timeout(PLAYER_RUNTIME_SHUTDOWN_TIMEOUT);

            debug!("PlayerInternal thread finished.");
        })
            .expect("Failed to spawn player thread"); // SPOTIFYGOOD: see thread::Builder above

        Arc::new(Self {
            commands: Some(cmd_tx),
            thread_handle: Some(handle),
            decoded,
            buffered,
        })
    }

    // SPOTIFYGOOD: the engine hands a playing track over to its offline queue when the session
    // goes away: a downloaded one, or a streamed one whose data is all there
    /// The playing (or paused) track if its file is all there (downloaded, or streamed to its
    /// end); read without a command
    pub fn fully_buffered(&self) -> Option<SpotifyUri> {
        lock_buffered(&self.buffered).clone()
    }

    // SPOTIFYGOOD: Connect's position of a playing track is extrapolated from its last anchor;
    // while a stream stalls nothing plays, but it goes on (the player reports the position only
    // when the data comes back). The engine caps a restore point with this.
    /// The last packet the player decoded (its track and position, and when), or the position it
    /// was last told to load or seek to; `None` after a stop
    pub fn last_decoded(&self) -> Option<DecodedPosition> {
        lock_decoded(&self.decoded).clone()
    }

    pub fn is_invalid(&self) -> bool {
        if let Some(handle) = self.thread_handle.as_ref() {
            return handle.is_finished();
        }
        true
    }

    fn command(&self, cmd: PlayerCommand) {
        if let Some(commands) = self.commands.as_ref() {
            if let Err(e) = commands.send(cmd) {
                error!("Player Commands Error: {e}");
            }
        }
    }

    pub fn load(&self, track_id: SpotifyUri, start_playing: bool, position_ms: u32) {
        // SPOTIFYGOOD: see last_decoded, also while the player thread is busy
        set_decoded(&self.decoded, &track_id, position_ms);
        self.command(PlayerCommand::Load {
            track_id,
            play: start_playing,
            position_ms,
        });
    }

    pub fn preload(&self, track_id: SpotifyUri) {
        self.command(PlayerCommand::Preload { track_id });
    }

    pub fn play(&self) {
        self.command(PlayerCommand::Play)
    }

    pub fn pause(&self) {
        self.command(PlayerCommand::Pause)
    }

    pub fn stop(&self) {
        self.command(PlayerCommand::Stop)
    }

    pub fn seek(&self, position_ms: u32) {
        // SPOTIFYGOOD: see last_decoded, also while the player thread is busy (blocked in the
        // read of a stalled stream)
        if let Some(decoded) = lock_decoded(&self.decoded).as_mut() {
            decoded.position_ms = position_ms;
            decoded.at = Instant::now();
        }
        self.command(PlayerCommand::Seek(position_ms));
    }

    pub fn set_session(&self, session: Session) {
        self.command(PlayerCommand::SetSession(session));
    }

    pub fn get_player_event_channel(&self) -> PlayerEventChannel {
        let (event_sender, event_receiver) = mpsc::unbounded_channel();
        self.command(PlayerCommand::AddEventSender(event_sender));
        event_receiver
    }

    pub async fn await_end_of_track(&self) {
        let mut channel = self.get_player_event_channel();
        while let Some(event) = channel.recv().await {
            if matches!(
                event,
                PlayerEvent::EndOfTrack { .. } | PlayerEvent::Stopped { .. }
            ) {
                return;
            }
        }
    }

    pub fn set_sink_event_callback(&self, callback: Option<SinkEventCallback>) {
        self.command(PlayerCommand::SetSinkEventCallback(callback));
    }

    pub fn emit_volume_changed_event(&self, volume: u16) {
        self.command(PlayerCommand::EmitVolumeChangedEvent(volume));
    }

    pub fn set_auto_normalise_as_album(&self, setting: bool) {
        self.command(PlayerCommand::SetAutoNormaliseAsAlbum(setting));
    }

    pub fn emit_filter_explicit_content_changed_event(&self, filter: bool) {
        self.command(PlayerCommand::EmitFilterExplicitContentChangedEvent(filter));
    }

    pub fn emit_session_connected_event(&self, connection_id: String, user_name: String) {
        self.command(PlayerCommand::EmitSessionConnectedEvent {
            connection_id,
            user_name,
        });
    }

    pub fn emit_session_disconnected_event(&self, connection_id: String, user_name: String) {
        self.command(PlayerCommand::EmitSessionDisconnectedEvent {
            connection_id,
            user_name,
        });
    }

    pub fn emit_session_client_changed_event(
        &self,
        client_id: String,
        client_name: String,
        client_brand_name: String,
        client_model_name: String,
    ) {
        self.command(PlayerCommand::EmitSessionClientChangedEvent {
            client_id,
            client_name,
            client_brand_name,
            client_model_name,
        });
    }

    pub fn emit_shuffle_changed_event(&self, shuffle: bool) {
        self.command(PlayerCommand::EmitShuffleChangedEvent(shuffle));
    }

    pub fn emit_repeat_changed_event(&self, context: bool, track: bool) {
        self.command(PlayerCommand::EmitRepeatChangedEvent { context, track });
    }

    pub fn emit_auto_play_changed_event(&self, auto_play: bool) {
        self.command(PlayerCommand::EmitAutoPlayChangedEvent(auto_play));
    }

    // SPOTIFYGOOD: replace (or remove with `None`) the downloaded-file source at runtime.
    // It applies to loads and preloads started after the command is processed. A track that
    // is already loaded or preloaded keeps the source it was loaded with.
    pub fn set_offline_source(&self, source: Option<OfflineSourceRef>) {
        self.command(PlayerCommand::SetOfflineSource(source));
    }

    // SPOTIFYGOOD: change the streaming bitrate preference at runtime. It applies to loads and
    // preloads started after the command is processed. The current track and a ready preload
    // keep their bitrate; to re-buffer the current track at the new bitrate, `stop()` and
    // `load()` it again at the current position.
    pub fn set_bitrate(&self, bitrate: Bitrate) {
        self.command(PlayerCommand::SetBitrate(bitrate));
    }

    // SPOTIFYGOOD: change normalisation at runtime. It takes effect at the next audio packet,
    // and the current track's gain factor is recomputed from its normalisation data.
    pub fn set_normalisation(&self, settings: NormalisationSettings) {
        self.command(PlayerCommand::SetNormalisation(settings));
    }

    // SPOTIFYGOOD: change gapless playback at runtime. `config.gapless` is only read when a load
    // starts (`handle_command_load`), so it applies from the next track change on.
    pub fn set_gapless(&self, gapless: bool) {
        self.command(PlayerCommand::SetGapless(gapless));
    }

    // SPOTIFYGOOD: the speed the sink plays at (the app's podcast speed; 1 for music). The
    // position corrections are measured against the line of that speed (see
    // nominal_start_time); the playing track's line is re-based at its position when it
    // changes. It changes nothing else: the sink applies the speed itself. Not finite or not
    // positive counts as 1.
    pub fn set_playback_speed(&self, speed: f64) {
        self.command(PlayerCommand::SetPlaybackSpeed(speed));
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        debug!("Shutting down player thread ...");
        self.commands = None;
        if let Some(handle) = self.thread_handle.take() {
            if let Err(e) = handle.join() {
                error!("Player thread Error: {e:?}");
            }
        }
    }
}

struct PlayerLoadedTrackData {
    decoder: Decoder,
    normalisation_data: NormalisationData,
    stream_loader_controller: StreamLoaderController,
    audio_item: AudioItem,
    bytes_per_second: usize,
    duration_ms: u32,
    stream_position_ms: u32,
    is_explicit: bool,
}

enum PlayerPreload {
    None,
    Loading {
        track_id: SpotifyUri,
        // SPOTIFYGOOD: loaders fail with an UnavailableReason instead of `()`.
        loader: Pin<Box<dyn FusedFuture<Output = Result<PlayerLoadedTrackData, UnavailableReason>> + Send>>,
    },
    Ready {
        track_id: SpotifyUri,
        loaded_track: Box<PlayerLoadedTrackData>,
    },
}

type Decoder = Box<dyn AudioDecoder + Send>;

enum PlayerState {
    Stopped,
    Loading {
        track_id: SpotifyUri,
        play_request_id: u64,
        start_playback: bool,
        // SPOTIFYGOOD: loaders fail with an UnavailableReason instead of `()`.
        loader: Pin<Box<dyn FusedFuture<Output = Result<PlayerLoadedTrackData, UnavailableReason>> + Send>>,
    },
    Paused {
        track_id: SpotifyUri,
        play_request_id: u64,
        decoder: Decoder,
        audio_item: AudioItem,
        normalisation_data: NormalisationData,
        normalisation_factor: f64,
        stream_loader_controller: StreamLoaderController,
        bytes_per_second: usize,
        duration_ms: u32,
        stream_position_ms: u32,
        suggested_to_preload_next_track: bool,
        is_explicit: bool,
    },
    Playing {
        track_id: SpotifyUri,
        play_request_id: u64,
        decoder: Decoder,
        normalisation_data: NormalisationData,
        audio_item: AudioItem,
        normalisation_factor: f64,
        stream_loader_controller: StreamLoaderController,
        bytes_per_second: usize,
        duration_ms: u32,
        stream_position_ms: u32,
        reported_nominal_start_time: Option<Instant>,
        suggested_to_preload_next_track: bool,
        is_explicit: bool,
    },
    EndOfTrack {
        track_id: SpotifyUri,
        play_request_id: u64,
        loaded_track: PlayerLoadedTrackData,
    },
    Invalid,
}

impl PlayerState {
    fn is_playing(&self) -> bool {
        use self::PlayerState::*;
        match *self {
            Stopped | EndOfTrack { .. } | Paused { .. } | Loading { .. } => false,
            Playing { .. } => true,
            Invalid => {
                error!("PlayerState::is_playing in invalid state");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("PlayerState::is_playing in invalid state");
            }
        }
    }

    #[allow(dead_code)]
    fn is_stopped(&self) -> bool {
        use self::PlayerState::*;
        matches!(self, Stopped)
    }

    #[allow(dead_code)]
    fn is_loading(&self) -> bool {
        use self::PlayerState::*;
        matches!(self, Loading { .. })
    }

    fn decoder(&mut self) -> Option<&mut Decoder> {
        use self::PlayerState::*;
        match *self {
            Stopped | EndOfTrack { .. } | Loading { .. } => None,
            Paused {
                ref mut decoder, ..
            }
            | Playing {
                ref mut decoder, ..
            } => Some(decoder),
            Invalid => {
                error!("PlayerState::decoder in invalid state");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("PlayerState::decoder in invalid state");
            }
        }
    }

    fn playing_to_end_of_track(&mut self) {
        use self::PlayerState::*;
        let new_state = mem::replace(self, Invalid);
        match new_state {
            Playing {
                track_id,
                play_request_id,
                decoder,
                duration_ms,
                bytes_per_second,
                normalisation_data,
                stream_loader_controller,
                stream_position_ms,
                is_explicit,
                audio_item,
                ..
            } => {
                *self = EndOfTrack {
                    track_id,
                    play_request_id,
                    loaded_track: PlayerLoadedTrackData {
                        decoder,
                        normalisation_data,
                        stream_loader_controller,
                        audio_item,
                        bytes_per_second,
                        duration_ms,
                        stream_position_ms,
                        is_explicit,
                    },
                };
            }
            _ => {
                error!("Called playing_to_end_of_track in non-playing state: {new_state:?}");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("playing_to_end_of_track in non-playing state");
            }
        }
    }

    // SPOTIFYGOOD: `speed`, see nominal_start_time
    fn paused_to_playing(&mut self, speed: f64) {
        use self::PlayerState::*;
        let new_state = mem::replace(self, Invalid);
        match new_state {
            Paused {
                track_id,
                play_request_id,
                decoder,
                audio_item,
                normalisation_data,
                normalisation_factor,
                stream_loader_controller,
                duration_ms,
                bytes_per_second,
                stream_position_ms,
                suggested_to_preload_next_track,
                is_explicit,
            } => {
                *self = Playing {
                    track_id,
                    play_request_id,
                    decoder,
                    audio_item,
                    normalisation_data,
                    normalisation_factor,
                    stream_loader_controller,
                    duration_ms,
                    bytes_per_second,
                    stream_position_ms,
                    // SPOTIFYGOOD: on the line of the speed, see nominal_start_time
                    reported_nominal_start_time: nominal_start_time(
                        Instant::now(),
                        Duration::from_millis(stream_position_ms as u64),
                        speed,
                    ),
                    suggested_to_preload_next_track,
                    is_explicit,
                };
            }
            _ => {
                error!("PlayerState::paused_to_playing in invalid state: {new_state:?}");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("PlayerState::paused_to_playing in invalid state");
            }
        }
    }

    fn playing_to_paused(&mut self) {
        use self::PlayerState::*;
        let new_state = mem::replace(self, Invalid);
        match new_state {
            Playing {
                track_id,
                play_request_id,
                decoder,
                audio_item,
                normalisation_data,
                normalisation_factor,
                stream_loader_controller,
                duration_ms,
                bytes_per_second,
                stream_position_ms,
                suggested_to_preload_next_track,
                is_explicit,
                ..
            } => {
                *self = Paused {
                    track_id,
                    play_request_id,
                    decoder,
                    audio_item,
                    normalisation_data,
                    normalisation_factor,
                    stream_loader_controller,
                    duration_ms,
                    bytes_per_second,
                    stream_position_ms,
                    suggested_to_preload_next_track,
                    is_explicit,
                };
            }
            _ => {
                error!("PlayerState::playing_to_paused in invalid state: {new_state:?}");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("PlayerState::playing_to_paused in invalid state");
            }
        }
    }
}

struct PlayerTrackLoader {
    session: Session,
    config: PlayerConfig,
    local_file_lookup: Arc<LocalFileLookup>,
}

impl PlayerTrackLoader {
    async fn find_available_alternative(&self, audio_item: AudioItem) -> Option<AudioItem> {
        if let Err(e) = audio_item.availability {
            error!("Track is unavailable: {e}");
            None
        } else if !audio_item.files.is_empty() {
            Some(audio_item)
        } else if let Some(alternatives) = audio_item.alternatives {
            let Tracks(alternatives_vec) = alternatives; // required to make `into_iter` able to move

            let alternatives: FuturesUnordered<_> = alternatives_vec
                .into_iter()
                .map(|alt_id| AudioItem::get_file(&self.session, alt_id))
                .collect();

            alternatives
                .filter_map(|x| future::ready(x.ok()))
                .filter(|x| future::ready(x.availability.is_ok()))
                .next()
                .await
        } else {
            error!("Track should be available, but no alternatives found.");
            None
        }
    }

    fn stream_data_rate(&self, format: AudioFileFormat) -> Option<usize> {
        let kbps = match format {
            AudioFileFormat::OGG_VORBIS_96 => 12.,
            AudioFileFormat::OGG_VORBIS_160 => 20.,
            AudioFileFormat::OGG_VORBIS_320 => 40.,
            AudioFileFormat::MP3_256 => 32.,
            AudioFileFormat::MP3_320 => 40.,
            AudioFileFormat::MP3_160 => 20.,
            AudioFileFormat::MP3_96 => 12.,
            AudioFileFormat::MP3_160_ENC => 20.,
            AudioFileFormat::AAC_24 => 3.,
            AudioFileFormat::AAC_48 => 6.,
            AudioFileFormat::AAC_160 => 20.,
            AudioFileFormat::AAC_320 => 40.,
            AudioFileFormat::MP4_128 => 16.,
            AudioFileFormat::OTHER5 => 40.,
            AudioFileFormat::FLAC_FLAC => 112., // assume 900 kbit/s on average
            AudioFileFormat::XHE_AAC_12 => 1.5,
            AudioFileFormat::XHE_AAC_16 => 2.,
            AudioFileFormat::XHE_AAC_24 => 3.,
            AudioFileFormat::FLAC_FLAC_24BIT => 3.,
        };
        let data_rate: f32 = kbps * 1024.;
        Some(data_rate.ceil() as usize)
    }

    // SPOTIFYGOOD: returns why the load failed instead of `None` (here and in the
    // load_*_track functions below).
    async fn load_track(
        &self,
        track_uri: SpotifyUri,
        position_ms: u32,
    ) -> Result<PlayerLoadedTrackData, UnavailableReason> {
        match track_uri {
            SpotifyUri::Track { .. } | SpotifyUri::Episode { .. } => {
                self.load_remote_track(track_uri, position_ms).await
            }
            SpotifyUri::Local { .. } => self.load_local_track(track_uri, position_ms).await,
            _ => {
                error!("Cannot handle load of track with URI: <{track_uri}>",);
                Err(UnavailableReason::Other)
            }
        }
    }

    // SPOTIFYGOOD: audio-key request with retries for transient failures (librespot #1649,
    // PR #1763). A permanent denial is returned at once as `KeyDenied`. While the shared
    // cool-down runs (AUDIO_KEY_COOLDOWN) only one attempt is made.
    async fn request_audio_key(
        &self,
        track_id: SpotifyId,
        file_id: FileId,
    ) -> Result<AudioKey, UnavailableReason> {
        let max_retries = audio_key_brake().retries(Instant::now());
        let mut retries = 0;
        loop {
            let err = match self.session.audio_key().request(track_id, file_id).await {
                Ok(key) => {
                    audio_key_brake().succeeded();
                    return Ok(key);
                }
                Err(err) => err,
            };
            match classify_audio_key_error(&err, &self.session) {
                KeyFailure::Permanent => {
                    error!("Spotify refused the audio key for file {file_id}: {err}");
                    return Err(UnavailableReason::KeyDenied);
                }
                KeyFailure::NoSession => {
                    warn!("Unable to request audio key, session is not connected: {err}");
                    return Err(UnavailableReason::KeyTemporarilyDenied);
                }
                KeyFailure::Transient if retries < max_retries => {
                    retries += 1;
                    warn!(
                        "Audio key request failed: {err}; retry {retries}/{max_retries} in {AUDIO_KEY_RETRY_DELAY:?}"
                    );
                    tokio::time::sleep(AUDIO_KEY_RETRY_DELAY).await;
                }
                KeyFailure::Transient => {
                    if max_retries == 0 {
                        warn!("Audio key request failed while keys are cooling down: {err}");
                    } else {
                        warn!("Audio key request failed after {max_retries} retries: {err}");
                    }
                    audio_key_brake().exhausted(Instant::now());
                    return Err(UnavailableReason::KeyTemporarilyDenied);
                }
            }
        }
    }

    async fn load_remote_track(
        &self,
        track_uri: SpotifyUri,
        position_ms: u32,
    ) -> Result<PlayerLoadedTrackData, UnavailableReason> {
        let track_id: SpotifyId = match (&track_uri).try_into() {
            Ok(id) => id,
            Err(_) => {
                warn!("<{track_uri}> could not be converted to a base62 ID");
                return Err(UnavailableReason::Other);
            }
        };

        // SPOTIFYGOOD: downloaded files are played without any network access: no metadata,
        // alternatives, audio-key or CDN request, and the librespot cache is not used.
        let offline = self
            .config
            .offline_source
            .as_ref()
            .and_then(|source| source.lookup(&track_uri));

        let audio_item = if let Some(ref offline) = offline {
            // SPOTIFYGOOD: only local checks: availability as supplied by the engine, and the
            // explicit filter from session attributes (no network).
            if let Err(e) = offline.audio_item.availability {
                warn!("Offline item <{}> is not available: {e}", offline.audio_item.uri);
                return Err(UnavailableReason::NotAvailable);
            }
            if offline.audio_item.is_explicit && self.session.filter_explicit_content() {
                warn!("Offline item <{}> is explicit, which client setting forbids", offline.audio_item.uri);
                return Err(UnavailableReason::NotAvailable);
            }
            offline.audio_item.clone()
        } else {
            match AudioItem::get_file(&self.session, track_uri).await {
                Ok(audio) => match self.find_available_alternative(audio).await {
                    Some(audio) => audio,
                    None => {
                        warn!(
                            "spotify:track:<{}> is not available",
                            track_id.to_base62().unwrap_or_default()
                        );
                        return Err(UnavailableReason::NotAvailable);
                    }
                },
                Err(e) => {
                    error!("Unable to load audio item: {e:?}");
                    // SPOTIFYGOOD: MetadataError (except Empty) is Spotify's verdict;
                    // anything else is a failure to fetch.
                    return Err(match e.error.downcast_ref::<MetadataError>() {
                        Some(MetadataError::Empty) | None => UnavailableReason::NetworkError,
                        Some(_) => UnavailableReason::NotAvailable,
                    });
                }
            }
        };

        info!(
            "Loading <{}> with Spotify URI <{}>",
            audio_item.name, audio_item.uri
        );

        // (Most) podcasts seem to support only 96 kbps Ogg Vorbis, so fall back to it
        let formats = match self.config.bitrate {
            Bitrate::Bitrate96 => [
                AudioFileFormat::OGG_VORBIS_96,
                AudioFileFormat::MP3_96,
                AudioFileFormat::OGG_VORBIS_160,
                AudioFileFormat::MP3_160,
                AudioFileFormat::MP3_256,
                AudioFileFormat::OGG_VORBIS_320,
                AudioFileFormat::MP3_320,
            ],
            Bitrate::Bitrate160 => [
                AudioFileFormat::OGG_VORBIS_160,
                AudioFileFormat::MP3_160,
                AudioFileFormat::OGG_VORBIS_96,
                AudioFileFormat::MP3_96,
                AudioFileFormat::MP3_256,
                AudioFileFormat::OGG_VORBIS_320,
                AudioFileFormat::MP3_320,
            ],
            Bitrate::Bitrate320 => [
                AudioFileFormat::OGG_VORBIS_320,
                AudioFileFormat::MP3_320,
                AudioFileFormat::MP3_256,
                AudioFileFormat::OGG_VORBIS_160,
                AudioFileFormat::MP3_160,
                AudioFileFormat::OGG_VORBIS_96,
                AudioFileFormat::MP3_96,
            ],
        };

        // SPOTIFYGOOD: an offline track uses the format and file id of the downloaded file.
        let selected = match offline {
            Some(ref offline) => Some((offline.format, offline.file_id)),
            None => formats
                .iter()
                .find_map(|format| match audio_item.files.get(format) {
                    Some(&file_id) => Some((*format, file_id)),
                    _ => None,
                }),
        };
        let (format, file_id) = match selected {
            Some(t) => t,
            None => {
                warn!(
                    "<{}> is not available in any supported format",
                    audio_item.name
                );
                return Err(UnavailableReason::NotAvailable);
            }
        };

        // SPOTIFYGOOD: `?` on Option replaced (the function returns a Result now).
        let bytes_per_second = self
            .stream_data_rate(format)
            .ok_or(UnavailableReason::Other)?;

        // SPOTIFYGOOD: reason to report when reading the file fails.
        let io_failure = if offline.is_some() {
            UnavailableReason::OfflineFileError
        } else {
            UnavailableReason::NetworkError
        };

        // This is only a loop to be able to reload the file if an error occurred
        // while opening a cached file.
        loop {
            // SPOTIFYGOOD: an offline file is opened directly (never the CDN or the cache).
            let encrypted_file = if let Some(ref offline) = offline {
                match File::open(&offline.path) {
                    Ok(file) => AudioFile::Cached(file),
                    Err(e) => {
                        error!("Unable to open offline file {:?}: {e}", offline.path);
                        return Err(UnavailableReason::OfflineFileError);
                    }
                }
            } else {
                match AudioFile::open(&self.session, file_id, bytes_per_second).await {
                    Ok(encrypted_file) => encrypted_file,
                    Err(e) => {
                        error!("Unable to load encrypted file: {e:?}");
                        return Err(UnavailableReason::NetworkError);
                    }
                }
            };

            // SPOTIFYGOOD: offline files are not owned by the librespot cache, so they are never
            // deleted or downloaded again below.
            let is_cached = offline.is_none() && encrypted_file.is_cached();

            // SPOTIFYGOOD: `.ok()?` replaced (the function returns a Result now).
            let stream_loader_controller = match encrypted_file.get_stream_loader_controller() {
                Ok(controller) => controller,
                Err(e) => {
                    error!("Unable to read audio file size: {e}");
                    return Err(io_failure);
                }
            };

            // Not all audio files are encrypted. If we can't get a key, try loading the track
            // without decryption. If the file was encrypted after all, the decoder will fail
            // parsing and bail out, so we should be safe from outputting ear-piercing noise.
            // SPOTIFYGOOD: offline tracks use the stored key and never request one. Online, the
            // request is retried on transient failures; a permanent denial aborts the load
            // (no attempt without decryption). The failure is remembered so that a decode
            // error is reported as a key problem and never deletes a cached file.
            let mut key_failure: Option<UnavailableReason> = None;
            let key = match offline {
                Some(ref offline) => offline.key,
                None => match self.request_audio_key(track_id, file_id).await {
                    Ok(key) => Some(key),
                    Err(UnavailableReason::KeyDenied) => return Err(UnavailableReason::KeyDenied),
                    Err(reason) => {
                        warn!("Unable to load key, continuing without decryption");
                        key_failure = Some(reason);
                        None
                    }
                },
            };

            let mut decrypted_file = AudioDecrypt::new(key, encrypted_file);

            let is_ogg_vorbis = AudioFiles::is_ogg_vorbis(format);
            let (offset, mut normalisation_data) = if is_ogg_vorbis {
                // Spotify stores normalisation data in a custom Ogg packet instead of Vorbis comments.
                let normalisation_data =
                    NormalisationData::parse_from_ogg(&mut decrypted_file).ok();
                (SPOTIFY_OGG_HEADER_END, normalisation_data)
            } else {
                (0, None)
            };

            let audio_file = match Subfile::new(
                decrypted_file,
                offset,
                stream_loader_controller.len() as u64,
            ) {
                Ok(audio_file) => audio_file,
                Err(e) => {
                    error!("PlayerTrackLoader::load_track error opening subfile: {e}");
                    return Err(io_failure); // SPOTIFYGOOD: reason
                }
            };

            let mut symphonia_decoder = |audio_file, format| {
                SymphoniaDecoder::new(audio_file, format).map(|mut decoder| {
                    // For formats other that Vorbis, we'll try getting normalisation data from
                    // ReplayGain metadata fields, if present.
                    if normalisation_data.is_none() {
                        normalisation_data = decoder.normalisation_data();
                    }
                    Box::new(decoder) as Decoder
                })
            };

            let mut hint = Hint::new();
            if let Some(mime_type) = AudioFiles::mime_type(format) {
                hint.mime_type(mime_type);
            }

            #[cfg(feature = "passthrough-decoder")]
            let decoder_type = if self.config.passthrough {
                PassthroughDecoder::new(audio_file, format).map(|x| Box::new(x) as Decoder)
            } else {
                symphonia_decoder(audio_file, hint)
            };

            #[cfg(not(feature = "passthrough-decoder"))]
            let decoder_type = { symphonia_decoder(audio_file, hint) };

            let normalisation_data = normalisation_data.unwrap_or_else(|| {
                warn!("Unable to get normalisation data, continuing with defaults.");
                NormalisationData::default()
            });

            let mut decoder = match decoder_type {
                Ok(decoder) => decoder,
                // SPOTIFYGOOD: only delete and download a cached file again when we had its key.
                // A decode failure after a key failure says nothing about the file.
                Err(e) if is_cached && key_failure.is_none() => {
                    warn!("Unable to read cached audio file: {e}. Trying to download it.");

                    match self.session.cache() {
                        Some(cache) => {
                            if cache.remove_file(file_id).is_err() {
                                error!("Error removing file from cache");
                                return Err(UnavailableReason::Other); // SPOTIFYGOOD: reason
                            }
                        }
                        None => {
                            error!("If the audio file is cached, a cache should exist");
                            return Err(UnavailableReason::Other); // SPOTIFYGOOD: reason
                        }
                    }

                    // Just try it again
                    continue;
                }
                Err(e) => {
                    error!("Unable to read audio file: {e}");
                    // SPOTIFYGOOD: report a key failure as such; otherwise a decode error.
                    return Err(key_failure.unwrap_or(UnavailableReason::DecodeError));
                }
            };

            let duration_ms = audio_item.duration_ms;
            // Don't try to seek past the track's duration.
            // If the position is invalid just start from
            // the beginning of the track.
            let position_ms = if position_ms > duration_ms {
                warn!(
                    "Invalid start position of {position_ms} ms exceeds track's duration of {duration_ms} ms, starting track from the beginning"
                );
                0
            } else {
                position_ms
            };

            // Ensure the starting position. Even when we want to play from the beginning,
            // the cursor may have been moved by parsing normalisation data. This may not
            // matter for playback (but won't hurt either), but may be useful for the
            // passthrough decoder.
            let stream_position_ms = match decoder.seek(position_ms) {
                Ok(new_position_ms) => new_position_ms,
                Err(e) => {
                    error!(
                        "PlayerTrackLoader::load_track error seeking to starting position {position_ms}: {e}"
                    );
                    return Err(UnavailableReason::DecodeError); // SPOTIFYGOOD: reason
                }
            };

            // Ensure streaming mode now that we are ready to play from the requested position.
            stream_loader_controller.set_stream_mode();

            let is_explicit = audio_item.is_explicit;

            info!("<{}> ({} ms) loaded", audio_item.name, duration_ms);

            return Ok(PlayerLoadedTrackData { // SPOTIFYGOOD: Result
                decoder,
                normalisation_data,
                stream_loader_controller,
                audio_item,
                bytes_per_second,
                duration_ms,
                stream_position_ms,
                is_explicit,
            });
        }
    }

    async fn load_local_track(
        &self,
        track_uri: SpotifyUri,
        position_ms: u32,
    ) -> Result<PlayerLoadedTrackData, UnavailableReason> {
        // SPOTIFYGOOD: every `None` in this function became an `Err(reason)`.
        info!("Loading local file with Spotify URI <{}>", track_uri);

        let SpotifyUri::Local { duration, .. } = track_uri else {
            error!("Unable to determine track duration for local file: not a local file URI");
            return Err(UnavailableReason::Other);
        };

        let entry = self.local_file_lookup.get(&track_uri);

        let Some(path) = entry else {
            error!("Unable to find file path for local file <{track_uri}>");
            return Err(UnavailableReason::Other);
        };

        let src = match File::open(path) {
            Ok(src) => src,
            Err(e) => {
                error!("Failed to open local file: {e}");
                return Err(UnavailableReason::Other);
            }
        };

        let mut hint = Hint::new();
        if let Some(file_extension) = path.extension().and_then(|e| e.to_str()) {
            hint.with_extension(file_extension);
        }

        let decoder = match SymphoniaDecoder::new(src, hint) {
            Ok(decoder) => decoder,
            Err(e) => {
                error!("Error decoding local file: {e}");
                return Err(UnavailableReason::DecodeError);
            }
        };

        let mut decoder = Box::new(decoder);
        let normalisation_data = decoder.normalisation_data().unwrap_or_else(|| {
            warn!("Unable to get normalisation data, continuing with defaults.");
            NormalisationData::default()
        });

        let local_file_metadata = decoder.local_file_metadata().unwrap_or_default();

        let stream_position_ms = match decoder.seek(position_ms) {
            Ok(new_position_ms) => new_position_ms,
            Err(e) => {
                error!(
                    "PlayerTrackLoader::load_local_track error seeking to starting position {position_ms}: {e}"
                );
                return Err(UnavailableReason::DecodeError);
            }
        };

        let file_size = fs::metadata(path)
            .map_err(|_| UnavailableReason::Other)?
            .len();
        // SPOTIFYGOOD: `.max(1)` avoids a division-by-zero panic for files shorter than 1 s.
        let bytes_per_second = (file_size / duration.as_secs().max(1)) as usize;

        let stream_loader_controller = StreamLoaderController::from_local_file(file_size);

        let name = local_file_metadata.name.unwrap_or_default();

        info!("Loaded <{name}> from path <{}>", path.display());

        Ok(PlayerLoadedTrackData {
            decoder,
            normalisation_data,
            stream_loader_controller,
            bytes_per_second,
            duration_ms: duration.as_millis() as u32,
            stream_position_ms,
            is_explicit: false,
            audio_item: AudioItem {
                duration_ms: duration.as_millis() as u32,
                uri: track_uri.to_uri().unwrap_or_default(),
                track_id: track_uri,
                files: Default::default(),
                name,
                // We can't get a CoverImage.URL for the track image, applications will have to parse the file metadata themselves using unique_fields.path
                covers: vec![],
                language: local_file_metadata
                    .language
                    .map(|val| vec![val])
                    .unwrap_or_default(),
                is_explicit: false,
                availability: Ok(()),
                alternatives: None,
                unique_fields: UniqueFields::Local {
                    artists: local_file_metadata.artists,
                    album: local_file_metadata.album,
                    album_artists: local_file_metadata.album_artists,
                    number: local_file_metadata.number,
                    disc_number: local_file_metadata.disc_number,
                    path: path.to_path_buf(),
                },
            },
        })
    }
}

impl Future for PlayerInternal {
    type Output = ();

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<()> {
        // While this is written as a future, it still contains blocking code.
        // It must be run on its own thread.
        let passthrough = self.config.passthrough;

        loop {
            let mut all_futures_completed_or_not_ready = true;

            // process commands that were sent to us
            let cmd = match self.commands.poll_recv(cx) {
                Poll::Ready(None) => return Poll::Ready(()), // client has disconnected - shut down.
                Poll::Ready(Some(cmd)) => {
                    all_futures_completed_or_not_ready = false;
                    Some(cmd)
                }
                _ => None,
            };

            if let Some(cmd) = cmd {
                if let Err(e) = self.handle_command(cmd) {
                    error!("Error handling command: {e}");
                }
            }

            // Handle loading of a new track to play
            if let PlayerState::Loading {
                ref mut loader,
                ref track_id,
                start_playback,
                play_request_id,
            } = self.state
            {
                // The loader may be terminated if we are trying to load the same track
                // as before, and that track failed to open before.
                let track_id = track_id.clone();

                if !loader.as_mut().is_terminated() {
                    match loader.as_mut().poll(cx) {
                        Poll::Ready(Ok(loaded_track)) => {
                            self.start_playback(
                                track_id,
                                play_request_id,
                                loaded_track,
                                start_playback,
                            );
                            if let PlayerState::Loading { .. } = self.state {
                                error!("The state wasn't changed by start_playback()");
                                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                                // becomes true and the engine can create a new Player) instead of the whole app.
                                panic!("The state wasn't changed by start_playback()");
                            }
                        }
                        Poll::Ready(Err(e)) => {
                            error!(
                                "Skipping to next track, unable to load track <{track_id:?}>: {e:?}"
                            );
                            self.send_event(PlayerEvent::Unavailable {
                                track_id,
                                play_request_id,
                                reason: e, // SPOTIFYGOOD: reason
                            })
                        }
                        Poll::Pending => (),
                    }
                }
            }

            // handle pending preload requests.
            if let PlayerPreload::Loading {
                ref mut loader,
                ref track_id,
            } = self.preload
            {
                let track_id = track_id.clone();
                match loader.as_mut().poll(cx) {
                    Poll::Ready(Ok(loaded_track)) => {
                        self.send_event(PlayerEvent::Preloading {
                            track_id: track_id.clone(),
                        });
                        self.preload = PlayerPreload::Ready {
                            track_id,
                            loaded_track: Box::new(loaded_track),
                        };
                    }
                    Poll::Ready(Err(reason)) => {
                        // SPOTIFYGOOD: keep the reason for the Unavailable event.
                        debug!("Unable to preload {track_id:?}: {reason:?}");
                        self.preload = PlayerPreload::None;
                        // Let Spirc know that the track was unavailable.
                        if let PlayerState::Playing {
                            play_request_id, ..
                        }
                        | PlayerState::Paused {
                            play_request_id, ..
                        } = self.state
                        {
                            self.send_event(PlayerEvent::Unavailable {
                                track_id,
                                play_request_id,
                                reason, // SPOTIFYGOOD: reason
                            });
                        }
                    }
                    Poll::Pending => (),
                }
            }

            if self.state.is_playing() {
                self.ensure_sink_running();
                // SPOTIFYGOOD: see nominal_start_time
                let speed = self.playback_speed;
                // SPOTIFYGOOD: see stall_action
                let stall = self.stream_stall;
                let session_valid = !self.session.is_invalid();
                // SPOTIFYGOOD: see Player::last_decoded
                let decoded = self.decoded.clone();

                if let PlayerState::Playing {
                    ref track_id,
                    play_request_id,
                    ref mut decoder,
                    normalisation_factor,
                    ref mut stream_position_ms,
                    ref mut reported_nominal_start_time,
                    ref stream_loader_controller,
                    bytes_per_second,
                    ..
                } = self.state
                {
                    let track_id = track_id.clone();
                    // SPOTIFYGOOD: a stalled track waits for its data (see wait_for_stalled_data),
                    // then it reads again from the position played (its reader may have stopped
                    // in the middle of a page)
                    let stall = stall.filter(|stall| stall.play_request_id == play_request_id);
                    let mut data_came = false;
                    let next_packet = match stall {
                        Some(stall) if stall.waiting => {
                            if wait_for_stalled_data(stream_loader_controller, bytes_per_second) {
                                data_came = true;
                                decoder
                                    .seek(stall.position_ms)
                                    .and_then(|_| decoder.next_packet())
                            } else {
                                Err(DecoderError::Stalled("no data in time".into()))
                            }
                        }
                        _ => decoder.next_packet(),
                    };
                    match next_packet {
                        Ok(result) => {
                            // SPOTIFYGOOD: the data came after a stall (its first packet reports
                            // its position, the line was cleared at the stall)
                            if data_came {
                                info!("Stream of <{track_id:?}> goes on");
                            }
                            if let Some((ref packet_position, ref packet)) = result {
                                let new_stream_position_ms = packet_position.position_ms;
                                // SPOTIFYGOOD: see Player::last_decoded
                                set_decoded(&decoded, &track_id, new_stream_position_ms);
                                let expected_position_ms = std::mem::replace(
                                    &mut *stream_position_ms,
                                    new_stream_position_ms,
                                );

                                if !passthrough {
                                    match packet.samples() {
                                        Ok(_) => {
                                            let new_stream_position = Duration::from_millis(
                                                new_stream_position_ms as u64,
                                            );

                                            let now = Instant::now();

                                            // Only notify if we're skipped some packets *or* we are behind.
                                            // If we're ahead it's probably due to a buffer of the backend
                                            // and we're actually in time.
                                            let notify_about_position =
                                                match *reported_nominal_start_time {
                                                    None => true,
                                                    Some(reported_nominal_start_time) => {
                                                        let mut notify = false;

                                                        if packet_position.skipped {
                                                            if let Some(ahead) = new_stream_position
                                                                .checked_sub(Duration::from_millis(
                                                                    expected_position_ms as u64,
                                                                ))
                                                            {
                                                                notify |=
                                                                    ahead >= Duration::from_secs(1)
                                                            }
                                                        }

                                                        // SPOTIFYGOOD: behind the line of the
                                                        // speed (see nominal_start_time)
                                                        notify |= lags_behind(
                                                            now,
                                                            reported_nominal_start_time,
                                                            new_stream_position,
                                                            speed,
                                                        );

                                                        notify
                                                    }
                                                };

                                            if notify_about_position {
                                                // SPOTIFYGOOD: on the line of the speed
                                                *reported_nominal_start_time = nominal_start_time(
                                                    now,
                                                    new_stream_position,
                                                    speed,
                                                );
                                                self.send_event(PlayerEvent::PositionCorrection {
                                                    play_request_id,
                                                    track_id: track_id.clone(),
                                                    position_ms: new_stream_position_ms,
                                                });
                                            }

                                            if let Some(interval) =
                                                self.config.position_update_interval
                                            {
                                                let last_progress_update_since_ms =
                                                    now.duration_since(self.last_progress_update);

                                                if last_progress_update_since_ms > interval {
                                                    self.last_progress_update = now;
                                                    self.send_event(PlayerEvent::PositionChanged {
                                                        play_request_id,
                                                        track_id,
                                                        position_ms: new_stream_position_ms,
                                                    });
                                                }
                                            }
                                        }
                                        Err(e) => {
                                            error!(
                                                "Skipping to next track, unable to decode samples for track <{track_id:?}>: {e:?}"
                                            );
                                            self.send_event(PlayerEvent::EndOfTrack {
                                                track_id,
                                                play_request_id,
                                            })
                                        }
                                    }
                                }
                            }

                            // SPOTIFYGOOD: the stall ends with a packet past its position (not
                            // the one the re-seek decodes again), or the end of the track
                            if let Some(stall) = stall {
                                let position = result.as_ref().map(|(p, _)| p.position_ms);
                                self.stream_stall = stall.after_packet(position);
                            }
                            self.handle_packet(result, normalisation_factor);
                        }
                        // SPOTIFYGOOD: the file's loader is gone (it ended after its requests
                        // failed): the data can't come, the track is opened again when it plays
                        // again (at the position played)
                        Err(e) if e.is_loader_gone() => {
                            warn!("The stream of <{track_id:?}> can't get its data, pausing: {e}");
                            self.stream_stall = None;
                            self.reopen = Some(track_id);
                            self.handle_pause();
                        }
                        // SPOTIFYGOOD: see stall_action
                        Err(e) => match stall_action(
                            e.is_stall(),
                            stall.and_then(|stall| stall.since),
                            Instant::now(),
                            session_valid,
                        ) {
                            StallAction::Retry => {
                                // the first packet after it reports its position
                                *reported_nominal_start_time = None;
                                let position_ms = *stream_position_ms;
                                if stall.is_none() {
                                    warn!(
                                        "Stream of <{track_id:?}> stalled, waiting for its data: {e}"
                                    );
                                }
                                let starts = stall.and_then(|stall| stall.since).is_none();
                                let stall = StreamStall::again(
                                    stall,
                                    play_request_id,
                                    position_ms,
                                    Instant::now(),
                                );
                                self.stream_stall = Some(stall);
                                // SPOTIFYGOOD: Spirc shows it as buffering at the position
                                // played; it went on extrapolating (the seek bar, a -15 s from
                                // there, the progress saved, the other clients ran ahead)
                                if starts {
                                    self.send_event(PlayerEvent::Stalled {
                                        play_request_id,
                                        track_id,
                                        position_ms: stall.position_ms,
                                    });
                                }
                            }
                            StallAction::Pause => {
                                warn!(
                                    "Stream of <{track_id:?}> stalled for {STREAM_STALL_MAX:?}, pausing: {e}"
                                );
                                // SPOTIFYGOOD: its loader may never deliver (an expired CDN
                                // URL), the next play opens it again at the position played
                                self.reopen = Some(track_id);
                                self.handle_pause();
                            }
                            StallAction::Skip => {
                                error!(
                                    "Skipping to next track, unable to get next packet for track <{track_id:?}>: {e:?}"
                                );
                                self.stream_stall = None;
                                self.send_event(PlayerEvent::EndOfTrack {
                                    track_id,
                                    play_request_id,
                                })
                            }
                        },
                    }
                } else {
                    // SPOTIFYGOOD: was `exit(1)`. We get here when `ensure_sink_running()` failed
                    // to start the sink and paused the player (a `Paused` event was sent).
                    debug!("PlayerInternal poll: sink failed to start, player paused");
                };
            }

            // SPOTIFYGOOD: see Player::fully_buffered
            let buffered = self.buffered.clone();
            if let PlayerState::Playing {
                ref track_id,
                play_request_id,
                duration_ms,
                stream_position_ms,
                ref mut stream_loader_controller,
                ref mut suggested_to_preload_next_track,
                ..
            }
            | PlayerState::Paused {
                ref track_id,
                play_request_id,
                duration_ms,
                stream_position_ms,
                ref mut stream_loader_controller,
                ref mut suggested_to_preload_next_track,
                ..
            } = self.state
            {
                let track_id = track_id.clone();
                // SPOTIFYGOOD: see Player::fully_buffered (its data stays once it is all there)
                let marked = lock_buffered(&buffered).as_ref() == Some(&track_id);
                if !marked && stream_loader_controller.range_to_end_available() {
                    *lock_buffered(&buffered) = Some(track_id.clone());
                }

                if (!*suggested_to_preload_next_track)
                    && ((duration_ms as i64 - stream_position_ms as i64)
                        < PRELOAD_NEXT_TRACK_BEFORE_END_DURATION_MS as i64)
                    && stream_loader_controller.range_to_end_available()
                {
                    *suggested_to_preload_next_track = true;
                    self.send_event(PlayerEvent::TimeToPreloadNextTrack {
                        track_id,
                        play_request_id,
                    });
                }
            }

            if (!self.state.is_playing()) && all_futures_completed_or_not_ready {
                return Poll::Pending;
            }
        }
    }
}

impl PlayerInternal {
    fn ensure_sink_running(&mut self) {
        if self.sink_status != SinkStatus::Running {
            trace!("== Starting sink ==");
            if let Some(callback) = &mut self.sink_event_callback {
                callback(SinkStatus::Running);
            }
            match self.sink.start() {
                Ok(()) => self.sink_status = SinkStatus::Running,
                Err(e) => {
                    error!("{e}");
                    self.handle_pause();
                }
            }
        }
    }

    fn ensure_sink_stopped(&mut self, temporarily: bool) {
        match self.sink_status {
            SinkStatus::Running => {
                trace!("== Stopping sink ==");
                match self.sink.stop() {
                    Ok(()) => {
                        self.sink_status = if temporarily {
                            SinkStatus::TemporarilyClosed
                        } else {
                            SinkStatus::Closed
                        };
                        if let Some(callback) = &mut self.sink_event_callback {
                            callback(self.sink_status);
                        }
                    }
                    Err(e) => {
                        // SPOTIFYGOOD: was `exit(1)`. A sink that fails to stop is treated as
                        // stopped, so a transient audio-output error can't kill the app.
                        error!("{e}");
                        self.sink_status = if temporarily {
                            SinkStatus::TemporarilyClosed
                        } else {
                            SinkStatus::Closed
                        };
                        if let Some(callback) = &mut self.sink_event_callback {
                            callback(self.sink_status);
                        }
                    }
                }
            }
            SinkStatus::TemporarilyClosed => {
                if !temporarily {
                    self.sink_status = SinkStatus::Closed;
                    if let Some(callback) = &mut self.sink_event_callback {
                        callback(SinkStatus::Closed);
                    }
                }
            }
            SinkStatus::Closed => (),
        }
    }

    fn handle_player_stop(&mut self) {
        match self.state {
            PlayerState::Playing {
                ref track_id,
                play_request_id,
                ..
            }
            | PlayerState::Paused {
                ref track_id,
                play_request_id,
                ..
            }
            | PlayerState::EndOfTrack {
                ref track_id,
                play_request_id,
                ..
            }
            | PlayerState::Loading {
                ref track_id,
                play_request_id,
                ..
            } => {
                let track_id = track_id.clone();

                self.ensure_sink_stopped(false);
                self.send_event(PlayerEvent::Stopped {
                    track_id,
                    play_request_id,
                });
                self.state = PlayerState::Stopped;
                // SPOTIFYGOOD: see Player::last_decoded, fully_buffered and PlayerInternal::reopen
                *lock_decoded(&self.decoded) = None;
                *lock_buffered(&self.buffered) = None;
                self.reopen = None;
            }
            PlayerState::Stopped => (),
            PlayerState::Invalid => {
                error!("PlayerInternal::handle_player_stop in invalid state");
                // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                // becomes true and the engine can create a new Player) instead of the whole app.
                panic!("PlayerInternal::handle_player_stop in invalid state");
            }
        }
    }

    fn handle_play(&mut self) {
        // SPOTIFYGOOD: a resumed track waits anew if its data still doesn't come (stall_action)
        if let Some(stall) = self.stream_stall.as_mut() {
            stall.since = None;
        }
        match self.state {
            PlayerState::Paused {
                ref track_id,
                play_request_id,
                stream_position_ms,
                ..
            } => {
                let track_id = track_id.clone();
                // SPOTIFYGOOD: see PlayerInternal::reopen
                if self.reopen.as_ref() == Some(&track_id) {
                    if let Err(e) = self.handle_command_load(
                        track_id,
                        Some(play_request_id),
                        true,
                        stream_position_ms,
                    ) {
                        error!("Opening the track again failed: {e}");
                    }
                    return;
                }

                self.state.paused_to_playing(self.playback_speed);
                self.send_event(PlayerEvent::Playing {
                    track_id,
                    play_request_id,
                    position_ms: stream_position_ms,
                });
                self.ensure_sink_running();
            }
            PlayerState::Loading {
                ref mut start_playback,
                ..
            } => {
                *start_playback = true;
            }
            _ => error!("Player::play called from invalid state: {:?}", self.state),
        }
    }

    fn handle_pause(&mut self) {
        // SPOTIFYGOOD: a paused track doesn't read, it stops waiting for its data (stall_action)
        if let Some(stall) = self.stream_stall.as_mut() {
            stall.since = None;
        }
        match self.state {
            PlayerState::Paused { .. } => self.ensure_sink_stopped(false),
            PlayerState::Playing {
                ref track_id,
                play_request_id,
                stream_position_ms,
                ..
            } => {
                let track_id = track_id.clone();
                // SPOTIFYGOOD: see Player::last_decoded
                set_decoded(&self.decoded, &track_id, stream_position_ms);

                self.state.playing_to_paused();

                self.ensure_sink_stopped(false);
                self.send_event(PlayerEvent::Paused {
                    track_id,
                    play_request_id,
                    position_ms: stream_position_ms,
                });
            }
            PlayerState::Loading {
                ref mut start_playback,
                ..
            } => {
                *start_playback = false;
            }
            _ => error!("Player::pause called from invalid state: {:?}", self.state),
        }
    }

    fn handle_packet(
        &mut self,
        packet: Option<(AudioPacketPosition, AudioPacket)>,
        normalisation_factor: f64,
    ) {
        match packet {
            Some((_, mut packet)) => {
                if !packet.is_empty() {
                    if let AudioPacket::Samples(ref mut data) = packet {
                        // Get the volume for the packet. In the case of hardware volume control
                        // this will always be 1.0 (no change).
                        let volume = self.volume_getter.attenuation_factor();

                        // For the basic normalisation method, a normalisation factor of 1.0
                        // indicates that there is nothing to normalise (all samples should pass
                        // unaltered). For the dynamic method, there may still be peaks that we
                        // want to shave off.
                        //
                        // No matter the case we apply volume attenuation last if there is any.
                        match (self.config.normalisation, self.config.normalisation_method) {
                            (false, _) => {
                                if volume < 1.0 {
                                    for sample in data.iter_mut() {
                                        *sample *= volume;
                                    }
                                }
                            }
                            (true, NormalisationMethod::Dynamic) => {
                                // zero-cost shorthands
                                let threshold_db = self.config.normalisation_threshold_dbfs;
                                let knee_db = self.config.normalisation_knee_db;
                                let attack_cf = self.config.normalisation_attack_cf;
                                let release_cf = self.config.normalisation_release_cf;

                                for sample in data.iter_mut() {
                                    // Feedforward limiter in the log domain
                                    // After: Giannoulis, D., Massberg, M., & Reiss, J.D. (2012).
                                    // Digital Dynamic Range Compressor Design—A Tutorial and
                                    // Analysis. Journal of The Audio Engineering Society, 60,
                                    // 399-408.

                                    // This implementation assumes audio is stereo.

                                    // step 0: apply gain stage
                                    *sample *= normalisation_factor;

                                    // step 1-4: half-wave rectification and conversion into dB, and
                                    // gain computer with soft knee and subtractor
                                    let limiter_db = {
                                        // Add slight DC offset. Some samples are silence, which is
                                        // -inf dB and gets the limiter stuck. Adding a small
                                        // positive offset prevents this.
                                        *sample += f64::MIN_POSITIVE;

                                        let bias_db = ratio_to_db(sample.abs()) - threshold_db;
                                        let knee_boundary_db = bias_db * 2.0;
                                        if knee_boundary_db < -knee_db {
                                            0.0
                                        } else if knee_boundary_db.abs() <= knee_db {
                                            let term = knee_boundary_db + knee_db;
                                            term * term * self.normalisation_knee_factor
                                        } else {
                                            bias_db
                                        }
                                    };

                                    // track left/right channel
                                    let channel = self.normalisation_channel;
                                    self.normalisation_channel ^= 1;

                                    // step 5: smooth, decoupled peak detector for each channel
                                    // Use direct references to reduce repeated array indexing
                                    let integrator = &mut self.normalisation_integrators[channel];
                                    let peak = &mut self.normalisation_peaks[channel];

                                    *integrator = f64::max(
                                        limiter_db,
                                        release_cf * *integrator + (1.0 - release_cf) * limiter_db,
                                    );
                                    *peak = attack_cf * *peak + (1.0 - attack_cf) * *integrator;

                                    // steps 6-8: conversion into level and multiplication into gain
                                    // stage. Find maximum peak across both channels to couple the
                                    // gain and maintain stereo imaging.
                                    let max_peak = f64::max(
                                        self.normalisation_peaks[0],
                                        self.normalisation_peaks[1],
                                    );
                                    *sample *= db_to_ratio(-max_peak) * volume;
                                }
                            }
                            (true, NormalisationMethod::Basic) => {
                                if normalisation_factor < 1.0 || volume < 1.0 {
                                    for sample in data.iter_mut() {
                                        *sample *= normalisation_factor * volume;
                                    }
                                }
                            }
                        }
                    }

                    if let Err(e) = self.sink.write(packet, &mut self.converter) {
                        error!("{e}");
                        self.handle_pause();
                    }
                }
            }

            None => {
                self.state.playing_to_end_of_track();
                if let PlayerState::EndOfTrack {
                    ref track_id,
                    play_request_id,
                    ..
                } = self.state
                {
                    self.send_event(PlayerEvent::EndOfTrack {
                        track_id: track_id.clone(),
                        play_request_id,
                    })
                } else {
                    error!("PlayerInternal handle_packet: Invalid PlayerState");
                    // SPOTIFYGOOD: was `exit(1)`. A panic only ends the player thread (`Player::is_invalid()`
                    // becomes true and the engine can create a new Player) instead of the whole app.
                    panic!("PlayerInternal handle_packet: Invalid PlayerState");
                }
            }
        }
    }

    // SPOTIFYGOOD: gain factor for a track under the current config (Auto resolved to Album or
    // Track). The code was in start_playback.
    fn normalisation_factor_for(&self, data: NormalisationData) -> f64 {
        let mut config = self.config.clone();
        if config.normalisation_type == NormalisationType::Auto {
            if self.auto_normalise_as_album {
                config.normalisation_type = NormalisationType::Album;
            } else {
                config.normalisation_type = NormalisationType::Track;
            }
        };
        NormalisationData::get_factor(&config, data)
    }

    // SPOTIFYGOOD: apply new normalisation settings now. The limiter parameters are read from
    // the config for every packet; the knee factor and the current track's gain are recomputed.
    // SPOTIFYGOOD: see Player::set_playback_speed. The playing track's line is re-based at its
    // position: the line of the old speed is far off the stream after a while (behind it after a
    // fast part, so a slower speed got no corrections for long).
    fn handle_set_playback_speed(&mut self, speed: f64) {
        let speed = valid_playback_speed(speed);
        if speed == self.playback_speed {
            return;
        }
        self.playback_speed = speed;
        if let PlayerState::Playing {
            stream_position_ms,
            ref mut reported_nominal_start_time,
            ..
        } = self.state
        {
            *reported_nominal_start_time = nominal_start_time(
                Instant::now(),
                Duration::from_millis(stream_position_ms as u64),
                speed,
            );
        }
    }

    fn handle_set_normalisation(&mut self, settings: NormalisationSettings) {
        self.config.set_normalisation_settings(settings);
        self.normalisation_knee_factor = 1.0 / (8.0 * self.config.normalisation_knee_db);
        let data = match self.state {
            PlayerState::Playing {
                normalisation_data, ..
            }
            | PlayerState::Paused {
                normalisation_data, ..
            } => Some(normalisation_data),
            _ => None,
        };
        if let Some(data) = data {
            let factor = self.normalisation_factor_for(data);
            if let PlayerState::Playing {
                ref mut normalisation_factor,
                ..
            }
            | PlayerState::Paused {
                ref mut normalisation_factor,
                ..
            } = self.state
            {
                *normalisation_factor = factor;
            }
        }
    }

    fn start_playback(
        &mut self,
        track_id: SpotifyUri,
        play_request_id: u64,
        loaded_track: PlayerLoadedTrackData,
        start_playback: bool,
    ) {
        let audio_item = Box::new(loaded_track.audio_item.clone());

        self.send_event(PlayerEvent::TrackChanged { audio_item });

        let position_ms = loaded_track.stream_position_ms;
        // SPOTIFYGOOD: see Player::last_decoded
        set_decoded(&self.decoded, &track_id, position_ms);

        // SPOTIFYGOOD: moved into normalisation_factor_for() so set_normalisation can reuse it.
        let normalisation_factor = self.normalisation_factor_for(loaded_track.normalisation_data);

        if start_playback {
            self.ensure_sink_running();
            self.send_event(PlayerEvent::Playing {
                track_id: track_id.clone(),
                play_request_id,
                position_ms,
            });

            self.state = PlayerState::Playing {
                track_id,
                play_request_id,
                decoder: loaded_track.decoder,
                audio_item: loaded_track.audio_item,
                normalisation_data: loaded_track.normalisation_data,
                normalisation_factor,
                stream_loader_controller: loaded_track.stream_loader_controller,
                duration_ms: loaded_track.duration_ms,
                bytes_per_second: loaded_track.bytes_per_second,
                stream_position_ms: loaded_track.stream_position_ms,
                // SPOTIFYGOOD: on the line of the speed, see nominal_start_time
                reported_nominal_start_time: nominal_start_time(
                    Instant::now(),
                    Duration::from_millis(position_ms as u64),
                    self.playback_speed,
                ),
                suggested_to_preload_next_track: false,
                is_explicit: loaded_track.is_explicit,
            };
        } else {
            self.ensure_sink_stopped(false);

            self.state = PlayerState::Paused {
                track_id: track_id.clone(),
                play_request_id,
                decoder: loaded_track.decoder,
                audio_item: loaded_track.audio_item,
                normalisation_data: loaded_track.normalisation_data,
                normalisation_factor,
                stream_loader_controller: loaded_track.stream_loader_controller,
                duration_ms: loaded_track.duration_ms,
                bytes_per_second: loaded_track.bytes_per_second,
                stream_position_ms: loaded_track.stream_position_ms,
                suggested_to_preload_next_track: false,
                is_explicit: loaded_track.is_explicit,
            };

            self.send_event(PlayerEvent::Paused {
                track_id,
                play_request_id,
                position_ms,
            });
        }
    }

    fn handle_command_load(
        &mut self,
        track_id: SpotifyUri,
        play_request_id_option: Option<u64>,
        play: bool,
        position_ms: u32,
    ) -> PlayerResult {
        let play_request_id =
            play_request_id_option.unwrap_or(self.play_request_id_generator.get());
        // SPOTIFYGOOD: see PlayerInternal::reopen (any other load forgets it); and see
        // Player::fully_buffered
        let reopen = self.reopen.take().is_some_and(|reopen| reopen == track_id);
        if reopen {
            info!("Opening <{track_id:?}> again");
            self.stream_stall = None;
        }
        *lock_buffered(&self.buffered) = None;

        self.send_event(PlayerEvent::PlayRequestIdChanged { play_request_id });

        if !self.config.gapless {
            self.ensure_sink_stopped(play);
        }

        if matches!(self.state, PlayerState::Invalid) {
            return Err(Error::internal(format!(
                "Player::handle_command_load called from invalid state: {:?}",
                self.state
            )));
        }

        // Now we check at different positions whether we already have a pre-loaded version
        // of this track somewhere. If so, use it and return.

        // Check if there's a matching loaded track in the EndOfTrack player state.
        // This is the case if we're repeating the same track again.
        if let PlayerState::EndOfTrack {
            track_id: previous_track_id,
            ..
        } = &self.state
        {
            // SPOTIFYGOOD: not when it is opened again (see PlayerInternal::reopen)
            if *previous_track_id == track_id && !reopen {
                let mut loaded_track = match mem::replace(&mut self.state, PlayerState::Invalid) {
                    PlayerState::EndOfTrack { loaded_track, .. } => loaded_track,
                    _ => {
                        return Err(Error::internal(format!(
                            "PlayerInternal::handle_command_load repeating the same track: invalid state: {:?}",
                            self.state
                        )));
                    }
                };

                if position_ms != loaded_track.stream_position_ms {
                    // This may be blocking.
                    loaded_track.stream_position_ms = loaded_track.decoder.seek(position_ms)?;
                }
                self.preload = PlayerPreload::None;
                self.start_playback(track_id, play_request_id, loaded_track, play);
                if let PlayerState::Invalid = self.state {
                    return Err(Error::internal(format!(
                        "PlayerInternal::handle_command_load repeating the same track: start_playback() did not transition to valid player state: {:?}",
                        self.state
                    )));
                }
                return Ok(());
            }
        }

        // Check if we are already playing the track. If so, just do a seek and update our info.
        if let PlayerState::Playing {
            track_id: ref current_track_id,
            ref mut stream_position_ms,
            ref mut decoder,
            ..
        }
        | PlayerState::Paused {
            track_id: ref current_track_id,
            ref mut stream_position_ms,
            ref mut decoder,
            ..
        } = self.state
        {
            // SPOTIFYGOOD: not when it is opened again (see PlayerInternal::reopen)
            if *current_track_id == track_id && !reopen {
                // we can use the current decoder. Ensure it's at the correct position.
                if position_ms != *stream_position_ms {
                    // This may be blocking.
                    *stream_position_ms = decoder.seek(position_ms)?;
                }

                // Move the info from the current state into a PlayerLoadedTrackData so we can use
                // the usual code path to start playback.
                let old_state = mem::replace(&mut self.state, PlayerState::Invalid);

                if let PlayerState::Playing {
                    stream_position_ms,
                    decoder,
                    audio_item,
                    stream_loader_controller,
                    bytes_per_second,
                    duration_ms,
                    normalisation_data,
                    is_explicit,
                    ..
                }
                | PlayerState::Paused {
                    stream_position_ms,
                    decoder,
                    audio_item,
                    stream_loader_controller,
                    bytes_per_second,
                    duration_ms,
                    normalisation_data,
                    is_explicit,
                    ..
                } = old_state
                {
                    let loaded_track = PlayerLoadedTrackData {
                        decoder,
                        normalisation_data,
                        stream_loader_controller,
                        audio_item,
                        bytes_per_second,
                        duration_ms,
                        stream_position_ms,
                        is_explicit,
                    };

                    self.preload = PlayerPreload::None;
                    self.start_playback(track_id, play_request_id, loaded_track, play);

                    if let PlayerState::Invalid = self.state {
                        return Err(Error::internal(format!(
                            "PlayerInternal::handle_command_load already playing this track: start_playback() did not transition to valid player state: {:?}",
                            self.state
                        )));
                    }

                    return Ok(());
                } else {
                    return Err(Error::internal(format!(
                        "PlayerInternal::handle_command_load already playing this track: invalid state: {:?}",
                        self.state
                    )));
                }
            }
        }

        // Check if the requested track has been preloaded already. If so use the preloaded data.
        if let PlayerPreload::Ready {
            track_id: loaded_track_id,
            ..
        } = &self.preload
        {
            if track_id == *loaded_track_id {
                let preload = std::mem::replace(&mut self.preload, PlayerPreload::None);
                if let PlayerPreload::Ready {
                    track_id,
                    mut loaded_track,
                } = preload
                {
                    if position_ms != loaded_track.stream_position_ms {
                        // This may be blocking
                        loaded_track.stream_position_ms = loaded_track.decoder.seek(position_ms)?;
                    }
                    self.start_playback(track_id, play_request_id, *loaded_track, play);
                    return Ok(());
                } else {
                    return Err(Error::internal(format!(
                        "PlayerInternal::handle_command_loading preloaded track: invalid state: {:?}",
                        self.state
                    )));
                }
            }
        }

        self.send_event(PlayerEvent::Loading {
            track_id: track_id.clone(),
            play_request_id,
            position_ms,
        });

        // Try to extract a pending loader from the preloading mechanism
        let loader = if let PlayerPreload::Loading {
            track_id: loaded_track_id,
            ..
        } = &self.preload
        {
            if (track_id == *loaded_track_id) && (position_ms == 0) {
                let mut preload = PlayerPreload::None;
                std::mem::swap(&mut preload, &mut self.preload);
                if let PlayerPreload::Loading { loader, .. } = preload {
                    Some(loader)
                } else {
                    None
                }
            } else {
                None
            }
        } else {
            None
        };

        self.preload = PlayerPreload::None;

        // If we don't have a loader yet, create one from scratch.
        let loader =
            loader.unwrap_or_else(|| Box::pin(self.load_track(track_id.clone(), position_ms)));

        // Set ourselves to a loading state.
        self.state = PlayerState::Loading {
            track_id,
            play_request_id,
            start_playback: play,
            loader,
        };

        Ok(())
    }

    fn handle_command_preload(&mut self, track_id: SpotifyUri) {
        debug!("Preloading track");
        let mut preload_track = true;
        // check whether the track is already loaded somewhere or being loaded.
        if let PlayerPreload::Loading {
            track_id: currently_loading,
            ..
        }
        | PlayerPreload::Ready {
            track_id: currently_loading,
            ..
        } = &self.preload
        {
            if *currently_loading == track_id {
                // we're already preloading the requested track.
                preload_track = false;
            } else {
                // we're preloading something else - cancel it.
                self.preload = PlayerPreload::None;
            }
        }

        if let PlayerState::Playing {
            track_id: current_track_id,
            ..
        }
        | PlayerState::Paused {
            track_id: current_track_id,
            ..
        }
        | PlayerState::EndOfTrack {
            track_id: current_track_id,
            ..
        } = &self.state
        {
            if *current_track_id == track_id {
                // we already have the requested track loaded.
                preload_track = false;
            }
        }

        // schedule the preload of the current track if desired.
        if preload_track {
            let loader = self.load_track(track_id.clone(), 0);
            self.preload = PlayerPreload::Loading {
                track_id,
                loader: Box::pin(loader),
            }
        }
    }

    fn handle_command_seek(&mut self, position_ms: u32) -> PlayerResult {
        // When we are still loading, the user may immediately ask to
        // seek to another position yet the decoder won't be ready for
        // that. In this case just restart the loading process but
        // with the requested position.
        if let PlayerState::Loading {
            ref track_id,
            play_request_id,
            start_playback,
            ..
        } = self.state
        {
            return self.handle_command_load(
                track_id.clone(),
                Some(play_request_id),
                start_playback,
                position_ms,
            );
        }

        // SPOTIFYGOOD: a seek while the stream stalls (-15 s, scrubbing) waits for the data like
        // the stall does: the decoder is seeked once the data is there (see the packet loop).
        // Its seek bisects the whole Ogg file, and without data each probe waited
        // `download_timeout`: every command waited tens of seconds, and the seek was dropped.
        if let PlayerState::Playing {
            ref track_id,
            play_request_id,
            duration_ms,
            ref mut stream_position_ms,
            ..
        }
        | PlayerState::Paused {
            ref track_id,
            play_request_id,
            duration_ms,
            ref mut stream_position_ms,
            ..
        } = self.state
        {
            if let Some(stall) = self
                .stream_stall
                .as_mut()
                .filter(|stall| stall.play_request_id == play_request_id)
            {
                let target = position_ms.min(duration_ms);
                stall.position_ms = target;
                stall.waiting = true;
                *stream_position_ms = target;
                set_decoded(&self.decoded, track_id, target);
                let track_id = track_id.clone();
                self.send_event(PlayerEvent::Seeked {
                    play_request_id,
                    track_id,
                    position_ms: target,
                });
                return Ok(());
            }
        }

        if let Some(decoder) = self.state.decoder() {
            match decoder.seek(position_ms) {
                Ok(new_position_ms) => {
                    if let PlayerState::Playing {
                        ref mut stream_position_ms,
                        ref track_id,
                        play_request_id,
                        ..
                    }
                    | PlayerState::Paused {
                        ref mut stream_position_ms,
                        ref track_id,
                        play_request_id,
                        ..
                    } = self.state
                    {
                        *stream_position_ms = new_position_ms;
                        // SPOTIFYGOOD: see Player::last_decoded
                        set_decoded(&self.decoded, track_id, new_position_ms);

                        self.send_event(PlayerEvent::Seeked {
                            play_request_id,
                            track_id: track_id.clone(),
                            position_ms: new_position_ms,
                        });
                    }
                }
                Err(e) => {
                    error!("PlayerInternal::handle_command_seek error: {e}");
                    // SPOTIFYGOOD: Player::seek put the target in last_decoded, the playback
                    // stays where it was
                    if let PlayerState::Playing {
                        ref track_id,
                        stream_position_ms,
                        ..
                    }
                    | PlayerState::Paused {
                        ref track_id,
                        stream_position_ms,
                        ..
                    } = self.state
                    {
                        set_decoded(&self.decoded, track_id, stream_position_ms);
                    }
                }
            }
        } else {
            error!("Player::seek called from invalid state: {:?}", self.state);
        }

        // ensure we have a bit of a buffer of downloaded data
        let preloaded = self.preload_data_before_playback();

        // SPOTIFYGOOD: no line until the first packet after the seek, which reports its
        // position (a PositionCorrection) and starts the line there (see nominal_start_time).
        // The line started after the wait for the data above, so the Seeked position, sent
        // before it, stayed ahead of the audio by that wait (at every speed). Also when the wait
        // timed out (the data comes with a later read). The seek starts any stall afresh.
        if let PlayerState::Playing {
            ref mut reported_nominal_start_time,
            ..
        } = self.state
        {
            *reported_nominal_start_time = None;
        }
        self.stream_stall = None;

        preloaded
    }

    fn handle_command(&mut self, cmd: PlayerCommand) -> PlayerResult {
        debug!("command={cmd:?}");
        match cmd {
            PlayerCommand::Load {
                track_id,
                play,
                position_ms,
            } => self.handle_command_load(track_id, None, play, position_ms)?,

            PlayerCommand::Preload { track_id } => self.handle_command_preload(track_id),

            PlayerCommand::Seek(position_ms) => self.handle_command_seek(position_ms)?,

            PlayerCommand::Play => self.handle_play(),

            PlayerCommand::Pause => self.handle_pause(),

            PlayerCommand::Stop => self.handle_player_stop(),

            PlayerCommand::SetSession(session) => self.session = session,

            PlayerCommand::AddEventSender(sender) => self.event_senders.push(sender),

            PlayerCommand::SetSinkEventCallback(callback) => self.sink_event_callback = callback,

            PlayerCommand::EmitVolumeChangedEvent(volume) => {
                self.send_event(PlayerEvent::VolumeChanged { volume })
            }

            PlayerCommand::EmitRepeatChangedEvent { context, track } => {
                self.send_event(PlayerEvent::RepeatChanged { context, track })
            }

            PlayerCommand::EmitShuffleChangedEvent(shuffle) => {
                self.send_event(PlayerEvent::ShuffleChanged { shuffle })
            }

            PlayerCommand::EmitAutoPlayChangedEvent(auto_play) => {
                self.send_event(PlayerEvent::AutoPlayChanged { auto_play })
            }

            PlayerCommand::EmitSessionClientChangedEvent {
                client_id,
                client_name,
                client_brand_name,
                client_model_name,
            } => self.send_event(PlayerEvent::SessionClientChanged {
                client_id,
                client_name,
                client_brand_name,
                client_model_name,
            }),

            PlayerCommand::EmitSessionConnectedEvent {
                connection_id,
                user_name,
            } => self.send_event(PlayerEvent::SessionConnected {
                connection_id,
                user_name,
            }),

            PlayerCommand::EmitSessionDisconnectedEvent {
                connection_id,
                user_name,
            } => self.send_event(PlayerEvent::SessionDisconnected {
                connection_id,
                user_name,
            }),

            PlayerCommand::SetAutoNormaliseAsAlbum(setting) => {
                self.auto_normalise_as_album = setting
            }

            // SPOTIFYGOOD: runtime settings. `load_track` clones `self.config` for every new
            // loader, so the source and bitrate apply to loads and preloads started from now on;
            // `gapless` is read by `handle_command_load`, so it applies from the next load.
            PlayerCommand::SetOfflineSource(source) => self.config.offline_source = source,

            PlayerCommand::SetBitrate(bitrate) => self.config.bitrate = bitrate,

            PlayerCommand::SetNormalisation(settings) => self.handle_set_normalisation(settings),

            PlayerCommand::SetGapless(gapless) => self.config.gapless = gapless,

            // SPOTIFYGOOD: see Player::set_playback_speed
            PlayerCommand::SetPlaybackSpeed(speed) => self.handle_set_playback_speed(speed),

            PlayerCommand::EmitFilterExplicitContentChangedEvent(filter) => {
                self.send_event(PlayerEvent::FilterExplicitContentChanged { filter });

                if filter {
                    if let PlayerState::Playing {
                        ref track_id,
                        play_request_id,
                        is_explicit,
                        ..
                    }
                    | PlayerState::Paused {
                        ref track_id,
                        play_request_id,
                        is_explicit,
                        ..
                    } = self.state
                    {
                        let track_id = track_id.clone();

                        if is_explicit {
                            warn!(
                                "Currently loaded track is explicit, which client setting forbids -- skipping to next track."
                            );
                            self.send_event(PlayerEvent::EndOfTrack {
                                track_id,
                                play_request_id,
                            })
                        }
                    }
                }
            }
        };

        Ok(())
    }

    fn send_event(&mut self, event: PlayerEvent) {
        self.event_senders
            .retain(|sender| sender.send(event.clone()).is_ok());
    }

    fn load_track(
        &mut self,
        spotify_uri: SpotifyUri,
        position_ms: u32,
    // SPOTIFYGOOD: the future resolves to the failure reason instead of `()`.
    ) -> impl FusedFuture<Output = Result<PlayerLoadedTrackData, UnavailableReason>> + Send + 'static
    {
        // This method creates a future that returns the loaded stream and associated info.
        // Ideally all work should be done using asynchronous code. However, seek() on the
        // audio stream is implemented in a blocking fashion. Thus, we can't turn it into future
        // easily. Instead we spawn a thread to do the work and return a one-shot channel as the
        // future to work with.

        let loader = PlayerTrackLoader {
            session: self.session.clone(),
            config: self.config.clone(),
            local_file_lookup: self.local_file_lookup.clone(),
        };

        let (result_tx, result_rx) = oneshot::channel();

        let load_handles_clone = self.load_handles.clone();
        let handle = tokio::runtime::Handle::current();

        // SPOTIFYGOOD: hold the lock while spawning and inserting. Otherwise a fast loader
        // (e.g. an offline file) could remove its entry before it was inserted, leaving an
        // un-joined handle in the map until the player is dropped.
        let mut load_handles = lock_load_handles(&self.load_handles);

        // SPOTIFYGOOD: named thread; the result (including the failure reason) is always sent.
        // A failed spawn (EAGAIN: thread limit or memory pressure) must not panic while the
        // guard is held: that poisoned the mutex and the unwind's `PlayerInternal::drop` then
        // panicked again, aborting the process. The closure (and `result_tx` with it) is
        // dropped, so the load ends as `Unavailable(Other)` below.
        let spawned = thread::Builder::new()
            .name("lrs-loader".to_string())
            .spawn(move || {
                let data = handle.block_on(loader.load_track(spotify_uri, position_ms));
                let _ = result_tx.send(data);

                lock_load_handles(&load_handles_clone).remove(&thread::current().id());
            });

        match spawned {
            Ok(load_handle) => {
                load_handles.insert(load_handle.thread().id(), load_handle);
            }
            Err(e) => error!("Failed to spawn loader thread: {e}"),
        }
        drop(load_handles);

        // SPOTIFYGOOD: a dropped sender (the loader thread panicked or could not be spawned) is
        // reported as `Other`.
        result_rx.map(|result| result.unwrap_or(Err(UnavailableReason::Other)))
    }

    fn preload_data_before_playback(&mut self) -> PlayerResult {
        if let PlayerState::Playing {
            bytes_per_second,
            ref mut stream_loader_controller,
            ..
        } = self.state
        {
            let read_ahead_during_playback = AudioFetchParams::get().read_ahead_during_playback;
            // Request our read ahead range
            let request_data_length =
                (read_ahead_during_playback.as_secs_f32() * bytes_per_second as f32) as usize;

            // Request the part we want to wait for blocking. This effectively means we wait for the previous request to partially complete.
            let wait_for_data_length =
                (read_ahead_during_playback.as_secs_f32() * bytes_per_second as f32) as usize;

            // SPOTIFYGOOD: bounded (see wait_for_data), fetch_blocking's wait didn't return with
            // requests that fail at once
            let deadline = AudioFetchParams::get().download_timeout;
            if wait_for_data(
                stream_loader_controller,
                request_data_length,
                wait_for_data_length,
                deadline,
            ) {
                Ok(())
            } else {
                Err(AudioFileError::WaitTimeout.into())
            }
        } else {
            Ok(())
        }
    }
}

// SPOTIFYGOOD: locks `load_handles` even if a thread panicked while holding it. The map stays
// consistent (single insert / remove / drain operations), so the poison flag carries no
// information here, and panicking on it in `Drop` would abort the process.
type LoadHandles = HashMap<thread::ThreadId, thread::JoinHandle<()>>;

fn lock_load_handles(handles: &Mutex<LoadHandles>) -> MutexGuard<'_, LoadHandles> {
    handles.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Drop for PlayerInternal {
    fn drop(&mut self) {
        debug!("drop PlayerInternal[{}]", self.player_id);

        let handles: Vec<thread::JoinHandle<()>> = {
            // waiting for the thread while holding the mutex would result in a deadlock
            let mut load_handles = lock_load_handles(&self.load_handles); // SPOTIFYGOOD

            load_handles
                .drain()
                .map(|(_thread_id, handle)| handle)
                .collect()
        };

        // SPOTIFYGOOD: join for at most LOADER_JOIN_TIMEOUT in total, then detach the rest.
        let deadline = Instant::now() + LOADER_JOIN_TIMEOUT;
        for handle in handles {
            while !handle.is_finished() && Instant::now() < deadline {
                thread::sleep(LOADER_JOIN_POLL);
            }
            if handle.is_finished() {
                let _ = handle.join();
            } else {
                warn!("Loader thread still running at player shutdown, detaching it");
            }
        }
    }
}

impl fmt::Debug for PlayerCommand {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PlayerCommand::Load {
                track_id,
                play,
                position_ms,
                ..
            } => f
                .debug_tuple("Load")
                .field(&track_id)
                .field(&play)
                .field(&position_ms)
                .finish(),
            PlayerCommand::Preload { track_id } => {
                f.debug_tuple("Preload").field(&track_id).finish()
            }
            PlayerCommand::Play => f.debug_tuple("Play").finish(),
            PlayerCommand::Pause => f.debug_tuple("Pause").finish(),
            PlayerCommand::Stop => f.debug_tuple("Stop").finish(),
            PlayerCommand::Seek(position) => f.debug_tuple("Seek").field(&position).finish(),
            PlayerCommand::SetSession(_) => f.debug_tuple("SetSession").finish(),
            PlayerCommand::AddEventSender(_) => f.debug_tuple("AddEventSender").finish(),
            PlayerCommand::SetSinkEventCallback(_) => {
                f.debug_tuple("SetSinkEventCallback").finish()
            }
            PlayerCommand::EmitVolumeChangedEvent(volume) => f
                .debug_tuple("EmitVolumeChangedEvent")
                .field(&volume)
                .finish(),
            PlayerCommand::SetAutoNormaliseAsAlbum(setting) => f
                .debug_tuple("SetAutoNormaliseAsAlbum")
                .field(&setting)
                .finish(),
            PlayerCommand::EmitFilterExplicitContentChangedEvent(filter) => f
                .debug_tuple("EmitFilterExplicitContentChangedEvent")
                .field(&filter)
                .finish(),
            PlayerCommand::EmitSessionConnectedEvent {
                connection_id,
                user_name,
            } => f
                .debug_tuple("EmitSessionConnectedEvent")
                .field(&connection_id)
                .field(&user_name)
                .finish(),
            PlayerCommand::EmitSessionDisconnectedEvent {
                connection_id,
                user_name,
            } => f
                .debug_tuple("EmitSessionDisconnectedEvent")
                .field(&connection_id)
                .field(&user_name)
                .finish(),
            PlayerCommand::EmitSessionClientChangedEvent {
                client_id,
                client_name,
                client_brand_name,
                client_model_name,
            } => f
                .debug_tuple("EmitSessionClientChangedEvent")
                .field(&client_id)
                .field(&client_name)
                .field(&client_brand_name)
                .field(&client_model_name)
                .finish(),
            PlayerCommand::EmitShuffleChangedEvent(shuffle) => f
                .debug_tuple("EmitShuffleChangedEvent")
                .field(&shuffle)
                .finish(),
            PlayerCommand::EmitRepeatChangedEvent { context, track } => f
                .debug_tuple("EmitRepeatChangedEvent")
                .field(&context)
                .field(&track)
                .finish(),
            PlayerCommand::EmitAutoPlayChangedEvent(auto_play) => f
                .debug_tuple("EmitAutoPlayChangedEvent")
                .field(&auto_play)
                .finish(),
            // SPOTIFYGOOD: Debug output for the new commands.
            PlayerCommand::SetOfflineSource(source) => f
                .debug_tuple("SetOfflineSource")
                .field(&source.is_some())
                .finish(),
            PlayerCommand::SetBitrate(bitrate) => {
                f.debug_tuple("SetBitrate").field(&bitrate).finish()
            }
            PlayerCommand::SetNormalisation(settings) => f
                .debug_tuple("SetNormalisation")
                .field(&settings)
                .finish(),
            PlayerCommand::SetGapless(gapless) => {
                f.debug_tuple("SetGapless").field(&gapless).finish()
            }
            PlayerCommand::SetPlaybackSpeed(speed) => {
                f.debug_tuple("SetPlaybackSpeed").field(&speed).finish()
            }
        }
    }
}

impl fmt::Debug for PlayerState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        use PlayerState::*;
        match self {
            Stopped => f.debug_struct("Stopped").finish(),
            Loading {
                track_id,
                play_request_id,
                ..
            } => f
                .debug_struct("Loading")
                .field("track_id", &track_id)
                .field("play_request_id", &play_request_id)
                .finish(),
            Paused {
                track_id,
                play_request_id,
                ..
            } => f
                .debug_struct("Paused")
                .field("track_id", &track_id)
                .field("play_request_id", &play_request_id)
                .finish(),
            Playing {
                track_id,
                play_request_id,
                ..
            } => f
                .debug_struct("Playing")
                .field("track_id", &track_id)
                .field("play_request_id", &play_request_id)
                .finish(),
            EndOfTrack {
                track_id,
                play_request_id,
                ..
            } => f
                .debug_struct("EndOfTrack")
                .field("track_id", &track_id)
                .field("play_request_id", &play_request_id)
                .finish(),
            Invalid => f.debug_struct("Invalid").finish(),
        }
    }
}

struct Subfile<T: Read + Seek> {
    stream: T,
    offset: u64,
    length: u64,
}

impl<T: Read + Seek> Subfile<T> {
    pub fn new(mut stream: T, offset: u64, length: u64) -> Result<Subfile<T>, io::Error> {
        let target = SeekFrom::Start(offset);
        stream.seek(target)?;

        Ok(Subfile {
            stream,
            offset,
            length,
        })
    }
}

impl<T: Read + Seek> Read for Subfile<T> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.stream.read(buf)
    }
}

impl<T: Read + Seek> Seek for Subfile<T> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let pos = match pos {
            SeekFrom::Start(offset) => SeekFrom::Start(offset + self.offset),
            SeekFrom::End(offset) => {
                if (self.length as i64 - offset) < self.offset as i64 {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "newpos would be < self.offset",
                    ));
                }
                pos
            }
            _ => pos,
        };

        let newpos = self.stream.seek(pos)?;
        Ok(newpos - self.offset)
    }
}

impl<R> MediaSource for Subfile<R>
where
    R: Read + Seek + Send + Sync,
{
    fn is_seekable(&self) -> bool {
        true
    }

    fn byte_len(&self) -> Option<u64> {
        Some(self.length)
    }
}

// SPOTIFYGOOD: unit tests for the local patches that don't need a session.
#[cfg(test)]
mod spotifygood_tests {
    use super::*;

    #[test]
    fn key_retries_cool_down_after_exhaustion() {
        let t0 = Instant::now();
        let mut brake = KeyRetryBrake::new();
        assert_eq!(brake.retries(t0), AUDIO_KEY_RETRIES);

        // The retries ran out: for AUDIO_KEY_COOLDOWN every load makes a single key request.
        brake.exhausted(t0);
        assert_eq!(brake.retries(t0 + Duration::from_secs(1)), 0);
        assert_eq!(brake.retries(t0 + AUDIO_KEY_COOLDOWN - Duration::from_millis(1)), 0);
        assert_eq!(brake.retries(t0 + AUDIO_KEY_COOLDOWN), AUDIO_KEY_RETRIES);

        // A failed single attempt extends the cool-down; a key ends it.
        brake.exhausted(t0 + Duration::from_secs(20));
        assert_eq!(brake.retries(t0 + Duration::from_secs(40)), 0);
        brake.succeeded();
        assert_eq!(brake.retries(t0 + Duration::from_secs(41)), AUDIO_KEY_RETRIES);
    }

    #[test]
    fn key_brake_is_shared() {
        let now = Instant::now();
        audio_key_brake().succeeded();
        audio_key_brake().exhausted(now);
        let other_loader = std::thread::spawn(move || audio_key_brake().retries(now)).join().expect("join");
        assert_eq!(other_loader, 0, "another loader thread sees the cool-down");
        audio_key_brake().succeeded();
    }

    // SPOTIFYGOOD: see nominal_start_time
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    /// The packet loop's position check over a simulated playback: the decoder delivers 20 ms
    /// packets as the sink (playing at `speed`) takes them, a correction re-bases the line like
    /// the loop does. `line_speed` is the speed the line is on (1 = stock).
    struct Playback {
        now: Instant,
        position: Duration,
        speed: f64,
        line_speed: f64,
        line: Option<Instant>,
        corrections: usize,
    }

    impl Playback {
        /// starts playing at `speed` (start_playback, paused_to_playing)
        fn start(speed: f64, line_speed: f64) -> Self {
            let now = Instant::now() + Duration::from_secs(3600);
            Self {
                now,
                position: Duration::ZERO,
                speed,
                line_speed,
                line: nominal_start_time(now, Duration::ZERO, line_speed),
                corrections: 0,
            }
        }

        /// the decoder runs ahead by `media_ms` (until the sink's buffer is full)
        fn fill(&mut self, media_ms: u64) {
            for _ in 0..media_ms / 20 {
                self.packet(Duration::ZERO);
            }
        }

        /// `wall_ms` of playback, the decoder held back by the sink
        fn play(&mut self, wall_ms: u64) {
            let end = self.now + ms(wall_ms);
            while self.now < end {
                self.packet(ms(20).div_f64(self.speed));
            }
        }

        /// a read blocks for `wall_ms`: the audio stops, the decoder delivers nothing
        fn stall(&mut self, wall_ms: u64) {
            self.now += ms(wall_ms);
        }

        /// a speed change (handle_set_playback_speed): the line is re-based if `rebase`
        fn set_speed(&mut self, speed: f64, rebase: bool) {
            self.speed = speed;
            self.line_speed = speed;
            if rebase {
                self.line = nominal_start_time(self.now, self.position, speed);
            }
        }

        fn packet(&mut self, after: Duration) {
            self.now += after;
            self.position += ms(20);
            let line = self.line.expect("a line");
            if lags_behind(self.now, line, self.position, self.line_speed) {
                self.line = nominal_start_time(self.now, self.position, self.line_speed);
                self.corrections += 1;
            }
        }
    }

    #[test]
    fn a_stall_above_1x_is_corrected() {
        // 2x, the decoder ahead by the sink's buffer (250 ms, 500 ms of media)
        let mut p = Playback::start(2., 2.);
        p.fill(500);
        p.play(30_000);
        assert_eq!(p.corrections, 0, "in time, ahead by the buffer");

        // a read blocks for 1 s: 2 s of media, 1.5 s past the buffer, corrected at the first
        // packet after it
        p.stall(1_000);
        p.play(20);
        assert_eq!(p.corrections, 1);
        p.play(30_000);
        assert_eq!(p.corrections, 1, "on the line again");

        // within 1 s of media nothing is reported (the buffer refilled meanwhile)
        p.fill(500);
        p.stall(700);
        p.play(1_000);
        assert_eq!(p.corrections, 1);
        // a little more is
        p.stall(400);
        p.play(1_000);
        assert_eq!(p.corrections, 2);

        // stock (the 1x line): after 30 s at 2x the stream is 30 s ahead of it, a 3 s stall
        // went unreported
        let mut stock = Playback::start(2., 1.);
        stock.fill(500);
        stock.play(30_000);
        stock.stall(3_000);
        stock.play(10_000);
        assert_eq!(stock.corrections, 0);
        // at 1x the two are the same
        let mut one = Playback::start(1., 1.);
        one.fill(250);
        one.play(10_000);
        one.stall(1_500);
        one.play(1_000);
        assert_eq!(one.corrections, 1);
    }

    #[test]
    fn corrections_go_on_after_a_speed_change() {
        // 20 min at 2x, then 0.5x: a 3 s stall (1.5 s of media) is corrected
        let mut p = Playback::start(2., 2.);
        p.fill(500);
        p.play(20 * 60_000);
        p.set_speed(0.5, true);
        p.play(60_000);
        assert_eq!(p.corrections, 0);
        p.stall(3_000);
        p.play(1_000);
        assert_eq!(p.corrections, 1);
        // and back up to 2x
        p.set_speed(2., true);
        p.play(60_000);
        p.stall(1_000);
        p.play(1_000);
        assert_eq!(p.corrections, 2);

        // without the re-base the line of 2x stays 30 min ahead at 0.5x: nothing for long
        let mut kept = Playback::start(2., 2.);
        kept.fill(500);
        kept.play(20 * 60_000);
        kept.set_speed(0.5, false);
        kept.play(60_000);
        kept.stall(3_000);
        kept.play(10_000);
        assert_eq!(kept.corrections, 0);
    }

    // SPOTIFYGOOD: see wait_for_data
    struct FakeSource {
        position: Option<usize>,
        /// the data is there from this look on (`None`: never, its requests fail at once)
        available_after: Option<usize>,
        looks: std::cell::Cell<usize>,
        requests: std::cell::Cell<usize>,
    }

    impl FakeSource {
        fn new(position: Option<usize>, available_after: Option<usize>) -> Self {
            Self {
                position,
                available_after,
                looks: Default::default(),
                requests: Default::default(),
            }
        }
    }

    impl DataSource for FakeSource {
        fn read_position(&self) -> Option<usize> {
            self.position
        }

        fn request(&self, _start: usize, _length: usize) {
            self.requests.set(self.requests.get() + 1);
        }

        fn available(&self, _start: usize, _length: usize) -> bool {
            let looks = self.looks.get();
            self.looks.set(looks + 1);
            self.available_after.is_some_and(|after| looks >= after)
        }
    }

    #[test]
    fn a_wait_for_the_data_is_bounded() {
        // its requests fail at once, the data never comes: one request, back at the deadline
        let failing = FakeSource::new(Some(4_096), None);
        let started = Instant::now();
        assert!(!wait_for_data(&failing, 65_536, STALL_WAIT_BYTES, ms(300)));
        let took = started.elapsed();
        assert!(took >= ms(300) && took < ms(1_000), "{took:?}");
        assert_eq!(failing.requests.get(), 1);

        // the data comes after a few looks
        let coming = FakeSource::new(Some(4_096), Some(3));
        assert!(wait_for_data(&coming, 65_536, STALL_WAIT_BYTES, ms(5_000)));
        assert_eq!(coming.requests.get(), 1);

        // it is there already, or the file isn't streamed: no request
        let there = FakeSource::new(Some(4_096), Some(0));
        assert!(wait_for_data(&there, 65_536, STALL_WAIT_BYTES, ms(5_000)));
        assert_eq!(there.requests.get(), 0);
        let local = FakeSource::new(None, None);
        assert!(wait_for_data(&local, 65_536, STALL_WAIT_BYTES, ms(5_000)));
        assert_eq!(local.requests.get(), 0);
    }

    // SPOTIFYGOOD: see stall_action
    #[test]
    fn a_stall_keeps_the_track_for_a_while() {
        let now = Instant::now() + Duration::from_secs(3600);
        // the first timed-out read, and the ones after it while the data doesn't come
        assert_eq!(stall_action(true, None, now, true), StallAction::Retry);
        assert_eq!(
            stall_action(true, Some(now - Duration::from_secs(40)), now, true),
            StallAction::Retry
        );
        // then it pauses (at the position played)
        assert_eq!(
            stall_action(true, Some(now - STREAM_STALL_MAX), now, true),
            StallAction::Pause
        );
        // a broken track, or a session that is gone, is skipped as before
        assert_eq!(stall_action(false, None, now, true), StallAction::Skip);
        assert_eq!(stall_action(true, None, now, false), StallAction::Skip);
    }

    // SPOTIFYGOOD: see StreamStall
    #[test]
    fn a_stall_lasts_until_a_packet_past_it() {
        let t0 = Instant::now() + Duration::from_secs(3600);
        // stalled at 61 s; the waits for the data that time out keep its start and position
        let stall = StreamStall::again(None, 7, 61_000, t0);
        assert_eq!(
            (stall.since, stall.position_ms, stall.waiting),
            (Some(t0), 61_000, true)
        );
        let later = StreamStall::again(Some(stall), 7, 61_000, t0 + ms(8_000));
        assert_eq!((later.since, later.position_ms), (Some(t0), 61_000));
        assert_eq!(
            stall_action(true, later.since, t0 + ms(8_000), true),
            StallAction::Retry
        );
        // the data came: the re-seek decodes the position played again, still the stall (no
        // more waiting, the 60 s still count from its start)
        let reseeked = later.after_packet(Some(61_000)).expect("still stalled");
        assert!(!reseeked.waiting);
        assert_eq!(reseeked.since, Some(t0));
        // it stalls again before a new packet: waits again, the same start
        let again = StreamStall::again(Some(reseeked), 7, 61_000, t0 + ms(30_000));
        assert!(again.waiting);
        assert_eq!(
            stall_action(true, again.since, t0 + STREAM_STALL_MAX, true),
            StallAction::Pause
        );
        // a packet past it ends the stall, as does the end of the track
        assert!(reseeked.after_packet(Some(61_020)).is_none());
        assert!(reseeked.after_packet(None).is_none());
    }

    // SPOTIFYGOOD: see Player::last_decoded
    #[test]
    fn the_last_packet_is_shared() {
        let decoded = SharedDecoded::default();
        let track = SpotifyUri::Track {
            id: SpotifyId::from_raw(&[1; 16]).expect("id"),
        };
        let other = SpotifyUri::Track {
            id: SpotifyId::from_raw(&[2; 16]).expect("id"),
        };
        set_decoded(&decoded, &track, 1_000);
        let first = lock_decoded(&decoded).clone().expect("decoded");
        set_decoded(&decoded, &track, 1_020);
        let second = lock_decoded(&decoded).clone().expect("decoded");
        assert_eq!(
            (second.track_id.clone(), second.position_ms),
            (track, 1_020)
        );
        assert!(second.at >= first.at);
        set_decoded(&decoded, &other, 0);
        assert_eq!(
            lock_decoded(&decoded).as_ref().map(|d| d.track_id.clone()),
            Some(other)
        );
    }

    // SPOTIFYGOOD: see DecoderError::Stalled
    #[test]
    fn a_timed_out_read_is_a_stall() {
        use std::io;
        let timed_out = || io::Error::new(io::ErrorKind::TimedOut, "no data");
        assert!(DecoderError::from_io(timed_out()).is_stall());
        assert!(
            !DecoderError::from_io(io::Error::new(io::ErrorKind::BrokenPipe, "closed")).is_stall()
        );
        // a loader that is gone
        let gone = DecoderError::from_io(io::Error::new(io::ErrorKind::BrokenPipe, "closed"));
        assert!(gone.is_loader_gone() && !gone.is_stall());
        // as next_packet and seek get it from symphonia
        let err: DecoderError = symphonia::core::errors::Error::IoError(timed_out()).into();
        assert!(err.is_stall());
        let err: DecoderError = symphonia::core::errors::Error::DecodeError("bad packet").into();
        assert!(!err.is_stall());
    }

    #[test]
    fn the_line_at_1x_is_the_stock_one() {
        let now = Instant::now() + Duration::from_secs(3600);
        for position in [0, 1, 999, 1_000, 61_234] {
            assert_eq!(
                nominal_start_time(now, ms(position), 1.),
                now.checked_sub(ms(position))
            );
        }
        let start = now - ms(10_000);
        assert!(!lags_behind(now, start, ms(9_001), 1.));
        assert!(lags_behind(now, start, ms(9_000), 1.));
        assert!(!lags_behind(now, start, ms(12_000), 1.), "ahead");
        // a line in the future (just re-based): not behind
        assert!(!lags_behind(start, now, ms(0), 2.));

        assert_eq!(valid_playback_speed(1.5), 1.5);
        for invalid in [0., -1., f64::NAN, f64::INFINITY] {
            assert_eq!(valid_playback_speed(invalid), 1.);
        }
        assert_eq!(valid_playback_speed(1e9), 20.);
    }
}
