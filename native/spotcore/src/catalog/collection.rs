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
use super::metadata::{self, Fetched};
use super::playlist;
use super::proto::collection2v2 as c2;
use super::util::{now_ms, parse_kind, parse_uri, ParsedUri, UriKind};
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
    super::account(session)
}

/// Encoding mismatch (worth retrying with the other encoding) vs. a real failure.
fn encoding_rejected(e: &HttpError) -> bool {
    matches!(e.status, Some(400) | Some(406) | Some(415)) || e.error.code == ErrorCode::Unavailable && e.status.is_none()
}

/// Items of a set and whether the paging reached its end (false: `MAX_PAGES` ran out while
/// the server still offered a next page).
type Listing = (Vec<CollItem>, bool);

/// Every item of `set`. A listing cut off at `MAX_PAGES` fails: a snapshot is the authoritative
/// membership (totals, `urisOnly`, download sync), so it is never silently short.
async fn fetch_set(session: &Session, user: &str, set: Set) -> AppResult<Vec<CollItem>> {
    let (items, complete) = match fetch_set_json(session, user, set).await {
        Ok(v) => v,
        Err(e) if encoding_rejected(&e) => {
            log::info!("collection {} JSON paging failed ({}), trying protobuf", set.name(), e.error);
            fetch_set_proto(session, user, set).await?
        }
        Err(e) => return Err(e.into()),
    };
    if !complete {
        return Err(AppError::unavailable(format!("collection {} has more than {MAX_PAGES} pages", set.name())));
    }
    Ok(items)
}

async fn fetch_set_json(session: &Session, user: &str, set: Set) -> Result<Listing, HttpError> {
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
            _ => return Ok((out, true)),
        }
    }
    Ok((out, false))
}

async fn fetch_set_proto(session: &Session, user: &str, set: Set) -> AppResult<Listing> {
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
            _ => return Ok((out, true)),
        }
    }
    Ok((out, false))
}

// ---------------------------------------------------------------------------------------------
// Snapshots
// ---------------------------------------------------------------------------------------------

struct Snapshot {
    items: Arc<Vec<CollItem>>,
    at: Instant,
    /// Account the snapshot was read for; another account's snapshot is never served.
    owner: String,
    /// Only a prefix of the list (Liked Songs fallback cut off at its item/page budget).
    truncated: bool,
}

impl Snapshot {
    fn new(owner: &str, items: Arc<Vec<CollItem>>, truncated: bool) -> Self {
        Self { items, at: Instant::now(), owner: owner.to_string(), truncated }
    }

    fn usable(&self, owner: &str, max_age: Duration) -> bool {
        self.owner == owner && self.at.elapsed() < max_age
    }
}

static SNAPSHOTS: LazyLock<Mutex<HashMap<Set, Snapshot>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static LOADS: LazyLock<HashMap<Set, tokio::sync::Mutex<()>>> =
    LazyLock::new(|| Set::ALL.iter().map(|s| (*s, tokio::sync::Mutex::new(()))).collect());

fn cached(owner: &str, set: Set, max_age: Duration) -> Option<Arc<Vec<CollItem>>> {
    SNAPSHOTS.lock().get(&set).filter(|s| s.usable(owner, max_age)).map(|s| s.items.clone())
}

/// Drops every snapshot (logout / account switch, see `catalog::clear_user_state`).
pub(crate) fn forget_account() {
    SNAPSHOTS.lock().clear();
    *LIKED_FALLBACK.lock() = None;
}

/// `library.invalidate` (pull-to-refresh): forgets the cached library lists (set snapshots,
/// the Liked Songs fallback, the rootlist), so the next `library.*` read goes to the server.
/// Reads after it load each list once, as usual (no per-page bypass).
pub(crate) async fn invalidate_rpc(_args: Value) -> AppResult<Value> {
    invalidate_lists();
    Ok(json!({}))
}

fn invalidate_lists() {
    forget_account();
    playlist::invalidate_rootlist();
}

/// Sorted (newest first) contents of `set`, at most `max_age` old.
pub(crate) async fn snapshot(session: &Session, set: Set, max_age: Duration) -> AppResult<Arc<Vec<CollItem>>> {
    let user = username(session)?;
    if let Some(s) = cached(&user, set, max_age) {
        return Ok(s);
    }
    let _guard = match LOADS.get(&set) {
        Some(m) => Some(m.lock().await),
        None => None,
    };
    if let Some(s) = cached(&user, set, max_age) {
        return Ok(s);
    }
    let mut items = fetch_set(session, &user, set).await?;
    sort_items(&mut items);
    let items = Arc::new(items);
    SNAPSHOTS.lock().insert(set, Snapshot::new(&user, items.clone(), false));
    Ok(items)
}

pub(crate) fn sort_items(items: &mut Vec<CollItem>) {
    let mut seen = HashSet::new();
    items.retain(|i| seen.insert(i.uri.clone()));
    items.sort_by_key(|i| std::cmp::Reverse(i.added_at));
}

/// Applies a library write by `owner` to their cached lists.
fn patch_snapshot(owner: &str, set: Set, uris: &[String], removed: bool) {
    if let Some(s) = SNAPSHOTS.lock().get_mut(&set).filter(|s| s.owner == owner) {
        s.items = Arc::new(patched(&s.items, uris, removed));
    }
    if set == Set::Collection {
        let tracks: Vec<String> = uris.iter().filter(|u| parse_kind(u, UriKind::Track).is_some()).cloned().collect();
        if let Some(s) = LIKED_FALLBACK.lock().as_mut().filter(|s| s.owner == owner && !tracks.is_empty()) {
            s.items = Arc::new(patched(&s.items, &tracks, removed));
        }
    }
}

/// `items` with `uris` removed, or moved/added to the front (newest) when saved.
fn patched(items: &[CollItem], uris: &[String], removed: bool) -> Vec<CollItem> {
    let rest = items.iter().filter(|i| !uris.contains(&i.uri)).cloned();
    if removed {
        return rest.collect();
    }
    let now = now_ms() / 1000;
    uris.iter().map(|u| CollItem { uri: u.clone(), added_at: now }).chain(rest).collect()
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
                let snap = match cached(user, set, CONTAINS_FALLBACK_AGE) {
                    Some(s) => s,
                    // Throttled or offline: downloading the whole set would only make it worse.
                    None if stops_fallback(&e) => return Err(e),
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
        patch_snapshot(user, set, chunk, remove);
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

/// A `library.*` page: exactly one item per window slot, so `items.len() == min(limit, total -
/// offset)` and offset paging stays aligned. Entries without metadata keep their slot as a
/// placeholder carrying only the URI; `partial` is set when some of them are only missing
/// because their request failed, or when the list itself is only a prefix (`truncated`, docs
/// §6.3, §6.5).
pub(crate) fn saved_page<M, V: Serialize>(
    total: u32,
    page: &[CollItem],
    kind: UriKind,
    fetched: &Fetched<M>,
    truncated: bool,
    item: impl Fn(&str, Option<&M>) -> V,
) -> AppResult<Value> {
    let keys: Vec<String> = page.iter().map(|i| parse_kind(&i.uri, kind).map(|p| p.uri()).unwrap_or_else(|| i.uri.clone())).collect();
    let items: Vec<Saved<V>> = page
        .iter()
        .zip(&keys)
        .map(|(i, key)| Saved { added_at: added_ms(i.added_at), value: item(key, fetched.map.get(key).map(|m| &**m)) })
        .collect();
    let mut out = json!({ "total": total, "items": items });
    if fetched.any_failed(keys.iter()) {
        log::warn!("library {} page partial: {:?}", kind.as_str(), fetched.error);
        out["partial"] = json!(true);
    }
    if truncated {
        out["partial"] = json!(true);
    }
    to_value(&out)
}

fn window_uris(page: &[CollItem]) -> Vec<String> {
    page.iter().map(|i| i.uri.clone()).collect()
}

/// Errors after which trying another source is pointless or harmful (offline, throttled,
/// cancelled, logged out): they are returned instead of starting a fallback.
fn stops_fallback(e: &AppError) -> bool {
    matches!(e.code, ErrorCode::NotLoggedIn | ErrorCode::Network | ErrorCode::RateLimited | ErrorCode::Cancelled)
}

/// Liked Songs from context-resolve while `collection/v2/paging` fails. Tracks only, so it is
/// kept apart from the `collection` snapshot (which also holds the saved albums); same TTL.
static LIKED_FALLBACK: LazyLock<Mutex<Option<Snapshot>>> = LazyLock::new(|| Mutex::new(None));
static LIKED_FALLBACK_LOAD: LazyLock<tokio::sync::Mutex<()>> = LazyLock::new(|| tokio::sync::Mutex::new(()));
const LIKED_CONTEXT_MAX_ITEMS: usize = 20_000;
const LIKED_CONTEXT_MAX_PAGES: usize = 200;

/// Liked Songs as served: the list and whether it is only a prefix (fallback cut off at its
/// budget), which `library.tracks` reports instead of passing it off as the whole collection.
#[derive(Clone)]
struct Liked {
    items: Arc<Vec<CollItem>>,
    truncated: bool,
}

fn cached_liked_fallback(owner: &str) -> Option<Liked> {
    LIKED_FALLBACK
        .lock()
        .as_ref()
        .filter(|s| s.usable(owner, LIST_MAX_AGE))
        .map(|s| Liked { items: s.items.clone(), truncated: s.truncated })
}

/// Liked Songs (newest first) from context items: tracks only, deduplicated.
pub(crate) fn liked_items(items: Vec<context::ContextItem>) -> Vec<CollItem> {
    let mut list: Vec<CollItem> = items
        .into_iter()
        .filter(|i| i.uri.starts_with("spotify:track:"))
        .map(|i| CollItem { added_at: i.metadata.get("added_at").and_then(|s| s.parse().ok()).unwrap_or(0), uri: i.uri })
        .collect();
    sort_items(&mut list);
    list
}

/// The Liked Songs list from context-resolve, resolved once per [`LIST_MAX_AGE`] (loads
/// coalesced) so paging does not re-resolve it for every page.
async fn liked_from_context(session: &Session, user: &str) -> AppResult<Liked> {
    if let Some(s) = cached_liked_fallback(user) {
        return Ok(s);
    }
    let _guard = LIKED_FALLBACK_LOAD.lock().await;
    if let Some(s) = cached_liked_fallback(user) {
        return Ok(s);
    }
    // Fails when a page fails, and reports a list cut off at the budget: a short list must not
    // read as songs that are no longer liked.
    let context_uri = format!("spotify:user:{user}:collection");
    let r = context::resolve_within(session, &context_uri, LIKED_CONTEXT_MAX_ITEMS, LIKED_CONTEXT_MAX_PAGES).await?;
    if r.truncated {
        log::warn!("Liked Songs fallback stopped at its budget ({} items)", r.items.len());
    }
    let liked = Liked { items: Arc::new(liked_items(r.items)), truncated: r.truncated };
    *LIKED_FALLBACK.lock() = Some(Snapshot::new(user, liked.items.clone(), liked.truncated));
    Ok(liked)
}

/// Liked Songs: the `collection` snapshot, or the context-resolve fallback when paging fails
/// (not for transport errors). While a fallback list is fresh, the failing request is skipped.
async fn liked_songs(session: &Session) -> AppResult<Liked> {
    let user = username(session)?;
    if let Some(items) = cached(&user, Set::Collection, LIST_MAX_AGE) {
        return Ok(Liked { items, truncated: false });
    }
    if let Some(s) = cached_liked_fallback(&user) {
        return Ok(s);
    }
    match snapshot(session, Set::Collection, LIST_MAX_AGE).await {
        Ok(items) => Ok(Liked { items, truncated: false }),
        Err(e) if stops_fallback(&e) => Err(e),
        Err(e) => {
            log::warn!("collection paging failed ({e}); falling back to context-resolve");
            liked_from_context(session, &user).await
        }
    }
}

pub(crate) async fn tracks(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let limit = a.limit.clamp(1, MAX_LIMIT);
    let session = engine::session()?;
    let liked = liked_songs(&session).await?;
    let (total, page) = window(&liked.items, UriKind::Track, a.offset, limit);
    if a.uris_only {
        // The membership source (download sync drops what it does not list): only whole.
        if liked.truncated {
            return Err(AppError::unavailable("Liked Songs could not be listed completely"));
        }
        let uris: Vec<&str> = page.iter().map(|i| i.uri.as_str()).collect();
        return Ok(json!({ "total": total, "items": [], "uris": uris }));
    }
    let fetched = metadata::track_lookup(&session, &window_uris(&page)).await.map_err(metadata::page_error)?;
    saved_page(total, &page, UriKind::Track, &fetched, liked.truncated, |uri, t| TrackItem {
        track: t.cloned().unwrap_or_else(|| metadata::placeholder_track(uri)),
    })
}

pub(crate) async fn albums(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Collection, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Album, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let fetched = metadata::album_lookup(&session, &window_uris(&page)).await.map_err(metadata::page_error)?;
    saved_page(total, &page, UriKind::Album, &fetched, false, |uri, m| AlbumItem {
        album: m.map(|m| m.album.clone()).unwrap_or_else(|| AlbumRef { uri: uri.to_string(), ..Default::default() }),
    })
}

pub(crate) async fn artists(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Artist, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Artist, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let fetched = metadata::artist_lookup(&session, &window_uris(&page)).await.map_err(metadata::page_error)?;
    saved_page(total, &page, UriKind::Artist, &fetched, false, |uri, m| ArtistItem {
        artist: m.map(|m| m.artist.clone()).unwrap_or_else(|| ArtistRef { uri: uri.to_string(), ..Default::default() }),
    })
}

pub(crate) async fn shows(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::Show, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Show, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let fetched = metadata::show_lookup(&session, &window_uris(&page)).await.map_err(metadata::page_error)?;
    saved_page(total, &page, UriKind::Show, &fetched, false, |uri, m| ShowItem {
        show: m.map(|m| m.show.clone()).unwrap_or_else(|| ShowRef { uri: uri.to_string(), ..Default::default() }),
    })
}

pub(crate) async fn episodes(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let session = engine::session()?;
    let snap = snapshot(&session, Set::ListenLater, LIST_MAX_AGE).await?;
    let (total, page) = window(&snap, UriKind::Episode, a.offset, a.limit.clamp(1, MAX_LIMIT));
    let fetched = metadata::episode_lookup(&session, &window_uris(&page)).await.map_err(metadata::page_error)?;
    saved_page(total, &page, UriKind::Episode, &fetched, false, |uri, e| EpisodeItem {
        episode: e.cloned().unwrap_or_else(|| metadata::placeholder_episode(uri)),
    })
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
    fn library_pages_keep_one_item_per_window_slot() {
        use crate::catalog::pages::tests::fetched;
        let (a, b, c) = ("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:track:7GhIk7Il098yCjg4BQjzvb", "spotify:track:2takcwOaAZWiXQijPHIx7B");
        let album = "spotify:album:6XhjNHCyCDyyGJRM5mg40G";
        let items: Vec<CollItem> = [a, album, b, c]
            .iter()
            .enumerate()
            .map(|(i, u)| CollItem { uri: u.to_string(), added_at: 100 - i as i64 })
            .collect();
        let (total, page) = window(&items, UriKind::Track, 0, 10);
        let ta = Track { uri: a.into(), name: "A".into(), ..Default::default() };
        let track_item = |uri: &str, t: Option<&Track>| TrackItem { track: t.cloned().unwrap_or_else(|| metadata::placeholder_track(uri)) };
        // b has no data on the server, c's request failed.
        let v = saved_page(total, &page, UriKind::Track, &fetched(&[(a, ta.clone())], &[c]), false, track_item).unwrap();
        assert_eq!(v["total"], 3);
        let got: Vec<&str> = v["items"].as_array().unwrap().iter().map(|i| i["track"]["uri"].as_str().unwrap()).collect();
        assert_eq!(got, [a, b, c]);
        assert_eq!(v["items"][0]["track"]["name"], "A");
        assert_eq!(v["items"][1]["track"]["playable"], false);
        assert_eq!(v["items"][1]["addedAt"], 98_000);
        assert_eq!(v["partial"], true);
        // Missing data alone does not make a page partial.
        let v = saved_page(total, &page, UriKind::Track, &fetched(&[(a, ta.clone())], &[]), false, track_item).unwrap();
        assert_eq!(v["items"].as_array().unwrap().len(), 3);
        assert!(v.get("partial").is_none());
        // A list that is only a prefix of the collection is partial.
        let v = saved_page(total, &page, UriKind::Track, &fetched(&[(a, ta)], &[]), true, track_item).unwrap();
        assert_eq!(v["partial"], true);
        // Albums keep their slot as a bare reference.
        let (total, page) = window(&items, UriKind::Album, 0, 10);
        let v = saved_page(total, &page, UriKind::Album, &fetched::<metadata::AlbumMeta>(&[], &[]), false, |uri, m| AlbumItem {
            album: m.map(|m| m.album.clone()).unwrap_or_else(|| AlbumRef { uri: uri.to_string(), ..Default::default() }),
        })
        .unwrap();
        assert_eq!(v["items"][0]["album"]["uri"], album);
    }

    use crate::catalog::TEST_CACHES as CACHES;

    fn snap(owner: &str, uris: &[&str]) -> Snapshot {
        let items = uris.iter().map(|u| CollItem { uri: u.to_string(), added_at: 1 }).collect();
        Snapshot::new(owner, Arc::new(items), false)
    }

    #[test]
    fn liked_songs_fallback_is_cached_patched_and_skipped_on_transport_errors() {
        let _caches = CACHES.blocking_lock();
        let item = |uri: &str, at: &str| context::ContextItem {
            uri: uri.into(),
            uid: None,
            metadata: [("added_at".to_string(), at.to_string())].into_iter().collect(),
        };
        let (a, b) = ("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:track:7GhIk7Il098yCjg4BQjzvb");
        let list = liked_items(vec![item(a, "10"), item("spotify:episode:512ojhOuo1ktJprKbVcKyQ", "30"), item(b, "20"), item(a, "10")]);
        assert_eq!(list.iter().map(|i| i.uri.as_str()).collect::<Vec<_>>(), [b, a], "tracks only, deduplicated, newest first");

        *LIKED_FALLBACK.lock() = Some(Snapshot::new("alice", Arc::new(list), true));
        let liked = cached_liked_fallback("alice").unwrap();
        assert_eq!(liked.items.len(), 2);
        assert!(liked.truncated, "a fallback cut off at its budget stays marked");
        // Likes and unlikes show up in the fallback list too (albums do not).
        patch_snapshot("alice", Set::Collection, &[b.to_string(), "spotify:album:6XhjNHCyCDyyGJRM5mg40G".into()], true);
        let uris = |l: Liked| l.items.iter().map(|i| i.uri.clone()).collect::<Vec<_>>();
        assert_eq!(uris(cached_liked_fallback("alice").unwrap()), [a]);
        patch_snapshot("alice", Set::Collection, &[b.to_string()], false);
        assert_eq!(uris(cached_liked_fallback("alice").unwrap())[0], b);
        // Another account's write or lookup never touches it.
        patch_snapshot("bob", Set::Collection, &[a.to_string()], true);
        assert_eq!(cached_liked_fallback("alice").unwrap().items.len(), 2);
        assert!(cached_liked_fallback("bob").is_none());
        *LIKED_FALLBACK.lock() = None;

        for code in [ErrorCode::Network, ErrorCode::RateLimited, ErrorCode::Cancelled, ErrorCode::NotLoggedIn] {
            assert!(stops_fallback(&AppError::new(code, "x")), "{code:?}");
        }
        assert!(!stops_fallback(&AppError::unavailable("403")));
    }

    #[test]
    fn patches_snapshots() {
        let _caches = CACHES.blocking_lock();
        SNAPSHOTS.lock().insert(Set::Show, snap("alice", &["spotify:show:a"]));
        patch_snapshot("alice", Set::Show, &["spotify:show:b".into()], false);
        patch_snapshot("alice", Set::Show, &["spotify:show:a".into()], true);
        let s = cached("alice", Set::Show, Duration::from_secs(60)).unwrap();
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].uri, "spotify:show:b");
        SNAPSHOTS.lock().remove(&Set::Show);
    }

    #[test]
    fn snapshots_belong_to_their_account() {
        let _caches = CACHES.blocking_lock();
        SNAPSHOTS.lock().insert(Set::Artist, snap("alice", &["spotify:artist:0gxyHStUsqpMadRV0Di1Qt"]));
        *LIKED_FALLBACK.lock() = Some(snap("alice", &["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]));
        // The next account never sees them (also covers a load for alice finishing late).
        assert!(cached("bob", Set::Artist, Duration::from_secs(60)).is_none());
        assert!(cached_liked_fallback("bob").is_none());
        assert!(cached("alice", Set::Artist, Duration::from_secs(60)).is_some());
        // Logout drops them altogether.
        forget_account();
        assert!(cached("alice", Set::Artist, Duration::from_secs(60)).is_none());
        assert!(cached_liked_fallback("alice").is_none());
    }

    #[test]
    fn pull_to_refresh_forgets_the_cached_lists() {
        let _caches = CACHES.blocking_lock();
        SNAPSHOTS.lock().insert(Set::Collection, snap("alice", &["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]));
        *LIKED_FALLBACK.lock() = Some(snap("alice", &["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]));
        assert!(cached("alice", Set::Collection, LIST_MAX_AGE).is_some());
        invalidate_lists();
        assert!(cached("alice", Set::Collection, LIST_MAX_AGE).is_none(), "the next read goes to the server");
        assert!(cached_liked_fallback("alice").is_none());
    }
}
