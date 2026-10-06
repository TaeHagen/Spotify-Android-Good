//! The user's library through spclient `collection/v2` (`library.*`).
//!
//! Sets: `collection` (tracks + albums), `artist` (followed artists), `show` (saved podcasts),
//! `listenlater` (Your Episodes). The JSON API is the one the live web player uses
//! (`POST /collection/v2/paging|contains|write` with `{username,set,items}`); protobuf
//! (`collection2v2.proto`, compiled in build.rs) is the fallback encoding. Playlists are routed
//! to the rootlist (follow/unfollow).
//!
//! Lists are served from an in-memory snapshot of each set (refreshed after 60 s, patched on
//! writes), so paging, totals and the track/album split are exact and cheap.

use super::context;
use super::http::{self, HttpError, JSON};
use super::metadata;
use super::playlist;
use super::proto::collection2v2 as c2;
use super::util::{now_ms, parse_uri, ParsedUri, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{AlbumRef, ArtistRef, Episode, ShowRef, Track};
use crate::rpc::{parse_args, to_value};
use ::http::Method;
use librespot_core::Session;
use parking_lot::Mutex;
use protobuf::Message;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

const PROTO_CT: &str = "application/vnd.collection-v2.spotify.proto";
const MAX_PAGES: usize = 200;
const LIST_MAX_AGE: Duration = Duration::from_secs(60);
const CONTAINS_FALLBACK_AGE: Duration = Duration::from_secs(300);
const MAX_LIMIT: u32 = 500;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum Set {
    Collection,
    Artist,
    Show,
    ListenLater,
}

impl Set {
    pub(crate) fn name(self) -> &'static str {
        match self {
            Set::Collection => "collection",
            Set::Artist => "artist",
            Set::Show => "show",
            Set::ListenLater => "listenlater",
        }
    }

    const ALL: [Set; 4] = [Set::Collection, Set::Artist, Set::Show, Set::ListenLater];
}

/// Where a URI is saved: a collection set, the rootlist (playlists) or nowhere.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Target {
    Set(Set, String),
    Rootlist(String),
}

pub(crate) fn target_for(uri: &str) -> Option<Target> {
    let p: ParsedUri = parse_uri(uri)?;
    let set = match p.kind {
        UriKind::Track | UriKind::Album => Set::Collection,
        UriKind::Artist => Set::Artist,
        UriKind::Show => Set::Show,
        UriKind::Episode => Set::ListenLater,
        UriKind::Playlist => return Some(Target::Rootlist(p.uri())),
    };
    Some(Target::Set(set, p.uri()))
}

// ---------------------------------------------------------------------------------------------
// Wire formats
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CollItem {
    pub uri: String,
    /// Seconds since epoch.
    pub added_at: i64,
}

fn lenient_i64<'de, D: Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Value::String(s) => s.trim().parse().unwrap_or(0),
        _ => 0,
    })
}

fn lenient_bool<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Bool(b) => b,
        Value::String(s) => s == "true",
        Value::Number(n) => n.as_i64().unwrap_or(0) != 0,
        _ => false,
    })
}

#[derive(Debug, Deserialize, Default)]
struct JsonItem {
    #[serde(default)]
    uri: String,
    #[serde(default, alias = "addedAt", deserialize_with = "lenient_i64")]
    added_at: i64,
    #[serde(default, alias = "isRemoved", deserialize_with = "lenient_bool")]
    is_removed: bool,
}

#[derive(Debug, Deserialize, Default)]
struct JsonPage {
    #[serde(default)]
    items: Vec<JsonItem>,
    #[serde(default, alias = "nextPageToken")]
    next_page_token: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
struct JsonContains {
    #[serde(default)]
    found: Vec<Value>,
}

pub(crate) fn parse_json_page(body: &[u8]) -> AppResult<(Vec<CollItem>, Option<String>)> {
    let page: JsonPage = serde_json::from_slice(body)
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("collection page: {e}")))?;
    let items = page
        .items
        .into_iter()
        .filter(|i| !i.is_removed && !i.uri.is_empty())
        .map(|i| CollItem { uri: i.uri, added_at: i.added_at })
        .collect();
    Ok((items, page.next_page_token.filter(|t| !t.is_empty())))
}

pub(crate) fn parse_proto_page(body: &[u8]) -> AppResult<(Vec<CollItem>, Option<String>)> {
    let page: c2::PageResponse = http::proto(body)?;
    let items = page
        .items
        .into_iter()
        .filter(|i| !i.is_removed && !i.uri.is_empty())
        .map(|i| CollItem { uri: i.uri, added_at: i.added_at as i64 })
        .collect();
    Ok((items, Some(page.next_page_token).filter(|t| !t.is_empty())))
}

pub(crate) fn parse_contains(body: &[u8], n: usize) -> AppResult<Vec<bool>> {
    let c: JsonContains = serde_json::from_slice(body)
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("collection contains: {e}")))?;
    Ok((0..n).map(|i| c.found.get(i).is_some_and(|v| v.as_bool().unwrap_or(false))).collect())
}

fn username(session: &Session) -> AppResult<String> {
    let u = engine::username().unwrap_or_else(|| session.username());
    if u.is_empty() {
        Err(AppError::new(ErrorCode::NotLoggedIn, "no username"))
    } else {
        Ok(u)
    }
}

/// Encoding mismatch (worth retrying with the other encoding) vs. a real failure.
fn encoding_rejected(e: &HttpError) -> bool {
    matches!(e.status, Some(400) | Some(406) | Some(415)) || e.error.code == ErrorCode::Unavailable && e.status.is_none()
}

async fn fetch_set(session: &Session, user: &str, set: Set) -> AppResult<Vec<CollItem>> {
    match fetch_set_json(session, user, set).await {
        Ok(v) => Ok(v),
        Err(e) if encoding_rejected(&e) => {
            log::info!("collection {} JSON paging failed ({}), trying protobuf", set.name(), e.error);
            fetch_set_proto(session, user, set).await
        }
        Err(e) => Err(e.into()),
    }
}

async fn fetch_set_json(session: &Session, user: &str, set: Set) -> Result<Vec<CollItem>, HttpError> {
    let mut out = Vec::new();
    let mut token: Option<String> = None;
    for _ in 0..MAX_PAGES {
        let mut body = json!({ "username": user, "set": set.name() });
        if let Some(t) = &token {
            body["pagination_token"] = json!(t);
        }
        let resp = http::spc_post_idempotent(session, "/collection/v2/paging", JSON, JSON, body.to_string().as_bytes())
            .await?;
        let (items, next) = parse_json_page(&resp).map_err(|error| HttpError { status: None, error })?;
        out.extend(items);
        match next {
            Some(n) if token.as_deref() != Some(n.as_str()) => token = Some(n),
            _ => break,
        }
    }
    Ok(out)
}

async fn fetch_set_proto(session: &Session, user: &str, set: Set) -> AppResult<Vec<CollItem>> {
    let mut out = Vec::new();
    let mut token = String::new();
    for _ in 0..MAX_PAGES {
        let mut req = c2::PageRequest::new();
        req.username = user.to_string();
        req.set = set.name().to_string();
        req.pagination_token = token.clone();
        req.limit = 300;
        let resp =
            http::spc_post_idempotent(session, "/collection/v2/paging", PROTO_CT, PROTO_CT, &req.write_to_bytes()?).await?;
        let (items, next) = parse_proto_page(&resp)?;
        out.extend(items);
        match next {
            Some(n) if n != token => token = n,
            _ => break,
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// Snapshots
// ---------------------------------------------------------------------------------------------

struct Snapshot {
    items: Arc<Vec<CollItem>>,
    at: Instant,
}

static SNAPSHOTS: LazyLock<Mutex<HashMap<Set, Snapshot>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static LOADS: LazyLock<HashMap<Set, tokio::sync::Mutex<()>>> =
    LazyLock::new(|| Set::ALL.iter().map(|s| (*s, tokio::sync::Mutex::new(()))).collect());

fn cached(set: Set, max_age: Duration) -> Option<Arc<Vec<CollItem>>> {
    SNAPSHOTS.lock().get(&set).filter(|s| s.at.elapsed() < max_age).map(|s| s.items.clone())
}

/// Sorted (newest first) contents of `set`, at most `max_age` old.
pub(crate) async fn snapshot(session: &Session, set: Set, max_age: Duration) -> AppResult<Arc<Vec<CollItem>>> {
    if let Some(s) = cached(set, max_age) {
        return Ok(s);
    }
    let _guard = match LOADS.get(&set) {
        Some(m) => Some(m.lock().await),
        None => None,
    };
    if let Some(s) = cached(set, max_age) {
        return Ok(s);
    }
    let user = username(session)?;
    let mut items = fetch_set(session, &user, set).await?;
    sort_items(&mut items);
    let items = Arc::new(items);
    SNAPSHOTS.lock().insert(set, Snapshot { items: items.clone(), at: Instant::now() });
    Ok(items)
}

pub(crate) fn sort_items(items: &mut Vec<CollItem>) {
    let mut seen = HashSet::new();
    items.retain(|i| seen.insert(i.uri.clone()));
    items.sort_by_key(|i| std::cmp::Reverse(i.added_at));
}

fn patch_snapshot(set: Set, uris: &[String], removed: bool) {
    let mut snaps = SNAPSHOTS.lock();
    if let Some(s) = snaps.get_mut(&set) {
        let mut items: Vec<CollItem> = s.items.iter().filter(|i| !uris.contains(&i.uri)).cloned().collect();
        if !removed {
            let now = now_ms() / 1000;
            let mut added: Vec<CollItem> = uris.iter().map(|u| CollItem { uri: u.clone(), added_at: now }).collect();
            added.extend(items);
            items = added;
        }
        s.items = Arc::new(items);
    }
}

// ---------------------------------------------------------------------------------------------
// contains / save / remove
// ---------------------------------------------------------------------------------------------

async fn contains_set(session: &Session, user: &str, set: Set, uris: &[String]) -> AppResult<Vec<bool>> {
    let mut out = Vec::with_capacity(uris.len());
    for chunk in uris.chunks(200) {
        let body = json!({
            "username": user,
            "set": set.name(),
            "items": chunk.iter().map(|u| json!({ "uri": u })).collect::<Vec<_>>(),
        });
        let res = http::spc_post_idempotent(session, "/collection/v2/contains", JSON, JSON, body.to_string().as_bytes())
            .await
            .map_err(AppError::from)
            .and_then(|b| parse_contains(&b, chunk.len()));
        match res {
            Ok(found) => out.extend(found),
            Err(e) => {
                log::info!("collection contains failed ({e}); using the set snapshot");
                let snap = match cached(set, CONTAINS_FALLBACK_AGE) {
                    Some(s) => s,
                    None if e.code == ErrorCode::Network => return Err(e),
                    None => snapshot(session, set, CONTAINS_FALLBACK_AGE).await?,
                };
                let have: HashSet<&str> = snap.iter().map(|i| i.uri.as_str()).collect();
                out.extend(chunk.iter().map(|u| have.contains(u.as_str())));
            }
        }
    }
    Ok(out)
}

pub(crate) async fn contains(session: &Session, uris: &[String]) -> AppResult<Vec<bool>> {
    let user = username(session)?;
    let mut result = vec![false; uris.len()];
    let mut by_set: HashMap<Set, Vec<(usize, String)>> = HashMap::new();
    let mut playlists: Vec<(usize, String)> = Vec::new();
    for (i, uri) in uris.iter().enumerate() {
        match target_for(uri) {
            Some(Target::Set(set, u)) => by_set.entry(set).or_default().push((i, u)),
            Some(Target::Rootlist(u)) => playlists.push((i, u)),
            None => {}
        }
    }
    for (set, entries) in by_set {
        let list: Vec<String> = entries.iter().map(|(_, u)| u.clone()).collect();
        let found = contains_set(session, &user, set, &list).await?;
        for ((i, _), f) in entries.iter().zip(found) {
            result[*i] = f;
        }
    }
    if !playlists.is_empty() {
        let list: Vec<String> = playlists.iter().map(|(_, u)| u.clone()).collect();
        let found = playlist::rootlist_contains(session, &list).await?;
        for ((i, _), f) in playlists.iter().zip(found) {
            result[*i] = f;
        }
    }
    Ok(result)
}

async fn write_set(session: &Session, user: &str, set: Set, uris: &[String], remove: bool) -> AppResult<()> {
    for chunk in uris.chunks(100) {
        let items: Vec<Value> =
            chunk.iter().map(|u| if remove { json!({ "uri": u, "is_removed": true }) } else { json!({ "uri": u }) }).collect();
        let body = json!({ "username": user, "set": set.name(), "items": items });
        let res = http::spc_send_once(
            session,
            Method::POST,
            "/collection/v2/write",
            JSON,
            JSON,
            body.to_string().into_bytes(),
        )
        .await;
        match res {
            Ok(_) => {}
            Err(e) if matches!(e.status, Some(400) | Some(406) | Some(415)) => {
                log::info!("collection write JSON rejected ({:?}), trying protobuf", e.status);
                let mut req = c2::WriteRequest::new();
                req.username = user.to_string();
                req.set = set.name().to_string();
                let now = (now_ms() / 1000) as i32;
                req.items = chunk
                    .iter()
                    .map(|u| {
                        let mut i = c2::CollectionItem::new();
                        i.uri = u.clone();
                        i.added_at = now;
                        i.is_removed = remove;
                        i
                    })
                    .collect();
                req.client_update_id = uuid::Uuid::new_v4().simple().to_string();
                http::spc_send_once(session, Method::POST, "/collection/v2/write", PROTO_CT, PROTO_CT, req.write_to_bytes()?)
                    .await?;
            }
            Err(e) => return Err(e.into()),
        }
        patch_snapshot(set, chunk, remove);
    }
    Ok(())
}

pub(crate) async fn write(session: &Session, uris: &[String], remove: bool) -> AppResult<()> {
    let user = username(session)?;
    let mut by_set: HashMap<Set, Vec<String>> = HashMap::new();
    let mut playlists = Vec::new();
    for uri in uris {
        match target_for(uri) {
            Some(Target::Set(set, u)) => by_set.entry(set).or_default().push(u),
            Some(Target::Rootlist(u)) => playlists.push(u),
            None => return Err(AppError::invalid(format!("cannot save {uri}"))),
        }
    }
    for (set, list) in by_set {
        write_set(session, &user, set, &playlist::dedup(&list), remove).await?;
    }
    for p in playlists {
        if remove {
            playlist::unfollow_uri(session, &p).await?;
        } else {
            playlist::follow_uri(session, &p).await?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// RPCs
// ---------------------------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PageArgs {
    #[serde(default)]
    offset: u32,
    #[serde(default = "default_limit")]
    limit: u32,
    /// Extension: only return the URIs of the window (`{"total","items":[],"uris":[…]}`).
    #[serde(default)]
    uris_only: bool,
}

fn default_limit() -> u32 {
    50
}

#[derive(Deserialize)]
struct UrisArgs {
    #[serde(default)]
    uris: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Saved<T: Serialize> {
    #[serde(skip_serializing_if = "Option::is_none")]
    added_at: Option<i64>,
    #[serde(flatten)]
    value: T,
}

#[derive(Serialize)]
struct TrackItem {
    track: Track,
}
#[derive(Serialize)]
struct AlbumItem {
    album: AlbumRef,
}
#[derive(Serialize)]
struct ArtistItem {
    artist: ArtistRef,
}
#[derive(Serialize)]
struct ShowItem {
    show: ShowRef,
}
#[derive(Serialize)]
struct EpisodeItem {
    episode: Episode,
}

fn added_ms(secs: i64) -> Option<i64> {
    (secs > 0).then(|| if secs > 100_000_000_000 { secs } else { secs * 1000 })
}

/// Filters a snapshot to one URI kind and slices the window.
pub(crate) fn window(items: &[CollItem], kind: UriKind, offset: u32, limit: u32) -> (u32, Vec<CollItem>) {
    let of_kind: Vec<&CollItem> =
        items.iter().filter(|i| parse_uri(&i.uri).is_some_and(|p| p.kind == kind)).collect();
    let total = of_kind.len() as u32;
    let page = of_kind.into_iter().skip(offset as usize).take(limit as usize).cloned().collect();
    (total, page)
}

fn page_value<T: Serialize>(total: u32, items: Vec<Saved<T>>) -> AppResult<Value> {
    to_value(&json!({ "total": total, "items": items }))
}

async fn liked_from_context(session: &Session, offset: u32, limit: u32) -> AppResult<(u32, Vec<CollItem>)> {
    let user = username(session)?;
    let items = context::resolve(session, &format!("spotify:user:{user}:collection"), 20_000, 200).await?;
    let mut list: Vec<CollItem> = items
        .into_iter()
        .filter(|i| i.uri.starts_with("spotify:track:"))
        .map(|i| CollItem {
            added_at: i.metadata.get("added_at").and_then(|s| s.parse().ok()).unwrap_or(0),
            uri: i.uri,
        })
        .collect();
    let mut seen = HashSet::new();
    list.retain(|i| seen.insert(i.uri.clone()));
    Ok(window(&list, UriKind::Track, offset, limit))
}

pub(crate) async fn tracks(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let limit = a.limit.clamp(1, MAX_LIMIT);
    let session = engine::session()?;
    let (total, page) = match snapshot(&session, Set::Collection, LIST_MAX_AGE).await {
        Ok(s) => window(&s, UriKind::Track, a.offset, limit),
        Err(e) if e.code == ErrorCode::NotLoggedIn || e.code == ErrorCode::Network => return Err(e),
        Err(e) => {
            log::warn!("collection paging failed ({e}); falling back to context-resolve");
            liked_from_context(&session, a.offset, limit).await?
        }
    };
    if a.uris_only {
        let uris: Vec<&str> = page.iter().map(|i| i.uri.as_str()).collect();
        return Ok(json!({ "total": total, "items": [], "uris": uris }));
    }
    let uris: Vec<String> = page.iter().map(|i| i.uri.clone()).collect();
    let map = metadata::track_map(&session, &uris).await?;
    let items = page
        .iter()
        .filter_map(|i| {
            Some(Saved { added_at: added_ms(i.added_at), value: TrackItem { track: (**map.get(&i.uri)?).clone() } })
        })
        .collect();
    page_value(total, items)
}

pub(crate) async fn albums(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Collection, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Album, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let uris: Vec<String> = page.iter().map(|i| i.uri.clone()).collect();
    let map = metadata::albums(&session, &uris).await?;
    let items = page
        .iter()
        .filter_map(|i| {
            Some(Saved { added_at: added_ms(i.added_at), value: AlbumItem { album: map.get(&i.uri)?.album.clone() } })
        })
        .collect();
    page_value(total, items)
}

pub(crate) async fn artists(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Artist, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Artist, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let uris: Vec<String> = page.iter().map(|i| i.uri.clone()).collect();
    let map = metadata::artists(&session, &uris).await?;
    let items = page
        .iter()
        .filter_map(|i| {
            Some(Saved { added_at: added_ms(i.added_at), value: ArtistItem { artist: map.get(&i.uri)?.artist.clone() } })
        })
        .collect();
    page_value(total, items)
}

pub(crate) async fn shows(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Show, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Show, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let uris: Vec<String> = page.iter().map(|i| i.uri.clone()).collect();
    let map = metadata::shows(&session, &uris).await?;
    let items = page
        .iter()
        .filter_map(|i| Some(Saved { added_at: added_ms(i.added_at), value: ShowItem { show: map.get(&i.uri)?.show.clone() } }))
        .collect();
    page_value(total, items)
}

pub(crate) async fn episodes(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::ListenLater, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Episode, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let uris: Vec<String> = page.iter().map(|i| i.uri.clone()).collect();
    let map = metadata::episode_map(&session, &uris).await?;
    let items = page
        .iter()
        .filter_map(|i| {
            Some(Saved { added_at: added_ms(i.added_at), value: EpisodeItem { episode: (**map.get(&i.uri)?).clone() } })
        })
        .collect();
    page_value(total, items)
}

pub(crate) async fn contains_rpc(args: Value) -> AppResult<Value> {
    let a: UrisArgs = parse_args(args)?;
    if a.uris.is_empty() {
        return Ok(json!({ "contains": [] }));
    }
    let session = engine::session()?;
    Ok(json!({ "contains": contains(&session, &a.uris).await? }))
}

pub(crate) async fn save_rpc(args: Value, remove: bool) -> AppResult<Value> {
    let a: UrisArgs = parse_args(args)?;
    if a.uris.is_empty() {
        return Ok(json!({}));
    }
    let session = engine::session()?;
    write(&session, &a.uris, remove).await?;
    Ok(json!({}))
}

/// First `n` followed artists (for the local home feed).
pub(crate) async fn followed_artists(session: &Session, n: usize) -> AppResult<Vec<ArtistRef>> {
    let snap = snapshot(session, Set::Artist, LIST_MAX_AGE).await?;
    let uris: Vec<String> = snap.iter().take(n).map(|i| i.uri.clone()).collect();
    let map = metadata::artists(session, &uris).await?;
    Ok(uris.iter().filter_map(|u| map.get(u)).map(|m| m.artist.clone()).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routes_uris_to_sets() {
        assert_eq!(
            target_for("spotify:track:4uLU6hMCjMI75M1A2tKUQC"),
            Some(Target::Set(Set::Collection, "spotify:track:4uLU6hMCjMI75M1A2tKUQC".into()))
        );
        assert!(matches!(target_for("spotify:album:6XhjNHCyCDyyGJRM5mg40G"), Some(Target::Set(Set::Collection, _))));
        assert!(matches!(target_for("spotify:artist:0gxyHStUsqpMadRV0Di1Qt"), Some(Target::Set(Set::Artist, _))));
        assert!(matches!(target_for("spotify:show:5CfCWKI5pZ28U0uOzXkDHe"), Some(Target::Set(Set::Show, _))));
        assert!(matches!(target_for("spotify:episode:512ojhOuo1ktJprKbVcKyQ"), Some(Target::Set(Set::ListenLater, _))));
        assert_eq!(
            target_for("spotify:user:x:playlist:37i9dQZF1DXcBWIGoYBM5M"),
            Some(Target::Rootlist("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M".into()))
        );
        assert_eq!(target_for("spotify:local:a:b:c:1"), None);
    }

    #[test]
    fn parses_json_paging() {
        let body = br#"{
            "items": [
                {"uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "added_at": 1700000300},
                {"uri": "spotify:album:6XhjNHCyCDyyGJRM5mg40G", "added_at": "1700000200"},
                {"uri": "spotify:track:7GhIk7Il098yCjg4BQjzvb", "added_at": 1700000100, "is_removed": true},
                {"uri": "spotify:track:2takcwOaAZWiXQijPHIx7B", "addedAt": 1700000400, "context_uri": "spotify:album:x"},
                {"added_at": 5}
            ],
            "next_page_token": "tok2",
            "sync_token": "s"
        }"#;
        let (mut items, next) = parse_json_page(body).unwrap();
        assert_eq!(next.as_deref(), Some("tok2"));
        assert_eq!(items.len(), 3, "removed and uri-less items skipped");
        sort_items(&mut items);
        assert_eq!(items[0].uri, "spotify:track:2takcwOaAZWiXQijPHIx7B");
        let (total, page) = window(&items, UriKind::Track, 0, 10);
        assert_eq!(total, 2);
        assert_eq!(page[1].uri, "spotify:track:4uLU6hMCjMI75M1A2tKUQC");
        let (total, page) = window(&items, UriKind::Album, 0, 10);
        assert_eq!((total, page.len()), (1, 1));
        let (_, page) = window(&items, UriKind::Track, 1, 1);
        assert_eq!(page.len(), 1);
        let (empty, next) = parse_json_page(br#"{"items":[]}"#).unwrap();
        assert!(empty.is_empty() && next.is_none());
        assert!(parse_json_page(b"<html>").is_err());
    }

    #[test]
    fn parses_proto_paging() {
        let mut resp = c2::PageResponse::new();
        for (uri, at, removed) in [("spotify:artist:0gxyHStUsqpMadRV0Di1Qt", 10, false), ("spotify:artist:x", 11, true)] {
            let mut i = c2::CollectionItem::new();
            i.uri = uri.into();
            i.added_at = at;
            i.is_removed = removed;
            resp.items.push(i);
        }
        resp.next_page_token = "next".into();
        let (items, next) = parse_proto_page(&resp.write_to_bytes().unwrap()).unwrap();
        assert_eq!(items, vec![CollItem { uri: "spotify:artist:0gxyHStUsqpMadRV0Di1Qt".into(), added_at: 10 }]);
        assert_eq!(next.as_deref(), Some("next"));
        let mut req = c2::PageRequest::new();
        req.username = "alice".into();
        req.set = "collection".into();
        req.limit = 300;
        let back = c2::PageRequest::parse_from_bytes(&req.write_to_bytes().unwrap()).unwrap();
        assert_eq!(back.set, "collection");
    }

    #[test]
    fn parses_contains() {
        assert_eq!(parse_contains(br#"{"found":[true,false,"x"]}"#, 4).unwrap(), vec![true, false, false, false]);
        assert!(parse_contains(b"[", 1).is_err());
    }

    #[test]
    fn saved_items_serialise_flat() {
        let v = serde_json::to_value(Saved {
            added_at: added_ms(1_700_000_000),
            value: AlbumItem { album: AlbumRef { uri: "spotify:album:x".into(), name: "X".into(), ..Default::default() } },
        })
        .unwrap();
        assert_eq!(v["addedAt"], 1_700_000_000_000i64);
        assert_eq!(v["album"]["uri"], "spotify:album:x");
        assert_eq!(added_ms(0), None);
    }

    #[test]
    fn patches_snapshots() {
        SNAPSHOTS.lock().insert(
            Set::Show,
            Snapshot {
                items: Arc::new(vec![CollItem { uri: "spotify:show:a".into(), added_at: 1 }]),
                at: Instant::now(),
            },
        );
        patch_snapshot(Set::Show, &["spotify:show:b".into()], false);
        patch_snapshot(Set::Show, &["spotify:show:a".into()], true);
        let s = cached(Set::Show, Duration::from_secs(60)).unwrap();
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].uri, "spotify:show:b");
    }
}
