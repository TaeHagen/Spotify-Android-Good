//! Downloads (encrypted, resumable) and the offline index consulted by the patched librespot
//! Player (docs/ARCHITECTURE.md §4.5, §6.4, §9.7).
//!
//! Public API used by other modules (stable contract): [`is_downloaded`], [`downloaded_record`],
//! [`all_records`], [`source`], [`handle`].
//!
//! RPCs:
//! * `download.track {uri, bitrate, dir, imageDir}` → `OfflineTrackRecord` (see [`download`]).
//!   The record is *not* registered; Kotlin persists it and then calls `offline.add`. Its audio
//!   key is paced by the process's key budget (see [`keys`], [`key_budget`]).
//! * `download.fileId {uri}` → `{"fileId"}` (omitted when unknown): the file the last
//!   `download.track` of `uri` in this process chose, also when it failed or was cancelled, so
//!   Kotlin knows which `<fileId>.part` belongs to an unfinished download.
//! * `offline.setIndex {tracks:[OfflineTrackRecord], seq?}` replaces the index;
//!   `offline.add {tracks, seq?}` adds/replaces by `uri`; `offline.remove {uris, seq?}`
//!   unregisters by `uri` (only: a record reachable through its `playedUri` is not removed).
//!   `offline.beginIndex {seq}` announces that the next `setIndex` without `seq` is a snapshot
//!   taken after change `seq`. `seq` numbers Kotlin's changes so that they apply in order
//!   whatever order the calls arrive in (see [`index`]). Invalid records are skipped and reported
//!   as `{"rejected":[uri…]}` (omitted when none).
//!
//! **Files are owned by Kotlin**: `offline.remove` and `offline.setIndex` never delete anything.
//! `DownloadManager` deletes `<dir>/<fileId>` when no remaining download uses it, and collects
//! `.part` files and covers no download refers to; everything goes when the user logs out.
//!
//! Record format (`OfflineTrackRecord`): `fileId` 40 hex chars; `keyHex` 32 hex chars (empty =
//! unencrypted); `format` = `format!("{:?}", AudioFileFormat)` e.g. `"OGG_VORBIS_320"`, `"MP3_96"`
//! (only Ogg Vorbis and MP3 are accepted); `track` for track URIs, `episode` for episode URIs;
//! `sizeBytes` must match the file (0 = unchecked). The key is secret and never logged.

mod convert;
mod disk;
mod download;
mod fetch;
pub mod format;
mod index;
mod key_budget;
mod keys;
mod progress;
mod transport;

#[cfg(test)]
mod e2e_tests;

use crate::error::{AppError, AppResult};
use crate::models::OfflineTrackRecord;
use crate::rpc;
use librespot_playback::offline::OfflineSourceRef;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

/// True if `uri` (requested or played URI) has a complete download registered in the offline
/// index whose file was present at the last check. No disk access.
pub fn is_downloaded(uri: &str) -> bool {
    index::global().is_downloaded(uri)
}

/// The registered download for `uri`, if any (see [`is_downloaded`]).
pub fn downloaded_record(uri: &str) -> Option<OfflineTrackRecord> {
    index::global().downloaded_record(uri)
}

/// All registered downloads whose file is present (offline queue building), sorted by `uri`.
pub fn all_records() -> Vec<OfflineTrackRecord> {
    index::global().all_records()
}

/// The offline source installed on the Player (`Player::set_offline_source`). Lookups are
/// synchronous, in-memory and cheap (called on the librespot loader thread); it reflects later
/// index changes.
pub fn source() -> OfflineSourceRef {
    Arc::new(index::IndexSource(index::global().clone()))
}

/// Lets the downloads' audio-key budget see every key request of the process, the player's
/// included (docs/ARCHITECTURE.md §9.7). Idempotent; called before a Player is created.
pub(crate) fn observe_audio_keys() {
    keys::observe_audio_keys();
}

/// Logout or another account: the keys received so far and the key budget are forgotten.
pub(crate) fn forget_audio_keys() {
    keys::forget_account();
}

/// How long the player should wait before asking for a key again after Spotify throttled keys.
pub(crate) fn key_retry_after() -> std::time::Duration {
    keys::playback_retry_after()
}

pub async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "download.track" => download::handle(args).await,
        "download.fileId" => download::handle_file_id(args),
        "offline.setIndex" => register(args, true).await,
        "offline.add" => register(args, false).await,
        "offline.remove" => remove(args),
        "offline.beginIndex" => begin_index(args),
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

/// Splits `{"tracks":[…]}` into well-formed records and the URIs (or indices) of malformed ones.
fn parse_records(args: &Value) -> AppResult<(Vec<OfflineTrackRecord>, Vec<String>)> {
    let Some(items) = args.get("tracks").and_then(Value::as_array) else {
        return Err(AppError::invalid("tracks must be an array"));
    };
    let mut records = Vec::with_capacity(items.len());
    let mut rejected = Vec::new();
    for (i, item) in items.iter().enumerate() {
        match OfflineTrackRecord::deserialize(item) {
            Ok(r) => records.push(r),
            Err(e) => {
                let id = item.get("uri").and_then(Value::as_str).map_or_else(|| format!("#{i}"), str::to_owned);
                log::warn!("offline index: malformed record {id}: {e}");
                rejected.push(id);
            }
        }
    }
    Ok((records, rejected))
}

/// The optional change number `seq` of an index RPC.
fn parse_seq(args: &Value) -> AppResult<Option<u64>> {
    match args.get("seq") {
        None | Some(Value::Null) => Ok(None),
        Some(v) => v.as_u64().map(Some).ok_or_else(|| AppError::invalid("seq must be a non-negative integer")),
    }
}

async fn register(args: Value, replace: bool) -> AppResult<Value> {
    let seq = parse_seq(&args)?;
    let (records, mut rejected) = parse_records(&args)?;
    // Validation is cheap, but checking the files is disk I/O: keep it off the async workers.
    // Calls may overtake each other here; `seq` orders their effect (see `index`).
    let (entries, invalid) = disk::blocking(move || Ok(index::build_entries(records))).await?;
    rejected.extend(invalid);
    let idx = index::global();
    if replace {
        idx.replace(entries, seq);
    } else {
        let offered = entries.len();
        let applied = idx.add(entries, seq);
        if applied < offered {
            log::info!("offline index: skipped {} records superseded by newer changes", offered - applied);
        }
    }
    log::info!("offline index: {} downloads ({} rejected)", idx.len(), rejected.len());
    Ok(if rejected.is_empty() { json!({}) } else { json!({ "rejected": rejected }) })
}

#[derive(Deserialize)]
struct RemoveArgs {
    uris: Vec<String>,
    #[serde(default)]
    seq: Option<u64>,
}

fn remove(args: Value) -> AppResult<Value> {
    let args: RemoveArgs = rpc::parse_args(args)?;
    let removed = index::global().remove(&args.uris, args.seq);
    log::info!("offline index: removed {removed} downloads");
    rpc::ok()
}

#[derive(Deserialize)]
struct BeginIndexArgs {
    seq: u64,
}

fn begin_index(args: Value) -> AppResult<Value> {
    let args: BeginIndexArgs = rpc::parse_args(args)?;
    index::global().announce_snapshot(args.seq);
    rpc::ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline::index::tests::{record, scratch_dir, ALT_URI, TRACK_URI};

    /// The global index is shared by every test in the process: only this test uses it.
    #[tokio::test]
    async fn rpc_set_add_remove_against_global_index() {
        let dir = scratch_dir("rpc");
        let file = dir.join("audio");
        std::fs::write(&file, b"0123456789").expect("write");
        let mut rec = record(TRACK_URI, &file, 10);
        rec.played_uri = Some(ALT_URI.into());

        let args = json!({"tracks": [rec.clone(), {"uri": "spotify:track:broken"}, {"nope": 1}]});
        let out = handle("offline.setIndex", args).await.expect("setIndex");
        assert_eq!(out, json!({"rejected": ["spotify:track:broken", "#2"]}));
        assert!(is_downloaded(TRACK_URI) && is_downloaded(ALT_URI));
        assert_eq!(downloaded_record(ALT_URI).map(|r| r.uri), Some(TRACK_URI.to_owned()));
        assert_eq!(all_records().len(), 1);
        let src = source();
        let looked_up = src.lookup(&librespot_core::SpotifyUri::from_uri(TRACK_URI).expect("uri"));
        assert!(looked_up.is_some());

        let other = record("spotify:track:0000000000000000000001", &file, 10);
        assert_eq!(handle("offline.add", json!({"tracks": [other]})).await.expect("add"), json!({}));
        assert_eq!(all_records().len(), 2);

        assert_eq!(handle("offline.remove", json!({"uris": [TRACK_URI]})).await.expect("remove"), json!({}));
        assert!(!is_downloaded(TRACK_URI) && !is_downloaded(ALT_URI));
        assert!(file.exists(), "files are Kotlin's");
        assert_eq!(all_records().len(), 1);

        assert!(handle("offline.setIndex", json!({})).await.is_err());
        assert!(handle("offline.remove", json!({"uris": "x"})).await.is_err());
        assert!(handle("offline.add", json!({"tracks": [], "seq": -1})).await.is_err());
        assert!(handle("offline.beginIndex", json!({})).await.is_err());
        assert!(handle("offline.nope", json!({})).await.is_err());
        handle("offline.setIndex", json!({"tracks": []})).await.expect("clear");
        assert!(all_records().is_empty());

        // Numbered changes: a snapshot announced at 2 and applied after changes 3 and 4 keeps them.
        let a = record("spotify:track:0000000000000000000002", &file, 10);
        let b = record("spotify:track:0000000000000000000003", &file, 10);
        handle("offline.beginIndex", json!({"seq": 2})).await.expect("begin");
        handle("offline.add", json!({"tracks": [a.clone()], "seq": 3})).await.expect("add");
        handle("offline.remove", json!({"uris": [b.uri.clone()], "seq": 4})).await.expect("remove");
        handle("offline.setIndex", json!({"tracks": [b.clone()]})).await.expect("snapshot");
        assert_eq!(all_records().into_iter().map(|r| r.uri).collect::<Vec<_>>(), vec![a.uri.clone()]);
        // Stale duplicates are ignored.
        handle("offline.add", json!({"tracks": [b], "seq": 1})).await.expect("stale add");
        handle("offline.remove", json!({"uris": [a.uri.clone()], "seq": 2})).await.expect("stale remove");
        assert_eq!(all_records().into_iter().map(|r| r.uri).collect::<Vec<_>>(), vec![a.uri]);
        handle("offline.setIndex", json!({"tracks": [], "seq": 5})).await.expect("clear");
        assert!(all_records().is_empty());
        let _ = std::fs::remove_dir_all(dir);
    }
}
