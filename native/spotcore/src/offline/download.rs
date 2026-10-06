//! `download.track {uri, bitrate, dir, imageDir}` (docs/ARCHITECTURE.md §6.4, §9.7).
//!
//! 1. Metadata (`Track` via TRACK_V4, or the raw `Episode` via EPISODE_V4), relinking through
//!    `alternatives` when the requested track has no available file; both the requested `uri`
//!    and the `playedUri` are recorded.
//! 2. Availability for the session's country/catalogue (embargo, restrictions) and the explicit
//!    filter.
//! 3. File choice per bitrate with fallbacks (Ogg Vorbis 320/160/96, MP3; ≤ 160 kbps for
//!    non-Premium sessions).
//! 4. Audio key: reused from the offline index when the same file is registered, otherwise
//!    requested (serialised, retried; permanent denial → `PLAYBACK_REFUSED`).
//! 5. Existing `<dir>/<fileId>` that verifies → reused. Otherwise the encrypted file is
//!    downloaded into `<dir>/<fileId>.part` (resumable, see `super::fetch`), verified, its Ogg
//!    normalisation read, synced and renamed to `<dir>/<fileId>`.
//! 6. Cover (best effort): `<imageDir>/<imageId>.jpg`, the largest one ≤ 640 px.
//!
//! The record is returned, not registered: Kotlin persists it and calls `offline.add`.
//! Aborting the call leaves the `.part` for a later resume and emits `cancelled`, never
//! `completed`. Only one download per file id runs at a time.

use super::convert::{self, check_availability};
use super::disk;
use super::fetch::{self, Policy};
use super::format::{self, file_id_hex};
use super::index;
use super::keys;
use super::progress::{self, Progress};
use super::transport::SessionTransport;
use crate::error::{AppError, AppResult};
use crate::models::{self, OfflineTrackRecord};
use crate::{engine, rpc};
use bytes::Bytes;
use http::{Method, Request};
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
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Weak};
use std::time::Duration;

/// One metadata request (spclient retries internally).
const METADATA_TIMEOUT: Duration = Duration::from_secs(30);
/// Relinking candidates tried at most.
const MAX_ALTERNATIVES: usize = 8;
const COVER_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_COVER_BYTES: usize = 10 * 1024 * 1024;

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
            log::warn!("download of {uri_str} failed: {e}");
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

    let prepared = prepare(&session, &uri, uri_str, args.bitrate).await?;
    let file_hex = file_id_hex(&prepared.file_id);
    let _file_lock = lock_file(&file_hex).await;

    let dir = PathBuf::from(args.dir.trim());
    disk::create_dir(&dir).await?;
    let final_path = dir.join(&file_hex);
    let part_path = dir.join(format!("{file_hex}.part"));

    let key = match index::global().key_for_file(&file_hex) {
        Some(key) => key,
        None => keys::request_key(&session, prepared.played_id, prepared.file_id).await?,
    };

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

/// Session facts that decide availability and quality.
struct Account {
    country: String,
    catalogue: String,
    filter_explicit: bool,
    cap_160: bool,
}

impl Account {
    fn of(session: &Session) -> Self {
        Self {
            country: session.country(),
            catalogue: session.get_user_attribute("catalogue").unwrap_or_else(|| "premium".to_owned()),
            filter_explicit: session.filter_explicit_content(),
            // Downloads are a Premium feature; if another product ever gets here, stay ≤ 160 kbps.
            cap_160: session.get_user_attribute("type").is_some_and(|t| t != "premium"),
        }
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

async fn prepare(session: &Session, uri: &SpotifyUri, uri_str: &str, bitrate: u32) -> AppResult<Prepared> {
    let account = Account::of(session);
    let now = Date::now_utc();
    match uri {
        SpotifyUri::Episode { .. } => prepare_episode(session, uri, uri_str, bitrate, &account, &now).await,
        _ => {
            let track = get_track(session, uri).await?;
            let (played, played_uri, (fmt, file_id)) = match track_choice(&track, &account, bitrate, &now) {
                Ok(choice) => (track.clone(), None, choice),
                Err(direct) => {
                    let mut found = None;
                    for alt in track.alternatives.iter().take(MAX_ALTERNATIVES) {
                        match get_track(session, alt).await {
                            Ok(t) => {
                                if let Ok(choice) = track_choice(&t, &account, bitrate, &now) {
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
            if played.is_explicit && account.filter_explicit {
                return Err(AppError::unavailable("Explicit content is filtered for this account"));
            }
            if let Some(alt) = &played_uri {
                log::info!("{uri_str} is relinked to {alt}");
            }
            let played_id = SpotifyId::try_from(&played.id).map_err(AppError::from)?;
            let covers = if played.album.covers.is_empty() { track.album.covers.clone() } else { played.album.covers.clone() };
            Ok(Prepared {
                played_id,
                played_uri: played_uri.filter(|u| u != uri_str),
                format: fmt,
                file_id,
                track: Some(convert::track_model(uri_str, &track, &played)),
                episode: None,
                covers,
            })
        }
    }
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
    if episode.is_explicit && account.filter_explicit {
        return Err(AppError::unavailable("Explicit content is filtered for this account"));
    }
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
    use crate::error::ErrorCode;

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
