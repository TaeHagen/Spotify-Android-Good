# librespot-playback 0.8.0 — local patches

This is the crates.io package `librespot-playback 0.8.0`, unchanged except for the hunks
below. `Cargo.lock`, `Cargo.toml.orig` and `.cargo_vcs_info.json` were removed, and `LICENSE`
(MIT) was copied from the librespot 0.8.0 repository root. It is wired in through
`[patch.crates-io]` in `native/Cargo.toml`. Every local change is marked `// SPOTIFYGOOD:`;
`grep -rn SPOTIFYGOOD src/` lists them all, so the patch can be re-applied when upgrading.

**Requires the vendored `librespot-core`.** The patch uses
`librespot_core::audio_key::is_permanent_denial`, which stock core 0.8.0 does not have.

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
| `player.rs` consts | `AUDIO_KEY_RETRIES = 3`, `AUDIO_KEY_RETRY_DELAY = 1 s`, `PLAYER_RUNTIME_WORKER_THREADS = 1`. |
| `UnavailableReason`, `KeyFailure`, `classify_audio_key_error` | New. Classification: `is_permanent_denial` → abort; session invalid / `SessionError::NotConnected` → no retry; everything else → retry. |
| `PlayerCommand` | `SetOfflineSource`, `SetBitrate`, `SetNormalisation`, `SetGapless` (+ `Debug` arms). |
| `PlayerEvent::Unavailable` | `reason` field. |
| `Player::new` | `thread::Builder` named `lrs-player`; runtime `new_multi_thread().worker_threads(1).thread_name("lrs-player-rt")`. |
| `Player::set_*` | New methods. |
| `PlayerPreload::Loading`, `PlayerState::Loading` | loader output `Result<_, UnavailableReason>` instead of `Result<_, ()>`. |
| `PlayerTrackLoader::load_track` / `load_remote_track` / `load_local_track` | Return `Result<PlayerLoadedTrackData, UnavailableReason>`. Offline hook. Key retry. No cache deletion after a key failure. Local files: `duration.as_secs().max(1)` (stock divides by zero for files < 1 s). |
| `PlayerTrackLoader::request_audio_key` | New: request with retries. |
| poll loop | `Unavailable` carries the reason (load and preload). The `else { exit(1) }` after a failed sink start is now a debug log; the player is already paused. |
| `PlayerState::{is_playing, decoder, playing_to_end_of_track, paused_to_playing, playing_to_paused}`, start-playback check, `handle_player_stop`, `handle_packet` | `exit(1)` → `panic!`. A panic only ends the player thread: `Player::is_invalid()` becomes true and the engine can create a new Player. |
| `ensure_sink_stopped` | `sink.stop()` error: log, mark the sink closed, call the sink callback (was `exit(1)`). |
| `normalisation_factor_for`, `handle_set_normalisation` | New. `start_playback` uses the helper; same behaviour. |
| `PlayerInternal::load_track` | Named thread `lrs-loader`. Sends the full `Result`. Holds `load_handles` while spawning and inserting (stock could leak a finished thread's handle until Player drop). A dropped result sender maps to `Other`. |

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
* **`set_normalisation`** applies from the next packet: the config and knee factor are updated,
  and the current track's gain is recomputed from its normalisation data. `normalisation_type:
  Auto` still follows `set_auto_normalise_as_album`.
* **Key retry timing.**
  * Up to 4 attempts (1 + 3 retries) with 1 s between them. Each attempt has core's 1.5 s
    timeout, so worst case ≈ 9 s before the load finishes.
  * A superseded load keeps retrying in its own thread; the result is discarded.
  * Permanent denial → `Unavailable(KeyDenied)` at once, with no "without decryption" attempt.
  * Transient exhaustion → the file is still tried without decryption (some files are not
    encrypted). If that fails → `Unavailable(KeyTemporarilyDenied)`. A cached file is never
    deleted after a key failure.
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
