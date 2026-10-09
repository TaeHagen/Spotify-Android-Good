# librespot-playback 0.8.0 — local patches

This is the crates.io package `librespot-playback 0.8.0`, unchanged except for the hunks
below. `Cargo.lock`, `Cargo.toml.orig` and `.cargo_vcs_info.json` were removed, and `LICENSE`
(MIT) was copied from the librespot 0.8.0 repository root. It is wired in through
`[patch.crates-io]` in `native/Cargo.toml`. Every local change is marked `// SPOTIFYGOOD:`;
`grep -rn SPOTIFYGOOD src/` lists them all, so the patch can be re-applied when upgrading.

**Requires the vendored `librespot-core`.** The patch uses
`librespot_core::audio_key::is_permanent_denial`, which stock core 0.8.0 does not have.

**Requires the vendored `librespot-audio`.** The bounded waits for a stream's data use
`StreamLoaderController::{read_position, fetch_range, range_available_at, is_loader_gone}`, and
the seeks that don't wait for data `set_fail_fast` and `missed`, which stock audio 0.8.0 does not
have (its `Range` isn't public). Its reads wait until one deadline, and its loader doesn't loop
on errors (see "Stalls" below).

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
    pub fn is_loader_gone(&self) -> bool;
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
| `PlayerCommand::SetPlaybackSpeed`, `Player::set_playback_speed`, `PlayerInternal::playback_speed`, `handle_set_playback_speed`, `nominal_start_time`, `lags_behind`, `valid_playback_speed` | The playback's line (`reported_nominal_start_time`) is in media time at the playback speed: `now - position / speed` at load-and-play, resume (`paused_to_playing(speed)`) and after a correction, and the playing track's line is re-based at its position when the speed changes. The packet loop reports a correction when the stream lags 1 s of media behind that line (`lags_behind`); being ahead (the sink's buffer) still isn't reported, and the skipped-packet check is unchanged. At 1x both are the stock computations. A cleared line (a stall, a seek before its first packet) stays cleared: the first packet after it reports its position (round 18). |
| `handle_command_seek` | The line is `None` after a seek, so the first packet reports its position (`PositionCorrection`) and starts the line there. Stock started the line after its wait for the data, so the `Seeked` position (sent before it) stayed ahead of the audio by the wait. |
| `src/decoder/mod.rs`, `src/decoder/symphonia_decoder.rs` | `DecoderError::Stalled`, `is_stall`, `from_io`: an `io::ErrorKind::TimedOut` from symphonia (`next_packet`, and `seek` through `From<symphonia::Error>`) is a stall; every other error is the stock `SymphoniaDecoder(String)`. An MP3 seeks with symphonia's `SeekMode::Coarse` (`SymphoniaDecoder::coarse_seeks`; a seek to 0, or an MP3 whose length or duration isn't known, is accurate as before): it jumps to the byte the target's share of the file gives (exact for a constant bit rate, as Spotify's MP3 files are; approximate for a variable one), syncs strictly on two frame headers and parses a few frames, and starts over from that byte on every attempt. Its accurate seek read every frame header from where its reader was, and a seek that missed data (fail-fast) left the reader in the middle of a frame it never counted: each retry lost about a frame (a far load or skip landed seconds past its target, every position after it off by that), and a false sync on a frame body could fail the load with `DecodeError` (the episode skipped and marked unavailable) (round 20). |
| `STREAM_STALL_MAX`, `StreamStall`, `StallAction`, `stall_action`, `StallStep`, `PlayerInternal::{stream_stall, stalled_step, begin_stall, stalls_now, stall_resumed}`, packet loop, `handle_play`, `handle_pause` | A stream whose data doesn't come keeps its track (see "Stalls" below for the design): a read that times out, or a seek that misses data, makes it a stall at the position played (or the seek's target) waiting for the byte the decoder needs. The packet loop's `stalled_step` waits for it (one bounded wait, `wait_for_data`), then seeks the decoder back to the position without waiting (`seek_without_waiting`; a seek that misses more data waits for that next); a wait that times out is an attempt (`stall_action`): after `STREAM_STALL_MAX` (60 s, as long as the engine keeps the session up for a device that streams from its buffer) without a packet past the position it pauses there (a `Paused` event) and marks the track for a reopen. The stall ends with a packet past its position (not the one the re-seek decodes again). While it plays and waits, Spirc shows it buffering (`PlayerEvent::Stalled`, sent when it begins: at the first timed-out read, at once for a seek or reuse whose data isn't there and for a resume whose data still isn't), the line is cleared (the first packet after it reports its position) and `since` counts. A pause stops the waiting (`since` is cleared). Only a decoder error that isn't a stall sends `EndOfTrack` as stock: a stall also waits on when the session goes invalid meanwhile (round 19; it was skipped, Spirc loaded the next item, and the reconnect restored that one at 0 instead of the stalled one at its position). |
| `DataSource`, `Waited`, `wait_for_data`, `DATA_POLL`, `STALL_WAIT_BYTES`, `read_ahead_bytes`, `request_read_ahead` | The player's one wait for a stream's data (invariant (a)): the bytes are requested once (not again within `download_timeout` of the last request), then it looks every 100 ms whether they are there, until `download_timeout` after that request, the loader is gone, or a command is queued (`Interrupted`: the loop handles it, the wait goes on without asking again). It replaced `fetch_next_and_wait`/`fetch_blocking`, which requested a range again after every failure of it and started their timeout anew with every wake-up: with requests that fail at once (an expired CDN URL's 403, a 5xx, a refused connection) it never returned and drained the per-domain rate limit. `preload_data_before_playback` (the wait after every seek, on the player thread) is gone: a seek requests the data to play on (`request_read_ahead`) and the packet loop's reads wait for it, bounded. Right after its request a wait looks once more (the data may be on its way, round 19). |
| `SeekOutcome`, `seek_without_waiting`, `seek_waiting`, `start_loaded`, `load_remote_track` | Seeks never wait for data on the player thread (invariant (b)): the decoder seeks with librespot-audio's fail-fast reads (`StreamLoaderController::set_fail_fast`) and returns the first byte it missed. symphonia's Ogg seek bisects the whole file and each of its probes into data that isn't there waited `download_timeout`: a -15 s past the download, a scrub, a same-track load (a transfer, a resume) or the re-seek of a stall blocked the audio and every command for tens of seconds, and the failed probes were taken as the end of the stream. `handle_command_seek`, the stall's re-seek and the three reuses of a loaded track (`start_loaded`: the same track after its end, the playing or paused one, a ready preload) seek without waiting; a seek that misses data becomes a stall at its target. A reuse whose decoder fails to seek is loaded anew (stock returned the error after `PlayRequestIdChanged`, so Spirc loaded the new id while the state kept the old one, and the track never went on). The loader's seek to the start position (`seek_waiting`, on its own thread) waits for each piece it misses (one wait each, as long as data comes) and fails after one wait without data, or when a wait brought it no further (it misses at the byte it waited for). It has no count (round 19): its cap of 64 waits (`SEEK_WAITS_MAX`, removed) failed an MP3 stream loaded at a resume or transfer position a few minutes in (symphonia's MP3 seek reads every frame header from the start of the file, about 5 s of audio per wait), so the episode was skipped, or a reopen stayed paused for good. (Round 20: an MP3 seeks coarsely now, see the decoder row; the request of the bytes up to an MP3's target before the seek, which round 19 added for the linear scan, is gone: a coarse seek misses near its target, and the wait there requests the read-ahead.) A load the player dropped (another load or a stop superseded it: the future `PlayerInternal::load_track` returned is gone, `PlayerTrackLoader::superseded`) stops its seek, also in the middle of a wait, and drops its file, which ends its download; it went on to the end on its loader thread, downloading what the seek needed (round 20). A reuse of the playing or paused track seeks also to its own position when a stall that waits for data says its decoder is displaced (`displaced` in `handle_command_load`): the stall was cleared and the seek skipped, so a restore at the stall's target played on from where the failed seek had left the decoder (round 18). |
| `PlayerInternal::reopen`, `reopen_track`, `reopen_waits`, `DecoderError::LoaderGone`, `PlayerState::Loading::position_ms`, `handle_play`, `handle_command_load`, `start_playback` | A track that stalled for `STREAM_STALL_MAX` (it paused) is opened again on its next play, one whose loader is gone (an expired CDN URL ends it) at once, still playing: `load_track` at the position played (a new CDN URL, a new loader), never its decoder reused (it could never get data again). The mark stays until the track is open (`start_playback`), so a reopen whose load fails for a reason that can pass (`NetworkError`, `KeyTemporarilyDenied`, `Other`) sends `Paused` at its position (the state stays `Loading` with the loader ended and `start_playback` cleared; a seek loads it again paused, a play playing). It sent `Unavailable`, and Spirc skipped the episode. Spotify's verdicts (not available, key denied, not decodable) skip it like any load; a load of another track forgets it. The loader reports a read without data (a stall, a gone loader) while it opens or seeks as `NetworkError` (it was `DecodeError`). `handle_set_session` (`PlayerCommand::SetSession`), `PlayerInternal::stale_loader`, `session_logged_in`, `opens_again`: a new session marks a streamed track whose data playback still needs isn't there (`stale_loader`; a playing or paused track from its read position to its end, `range_to_end_available` as `fully_buffered`, an ended one the whole file, which a repeat plays) and drops a preload that isn't all there. The mark opens the track again on its next play or load, or at its first stall while it plays, once the session has logged in (round 18). Round 19: it went by the whole file and acted on any session, so a track resumed mid-file (its tail first, its head last) that the engine handed to its offline queue was marked by the offline session's `SetSession`, and its next play reopened it through that never-connected session: the buffered rest was thrown away and every play failed until the network came back. |
| `PlayerInternal::fresh_loader`, poll loop | A loader made in a pass after the loop polled its loaders is polled before the poll returns `Pending`, so that its waker is registered (`handle_command_load` and `handle_command_preload` set it, the pass that polls the loaders clears it). A reopen started inside the packet loop (a loader that is gone, a marked track's first stall) was never polled: the loop slept with it, and the player stayed `Loading` (endless buffering in Connect) until some command came (round 19). |
| `PlayerEvent::Stalled` | Sent when a stall begins while the track plays (see above), with the position played or the seek's target: the vendored Spirc shows it as buffering there instead of extrapolating (see the connect crate's item V). |
| `SharedBuffered`, `Player::fully_buffered` | The playing or paused track once its file is all there (`range_to_end_available`: downloaded, or streamed to its end), cleared at a load or stop; read without a command. The engine hands such a streamed track to its offline queue when the session goes away. |
| `DecodedPosition`, `SharedDecoded`, `Player::last_decoded`, `set_decoded` | The last packet decoded (track, position, when), written by the packet loop and at a load, seek and pause (also when `Player::load` / `Player::seek` are called, before the player thread gets to them), cleared at a stop. The engine reads it without a command (the player thread may be blocked in a read) to cap a restore point that Connect's extrapolation put past a stall. |
| `mod spotifygood_tests` (stall) | `stall_action` (retry, pause after the bound, skip a broken track), a stall lasting across attempts until a packet past it (`StreamStall::{new, again, after_packet}`), the shared last packet, the mapping of a timed-out read to `Stalled`; per invariant: (a) a wait is bounded, a command interrupts it and the wait after it doesn't ask again, and every queued command is handled before the decoder reads (a `PlayerInternal` harness without its thread: no read before a queued pause); (b) a seek never waits (a bisecting fake decoder over a fake stream: the first byte missed, at once, nothing requested) and the loader's seek waits once for each piece; (c) a playing track that waits for data shows as buffering at once (`Stalled`, line cleared, `since` counts; nothing while paused); (d) a reopen keeps its position and stays paused when it fails (and a seek or play then), Spotify's verdict skips it, and which reasons wait; the explicit filter (a repeat, the playing track, a late load, preloads at the flip and after it). Round 18: (f) every command and the pause under a real `block_on` budget (more than 128 queued while playing; also while stalled on a stream that never delivers, on a watchdog thread: it spun), a wait with a command it can't take wakes once per tick and times out, a failing decoder ends its track; (b) a reuse of a displaced decoder seeks, also to the same position (playing and paused); (c) a speed change keeps a cleared line (after a stall and after a seek), the first packet reports; (d) a new session reopens a track that isn't all there (and drops such a preload; a complete one is kept; a playing one reopens at its first stall). Each fails with the round-17 code. Round 19: (d) a reopen the packet loop starts (a loader that is gone, a marked track's first stall) reaches Playing under a real waker (`poll_until`, an offline MP3 file, on a watchdog thread: it stayed Loading); a track resumed mid-file and streamed to its end resumes from its buffer after a new session (never connected, and logged in), and a marked one isn't reopened through a session that hasn't logged in; a stall waits on when the session goes invalid (no `EndOfTrack`, the reconnect's pause keeps P); (b), (e) the loader's seek goes on past 64 waits while data comes (a linear, MP3-like seek over a source that delivers a chunk per request), times out when the data stops, and fails when a wait brings no progress. Each fails with the round-18 code. Round 20: (b) a far MP3 seek over a real decoder (`SymphoniaDecoder` over a 78 s MP3 with pseudo-random bytes in its frames, its data coming in odd-sized chunks, fail-fast at the end of what is there): the loader's seek at 61 s, 1.5 s and 45.1 s, and a skip from 10 s to 70 s past the download, land on the frame of the target, as the decoder says and as it is (its packets counted back from the end of the file), without an error but a stall, and a seek back to 0 is exact (with the accurate seek the loader's seek failed); (e) a load the player dropped stops its seek within a tick. |
| `mod spotifygood_tests` (speed) | A simulated packet loop: a stall at 2x is corrected at the first packet once it is 1 s of media behind (stock: never), a speed change from 2x to 0.5x (and back) keeps corrections working, the 1x line is the stock one. |
| `PlayerCommand::EmitFilterExplicitContentChangedEvent`, `filtered`, `preload_failed`, `handle_command_load`, poll loop | The explicit filter is checked again when it turns on and wherever a loaded track is used: an explicit ready preload, and one that still loads, are dropped at the flip; the three reuses of a loaded track (the same one again after its end, the playing or paused one, a ready preload) don't reuse an explicit one while the filter is on (it is loaded anew, which the filter refuses); a load or preload that ends with an explicit track while it is on is `Unavailable(NotAvailable)` (a preload is dropped like a failed one). Stock only ended the current explicit track (`EndOfTrack`): a repeat-one (Spirc's, the offline queue's) reused it on and on, and a preload made before the flip played in full. |
| `Player::new` (`coop::unconstrained`), poll loop (`try_recv`, `poll_recv` before `Pending`), `wait_for_data`, the packet loop's `Skip` | Invariant (f), see "Stalls": no command is held back by tokio's cooperative budget, a wait that sees a command wakes once per tick and still times out, and a track whose decoder fails ends (EndOfTrack state) instead of being read again on every pass. |
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
* **Stalls.** See the section below.
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

## Stalls

A streamed file whose data doesn't come (a tunnel, a cell handover, a dead Wi-Fi, an expired
CDN URL, a CDN outage). Rounds 14 to 16 fixed this case by case; round 17 wrote down what the
design must keep and made the code meet it as a whole; round 18 added (f); round 19's findings
are listed below.

### Invariants

* **(a)** The player thread never blocks on network data for more than one bounded wait, and
  handles every queued command before and between waits.
* **(b)** No decoder read, seek or same-track load on data that isn't downloaded runs on the
  player thread without a bound.
* **(c)** While no audio is produced, nothing extrapolates the position: not Spirc's state, its
  puts and their retries, the snapshots, the engine's restore caps, nor the `playback_speed`
  other clients see.
* **(d)** A reopen never loses the position or the item. It keeps P, and stays paused at P if it
  fails.
* **(e)** The loader never loops on errors.
* **(f)** No busy loop: while the player thread waits it wakes at most once per `DATA_POLL`
  tick, and a command it can see it can also take.

### How each is guaranteed

* **(a)** The player thread waits for network data in two places only, each bounded by
  `download_timeout` (8 s). A decoder read waits until one deadline taken at its start (the
  vendored librespot-audio's `AudioFileStreaming::read`; every notify, also of a range that failed,
  restarted it). A stalled track's wait (`wait_for_data` in `stalled_step`) returns within a tick
  when a command is queued, and the wait after it continues without asking again. Every pass of
  the poll loop first handles every queued command (it took one per pass, so the k-th command
  waited k attempts), with `try_recv`: tokio's cooperative budget can't hold any back (see (f)).
  Nothing else waits: a seek doesn't (b), and the wait after a seek
  (`preload_data_before_playback`) is gone. A request on a network that drops its packets ends
  after `download_timeout` without news (librespot-audio): it held the file's one download slot
  for good.
* **(b)** Every decoder seek on the player thread runs with fail-fast reads
  (`seek_without_waiting`). A read of data that isn't there fails at once and requests nothing,
  and the seek gives back the first byte it missed. Then the track is a stall at the target, and
  the packet loop waits for that byte (a), then seeks again. That covers `handle_command_seek` (also
  while stalled), the stall's re-seek, and the three reuses of a loaded track in
  `handle_command_load` (`start_loaded`). A reuse whose decoder can't seek is loaded anew. A
  reuse also seeks when a stall that waits for data says the decoder isn't at the track's position
  (a missed seek left it where its scan stopped), also to that same position: a restore or a
  resume loads exactly the position shown while it stalled, and played on from where the decoder
  was. The loader's seek to the start position runs on its own thread, one bounded wait per piece
  it misses, for as long as each wait gets it further (no count), and only while the player still
  wants the load. An MP3 seeks coarsely, so every attempt after a miss starts over at its byte
  (an accurate MP3 seek continued from the middle of the frame where the miss left it). Decoder
  reads are bounded by (a).
* **(c)** The player tells Spirc when a playing track stops producing audio (`Stalled` at the
  position heard or the seek's target). That happens at the first wait that timed out, and at once
  for a seek, a reuse or a resume whose data isn't there. A reopen tells it with `Loading` at its
  position. Spirc then shows LoadingPlay at that position, and nothing moves it:
  * `ConnectState::set_status` gives every status but Playing speed 0 (it gave LoadingPlay the
    playing speed), so other clients see `playback_speed` 0;
  * `prepare_put` re-anchors only Playing, so the stall's puts, their retries and the disconnect's
    put stay put;
  * the snapshot reports speed 0;
  * Spirc's `position()` is the LoadingPlay position.

  The engine's restore cap (`restore::position_heard`) caps LoadingPlay at the Player's last
  packet, as it caps Playing. The first packet after the data comes reports its position (the line
  was cleared, a speed change keeps it cleared, and the packet loop reports the first packet after
  a stall anyway), and Spirc plays on from there. Before the first wait times out (at most
  `download_timeout` after the audio stopped), Connect extrapolates. Anything the engine freezes
  meanwhile is capped at `last_decoded`. The engine's network-loss deferral counts only Playing:
  a stall has nothing to cut off.
* **(d)** A reopen (a stall that paused after `STREAM_STALL_MAX` and then plays again, or a loader
  that is gone) loads the track at P, with the same request id. `last_decoded` is set to P.
  Spirc's Loading arm (`loading_status`) keeps P: it was 0 while Playing. `PlayerState::Loading`
  carries P, and `reopen` stays set until the track is open. A load that fails for a reason that
  can pass sends `Paused` at P and stays reopenable: a play loads it again, and a seek loads it
  paused at the target. Only Spotify's verdict on the track skips it. A new session (a reconnect,
  often on another network) marks a streamed track whose data playback still needs isn't there
  (from the read position to the end; the whole file for an ended track): its loader belongs to
  the old session, whose connections may hang on the old network. Once the session has logged in,
  the restore's load of it reopens it at P with the new session, and one that plays on reopens at
  its first stall. A track that has what it still needs (the engine's offline hand-off takes such
  a one) keeps its decoder, and the never-connected offline session reopens nothing. A reopen the
  packet loop starts is polled before the loop sleeps (`fresh_loader`). A stall isn't skipped when
  the session goes invalid: it waits on, and the reconnect pauses it at P.
* **(e)** The vendored librespot-audio loader sends `ResponseTime` only for a response with data.
  A failed one woke its loop, and the prefetch asked for the same range again every round trip.
  After a failure the prefetch stays off until data comes. Every request, a reader's or the
  player's, waits out a backoff (500 ms doubling to 8 s, reset by data). An expired or invalid URL
  (401, 403, 404, 410), or one that failed 3 times in a row, is left for the file's next CDN URL
  that isn't dead, which is asked once, at once, for what failed; once no URL is left the loader
  ends, and the player then reopens the track with new URLs (d). The open tries the URLs in turn
  (an error status goes on to the next one). The loader's seek stops when a wait brings no
  progress, and when the player dropped the load. The player asks at most once per bounded wait (a), and a paused track asks nothing. A
  request without a response or a part of its body for `download_timeout` fails like any other.
* **(f)** The future runs without tokio's cooperative budget (`coop::unconstrained` in
  `Player::new`), and the loop takes its commands with `try_recv`, which uses none. Under
  `block_on`'s budget, `poll_recv` gave nothing after 128 commands in one stretch of playback
  (the poll doesn't return while a track plays), and the stall's wait, which saw them queued,
  returned at once, again and again: full CPU for as long as the network was down, a pause never
  taken, `STREAM_STALL_MAX` never reached. `wait_for_data` looks at the commands only after a
  `DATA_POLL` sleep and never past its deadline. A decoder that fails ends its track (the state
  goes to EndOfTrack): it stayed Playing and was read again on every pass, sending `EndOfTrack`
  each time, until a load came.

### Round 17's findings against them

1. A reopen on Play went Playing from 0:00, and a failed reopen skipped the item: (c) and (d).
2. One queued command per 8 s attempt: (a).
3. LoadingPlay puts, their retries and `playback_speed` extrapolated, and the restore cap skipped
   LoadingPlay: (c).
4. The loader re-requested on CDN errors every round trip: (e). A 403 ends it, for (d)'s reopen.
5. Seeks and same-track loads bisected on the player thread: (b), and (a) for the wait after a
   seek.
6. The explicit filter didn't stop a ready or loading preload, or a reused track. This is not a
   stall invariant: the filter is now checked wherever a loaded track is used (see the hunk row).

### Round 18's findings against them

1. The loop's commands under tokio's budget, and the stall's wait spinning on them: (a) and the
   new (f).
2. A same-track load at a pending stall's target played on from the displaced decoder: (b).
3. A speed change during a stall set the cleared line, so Spirc stayed LoadingPlay: (c).
4. A restored track kept the old session's loader, whose hung request (no timeout) held its only
   download slot: (a) and (e) (the request's idle timeout), (d) (the reopen on a new session).
5. The tracks the explicit filter refused stayed unavailable in Connect for the session: not a
   stall invariant; the vendored Spirc forgets them when the filter turns off (connect item L).

### Round 19's findings against them

1. A reopen the packet loop started was never polled, so the player stayed Loading (endless
   buffering): (d), with `fresh_loader` (every loader is polled before the loop sleeps).
2. The new session's mark went by the whole file and any session: a track resumed mid-file and
   handed to the offline queue was reopened through the offline session on its next play, which
   failed: (d) (by what playback still needs, and only once the session has logged in).
3. The loader's seek gave up after 64 waits, so an MP3 loaded minutes in failed: (b) and (e)
   (bounded by progress per wait; an MP3's bytes up to the target are requested up front).
4. The open and the loader never used the file's other CDN URLs: (e) (the open tries each, the
   loader moves on, once per switch) and (d) (a reopen only once none is left).
5. A stall was skipped when the session went invalid during it: (d) (it waits on, the reconnect
   pauses it at P).
6. A Connect transfer received while the other device played autoplay never finished: not a
   stall invariant (connect item W).

### Round 20's findings against them

1. A far MP3 load or skip resynced in the middle of a frame after every fail-fast miss: it landed
   seconds past its target, and a false sync failed the load (`DecodeError`, the episode skipped
   and marked unavailable): (b) and (d) (an MP3 seeks coarsely, every attempt starts over; the
   up-front request of the bytes before the target is gone). A load the player dropped kept
   seeking and downloading: (e) (`superseded`).
2. A transfer whose context failed to resolve a moment ago never finished, and the shuffled
   transfer of an autoplay track played the finished context again: not stall invariants
   (connect items X and W).

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
