//! `download.track {uri, bitrate, dir, imageDir}` (docs/ARCHITECTURE.md §6.4, §9.7).
//!
//! 0. The session's country is needed for the availability check: it may arrive after the
//!    session was declared online, so the download waits for it (≤ 10 s, else `NOT_CONNECTED`,
//!    which Kotlin retries) instead of judging restrictions against an unknown country.
//! 1. Metadata (`Track` via TRACK_V4, or the raw `Episode` via EPISODE_V4), relinking through
//!    `alternatives` when the requested track has no available file; both the requested `uri`
//!    and the `playedUri` are recorded.
//! 2. Availability for the session's country/catalogue (embargo, restrictions), and the
//!    account's own explicit filter (Spotify's parental setting): explicit items are refused
//!    while it is on. Not "Hide explicit content": that app setting applies when downloads are
//!    shown and played, never to downloading them.
//! 3. File choice per bitrate with fallbacks (Ogg Vorbis 320/160/96, MP3; ≤ 160 kbps for
//!    non-Premium sessions).
//! 4. Audio key: reused from the offline index when the same file is registered, or from the
//!    keys this process received (a streamed track), otherwise requested when this file's
//!    download starts, paced by the key budget (`super::keys`): a pause longer than a few seconds
//!    returns `RATE_LIMITED` (context `keyPacing` / `keyThrottled`, `retryAfterMs`) without a
//!    request, checked once before step 1 too (no metadata requests while downloads wait). A
//!    refused file (0x0001, decided per track and context) is relinked once to an alternative
//!    with a file of its own; without one the song is `UNAVAILABLE` (context `keyRefused`), and
//!    the account's refusal (`PLAYBACK_REFUSED`) only as the key budget judges it.
//! 5. Existing `<dir>/<fileId>` that verifies → reused. Otherwise the encrypted file is
//!    downloaded into `<dir>/<fileId>.part` (resumable, see `super::fetch`), verified, its Ogg
//!    normalisation read, synced and renamed to `<dir>/<fileId>`.
//! 6. Cover (best effort): `<imageDir>/<imageId>.jpg`, the largest one ≤ 640 px.
//!
//! The record is returned, not registered: Kotlin persists it and calls `offline.add`.
//! Aborting the call leaves the `.part` for a later resume and emits `cancelled`, never
//! `completed`. Only one download per file id runs at a time. The chosen file id is remembered
//! per URI (`download.fileId`), so Kotlin can keep the `.part` of a failed download.

use super::convert::{self, check_availability};
use super::disk;
use super::fetch::{self, Policy};
use super::format::{self, file_id_hex};
use super::index;
use super::keys;
use super::progress::{self, Progress};
use super::transport::SessionTransport;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{self, OfflineTrackRecord};
use crate::{engine, rpc};
use bytes::Bytes;
use http::{Method, Request};
use librespot_core::audio_key::AudioKey;
use librespot_core::date::Date;
use librespot_core::{FileId, Session, SpotifyId, SpotifyUri};
use librespot_metadata::audio::AudioFileFormat;
use librespot_metadata::image::Images;
use librespot_metadata::{Episode, Metadata, Track};
use librespot_protocol::metadata as pm;
use parking_lot::Mutex;
use protobuf::Message;
use serde::Deserialize;
use serde_json::Value;
use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::num::NonZeroUsize;
use std::sync::{Arc, LazyLock, Weak};
use std::time::{Duration, Instant};

/// One metadata request (spclient retries internally).
const METADATA_TIMEOUT: Duration = Duration::from_secs(30);
/// Relinking candidates tried at most.
const MAX_ALTERNATIVES: usize = 8;
const COVER_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_COVER_BYTES: usize = 10 * 1024 * 1024;
/// How long a download waits for the session's country (CountryCode / ProductInfo).
const COUNTRY_TIMEOUT: Duration = Duration::from_secs(10);
const COUNTRY_POLL: Duration = Duration::from_millis(250);
/// URIs whose chosen file is remembered for `download.fileId`.
const CHOSEN_FILES: usize = 512;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadArgs {
    pub uri: String,
    #[serde(default = "default_bitrate")]
    pub bitrate: u32,
    pub dir: String,
    #[serde(default)]
    pub image_dir: Option<String>,
}

fn default_bitrate() -> u32 {
    160
}

pub async fn handle(args: Value) -> AppResult<Value> {
    let args: DownloadArgs = rpc::parse_args(args)?;
    let record = download_track(args).await?;
    rpc::to_value(&record)
}

#[derive(Debug, Deserialize)]
struct FileIdArgs {
    uri: String,
}

/// `download.fileId {uri}` → `{"fileId"}`, omitted when no download of `uri` chose a file in
/// this process.
pub fn handle_file_id(args: Value) -> AppResult<Value> {
    let args: FileIdArgs = rpc::parse_args(args)?;
    Ok(match chosen_file(args.uri.trim()) {
        Some(hex) => serde_json::json!({ "fileId": hex }),
        None => serde_json::json!({}),
    })
}

static CHOSEN: LazyLock<Mutex<lru::LruCache<String, String>>> =
    LazyLock::new(|| Mutex::new(lru::LruCache::new(NonZeroUsize::new(CHOSEN_FILES).unwrap_or(NonZeroUsize::MIN))));

/// Remembers that downloading `uri` writes `<fileId>(.part)`.
fn remember_file(uri: &str, file_hex: &str) {
    CHOSEN.lock().put(uri.to_owned(), file_hex.to_owned());
}

/// The file the last download of `uri` chose (lower-case hex), if any.
pub fn chosen_file(uri: &str) -> Option<String> {
    CHOSEN.lock().get(uri).cloned()
}

/// The session's country: the CountryCode packet, else the `country` user attribute.
fn session_country(session: &Session) -> Option<String> {
    Some(session.country()).filter(|c| !c.is_empty()).or_else(|| session.get_user_attribute("country").filter(|c| !c.is_empty()))
}

/// Polls `get` every `poll` until it yields a value, `gone` reports the session ended or
/// `timeout` passed.
async fn await_value<T>(mut get: impl FnMut() -> Option<T>, gone: impl Fn() -> bool, timeout: Duration, poll: Duration) -> Option<T> {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(v) = get() {
            return Some(v);
        }
        if gone() || Instant::now() >= deadline {
            return None;
        }
        tokio::time::sleep(poll).await;
    }
}

/// What was chosen for download.
struct Prepared {
    /// The item whose audio is downloaded (a relinked alternative or the requested item).
    played_id: SpotifyId,
    played_uri: Option<String>,
    format: AudioFileFormat,
    file_id: FileId,
    track: Option<models::Track>,
    episode: Option<models::Episode>,
    covers: Images,
    /// The album (or show) of the audio, for the key budget's judgement of refusals.
    group: u64,
    /// The requested track's metadata (relinking after a refused key).
    requested: Option<Track>,
}

/// A key for grouping refusals by album or show.
fn group_of(id: &impl Hash) -> u64 {
    let mut hasher = DefaultHasher::new();
    id.hash(&mut hasher);
    hasher.finish()
}

pub async fn download_track(args: DownloadArgs) -> AppResult<OfflineTrackRecord> {
    let uri_str = args.uri.trim().to_owned();
    let mut progress = Progress::new(uri_str.clone(), progress::to_kotlin());
    progress.preparing();
    match run(&uri_str, &args, &mut progress).await {
        Ok(record) => {
            progress.completed(record.size_bytes);
            Ok(record)
        }
        Err(e) => {
            if e.context.as_deref() == Some("keyPacing") {
                log::info!("download of {uri_str} waits {:?} ms for its audio-key turn", e.retry_after_ms);
            } else {
                log::warn!("download of {uri_str} failed: {e}");
            }
            progress.failed(&e);
            Err(e)
        }
    }
}

async fn run(uri_str: &str, args: &DownloadArgs, progress: &mut Progress) -> AppResult<OfflineTrackRecord> {
    let uri = match SpotifyUri::from_uri(uri_str) {
        Ok(u @ (SpotifyUri::Track { .. } | SpotifyUri::Episode { .. })) => u,
        _ => return Err(AppError::invalid(format!("not a track or episode URI: {uri_str}"))),
    };
    if args.dir.trim().is_empty() {
        return Err(AppError::invalid("dir is required"));
    }
    let session = engine::session()?;
    if session.is_invalid() {
        return Err(AppError::not_connected());
    }
    // Restrictions are per country: judging them before the country is known would fail the
    // item as "not available in your country" for good. NOT_CONNECTED is retried by Kotlin.
    let country = await_value(|| session_country(&session), || session.is_invalid(), COUNTRY_TIMEOUT, COUNTRY_POLL)
        .await
        .ok_or_else(|| AppError::new(ErrorCode::NotConnected, "The session has not reported its country yet"))?;

    // A queue that woke while downloads must wait for their key goes back at once, without the
    // metadata requests below.
    keys::check_turn()?;
    let account = Account::of(&session, country);
    let now = Date::now_utc();
    let mut prepared = prepare(&session, &uri, uri_str, args.bitrate, &account, &now).await?;
    remember_file(uri_str, &file_id_hex(&prepared.file_id));
    let key = match file_key(&session, &prepared).await {
        // Spotify decides per track and context (license-gated releases): another release of the
        // same recording may be granted, as an unavailable track is relinked.
        Err(e) if keys::is_refused_file(&e) => match relink_refused(&session, uri_str, &prepared, &account, args.bitrate, &now).await {
            Some(alt) => {
                log::info!("{uri_str}: its audio key was refused, relinked to {:?}", alt.played_uri);
                prepared = alt;
                remember_file(uri_str, &file_id_hex(&prepared.file_id));
                file_key(&session, &prepared).await?
            }
            None => return Err(e),
        },
        other => other?,
    };
    let file_hex = file_id_hex(&prepared.file_id);
    let _file_lock = lock_file(&file_hex).await;

    let dir = PathBuf::from(args.dir.trim());
    disk::create_dir(&dir).await?;
    let final_path = dir.join(&file_hex);
    let part_path = dir.join(format!("{file_hex}.part"));

    let verified = match disk::verify(&final_path, prepared.format, Some(key)).await? {
        Some(v) => {
            log::info!("{uri_str}: file {file_hex} already downloaded");
            // A leftover partial copy of a complete file is just wasted space.
            if let Err(e) = disk::remove(&part_path).await {
                log::warn!("{uri_str}: could not remove stale partial file: {e}");
            }
            v
        }
        None => {
            if disk::file_len(&final_path).await? > 0 {
                log::warn!("{uri_str}: existing file {file_hex} does not verify, downloading it again");
                disk::remove(&final_path).await?;
            }
            let transport = SessionTransport::new(session.clone(), prepared.file_id);
            fetch::download_part(&transport, &part_path, prepared.format, Some(key), &Policy::default(), progress).await?;
            let v = disk::verify(&part_path, prepared.format, Some(key))
                .await?
                .ok_or_else(|| AppError::unavailable("Downloaded audio failed verification"))?;
            disk::finalize(&part_path, &final_path).await?;
            v
        }
    };

    let image_path = match args.image_dir.as_deref().map(str::trim).filter(|d| !d.is_empty()) {
        Some(image_dir) => fetch_cover(&session, &prepared.covers, image_dir).await,
        None => None,
    };

    Ok(OfflineTrackRecord {
        uri: uri_str.to_owned(),
        played_uri: prepared.played_uri,
        file_id: file_hex,
        format: format::format_to_string(prepared.format),
        key_hex: format::key_hex(&key),
        path: final_path.to_string_lossy().into_owned(),
        size_bytes: verified.size,
        normalisation: verified.normalisation,
        track: prepared.track,
        episode: prepared.episode,
        image_path,
    })
}

// ---------------------------------------------------------------------------------------------
// Metadata, relinking, availability, file choice
// ---------------------------------------------------------------------------------------------

/// Session facts that decide availability and quality (docs §9.7).
struct Account {
    country: String,
    catalogue: String,
    /// The account's own explicit filter (a Family plan child, "Allow explicit content" off), not
    /// "Hide explicit content": explicit items are not downloaded for such an account. The app
    /// setting applies when downloads are shown and played, never to downloading them.
    account_filter: bool,
    cap_160: bool,
}

impl Account {
    fn of(session: &Session, country: String) -> Self {
        Self {
            country,
            catalogue: session.get_user_attribute("catalogue").unwrap_or_else(|| "premium".to_owned()),
            account_filter: crate::engine::explicit::account_filter(session),
            // Downloads are a Premium feature; if another product ever gets here, stay ≤ 160 kbps.
            cap_160: session.get_user_attribute("type").is_some_and(|t| t != "premium"),
        }
    }

    /// Refuses an explicit item while the account's own filter is on.
    fn check_explicit(&self, explicit: bool) -> AppResult<()> {
        if explicit && self.account_filter {
            return Err(AppError::unavailable("Explicit content is filtered for this account"));
        }
        Ok(())
    }
}

fn unavailable_reason(reason: librespot_metadata::availability::UnavailabilityReason) -> AppError {
    use librespot_metadata::availability::UnavailabilityReason as R;
    AppError::unavailable(match reason {
        R::Embargo => "Not released yet",
        R::Blacklisted | R::NotWhitelisted => "Not available in your country",
        R::NoData => "Not available",
    })
}

/// A track's chosen file, or why it cannot be downloaded.
fn track_choice(t: &Track, account: &Account, bitrate: u32, now: &Date) -> Result<(AudioFileFormat, FileId), AppError> {
    if t.duration <= 0 {
        return Err(AppError::unavailable("Not available (no duration)"));
    }
    check_availability(&t.availability, &t.restrictions, Some(&t.earliest_live_timestamp), &account.country, &account.catalogue, now)
        .map_err(unavailable_reason)?;
    format::choose_file(&t.files, bitrate, account.cap_160).ok_or_else(|| AppError::unavailable("No downloadable audio file"))
}

async fn get_track(session: &Session, uri: &SpotifyUri) -> AppResult<Track> {
    Ok(tokio::time::timeout(METADATA_TIMEOUT, Track::get(session, uri)).await??)
}

/// The key of `p`'s file: from the offline index when the file is registered, else requested
/// (paced; core's known keys first).
async fn file_key(session: &Session, p: &Prepared) -> AppResult<AudioKey> {
    match index::global().key_for_file(&file_id_hex(&p.file_id)) {
        Some(key) => Ok(key),
        None => keys::request_key(session, p.played_id, p.file_id, p.group).await,
    }
}

async fn prepare(session: &Session, uri: &SpotifyUri, uri_str: &str, bitrate: u32, account: &Account, now: &Date) -> AppResult<Prepared> {
    match uri {
        SpotifyUri::Episode { .. } => prepare_episode(session, uri, uri_str, bitrate, account, now).await,
        _ => {
            let track = get_track(session, uri).await?;
            let (played, played_uri, choice) = match track_choice(&track, account, bitrate, now) {
                Ok(choice) => (track.clone(), None, choice),
                Err(direct) => {
                    let mut found = None;
                    for alt in track.alternatives.iter().take(MAX_ALTERNATIVES) {
                        match get_track(session, alt).await {
                            Ok(t) => {
                                if let Ok(choice) = track_choice(&t, account, bitrate, now) {
                                    let alt_uri = alt.to_uri().ok();
                                    found = Some((t, alt_uri, choice));
                                    break;
                                }
                            }
                            Err(e) => log::debug!("relinking candidate failed: {e}"),
                        }
                    }
                    found.ok_or(direct)?
                }
            };
            if let Some(alt) = &played_uri {
                log::info!("{uri_str} is relinked to {alt}");
            }
            track_prepared(uri_str, &track, played, played_uri, choice, account)
        }
    }
}

/// What downloading `played` (the requested `track` or an alternative at `played_uri`) means.
fn track_prepared(
    uri_str: &str,
    track: &Track,
    played: Track,
    played_uri: Option<String>,
    (format, file_id): (AudioFileFormat, FileId),
    account: &Account,
) -> AppResult<Prepared> {
    account.check_explicit(played.is_explicit || track.is_explicit)?;
    let played_id = SpotifyId::try_from(&played.id).map_err(AppError::from)?;
    let covers = if played.album.covers.is_empty() { track.album.covers.clone() } else { played.album.covers.clone() };
    Ok(Prepared {
        played_id,
        played_uri: played_uri.filter(|u| u != uri_str),
        format,
        file_id,
        track: Some(convert::track_model(uri_str, track, &played)),
        episode: None,
        covers,
        group: group_of(&played.album.id),
        requested: Some(track.clone()),
    })
}

/// After Spotify refused the key of `refused`'s file (0x0001): the first relinking alternative of
/// the requested track with an available file of its own that was not refused (another release of
/// the recording; go-librespot #235 relinks refused tracks the same way). None for an episode or
/// a track without one.
async fn relink_refused(
    session: &Session,
    uri_str: &str,
    refused: &Prepared,
    account: &Account,
    bitrate: u32,
    now: &Date,
) -> Option<Prepared> {
    let requested = refused.requested.as_ref()?;
    for alt in requested.alternatives.iter().take(MAX_ALTERNATIVES) {
        if SpotifyId::try_from(alt).is_ok_and(|id| id == refused.played_id) {
            continue;
        }
        let t = match get_track(session, alt).await {
            Ok(t) => t,
            Err(e) => {
                log::debug!("relinking candidate failed: {e}");
                continue;
            }
        };
        let Ok(choice) = track_choice(&t, account, bitrate, now) else { continue };
        if choice.1 == refused.file_id || keys::was_refused(choice.1) {
            continue;
        }
        if let Ok(p) = track_prepared(uri_str, requested, t, alt.to_uri().ok(), choice, account) {
            return Some(p);
        }
    }
    None
}

async fn prepare_episode(
    session: &Session,
    uri: &SpotifyUri,
    uri_str: &str,
    bitrate: u32,
    account: &Account,
    now: &Date,
) -> AppResult<Prepared> {
    let raw = tokio::time::timeout(METADATA_TIMEOUT, <Episode as Metadata>::request(session, uri)).await??;
    let msg = pm::Episode::parse_from_bytes(&raw)?;
    let episode = Episode::parse(&msg, uri)?;
    if episode.duration <= 0 {
        return Err(AppError::unavailable("Not available (no duration)"));
    }
    check_availability(&episode.availability, &episode.restrictions, None, &account.country, &account.catalogue, now)
        .map_err(unavailable_reason)?;
    account.check_explicit(episode.is_explicit)?;
    let Some((fmt, file_id)) = format::choose_file(&episode.audio, bitrate, account.cap_160) else {
        return Err(AppError::unavailable(if episode.external_url.is_empty() {
            "No downloadable audio file"
        } else {
            "Episodes hosted outside Spotify cannot be downloaded"
        }));
    };
    let played_id = SpotifyId::try_from(uri).map_err(AppError::from)?;
    Ok(Prepared {
        played_id,
        played_uri: None,
        format: fmt,
        file_id,
        track: None,
        episode: Some(convert::episode_model(uri_str, &episode, &msg)),
        covers: convert::episode_cover_images(&episode, &msg),
        group: group_of(&episode.show_name),
        requested: None,
    })
}

// ---------------------------------------------------------------------------------------------
// Cover image (best effort)
// ---------------------------------------------------------------------------------------------

async fn fetch_cover(session: &Session, covers: &Images, image_dir: &str) -> Option<String> {
    let id = convert::pick_cover(covers)?;
    let hex = hex::encode(id.0);
    let dest = PathBuf::from(image_dir).join(format!("{hex}.jpg"));
    let dest_str = dest.to_string_lossy().into_owned();
    match disk::file_len(&dest).await {
        Ok(len) if len > 0 => return Some(dest_str),
        Ok(_) => {}
        Err(e) => {
            log::warn!("cover: {e}");
            return None;
        }
    }
    let url = session
        .get_user_attribute("image-url")
        .filter(|t| t.contains("{file_id}"))
        .unwrap_or_else(|| "https://i.scdn.co/image/{file_id}".to_owned())
        .replace("{file_id}", &hex);
    let req = Request::builder().method(Method::GET).uri(url.as_str()).body(Bytes::new()).ok()?;
    let body = match tokio::time::timeout(COVER_TIMEOUT, session.http_client().request_body(req)).await {
        Ok(Ok(body)) if !body.is_empty() && body.len() <= MAX_COVER_BYTES => body,
        Ok(Ok(body)) => {
            log::warn!("cover {hex}: unexpected size {}", body.len());
            return None;
        }
        Ok(Err(e)) => {
            log::warn!("cover {hex}: {e}");
            return None;
        }
        Err(_) => {
            log::warn!("cover {hex}: timed out");
            return None;
        }
    };
    match disk::write_atomic(&dest, body).await {
        Ok(()) => Some(dest_str),
        Err(e) => {
            log::warn!("cover {hex}: {e}");
            None
        }
    }
}

// ---------------------------------------------------------------------------------------------
// One download per file at a time
// ---------------------------------------------------------------------------------------------

type FileLock = Arc<tokio::sync::Mutex<()>>;

static FILE_LOCKS: LazyLock<Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// Serialises work on one `<fileId>(.part)` (two URIs can share a file, e.g. via relinking).
async fn lock_file(file_hex: &str) -> tokio::sync::OwnedMutexGuard<()> {
    let lock: FileLock = {
        let mut locks = FILE_LOCKS.lock();
        locks.retain(|_, w| w.strong_count() > 0);
        match locks.get(file_hex).and_then(Weak::upgrade) {
            Some(lock) => lock,
            None => {
                let lock = FileLock::default();
                locks.insert(file_hex.to_owned(), Arc::downgrade(&lock));
                lock
            }
        }
    };
    lock.lock_owned().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    #[test]
    fn explicit_items_are_refused_only_on_the_accounts_own_filter() {
        let account = |account_filter| Account { country: "SE".into(), catalogue: "premium".into(), account_filter, cap_160: false };
        // "Hide explicit content" never reaches the downloader: only the account's filter counts.
        assert!(account(false).check_explicit(true).is_ok());
        assert!(account(true).check_explicit(false).is_ok());
        let refused = account(true).check_explicit(true).unwrap_err();
        assert_eq!(refused.code, crate::error::ErrorCode::Unavailable);
    }

    #[tokio::test]
    async fn file_lock_serialises_same_file() {
        let a = lock_file("aa").await;
        let other = tokio::time::timeout(Duration::from_millis(20), lock_file("bb")).await;
        assert!(other.is_ok(), "different files do not block");
        let same = tokio::time::timeout(Duration::from_millis(20), lock_file("aa")).await;
        assert!(same.is_err(), "same file blocks");
        drop(a);
        assert!(tokio::time::timeout(Duration::from_millis(200), lock_file("aa")).await.is_ok());
    }

    #[tokio::test]
    async fn waits_for_a_late_country() {
        let polls = AtomicUsize::new(0);
        let late = || (polls.fetch_add(1, Ordering::SeqCst) >= 3).then(|| "SE".to_owned());
        let got = await_value(late, || false, Duration::from_secs(5), Duration::from_millis(1)).await;
        assert_eq!(got.as_deref(), Some("SE"));
        assert_eq!(polls.load(Ordering::SeqCst), 4);

        let started = Instant::now();
        let never = await_value(|| None::<String>, || false, Duration::from_millis(30), Duration::from_millis(5)).await;
        assert!(never.is_none() && started.elapsed() >= Duration::from_millis(30), "times out");

        let gone = AtomicBool::new(false);
        let started = Instant::now();
        let ended = await_value(
            || {
                gone.store(true, Ordering::SeqCst);
                None::<String>
            },
            || gone.load(Ordering::SeqCst),
            Duration::from_secs(10),
            Duration::from_millis(5),
        )
        .await;
        assert!(ended.is_none() && started.elapsed() < Duration::from_secs(5), "stops when the session ends");
    }

    #[test]
    fn remembers_the_chosen_file_per_uri() {
        let rpc = |uri: &str| handle_file_id(serde_json::json!({ "uri": uri })).expect("fileId");
        assert_eq!(rpc("spotify:track:0000000000000000000077"), serde_json::json!({}));
        remember_file("spotify:track:0000000000000000000077", &"ab".repeat(20));
        remember_file("spotify:track:0000000000000000000077", &"cd".repeat(20));
        assert_eq!(rpc(" spotify:track:0000000000000000000077 "), serde_json::json!({ "fileId": "cd".repeat(20) }), "the latest choice");
        assert!(handle_file_id(serde_json::json!({})).is_err());
    }

    #[test]
    fn args_parsing() {
        let a: DownloadArgs =
            serde_json::from_value(serde_json::json!({"uri":"spotify:track:x","dir":"/d","imageDir":"/i"})).expect("args");
        assert_eq!(a.bitrate, 160);
        assert_eq!(a.image_dir.as_deref(), Some("/i"));
        assert!(serde_json::from_value::<DownloadArgs>(serde_json::json!({"uri":"x"})).is_err(), "dir required");
    }

    #[tokio::test]
    async fn rejects_bad_arguments_and_reports_failure() {
        // No session in unit tests: argument errors come first, then NOT_CONNECTED.
        let err = download_track(DownloadArgs { uri: "spotify:album:6akEvsycLGftJxYudPjmqK".into(), bitrate: 160, dir: "/tmp".into(), image_dir: None })
            .await
            .expect_err("album");
        assert_eq!(err.code, ErrorCode::InvalidArgument);
        let err = download_track(DownloadArgs { uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC".into(), bitrate: 160, dir: " ".into(), image_dir: None })
            .await
            .expect_err("dir");
        assert_eq!(err.code, ErrorCode::InvalidArgument);
        let err = download_track(DownloadArgs { uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC".into(), bitrate: 160, dir: "/tmp".into(), image_dir: None })
            .await
            .expect_err("no session");
        assert_eq!(err.code, ErrorCode::NotConnected);
    }
}
