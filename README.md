# SpotifyGood

An Android Spotify client written in Kotlin with Jetpack Compose and Media3, playing audio
through [librespot](https://github.com/librespot-org/librespot) (Rust, compiled into the app
over JNI). Requires a Spotify Premium account.

## Features

- **Playback:** background play with a media notification and lock-screen controls,
  Bluetooth/headset buttons, Android Auto, playback resumption, audio focus and ducking,
  pause on headphone unplug, gapless, normalisation, streaming quality, sleep timer, system
  equalizer.
- **Home-screen widget:** what plays on the phone or a Spotify Connect device ("Playing on
  Kitchen"), resizable from 2x1 (artwork and play/pause) through 4x1 (title, artist, previous /
  next) to 4x2 and larger (like and shuffle), in the wallpaper's colours from Android 12. With
  nothing playing it offers the last session, whose Play works with the app closed; logged out it
  offers "Sign in". It is updated only when what plays changes, never on a schedule.
- **Output switching:** speaker, Bluetooth, wired or USB from the app's device sheet or the
  system output switcher; routing follows device changes.
- **Android's output switcher (Android 12 and later):** your Spotify Connect devices are listed in
  the system's own picker, the "This phone" chip of the media controls in quick settings and on
  the lock screen (and the volume panel's media output). Picking one moves playback there, picking
  "This phone" or a headset brings it back, and while another device plays the chip shows its
  name and the switcher's slider sets its volume. From Android 14 the list keeps the app's order,
  shows devices that can't play as such, and "Other devices on your network" opens the app's device
  sheet to sign in speakers and TVs. Nothing scans for it: the list is the one the app already has
  while playback runs, and with nothing playing the system part lists nothing and does no work. Not
  on Android 11, where Android would keep the app running all day for it.
- **Spotify Connect:**
  - send: pick a device, transfer playback, control the remote device including volume
    keys. With nothing playing, picking a device makes the next play start there.
    Speakers on the local network that are not signed in to the account (librespot and
    spotifyd boxes, Connect speakers) are discovered and can be signed in from the device
    sheet. So are Google Cast speakers and TVs (Nest, Chromecast, Google TV, soundbars with
    Chromecast built-in): the app starts Spotify on them and signs them in itself, without the
    Cast SDK or Google Play services;
  - receive: the phone shows up as a Connect device and can be switched to. Receiving while
    the app is closed needs the "Stay available for Spotify Connect" setting. It comes back by
    itself after an app update, and after a reboot up to Android 14; from Android 15 a
    notification after a reboot asks to open the app once (Android does not let a service
    started at boot play media later). The phone is only listed as a Connect target while it
    can actually play.
- **Play modes:** shuffle, smart shuffle (suggestions mixed into the context), repeat all,
  repeat one, autoplay, radio. In-app plays keep the current shuffle and repeat; podcasts play
  in order.
- **Podcasts:** playback speed (0.5× to 3.5×, one speed for all episodes, on this phone),
  ±15 s, resume points (newest of this phone's and Spotify's wins, on every way an episode
  starts), Mark as played / unplayed, Your Episodes, "End of episode" sleep timer.
- **Queue:** view, add, remove, reorder, clear, jump; add whole albums and playlists.
- **Library:** playlists (create, rename, edit, reorder, delete, follow, make public, private
  or collaborative, add albums, playlists and episodes with an "already added" check), Liked
  Songs, saved albums, artists and podcasts, follow artists. Liked Songs and playlists can be
  sorted (recently added, title, artist, album, custom order) and filtered; a sorted list plays
  in the order shown.
- **Android Auto and voice:** browse the library and downloads (also offline), paged lists,
  search, and "play X" requests from Assistant or Auto, matched against your own playlists and
  downloads first.
- **Hide explicit content:** the app's setting and the account's own (family) filter both
  apply, online and offline.
- **Browse:** search across all types with recent searches, home feed, album, artist,
  playlist, show and episode pages, synced lyrics, share links and deep links
  (`spotify:` URIs and open.spotify.com links). On Android 12 and later, open.spotify.com
  links open in the browser until you allow them for SpotifyGood: Settings › Links in the app
  takes you to the system's "Open by default" page (shown only while needed). Sharing a link
  to SpotifyGood works without that.
- **Downloads:** tracks, albums, playlists, Liked Songs and podcasts for offline playback in
  the app. Files stay encrypted and their keys are protected by the Android Keystore.
  - Optional Wi-Fi only, collection auto-sync, storage management, internal storage or an SD
    card as the download location (downloads move safely between them; a removed card's
    downloads come back when it is reinserted).
  - Offline mode. Downloaded music keeps playing without a gap when the network goes away
    mid-song, and downloaded albums, playlists and shows can be browsed and played offline.

## Layout

| Path | What |
|------|------|
| `app/` | Android app (Compose UI, Media3 `MediaLibraryService`, Room, WorkManager / user-initiated jobs) |
| `native/spotcore/` | Rust engine: session, Connect, catalog (Spotify internal APIs), downloads, JNI bridge |
| `native/vendor/` | Patched copies of librespot-core, -connect, -playback and -audio 0.8.0 (see `native/vendor/README.md`) |
| `docs/ARCHITECTURE.md` | Design, the Kotlin–Rust contract (RPC methods, events, JSON shapes), battery rules |

## Building

Requirements:
- JDK 21
- Android SDK with platform 37
- NDK 29.0.14206865 (the version pinned in `app/build.gradle.kts`)
- Rust 1.85 or newer with the Android targets, plus `cargo-ndk`

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./gradlew assembleDebug        # or assembleRelease (signed with the debug key)
```

Gradle runs `cargo ndk` itself (task `cargoBuild`) and packages `libspotcore.so`. Optional
Gradle properties:
- `-Pnative.abis=arm64-v8a`: build fewer ABIs (default `arm64-v8a,armeabi-v7a,x86_64`).
- `-Pnative.profile=dev`: faster unoptimised native builds.

Tests: `./gradlew :app:testDebugUnitTest` and
`cd native && cargo test -p spotcore -p librespot-connect -p librespot-playback -p librespot-audio`.
Some JVM tests run on Robolectric: the first test run downloads its Android runtime
(`org.robolectric:android-all-instrumented`, about 200 MB) from Maven Central's Google-hosted
mirror (`maven-central.storage-download.googleapis.com`) into `~/.m2/repository`, so it needs a
network; later runs use that copy and run offline.
Screenshots (Robolectric + Roborazzi, test dependencies only): `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest'` writes PNGs to `app/build/outputs/roborazzi` (build output, not committed); the plain test run skips them.

## Known limitations

- Spotify refuses audio keys for some accounts when used from librespot (librespot #1649).
  The app reports this as "playback refused" instead of failing silently, but cannot work
  around it.
- Spotify also limits how fast an account gets audio keys (librespot #1319): about 20–30 in
  a burst, then roughly one every 30 seconds, for streaming and downloads alike (Spotify's own
  apps use a key path librespot can't). So big downloads proceed at a measured pace: about 10
  songs at once, then a few songs every minute or two (about 100 an hour; a 100-song playlist
  takes roughly an hour), always leaving room for songs you stream, and the Downloads screen says
  when the next ones follow. If Spotify throttles anyway, downloads pause for 10 minutes to about
  an hour and continue by themselves, and a song that can't start right then stops playback with
  "Spotify is limiting how fast songs that aren't downloaded can start" instead of skipping
  through the queue.
- Catalog, search, home and library calls use Spotify's internal web/desktop endpoints,
  because the public Web API rate-limits this client id. These endpoints can change without
  notice. Search and home fall back to older endpoints and to a locally built feed.
- Smart shuffle is computed locally; other Connect clients see the suggestions as ordinary
  tracks.
- Downloads can only be played inside the app.
- While the phone controls another Connect device with a Bluetooth headset, speaker or car
  connected, its media session reads paused (otherwise a multipoint headset would pause that
  device). Android hides paused media controls from the lock screen and the first pull-down of
  quick settings after 10 minutes, so for remote playback they disappear 10 minutes after the
  session last read playing (sooner when the device was resumed after a pause). The fully
  expanded quick settings and the app keep working, and the controls come back when the phone
  itself plays, or when the Bluetooth device disconnects while the other device plays. Details in docs/ARCHITECTURE.md §9.4.
- In Android's output switcher, from Android 15, a device that starts playing while the app is in
  the background (started from another Spotify app) shows as "Other device" on the media chip until
  the app is opened or the switcher is opened from the volume panel: Android only lets the app's
  device list reach the system then. Speakers and Cast devices not yet signed in are not listed
  there (the app's device sheet signs them in). The integration follows the Android sources; it has
  not been tried on a phone yet (details in docs/ARCHITECTURE.md §8).
- Podcast progress made on this phone (offline above all) is kept on the phone and resumes
  there, but is not reported back to Spotify: other devices don't see it. The same goes for
  "Mark as played" / "Mark as unplayed". Spotify's own resume points are shown when its web
  player API provides them (best effort).
- Signing in a local-network speaker uses the Spotify Connect ZeroConf protocol as
  implemented by librespot. It is tested against librespot's own server code, not against
  commercial speakers.
- Google Cast support is implemented from the Cast protocol as open-source Cast clients use it
  (launching Spotify's Cast receiver and signing it in with an access token for the receiver's
  client id). It is tested against a fake receiver, not against real Cast hardware, and the
  receiver may refuse the token the app obtains for it; the device then shows an error and can
  still be started from the official Spotify app or Google Assistant.
- The app was developed without a device or a Spotify account: the JVM and Rust test
  suites run offline (with fakes for the CDN and the ZeroConf server; the JVM suite's first
  run downloads the Robolectric runtime, see Tests above), and nothing has yet been run
  against Spotify's live services.
