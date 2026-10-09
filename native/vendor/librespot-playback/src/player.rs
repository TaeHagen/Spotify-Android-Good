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
    audio::{AudioDecrypt, AudioFetchParams, AudioFile, StreamLoaderController},
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
// SPOTIFYGOOD: see the command drain in PlayerInternal::poll
use tokio::sync::mpsc::error::TryRecvError;

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
    // SPOTIFYGOOD: (d) a track whose loader can't deliver: it stalled for STREAM_STALL_MAX (it
    // paused, its next play opens it), or its loader is gone (an expired CDN URL: opened at once,
    // still playing). It is opened again (load_track: a new CDN URL, a new loader) at the
    // position played, never its decoder reused, by every load of it until it is open
    // (start_playback); a load that fails for a reason that can pass stays paused at that
    // position (see reopen_waits). A load of another track forgets it.
    reopen: Option<SpotifyUri>,
    // SPOTIFYGOOD: (d) see handle_set_session
    stale_loader: Option<SpotifyUri>,
    // SPOTIFYGOOD: a loader (a load's or a preload's) was made after this pass polled the
    // loaders: the poll goes round once more before it returns, so that the new one is polled
    // and wakes the thread when it is done. A reopen from the packet loop (a loader that is gone,
    // a track a new session marked) was never polled: nothing woke the player thread, it stayed
    // Loading for good.
    fresh_loader: bool,
}

// SPOTIFYGOOD: Stalls of streamed files: see PATCHES.md "Stalls" for the invariants (a) to (e)
// this code keeps, and how.
// SPOTIFYGOOD: how long a playing streamed track waits for its data before it pauses, as long as
// the engine keeps the session up for a device that streams from its buffer
const STREAM_STALL_MAX: Duration = Duration::from_secs(60);

// SPOTIFYGOOD: a streamed track that waits for its data: a read timed out (the decoder stopped
// where it read), or a seek found its data missing (see seek_without_waiting). It produces no
// audio until the data is there and the decoder is at `position_ms` again, and stays one until a
// packet past that position comes.
#[derive(Debug, Clone, Copy)]
struct StreamStall {
    play_request_id: u64,
    /// since when the playing track produces no audio (Spirc was told, `PlayerEvent::Stalled`);
    /// `None` while it is paused (a resume waits anew)
    since: Option<Instant>,
    /// the position to play from: the one played when it stalled, or a seek's target
    position_ms: u32,
    /// the byte the decoder needs (where its read timed out, the first one a seek missed);
    /// `None` once it came and the decoder is at `position_ms` again
    at: Option<usize>,
    /// when `at` was requested last (see wait_for_data)
    requested: Option<Instant>,
}

impl StreamStall {
    /// Waits for the byte `at` to play from `position_ms`; if `playing`, since now
    fn new(play_request_id: u64, position_ms: u32, at: usize, playing: bool) -> Self {
        Self {
            play_request_id,
            since: playing.then(Instant::now),
            position_ms,
            at: Some(at),
            requested: None,
        }
    }

    /// After a read (at `at`, the read position) or a wait for the data that timed out: it
    /// waits, since the first one, to play from where it stalled
    fn again(
        stall: Option<Self>,
        play_request_id: u64,
        position_ms: u32,
        at: Option<usize>,
        now: Instant,
    ) -> Self {
        Self {
            play_request_id,
            since: Some(stall.and_then(|stall| stall.since).unwrap_or(now)),
            position_ms: stall.map_or(position_ms, |stall| stall.position_ms),
            at: stall.and_then(|stall| stall.at).or(at),
            requested: stall.and_then(|stall| stall.requested),
        }
    }

    /// After a packet at `position_ms` (`None`: the end of the track): over once it is past the
    /// position it stalled at, the one the re-seek decodes again doesn't count
    fn after_packet(self, position_ms: Option<u32>) -> Option<Self> {
        match position_ms {
            Some(position) if position <= self.position_ms => Some(self),
            _ => None,
        }
    }
}

// SPOTIFYGOOD: see wait_for_data
/// How much data at the byte a stalled stream needs it waits for before the decoder goes on (a
/// page or two)
const STALL_WAIT_BYTES: usize = 16 * 1024;

// SPOTIFYGOOD: see wait_for_data
/// How often a wait for the data of a stream looks whether it is there
const DATA_POLL: Duration = Duration::from_millis(100);

// SPOTIFYGOOD: what the player needs of a stream's loader to wait for its data and to seek
// without waiting for it (a test fakes it)
trait DataSource {
    /// where the file is read; `None`: not streamed, its data is there
    fn read_position(&self) -> Option<usize>;
    /// requests the bytes once
    fn request(&self, start: usize, length: usize);
    fn available(&self, start: usize, length: usize) -> bool;
    /// the loader ended: nothing it is asked for comes any more
    fn gone(&self) -> bool;
    /// while on, a read of data that isn't there fails at once
    fn fail_fast(&self, on: bool);
    /// the first offset such a read missed since fail-fast was turned on
    fn missed(&self) -> Option<usize>;
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

    fn gone(&self) -> bool {
        self.is_loader_gone()
    }

    fn fail_fast(&self, on: bool) {
        self.set_fail_fast(on)
    }

    fn missed(&self) -> Option<usize> {
        StreamLoaderController::missed(self)
    }
}

// SPOTIFYGOOD: see wait_for_data
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Waited {
    Came,
    /// at the deadline after its request
    TimedOut,
    /// the loader ended (see StreamLoaderController::is_loader_gone)
    Gone,
    /// a command came (it is handled, then the wait goes on without a new request)
    Interrupted,
}

// SPOTIFYGOOD: (a) the player's one wait for a stream's data. The bytes are requested once
// (`requested`: not again within `deadline` of the last request, so that a wait a command
// interrupted doesn't ask again), then it looks every DATA_POLL whether they are there, until
// `deadline` after the request; nothing extends it. librespot-audio's `fetch_blocking` requested a
// range again after every failure of it and started its timeout anew with every wake-up: with
// requests that fail at once (an expired CDN URL's 403, a 5xx, a refused connection) it never
// returned, the player thread handled no command, and it drained the per-domain rate limit.
/// One wait for `wait` bytes at `at` (`request` bytes are requested)
fn wait_for_data(
    source: &impl DataSource,
    at: usize,
    request: usize,
    wait: usize,
    deadline: Duration,
    requested: &mut Option<Instant>,
    interrupted: impl Fn() -> bool,
) -> Waited {
    if source.available(at, wait) {
        return Waited::Came;
    }
    let now = Instant::now();
    let since = match *requested {
        Some(since) if now.saturating_duration_since(since) < deadline => since,
        _ => {
            source.request(at, request.max(wait));
            *requested = Some(now);
            // SPOTIFYGOOD: it may be there already (it was on its way)
            if source.available(at, wait) {
                return Waited::Came;
            }
            now
        }
    };
    let end = since + deadline;
    loop {
        if source.gone() {
            return Waited::Gone;
        }
        let left = end.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Waited::TimedOut;
        }
        thread::sleep(DATA_POLL.min(left));
        if source.available(at, wait) {
            return Waited::Came;
        }
        // SPOTIFYGOOD: (f) only after a sleep, and never past the deadline: a command it sees
        // but the loop can't take (tokio's budget ran out, see Player::new) made it return at
        // once, again and again, a spin that never timed out
        if Instant::now() < end && interrupted() {
            return Waited::Interrupted;
        }
    }
}

// SPOTIFYGOOD: see wait_for_data
/// The data a stream plays on with: what it requests at a time
fn read_ahead_bytes(bytes_per_second: usize) -> usize {
    (AudioFetchParams::get()
        .read_ahead_during_playback
        .as_secs_f32()
        * bytes_per_second as f32) as usize
}

// SPOTIFYGOOD: (a) after a seek, which requested nothing (see seek_without_waiting): the data to
// play on is requested without waiting for it (the packet loop's reads wait, bounded, and a read
// that times out is a stall). Stock waited for it on the player thread after every seek.
fn request_read_ahead(source: &impl DataSource, bytes_per_second: usize) {
    if let Some(at) = source.read_position() {
        source.request(at, read_ahead_bytes(bytes_per_second));
    }
}

// SPOTIFYGOOD: see seek_without_waiting
#[derive(Debug)]
enum SeekOutcome {
    /// at this position
    Done(u32),
    /// its data isn't there: the first byte it missed
    Missed(usize),
    Failed(DecoderError),
}

// SPOTIFYGOOD: (b) a seek on the player thread never waits for data. symphonia's Ogg seek bisects
// the whole file, and each of its probes into data that isn't there waited `download_timeout` (a
// -15 s past the download, a same-track load of a transfer or a resume, the re-seek of a stall:
// tens of seconds with no audio and no command handled, and the failed probes were taken as the
// end of the stream). Its reads fail at once instead (StreamLoaderController::set_fail_fast); a
// seek that missed data returns the first byte it missed: the caller waits for it (one bounded
// wait that commands interrupt) and seeks again. A successful seek is exact (the probes that
// failed only narrowed its bisection, its scan then read the packets up to the target).
fn seek_without_waiting(
    decoder: &mut (impl AudioDecoder + ?Sized),
    source: &impl DataSource,
    position_ms: u32,
) -> SeekOutcome {
    source.fail_fast(true);
    let result = decoder.seek(position_ms);
    source.fail_fast(false);
    match result {
        Ok(position_ms) => SeekOutcome::Done(position_ms),
        Err(e) => match source.missed() {
            Some(at) => SeekOutcome::Missed(at),
            None => SeekOutcome::Failed(e),
        },
    }
}

// SPOTIFYGOOD: the loader's seek to the start position (on its own thread): it waits for each
// piece of data the seek misses (see seek_without_waiting), one wait of at most
// `download_timeout` each, as long as data comes. Without data it fails after one wait
// (`Stalled`, a network failure); each probe used to wait `download_timeout` for it. Data that
// came is progress: a miss at the byte it waited for last (its data came) is none, and fails.
// There is no count: an MP3 seek read every frame header from the start of the file (about 5 s
// of audio per wait; it seeks coarsely now, see SymphoniaDecoder), and a cap of 64 waits failed
// a resume or a transfer a few minutes in. A load the player no longer wants (`superseded`)
// stops at once, also in the middle of a wait.
fn seek_waiting(
    decoder: &mut (impl AudioDecoder + ?Sized),
    source: &impl DataSource,
    position_ms: u32,
    bytes_per_second: usize,
    deadline: Duration,
    superseded: impl Fn() -> bool,
) -> Result<u32, DecoderError> {
    let mut waited_at = None;
    loop {
        if superseded() {
            return Err(DecoderError::Stalled("the load was superseded".into()));
        }
        let at = match seek_without_waiting(decoder, source, position_ms) {
            SeekOutcome::Done(position_ms) => return Ok(position_ms),
            SeekOutcome::Failed(e) => return Err(e),
            SeekOutcome::Missed(at) => at,
        };
        if waited_at == Some(at) {
            return Err(DecoderError::Stalled("the seek doesn't get on".into()));
        }
        waited_at = Some(at);
        let mut requested = None;
        match wait_for_data(
            source,
            at,
            read_ahead_bytes(bytes_per_second),
            STALL_WAIT_BYTES,
            deadline,
            &mut requested,
            &superseded,
        ) {
            // SPOTIFYGOOD: Interrupted: superseded (the loop's next pass returns)
            Waited::Came | Waited::Interrupted => (),
            Waited::TimedOut => return Err(DecoderError::Stalled("no data in time".into())),
            Waited::Gone => return Err(DecoderError::LoaderGone("the loader is gone".into())),
        }
    }
}

// SPOTIFYGOOD: see PlayerInternal::stalled_step
enum StallStep {
    /// no stall waits for data: the decoder reads on
    Decode,
    /// the data came, the decoder is at the stall's position again
    Resumed,
    /// not yet (a command came, or the decoder's seek missed more data): the loop goes on
    Wait,
    /// the wait for the data timed out, or its loader is gone, or the decoder failed
    Failed(DecoderError),
}

// SPOTIFYGOOD: what the packet loop does when the decoder fails
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StallAction {
    /// wait for the data again, the track keeps playing
    Retry,
    /// pause at the position played: the data didn't come for STREAM_STALL_MAX
    Pause,
    /// a broken track: on to the next one, as stock
    Skip,
}

// SPOTIFYGOOD: a read of a streamed file times out after librespot-audio's `download_timeout`
// (8 s) without data. Stock took it as a broken track and skipped it (EndOfTrack): a stall of a
// few seconds past the buffer (a tunnel, a cell handover) ended the episode, and Spirc went on
// with the next one. A stall keeps the track: each attempt is one wait for the data
// (PlayerInternal::stalled_step), every queued command is handled before and between the
// attempts (a pause ends the waiting: a paused track doesn't read), and after STREAM_STALL_MAX it
// pauses at the position played. Also when the session is gone meanwhile (its keep-alive ran
// out on a dead network): it was skipped then, and Spirc's next item became the restore point
// the engine froze (the reconnect played the next episode from 0:00). The engine's restore
// freezes the stall at the position heard and pauses the Player. Only a decoder that fails
// skips the track as before.
/// What to do when the playing track can't go on: `stalled` its data didn't come (a timed-out
/// read, or the wait for it), else the decoder failed. Stalled since `stalled_since` (its first
/// timed-out read).
fn stall_action(stalled: bool, stalled_since: Option<Instant>, now: Instant) -> StallAction {
    if !stalled {
        return StallAction::Skip;
    }
    match stalled_since {
        Some(since) if now.saturating_duration_since(since) >= STREAM_STALL_MAX => {
            StallAction::Pause
        }
        _ => StallAction::Retry,
    }
}

// SPOTIFYGOOD: (d) a reopen (see PlayerInternal::reopen) whose load fails for one of these
// reasons stays paused at its position, and the next play opens it again: they can pass (the
// network, a key refused for now, a loader thread that died). It was skipped (Unavailable, Spirc
// went on with the next item) and its position went with it. Spotify's verdicts (not available,
// a key denied for good, not decodable) skip it like any load.
fn reopen_waits(reason: UnavailableReason) -> bool {
    matches!(
        reason,
        UnavailableReason::NetworkError
            | UnavailableReason::KeyTemporarilyDenied
            | UnavailableReason::Other
    )
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
                // SPOTIFYGOOD: see PlayerInternal::stale_loader and fresh_loader
                stale_loader: None,
                fresh_loader: false,
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
            // SPOTIFYGOOD: (f) without tokio's cooperative budget: the future is the player
            // thread's loop, not a task among others. `block_on` gives each poll 128 units and
            // every command received uses one; the poll doesn't return while a track plays, so
            // after 128 commands in one stretch `poll_recv` gave none (Pause, Next and the close
            // of Player::drop were ignored). The loop takes its commands with `try_recv` too,
            // which uses no budget.
            runtime.block_on(tokio::task::coop::unconstrained(internal));
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
        // SPOTIFYGOOD: the position it loads at (a reopen that fails stays paused there, see
        // PlayerInternal::reopen)
        position_ms: u32,
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

    // SPOTIFYGOOD: unused since handle_command_seek seeks without waiting (it matches the state)
    #[allow(dead_code)]
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
    // SPOTIFYGOOD: alive while the player still wants the load (the future PlayerInternal::
    // load_track returned holds it), see superseded
    wanted: std::sync::Weak<()>,
}

impl PlayerTrackLoader {
    // SPOTIFYGOOD: the player dropped the load (another load or a stop superseded it): its seek
    // stops waiting for data (see seek_waiting), and the file it drops stops its download. It
    // went on to the end on its own thread, downloading what the seek needed.
    fn superseded(&self) -> bool {
        self.wanted.strong_count() == 0
    }

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
                    // SPOTIFYGOOD: report a key failure as such; otherwise a decode error. A
                    // read that got no data is the network's failure (a reopen keeps its
                    // position then, see reopen_waits).
                    return Err(
                        key_failure.unwrap_or(if e.is_stall() || e.is_loader_gone() {
                            UnavailableReason::NetworkError
                        } else {
                            UnavailableReason::DecodeError
                        }),
                    );
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
            // SPOTIFYGOOD: one bounded wait for each piece of data the seek misses (see
            // seek_waiting), while the load is wanted; without data it is the network's failure
            // (see reopen_waits)
            let stream_position_ms = match seek_waiting(
                &mut *decoder,
                &stream_loader_controller,
                position_ms,
                bytes_per_second,
                AudioFetchParams::get().download_timeout,
                || self.superseded(),
            ) {
                Ok(new_position_ms) => new_position_ms,
                Err(e) => {
                    error!(
                        "PlayerTrackLoader::load_track error seeking to starting position {position_ms}: {e}"
                    );
                    return Err(if e.is_stall() || e.is_loader_gone() {
                        UnavailableReason::NetworkError
                    } else {
                        UnavailableReason::DecodeError
                    }); // SPOTIFYGOOD: reason
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

            // SPOTIFYGOOD: (a) every queued command, not one per pass: a pass may wait for data
            // (bounded), and a command waited one wait for each command queued before it.
            // (f) `try_recv`, which uses no cooperative budget (see Player::new): `poll_recv` gave
            // nothing once it ran out, with commands queued, and the stall's wait (which sees
            // them) returned at once, again and again: the thread spun at full CPU. `poll_recv`
            // only registers the waker before the poll returns (below).
            loop {
                match self.commands.try_recv() {
                    Ok(cmd) => {
                        all_futures_completed_or_not_ready = false;
                        if let Err(e) = self.handle_command(cmd) {
                            error!("Error handling command: {e}");
                        }
                    }
                    Err(TryRecvError::Empty) => break,
                    // client has disconnected - shut down.
                    Err(TryRecvError::Disconnected) => return Poll::Ready(()),
                }
            }

            // SPOTIFYGOOD: the loaders made so far are polled below (see fresh_loader)
            self.fresh_loader = false;

            // Handle loading of a new track to play
            if let PlayerState::Loading {
                ref mut loader,
                ref track_id,
                start_playback,
                play_request_id,
                position_ms,
            } = self.state
            {
                // The loader may be terminated if we are trying to load the same track
                // as before, and that track failed to open before.
                let track_id = track_id.clone();

                if !loader.as_mut().is_terminated() {
                    match loader.as_mut().poll(cx) {
                        // SPOTIFYGOOD: an explicit track whose load ended after the filter was
                        // turned on isn't played (it was, also when a repeat or the queue loaded
                        // it again)
                        Poll::Ready(Ok(loaded_track))
                            if self.filtered(loaded_track.is_explicit) =>
                        {
                            warn!("<{track_id:?}> is explicit, which client setting forbids");
                            self.send_event(PlayerEvent::Unavailable {
                                track_id,
                                play_request_id,
                                reason: UnavailableReason::NotAvailable,
                            })
                        }
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
                        // SPOTIFYGOOD: (d) see reopen_waits; the state stays Loading with the
                        // loader ended, a play opens it again (handle_play)
                        Poll::Ready(Err(e))
                            if self.reopen.as_ref() == Some(&track_id) && reopen_waits(e) =>
                        {
                            warn!(
                                "Opening <{track_id:?}> again failed ({e:?}), paused at {position_ms} ms"
                            );
                            // paused: a seek loads it again paused, a play playing
                            if let PlayerState::Loading {
                                ref mut start_playback,
                                ..
                            } = self.state
                            {
                                *start_playback = false;
                            }
                            self.send_event(PlayerEvent::Paused {
                                track_id,
                                play_request_id,
                                position_ms,
                            })
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
                    // SPOTIFYGOOD: an explicit track whose preload ended after the filter was
                    // turned on is dropped like a preload that failed (it played when the track
                    // before it ended)
                    Poll::Ready(Ok(loaded_track)) if self.filtered(loaded_track.is_explicit) => {
                        self.preload_failed(track_id, UnavailableReason::NotAvailable)
                    }
                    Poll::Ready(Ok(loaded_track)) => {
                        self.send_event(PlayerEvent::Preloading {
                            track_id: track_id.clone(),
                        });
                        self.preload = PlayerPreload::Ready {
                            track_id,
                            loaded_track: Box::new(loaded_track),
                        };
                    }
                    // SPOTIFYGOOD: moved to preload_failed
                    Poll::Ready(Err(reason)) => self.preload_failed(track_id, reason),
                    Poll::Pending => (),
                }
            }

            if self.state.is_playing() {
                self.ensure_sink_running();
                // SPOTIFYGOOD: (a), (b) a stalled track waits for its data here, see stalled_step
                let step = self.stalled_step();
                if let StallStep::Wait = step {
                    continue;
                }
                // SPOTIFYGOOD: see nominal_start_time
                let speed = self.playback_speed;
                // SPOTIFYGOOD: see stall_action
                let stall = self.stream_stall;
                // SPOTIFYGOOD: see Player::last_decoded
                let decoded = self.decoded.clone();
                // SPOTIFYGOOD: see handle_set_session
                let marked = self
                    .stale_loader
                    .clone()
                    .filter(|_| self.session_logged_in());

                if let PlayerState::Playing {
                    ref track_id,
                    play_request_id,
                    ref mut decoder,
                    normalisation_factor,
                    ref mut stream_position_ms,
                    ref mut reported_nominal_start_time,
                    ref stream_loader_controller,
                    ..
                } = self.state
                {
                    let track_id = track_id.clone();
                    let stall = stall.filter(|stall| stall.play_request_id == play_request_id);
                    // SPOTIFYGOOD: see stalled_step
                    let data_came = matches!(step, StallStep::Resumed);
                    let next_packet = match step {
                        StallStep::Failed(e) => Err(e),
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
                                            // SPOTIFYGOOD: (c) also the first packet after a
                                            // stall, whatever the line says (it takes Spirc from
                                            // LoadingPlay back to Playing)
                                            let notify_about_position = data_came
                                                || match *reported_nominal_start_time {
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
                        // SPOTIFYGOOD: (d) the file's loader is gone (its CDN URL expired, or its
                        // rate limit ran out): the data can't come, the track is opened again at
                        // once at the position played (a new URL, a new loader), still playing.
                        // Spirc shows it loading there (its Loading arm); a reopen that fails
                        // stays paused there.
                        Err(e) if e.is_loader_gone() => {
                            let position_ms =
                                stall.map_or(*stream_position_ms, |stall| stall.position_ms);
                            warn!(
                                "The stream of <{track_id:?}> can't get its data, opening it again at {position_ms} ms: {e}"
                            );
                            self.reopen = Some(track_id.clone());
                            self.reopen_track(track_id, play_request_id, true, position_ms);
                        }
                        // SPOTIFYGOOD: see stall_action
                        Err(e) => match stall_action(
                            e.is_stall(),
                            stall.and_then(|stall| stall.since),
                            Instant::now(),
                        ) {
                            // SPOTIFYGOOD: (d) a track marked for a reopen (a new session, see
                            // handle_set_session) opens again at its first stall, still playing,
                            // instead of waiting on the old session's loader
                            StallAction::Retry if marked.as_ref() == Some(&track_id) => {
                                let position_ms =
                                    stall.map_or(*stream_position_ms, |stall| stall.position_ms);
                                warn!(
                                    "Stream of <{track_id:?}> stalled on the old session's loader, opening it again at {position_ms} ms: {e}"
                                );
                                self.reopen_track(track_id, play_request_id, true, position_ms);
                            }
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
                                // SPOTIFYGOOD: a read that timed out stopped at the read position
                                let at = stream_loader_controller.read_position();
                                let stall = StreamStall::again(
                                    stall,
                                    play_request_id,
                                    position_ms,
                                    at,
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
                                // SPOTIFYGOOD: (f) it ends there, like a track that played to its
                                // end: it stayed Playing, and each pass read the failed decoder
                                // again and sent another EndOfTrack, at full CPU until a load
                                self.state.playing_to_end_of_track();
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
                // SPOTIFYGOOD: a loader made after the loaders were polled (a reopen from the
                // packet loop) is polled first, see fresh_loader
                if std::mem::take(&mut self.fresh_loader) {
                    continue;
                }
                // SPOTIFYGOOD: (f) the waker for the next command (see the drain above)
                match self.commands.poll_recv(cx) {
                    Poll::Ready(Some(cmd)) => {
                        if let Err(e) = self.handle_command(cmd) {
                            error!("Error handling command: {e}");
                        }
                    }
                    Poll::Ready(None) => return Poll::Ready(()),
                    Poll::Pending => return Poll::Pending,
                }
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
                self.stale_loader = None;
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
        match self.state {
            PlayerState::Paused {
                ref track_id,
                play_request_id,
                stream_position_ms,
                ..
            } => {
                let track_id = track_id.clone();
                // SPOTIFYGOOD: (d) see PlayerInternal::reopen and stale_loader
                if self.opens_again(&track_id) {
                    self.reopen_track(track_id, play_request_id, true, stream_position_ms);
                    return;
                }

                self.state.paused_to_playing(self.playback_speed);
                self.send_event(PlayerEvent::Playing {
                    track_id: track_id.clone(),
                    play_request_id,
                    position_ms: stream_position_ms,
                });
                self.ensure_sink_running();
                // SPOTIFYGOOD: (c) see stall_resumed
                self.stall_resumed(play_request_id, track_id);
            }
            PlayerState::Loading {
                ref track_id,
                ref mut loader,
                ref mut start_playback,
                play_request_id,
                position_ms,
            } => {
                // SPOTIFYGOOD: (d) a reopen that failed (it stayed paused, see reopen_waits) is
                // opened again; stock only set the flag, and nothing loaded it any more
                if loader.as_mut().is_terminated() && self.reopen.as_ref() == Some(track_id) {
                    let track_id = track_id.clone();
                    self.reopen_track(track_id, play_request_id, true, position_ms);
                    return;
                }
                *start_playback = true;
            }
            _ => error!("Player::play called from invalid state: {:?}", self.state),
        }
    }

    // SPOTIFYGOOD: (d) see PlayerInternal::reopen
    /// Opens the track again (a new loader) at `position_ms`, with the same request id
    fn reopen_track(
        &mut self,
        track_id: SpotifyUri,
        play_request_id: u64,
        play: bool,
        position_ms: u32,
    ) {
        set_decoded(&self.decoded, &track_id, position_ms);
        if let Err(e) = self.handle_command_load(track_id, Some(play_request_id), play, position_ms)
        {
            error!("Opening the track again failed: {e}");
        }
    }

    // SPOTIFYGOOD: (c) a resumed track whose stall still waits for data that isn't there plays
    // nothing: Spirc shows it buffering at once (Stalled; it extrapolated until the first wait
    // timed out). Its STREAM_STALL_MAX starts anew (a pause ended the waiting).
    fn stall_resumed(&mut self, play_request_id: u64, track_id: SpotifyUri) {
        let waiting = match (&self.state, self.stream_stall) {
            (
                PlayerState::Playing {
                    stream_loader_controller,
                    ..
                },
                Some(StreamStall {
                    play_request_id: id,
                    at: Some(at),
                    position_ms,
                    ..
                }),
            ) if id == play_request_id
                && !stream_loader_controller.range_available_at(at, STALL_WAIT_BYTES) =>
            {
                Some(position_ms)
            }
            _ => None,
        };
        if let Some(position_ms) = waiting {
            self.stalls_now(play_request_id, track_id, position_ms);
        }
    }

    // SPOTIFYGOOD: (c) the playing track produces no audio from now on (its stall waits for
    // data): Spirc shows it buffering at the stall's position (PlayerEvent::Stalled), its
    // STREAM_STALL_MAX counts from now, and the line is cleared, so that the first packet after
    // the stall reports its position (a PositionCorrection: Spirc plays on from there)
    fn stalls_now(&mut self, play_request_id: u64, track_id: SpotifyUri, position_ms: u32) {
        if let Some(stall) = self.stream_stall.as_mut() {
            stall.since = Some(Instant::now());
        }
        if let PlayerState::Playing {
            ref mut reported_nominal_start_time,
            ..
        } = self.state
        {
            *reported_nominal_start_time = None;
        }
        self.send_event(PlayerEvent::Stalled {
            play_request_id,
            track_id,
            position_ms,
        });
    }

    // SPOTIFYGOOD: (b), (c) a seek, or the reuse of a loaded track, whose data isn't there: the
    // track waits for the byte `at` to play from `position_ms` (the packet loop, see
    // stalled_step); while it plays, Spirc shows it buffering there at once
    fn begin_stall(
        &mut self,
        play_request_id: u64,
        track_id: SpotifyUri,
        position_ms: u32,
        at: usize,
    ) {
        let playing = self.state.is_playing();
        self.stream_stall = Some(StreamStall::new(play_request_id, position_ms, at, playing));
        if playing {
            self.stalls_now(play_request_id, track_id, position_ms);
        }
    }

    // SPOTIFYGOOD: (a), (b) the step of the packet loop for a playing track whose stall waits
    // for its data: one wait for it (wait_for_data, which a queued command interrupts), then the
    // decoder seeks back to the stall's position without waiting (seek_without_waiting; a seek
    // that misses more data waits for that next)
    fn stalled_step(&mut self) -> StallStep {
        let PlayerState::Playing {
            play_request_id,
            ref mut decoder,
            ref stream_loader_controller,
            bytes_per_second,
            ..
        } = self.state
        else {
            return StallStep::Decode;
        };
        let Some(stall) = self
            .stream_stall
            .as_mut()
            .filter(|stall| stall.play_request_id == play_request_id)
        else {
            return StallStep::Decode;
        };
        let Some(at) = stall.at else {
            return StallStep::Decode;
        };
        let commands = &self.commands;
        match wait_for_data(
            stream_loader_controller,
            at,
            read_ahead_bytes(bytes_per_second),
            STALL_WAIT_BYTES,
            AudioFetchParams::get().download_timeout,
            &mut stall.requested,
            || !commands.is_empty(),
        ) {
            Waited::Interrupted => StallStep::Wait,
            Waited::TimedOut => StallStep::Failed(DecoderError::Stalled("no data in time".into())),
            Waited::Gone => {
                StallStep::Failed(DecoderError::LoaderGone("the loader is gone".into()))
            }
            Waited::Came => {
                match seek_without_waiting(
                    &mut **decoder,
                    stream_loader_controller,
                    stall.position_ms,
                ) {
                    SeekOutcome::Done(_) => {
                        stall.at = None;
                        stall.requested = None;
                        StallStep::Resumed
                    }
                    SeekOutcome::Missed(next) => {
                        stall.at = Some(next);
                        stall.requested = None;
                        StallStep::Wait
                    }
                    SeekOutcome::Failed(e) => StallStep::Failed(e),
                }
            }
        }
    }

    // SPOTIFYGOOD: (d) a new session (the engine reconnected: often another network, after a
    // Wi-Fi that lost its internet). A streamed track whose data playback still needs isn't all
    // there is marked (`stale_loader`): its loader belongs to the old session (its HTTP client,
    // its connections), whose requests may hang on the old network, so the track stalled after
    // its buffer until it paused, while the new network worked. Its next play or load, or its
    // first stall, opens it again (like PlayerInternal::reopen), but only once the session has
    // logged in (`session_logged_in`): the engine binds the never-connected offline session when
    // it goes offline, and a reopen through it can never load. What playback needs: from the
    // read position to the end for a playing or paused track (like `fully_buffered`, the test of
    // the engine's offline hand-off: a track resumed mid-file has its tail first and its head
    // last, and was reopened offline, throwing its buffer away); the whole file for an ended
    // track (a repeat plays it from its start). A preload that isn't all there is dropped.
    fn handle_set_session(&mut self, session: Session) {
        self.session = session;
        let incomplete = |controller: &StreamLoaderController| {
            !controller.range_available_at(0, controller.len())
        };
        self.stale_loader = match self.state {
            PlayerState::Playing {
                ref track_id,
                ref stream_loader_controller,
                ..
            }
            | PlayerState::Paused {
                ref track_id,
                ref stream_loader_controller,
                ..
            } if !stream_loader_controller.range_to_end_available() => Some(track_id.clone()),
            PlayerState::EndOfTrack {
                ref track_id,
                loaded_track:
                    PlayerLoadedTrackData {
                        ref stream_loader_controller,
                        ..
                    },
                ..
            } if incomplete(stream_loader_controller) => Some(track_id.clone()),
            _ => None,
        };
        if let Some(ref track_id) = self.stale_loader {
            info!("<{track_id:?}> streams with the old session's loader, it is opened again");
        }
        let drop_preload = match &self.preload {
            PlayerPreload::Ready { loaded_track, .. } => {
                incomplete(&loaded_track.stream_loader_controller)
            }
            PlayerPreload::Loading { .. } => true,
            PlayerPreload::None => false,
        };
        if drop_preload {
            self.preload = PlayerPreload::None;
        }
    }

    // SPOTIFYGOOD: see handle_set_session (a session that has logged in has its credentials)
    fn session_logged_in(&self) -> bool {
        !self.session.auth_data().is_empty()
    }

    // SPOTIFYGOOD: (d) whether the next play or load of `track_id` opens it again (a reopen, or a
    // track a new session marked, see handle_set_session)
    fn opens_again(&self, track_id: &SpotifyUri) -> bool {
        self.reopen.as_ref() == Some(track_id)
            || (self.stale_loader.as_ref() == Some(track_id) && self.session_logged_in())
    }

    // SPOTIFYGOOD: whether the explicit filter refuses a track
    fn filtered(&self, is_explicit: bool) -> bool {
        is_explicit && self.session.filter_explicit_content()
    }

    // SPOTIFYGOOD: moved from the poll loop (also for an explicit preload the filter refuses)
    fn preload_failed(&mut self, track_id: SpotifyUri, reason: UnavailableReason) {
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
            // SPOTIFYGOOD: (c) a cleared line stays cleared: during a stall (or after a seek,
            // before its first packet) the first packet must report its position, the
            // PositionCorrection that takes Spirc from LoadingPlay back to Playing. A line set
            // here kept it away, and Spirc showed buffering for the rest of the track.
            if reported_nominal_start_time.is_some() {
                *reported_nominal_start_time = nominal_start_time(
                    Instant::now(),
                    Duration::from_millis(stream_position_ms as u64),
                    speed,
                );
            }
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
        // SPOTIFYGOOD: (d) it is open again, see PlayerInternal::reopen
        if self.reopen.as_ref() == Some(&track_id) {
            self.reopen = None;
        }

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
        // SPOTIFYGOOD: (d) see PlayerInternal::reopen (a load of another track forgets it); a
        // track a new session marked (stale_loader) opens again like it, once the session has
        // logged in. A load starts any stall afresh. See Player::fully_buffered.
        let reopen = self.opens_again(&track_id);
        if reopen {
            info!("Opening <{track_id:?}> again at {position_ms} ms");
            self.reopen = Some(track_id.clone());
        } else {
            self.reopen = None;
        }
        if reopen || self.stale_loader.as_ref() != Some(&track_id) {
            self.stale_loader = None;
        }
        // SPOTIFYGOOD: (b) a stall that waits for data says its decoder isn't at the track's
        // position: a seek that missed data left it where its scan stopped (the end of the
        // download, minutes away), a read that timed out in the middle of a page. A reuse of it
        // seeks, also to that same position, which is what a restore or a resume loads (the
        // position shown while it stalled): it played on from where the decoder was.
        let displaced = match (&self.state, self.stream_stall) {
            (
                PlayerState::Playing {
                    play_request_id, ..
                }
                | PlayerState::Paused {
                    play_request_id, ..
                },
                Some(StreamStall {
                    play_request_id: stalled,
                    at: Some(_),
                    ..
                }),
            ) => *play_request_id == stalled,
            _ => false,
        };
        self.stream_stall = None;
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
            loaded_track,
            ..
        } = &self.state
        {
            // SPOTIFYGOOD: not when it is opened again (see PlayerInternal::reopen), nor an
            // explicit one the filter refuses now (a repeat-one played it on and on)
            if *previous_track_id == track_id && !reopen && !self.filtered(loaded_track.is_explicit)
            {
                let loaded_track = match mem::replace(&mut self.state, PlayerState::Invalid) {
                    PlayerState::EndOfTrack { loaded_track, .. } => loaded_track,
                    _ => {
                        return Err(Error::internal(format!(
                            "PlayerInternal::handle_command_load repeating the same track: invalid state: {:?}",
                            self.state
                        )));
                    }
                };

                self.preload = PlayerPreload::None;
                // SPOTIFYGOOD: (b) see start_loaded (its decoder can't seek: loaded anew below)
                if self.start_loaded(
                    track_id.clone(),
                    play_request_id,
                    loaded_track,
                    play,
                    position_ms,
                    false,
                ) {
                    return Ok(());
                }
            }
        }

        // Check if we are already playing the track. If so, just do a seek and update our info.
        if let PlayerState::Playing {
            track_id: ref current_track_id,
            is_explicit,
            ..
        }
        | PlayerState::Paused {
            track_id: ref current_track_id,
            is_explicit,
            ..
        } = self.state
        {
            // SPOTIFYGOOD: not when it is opened again (see PlayerInternal::reopen), nor an
            // explicit one the filter refuses now
            if *current_track_id == track_id && !reopen && !self.filtered(is_explicit) {
                // Move the info from the current state into a PlayerLoadedTrackData so we can use
                // the usual code path to start playback.
                // SPOTIFYGOOD: (b) the seek is start_loaded's (it was here, with `?`)
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
                    // SPOTIFYGOOD: (b) see start_loaded (its decoder can't seek: loaded anew
                    // below; stock returned the seek's error with Spirc on the new request id
                    // and the state on the old one)
                    if self.start_loaded(
                        track_id.clone(),
                        play_request_id,
                        loaded_track,
                        play,
                        position_ms,
                        displaced,
                    ) {
                        return Ok(());
                    }
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
            loaded_track,
        } = &self.preload
        {
            // SPOTIFYGOOD: not an explicit one the filter refuses now (it is loaded anew, which
            // the filter refuses)
            if track_id == *loaded_track_id && !self.filtered(loaded_track.is_explicit) {
                let preload = std::mem::replace(&mut self.preload, PlayerPreload::None);
                if let PlayerPreload::Ready { loaded_track, .. } = preload {
                    // SPOTIFYGOOD: (b) see start_loaded
                    if self.start_loaded(
                        track_id.clone(),
                        play_request_id,
                        *loaded_track,
                        play,
                        position_ms,
                        false,
                    ) {
                        return Ok(());
                    }
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
            position_ms, // SPOTIFYGOOD
        };
        // SPOTIFYGOOD: see PlayerInternal::fresh_loader
        self.fresh_loader = true;

        Ok(())
    }

    // SPOTIFYGOOD: (b) the reuse of a loaded track (the same one again, a preload) at
    // `position_ms`: its decoder seeks without waiting for data (see seek_without_waiting); if
    // its data isn't there it starts as a stall at the target (Spirc shows it buffering there).
    // Stock seeked through the whole-file bisection on the player thread, with `?` after
    // PlayRequestIdChanged: a failed seek left Spirc loading the new id and the state on the old
    // one, so the track never went on.
    /// Starts `loaded_track`; false if its decoder can't seek (it is loaded anew then).
    /// `displaced`: its decoder isn't at its position (it seeks also to that position)
    fn start_loaded(
        &mut self,
        track_id: SpotifyUri,
        play_request_id: u64,
        mut loaded_track: PlayerLoadedTrackData,
        play: bool,
        position_ms: u32,
        displaced: bool,
    ) -> bool {
        let mut missed = None;
        if displaced || position_ms != loaded_track.stream_position_ms {
            match seek_without_waiting(
                &mut *loaded_track.decoder,
                &loaded_track.stream_loader_controller,
                position_ms,
            ) {
                SeekOutcome::Done(position_ms) => {
                    loaded_track.stream_position_ms = position_ms;
                    request_read_ahead(
                        &loaded_track.stream_loader_controller,
                        loaded_track.bytes_per_second,
                    );
                }
                SeekOutcome::Missed(at) => {
                    loaded_track.stream_position_ms = position_ms.min(loaded_track.duration_ms);
                    missed = Some(at);
                }
                SeekOutcome::Failed(e) => {
                    warn!(
                        "Seeking <{track_id:?}> to {position_ms} ms failed, loading it anew: {e}"
                    );
                    return false;
                }
            }
        }
        let position_ms = loaded_track.stream_position_ms;
        self.start_playback(track_id.clone(), play_request_id, loaded_track, play);
        if let Some(at) = missed {
            self.begin_stall(play_request_id, track_id, position_ms, at);
        }
        true
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
            };
            // SPOTIFYGOOD: see PlayerInternal::fresh_loader
            self.fresh_loader = true;
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

        // SPOTIFYGOOD: (b) the decoder seeks without waiting for data (see
        // seek_without_waiting): a seek whose data isn't there (-15 s past the download,
        // scrubbing, also while the track stalls) waits for it like a stall (the packet loop
        // seeks again once it is there), and Spirc shows it buffering at the target. Stock seeked
        // through the whole-file bisection, then waited for the data after it, on the player
        // thread: each probe past the data waited `download_timeout`, tens of seconds with no
        // audio and no command handled.
        let (PlayerState::Playing {
            ref track_id,
            play_request_id,
            duration_ms,
            bytes_per_second,
            ref mut decoder,
            ref stream_loader_controller,
            ref mut stream_position_ms,
            ..
        }
        | PlayerState::Paused {
            ref track_id,
            play_request_id,
            duration_ms,
            bytes_per_second,
            ref mut decoder,
            ref stream_loader_controller,
            ref mut stream_position_ms,
            ..
        }) = self.state
        else {
            error!("Player::seek called from invalid state: {:?}", self.state);
            return Ok(());
        };
        let track_id = track_id.clone();
        let played_ms = *stream_position_ms;
        let seeked =
            match seek_without_waiting(&mut **decoder, stream_loader_controller, position_ms) {
                SeekOutcome::Done(position_ms) => {
                    request_read_ahead(stream_loader_controller, bytes_per_second);
                    *stream_position_ms = position_ms;
                    Ok((position_ms, None))
                }
                SeekOutcome::Missed(at) => {
                    let target = position_ms.min(duration_ms);
                    info!("The data at {target} ms of <{track_id:?}> isn't there, waiting for it");
                    *stream_position_ms = target;
                    Ok((target, Some(at)))
                }
                SeekOutcome::Failed(e) => Err(e),
            };

        match seeked {
            Ok((position_ms, missed)) => {
                // SPOTIFYGOOD: see Player::last_decoded
                set_decoded(&self.decoded, &track_id, position_ms);
                // SPOTIFYGOOD: no line until the first packet after the seek, which reports its
                // position (a PositionCorrection) and starts the line there (see
                // nominal_start_time). Stock started the line after its wait for the data, so
                // the Seeked position (sent before it) stayed ahead of the audio by that wait.
                if let PlayerState::Playing {
                    ref mut reported_nominal_start_time,
                    ..
                } = self.state
                {
                    *reported_nominal_start_time = None;
                }
                self.stream_stall = None;
                self.send_event(PlayerEvent::Seeked {
                    play_request_id,
                    track_id: track_id.clone(),
                    position_ms,
                });
                if let Some(at) = missed {
                    self.begin_stall(play_request_id, track_id, position_ms, at);
                }
            }
            Err(e) => {
                error!("PlayerInternal::handle_command_seek error: {e}");
                // SPOTIFYGOOD: Player::seek put the target in last_decoded, the playback stays
                // where it was
                set_decoded(&self.decoded, &track_id, played_ms);
            }
        }

        Ok(())
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

            // SPOTIFYGOOD: see handle_set_session
            PlayerCommand::SetSession(session) => self.handle_set_session(session),

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

                // SPOTIFYGOOD: an explicit preload is dropped, and one that still loads (whether
                // it is explicit isn't known yet): the next load of it loads it anew, which the
                // filter refuses. It played in full when the track before it ended. (The reuse
                // of a loaded track and a load or preload that ends later check the filter too.)
                let drop_preload = match &self.preload {
                    PlayerPreload::Ready { loaded_track, .. } => loaded_track.is_explicit,
                    PlayerPreload::Loading { .. } => true,
                    PlayerPreload::None => false,
                };
                if filter && drop_preload {
                    self.preload = PlayerPreload::None;
                }

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

        // SPOTIFYGOOD: see PlayerTrackLoader::superseded (the future below holds it)
        let wanted = Arc::new(());
        let loader = PlayerTrackLoader {
            session: self.session.clone(),
            config: self.config.clone(),
            local_file_lookup: self.local_file_lookup.clone(),
            wanted: Arc::downgrade(&wanted),
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
        // SPOTIFYGOOD: `wanted` lives as long as the future (see PlayerTrackLoader::superseded)
        result_rx.map(move |result| {
            drop(wanted);
            result.unwrap_or(Err(UnavailableReason::Other))
        })
    }

    // SPOTIFYGOOD: `preload_data_before_playback` (the wait for the data after a seek, on the
    // player thread) is gone, see request_read_ahead
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
    use crate::decoder::DecoderResult;
    use std::sync::atomic::AtomicBool;

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

    // SPOTIFYGOOD: see wait_for_data and seek_without_waiting
    /// A stream's loader and file: the file is there up to `downloaded`, the rest comes after a
    /// number of looks of a wait (or never)
    struct FakeSource {
        downloaded: std::cell::Cell<usize>,
        /// a wait's data comes (all of the file) at this look; `None`: never, its requests fail
        /// at once
        comes_after: Option<usize>,
        gone: bool,
        /// a request delivers this much from its start at once (0: nothing)
        chunk: std::cell::Cell<usize>,
        looks: std::cell::Cell<usize>,
        requests: std::cell::Cell<usize>,
        fail_fast: std::cell::Cell<bool>,
        missed: std::cell::Cell<Option<usize>>,
    }

    impl FakeSource {
        fn new(downloaded: usize, comes_after: Option<usize>) -> Self {
            Self {
                downloaded: downloaded.into(),
                comes_after,
                gone: false,
                chunk: Default::default(),
                looks: Default::default(),
                requests: Default::default(),
                fail_fast: Default::default(),
                missed: Default::default(),
            }
        }

        /// A read of the decoder at `at`: as librespot-audio's (fail-fast: it misses at once);
        /// one that would wait for its data fails the test
        fn read(&self, at: usize) -> Result<(), DecoderError> {
            if at < self.downloaded.get() {
                return Ok(());
            }
            assert!(
                self.fail_fast.get(),
                "a read at {at} waited for its data on the player thread"
            );
            if self.missed.get().is_none() {
                self.missed.set(Some(at));
            }
            Err(DecoderError::Stalled("not there".into()))
        }
    }

    impl DataSource for FakeSource {
        fn read_position(&self) -> Option<usize> {
            Some(0)
        }

        fn request(&self, start: usize, _length: usize) {
            self.requests.set(self.requests.get() + 1);
            if self.chunk.get() > 0 {
                let downloaded = self
                    .downloaded
                    .get()
                    .max(start.saturating_add(self.chunk.get()));
                self.downloaded.set(downloaded);
            }
        }

        fn available(&self, start: usize, length: usize) -> bool {
            if start + length <= self.downloaded.get() {
                return true;
            }
            let looks = self.looks.get();
            self.looks.set(looks + 1);
            let comes = self.comes_after.is_some_and(|after| looks >= after);
            if comes {
                self.downloaded.set(usize::MAX);
            }
            comes
        }

        fn gone(&self) -> bool {
            self.gone
        }

        fn fail_fast(&self, on: bool) {
            if on {
                self.missed.set(None);
            }
            self.fail_fast.set(on);
        }

        fn missed(&self) -> Option<usize> {
            self.missed.get()
        }
    }

    /// Seeks like symphonia's Ogg seek: it bisects the file (a probe that fails narrows it, as
    /// if past the end), then reads at the target's byte
    struct BisectingDecoder<'a> {
        file: &'a FakeSource,
        len: usize,
        bytes_per_ms: usize,
    }

    impl AudioDecoder for BisectingDecoder<'_> {
        fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
            let target = position_ms as usize * self.bytes_per_ms;
            let (mut start, mut end) = (0, self.len);
            while end - start > 2 * 65_536 {
                let mid = (start + end) / 2;
                match self.file.read(mid) {
                    Ok(()) if mid <= target => start = mid,
                    _ => end = mid,
                }
            }
            self.file.read(start)?;
            self.file.read(target)?;
            Ok(position_ms)
        }

        fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
            Ok(None)
        }
    }

    #[test]
    fn a_wait_for_the_data_is_bounded() {
        let never = || false;
        // its requests fail at once, the data never comes: one request, back at the deadline
        let failing = FakeSource::new(0, None);
        let started = Instant::now();
        assert_eq!(
            wait_for_data(
                &failing,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(300),
                &mut None,
                never
            ),
            Waited::TimedOut
        );
        let took = started.elapsed();
        assert!(took >= ms(300) && took < ms(1_000), "{took:?}");
        assert_eq!(failing.requests.get(), 1);

        // the data comes after a few looks
        let coming = FakeSource::new(0, Some(3));
        assert_eq!(
            wait_for_data(
                &coming,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(5_000),
                &mut None,
                never
            ),
            Waited::Came
        );
        assert_eq!(coming.requests.get(), 1);

        // it is there already: no request
        let there = FakeSource::new(usize::MAX, None);
        assert_eq!(
            wait_for_data(
                &there,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(5_000),
                &mut None,
                never
            ),
            Waited::Came
        );
        assert_eq!(there.requests.get(), 0);

        // the loader is gone: back at once (the player opens the file again)
        let mut gone = FakeSource::new(0, None);
        gone.gone = true;
        let started = Instant::now();
        assert_eq!(
            wait_for_data(
                &gone,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(5_000),
                &mut None,
                never
            ),
            Waited::Gone
        );
        assert!(started.elapsed() < ms(200));
    }

    // SPOTIFYGOOD: (a)
    #[test]
    fn a_command_interrupts_a_wait_which_then_goes_on_without_asking_again() {
        let failing = FakeSource::new(0, None);
        let mut requested = None;
        let started = Instant::now();
        // a command is queued: back after one tick (f), after the one request
        assert_eq!(
            wait_for_data(
                &failing,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(400),
                &mut requested,
                || true
            ),
            Waited::Interrupted
        );
        let took = started.elapsed();
        assert!(took >= DATA_POLL && took < ms(250), "{took:?}");
        assert_eq!(failing.requests.get(), 1);
        // the wait after it (the command was handled) asks nothing and ends at the deadline of
        // that request
        assert_eq!(
            wait_for_data(
                &failing,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(400),
                &mut requested,
                || false
            ),
            Waited::TimedOut
        );
        let took = started.elapsed();
        assert!(took >= ms(400) && took < ms(800), "{took:?}");
        assert_eq!(failing.requests.get(), 1);
        // the next attempt asks again
        assert_eq!(
            wait_for_data(
                &failing,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(100),
                &mut requested,
                || false
            ),
            Waited::TimedOut
        );
        assert_eq!(failing.requests.get(), 2);
    }

    // SPOTIFYGOOD: (f)
    #[test]
    fn a_command_the_loop_cant_take_doesnt_make_a_wait_spin() {
        // a command it sees but nobody takes (the loop ran out of tokio's budget): one wake-up
        // per tick, and the attempt still times out at its deadline (it returned at once, for good)
        let failing = FakeSource::new(0, None);
        let mut requested = None;
        let started = Instant::now();
        let mut calls = 0;
        let waited = loop {
            calls += 1;
            match wait_for_data(
                &failing,
                4_096,
                65_536,
                STALL_WAIT_BYTES,
                ms(500),
                &mut requested,
                || true,
            ) {
                Waited::Interrupted if calls < 1_000 => continue,
                waited => break waited,
            }
        };
        assert_eq!(waited, Waited::TimedOut);
        assert!(calls <= 7, "{calls} wake-ups");
        let took = started.elapsed();
        assert!(took >= ms(500) && took < ms(900), "{took:?}");
        assert_eq!(failing.requests.get(), 1);
    }

    // SPOTIFYGOOD: (b)
    #[test]
    fn a_seek_never_waits_for_data() {
        // a 1 MB file at 40 kB/s, its first 100 kB downloaded
        let file = FakeSource::new(100_000, None);
        let mut decoder = BisectingDecoder {
            file: &file,
            len: 1_000_000,
            bytes_per_ms: 40,
        };
        // past the download: the first byte the bisection missed, at once, nothing requested
        let started = Instant::now();
        match seek_without_waiting(&mut decoder, &file, 20_000) {
            SeekOutcome::Missed(at) => assert_eq!(at, 500_000),
            other => panic!("{other:?}"),
        }
        assert!(started.elapsed() < ms(50));
        assert!(!file.fail_fast.get(), "reads wait again after it");
        assert_eq!(file.requests.get(), 0);
        // within the download: exact (the probes that missed only narrowed the bisection)
        assert!(matches!(
            seek_without_waiting(&mut decoder, &file, 1_000),
            SeekOutcome::Done(1_000)
        ));
        // a decoder error that isn't missing data
        struct Broken;
        impl AudioDecoder for Broken {
            fn seek(&mut self, _: u32) -> Result<u32, DecoderError> {
                Err(DecoderError::SymphoniaDecoder("bad page".into()))
            }
            fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
                Ok(None)
            }
        }
        assert!(matches!(
            seek_without_waiting(&mut Broken, &file, 1_000),
            SeekOutcome::Failed(_)
        ));
    }

    // SPOTIFYGOOD: (b), see seek_waiting (the loader's seek, on its own thread)
    #[test]
    fn the_loaders_seek_waits_once_for_each_piece_it_misses() {
        // the data comes: it seeks again until it is there
        let coming = FakeSource::new(100_000, Some(2));
        let mut decoder = BisectingDecoder {
            file: &coming,
            len: 1_000_000,
            bytes_per_ms: 40,
        };
        assert_eq!(
            seek_waiting(&mut decoder, &coming, 20_000, 40_000, ms(2_000), || false).expect("seek"),
            20_000
        );
        assert_eq!(coming.requests.get(), 1);

        // without data: one wait, then a stall (a network failure for the load)
        let never = FakeSource::new(100_000, None);
        let mut decoder = BisectingDecoder {
            file: &never,
            len: 1_000_000,
            bytes_per_ms: 40,
        };
        let started = Instant::now();
        let e = seek_waiting(&mut decoder, &never, 20_000, 40_000, ms(300), || false).unwrap_err();
        assert!(e.is_stall(), "{e}");
        assert!(started.elapsed() < ms(1_000));
        assert_eq!(never.requests.get(), 1);

        // a loader that is gone
        let mut gone = FakeSource::new(100_000, None);
        gone.gone = true;
        let mut decoder = BisectingDecoder {
            file: &gone,
            len: 1_000_000,
            bytes_per_ms: 40,
        };
        let e = seek_waiting(&mut decoder, &gone, 20_000, 40_000, ms(5_000), || false).unwrap_err();
        assert!(e.is_loader_gone(), "{e}");
    }

    /// Seeks like symphonia's MP3 seek: it reads the file from its start up to the target's
    /// byte (every frame header)
    struct LinearDecoder<'a> {
        file: &'a FakeSource,
        bytes_per_ms: usize,
    }

    impl AudioDecoder for LinearDecoder<'_> {
        fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
            let target = position_ms as usize * self.bytes_per_ms;
            for at in (0..=target).step_by(16_384) {
                self.file.read(at)?;
            }
            Ok(position_ms)
        }

        fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
            Ok(None)
        }
    }

    // SPOTIFYGOOD: (b), (e) see seek_waiting
    #[test]
    fn the_loaders_seek_goes_on_while_data_comes_and_only_then() {
        // an MP3 episode (20 kB/s) resumed at 40:00: its seek reads the 48 MB before the
        // target, one wait for each 64 kB that comes (a cap of 64 waits failed it after 4 MB)
        let far = FakeSource::new(0, None);
        far.chunk.set(65_536);
        let mut decoder = LinearDecoder {
            file: &far,
            bytes_per_ms: 20,
        };
        assert_eq!(
            seek_waiting(&mut decoder, &far, 2_400_000, 20_000, ms(300), || false).expect("seek"),
            2_400_000
        );
        assert!(far.requests.get() > 700, "{} waits", far.requests.get());

        // the data stops coming on the way: that wait times out, a stall
        let stops = FakeSource::new(0, None);
        stops.chunk.set(65_536);
        let mut decoder = LinearDecoder {
            file: &stops,
            bytes_per_ms: 20,
        };
        assert!(seek_waiting(&mut decoder, &stops, 30_000, 20_000, ms(300), || false).is_ok());
        stops.chunk.set(0);
        let started = Instant::now();
        let e = seek_waiting(&mut decoder, &stops, 60_000, 20_000, ms(300), || false).unwrap_err();
        assert!(e.is_stall(), "{e}");
        assert!(started.elapsed() < ms(1_000));

        // (e) data came, but the seek misses at the byte it waited for again: no progress, it
        // fails instead of waiting again and again
        struct Stuck<'a>(&'a FakeSource);
        impl AudioDecoder for Stuck<'_> {
            fn seek(&mut self, _: u32) -> Result<u32, DecoderError> {
                self.0.missed.set(Some(500_000));
                Err(DecoderError::Stalled("not there".into()))
            }
            fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
                Ok(None)
            }
        }
        let comes = FakeSource::new(0, Some(0));
        let started = Instant::now();
        let e = seek_waiting(
            &mut Stuck(&comes),
            &comes,
            20_000,
            40_000,
            ms(5_000),
            || false,
        )
        .unwrap_err();
        assert!(e.is_stall(), "{e}");
        assert!(started.elapsed() < ms(500));
    }

    // SPOTIFYGOOD: see PlayerTrackLoader::superseded
    #[test]
    fn a_load_the_player_dropped_stops_its_seek() {
        // its data doesn't come; another load supersedes it after 200 ms
        let never = FakeSource::new(100_000, None);
        let mut decoder = BisectingDecoder {
            file: &never,
            len: 1_000_000,
            bytes_per_ms: 40,
        };
        let superseded = Arc::new(AtomicBool::new(false));
        let later = superseded.clone();
        std::thread::spawn(move || {
            std::thread::sleep(ms(200));
            later.store(true, Ordering::SeqCst);
        });
        let started = Instant::now();
        let e = seek_waiting(&mut decoder, &never, 20_000, 40_000, ms(5_000), || {
            superseded.load(Ordering::SeqCst)
        })
        .unwrap_err();
        assert!(e.is_stall(), "{e}");
        let took = started.elapsed();
        assert!(took >= ms(200) && took < ms(600), "{took:?}");
        assert_eq!(never.requests.get(), 1);
    }

    /// A streamed MP3 for a real decoder: its bytes come in chunks of an odd size (the end of
    /// what is there falls inside a frame), the next one at the byte a request or a look of a
    /// wait asks for; a fail-fast read of a byte that isn't there misses (as librespot-audio's),
    /// a read that waits gets it
    #[derive(Clone)]
    struct ChunkedFile(Arc<ChunkedShared>);

    struct ChunkedShared {
        data: Vec<u8>,
        there: Mutex<Vec<bool>>,
        chunk: usize,
        fail_fast: AtomicBool,
        missed: Mutex<Option<usize>>,
    }

    impl ChunkedFile {
        fn new(data: Vec<u8>, there: usize, chunk: usize) -> Self {
            let mut map = vec![false; data.len()];
            map[..there.min(data.len())].fill(true);
            Self(Arc::new(ChunkedShared {
                data,
                there: Mutex::new(map),
                chunk,
                fail_fast: AtomicBool::new(false),
                missed: Mutex::new(None),
            }))
        }

        /// The next chunk at `at`: from the first byte there isn't at or after it
        fn bring(&self, at: usize) {
            let mut there = self.0.there.lock().unwrap();
            let len = there.len();
            let Some(start) = (at..len).find(|&i| !there[i]) else {
                return;
            };
            there[start..(start + self.0.chunk).min(len)].fill(true);
        }

        fn all_there(&self, start: usize, length: usize) -> bool {
            let there = self.0.there.lock().unwrap();
            let end = start.saturating_add(length).min(there.len());
            there[start.min(end)..end].iter().all(|b| *b)
        }

        fn decoder(&self) -> SymphoniaDecoder {
            let mut hint = Hint::new();
            hint.mime_type("audio/mpeg");
            let reader = ChunkedReader {
                file: self.clone(),
                pos: 0,
            };
            SymphoniaDecoder::new(reader, hint).expect("an MP3")
        }
    }

    struct ChunkedReader {
        file: ChunkedFile,
        pos: usize,
    }

    impl Read for ChunkedReader {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            let shared = &self.file.0;
            if self.pos >= shared.data.len() || buf.is_empty() {
                return Ok(0);
            }
            if !self.file.all_there(self.pos, 1) {
                if shared.fail_fast.load(Ordering::SeqCst) {
                    shared.missed.lock().unwrap().get_or_insert(self.pos);
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "not there"));
                }
                self.file.bring(self.pos);
            }
            let there = shared.there.lock().unwrap();
            let n = there[self.pos..]
                .iter()
                .take(buf.len())
                .take_while(|b| **b)
                .count();
            buf[..n].copy_from_slice(&shared.data[self.pos..self.pos + n]);
            self.pos += n;
            Ok(n)
        }
    }

    impl Seek for ChunkedReader {
        fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
            let len = self.file.0.data.len() as i64;
            let pos = match pos {
                SeekFrom::Start(pos) => pos as i64,
                SeekFrom::End(delta) => len + delta,
                SeekFrom::Current(delta) => self.pos as i64 + delta,
            };
            self.pos = pos.clamp(0, len) as usize;
            Ok(self.pos as u64)
        }
    }

    impl MediaSource for ChunkedReader {
        fn is_seekable(&self) -> bool {
            true
        }

        fn byte_len(&self) -> Option<u64> {
            Some(self.file.0.data.len() as u64)
        }
    }

    impl DataSource for ChunkedFile {
        fn read_position(&self) -> Option<usize> {
            Some(0)
        }

        fn request(&self, start: usize, _length: usize) {
            self.bring(start);
        }

        fn available(&self, start: usize, length: usize) -> bool {
            if !self.all_there(start, length) {
                self.bring(start);
            }
            self.all_there(start, length)
        }

        fn gone(&self) -> bool {
            false
        }

        fn fail_fast(&self, on: bool) {
            if on {
                *self.0.missed.lock().unwrap() = None;
            }
            self.0.fail_fast.store(on, Ordering::SeqCst);
        }

        fn missed(&self) -> Option<usize> {
            *self.0.missed.lock().unwrap()
        }
    }

    /// An MP3 of `frames` frames (MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo, 417 bytes:
    /// 1152 samples, about 26 ms each) that play silence, with pseudo-random bytes after their
    /// side information (which the decoder skips, but in which a resync can find false frame
    /// headers); the first frame is all zero (no Xing tag)
    fn noisy_mp3(frames: usize) -> Vec<u8> {
        let mut seed = 0x9e37_79b9_7f4a_7c15_u64;
        let mut file = Vec::with_capacity(frames * 417);
        for index in 0..frames {
            let mut frame = vec![0; 417];
            frame[..4].copy_from_slice(&[0xFF, 0xFB, 0x90, 0x00]);
            if index > 0 {
                for byte in &mut frame[36..] {
                    seed ^= seed << 13;
                    seed ^= seed >> 7;
                    seed ^= seed << 17;
                    *byte = (seed >> 32) as u8;
                }
            }
            file.extend_from_slice(&frame);
        }
        file
    }

    /// The position of the frame the decoder is at, as it says (its first packet) and as it is
    /// (counted back from the end of the file, `frames` frames long)
    fn said_and_true(decoder: &mut SymphoniaDecoder, frames: usize) -> (u32, u32) {
        let mut said = None;
        let mut left = 0;
        while let Some((position, _)) = decoder.next_packet().expect("a packet") {
            said.get_or_insert(position.position_ms);
            left += 1;
        }
        let at = (frames - left) as u64;
        (said.expect("packets"), (at * 1152 * 1000 / 44_100) as u32)
    }

    // SPOTIFYGOOD: see SymphoniaDecoder's seek (an MP3 seeks coarsely)
    #[test]
    fn a_far_mp3_seek_lands_on_its_frame_while_the_data_comes() {
        const FRAMES: usize = 3_000; // 78 s
        const FRAME_MS: u32 = 27;
        let file = noisy_mp3(FRAMES);
        let lands = |seeked: u32, (said, truth): (u32, u32), target: u32| {
            assert!(said.abs_diff(truth) <= FRAME_MS, "said {said}, at {truth}");
            assert!(
                seeked.abs_diff(truth) <= FRAME_MS,
                "seeked {seeked}, at {truth}"
            );
            assert!(
                truth <= target && target - truth <= FRAME_MS,
                "at {truth} for {target}"
            );
        };

        // the loader's seek (a resume, a transfer, a reopen): the open brought the first 64 kB,
        // the rest comes in chunks of 7919 bytes. It missed at the end of each chunk, in the
        // middle of a frame, lost that frame (seconds over a far seek), and could sync on a
        // frame body (DecodeError).
        for target in [61_000, 1_500, 45_123] {
            let source = ChunkedFile::new(file.clone(), 65_536, 7_919);
            let mut decoder = source.decoder();
            let seeked = seek_waiting(&mut decoder, &source, target, 16_000, ms(2_000), || false)
                .expect("no failure but a stall");
            lands(seeked, said_and_true(&mut decoder, FRAMES), target);
        }

        // the player's: a track that plays at 10 s skips to 70 s, past the download (a seek,
        // then the stall's waits and seeks again)
        let source = ChunkedFile::new(file.clone(), 65_536, 7_919);
        let mut decoder = source.decoder();
        seek_waiting(&mut decoder, &source, 10_000, 16_000, ms(2_000), || false).expect("seek");
        for _ in 0..20 {
            decoder.next_packet().expect("a packet");
        }
        let seeked = seek_waiting(&mut decoder, &source, 70_000, 16_000, ms(2_000), || false)
            .expect("no failure but a stall");
        lands(seeked, said_and_true(&mut decoder, FRAMES), 70_000);
        // and back to the start (exact, the first frame)
        let seeked =
            seek_waiting(&mut decoder, &source, 0, 16_000, ms(2_000), || false).expect("seek");
        assert_eq!(seeked, 0);
        assert_eq!(said_and_true(&mut decoder, FRAMES), (0, 0));
    }

    // SPOTIFYGOOD: see stall_action
    #[test]
    fn a_stall_keeps_the_track_for_a_while() {
        let now = Instant::now() + Duration::from_secs(3600);
        // the first timed-out read, and the ones after it while the data doesn't come
        assert_eq!(stall_action(true, None, now), StallAction::Retry);
        assert_eq!(
            stall_action(true, Some(now - Duration::from_secs(40)), now),
            StallAction::Retry
        );
        // then it pauses (at the position played)
        assert_eq!(
            stall_action(true, Some(now - STREAM_STALL_MAX), now),
            StallAction::Pause
        );
        // a broken track is skipped as before (a stall never is, also when the session is gone
        // meanwhile: the engine's restore takes it from where it was heard)
        assert_eq!(stall_action(false, None, now), StallAction::Skip);
    }

    // SPOTIFYGOOD: see StreamStall
    #[test]
    fn a_stall_lasts_until_a_packet_past_it() {
        let t0 = Instant::now() + Duration::from_secs(3600);
        // stalled at 61 s, its read stopped at byte 4096; the waits for the data that time out
        // keep its start, position and byte
        let stall = StreamStall::again(None, 7, 61_000, Some(4_096), t0);
        assert_eq!(
            (stall.since, stall.position_ms, stall.at),
            (Some(t0), 61_000, Some(4_096))
        );
        let later = StreamStall::again(Some(stall), 7, 61_000, Some(9_999), t0 + ms(8_000));
        assert_eq!(
            (later.since, later.position_ms, later.at),
            (Some(t0), 61_000, Some(4_096))
        );
        assert_eq!(
            stall_action(true, later.since, t0 + ms(8_000)),
            StallAction::Retry
        );
        // the data came and the decoder is at the position again (stalled_step): still the
        // stall until a packet past it (the 60 s still count from its start)
        let resumed = StreamStall { at: None, ..later };
        let reseeked = resumed.after_packet(Some(61_000)).expect("still stalled");
        assert_eq!(reseeked.since, Some(t0));
        // a read times out again before a new packet (at another byte): waits again
        let again = StreamStall::again(Some(reseeked), 7, 61_000, Some(8_192), t0 + ms(30_000));
        assert_eq!((again.since, again.at), (Some(t0), Some(8_192)));
        assert_eq!(
            stall_action(true, again.since, t0 + STREAM_STALL_MAX),
            StallAction::Pause
        );
        // a packet past it ends the stall, as does the end of the track
        assert!(reseeked.after_packet(Some(61_020)).is_none());
        assert!(reseeked.after_packet(None).is_none());
        // a seek's stall: since now while playing, none while paused
        assert!(StreamStall::new(7, 5_000, 1_234, true).since.is_some());
        let paused = StreamStall::new(7, 5_000, 1_234, false);
        assert_eq!((paused.since, paused.at), (None, Some(1_234)));
    }

    // SPOTIFYGOOD: (d)
    #[test]
    fn only_a_reopen_failure_that_can_pass_keeps_the_track() {
        use UnavailableReason::*;
        for reason in [NetworkError, KeyTemporarilyDenied, Other] {
            assert!(reopen_waits(reason), "{reason:?}");
        }
        for reason in [NotAvailable, KeyDenied, DecodeError, OfflineFileError] {
            assert!(!reopen_waits(reason), "{reason:?}");
        }
    }

    // SPOTIFYGOOD: a PlayerInternal without its thread: commands and polls are run by the test
    struct NullSink;

    impl Sink for NullSink {
        fn write(
            &mut self,
            _: AudioPacket,
            _: &mut Converter,
        ) -> crate::audio_backend::SinkResult<()> {
            Ok(())
        }
    }

    /// Every track is "downloaded" to a file that doesn't exist: a load fails at once
    /// (OfflineFileError), without the network
    struct NoFiles;

    impl crate::offline::OfflineSource for NoFiles {
        fn lookup(&self, uri: &SpotifyUri) -> Option<crate::offline::OfflineTrack> {
            Some(crate::offline::OfflineTrack {
                audio_item: test_item(uri.clone(), false),
                format: AudioFileFormat::OGG_VORBIS_160,
                file_id: FileId([0; 20]),
                path: "/nonexistent/spotifygood-test.ogg".into(),
                key: None,
            })
        }
    }

    /// A silent MP3 of `frames` frames (MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo: 26 ms
    /// each), in a file of its own
    fn silent_mp3(frames: usize) -> std::path::PathBuf {
        static FILES: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "spotifygood-test-{}-{}.mp3",
            std::process::id(),
            FILES.fetch_add(1, Ordering::SeqCst)
        ));
        // a frame's header, then its side information and main data, all zero: silence
        let mut frame = vec![0; 417];
        frame[..4].copy_from_slice(&[0xFF, 0xFB, 0x90, 0x00]);
        std::fs::write(&path, frame.repeat(frames)).expect("mp3");
        path
    }

    /// Every track is downloaded to the file `.0`, an MP3 that isn't encrypted
    struct OneFile(std::path::PathBuf);

    impl crate::offline::OfflineSource for OneFile {
        fn lookup(&self, uri: &SpotifyUri) -> Option<crate::offline::OfflineTrack> {
            Some(crate::offline::OfflineTrack {
                audio_item: test_item(uri.clone(), false),
                format: AudioFileFormat::MP3_160,
                file_id: FileId([0; 20]),
                path: self.0.clone(),
                key: None,
            })
        }
    }

    fn test_item(track_id: SpotifyUri, is_explicit: bool) -> AudioItem {
        AudioItem {
            uri: track_id.to_uri().unwrap_or_default(),
            track_id,
            files: Default::default(),
            name: "test".into(),
            covers: vec![],
            language: vec![],
            duration_ms: 3_600_000,
            is_explicit,
            availability: Ok(()),
            alternatives: None,
            unique_fields: UniqueFields::Local {
                artists: None,
                album: None,
                album_artists: None,
                number: None,
                disc_number: None,
                path: "/nonexistent".into(),
            },
        }
    }

    fn track(n: u8) -> SpotifyUri {
        SpotifyUri::Track {
            id: SpotifyId::from_raw(&[n; 16]).expect("id"),
        }
    }

    /// Delivers silent 20 ms packets, counting them
    /// What a test sees of its SilentDecoder
    #[derive(Clone, Default)]
    struct Probe {
        packets: Arc<AtomicUsize>,
        seeks: Arc<Mutex<Vec<u32>>>,
    }

    impl Probe {
        fn packets(&self) -> usize {
            self.packets.load(Ordering::SeqCst)
        }

        fn seeks(&self) -> Vec<u32> {
            self.seeks.lock().unwrap().clone()
        }
    }

    /// Delivers silent 20 ms packets up to `end_ms` (then the end of the track); counts them and
    /// records its seeks
    struct SilentDecoder {
        position_ms: u32,
        end_ms: u32,
        probe: Probe,
    }

    impl AudioDecoder for SilentDecoder {
        fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
            self.probe.seeks.lock().unwrap().push(position_ms);
            self.position_ms = position_ms;
            Ok(position_ms)
        }

        fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
            if self.position_ms >= self.end_ms {
                return Ok(None);
            }
            self.probe.packets.fetch_add(1, Ordering::SeqCst);
            self.position_ms += 20;
            Ok(Some((
                AudioPacketPosition {
                    position_ms: self.position_ms,
                    skipped: false,
                },
                AudioPacket::Samples(vec![0.; 1_764]),
            )))
        }
    }

    /// Its reads time out (a stream without data)
    struct StallingDecoder;

    impl AudioDecoder for StallingDecoder {
        fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
            Ok(position_ms)
        }

        fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
            Err(DecoderError::Stalled("no data in time".into()))
        }
    }

    fn loaded(
        track_id: &SpotifyUri,
        position_ms: u32,
        is_explicit: bool,
    ) -> (PlayerLoadedTrackData, Arc<AtomicUsize>) {
        let (data, probe) = loaded_with(
            track_id,
            position_ms,
            is_explicit,
            StreamLoaderController::from_local_file(1_000_000),
            u32::MAX,
        );
        (data, probe.packets)
    }

    /// A track at `position_ms` read through `controller`, whose decoder ends at `end_ms`
    fn loaded_with(
        track_id: &SpotifyUri,
        position_ms: u32,
        is_explicit: bool,
        controller: StreamLoaderController,
        end_ms: u32,
    ) -> (PlayerLoadedTrackData, Probe) {
        let probe = Probe::default();
        let data = PlayerLoadedTrackData {
            decoder: Box::new(SilentDecoder {
                position_ms,
                end_ms,
                probe: probe.clone(),
            }),
            normalisation_data: NormalisationData::default(),
            stream_loader_controller: controller,
            audio_item: test_item(track_id.clone(), is_explicit),
            bytes_per_second: 20_000,
            duration_ms: 3_600_000,
            stream_position_ms: position_ms,
            is_explicit,
        };
        (data, probe)
    }

    fn local() -> StreamLoaderController {
        StreamLoaderController::from_local_file(1_000_000)
    }

    /// One poll of the player thread's loop as `block_on` polls it: with tokio's cooperative
    /// budget (128 units)
    fn poll_budgeted(runtime: &tokio::runtime::Runtime, internal: &mut PlayerInternal) {
        runtime.block_on(std::future::poll_fn(|cx| {
            let _ = Pin::new(&mut *internal).poll(cx);
            Poll::Ready(())
        }));
    }

    /// Polls the player's loop as its thread's `block_on` does, with a real waker (a pass that
    /// returned Pending is polled again only once something it polled wakes it), until an event
    /// `until` takes comes; the events up to it. A loader the loop never polled wakes nothing:
    /// it never returns then (see `returns`).
    fn poll_until(
        runtime: &tokio::runtime::Runtime,
        h: &mut Harness,
        until: impl Fn(&PlayerEvent) -> bool,
    ) -> Vec<PlayerEvent> {
        let mut events = vec![];
        runtime.block_on(std::future::poll_fn(|cx| {
            let _ = Pin::new(&mut h.internal).poll(cx);
            while let Ok(event) = h.events.try_recv() {
                let done = until(&event);
                events.push(event);
                if done {
                    return Poll::Ready(());
                }
            }
            Poll::Pending
        }));
        events
    }

    /// The runtime a harness test runs in (its body enters it: Session::new and load_track take
    /// the current one)
    fn test_runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .expect("runtime")
    }

    struct Harness {
        internal: PlayerInternal,
        commands: mpsc::UnboundedSender<PlayerCommand>,
        events: mpsc::UnboundedReceiver<PlayerEvent>,
    }

    impl Harness {
        fn new() -> Self {
            Self::with_offline(Arc::new(NoFiles))
        }

        /// Its tracks are loaded from `offline`
        fn with_offline(offline: crate::offline::OfflineSourceRef) -> Self {
            let config = PlayerConfig {
                offline_source: Some(offline),
                ..Default::default()
            };
            let (commands, commands_rx) = mpsc::unbounded_channel();
            let (events_tx, events) = mpsc::unbounded_channel();
            let normalisation_knee_factor = 1.0 / (8.0 * config.normalisation_knee_db);
            let internal = PlayerInternal {
                session: Session::new(crate::core::SessionConfig::default(), None),
                config,
                commands: commands_rx,
                load_handles: Default::default(),
                state: PlayerState::Stopped,
                preload: PlayerPreload::None,
                sink: Box::new(NullSink),
                sink_status: SinkStatus::Closed,
                sink_event_callback: None,
                volume_getter: Box::new(crate::mixer::NoOpVolume),
                event_senders: vec![events_tx],
                converter: Converter::new(None),
                normalisation_peaks: [0.0; 2],
                normalisation_integrators: [0.0; 2],
                normalisation_channel: 0,
                normalisation_knee_factor,
                auto_normalise_as_album: false,
                player_id: usize::MAX,
                play_request_id_generator: SeqGenerator::new(100),
                last_progress_update: Instant::now(),
                local_file_lookup: Arc::new(create_local_file_lookup(&[])),
                playback_speed: 1.,
                stream_stall: None,
                decoded: Default::default(),
                buffered: Default::default(),
                reopen: None,
                stale_loader: None,
                fresh_loader: false,
            };
            Self {
                internal,
                commands,
                events,
            }
        }

        /// One poll of the player thread's loop
        fn poll(&mut self) {
            let mut cx = Context::from_waker(std::task::Waker::noop());
            let _ = Pin::new(&mut self.internal).poll(&mut cx);
        }

        fn events(&mut self) -> Vec<PlayerEvent> {
            let mut events = vec![];
            while let Ok(event) = self.events.try_recv() {
                events.push(event);
            }
            events
        }

        /// Plays (or pauses) `loaded` as track 7 of request 7
        fn start(&mut self, track_id: &SpotifyUri, loaded: PlayerLoadedTrackData, play: bool) {
            self.internal
                .start_playback(track_id.clone(), 7, loaded, play);
            self.events();
        }

        fn loading(&self) -> Option<(bool, u32)> {
            match self.internal.state {
                PlayerState::Loading {
                    start_playback,
                    position_ms,
                    ..
                } => Some((start_playback, position_ms)),
                _ => None,
            }
        }
    }

    // SPOTIFYGOOD: (a)
    #[test]
    fn every_queued_command_is_handled_before_the_decoder_reads() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let (data, packets) = loaded(&t, 61_000, false);
        h.start(&t, data, true);
        // volume steps queued before a pause (each read of a stalled stream waits up to 8 s)
        for volume in [1, 2, 3, 4] {
            h.commands
                .send(PlayerCommand::EmitVolumeChangedEvent(volume))
                .expect("send");
        }
        h.commands.send(PlayerCommand::Pause).expect("send");
        h.poll();
        // all of them came before a single read (one command per read: four reads first)
        assert_eq!(packets.load(Ordering::SeqCst), 0);
        let events = h.events();
        assert_eq!(
            events
                .iter()
                .filter(|e| matches!(e, PlayerEvent::VolumeChanged { .. }))
                .count(),
            4
        );
        assert!(matches!(
            events.last(),
            Some(PlayerEvent::Paused {
                position_ms: 61_000,
                ..
            })
        ));
    }

    // SPOTIFYGOOD: (c)
    #[test]
    fn a_playing_track_that_waits_for_data_shows_as_buffering_at_once() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let (data, _) = loaded(&t, 61_000, false);
        h.start(&t, data, true);
        // a seek (or a reuse) whose data isn't there
        h.internal.begin_stall(7, t.clone(), 75_000, 3_000_000);
        let events = h.events();
        assert!(
            matches!(
                events.as_slice(),
                [PlayerEvent::Stalled {
                    play_request_id: 7,
                    position_ms: 75_000,
                    ..
                }]
            ),
            "{events:?}"
        );
        let stall = h.internal.stream_stall.expect("a stall");
        assert!(stall.since.is_some(), "its STREAM_STALL_MAX counts");
        assert!(
            matches!(
                h.internal.state,
                PlayerState::Playing {
                    reported_nominal_start_time: None,
                    ..
                }
            ),
            "the first packet after it reports its position (Spirc plays on from there)"
        );
        // paused: nothing to show, its time doesn't count
        h.internal.handle_pause();
        h.events();
        h.internal.begin_stall(7, t, 80_000, 3_000_000);
        assert!(h.events().is_empty());
        assert!(h.internal.stream_stall.expect("a stall").since.is_none());
    }

    // SPOTIFYGOOD: (d)
    #[test]
    fn a_reopen_keeps_its_position_and_stays_paused_if_it_fails() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let (data, _) = loaded(&t, 2_470_000, false);
        // paused by a stall that lasted STREAM_STALL_MAX at 41:10, marked for a reopen
        h.start(&t, data, false);
        h.internal.reopen = Some(t.clone());

        // play: opened again at 41:10 (Spirc's Loading arm keeps that), never at 0
        h.internal.handle_play();
        let events = h.events();
        assert!(
            matches!(
                events.as_slice(),
                [
                    PlayerEvent::PlayRequestIdChanged { play_request_id: 7 },
                    PlayerEvent::Loading {
                        play_request_id: 7,
                        position_ms: 2_470_000,
                        ..
                    }
                ]
            ),
            "{events:?}"
        );
        assert_eq!(h.loading(), Some((true, 2_470_000)));
        assert_eq!(
            h.internal
                .decoded
                .lock()
                .unwrap()
                .as_ref()
                .map(|d| d.position_ms),
            Some(2_470_000)
        );

        // the load fails on the bad network: paused at 41:10, not skipped (Unavailable)
        let fail = |h: &mut Harness, reason| {
            if let PlayerState::Loading { ref mut loader, .. } = h.internal.state {
                *loader = Box::pin(future::ready(Err::<PlayerLoadedTrackData, _>(reason)));
            }
        };
        fail(&mut h, UnavailableReason::NetworkError);
        h.poll();
        let events = h.events();
        assert!(
            matches!(
                events.as_slice(),
                [PlayerEvent::Paused {
                    play_request_id: 7,
                    position_ms: 2_470_000,
                    ..
                }]
            ),
            "{events:?}"
        );
        assert_eq!(h.internal.reopen, Some(t.clone()));
        assert_eq!(h.loading(), Some((false, 2_470_000)), "paused");

        // a seek meanwhile loads it paused at the target; play opens it again, playing
        h.internal.handle_command_seek(2_455_000).expect("seek");
        assert_eq!(h.loading(), Some((false, 2_455_000)));
        fail(&mut h, UnavailableReason::KeyTemporarilyDenied);
        h.poll();
        h.events();
        h.internal.handle_play();
        assert_eq!(h.loading(), Some((true, 2_455_000)));
        assert!(h.events().iter().any(|e| matches!(
            e,
            PlayerEvent::Loading {
                position_ms: 2_455_000,
                ..
            }
        )));

        // Spotify's verdict skips it like any load
        fail(&mut h, UnavailableReason::NotAvailable);
        h.poll();
        assert!(matches!(
            h.events().as_slice(),
            [PlayerEvent::Unavailable {
                reason: UnavailableReason::NotAvailable,
                ..
            }]
        ));

        // it opens: the mark is gone (paused here: a test can't poll a playing player, its
        // loop decodes on)
        h.internal
            .handle_command_load(t.clone(), Some(7), false, 2_455_000)
            .expect("load");
        let (data, _) = loaded(&t, 2_455_000, false);
        if let PlayerState::Loading { ref mut loader, .. } = h.internal.state {
            *loader = Box::pin(future::ready(Ok::<_, UnavailableReason>(data)));
        }
        h.poll();
        assert!(matches!(
            h.internal.state,
            PlayerState::Paused {
                stream_position_ms: 2_455_000,
                ..
            }
        ));
        assert_eq!(h.internal.reopen, None);
    }

    // SPOTIFYGOOD: see filtered
    #[test]
    fn the_explicit_filter_stops_a_repeat_a_preload_and_a_late_load() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);

        // repeat-one: the same explicit track again after its end is reused, until the filter
        let reuse = |h: &mut Harness| {
            let (data, _) = loaded(&t, 0, true);
            h.internal.state = PlayerState::EndOfTrack {
                track_id: t.clone(),
                play_request_id: 7,
                loaded_track: data,
            };
            h.internal
                .handle_command_load(t.clone(), None, true, 0)
                .expect("load");
            h.events()
        };
        assert!(
            reuse(&mut h)
                .iter()
                .any(|e| matches!(e, PlayerEvent::Playing { .. }))
        );
        h.internal.session.set_filter_explicit_forced(true);
        let events = reuse(&mut h);
        assert!(
            events
                .iter()
                .any(|e| matches!(e, PlayerEvent::Loading { .. }))
                && !events
                    .iter()
                    .any(|e| matches!(e, PlayerEvent::Playing { .. })),
            "loaded anew (which the filter refuses): {events:?}"
        );
        // the same playing one (a transfer, the queue's repeat)
        let (data, _) = loaded(&t, 0, true);
        h.start(&t, data, true);
        h.internal
            .handle_command_load(t.clone(), None, true, 0)
            .expect("load");
        assert!(h.loading().is_some());

        // a load that ends after the flip
        let (data, _) = loaded(&t, 0, true);
        if let PlayerState::Loading { ref mut loader, .. } = h.internal.state {
            *loader = Box::pin(future::ready(Ok::<_, UnavailableReason>(data)));
        }
        h.events();
        h.poll();
        assert!(matches!(
            h.events().as_slice(),
            [PlayerEvent::Unavailable {
                reason: UnavailableReason::NotAvailable,
                ..
            }]
        ));
        assert!(h.loading().is_some(), "not played");

        // preloads at the flip: an explicit one and one that still loads are dropped, a clean
        // one stays
        h.internal.session.set_filter_explicit_forced(false);
        let (data, _) = loaded(&t, 0, false);
        h.start(&t, data, true);
        let flip = |h: &mut Harness, preload: PlayerPreload| {
            h.internal.preload = preload;
            h.internal
                .handle_command(PlayerCommand::EmitFilterExplicitContentChangedEvent(true))
                .expect("command");
            !matches!(h.internal.preload, PlayerPreload::None)
        };
        let ready = |explicit| PlayerPreload::Ready {
            track_id: track(2),
            loaded_track: Box::new(loaded(&track(2), 0, explicit).0),
        };
        assert!(!flip(&mut h, ready(true)));
        assert!(flip(&mut h, ready(false)));
        assert!(!flip(
            &mut h,
            PlayerPreload::Loading {
                track_id: track(2),
                loader: Box::pin(
                    future::pending::<Result<PlayerLoadedTrackData, UnavailableReason>>().fuse(),
                ),
            }
        ));
        // and a preload that ends after it (paused: a test can't poll a playing player)
        h.internal.session.set_filter_explicit_forced(true);
        h.internal.handle_pause();
        h.events();
        h.internal.preload = PlayerPreload::Loading {
            track_id: track(2),
            loader: Box::pin(future::ready(Ok::<_, UnavailableReason>(
                loaded(&track(2), 0, true).0,
            ))),
        };
        h.poll();
        assert!(matches!(h.internal.preload, PlayerPreload::None));
        assert!(h.events().iter().any(|e| matches!(
            e,
            PlayerEvent::Unavailable {
                reason: UnavailableReason::NotAvailable,
                ..
            }
        )));
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

    fn volume_changes(events: &[PlayerEvent]) -> usize {
        events
            .iter()
            .filter(|e| matches!(e, PlayerEvent::VolumeChanged { .. }))
            .count()
    }

    // SPOTIFYGOOD: (f), (a)
    #[test]
    fn a_command_is_never_lost_to_tokios_budget() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        // the track ends after 100 packets (it would decode on past the budget until then)
        let (data, probe) = loaded_with(&t, 61_000, false, local(), 63_000);
        h.start(&t, data, true);
        // a long stretch of playback: more volume steps than tokio's budget, then a pause
        for volume in 0..200 {
            h.commands
                .send(PlayerCommand::EmitVolumeChangedEvent(volume))
                .expect("send");
        }
        h.commands.send(PlayerCommand::Pause).expect("send");
        poll_budgeted(&runtime, &mut h.internal);
        // all of them, before a single read: `poll_recv` gave 128, then none while it played
        assert_eq!(probe.packets(), 0);
        let events = h.events();
        assert_eq!(volume_changes(&events), 200);
        assert!(matches!(
            events.last(),
            Some(PlayerEvent::Paused {
                position_ms: 61_000,
                ..
            })
        ));
    }

    // SPOTIFYGOOD: (f)
    #[test]
    fn a_stalled_track_takes_a_pause_behind_many_commands_without_spinning() {
        // on its own thread: the loop spun for good at full CPU (the stall's wait saw the
        // commands that `poll_recv` no longer gave)
        let (done, result) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let runtime = test_runtime();
            let _runtime = runtime.enter();
            let mut h = Harness::new();
            let t = track(1);
            // a stream whose data doesn't come
            let controller = StreamLoaderController::stalled_for_tests(1_000_000, 100_000);
            let (data, probe) = loaded_with(&t, 61_000, false, controller, u32::MAX);
            h.start(&t, data, true);
            h.internal.begin_stall(7, t, 61_000, 500_000);
            h.events();
            for volume in 0..200 {
                h.commands
                    .send(PlayerCommand::EmitVolumeChangedEvent(volume))
                    .expect("send");
            }
            h.commands.send(PlayerCommand::Pause).expect("send");
            poll_budgeted(&runtime, &mut h.internal);
            let events = h.events();
            let paused = matches!(
                events.last(),
                Some(PlayerEvent::Paused {
                    position_ms: 61_000,
                    ..
                })
            );
            let _ = done.send((probe.packets(), volume_changes(&events), paused));
        });
        let outcome = result
            .recv_timeout(Duration::from_secs(20))
            .expect("the player thread didn't take the pause (it spins)");
        assert_eq!(outcome, (0, 200, true));
    }

    /// Runs `test` on its own thread, failing if it doesn't end within 20 s (a loop that never
    /// returns)
    fn returns<T: Send + 'static>(test: impl FnOnce() -> T + Send + 'static) -> T {
        let (done, result) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let _ = done.send(test());
        });
        result
            .recv_timeout(Duration::from_secs(20))
            .expect("the player's loop didn't return")
    }

    // SPOTIFYGOOD: (b), see handle_command_load (displaced)
    #[test]
    fn a_reuse_of_a_decoder_a_stall_displaced_seeks_also_to_the_same_position() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        for play in [false, true] {
            let (data, probe) = loaded_with(&t, 2_400_000, false, local(), u32::MAX);
            h.start(&t, data, play);
            // a seek to 40:00 missed its data: the decoder is where its scan stopped (11:00),
            // the stall says so; then the restore (or a resume) loads the track at 40:00
            h.internal.stream_stall = Some(StreamStall::new(7, 2_400_000, 123_456, play));
            h.internal
                .handle_command_load(t.clone(), None, play, 2_400_000)
                .expect("load");
            assert_eq!(probe.seeks(), [2_400_000], "play: {play}");
            assert_eq!(probe.packets(), 0, "nothing decoded from 11:00 first");
            // a decoder that is where it says isn't seeked (as before)
            h.internal
                .handle_command_load(t.clone(), None, play, 2_400_000)
                .expect("load");
            assert_eq!(probe.seeks(), [2_400_000]);
            h.events();
        }
    }

    // SPOTIFYGOOD: (c), see handle_set_playback_speed
    #[test]
    fn a_speed_change_keeps_a_cleared_line_so_the_first_packet_reports() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let is_cleared = |h: &Harness| {
            matches!(
                h.internal.state,
                PlayerState::Playing {
                    reported_nominal_start_time: None,
                    ..
                }
            )
        };
        let corrected = |events: &[PlayerEvent], at: u32| {
            events.iter().any(|e| {
                matches!(e, PlayerEvent::PositionCorrection { position_ms, .. } if *position_ms == at)
            })
        };

        // a stall (Spirc shows it loading), then the app's podcast speed
        let (data, _) = loaded_with(&t, 75_000, false, local(), 75_100);
        h.start(&t, data, true);
        h.internal.begin_stall(7, t.clone(), 75_000, 123);
        h.internal.handle_set_playback_speed(1.5);
        assert!(is_cleared(&h));
        h.events();
        // its data is there (a local file): the first packet reports, Spirc plays on
        h.poll();
        let events = h.events();
        assert!(corrected(&events, 75_020), "{events:?}");

        // a seek, then a speed change before its first packet
        let (data, _) = loaded_with(&t, 0, false, local(), 30_100);
        h.start(&t, data, true);
        h.internal.handle_command_seek(30_000).expect("seek");
        h.internal.handle_set_playback_speed(2.);
        assert!(is_cleared(&h));
        h.events();
        h.poll();
        assert!(corrected(&h.events(), 30_020));

        // a line that is set is re-based at the new speed (as before)
        let (data, _) = loaded_with(&t, 10_000, false, local(), u32::MAX);
        h.start(&t, data, true);
        h.internal.handle_set_playback_speed(1.);
        assert!(!is_cleared(&h));
    }

    // SPOTIFYGOOD: (d), see handle_set_session
    #[test]
    fn a_new_session_reopens_a_track_that_isnt_all_there() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let partial = || StreamLoaderController::stalled_for_tests(1_000_000, 100_000);
        // the session the engine binds before it connects; it has logged in when it loads
        let new_session = || {
            let session = Session::new(crate::core::SessionConfig::default(), None);
            PlayerCommand::SetSession(session)
        };
        let log_in = |h: &mut Harness| h.internal.session.set_auth_data(b"credentials");

        // paused by the reconnect, its file streamed up to 30:00 over the old network
        let (data, _) = loaded_with(&t, 1_800_000, false, partial(), u32::MAX);
        h.start(&t, data, false);
        h.internal.preload = PlayerPreload::Ready {
            track_id: track(2),
            loaded_track: Box::new(loaded_with(&track(2), 0, false, partial(), u32::MAX).0),
        };
        h.internal.handle_command(new_session()).expect("session");
        assert_eq!(h.internal.stale_loader, Some(t.clone()));
        assert!(matches!(h.internal.preload, PlayerPreload::None));
        // the restore's load of it (the session logged in) opens it again at its position, a
        // loader of the new session
        log_in(&mut h);
        h.internal
            .handle_command_load(t.clone(), None, true, 1_800_000)
            .expect("load");
        assert_eq!(h.loading(), Some((true, 1_800_000)));
        assert_eq!(h.internal.stale_loader, None);
        assert_eq!(
            h.internal.reopen,
            Some(t.clone()),
            "a failure keeps P (reopen_waits)"
        );

        // a file that is all there (a download, a stream to its end) keeps its decoder
        let (data, _) = loaded_with(&t, 1_800_000, false, local(), u32::MAX);
        h.start(&t, data, false);
        h.internal.handle_command(new_session()).expect("session");
        assert_eq!(h.internal.stale_loader, None);

        // a playing one marked by it opens again at its first stall (it waited STREAM_STALL_MAX
        // on the old session's loader)
        let mut data = loaded_with(&t, 1_200_000, false, local(), u32::MAX).0;
        data.decoder = Box::new(StallingDecoder);
        h.start(&t, data, true);
        h.internal.stale_loader = Some(t.clone());
        log_in(&mut h);
        h.poll();
        assert_eq!(h.loading(), Some((true, 1_200_000)));
        let events = h.events();
        assert!(
            events.iter().any(|e| matches!(
                e,
                PlayerEvent::Loading {
                    position_ms: 1_200_000,
                    ..
                }
            )),
            "{events:?}"
        );
    }

    // SPOTIFYGOOD: (d), see handle_set_session
    #[test]
    fn a_track_streamed_to_its_end_resumes_from_its_buffer_after_a_new_session() {
        let runtime = test_runtime();
        let _runtime = runtime.enter();
        let mut h = Harness::new();
        let t = track(1);
        let offline_session =
            || PlayerCommand::SetSession(Session::new(crate::core::SessionConfig::default(), None));
        let resumes = |h: &mut Harness| {
            h.internal.handle_pause();
            h.events();
            h.internal.handle_play();
            let events = h.events();
            assert!(
                matches!(events.as_slice(), [PlayerEvent::Playing { .. }]),
                "resumed from its decoder, no load: {events:?}"
            );
            assert!(h.internal.state.is_playing());
            assert_eq!(h.internal.reopen, None);
        };

        // resumed at 35:00 of an episode: its tail is there, its head isn't yet (the loader
        // fetches it last); the engine hands it to the offline queue, then binds the offline
        // session; a pause, a play
        let tail =
            StreamLoaderController::partial_for_tests(1_000_000, 600_000..1_000_000, 600_000);
        let (data, probe) = loaded_with(&t, 2_100_000, false, tail, u32::MAX);
        h.start(&t, data, true);
        h.internal
            .handle_command(offline_session())
            .expect("session");
        assert_eq!(
            h.internal.stale_loader, None,
            "playback has all it still needs"
        );
        resumes(&mut h);
        // also after a reconnect (the session logged in)
        h.internal
            .handle_command(offline_session())
            .expect("session");
        h.internal.session.set_auth_data(b"credentials");
        resumes(&mut h);
        assert!(probe.seeks().is_empty());

        // its tail is missing: marked, but never opened again through a session that hasn't
        // logged in (the offline one can't load anything): it resumes from its buffer
        let head = StreamLoaderController::stalled_for_tests(1_000_000, 100_000);
        let (data, _) = loaded_with(&t, 60_000, false, head, u32::MAX);
        h.start(&t, data, true);
        h.internal
            .handle_command(offline_session())
            .expect("session");
        assert_eq!(h.internal.stale_loader, Some(t.clone()));
        resumes(&mut h);
        assert_eq!(
            h.internal.stale_loader,
            Some(t.clone()),
            "kept for a session that logs in"
        );
    }

    // SPOTIFYGOOD: (f), see the Skip arm of the packet loop
    #[test]
    fn a_track_whose_decoder_fails_ends_instead_of_failing_again_and_again() {
        struct Broken;
        impl AudioDecoder for Broken {
            fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
                Ok(position_ms)
            }
            fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
                Err(DecoderError::SymphoniaDecoder("bad packet".into()))
            }
        }
        // one poll returns (it stayed Playing and read the decoder again, at full CPU until a
        // load came)
        let (ended, ends) = returns(|| {
            let runtime = test_runtime();
            let _runtime = runtime.enter();
            let mut h = Harness::new();
            let t = track(1);
            let mut data = loaded(&t, 5_000, false).0;
            data.decoder = Box::new(Broken);
            h.start(&t, data, true);
            h.poll();
            let ended = matches!(h.internal.state, PlayerState::EndOfTrack { .. });
            let ends = h
                .events()
                .iter()
                .filter(|e| matches!(e, PlayerEvent::EndOfTrack { .. }))
                .count();
            (ended, ends)
        });
        assert!(ended);
        assert_eq!(ends, 1);
    }

    // SPOTIFYGOOD: (d), see PlayerInternal::fresh_loader
    #[test]
    fn a_reopen_the_packet_loop_starts_plays() {
        struct Gone;
        impl AudioDecoder for Gone {
            fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError> {
                Ok(position_ms)
            }
            fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>> {
                Err(DecoderError::LoaderGone("its CDN URL expired".into()))
            }
        }
        let path = silent_mp3(200);
        let file = path.clone();
        // the loader the reopen made was never polled: Loading for good (endless buffering)
        let runs = returns(move || {
            let runtime = test_runtime();
            let _runtime = runtime.enter();
            let mut h = Harness::with_offline(Arc::new(OneFile(file)));
            let t = track(1);
            let mut runs = vec![];
            // its loader is gone; then a track a new session marked stalls on the old
            // session's loader (see handle_set_session)
            for marked in [false, true] {
                let mut data = loaded(&t, 1_000, false).0;
                if marked {
                    data.decoder = Box::new(StallingDecoder);
                    h.internal.stale_loader = Some(t.clone());
                    h.internal.session.set_auth_data(b"credentials");
                } else {
                    data.decoder = Box::new(Gone);
                }
                h.start(&t, data, true);
                runs.push(poll_until(&runtime, &mut h, |e| {
                    matches!(
                        e,
                        PlayerEvent::Playing { .. } | PlayerEvent::Unavailable { .. }
                    )
                }));
            }
            runs
        });
        let _ = std::fs::remove_file(path);
        for events in runs {
            assert!(
                events.iter().any(|e| matches!(
                    e,
                    PlayerEvent::Loading {
                        play_request_id: 7,
                        position_ms: 1_000,
                        ..
                    }
                )),
                "{events:?}"
            );
            assert!(
                matches!(
                    events.last(),
                    Some(PlayerEvent::Playing {
                        play_request_id: 7,
                        position_ms: 500..=1_000,
                        ..
                    })
                ),
                "{events:?}"
            );
        }
    }

    // SPOTIFYGOOD: see stall_action
    #[test]
    fn a_stall_outlives_its_session_for_the_restore() {
        let events = returns(|| {
            let runtime = test_runtime();
            let _runtime = runtime.enter();
            let mut h = Harness::new();
            let t = track(1);
            // its data stops where it reads (at 20:00); the AP's keep-alive times out during
            // the stall: the session is invalid
            let controller =
                StreamLoaderController::partial_for_tests(1_000_000, 0..100_000, 100_000);
            let mut data = loaded_with(&t, 1_200_000, false, controller, u32::MAX).0;
            data.decoder = Box::new(StallingDecoder);
            h.start(&t, data, true);
            h.internal.session.shutdown();
            // the reconnect's pause (prepare_reconnect freezes the restore at the stall) a
            // moment later
            let commands = h.commands.clone();
            std::thread::spawn(move || {
                std::thread::sleep(ms(300));
                let _ = commands.send(PlayerCommand::Pause);
            });
            h.poll();
            h.events()
        });
        // it was skipped: Spirc loaded the next track, which the restore then froze at 0
        assert!(
            !events
                .iter()
                .any(|e| matches!(e, PlayerEvent::EndOfTrack { .. })),
            "{events:?}"
        );
        assert!(
            matches!(
                events.as_slice(),
                [
                    PlayerEvent::Stalled {
                        position_ms: 1_200_000,
                        ..
                    },
                    PlayerEvent::Paused {
                        position_ms: 1_200_000,
                        ..
                    }
                ]
            ),
            "{events:?}"
        );
    }
}
