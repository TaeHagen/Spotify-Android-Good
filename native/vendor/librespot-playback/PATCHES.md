# librespot-playback 0.8.0 — local patches

This is the crates.io package `librespot-playback 0.8.0`, unchanged except for the hunks
below. `Cargo.lock`, `Cargo.toml.orig` and `.cargo_vcs_info.json` were removed, and `LICENSE`
(MIT) was copied from the librespot 0.8.0 repository root. It is wired in through
`[patch.crates-io]` in `native/Cargo.toml`. Every local change is marked `// SPOTIFYGOOD:`;
`grep -rn SPOTIFYGOOD src/` lists them all, so the patch can be re-applied when upgrading.

**Requires the vendored `librespot-core`.** The patch uses
`librespot_core::audio_key::is_permanent_denial`, which stock core 0.8.0 does not have.

**Requires the vendored `librespot-audio`.** The bounded waits for a stream's data use
`StreamLoaderController::{read_position, fetch_range, range_available_at}`, which stock audio
0.8.0 does not have (its `Range` isn't public).

## Why

1. **Offline playback of downloaded (still encrypted) files.** The stock loader always fetches
   metadata and the audio key over the network, so a downloaded track can't be played offline.
   Worse, if the key request fails for a file in librespot's `Cache`, the stock loader *deletes*
   that file. The `OfflineSource` hook plays a downloaded file through the stock state machine
   (gapless, preload, seek, normalisation, events, Spirc) without touching the network.
2. **No `std::process::exit`.** Stock code exits the process if `Sink::stop()` fails, if
   `Sink::start()` fails while a track starts, and on internal state-invariant violations. On
   Android that kills the app.
3. **Audio-key refusals (librespot #1649 / PR #1763).** Transient key failures are retried. A
   permanent denial aborts the load, and the reason reaches the app.
4. **Runtime settings.** Downloads, bitrate, normalisation and gapless can change without
   recreating the Player (and with it the Sink / AudioTrack and the Spirc binding).
5. **Resources.** Named threads, a 1-worker player runtime instead of one worker per CPU core,
   and a fix for a leaked loader-thread handle.
6. **Position corrections at another playback speed.** The app's sink plays podcasts at 0.5x
   to 3.5x. Stock measures a `PositionCorrection` against a 1x line (`now - position`), so above
   1x the stream is always ahead of it: a stall (a blocking read of a streamed file) was never
   reported, and Connect, the app's seek bar, skips, resume points and transfers ran ahead of
   the audio by speed x stall. After a fast part a slower speed kept the corrections away for
   long, and below 1x one came every second or two. A seek's wait for its data wasn't reported
   at any speed.
7. **Stalls of streamed files.** A read that waits longer than librespot-audio's
   `download_timeout` (8 s) for its data fails with `TimedOut`, and stock took that as a broken
   track: it sent `EndOfTrack`, so a tunnel or a cell handover a few seconds past the buffer
   ended the episode and Spirc went on with the next one.

## Public API added

```rust
// src/offline.rs (new module, `pub mod offline` in lib.rs)
pub struct OfflineTrack {
    pub audio_item: AudioItem,          // emitted in TrackChanged; availability Err => Unavailable(NotAvailable)
    pub format: AudioFileFormat,        // format of the downloaded file
    pub file_id: FileId,                // CDN file id (logging only)
    pub path: PathBuf,                  // file exactly as downloaded (encrypted)
    pub key: Option<AudioKey>,          // stored key; None only for unencrypted files
}                                       // Clone; Debug redacts the key
pub trait OfflineSource: Send + Sync {
    fn lookup(&self, uri: &SpotifyUri) -> Option<OfflineTrack>;
}
pub type OfflineSourceRef = Arc<dyn OfflineSource>;

// src/config.rs
pub struct PlayerConfig { /* ..., */ pub offline_source: Option<OfflineSourceRef> }   // default None
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NormalisationSettings {
    pub normalisation: bool,
    pub normalisation_type: NormalisationType,
    pub normalisation_method: NormalisationMethod,
    pub normalisation_pregain_db: f64,
    pub normalisation_threshold_dbfs: f64,
    pub normalisation_attack_cf: f64,
    pub normalisation_release_cf: f64,
    pub normalisation_knee_db: f64,
}                                       // Default == PlayerConfig::default() values
impl PlayerConfig {
    pub fn normalisation_settings(&self) -> NormalisationSettings;
    pub fn set_normalisation_settings(&mut self, settings: NormalisationSettings);
}

// src/player.rs
impl Player {
    pub fn set_offline_source(&self, source: Option<OfflineSourceRef>);
    pub fn set_bitrate(&self, bitrate: Bitrate);
    pub fn set_normalisation(&self, settings: NormalisationSettings);
    pub fn set_gapless(&self, gapless: bool);
    pub fn set_playback_speed(&self, speed: f64);   // the sink's speed; not finite or <= 0 => 1
    pub fn last_decoded(&self) -> Option<DecodedPosition>;  // without a command, see below
    pub fn fully_buffered(&self) -> Option<SpotifyUri>;     // the track if its file is all there
}
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedPosition {
    pub track_id: SpotifyUri,
    pub position_ms: u32,               // the last packet decoded (or the position of a load / seek)
    pub at: Instant,                    // when
}
// src/decoder/mod.rs
pub enum DecoderError {
    // ...
    Stalled(String),                    // a read timed out waiting for the data of a streamed file
    LoaderGone(String),                 // a read found the file's loader gone (it can't get data)
}
impl DecoderError {
    pub fn is_stall(&self) -> bool;
}

pub enum PlayerEvent {
    // ...
    Unavailable { play_request_id: u64, track_id: SpotifyUri, reason: UnavailableReason }, // `reason` added
    // ...
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnavailableReason {
    NotAvailable,          // Spotify says not playable (restriction, embargo, explicit filter,
                           // no alternative, no supported format, offline availability Err)
    NetworkError,          // metadata / CDN / token fetch failed (includes "never logged in")
    KeyDenied,             // audio key permanently refused (AesKeyError 0x0001): don't retry
    KeyTemporarilyDenied,  // key failed transiently after retries (throttled, timeout, AP down)
    DecodeError,           // opened but undecodable (corrupt, wrong key, rate/channels), or seek failed
    OfflineFileError,      // OfflineSource returned a track but its file could not be read
    Other,                 // unsupported URI, local file missing, loader thread died
}
```

Existing consumers that match `PlayerEvent::Unavailable { track_id, .. }` keep compiling. That
includes the vendored `librespot-connect` Spirc (`spirc.rs`, the `Unavailable { track_id, .. }`
arm), which needed no change.

## Hunks

| File / item | Change |
|---|---|
| `src/offline.rs` | New module (above). |
| `src/lib.rs` | `pub mod offline;` |
| `src/config.rs` | `PlayerConfig::offline_source` (+ default `None`); `NormalisationSettings` + helpers. |
| `player.rs` imports | `process::exit` removed; `FutureExt` instead of `TryFutureExt`; new imports. |
| `player.rs` consts | `AUDIO_KEY_RETRIES = 3`, `AUDIO_KEY_RETRY_DELAY = 1 s`, `PLAYER_RUNTIME_WORKER_THREADS = 1`, `LOADER_JOIN_TIMEOUT = 1 s`, `LOADER_JOIN_POLL = 10 ms`, `PLAYER_RUNTIME_SHUTDOWN_TIMEOUT = 250 ms`, `AUDIO_KEY_COOLDOWN = 30 s`. |
| `UnavailableReason`, `KeyFailure`, `classify_audio_key_error` | New. Classification: `is_permanent_denial` → abort; session invalid / `SessionError::NotConnected` → no retry; everything else → retry. |
| `PlayerCommand` | `SetOfflineSource`, `SetBitrate`, `SetNormalisation`, `SetGapless` (+ `Debug` arms). |
| `PlayerEvent::Unavailable` | `reason` field. |
| `Player::new` | `thread::Builder` named `lrs-player`; runtime `new_multi_thread().worker_threads(1).thread_name("lrs-player-rt")`, ended with `shutdown_timeout(PLAYER_RUNTIME_SHUTDOWN_TIMEOUT)` instead of a plain drop (which waits for every blocking task, e.g. a hanging getaddrinfo). |
| `impl Drop for PlayerInternal` | Joins the loader threads for at most `LOADER_JOIN_TIMEOUT` in total, then detaches the rest (stock joined every one without a limit; loaders have no network timeout, so a stalled request blocked `Player::drop` for minutes). |
| `Player::set_*` | New methods. |
| `PlayerPreload::Loading`, `PlayerState::Loading` | loader output `Result<_, UnavailableReason>` instead of `Result<_, ()>`. |
| `PlayerTrackLoader::load_track` / `load_remote_track` / `load_local_track` | Return `Result<PlayerLoadedTrackData, UnavailableReason>`. Offline hook. Key retry. No cache deletion after a key failure. Local files: `duration.as_secs().max(1)` (stock divides by zero for files < 1 s). |
| `PlayerTrackLoader::request_audio_key` | New: request with retries. While the cool-down runs, a single attempt. |
| `KeyRetryBrake`, `AUDIO_KEY_BRAKE`, `audio_key_brake` | New: the process-wide key-retry cool-down. Retries that run out on a transient failure start `AUDIO_KEY_COOLDOWN`; a key ends it. |
| `mod spotifygood_tests` | New: unit tests for the cool-down (`cargo test -p librespot-playback --lib spotifygood`). |
| poll loop | `Unavailable` carries the reason (load and preload). The `else { exit(1) }` after a failed sink start is now a debug log; the player is already paused. |
| `PlayerState::{is_playing, decoder, playing_to_end_of_track, paused_to_playing, playing_to_paused}`, start-playback check, `handle_player_stop`, `handle_packet` | `exit(1)` → `panic!`. A panic only ends the player thread: `Player::is_invalid()` becomes true and the engine can create a new Player. |
| `ensure_sink_stopped` | `sink.stop()` error: log, mark the sink closed, call the sink callback (was `exit(1)`). |
| `normalisation_factor_for`, `handle_set_normalisation` | New. `start_playback` uses the helper; same behaviour. |
| `PlayerInternal::load_track` | Named thread `lrs-loader`. Sends the full `Result`. Holds `load_handles` while spawning and inserting (stock could leak a finished thread's handle until Player drop). A failed spawn is logged instead of panicking while the guard is held (that poisoned the mutex, and the unwind's `Drop` panicked again, aborting the process); the load ends as `Unavailable(Other)`. A dropped result sender maps to `Other`. |
| `PlayerCommand::SetPlaybackSpeed`, `Player::set_playback_speed`, `PlayerInternal::playback_speed`, `handle_set_playback_speed`, `nominal_start_time`, `lags_behind`, `valid_playback_speed` | The playback's line (`reported_nominal_start_time`) is in media time at the playback speed: `now - position / speed` at load-and-play, resume (`paused_to_playing(speed)`) and after a correction, and the playing track's line is re-based at its position when the speed changes. The packet loop reports a correction when the stream lags 1 s of media behind that line (`lags_behind`); being ahead (the sink's buffer) still isn't reported, and the skipped-packet check is unchanged. At 1x both are the stock computations. |
| `handle_command_seek` | After the wait for the data (`preload_data_before_playback`) the line is `None`, so the first packet reports its position (`PositionCorrection`) and starts the line there. Stock started the line after the wait, so the `Seeked` position (sent before it) stayed ahead of the audio by the wait. |
| `src/decoder/mod.rs`, `src/decoder/symphonia_decoder.rs` | `DecoderError::Stalled`, `is_stall`, `from_io`: an `io::ErrorKind::TimedOut` from symphonia (`next_packet`, and `seek` through `From<symphonia::Error>`) is a stall; every other error is the stock `SymphoniaDecoder(String)`. |
| `STREAM_STALL_MAX`, `StreamStall`, `StallAction`, `stall_action`, `PlayerInternal::stream_stall`, `wait_for_stalled_data`, `STALL_WAIT_BYTES`, packet loop, `handle_play`, `handle_pause` | A stalled read of the playing track keeps it Playing: the line is cleared (the first packet after the stall reports its position). Each attempt waits for the data at the read position without the decoder (`StreamLoaderController::fetch_next_and_wait`, 16 KiB, which asks again for a range whose request failed): one wait of about `download_timeout`, with the commands handled between attempts. Once the data is there the decoder is re-seeked to the position played (its reader may have stopped in the middle of a page). The re-seek went first before: symphonia's Ogg seek bisects the whole file, and each of its probes past the data waited `download_timeout` again, 30-40 s for an attempt with every command waiting. The stall ends with a packet past the position it stalled at (not the one the re-seek decodes again), so `STREAM_STALL_MAX` (60 s, as long as the engine keeps the session up for a device that streams from its buffer) counts from its first timed-out read; then it pauses at the position played (a `Paused` event). A pause stops the waiting; a resume waits anew. A session that is gone and every other decoder error send `EndOfTrack` as stock. |
| `DataSource`, `wait_for_data`, `DATA_POLL`, `wait_for_stalled_data`, `preload_data_before_playback` | One bounded wait for the data at the read position: the bytes are requested once, then it looks every 100 ms whether they are there, until `download_timeout` from its start; nothing extends it and nothing is requested again. It replaces `fetch_next_and_wait` in the stall's attempts and in the wait after a seek: `fetch_blocking` requested a range again after every failure of it and started its timeout anew with every wake-up, so with requests that fail at once (an expired CDN URL's 403, a 5xx, a refused connection) it never returned (the player thread handled no command), and it drained the per-domain rate limit, which ended the file's loader for good. |
| `PlayerInternal::reopen`, `DecoderError::LoaderGone`, `handle_play`, `handle_command_load` | A track that stalled for `STREAM_STALL_MAX`, or whose read found its loader gone (BrokenPipe: it pauses then), is opened again on its next play or load of it (`load_track` at the position played: a new CDN URL, a new loader) instead of reusing its decoder, which could never get data again. Any other load forgets it. |
| `PlayerEvent::Stalled` | Sent when a stall starts (also when a resumed one stalls again), with the position played: the vendored Spirc shows it as buffering there instead of extrapolating (see the connect crate's item V). |
| `handle_command_seek` (stalled) | A seek while the track stalls doesn't touch the decoder: the target becomes the stall's position (the decoder is seeked there once the data is back), `Seeked` is sent. The Ogg seek bisects the whole file, and without data each probe waited `download_timeout`: every command waited tens of seconds and the seek was dropped. A seek that fails puts `last_decoded` back to the position played (`Player::seek` set it to the target). |
| `SharedBuffered`, `Player::fully_buffered` | The playing or paused track once its file is all there (`range_to_end_available`: downloaded, or streamed to its end), cleared at a load or stop; read without a command. The engine hands such a streamed track to its offline queue when the session goes away. |
| `DecodedPosition`, `SharedDecoded`, `Player::last_decoded`, `set_decoded` | The last packet decoded (track, position, when), written by the packet loop and at a load, seek and pause (also when `Player::load` / `Player::seek` are called, before the player thread gets to them), cleared at a stop. The engine reads it without a command (the player thread may be blocked in a read) to cap a restore point that Connect's extrapolation put past a stall. |
| `handle_command_seek` (stall) | The line is cleared also when the wait for the data times out (the seek's error is returned after it), and the seek starts any stall afresh. |
| `mod spotifygood_tests` (stall) | `stall_action` (retry, pause after the bound, skip a broken track or without a session), a stall lasting across attempts until a packet past it (`StreamStall::again`, `after_packet`), the shared last packet, and the mapping of a timed-out read to `Stalled`. |
| `mod spotifygood_tests` (speed) | A simulated packet loop: a stall at 2x is corrected at the first packet once it is 1 s of media behind (stock: never), a speed change from 2x to 0.5x (and back) keeps corrections working, the 1x line is the stock one. |
| `lock_load_handles`, `LOAD_HANDLES_POISON_MSG` | Every `load_handles` lock (loader thread, `load_track`, `Drop`) ignores poisoning (`PoisonError::into_inner`) instead of `expect`, so no panic can become a double panic in `PlayerInternal::drop`. The constant is removed. |

## Behaviour notes for the engine

* **Offline lookup.** `lookup` is called for every `Track`/`Episode` load **and preload**, on a
  `lrs-loader` thread, inside `Handle::block_on` of the player runtime.
  * It must be synchronous and fast, and must not call `block_on` or create a runtime (panics
    inside a runtime context). Avoid JNI. An `Arc<RwLock<HashMap<SpotifyUri, ..>>>` read is
    ideal.
  * Return `None` if the file is not present, so the network path is used.
  * Once it returns `Some`, there is no network fallback. Open failure → `Unavailable(OfflineFileError)`.
    Decode failure → `Unavailable(DecodeError)`, and nothing is deleted.
* **The offline path makes no network or `Cache` call.** It never calls `AudioItem::get_file`,
  alternatives, `session.audio_key()`, `AudioFile::open`, CDN or `session.cache()`. The only
  session access is the local attribute read `session.filter_explicit_content()`. It works with
  a never-connected `Session`.
* **Policy is the engine's job for downloads.** Spotify's availability checks are skipped. Set
  `audio_item.availability = Err(..)` to block an item, and re-validate downloads while online.
* **Runtime setters.**
  * `set_offline_source`, `set_bitrate`: apply to loads and preloads started after the command
    is processed (commands run between audio packets). A ready preload and the current track
    keep what they were loaded with.
  * `set_bitrate` only affects streamed tracks. To switch the current track, `stop()` and
    `load(uri, playing, position)`.
  * `set_gapless` updates `config.gapless`, which `handle_command_load` reads: it applies from
    the next load (track change). Commands are processed in order, so a load sent after the
    command sees the new value.
* **`set_playback_speed`** tells the player the speed the sink plays at; the sink applies the
  speed itself (the engine's `AndroidSink`), the player only measures its position corrections
  against it. The engine sets it with every `player.setSpeed` and on every new Player
  (`player_host::set_playback_speed`). Corrections then come after a stall or a seek, not
  periodically; Spirc absorbs the ones on the line of the speed (see the vendored connect
  crate's item S).
* **Stalls.** A streamed track whose data doesn't come stays Playing (the sink starves) for up
  to `STREAM_STALL_MAX`, then pauses at the position played; the engine's restore covers a
  session that went away, its restore point capped at `last_decoded` (Connect goes on
  extrapolating through a stall). Each attempt is one request and one wait of at most
  `download_timeout` for the data at the read position (`wait_for_data`), without the decoder,
  also when the requests fail at once: without a network (or with an expired CDN URL) it makes
  one range request about every 8 s for a minute, a command waits for one attempt at most, and a
  paused track makes none. A seek meanwhile waits for the data the same way. After the minute
  (or with a loader that is gone) the next play opens the track again. A `Stalled` event tells
  Spirc to show it as buffering at the position played. Downloaded and cached files never time
  out.
* **`fully_buffered`** like `last_decoded`: a lock, never a command.
* **`last_decoded`** takes a lock the packet loop holds for a moment per packet; call it from
  any thread, it never waits for the player thread.
* **`set_normalisation`** applies from the next packet: the config and knee factor are updated,
  and the current track's gain is recomputed from its normalisation data. `normalisation_type:
  Auto` still follows `set_auto_normalise_as_album`.
* **Key retry timing.**
  * Up to 4 attempts (1 + 3 retries) with 1 s between them. Each attempt has core's 1.5 s
    timeout, so worst case ≈ 9 s before the load finishes.
  * A superseded load keeps retrying in its own thread; the result is discarded.
  * Cool-down: once the retries ran out on a transient failure, every key request of the
    process (all loaders and Players) makes a single attempt for `AUDIO_KEY_COOLDOWN` (30 s); a
    failed single attempt starts it again, and any key that arrives ends it. So a run of skips
    after throttling costs one request per track instead of four.
  * Permanent denial → `Unavailable(KeyDenied)` at once, with no "without decryption" attempt.
  * Transient exhaustion → the file is still tried without decryption (some files are not
    encrypted). If that fails → `Unavailable(KeyTemporarilyDenied)`. A cached file is never
    deleted after a key failure.
* **Bounded drop.** `Player::drop` returns within about `LOADER_JOIN_TIMEOUT` +
  `PLAYER_RUNTIME_SHUTDOWN_TIMEOUT` plus the current packet. Loaders still running then are
  detached; the runtime shutdown cancels their I/O, so they end soon after with their result
  discarded. The engine still bounds its own wait (`player_host::PLAYER_DROP_TIMEOUT`).
* **Process safety.** Sink `start()`/`stop()` errors no longer exit the process. A failed start
  produces `Playing` then `Paused`. Watch `Player::is_invalid()` for a dead player thread.
* **Threads.** `lrs-player` (decoding and all Sink calls), `lrs-player-rt` (1 tokio worker:
  loader I/O and timers, plus hyper connection tasks created from loader context), and
  short-lived `lrs-loader` threads (one per load/preload). The runtime must stay multi-thread:
  with a current-thread runtime nothing drives loader I/O while `lrs-player` blocks in
  `Sink::write`. Verified: a timer plus a TCP connect from the loader context completes in
  ~52 ms while the player thread is busy writing.

## Verification (2026-10-06)

* `cargo check -p librespot-playback` and `cargo clippy -p librespot-playback --no-deps` in
  `native/`: clean.
* A scratch crate built this crate + vendored core with **stock crates.io librespot-connect
  0.8.0** (`Spirc::new` signature compile-checked).
* An end-to-end harness played a Spotify-style encrypted file (0xa7-byte custom header,
  normalisation floats at offset 144, AES-128-CTR) through a never-connected Session. Confirmed:
  * playback, seek, preload, repeat-path reuse, `TimeToPreloadNextTrack`, `EndOfTrack`, exactly
    40.00 s of frames;
  * `set_offline_source` swap: None → `Unavailable(NetworkError)`, Some → plays, None again →
    `Unavailable(NetworkError)`;
  * `set_normalisation` mid-track: peak 0.0633 → 0.0317 at −6 dB pregain → 0.0894 with
    normalisation off;
  * reasons `OfflineFileError` (missing file) and `DecodeError` (wrong key; file kept);
  * sink `start`/`stop` errors → `Paused`, process exit code 0.
* **Not covered** (needs network and an account): the online key-retry/denial path and streaming.
