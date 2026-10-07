# SpotifyGood architecture

This document is the contract between the Rust engine (`native/spotcore`) and the
Android app (`app/`). Every component author follows it; if something needs to change,
change it here first and keep both sides in sync.

```
┌──────────────────────────── Android app (Kotlin, Jetpack Compose) ───────────────────────────┐
│ ui/        Compose screens + ViewModels (observe StateFlows, call repositories/controller)    │
│ data/      repositories (catalog, library, search, home, lyrics), Room, DataStore settings    │
│ download/  DownloadManager + DownloadWorker (WorkManager, dataSync FGS)                        │
│ playback/  PlaybackService (MediaLibraryService) + SpotifyPlayer (SimpleBasePlayer),          │
│            AudioSinkBridge (AudioTrack), audio focus, noisy, output routing, volume, locks    │
│ connect/   DevicesRepository (Connect device list, transfer)                                  │
│ engine/    SpotifyEngine (native lifecycle, holders, network)   auth/ (OAuth PKCE, Keystore)   │
│ nativebridge/  NativeBridge (JNI), NativeRpc (suspend calls), NativeEvents (event fan-out)    │
└───┼───────────────────────────────────────────────────────────────────────────────────────────┘
    │  JNI: JSON RPC (Kotlin → Rust), JSON events (Rust → Kotlin), PCM float writes (Rust → Kotlin)
┌───┼──────────────────────────── libspotcore.so (Rust) ───────────────────────────────────────┐
│ jni.rs / rpc.rs / events.rs   bridge, dispatcher, catch_unwind, thread attach                 │
│ engine/     Session + Spirc + Player owner, supervisor/reconnect, offline mode                │
│ connect/    local Spirc commands, remote device commands (connect-state), cluster → devices   │
│ audio/      AndroidSink (PCM → AudioSinkBridge), AndroidMixer (volume ↔ Android)              │
│ catalog/    metadata (extended-metadata), playlists, rootlist, collection, search, home,      │
│             lyrics, radio — Spotify internal APIs (spclient / pathfinder), JSON to Kotlin     │
│ offline/    downloader (encrypted CDN download, resumable), offline index + Player hook       │
│ librespot 0.8.0: core, connect, playback vendored & patched (native/vendor); metadata, audio, │
│ protocol, oauth, discovery from crates.io                                                    │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 1. Ground rules

* **Spotify internal APIs, not the Web API.** Since Dec 2025 the public Web API answers
  calls made with librespot's (desktop "keymaster") client id with HTTP 429. Everything
  (metadata, playlists, library, search, home, lyrics, Connect control) goes through the
  session's spclient / pathfinder endpoints using the login5 token, like Spotify's own
  clients. The Web API is never on a hot path.
* **One owner per resource.** Rust `Engine` owns `Session`, `Spirc`, `Player`. Kotlin
  `SpotifyEngine` owns *when* the engine runs. `PlaybackService` owns the `MediaSession`.
  `AudioSinkBridge` owns the `AudioTrack`.
* **No polling.** State flows from Rust as events when it changes. Positions are
  interpolated from `(positionMs, positionTimestampMs, playbackSpeed, isPlaying)`; UI
  tickers run only while the UI is visible (`collectAsStateWithLifecycle`).
* **Idle means zero.** When nothing holds the engine (no visible UI, no playback, no
  download, no opt-in Connect presence), the native session is shut down after a grace
  period: no sockets, no timers, no wake-ups.
* **Wake/Wi-Fi locks only while audio is playing locally**, never while paused.
* **All JNI entry points catch panics** and never block the calling Java thread on
  network work; asynchronous work goes through the RPC mechanism.
* **Main thread is sacred.** No disk or network on main; JNI calls are cheap enqueues.
* **Premium only.** librespot cannot stream for Free accounts; the engine reports
  `PREMIUM_REQUIRED` and the app shows an explanatory screen (never crashes).

## 2. Repository layout

```
app/src/main/java/com/taehagen/spotifygood/
  App.kt, AppGraph.kt, MainActivity.kt
  nativebridge/   NativeBridge.kt NativeCallbacksImpl.kt NativeRpc.kt NativeEvents.kt AudioSinkBridge.kt
  model/          Models.kt (catalog + playback + device models, @Serializable)
  engine/         SpotifyEngine.kt EngineHolder.kt NetworkMonitor.kt
  auth/           OAuthManager.kt LoopbackServer.kt CredentialStore.kt AuthRepository.kt Pkce.kt
  playback/       PlaybackService.kt SpotifyPlayer.kt PlayerController.kt PlaybackRepository.kt
                  AudioFocusController.kt BecomingNoisyReceiver.kt OutputRouteManager.kt
                  VolumeSync.kt LibraryTree.kt SessionCommands.kt SleepTimer.kt ResumeStore.kt
                  ArtworkProvider.kt (content:// artwork for Auto/notification)
  connect/        DevicesRepository.kt
  data/           CatalogRepository.kt LibraryRepository.kt SearchRepository.kt HomeRepository.kt
                  LyricsRepository.kt PlaylistEditor.kt ResponseCache.kt
  data/db/        AppDatabase.kt Entities.kt Daos.kt
  data/settings/  SettingsRepository.kt Settings.kt
  download/       DownloadManager.kt DownloadWorker.kt DownloadNotifications.kt
  ui/             theme/ navigation/ components/ screens/<feature>/
native/
  Cargo.toml (workspace, [patch.crates-io] → vendor/)
  spotcore/  build.rs (compiles extra Spotify protos), proto/, src/
  vendor/    librespot-core, librespot-connect, librespot-playback (patched, see README)
docs/ARCHITECTURE.md (this file)
```

## 3. JNI contract

### 3.1 Kotlin → Rust (`com.taehagen.spotifygood.nativebridge.NativeBridge`)

```kotlin
object NativeBridge {
    external fun nativeInit(callbacks: NativeCallbacks, audio: AudioSinkBridge, configJson: String)
    external fun nativeCall(requestId: Long, method: String, argsJson: String)
    external fun nativeCancel(requestId: Long)
}
```

* `nativeInit` — once per process (from `App.onCreate`). Creates the tokio runtime,
  stores global refs and installs the logger. No network. `configJson`:
  `{"filesDir","cacheDir","noBackupDir","deviceId","deviceName","logLevel"}`. `deviceName` (the
  phone model) is the Connect / zeroconf name when `EngineSettings.deviceName` is empty.
* `nativeCall` — never blocks. Result delivered later via
  `NativeCallbacks.onResult(requestId, ok, json)`. `requestId == 0` means
  fire-and-forget. `argsJson` is a JSON object (`{}` when no args).
* `nativeCancel` — aborts the tokio task of an in-flight call. A cancelled call delivers
  `onResult(id, false, {"code":"CANCELLED","message":"…"})`. Exception: engine lifecycle work
  always runs to the end (`session.stop`, `session.logout`, and the configuration step of
  `session.start`, which runs in its own task); cancelling those only stops the wait. Kotlin
  registers its cancellation handler after `nativeCall` returned, so a call cancelled before
  it was dispatched is still cancelled natively.

### 3.2 Rust → Kotlin

```kotlin
interface NativeCallbacks {
    fun onResult(requestId: Long, ok: Boolean, json: String)  // any thread
    fun onEvent(type: String, json: String)                  // any thread
}
class AudioSinkBridge {                      // called ONLY on the librespot player thread
    fun start(): Boolean                     // Sink::start → create/resume AudioTrack
    fun stop()                               // Sink::stop  → pause (not release) AudioTrack
    fun write(frames: Int): Int              // blocking; PCM is already in `buffer`
    val buffer: java.nio.ByteBuffer          // direct, native order, BUFFER_FRAMES*2*4 bytes
    fun onVolume(volume: Int)                // AndroidMixer::set_volume (0..65535), non-blocking
}
```

* PCM format: **float32 interleaved stereo, 44100 Hz** (librespot's native format).
  `BUFFER_FRAMES = 4096`. Rust copies up to `BUFFER_FRAMES` frames into the shared
  direct buffer (address obtained once with `GetDirectBufferAddress`) and calls
  `write(frames)`; Kotlin does `AudioTrack.write(buffer, bytes, WRITE_BLOCKING)` which
  gives natural back-pressure. Returns frames written, or `-1` on a fatal error (Rust then
  returns `SinkError::OnWrite`, librespot stops the track; Kotlin recreates the track on
  the next `start()`).
* The player thread attaches to the JVM once (`attach_current_thread_permanently`).
* `onVolume` must not block: Kotlin posts to main and applies `setStreamVolume` there.

### 3.3 Errors

Error results and error events use
`{"code":"…","message":"…","retryAfterMs":<optional>}` with `code` one of
`NOT_LOGGED_IN, NOT_CONNECTED, BAD_CREDENTIALS, PREMIUM_REQUIRED, NETWORK, NOT_FOUND,
RATE_LIMITED, INVALID_ARGUMENT, UNAVAILABLE, NOT_ACTIVE_DEVICE, PLAYBACK_REFUSED, CANCELLED,
INTERNAL`. `BAD_CREDENTIALS` = the access point refused the login credentials (AP
`LoginFailed` "Bad credentials" / "Could not validate credentials"); Kotlin then deletes the
stored credentials. An HTTP 401/407/511 (rejected bearer token, proxy authentication) is
`NETWORK` and retried. `PLAYBACK_REFUSED` = Spotify permanently refused audio keys for this account
(librespot #1649; AesKeyError 0x0001). The engine stops after 3 consecutive refusals instead
of skipping through the queue, and the app shows a dedicated explanation screen.
Kotlin maps them to `NativeException(code, message)`.

### 3.4 JSON conventions

camelCase keys; absent optional values are omitted (Kotlin `explicitNulls = false`,
`ignoreUnknownKeys = true`, `coerceInputValues = true`). Times are ms. URIs are Spotify
URIs (`spotify:track:<base62>`). Image URLs are absolute (`https://i.scdn.co/image/<hex>`).

## 4. Native engine

### 4.1 Runtime and threads

* One multi-thread tokio runtime, 2 worker threads, `max_blocking_threads(4)`, named
  `spotcore-*`, created in `nativeInit` and kept for the process lifetime (idle workers
  park; no timers when the engine is stopped).
* librespot's Player runs its own thread (`Sink::write` happens there).
* Every RPC spawns a task on the runtime; the task's `AbortHandle` is stored in a
  `DashMap<requestId, AbortHandle>`-like registry for `nativeCancel`.
* `Session::new` is always called inside the runtime context.

### 4.2 Engine state machine

```
          session.start            connected               network lost / session invalid
 Stopped ───────────────▶ Connecting ─────────▶ Online ───────────────────────────────┐
    ▲   ◀── session.stop ──┤  ▲  failed (retryable)                                   │
    │                      │  └──────────── Reconnecting(backoff 1s,2s,4s…60s) ◀──────┘
    │                      └─ BAD_CREDENTIALS / PREMIUM_REQUIRED ─▶ Error (no retry)
    └── session.stop (from any state; graceful, bounded to 10 s)
 Offline mode (settings.offline or no network): Player runs without Spirc; only downloaded
 tracks playable; OfflineController owns the queue; emits the same playback snapshots.
```

* `session.start` creates `Session::new(SessionConfig{client_id: KEYMASTER, device_id,
  tmp_dir: cacheDir/librespot-tmp, autoplay: None ..}, Some(Cache))` (autoplay is applied with the patched
  `spirc.set_autoplay` after `Spirc::new`, so it can change at runtime),
  the `Player` (once; re-bound with `player.set_session` on reconnect), the `AndroidMixer`,
  and `Spirc::new(ConnectConfig{name, device_type: Smartphone, initial_volume:
  <current Android volume>, auto_takeover: false, volume_steps: 64 ..})`, spawns the spirc
  task, subscribes to `subscribe_state()`, `subscribe_cluster()`, `subscribe_errors()`.
* Credentials: first login uses `Credentials::with_access_token(oauthToken)`; afterwards
  always the stored reusable credentials (JSON, `{"username","authType","authData"}`)
  passed in by Kotlin. A `session.start` with only `accessToken` is a fresh login: reusable
  credentials still stored natively (possibly another account's) are dropped first, so the
  token is what logs in. A start with only `credentials` drops an earlier access token, so a
  rejection can't fall back on another account's token. When the account changes, the OAuth
  token (`session.setOAuthToken`) and the username are dropped too. A supervisor that is
  being stopped or replaced can't store or report credentials any more (login generation). When librespot produces new reusable credentials (taken from the
  Session after connect; the librespot `Cache` has no credentials location, so they are never
  written to disk in plaintext, and a `credentials.json` left by an older build is deleted),
  Rust emits a `credentials` event; Kotlin stores them encrypted.
* Reconnect supervisor: awaits the spirc task end / polls `session.is_invalid()` every 5 s
  while Online (cheap, no network), reacts to `session.setNetworkAvailable`. Backoff
  1→60 s, reset once a connection stayed up 60 s (or when the network comes back), so a
  connection that drops right after connecting keeps backing off; at most one attempt in
  flight; no attempts while the network is known to be down. At most 10 attempts per
  10 minutes, counted process-wide (the first attempt after a Kotlin restart is always made
  but counts), then `Error`/`NETWORK` until the network changes. On reconnect:
  `Session::new`, `player.set_session`, `Spirc::new`.
* `session.stop`: `spirc.shutdown()`, await task ≤ 4 s (abort + `dealer().close()` ≤ 2 s on
  timeout), `session.shutdown()`, drop Spirc/Session; the supervisor gets 7 s for this, then it
  is aborted (+ 0.5 s) and cleaned up by force. The Player is dropped on a blocking thread
  (its Drop joins the player thread, which joins its loaders for at most 1 s) only on
  `session.stop {releasePlayer:true}` (logout / process trim), awaited at most 1.5 s;
  otherwise kept for the offline mode. Whole stop ≤ 10 s (checked at compile time). Start,
  stop and logout are serialised by the supervisor lock, held for the whole teardown, so a
  `session.start` after a stop always finds the old session completely gone. Kotlin waits up
  to 15 s for `session.stop` and 30 s for `session.logout`.
* `session.logout`: forgets the account first (credentials, access token, OAuth token,
  username), then stops as above with `releasePlayer:true`, resets `connect`, and deletes the
  credentials dir, the streaming cache and `librespot-tmp`, all under the supervisor lock: a
  following login waits until it is done. Kotlin clears its `CredentialStore` before calling
  it, so a process death mid-logout doesn't log the account back in.

### 4.3 Playback configuration

`PlayerConfig { bitrate: from settings (96/160/320), gapless: settings.gapless, normalisation:
settings.normalize, normalisation_type: Auto, normalisation_pregain_db: settings
(quiet −5, normal 0, loud +5), position_update_interval: None, ditherer: None, .. }`.
Changes (`player.applySettings` or `session.updateSettings`) are applied to the running Player
through the patched runtime setters, without recreating it: bitrate and gapless from the next
load (track change), normalisation from the next audio packet.

Explicit filter: `EngineSettings.filterExplicit` is OR-ed into the session's own
`filter-explicit-content` user attribute (the account's value is kept in a private attribute
and restored when the setting goes off). librespot reads that attribute everywhere: the Player
refuses explicit tracks (Spirc skips them) and skips a loaded one when the filter turns on,
the catalog returns them with `playable:false` (its cached metadata is dropped when the
effective filter changes), and downloads refuse them. It is applied to the live session (when
it is declared online and on every health tick, since Spirc can overwrite it) and to the
offline session the Player uses while not online. `User.explicitFilter` stays the account's.

### 4.4 Audio output

* `AndroidSink` (Sink impl): `start()` → `AudioSinkBridge.start()`; `stop()` →
  `AudioSinkBridge.stop()`; `write(packet, converter)` → `converter.f64_to_f32`, chunk
  into the direct buffer, `AudioSinkBridge.write(frames)`.
* `AndroidMixer` (Mixer impl): `volume()` returns the last value; `set_volume(v)` stores
  it and calls `AudioSinkBridge.onVolume(v)` unless `quantize(v) == quantize(last)` (avoids
  the Android-step ping-pong; Android has ~15–25 steps). Kotlin reports hardware volume
  changes with `player.setVolume {volume, fromSystem:true}`, which updates the mixer's
  stored value *without* calling back to Kotlin, then informs Spirc.
* Soft volume: none (`NoOpVolume`); attenuation is the system stream volume. Ducking is
  done in Kotlin with `AudioTrack.setVolume`.

### 4.5 Offline playback hook (vendored librespot-playback)

`librespot-playback` is vendored with a hook in `PlayerTrackLoader::load_remote_track`:

```rust
pub struct OfflineTrack { audio_item: AudioItem, path: PathBuf, format: AudioFileFormat,
                          key: AudioKey, normalisation: NormalisationData }
pub trait OfflineResolver: Send + Sync { fn resolve(&self, uri: &SpotifyUri) -> Option<OfflineTrack>; }
Player::set_offline_resolver(Option<Arc<dyn OfflineResolver>>)
```

If the resolver returns a track, the Player decrypts the local file (AES-128-CTR via
`AudioDecrypt`, skipping the 0xA7 Ogg header) and never touches the network for metadata,
key or audio. This is used both online (downloaded tracks play from disk) and offline. A
decode failure of an offline file is reported (`playback` error event) and the file is
**never deleted** by the Player.

### 4.6 Offline mode controller

When `settings.offline == true` or the network is down and no session is Online, playback
commands are handled by `OfflineController`: a local queue of downloaded tracks with
shuffle (seeded), repeat context/track, user queue (add/remove/move/clear/skipTo),
prev/next semantics identical to Spirc (prev restarts if position > 3 s). It drives the
same Player and emits the same `playback` snapshots with `source:"local"`,
`isActiveDevice:true`, `offline:true`. When the session comes back Online, the offline
queue keeps playing; the next `player.load` goes through Spirc again.
The engine cannot know which downloads belong to a playlist or Liked Songs, so while the
session is not Online Kotlin's `PlayerController` sends context loads of a playlist / Liked
Songs / album / show with `trackUris` = that context's downloads in context order (Room
collection membership; albums/shows not downloaded as a whole by metadata), keeping
`contextUri`/`startUri`/`startUid` for Spirc. Nothing downloaded while offline → "not
available offline" without a native call. Smart shuffle is not offered for `offline:true`
snapshots.

## 5. Events (Rust → Kotlin `onEvent(type, json)`)

| type | payload |
|---|---|
| `session` | `SessionEvent` |
| `credentials` | `{"username","authType","authData"}` — store encrypted, replaces previous |
| `playback` | `PlaybackSnapshot` (full snapshot, only on change) |
| `devices` | `DeviceList` |
| `queueMetadata` | `{"tracks":[Track…],"episodes":[Episode…]}` metadata for URIs referenced by the snapshot that were not yet cached (UI merges by uri) |
| `download` | `DownloadProgress` |
| `error` | `{"code","message","context":"playback|connect|session|…"}` user-visible, transient |
| `log` | not used (logs go to logcat via android_logger, tag `spotcore`) |

```jsonc
// SessionEvent
{ "state": "stopped|connecting|online|reconnecting|offline|error",
  "error": {"code":"…","message":"…"},            // when state == error or last failure
  "user": {"username":"…","displayName":"…","country":"US","product":"premium",
           "explicitFilter":false,"imageUrl":"…"},  // when known (after ProductInfo)
  "deviceId": "…", "nextRetryMs": 4000 }            // when reconnecting

// PlaybackSnapshot
{ "source": "local|remote|none",        // local = this phone is the active device
  "offline": false,
  "activeDevice": {"id","name","type"},  // omitted when none
  "status": "stopped|loading|playing|paused",
  "positionMs": 0, "positionTimestampMs": 0,   // wall-clock epoch ms when positionMs was valid
  "playbackSpeed": 1.0, "durationMs": 0,
  "context": {"uri":"spotify:playlist:…","name":"…","type":"playlist|album|artist|collection|search|show|station|tracks|unknown"},
  "track": PlaybackTrack, "prevTracks": [PlaybackTrack], "nextTracks": [PlaybackTrack],  // next ≤ 80, prev ≤ 10, hidden delimiters removed
  "shuffle": false, "smartShuffle": false, "repeat": "off|context|track",
  "isPlayingAutoplay": false,
  "restrictions": {"canSkipPrev":true,"canSkipNext":true,"canSeek":true,"canToggleShuffle":true,"canToggleRepeat":true,"canPause":true},
  "volume": 0,                          // 0..65535 of the active device
  "lastError": "…" }
// PlaybackTrack
{ "uri","uid","provider":"context|queue|autoplay|suggestion|unavailable",
  "name","artists":[{"uri","name"}],"album":{"uri","name","images":[Image]},
  "durationMs","explicit", "isEpisode":false, "show":{"uri","name"} }   // metadata fields present when known

// DeviceList
{ "activeDeviceId": "…", "thisDeviceId": "…",
  "devices": [ { "id","name","type":"smartphone|computer|tablet|speaker|tv|avr|stb|audio_dongle|game_console|cast_audio|cast_video|automobile|smartwatch|chromebook|unknown",
                 "volume":0,"supportsVolume":true,"isActive":false,"isThisDevice":false,
                 "isGroup":false,"canPlay":true,"brand":"…","model":"…",
                 "audioOutput": {"type":"bluetooth|speaker|line_out|airplay|car|unknown","name":"…"} } ] }

// DownloadProgress
{ "uri","state":"queued|preparing|downloading|completed|failed|cancelled",
  "bytes":0,"totalBytes":0,"error":{"code","message"} }
```

## 6. RPC methods (`nativeCall(id, method, args)`)

All results are JSON objects (`{}` when nothing to return). Commands that act on
playback are routed by the engine: **if this device is active (or nothing is active)**
→ local Spirc (activating first when needed) / OfflineController; **if another device is
active** → connect-state command to that device.

### 6.1 Session

| method | args | result |
|---|---|---|
| `session.start` | `{"credentials":{…}?,"accessToken":"…"?,"settings":EngineSettings,"initialVolume":0..65535}` | `{}` once Online (or error). `initialVolume` = current `STREAM_MUSIC` volume mapped to 0..65535 (used for the mixer and Connect so startup never changes the system volume). `accessToken` without `credentials` is a fresh login (§4.2). Cancelling only stops the wait |
| `session.stop` | `{"releasePlayer":false}` | `{}` (≤ 10 s; runs to the end even if cancelled) |
| `session.setNetworkAvailable` | `{"available":true,"metered":false}` | `{}` |
| `session.updateSettings` | `EngineSettings` | `{}` |
| `session.logout` | `{}` | `{}` (forgets the account, stops, deletes the caches; runs to the end even if cancelled) |
| `session.zeroconfLogin` | `{"timeoutMs":180000,"deviceName":"…"}` | `{"credentials":{…}}` when another Spotify app hands over credentials (libmdns discovery; Kotlin holds a MulticastLock meanwhile). `deviceName` is advertised (the setting, else the phone model); without it the Connect name below is used |
| `session.token` | `{}` | `{"accessToken","expiresAtMs"}` login5 token (for Kotlin-side HTTP such as artwork never needs it; reserved) |
| `session.setOAuthToken` | `{"accessToken","expiresAtMs"}` | `{}` (lets pathfinder fall back to the OAuth token) |

`EngineSettings`: `{"bitrate":96|160|320,"normalize":true,"normalizePregain":"quiet|normal|loud",
"autoplay":true,"gapless":true,"deviceName":"…","streamingCacheMb":1024,"offline":false,
"filterExplicit":false,"connectVisible":true}`. `connectVisible`: listed as a Spotify Connect
target (Spirc runs), see §8. `filterExplicit` ("Hide explicit content") is OR-ed into the account's
own explicit filter (see §4.3); it can never turn the account's filter off.

### 6.2 Player (routed local/remote)

| method | args |
|---|---|
| `player.load` | `{"contextUri":"…"?,"trackUris":["…"]?,"startUri":"…"?,"startIndex":0?,"startUid":"…"?,"positionMs":0,"shuffle":false?,"smartShuffle":false?,"repeat":"off|context|track"?,"play":true}` |
| `player.play` / `player.pause` / `player.togglePlay` | `{}` |
| `player.next` / `player.prev` | `{}` |
| `player.seek` | `{"positionMs":0}` |
| `player.setShuffle` | `{"enabled":true}` |
| `player.setSmartShuffle` | `{"enabled":true}` (turns shuffle on too) |
| `player.setRepeat` | `{"mode":"off|context|track"}` |
| `player.setVolume` | `{"volume":0..65535,"fromSystem":false}` |
| `player.setAudioOutput` | `{"type":"speaker|bluetooth|line_out|car|unknown","name":"…"}` (local only; reported to Connect) |
| `player.applySettings` | `EngineSettings` subset (`bitrate`, `normalize`, `normalizePregain`, `gapless`), applied to the running Player (§4.3) |
| `queue.add` | `{"uri":"spotify:track:…"}` |
| `queue.remove` | `{"uid":"…"}` |
| `queue.move` | `{"uid":"…","toIndex":0}` — `toIndex` = final 0-based index in `nextTracks` (queued items come first; a queued item is clamped to the queue section) |
| `queue.clear` | `{}` |
| `queue.skipTo` | `{"uid":"…"}` |
| `connect.transfer` | `{"deviceId":"…","play":true?}` (self = pull, other = push) |
| `connect.refreshDevices` | `{}` → `DeviceList` |
| `connect.localInfo` | `{"url":"http://host:port/<CPath>"}` → `LocalDeviceInfo` (ZeroConf `getInfo` of a local-network device; see §8) |
| `connect.localLogin` | `{"url":"…","deviceId"?:"…"}` → `{"deviceId":"…"}` (ZeroConf `addUser`: logs the local device into this account; the returned id is the Connect device id to `connect.transfer` to) |

`LocalDeviceInfo`: `{"deviceId","remoteName","deviceType":<DeviceList type>,"activeUser"?,"tokenTypes":[…],"supportsAccessToken":bool,"version","brand"?,"model"?,"isGroup":bool,"availability"?}`.
Key material (the device's DH public key, client id) never crosses the JNI boundary; Rust keeps it
for the `addUser` call. `connect.localInfo`/`connect.localLogin` are routed by `rpc.rs` to the
`zeroconf_client` module (a `connect.local` prefix match ahead of the generic `connect.` route),
not to the `connect` playback module. `connect.localLogin` requires an online session.

### 6.3 Catalog (Spotify internal APIs, JSON shaped for the UI)

| method | args | result |
|---|---|---|
| `catalog.tracks` | `{"uris":[…≤200]}` | `{"tracks":[Track]}` (extended-metadata batched, LRU cached) |
| `catalog.episodes` | `{"uris":[…]}` | `{"episodes":[Episode]}` |
| `catalog.album` | `{"uri"}` | `Album` (with tracks) |
| `catalog.artist` | `{"uri"}` | `Artist` |
| `catalog.playlist` | `{"uri","offset":0,"limit":100}` | `Playlist` (items page) |
| `catalog.show` | `{"uri","offset":0,"limit":50}` | `Show` (episodes page) |
| `catalog.search` | `{"query","types":["track","artist","album","playlist","show","episode"],"offset":0,"limit":20}` | `SearchResults` |
| `catalog.home` | `{"timeZone"?}` (IANA id; defaults to UTC) | `{"sections":[HomeSection]}` |
| `catalog.lyrics` | `{"uri"}` | `Lyrics` or `NOT_FOUND` |
| `catalog.radio` | `{"uri"}` | `{"contextUri"?:"spotify:playlist:…","trackUris"?:[…]}` (inspiredby-mix; radio-apollo fallback may return only `trackUris`) |
| `catalog.recentlyPlayed` | `{"limit":50}` | `{"items":[MediaRef]}` |
| `catalog.user` | `{"username"?}` | `User` (me when omitted) |
| `library.playlists` | `{}` | `{"items":[RootlistEntry]}` (rootlist, folders preserved) |
| `library.tracks` | `{"offset":0,"limit":100,"urisOnly"?:false}` | `{"total","items":[{"addedAt","track":Track}]}` (Liked Songs); with `urisOnly`: `{"total","items":[],"uris":[…]}` |
| `library.albums` / `library.artists` / `library.shows` / `library.episodes` | `{"offset","limit"}` | paged `{"total","items":[…]}` |
| `library.contains` | `{"uris":[…]}` | `{"contains":[bool]}` |
| `library.save` / `library.remove` | `{"uris":[…]}` | `{}` (tracks/albums/artists/shows/episodes — routed to the right collection set) |
| — | | Playlist revision conflicts (stale `revision`) fail with `INVALID_ARGUMENT` and a message containing "revision"; clients reload and retry. |
| `playlist.create` | `{"name","description"?,"public":false,"uris"?:[…]}` | `{"uri","revision"}` (also added to the top of the rootlist) |
| `playlist.addItems` | `{"uri","uris":[…],"position":null}` | `{"revision"}` |
| `playlist.removeItems` | `{"uri","items":[{"uri","index"}],"revision"}` | `{"revision"}` |
| `playlist.moveItems` | `{"uri","fromIndex","length","toIndex","revision"}` | `{"revision"}` — `toIndex` uses playlist4 MOV semantics: the insert-before position in the list *before* the move (moving item 2 to the end of a 5-item list: from 2, to 5) |
| `playlist.updateDetails` | `{"uri","name"?,"description"?}` | `{}` |
| `playlist.delete` | `{"uri"}` | `{}` (removes from rootlist; unfollow) |
| `playlist.follow` / `playlist.unfollow` | `{"uri"}` | `{}` |

### 6.4 Downloads / offline

| method | args | result |
|---|---|---|
| `download.track` | `{"uri","bitrate":160,"dir":"…/offline/audio","imageDir":"…/offline/images"}` | `OfflineTrackRecord` (progress via `download` events; cancellable; resumes `.part`; waits ≤ 10 s for the session country, else `NOT_CONNECTED`; a CDN `429` asking for more than 30 s, or a second `429`, fails at once with `RATE_LIMITED` and the server's `retryAfterMs`) |
| `download.fileId` | `{"uri"}` | `{"fileId"}` (omitted when unknown): the file the last `download.track` of `uri` in this process chose, also after it failed or was cancelled |
| `offline.setIndex` | `{"tracks":[OfflineTrackRecord],"seq"?}` | `{}` or `{"rejected":["uri",…]}` (replaces the in-memory resolver index, except URIs changed after `seq`; malformed records are skipped) |
| `offline.add` / `offline.remove` | `{"tracks":[…],"seq"?}` / `{"uris":[…],"seq"?}` | `{}` (`add` may also return `"rejected"`; `remove` matches a record's `uri` only, never its `playedUri`, and never deletes files — Kotlin owns deletion) |
| `offline.beginIndex` | `{"seq"}` | `{}` (the next `setIndex` without `seq` is a snapshot containing the changes up to `seq`) |

Ordering: the index RPCs run as independent native tasks and may take effect out of order.
Kotlin numbers every change of its completed downloads (`seq`, increasing within the process,
taken under the `DownloadManager` lock together with the database write) and every snapshot
(the last change it contains; `DownloadManager.offlineRecords()` announces it with
`offline.beginIndex` before the engine sends `offline.setIndex`). The index applies each URI's
newest change, ignores older ones, and a snapshot only sets URIs that did not change after it.
Calls without `seq` apply unconditionally.

`OfflineTrackRecord`:
`{"uri","playedUri","fileId","format","keyHex","path","sizeBytes","normalisation":{"trackGainDb","trackPeak","albumGainDb","albumPeak"},"track":Track|"episode":Episode,"imagePath":"…"}`.
Kotlin persists it in Room (key encrypted with the Keystore key) and sends the decrypted
records to `offline.setIndex` each time the engine starts.

### 6.5 Catalog JSON shapes

```jsonc
Image        {"url","width"?,"height"?}
ArtistRef    {"uri","name","images"?:[Image]}
AlbumRef     {"uri","name","images":[Image],"artists"?:[ArtistRef],"releaseDate"?,"albumType"?:"album|single|compilation|ep","totalTracks"?}
Track        {"uri","name","artists":[ArtistRef],"album":AlbumRef,"durationMs","explicit","playable":true,
              "trackNumber"?,"discNumber"?,"popularity"?,"hasLyrics"?}
Episode      {"uri","name","show":{"uri","name","images"},"description","durationMs","releaseDate","images",
              "explicit","playable","resumePositionMs"?,"fullyPlayed"?}
Album        AlbumRef + {"label"?,"copyrights":[String],"tracks":[Track],"releaseDatePrecision"?}
Artist       {"uri","name","images","headerImages"?,"biography"?,"topTracks":[Track],"albums":[AlbumRef],
              "singles":[AlbumRef],"compilations":[AlbumRef],"appearsOn":[AlbumRef],"related":[ArtistRef],"following"?:bool}
PlaylistRef  {"uri","name","description"?,"images","owner":{"username","displayName"?},"totalTracks"?}
Playlist     PlaylistRef + {"collaborative","isOwnedByMe","canEdit","revision","offset","total",
              "items":[{"uid"?,"addedAt"?,"addedBy"?,"track"?:Track,"episode"?:Episode}],"following"?:bool}
             (items never drop out, so indexes stay aligned for edits: local files and unresolved
              items keep their slot as a `track`/`episode` with `playable:false`; local files have
              empty `artists`)
ShowRef      {"uri","name","publisher"?,"images"}
Show         ShowRef + {"description","episodes":[Episode],"total","offset","following"?}
SearchResults {"tracks","artists","albums","playlists","shows","episodes" (arrays),"topResult"?:MediaRef}
MediaRef     {"type":"track|album|artist|playlist|show|episode|collection","uri","name","subtitle"?,"images"}
HomeSection  {"id","title","items":[MediaRef]}
RootlistEntry {"type":"playlist|folder","uri"?,"name","images"?,"owner"?,"children"?:[RootlistEntry],"collaborative","canEdit"}
Lyrics       {"syncType":"LINE_SYNCED|UNSYNCED|SYLLABLE_SYNCED","lines":[{"startTimeMs","words"}],
              "provider"?,"colors"?:{"background","text","highlightText"}}
User         {"username","displayName","images","product","country","explicitFilter"}
```

## 7. Smart shuffle

Implemented inside the vendored Spirc (`Spirc::smart_shuffle`). Turning it on enables
shuffle, fetches recommendations for the current context through the autoplay context
endpoint off the event loop, and interleaves one suggestion after every 3rd context track
while filling the upcoming list. Suggestions carry provider `suggestion` in snapshots.
UI: shuffle button cycles **off → shuffle → smart shuffle → off**; suggested rows show a
sparkle badge with "Add to playlist / Remove suggestion" actions (`queue.remove`).
Media3: `shuffleModeEnabled=true` plus a custom command button (`ICON_SHUFFLE_STAR`).
For a remote active device, smart shuffle is not supported (the command reports
`UNAVAILABLE`), plain shuffle is.

## 8. Spotify Connect

* **This phone as a target**: Spirc registers the device via the dealer; other devices see
  it while the engine is Online **and** `EngineSettings.connectVisible` is true. Remote
  commands, transfers and volume arrive through Spirc. `auto_takeover` is off: the phone never
  starts audio on its own at launch.
* **Visibility**: the phone is listed only while it can play. Kotlin sets `connectVisible`
  while a UI (app in the foreground), PLAYBACK or PRESENCE holder is held; a DOWNLOAD holder
  alone and the idle grace keep it hidden. Hidden, the supervisor connects the Session without
  Spirc (catalog, downloads and tokens keep working, `connect` routes as if not online).
  Becoming hidden shuts Spirc down (it disconnects, deletes its connect state and closes the
  dealer, so the device leaves the cluster) and keeps the Session. Becoming visible reconnects
  with a new Session + Spirc: `Spirc::new` performs the login itself and a Session's dealer
  can be launched only once, so Spirc can't be added to a connected Session. These reconnects
  don't count against the reconnect limit.
* **Device name**: a rename (`EngineSettings.deviceName`) reconnects Session + Spirc so other
  devices see the new name, at once unless this phone is the active device and playing (then
  as soon as it isn't); a hidden session uses the new name when it becomes visible.
* **Controlling others**: device list and remote player state come from
  `Spirc::subscribe_cluster()`. Commands go to
  `POST /connect-state/v1/player/command/from/{me}/to/{target}` with bodies
  `{"command":{"endpoint":"pause|resume|skip_next|skip_prev|seek_to|set_shuffling_context|set_repeating_context|set_repeating_track|add_to_queue|set_queue|play","…","logging_params":{"command_id":"<hex32>"}}}`;
  volume `PUT /connect-state/v1/connect/volume/from/{me}/to/{target}` `{"volume":n}`
  (debounced ≥ 200 ms); transfer `SpClient::transfer(me, target, TransferOptions{restore_paused:"restore"})`.
  Remote queue edits are sent as `set_queue` with the cluster's `queue_revision`.
* **Remote playback in the app**: `PlaybackSnapshot.source == "remote"` is built from the
  cluster's `player_state` (position extrapolated with `session.time_delta()`); the
  MediaSession switches to `DeviceInfo(PLAYBACK_TYPE_REMOTE)` so hardware volume keys
  control the remote device; the notification says "Playing on <device>".
* **Audio output reporting**: Kotlin reports the current local output (speaker /
  Bluetooth "<name>" / wired / USB / car) with `player.setAudioOutput`.
* **Local-network discovery (the "send" side)**: speakers and receivers on the LAN that are not
  yet in the account's cluster (a librespot/spotifyd box, an idle speaker) advertise a ZeroConf
  HTTP service `_spotify-connect._tcp`. The app lists them and logs the tapped one into this
  account, so it joins the cluster and playback can be transferred to it.
  * **Kotlin (`connect/LocalDeviceDiscovery.kt`)** browses mDNS with `NsdManager`
    (`registerServiceInfoCallback` on API 34+, `resolveService` below, one resolve at a time),
    reads the `CPath` TXT record (default `/`), and holds a Wi-Fi `MulticastLock` **only while the
    devices sheet is visible**. Discovery runs only while the sheet is open and stops on dispose,
    background or logout (battery). Each resolved service is probed with `connect.localInfo`, then
    deduped by `deviceId` and dropped if it is already in the cluster `DeviceList`.
  * **Rust (`zeroconf_client/`)** is the exact inverse of `librespot-discovery` 0.8.0's device
    side. `connect.localInfo` GETs `?action=getInfo`. `connect.localLogin` POSTs `?action=addUser`
    with the credentials blob: Diffie-Hellman with the device's `publicKey` (librespot's DH group),
    `baseKey = SHA1(shared)[..16]`, `encryptionKey = HMAC-SHA1(baseKey,"encryption")[..16]`,
    `checksumKey = HMAC-SHA1(baseKey,"checksum")`; AES-128-CTR with a random IV and an HMAC-SHA1
    checksum over the ciphertext, sent as `base64(iv‖ciphertext‖mac)` with our DH public key as
    `clientKey`. The inner blob is the inverse of `Credentials::with_blob`
    (`0x49,bytes(user),0x50,int(authType),0x51,bytes(authData)`, block-padded, the XOR-with-prior-
    block step, AES-192-ECB under a PBKDF2 key from `SHA1(deviceId)` and the username, then base64).
    When `getInfo` advertises `tokenType` `accesstoken`, a fresh login5 access token (keymaster,
    `streaming` scope) is sent as the blob with the device's client id as `clientKey`; otherwise the
    stored reusable credentials blob is used. After a successful `addUser` the engine waits up to
    10 s for the device to appear in the cluster and returns its Connect device id for
    `connect.transfer`. Only local-network hosts (loopback / private / link-local / `.local`) over
    plain HTTP are accepted; all timeouts are bounded. mDNS browsing is Kotlin's `NsdManager`, so
    Rust only ever sees the URL.

## 9. Android app

### 9.1 Dependency injection

Manual DI: `App` creates `AppGraph` (lazy singletons). ViewModels get dependencies via
`viewModelFactory { initializer { … } }` reading `(application as App).graph`. No Hilt.

### 9.2 Engine lifecycle (Kotlin `SpotifyEngine`)

* `holders`: a ref-counted set of `EngineHolder` tokens: `UI` (ProcessLifecycleOwner
  STARTED), `PLAYBACK` (PlaybackService while it has playback or is foreground),
  `DOWNLOAD` (DownloadWorker while running), `PRESENCE` (opt-in Connect presence).
* When the first holder is acquired and credentials exist → `session.start`.
  When the last holder is released → after `IDLE_GRACE` (60 s) `session.stop`.
* `NetworkMonitor` (ConnectivityManager default-network callback, registered only while
  the engine is running) → `session.setNetworkAvailable`.
* `state: StateFlow<EngineState>` mirrors `session` events; `user: StateFlow<User?>`.
* Writes reusable credentials from `credentials` events to `CredentialStore`.

### 9.3 Auth

* **Primary: OAuth device authorization grant** (RFC 8628) with the desktop client id
  `65b708073fc0480ea92a077233ca87bd` and the desktop scope list:
  `POST https://accounts.spotify.com/oauth2/device/authorize` → `{device_code, user_code,
  verification_uri, verification_uri_complete, expires_in, interval}`; open
  `verification_uri_complete` in a Custom Tab (explicit browser package) and also show the
  code (for approving from another device); poll `POST https://accounts.spotify.com/api/token`
  (`grant_type=urn:ietf:params:oauth:grant-type:device_code`, honour `interval`/`slow_down`,
  stop on `expired_token`/`access_denied`) only while the login screen is visible: polling
  pauses when the app goes to the background or the screen leaves composition (e.g. while the
  code is approved in the Custom Tab) and resumes when it is shown again, and stops when the
  screen is left for good (activity finished) before a token arrived. The device code is
  persisted (≤ expiry) so polling resumes after process death. No local server.
* **Fallback:** OAuth Authorization Code + PKCE with the desktop client id
  `65b708073fc0480ea92a077233ca87bd`, redirect `http://127.0.0.1:5588/login` (fallback
  port 8898), desktop scope list, `state` verified. `LoopbackServer` binds 127.0.0.1 only,
  loops until `/login`, 5 min timeout, replies with a 302 to `spotifygood://auth` plus an
  HTML "Return to the app" link, then closes. Custom Tab launched with an explicit browser
  package so the official Spotify app cannot intercept. Exchange at
  `https://accounts.spotify.com/api/token` with OkHttp. The access token goes to
  `session.start {accessToken}`; the refresh token is stored encrypted as a fallback.
* Alternative login: "Use another device" → `session.zeroconfLogin` (mDNS, MulticastLock
  only while that screen is visible: hiding the screen cancels it).
* A finished login (`LoginState.Success`) goes back to the options as soon as the engine
  reports logged out (logout, rejected credentials).
* `CredentialStore`: AES-256-GCM key in AndroidKeyStore; ciphertext in
  `noBackupFilesDir/credentials.bin`. Also encrypts per-download audio keys. The key is only
  replaced when it is permanently invalid (`KeyPermanentlyInvalidatedException`, a corrupted or
  missing key); transient Keystore failures are retried and then reported as
  `KeystoreUnavailableException` without deleting anything.
* Logout (with confirmation): stops the login flows and deletes the pending device code, then
  `session.logout`, credentials, downloads, the resume state, the response and image caches,
  the DB and the settings. Every step runs even if an earlier one failed; no new login reaches
  the engine until the wipe is done.

### 9.4 Playback service

* `PlaybackService : MediaLibraryService`, `foregroundServiceType="mediaPlayback|connectedDevice"`.
  Session player = `SpotifyPlayer : SimpleBasePlayer(mainLooper)` built from
  `PlaybackRepository.snapshot` (window: last 10 prev + current + next 50, uids from
  Connect). `invalidateState()` on every snapshot. Position via `PositionSupplier` from the
  snapshot (extrapolating). Media items carry title/artist/album/artworkUri
  (`content://<app>.artwork/<urlhash>` served by `ArtworkProvider` from the Coil disk cache).
* Commands: play/pause/prev/next/seek/seek-to-item (`queue.skipTo`), shuffle, repeat,
  set-media-items (Auto/Assistant/resumption), device volume only when remote (relative
  steps accumulate from the last sent target for 2 s), seek back/forward 15 s for episodes.
  Media button preferences: like/unlike, shuffle (3-state), repeat (3-state); for episodes
  −15 s / +15 s next to play/pause instead of shuffle/repeat. `onSetRating` (HeartRating)
  toggles like. Remote playback: the current item's artist reads "<artists> • Playing on
  <device>" on API 30+ (SysUI shows only title/artist), the notification text adds it below
  API 30; the subtitle carries the device line. Downloaded tracks use their downloaded cover.
* Player error (only while nothing plays; STATE_IDLE, playlist kept): logged out →
  `AUTHENTICATION_EXPIRED` + "Sign in" action; `PREMIUM_REQUIRED`; `PLAYBACK_REFUSED`; else
  the last failed attempt to start playback (`PlayerController.failure`, also native
  `playback` error events). `prepare()` clears it. Browsing logged out / without Premium
  returns the matching `SessionError`.
* Cold start: commands that start playback wait (≤ 15 s, outside their timeout; a pause cancels
  the wait) while the session is starting with a network; play/resume fall back to the
  `ResumeStore` session on NOT_ACTIVE_DEVICE, NOT_CONNECTED (not while mirroring a remote
  device) and UNAVAILABLE while connecting. Auto browse/search/voice wait the same way.
* `onConnectAsync` grants commands to the notification, SysUI, Auto/AAOS, Wear and the
  app's own controller; others get read-only.
* `MediaLibrarySession.Callback`: browse tree for Android Auto (≤4 tabs: Home, Library,
  Downloads, Browse); search; `onPlaybackResumption` from `ResumeStore` (DataStore:
  context, track, position, metadata) persisted on pause and every 15 s while playing.
* Foreground: Media3 default (10 min after pause, then notification becomes dismissable).
  Local audio never plays without it: local audio starting in the background with no service
  (remote "play on this phone" during the idle grace or a download) starts the service with
  `startForegroundService` (focus waits for the foreground); refused, or not foreground within
  5 s → pause + "Tap to resume" (`ResumeAlert`). `onForegroundServiceStartNotAllowedException`
  → for local playback pause + "Tap to resume"; while mirroring a remote device the notification
  is posted without the foreground (the remote device is never paused).
  `onTaskRemoved` default behaviour. Engine holder released when the service is destroyed.
* **Opt-in Connect presence** (setting "Stay available for Spotify Connect", default off):
  when enabled and the app goes to background while idle, the service keeps itself in the
  foreground as `connectedDevice` with a low-importance "Available on Spotify Connect"
  notification (Stop action), so remote "play on this phone" works. Uses
  `onUpdateNotificationAsync` override as described in research; off by default because of
  the battery cost (~2 radio wake-ups per minute).
* Audio focus (`AudioFocusController`, AudioManagerCompat): requested when local playback
  starts (status playing, source local), abandoned on stop/pause timeout. LOSS → pause;
  LOSS_TRANSIENT → pause + resume on GAIN (if within 10 min); CAN_DUCK → AudioTrack volume
  0.2 → restore (a duck keeps focus; a granted request clears the duck). Request failure → pause.
* `BecomingNoisyReceiver`: registered only while playing locally → `player.pause`.
* Wake locks: Media3 `WakeLockManager` + `WifiLockManager` `setStayAwake(true)` only while
  local status is playing/loading; false otherwise.

### 9.5 Audio output routing (Bluetooth / external)

* `OutputRouteManager` lists media outputs (`AudioManager.getDevices(OUTPUTS)` filtered to
  speaker, wired headset/headphones, BT A2DP / BLE headset/speaker / hearing aid, USB,
  HDMI, line out, dock), tracks the current route via `AudioTrack.getRoutedDevice()` +
  `OnRoutingChangedListener`, and listens with `AudioDeviceCallback` (registered only
  while the engine runs).
* User selection → `AudioSinkBridge.setPreferredDevice(AudioDeviceInfo?)` (`null` =
  system default; best effort — verify with `routedDevice()`). "More devices…" opens the
  system output switcher via `androidx.mediarouter.app.SystemOutputSwitcherDialogController
  .showDialog(context)` (API 30+; on 26–29 falls back to Bluetooth settings) — lists Bluetooth and
  other system audio outputs not yet connected (the app does not cast). Never use `setCommunicationDevice` for media.
* Device sheet (one UI for everything, like Spotify's): **This phone** (with current output
  name + icon and local output choices), then **Spotify Connect devices**, then
  "More devices…". Selecting a Connect device → `connect.transfer`.
* On BT disconnect: `ACTION_AUDIO_BECOMING_NOISY` pauses; route listener updates UI and
  reports `player.setAudioOutput`. AudioTrack `ERROR_DEAD_OBJECT` → recreate track.

### 9.6 Volume

* Local active device: Connect volume ↔ `STREAM_MUSIC`. Mixer callbacks
  (`AudioSinkBridge.onVolume`) → `setStreamVolume` (no UI flag) unless the quantized step
  is unchanged; `VolumeSync` observes stream volume changes (ContentObserver on
  `Settings.System` + `VOLUME_CHANGED_ACTION`, registered only while the engine runs) and
  sends `player.setVolume {fromSystem:true}`.
* Remote active device: MediaSession `DeviceInfo(REMOTE, 0..100)`; `handleSetDeviceVolume`
  / increase / decrease → `player.setVolume`. In-app slider in the device sheet.

### 9.7 Downloads

* `DownloadManager`: enqueue track / album / playlist / Liked Songs / show episodes;
  persists `DownloadEntity` (state QUEUED) and `DownloadCollectionEntity` (collection URI,
  auto-sync); enqueues unique work `downloads` (KEEP) with constraints (network CONNECTED
  or UNMETERED per setting, storage not low). Removal deletes files + rows.
* Execution: **API 34+ → user-initiated data transfer job** (`JobScheduler`, `DownloadJobService`,
  `setUserInitiated(true)`, scheduled while the app is visible — survives the Android 15 dataSync
  limit and the Android 16 job quota); **API < 34 → WorkManager** worker below. Both delegate to
  the same `DownloadRunner`. Periodic collection sync is plain constrained WorkManager.
* `DownloadWorker : CoroutineWorker` → `setForeground` (dataSync, progress notification,
  Cancel action), acquires the `DOWNLOAD` holder, waits for Online (≤ 60 s), then downloads
  queued items one at a time with `download.track` (cancellation propagates to
  `nativeCancel`), stores records (encrypted key), updates `offline.add`, retries failures
  with backoff (max 3), stops gracefully on `onStopped`/timeout (Android 15 6 h limit),
  re-enqueues itself if work remains. "Not enough storage" reschedules (the hosts require
  storage not low) instead of stopping for good.
* Scheduling: turning "Download using mobile data" off or on stops a running run (its item
  resumes from the `.part`) and re-creates the job / worker with the new network constraint;
  pending work whose constraint does not match the setting is re-created too, and the runner
  never downloads on a metered network while mobile data is off. User actions, app start,
  coming online and returning to the app re-create work that waits out a retry backoff (never
  an executing job or running worker).
* Queue-wide pauses (`QueueBreaker`): `RATE_LIMITED` requeues the item without counting an
  attempt and pauses the whole queue for the server's `retryAfterMs` (else 1 min, doubling);
  three consecutive connectivity failures while the session is online (CDN unreachable) pause
  it for 1, 4, 16 min …; at most 30 min. Every pending row is held back (`retryAt`), so the run
  waits inline (≤ 2 min) or reschedules; a completed download resets the breaker.
* Collection sync: when online (engine start + daily periodic work), re-fetch downloaded
  playlists/albums/liked songs, enqueue new items, remove items that left (unless also part
  of another downloaded collection). Liked Songs are listed with `library.tracks
  {urisOnly:true}`; new rows get metadata from `catalog.tracks`. Only a *complete* resolution
  removes items (item count matches the source's total, not empty): an empty or short one only
  adds, and is retried. A collection whose sync fails or is incomplete is retried after 1 h,
  doubling up to 24 h (`lastAttemptAt`, `syncFailures`), instead of at every reconnect.
  Members the catalog resolves as not playable here (`playable:false` with a name) stay members
  but are not queued and do not count in the collection status (`unavailableUrisJson`); they
  are queued once they become playable.
* Storage: `noBackupFilesDir/offline/audio/<fileIdHex>` (+ `.part`),
  `noBackupFilesDir/offline/images/<imageIdHex>.jpg`. CDN chunks start at 2 MiB and adapt between 1 and
  4 MiB, streamed with a 20 s stall timeout; the first frame validates the key. Settings shows usage and "Remove all";
  usage counts every row that still owns a finished file (also ones marked failed later), each
  shared file once.
* Files are shared: the downloader reuses a verified `<fileId>`, so several rows (relinking, the
  same recording in two releases) can use one file. Removal deletes a completed file only when no
  remaining row has it as its `path` or `fileId`. Unfinished rows record the file their download
  writes (`download.fileId`, stored in `fileId`); garbage collection (when the queue is idle)
  keeps a `.part` while an unfinished row (pending, failed, cancelled) names it, so "Retry
  failed" resumes it, and deletes files and `.part`s no row names.
* Downloads require Premium (they are always Premium here) and are wiped on logout.

### 9.8 Data layer

Native catalog strategy (Rust `catalog/`):
* **spclient** (stable, preferred): extended-metadata batches (tracks, albums, artists, shows,
  episodes), `playlist/v2` (playlists, rootlist, changes), context-resolve (Liked Songs
  `spotify:user:<u>:collection`, artist/album contexts), `collection/v2` paging/write/contains
  (library sets: `collection` tracks+albums, `artist`, `show`, `listenlater`; protos compiled
  from `librespot-protocol/proto/collection2v2.proto` in `spotcore/build.rs`),
  `recently-played/v3`, `user-profile-view/v3`, `radio-apollo/v3` and `inspiredby-mix/v2`,
  `color-lyrics/v2` (lenient JSON parsing).
* **pathfinder** GraphQL (`https://api-partner.spotify.com/pathfinder/v2/query`, persisted
  queries) for search (`searchDesktop`) and the home feed (`home`). Operation hashes rot, so
  they are discovered at runtime: fetch `https://open.spotify.com/` once, extract the web
  player bundle URLs, regex out `"<operationName>","query","<sha256>"` pairs, cache them on
  disk (`filesDir/pathfinder.json`, refreshed weekly or on `PersistedQueryNotFound`); shipped
  defaults are only a starting point. Token: login5 first; on 401/403 the OAuth access token
  from login (if still valid); otherwise the call fails over to the fallbacks below.
* **Fallbacks**: search → spclient `searchview/km/v4/search/<q>` (JSON) → context-resolve
  `spotify:search:<q>` (tracks only). Home → assembled locally from recently played,
  rootlist playlists (incl. followed Made-For-You mixes), followed artists and radio
  stations seeded from recent tracks.
* The public Web API is never used by default.



Repositories call the native catalog RPCs and expose `suspend` functions / `Flow`s.
`ResponseCache` (Room table `response_cache`: key, json, fetchedAt) stores the last
successful response of browse calls (home, library lists, album/artist/playlist pages) so
the app opens instantly and works offline; stale-while-revalidate. Library mutations are
optimistic (local state flips immediately, rolled back on error). Liked-state of the
current track is cached in memory (LRU) and refreshed via `library.contains`.

### 9.9 UI

* Material 3, dark-first theme (Spotify-like near-black, green accent); optional dynamic
  color; edge-to-edge; predictive back; adaptive (bottom bar on phones, navigation rail on
  large screens via `NavigationSuiteScaffold`).
* Screens: Login, Premium-required, Home, Search (+ browse/recent searches), Library
  (filters: Playlists/Albums/Artists/Podcasts/Downloaded; sort; grid/list), Liked Songs,
  Album, Artist, Playlist (edit mode for owned), Show, Episode, Downloads, Now Playing
  (full screen, palette gradient, seek bar, like, shuffle tri-state, repeat, queue, lyrics,
  devices, share, sleep timer), Queue (reorder, remove, suggestions), Lyrics (synced,
  auto-scroll, tap to seek), Devices/Output sheet, Track/Album/Playlist action sheets,
  Add-to-playlist sheet, Create playlist dialog, Settings, Profile.
* Mini player above the navigation bar (swipe/tap to expand, progress line, play/pause,
  device indicator).
* Deep links: `https://open.spotify.com/{type}/{id}` and `spotify:{type}:{id}` intents.
* Offline: banner + downloaded-only filtering when offline mode or no network.

## 10. Lifecycle & battery policy (summary)

| Situation | Native session | Connect target | FGS | Locks |
|---|---|---|---|---|
| App visible | Online | yes | none unless playing | none |
| Playing locally | Online (or offline mode) | yes | mediaPlayback | wake + Wi-Fi |
| Paused < 10 min | Online | yes | mediaPlayback (Media3 timeout) | none |
| Paused ≥ 10 min, app background | hidden, stopped 60 s after release | no | none | none |
| Remote device playing, our session mirrors | Online | yes | mediaPlayback | none |
| Downloading (app in background) | Online | no (no Spirc) | dataSync (WorkManager) | Worker's |
| Presence opt-in, idle | Online | yes | connectedDevice (low-importance) | none |
| Nothing | stopped | no | none | none |

## 11. Feature checklist

Login (OAuth, other-device), Premium gate, logout, background play, notification &
lock-screen controls, Bluetooth/headset buttons, Android Auto, playback resumption,
audio focus & ducking, becoming-noisy pause, output switching (speaker/BT/wired/USB +
system switcher), Connect send (device list, transfer, remote control incl. volume keys)
and receive (phone as Connect device), shuffle, smart shuffle with suggestions, repeat
all/one, queue (view, add, remove, reorder, clear, jump), autoplay, gapless,
normalisation, streaming quality, playlists (view, create, edit, reorder, delete,
follow), Liked Songs, saved albums/artists/podcasts, follow artists, search (all types,
recent searches), home feed, album/artist/playlist/show/episode pages, lyrics (synced),
radio, share links, deep links, downloads (track/album/playlist/liked/podcast, Wi-Fi only
option, storage management, auto-sync), offline mode, sleep timer, explicit-content
filter, system equalizer, settings, adaptive layouts, accessibility (content
descriptions, touch targets, TalkBack-friendly controls).
