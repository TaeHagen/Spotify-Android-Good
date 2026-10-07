//! Playlists through spclient `playlist/v2`: `catalog.playlist`, the rootlist
//! (`library.playlists`, folders preserved) and `playlist.*` mutations.
//!
//! Reads are protobuf (`SelectedListContent`, parsed with the relaxed copy in `catalog::proto`
//! so one malformed item never fails a page). Writes mirror the live web player (verified in its
//! 2026-10 bundle): JSON `ListChanges` to `/playlist/v2/playlist/{id}/changes` and
//! `/playlist/v2/user/{user}/rootlist/changes`, a JSON `Delta` to `POST /playlist/v2/playlist`
//! for creation, with a protobuf fallback when the server rejects the JSON encoding. Writes are
//! sent exactly once (no librespot retry loop).

use super::http::{self, HttpError, JSON, PROTOBUF};
use super::metadata::{self, Fetched};
use super::proto::playlist4_external as p4;
use super::util::{decode_plus, encode_component, file_id_hex, now_ms, parse_kind, strip_html, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{
    Episode, Image, Playlist, PlaylistItem, PlaylistOwner, PlaylistRef, RootlistEntry, RootlistEntryType, Track,
};
use crate::rpc::{parse_args, to_value};
use base64::Engine as _;
use futures_util::stream::{self, StreamExt};
use ::http::Method;
use librespot_core::Session;
use lru::LruCache;
use parking_lot::Mutex;
use protobuf::{EnumOrUnknown, MessageField};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::num::NonZeroUsize;
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

const DECORATE: &str = "decorate=revision,length,attributes,timestamp,owner,capabilities";
const MAX_PAGE: u32 = 500;
const ROOTLIST_PAGE: usize = 500;
const ROOTLIST_MAX_PAGES: usize = 20;
/// Header requests per `library.playlists` call for rootlist entries without decorations
/// (cached entries do not count, so later calls resolve the rest).
const HEADER_LOOKUPS: usize = 100;
const HEADER_TTL: Duration = Duration::from_secs(30 * 60);
/// Lookups that will fail the same way again (deleted or private playlist) are not repeated
/// for this long. Transport errors and rate limits are never cached.
const HEADER_NEGATIVE_TTL: Duration = Duration::from_secs(30 * 60);

pub(crate) const CONFLICT_MESSAGE: &str = "playlist revision changed, reload and retry";

// ---------------------------------------------------------------------------------------------
// Shared conversion helpers
// ---------------------------------------------------------------------------------------------

fn picture_width(target: &str, url: &str) -> Option<u32> {
    if let Some(rest) = url.split("mosaic.scdn.co/").nth(1) {
        if let Some(n) = rest.split('/').next().and_then(|n| n.parse().ok()) {
            return Some(n);
        }
    }
    match target {
        "xsmall" | "small" => Some(60),
        "default" => Some(300),
        "large" => Some(640),
        "xlarge" => Some(1000),
        _ => None,
    }
}

/// Images of a playlist: transcoded `picture_size` URLs (mosaics, generated covers) and the
/// custom picture file id.
pub(crate) fn list_images(attrs: &p4::ListAttributes) -> Vec<Image> {
    let mut out: Vec<Image> = attrs
        .picture_size
        .iter()
        .filter(|p| p.url().starts_with("https://"))
        .map(|p| {
            let w = picture_width(p.target_name(), p.url());
            Image { url: p.url().to_string(), width: w, height: w }
        })
        .collect();
    if let Some(hex) = file_id_hex(attrs.picture()) {
        out.push(Image::from_file_id_hex(&hex, None, None));
    }
    super::util::normalize_images(out)
}

fn owner(username: &str) -> Option<PlaylistOwner> {
    (!username.is_empty()).then(|| PlaylistOwner {
        username: username.to_string(),
        display_name: (username == "spotify").then(|| "Spotify".to_string()),
    })
}

fn description(attrs: &p4::ListAttributes) -> Option<String> {
    Some(strip_html(attrs.description())).filter(|d| !d.is_empty())
}

fn playlist_ref(uri: String, attrs: &p4::ListAttributes, owner_name: &str, length: Option<i32>) -> PlaylistRef {
    PlaylistRef {
        uri,
        name: attrs.name().to_string(),
        description: description(attrs),
        images: list_images(attrs),
        owner: owner(owner_name),
        total_tracks: length.filter(|l| *l >= 0).map(|l| l as u32),
    }
}

fn millis(ts: i64) -> Option<i64> {
    match ts {
        t if t <= 0 => None,
        // Some timestamps are in microseconds (librespot playlist/list.rs heuristic).
        t if t > 9_295_169_800_000 => Some(t / 1000),
        t => Some(t),
    }
}

fn revision_hex(rev: &[u8]) -> Option<String> {
    (!rev.is_empty()).then(|| hex::encode(rev))
}

fn parse_revision(hex_rev: &str) -> AppResult<Vec<u8>> {
    hex::decode(hex_rev.trim()).map_err(|_| AppError::invalid("revision must be hex"))
}

/// Revision bytes from a JSON playlist response (`{"revision":"<base64>"}`).
fn revision_from_json(body: &[u8]) -> Option<Vec<u8>> {
    let v: Value = serde_json::from_slice(body).ok()?;
    let s = v.get("revision")?.as_str()?;
    base64::engine::general_purpose::STANDARD
        .decode(s)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(s))
        .ok()
        .filter(|r| !r.is_empty())
}

fn map_write_error(e: HttpError) -> AppError {
    match e.status {
        Some(409) | Some(412) => AppError::invalid(CONFLICT_MESSAGE),
        _ => e.error,
    }
}

// ---------------------------------------------------------------------------------------------
// Fetching
// ---------------------------------------------------------------------------------------------

async fn fetch_list(
    session: &Session,
    id: &str,
    range: Option<(u32, u32)>,
) -> Result<p4::SelectedListContent, HttpError> {
    let mut endpoint = format!("/playlist/v2/playlist/{id}?{DECORATE}");
    if let Some((from, length)) = range {
        endpoint.push_str(&format!("&from={from}&length={length}"));
    }
    let body = match http::spc_get(session, &endpoint, None).await {
        Err(e) if range.is_some() && e.error.code == ErrorCode::InvalidArgument => {
            // Paging parameters not accepted: fetch the whole list and slice locally.
            http::spc_get(session, &format!("/playlist/v2/playlist/{id}?{DECORATE}"), None).await?
        }
        r => r?,
    };
    http::proto(&body).map_err(|error| HttpError { status: None, error })
}

/// Header cache: `Some` = the playlist's reference, `None` = a lookup that will fail again.
type HeaderCache = LruCache<String, (Instant, Option<PlaylistRef>)>;

static HEADERS: LazyLock<Mutex<HeaderCache>> =
    LazyLock::new(|| Mutex::new(LruCache::new(NonZeroUsize::new(512).unwrap_or(NonZeroUsize::MIN))));

fn header_from_list(uri: &str, list: &p4::SelectedListContent) -> PlaylistRef {
    playlist_ref(uri.to_string(), &list.attributes, list.owner_username(), list.length)
}

/// Outcome of resolving playlist references.
#[derive(Debug, Default)]
pub(crate) struct Headers {
    pub refs: HashMap<String, PlaylistRef>,
    /// Some lookups failed transiently or were left for later (fetch budget): the missing
    /// playlists may well exist.
    pub incomplete: bool,
}

enum HeaderOutcome {
    Found(PlaylistRef),
    /// Deleted or not accessible: cached as such.
    Gone,
    /// Network, rate limit, server error: retried next time.
    Failed,
}

/// What a failed header lookup says about the playlist.
fn header_failure(e: &HttpError) -> HeaderOutcome {
    if e.is_not_found() || e.status == Some(403) {
        HeaderOutcome::Gone
    } else {
        HeaderOutcome::Failed
    }
}

/// Cached header state of `key`: `Some(Some(r))` known, `Some(None)` known to fail, `None` unknown.
fn cached_header(cache: &mut HeaderCache, key: &str) -> Option<Option<PlaylistRef>> {
    match cache.get(key) {
        Some((at, Some(r))) if at.elapsed() < HEADER_TTL => Some(Some(r.clone())),
        Some((at, None)) if at.elapsed() < HEADER_NEGATIVE_TTL => Some(None),
        _ => None,
    }
}

/// Playlist references (name, images, owner, length) for `uris`, cached for 30 minutes; at most
/// `max_fetches` uncached ones are requested. Unresolvable playlists are omitted.
pub(crate) async fn headers(session: &Session, uris: &[String], max_fetches: usize) -> Headers {
    let mut out = Headers::default();
    let mut missing = Vec::new();
    {
        let mut cache = HEADERS.lock();
        for uri in uris.iter().filter_map(|u| parse_kind(u, UriKind::Playlist)) {
            let key = uri.uri();
            match cached_header(&mut cache, &key) {
                Some(Some(r)) => {
                    out.refs.insert(key, r);
                }
                Some(None) => {}
                None if !missing.contains(&uri) => missing.push(uri),
                None => {}
            }
        }
    }
    if missing.len() > max_fetches {
        missing.truncate(max_fetches);
        out.incomplete = true;
    }
    let fetched: Vec<(String, HeaderOutcome)> = stream::iter(missing)
        .map(|p| async move {
            let outcome = match fetch_list(session, &p.id, Some((0, 1))).await {
                Ok(list) => HeaderOutcome::Found(header_from_list(&p.uri(), &list)),
                Err(e) => {
                    log::debug!("playlist header {} failed: {}", p.id, e.error);
                    header_failure(&e)
                }
            };
            (p.uri(), outcome)
        })
        .buffer_unordered(6)
        .collect()
        .await;
    let mut cache = HEADERS.lock();
    for (uri, outcome) in fetched {
        match outcome {
            HeaderOutcome::Found(r) => {
                cache.put(uri.clone(), (Instant::now(), Some(r.clone())));
                out.refs.insert(uri, r);
            }
            HeaderOutcome::Gone => {
                cache.put(uri, (Instant::now(), None));
            }
            HeaderOutcome::Failed => out.incomplete = true,
        }
    }
    out
}

fn forget_header(uri: &str) {
    HEADERS.lock().pop(uri);
}

// ---------------------------------------------------------------------------------------------
// catalog.playlist
// ---------------------------------------------------------------------------------------------

#[derive(Deserialize)]
struct PlaylistArgs {
    uri: String,
    #[serde(default)]
    offset: u32,
    #[serde(default = "default_limit")]
    limit: u32,
}

fn default_limit() -> u32 {
    100
}

/// Item placeholder used when metadata is missing (keeps indices aligned with the playlist).
fn local_track(uri: &str) -> Track {
    // spotify:local:<artist>:<album>:<title>:<seconds>
    let parts: Vec<&str> = uri.splitn(6, ':').collect();
    let field = |i: usize| parts.get(i).map(|s| decode_plus(s)).unwrap_or_default();
    Track {
        uri: uri.to_string(),
        name: field(4),
        duration_ms: parts.get(5).and_then(|s| s.parse::<u64>().ok()).unwrap_or(0) * 1000,
        playable: false,
        ..Default::default()
    }
}

/// Selects the requested window from a (possibly larger) returned window starting at `pos`.
fn window<T: Clone>(items: &[T], pos: u32, offset: u32, limit: u32) -> Option<Vec<T>> {
    if pos > offset {
        return None;
    }
    let start = (offset - pos) as usize;
    Some(items.iter().skip(start).take(limit as usize).cloned().collect())
}

pub(crate) fn build_items(
    raw: &[p4::Item],
    tracks: &HashMap<String, Arc<Track>>,
    episodes: &HashMap<String, Arc<Episode>>,
) -> Vec<PlaylistItem> {
    raw.iter()
        .map(|item| {
            let uri = item.uri();
            let attrs = &item.attributes;
            let mut out = PlaylistItem {
                uid: attrs.item_id.as_deref().and_then(|b| (!b.is_empty()).then(|| hex::encode(b))),
                added_at: attrs.timestamp.and_then(millis),
                added_by: attrs.added_by.clone().filter(|a| !a.is_empty()),
                track: None,
                episode: None,
            };
            if uri.starts_with("spotify:local:") {
                out.track = Some(local_track(uri));
            } else if let Some(p) = parse_kind(uri, UriKind::Track) {
                let key = p.uri();
                out.track = Some(tracks.get(&key).map(|t| (**t).clone()).unwrap_or(Track {
                    uri: key,
                    playable: false,
                    ..Default::default()
                }));
            } else if let Some(p) = parse_kind(uri, UriKind::Episode) {
                let key = p.uri();
                out.episode = Some(episodes.get(&key).map(|e| (**e).clone()).unwrap_or(Episode {
                    uri: key,
                    playable: false,
                    ..Default::default()
                }));
            }
            out
        })
        .collect()
}

pub(crate) async fn playlist(args: Value) -> AppResult<Value> {
    let a: PlaylistArgs = parse_args(args)?;
    let p = parse_kind(&a.uri, UriKind::Playlist).ok_or_else(|| AppError::invalid("not a playlist uri"))?;
    let limit = a.limit.clamp(1, MAX_PAGE);
    let session = engine::session()?;
    let mut list = fetch_list(&session, &p.id, Some((a.offset, limit))).await?;
    let mut page = window(&list.contents.items, list.contents.pos() as u32, a.offset, limit);
    if page.is_none() {
        // The server answered with a window starting after `offset`: fetch everything.
        list = fetch_list(&session, &p.id, None).await?;
        page = window(&list.contents.items, list.contents.pos() as u32, a.offset, limit);
    }
    let page = page.unwrap_or_default();
    let total = list
        .length
        .filter(|l| *l >= 0)
        .map(|l| l as u32)
        .unwrap_or(list.contents.pos() as u32 + list.contents.items.len() as u32);

    let track_uris: Vec<String> = page.iter().filter_map(|i| parse_kind(i.uri(), UriKind::Track)).map(|p| p.uri()).collect();
    let episode_uris: Vec<String> =
        page.iter().filter_map(|i| parse_kind(i.uri(), UriKind::Episode)).map(|p| p.uri()).collect();
    let (tracks, episodes) =
        tokio::join!(metadata::track_lookup(&session, &track_uris), metadata::episode_lookup(&session, &episode_uris));
    let tracks = Fetched::or_all_failed(tracks, &track_uris);
    let episodes = Fetched::or_all_failed(episodes, &episode_uris);
    let partial = page_partial(&tracks, &track_uris, &episodes, &episode_uris)?;

    let uri = p.uri();
    let header = header_from_list(&uri, &list);
    HEADERS.lock().put(uri.clone(), (Instant::now(), Some(header.clone())));
    let me = engine::username().unwrap_or_else(|| session.username());
    let owner_name = list.owner_username().to_string();
    let owned = !owner_name.is_empty() && owner_name.eq_ignore_ascii_case(&me);
    let collaborative = list.attributes.collaborative();
    let can_edit = list.capabilities.can_edit_items.unwrap_or(owned || collaborative);
    let following = cached_rootlist().map(|r| r.contains(&p.id));
    to_value(&Playlist {
        uri,
        name: header.name,
        description: header.description,
        images: header.images,
        owner: header.owner,
        total_tracks: Some(total),
        collaborative,
        is_owned_by_me: owned,
        can_edit,
        revision: revision_hex(list.revision()),
        offset: a.offset,
        total,
        items: build_items(&page, &tracks.map, &episodes.map),
        following,
        partial,
    })
}

/// Whether a page whose item metadata came from `tracks`/`episodes` is partial (some requests
/// failed; those items become placeholders). Fails when requests failed and nothing resolved:
/// a page of nameless placeholders is not worth returning.
pub(crate) fn page_partial(
    tracks: &Fetched<Track>,
    track_uris: &[String],
    episodes: &Fetched<Episode>,
    episode_uris: &[String],
) -> AppResult<bool> {
    let partial = tracks.any_failed(track_uris.iter()) || episodes.any_failed(episode_uris.iter());
    if !partial {
        return Ok(false);
    }
    let error = tracks.error.clone().or_else(|| episodes.error.clone());
    if tracks.map.is_empty() && episodes.map.is_empty() {
        return Err(metadata::page_error(error.unwrap_or_else(|| AppError::unavailable("item metadata unavailable"))));
    }
    log::warn!("playlist page partial: item metadata failed ({error:?})");
    Ok(true)
}

// ---------------------------------------------------------------------------------------------
// Rootlist
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub(crate) struct RootItem {
    /// Raw item URI as stored (playlist, `spotify:start-group:…`, `spotify:end-group:…`).
    pub uri: String,
    pub attrs: Option<p4::ListAttributes>,
    pub owner: String,
    pub length: Option<i32>,
    /// `capabilities.can_edit_items` decoration, when present.
    pub can_edit_items: Option<bool>,
    /// `status_code` decoration (e.g. 404 for a playlist its owner deleted), when present.
    pub status_code: Option<i32>,
}

impl RootItem {
    /// The rootlist reports the playlist as gone: there is nothing to look up.
    fn is_gone(&self) -> bool {
        matches!(self.status_code, Some(404) | Some(410))
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Rootlist {
    pub revision: Vec<u8>,
    pub items: Vec<RootItem>,
    fetched: Instant,
}

impl Rootlist {
    /// Index and raw URI of the playlist with base62 `id`.
    pub(crate) fn find(&self, id: &str) -> Option<(usize, &str)> {
        self.items.iter().enumerate().find_map(|(i, it)| {
            parse_kind(&it.uri, UriKind::Playlist).filter(|p| p.id == id).map(|_| (i, it.uri.as_str()))
        })
    }

    pub(crate) fn contains(&self, id: &str) -> bool {
        self.find(id).is_some()
    }

    /// Playlist items (folders flattened) in rootlist order.
    pub(crate) fn playlists(&self) -> impl Iterator<Item = &RootItem> {
        self.items.iter().filter(|i| parse_kind(&i.uri, UriKind::Playlist).is_some())
    }
}

static ROOTLIST: LazyLock<Mutex<Option<Arc<Rootlist>>>> = LazyLock::new(|| Mutex::new(None));
static ROOTLIST_LOAD: LazyLock<tokio::sync::Mutex<()>> = LazyLock::new(|| tokio::sync::Mutex::new(()));

fn cached_rootlist() -> Option<Arc<Rootlist>> {
    ROOTLIST.lock().as_ref().filter(|r| r.fetched.elapsed() < Duration::from_secs(300)).cloned()
}

pub(crate) fn invalidate_rootlist() {
    *ROOTLIST.lock() = None;
}

pub(crate) fn parse_rootlist_page(list: &p4::SelectedListContent) -> Vec<RootItem> {
    let contents = &list.contents;
    // Decorations are index-aligned with the items (as the web player assumes).
    let aligned = contents.meta_items.len() == contents.items.len();
    contents
        .items
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let meta = aligned.then(|| &contents.meta_items[i]);
            RootItem {
                uri: item.uri().to_string(),
                attrs: meta.and_then(|m| m.attributes.as_ref().cloned()),
                owner: meta.map(|m| m.owner_username().to_string()).unwrap_or_default(),
                length: meta.and_then(|m| m.length),
                can_edit_items: meta.and_then(|m| m.capabilities.as_ref()).and_then(|c| c.can_edit_items),
                status_code: meta.and_then(|m| m.status_code),
            }
        })
        .collect()
}

/// The user's rootlist (all pages), cached for `max_age`. Loads are coalesced.
pub(crate) async fn rootlist(session: &Session, max_age: Duration) -> AppResult<Arc<Rootlist>> {
    let fresh = |r: &Arc<Rootlist>| r.fetched.elapsed() < max_age;
    if let Some(r) = ROOTLIST.lock().as_ref().filter(|r| fresh(r)).cloned() {
        return Ok(r);
    }
    let _guard = ROOTLIST_LOAD.lock().await;
    if let Some(r) = ROOTLIST.lock().as_ref().filter(|r| fresh(r)).cloned() {
        return Ok(r);
    }
    let user = engine::username().unwrap_or_else(|| session.username());
    if user.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "no username"));
    }
    let mut items = Vec::new();
    let mut revision = Vec::new();
    let mut from = 0usize;
    for page in 0..ROOTLIST_MAX_PAGES {
        let endpoint = format!(
            "/playlist/v2/user/{}/rootlist?decorate=revision,attributes,length,owner,capabilities,status_code&from={from}&length={ROOTLIST_PAGE}",
            encode_component(&user)
        );
        let body = http::spc_get(session, &endpoint, None).await?;
        let list: p4::SelectedListContent = http::proto(&body)?;
        if page == 0 {
            revision = list.revision().to_vec();
        }
        let n = list.contents.items.len();
        items.extend(parse_rootlist_page(&list));
        if !list.contents.truncated() || n == 0 {
            break;
        }
        from += n;
    }
    let r = Arc::new(Rootlist { revision, items, fetched: Instant::now() });
    *ROOTLIST.lock() = Some(r.clone());
    Ok(r)
}

/// Builds the folder tree. Playlists without a name (no decorations and not in `extra`) are
/// skipped; unbalanced folder markers are tolerated.
pub(crate) fn build_tree(items: &[RootItem], username: &str, extra: &HashMap<String, PlaylistRef>) -> Vec<RootlistEntry> {
    struct Folder {
        id: String,
        entry: RootlistEntry,
    }
    let mut root: Vec<RootlistEntry> = Vec::new();
    let mut stack: Vec<Folder> = Vec::new();
    fn push(stack: &mut [Folder], root: &mut Vec<RootlistEntry>, e: RootlistEntry) {
        match stack.last_mut() {
            Some(f) => f.entry.children.push(e),
            None => root.push(e),
        }
    }
    for item in items {
        if let Some(rest) = item.uri.strip_prefix("spotify:start-group:") {
            let (id, name) = rest.split_once(':').unwrap_or((rest, ""));
            stack.push(Folder {
                id: id.to_string(),
                entry: RootlistEntry {
                    kind: RootlistEntryType::Folder,
                    uri: Some(format!("spotify:user:{username}:folder:{id}")),
                    name: decode_plus(name),
                    images: Vec::new(),
                    owner: None,
                    children: Vec::new(),
                    collaborative: false,
                    can_edit: false,
                },
            });
        } else if let Some(id) = item.uri.strip_prefix("spotify:end-group:") {
            if let Some(pos) = stack.iter().rposition(|f| f.id == id) {
                while stack.len() > pos {
                    if let Some(f) = stack.pop() {
                        push(&mut stack, &mut root, f.entry);
                    }
                }
            }
        } else if let Some(p) = parse_kind(&item.uri, UriKind::Playlist) {
            let uri = p.uri();
            let owned_by = |name: &str| !name.is_empty() && name.eq_ignore_ascii_case(username);
            let entry = match (&item.attrs, extra.get(&uri)) {
                (Some(attrs), _) if !attrs.name().is_empty() => {
                    let collaborative = attrs.collaborative();
                    Some(RootlistEntry {
                        kind: RootlistEntryType::Playlist,
                        images: list_images(attrs),
                        owner: owner(&item.owner),
                        name: attrs.name().to_string(),
                        uri: Some(uri),
                        children: Vec::new(),
                        collaborative,
                        can_edit: item.can_edit_items.unwrap_or(owned_by(&item.owner) || collaborative),
                    })
                }
                (_, Some(r)) => {
                    let owner_ref = r.owner.clone().or_else(|| owner(&item.owner));
                    let owned = owner_ref.as_ref().is_some_and(|o| owned_by(&o.username));
                    Some(RootlistEntry {
                        kind: RootlistEntryType::Playlist,
                        uri: Some(uri),
                        name: r.name.clone(),
                        images: r.images.clone(),
                        owner: owner_ref,
                        children: Vec::new(),
                        collaborative: false,
                        can_edit: item.can_edit_items.unwrap_or(owned),
                    })
                }
                _ => None,
            };
            if let Some(e) = entry {
                push(&mut stack, &mut root, e);
            }
        }
    }
    while let Some(f) = stack.pop() {
        push(&mut stack, &mut root, f.entry);
    }
    root
}

/// Rootlist playlists (folders flattened) as references, resolving missing decorations.
pub(crate) async fn rootlist_refs(session: &Session, max_age: Duration) -> AppResult<Vec<PlaylistRef>> {
    let r = rootlist(session, max_age).await?;
    let extra = resolve_undecorated(session, &r).await.refs;
    Ok(r.playlists()
        .filter_map(|it| {
            let uri = parse_kind(&it.uri, UriKind::Playlist)?.uri();
            match &it.attrs {
                Some(a) if !a.name().is_empty() => Some(playlist_ref(uri, a, &it.owner, it.length)),
                _ => extra.get(&uri).cloned(),
            }
        })
        .collect())
}

/// Rootlist entries that need a header lookup: no name decoration, not reported gone.
fn undecorated(r: &Rootlist) -> Vec<String> {
    r.playlists()
        .filter(|it| it.attrs.as_ref().is_none_or(|a| a.name().is_empty()) && !it.is_gone())
        .map(|it| it.uri.clone())
        .collect()
}

async fn resolve_undecorated(session: &Session, r: &Rootlist) -> Headers {
    let missing = undecorated(r);
    if missing.is_empty() {
        Headers::default()
    } else {
        headers(session, &missing, HEADER_LOOKUPS).await
    }
}

pub(crate) async fn library_playlists(_args: Value) -> AppResult<Value> {
    let session = engine::session()?;
    let r = rootlist(&session, Duration::from_secs(30)).await?;
    let extra = resolve_undecorated(&session, &r).await;
    let user = engine::username().unwrap_or_else(|| session.username());
    let mut out = json!({ "items": build_tree(&r.items, &user, &extra.refs) });
    if extra.incomplete {
        // Playlists whose names could not be looked up right now are missing from the tree.
        out["partial"] = json!(true);
    }
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------------------------

fn source_info() -> p4::ChangeInfo {
    let mut src = p4::SourceInfo::new();
    src.client = Some(EnumOrUnknown::new(p4::source_info::Client::CLIENT));
    let mut info = p4::ChangeInfo::new();
    info.source = MessageField::some(src);
    info
}

fn delta(ops: Vec<p4::Op>) -> p4::Delta {
    let mut d = p4::Delta::new();
    d.ops = ops;
    d.info = MessageField::some(source_info());
    d
}

pub(crate) fn list_changes(base_revision: Option<Vec<u8>>, ops: Vec<p4::Op>) -> p4::ListChanges {
    let mut c = p4::ListChanges::new();
    c.base_revision = base_revision.filter(|r| !r.is_empty());
    c.deltas.push(delta(ops));
    c.set_want_resulting_revisions(false);
    c.set_want_sync_result(false);
    c
}

fn new_item(uri: &str, timestamp: Option<i64>, public: Option<bool>) -> p4::Item {
    let mut item = p4::Item::new();
    item.set_uri(uri.to_string());
    if timestamp.is_some() || public.is_some() {
        let mut attrs = p4::ItemAttributes::new();
        attrs.timestamp = timestamp;
        attrs.public = public;
        item.attributes = MessageField::some(attrs);
    }
    item
}

fn op(kind: p4::op::Kind) -> p4::Op {
    let mut o = p4::Op::new();
    o.kind = Some(EnumOrUnknown::new(kind));
    o
}

pub(crate) fn add_op(uris: &[String], position: Option<u32>, timestamp: i64, public: Option<bool>) -> p4::Op {
    let mut add = p4::Add::new();
    add.items = uris.iter().map(|u| new_item(u, Some(timestamp), public)).collect();
    match position {
        None => add.set_add_last(true),
        Some(0) => add.set_add_first(true),
        Some(i) => add.set_from_index(i as i32),
    }
    let mut o = op(p4::op::Kind::ADD);
    o.add = MessageField::some(add);
    o
}

/// REM ops for `(uri, index)` pairs: contiguous runs merged, applied from the highest index down
/// so earlier ops do not shift later ones. Each op carries the URIs for server-side validation.
pub(crate) fn rem_ops(items: &[(String, u32)]) -> Vec<p4::Op> {
    let mut sorted: Vec<(String, u32)> = items.to_vec();
    sorted.sort_by_key(|(_, i)| *i);
    sorted.dedup_by_key(|(_, i)| *i);
    let mut runs: Vec<(u32, Vec<String>)> = Vec::new();
    for (uri, idx) in sorted {
        match runs.last_mut() {
            Some((start, uris)) if *start + uris.len() as u32 == idx => uris.push(uri),
            _ => runs.push((idx, vec![uri])),
        }
    }
    runs.into_iter()
        .rev()
        .map(|(start, uris)| {
            let mut rem = p4::Rem::new();
            rem.set_from_index(start as i32);
            rem.set_length(uris.len() as i32);
            rem.items = uris.iter().map(|u| new_item(u, None, None)).collect();
            let mut o = op(p4::op::Kind::REM);
            o.rem = MessageField::some(rem);
            o
        })
        .collect()
}

pub(crate) fn mov_op(from: u32, length: u32, to: u32) -> p4::Op {
    let mut mov = p4::Mov::new();
    mov.set_from_index(from as i32);
    mov.set_length(length as i32);
    mov.set_to_index(to as i32);
    let mut o = op(p4::op::Kind::MOV);
    o.mov = MessageField::some(mov);
    o
}

pub(crate) fn update_attributes_op(name: Option<&str>, desc: Option<&str>) -> p4::Op {
    let mut values = p4::ListAttributes::new();
    let mut no_value = Vec::new();
    if let Some(n) = name {
        values.set_name(n.to_string());
    }
    match desc {
        Some("") => no_value.push(EnumOrUnknown::new(p4::ListAttributeKind::LIST_DESCRIPTION)),
        Some(d) => values.set_description(d.to_string()),
        None => {}
    }
    let mut state = p4::ListAttributesPartialState::new();
    state.values = MessageField::some(values);
    state.no_value = no_value;
    let mut upd = p4::UpdateListAttributes::new();
    upd.new_attributes = MessageField::some(state);
    let mut o = op(p4::op::Kind::UPDATE_LIST_ATTRIBUTES);
    o.update_list_attributes = MessageField::some(upd);
    o
}

/// POSTs a protobuf message as JSON (web-player encoding), falling back to protobuf when the
/// JSON encoding is rejected. Returns the raw body and whether it is JSON.
async fn post_message<M: protobuf::MessageFull>(session: &Session, endpoint: &str, msg: &M) -> Result<(Vec<u8>, bool), HttpError> {
    let json = protobuf_json_mapping::print_to_string(msg)
        .map_err(|e| HttpError { status: None, error: AppError::internal(format!("encode: {e}")) })?;
    match http::spc_send_once(session, Method::POST, endpoint, JSON, JSON, json.into_bytes()).await {
        Ok(b) => Ok((b.to_vec(), true)),
        Err(e) if matches!(e.status, Some(400) | Some(406) | Some(415)) => {
            log::info!("{endpoint}: JSON rejected ({:?}), retrying as protobuf", e.status);
            let body = msg.write_to_bytes().map_err(|e| HttpError { status: None, error: e.into() })?;
            let b = http::spc_send_once(session, Method::POST, endpoint, PROTOBUF, PROTOBUF, body).await?;
            Ok((b.to_vec(), false))
        }
        Err(e) => Err(e),
    }
}

fn revision_from_body(body: &[u8], is_json: bool) -> Option<Vec<u8>> {
    if is_json {
        revision_from_json(body)
    } else {
        http::proto::<p4::SelectedListContent>(body).ok().map(|l| l.revision().to_vec()).filter(|r| !r.is_empty())
    }
}

/// Applies changes to a playlist and returns the new revision (hex), fetching it when the
/// response does not carry one.
async fn apply_changes(session: &Session, id: &str, changes: &p4::ListChanges) -> AppResult<Option<String>> {
    let endpoint = format!("/playlist/v2/playlist/{id}/changes");
    let (body, is_json) = post_message(session, &endpoint, changes).await.map_err(map_write_error)?;
    forget_header(&format!("spotify:playlist:{id}"));
    if let Some(rev) = revision_from_body(&body, is_json) {
        return Ok(revision_hex(&rev));
    }
    Ok(fetch_list(session, id, Some((0, 1))).await.ok().and_then(|l| revision_hex(l.revision())))
}

async fn apply_rootlist_changes(session: &Session, changes: &p4::ListChanges) -> AppResult<()> {
    let user = engine::username().unwrap_or_else(|| session.username());
    let endpoint = format!("/playlist/v2/user/{}/rootlist/changes", encode_component(&user));
    let result = post_message(session, &endpoint, changes).await.map_err(map_write_error);
    invalidate_rootlist();
    result.map(|_| ())
}

async fn current_revision(session: &Session, id: &str) -> AppResult<Vec<u8>> {
    let list = fetch_list(session, id, Some((0, 1))).await?;
    Ok(list.revision().to_vec())
}

async fn base_revision(session: &Session, id: &str, given: Option<&str>) -> AppResult<Vec<u8>> {
    match given.filter(|r| !r.trim().is_empty()) {
        Some(r) => parse_revision(r),
        None => current_revision(session, id).await,
    }
}

fn playlist_id(uri: &str) -> AppResult<String> {
    Ok(parse_kind(uri, UriKind::Playlist).ok_or_else(|| AppError::invalid("not a playlist uri"))?.id)
}

fn item_uri_ok(uri: &str) -> bool {
    parse_kind(uri, UriKind::Track).is_some() || parse_kind(uri, UriKind::Episode).is_some() || uri.starts_with("spotify:local:")
}

fn revision_result(rev: Option<String>) -> Value {
    match rev {
        Some(r) => json!({ "revision": r }),
        None => json!({}),
    }
}

#[derive(Deserialize)]
struct CreateArgs {
    name: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    public: bool,
    /// Optional initial items (extension of the contract).
    #[serde(default)]
    uris: Vec<String>,
}

pub(crate) async fn create(args: Value) -> AppResult<Value> {
    let a: CreateArgs = parse_args(args)?;
    let name = a.name.trim();
    if name.is_empty() {
        return Err(AppError::invalid("playlist name must not be empty"));
    }
    let session = engine::session()?;
    let d = delta(vec![update_attributes_op(Some(name), a.description.as_deref().filter(|d| !d.is_empty()))]);
    let (body, is_json) = post_message(&session, "/playlist/v2/playlist", &d).await?;
    let (uri, revision) = if is_json {
        let v: Value = http::json(&body)?;
        let rev = revision_from_json(&body);
        (v.get("uri").and_then(Value::as_str).unwrap_or_default().to_string(), rev)
    } else {
        let reply: p4::CreateListReply = http::proto(&body)?;
        (reply.uri().to_string(), reply.revision.clone())
    };
    let p = parse_kind(&uri, UriKind::Playlist)
        .ok_or_else(|| AppError::new(ErrorCode::Unavailable, "playlist creation returned no uri"))?;
    let uri = p.uri();
    let changes = list_changes(None, vec![add_op(std::slice::from_ref(&uri), Some(0), now_ms(), Some(a.public))]);
    apply_rootlist_changes(&session, &changes).await.map_err(|e| {
        AppError::new(e.code, format!("playlist created but could not be added to the library: {}", e.message))
    })?;
    let mut revision = revision.and_then(|r| revision_hex(&r));
    let initial: Vec<String> = a.uris.into_iter().filter(|u| item_uri_ok(u)).collect();
    if !initial.is_empty() {
        let changes = list_changes(None, vec![add_op(&initial, None, now_ms(), None)]);
        revision = apply_changes(&session, &p.id, &changes).await?.or(revision);
    }
    let mut out = json!({ "uri": uri });
    if let Some(r) = revision {
        out["revision"] = json!(r);
    }
    Ok(out)
}

#[derive(Deserialize)]
struct AddArgs {
    uri: String,
    uris: Vec<String>,
    #[serde(default)]
    position: Option<u32>,
}

pub(crate) async fn add_items(args: Value) -> AppResult<Value> {
    let a: AddArgs = parse_args(args)?;
    let id = playlist_id(&a.uri)?;
    if a.uris.is_empty() {
        return Err(AppError::invalid("no uris"));
    }
    if let Some(bad) = a.uris.iter().find(|u| !item_uri_ok(u)) {
        return Err(AppError::invalid(format!("cannot add {bad} to a playlist")));
    }
    let session = engine::session()?;
    let ts = now_ms();
    let mut ops = Vec::new();
    let mut position = a.position;
    for chunk in a.uris.chunks(100) {
        ops.push(add_op(chunk, position, ts, None));
        position = position.map(|p| p + chunk.len() as u32);
    }
    // Index-based inserts need the base revision (as in the web player).
    let base = match a.position {
        Some(p) if p > 0 => Some(current_revision(&session, &id).await?),
        _ => None,
    };
    let rev = apply_changes(&session, &id, &list_changes(base, ops)).await?;
    Ok(revision_result(rev))
}

#[derive(Deserialize)]
struct RemoveItem {
    uri: String,
    index: u32,
}

#[derive(Deserialize)]
struct RemoveArgs {
    uri: String,
    items: Vec<RemoveItem>,
    #[serde(default)]
    revision: Option<String>,
}

pub(crate) async fn remove_items(args: Value) -> AppResult<Value> {
    let a: RemoveArgs = parse_args(args)?;
    let id = playlist_id(&a.uri)?;
    if a.items.is_empty() {
        return Err(AppError::invalid("no items"));
    }
    let session = engine::session()?;
    let base = base_revision(&session, &id, a.revision.as_deref()).await?;
    let pairs: Vec<(String, u32)> = a.items.into_iter().map(|i| (i.uri, i.index)).collect();
    let rev = apply_changes(&session, &id, &list_changes(Some(base), rem_ops(&pairs))).await?;
    Ok(revision_result(rev))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MoveArgs {
    uri: String,
    from_index: u32,
    #[serde(default = "one")]
    length: u32,
    /// Insert-before position in the list before the move (playlist4 MOV semantics).
    to_index: u32,
    #[serde(default)]
    revision: Option<String>,
}

fn one() -> u32 {
    1
}

pub(crate) async fn move_items(args: Value) -> AppResult<Value> {
    let a: MoveArgs = parse_args(args)?;
    let id = playlist_id(&a.uri)?;
    if a.length == 0 {
        return Err(AppError::invalid("length must be > 0"));
    }
    if a.to_index >= a.from_index && a.to_index <= a.from_index + a.length {
        // Moving a block into itself is a no-op.
        return Ok(revision_result(a.revision.filter(|r| !r.is_empty())));
    }
    let session = engine::session()?;
    let base = base_revision(&session, &id, a.revision.as_deref()).await?;
    let rev =
        apply_changes(&session, &id, &list_changes(Some(base), vec![mov_op(a.from_index, a.length, a.to_index)])).await?;
    Ok(revision_result(rev))
}

#[derive(Deserialize)]
struct DetailsArgs {
    uri: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    description: Option<String>,
}

pub(crate) async fn update_details(args: Value) -> AppResult<Value> {
    let a: DetailsArgs = parse_args(args)?;
    let id = playlist_id(&a.uri)?;
    let name = a.name.as_deref().map(str::trim);
    if name == Some("") {
        return Err(AppError::invalid("playlist name must not be empty"));
    }
    if name.is_none() && a.description.is_none() {
        return Ok(json!({}));
    }
    let session = engine::session()?;
    let changes = list_changes(None, vec![update_attributes_op(name, a.description.as_deref().map(str::trim))]);
    let rev = apply_changes(&session, &id, &changes).await?;
    invalidate_rootlist();
    Ok(revision_result(rev))
}

/// Adds a playlist to the rootlist (no-op if present).
pub(crate) async fn follow_uri(session: &Session, uri: &str) -> AppResult<()> {
    let id = playlist_id(uri)?;
    let r = rootlist(session, Duration::from_secs(10)).await?;
    if r.contains(&id) {
        return Ok(());
    }
    let uri = format!("spotify:playlist:{id}");
    let changes = list_changes(None, vec![add_op(std::slice::from_ref(&uri), Some(0), now_ms(), None)]);
    let result = apply_rootlist_changes(session, &changes).await;
    // A failed lookup remembered from before must not hide the newly followed playlist.
    forget_header(&uri);
    result
}

/// Removes a playlist from the rootlist (no-op if absent). Retries once on a revision conflict.
pub(crate) async fn unfollow_uri(session: &Session, uri: &str) -> AppResult<()> {
    let id = playlist_id(uri)?;
    for attempt in 0..2 {
        let r = rootlist(session, Duration::ZERO).await?;
        let Some((index, raw)) = r.find(&id) else { return Ok(()) };
        let changes = list_changes(Some(r.revision.clone()), rem_ops(&[(raw.to_string(), index as u32)]));
        match apply_rootlist_changes(session, &changes).await {
            Err(e) if attempt == 0 && e.message == CONFLICT_MESSAGE => continue,
            other => return other,
        }
    }
    Ok(())
}

pub(crate) async fn follow(args: Value) -> AppResult<Value> {
    let a: super::pages::UriArgs = parse_args(args)?;
    let session = engine::session()?;
    follow_uri(&session, &a.uri).await?;
    Ok(json!({}))
}

pub(crate) async fn unfollow(args: Value) -> AppResult<Value> {
    let a: super::pages::UriArgs = parse_args(args)?;
    let session = engine::session()?;
    unfollow_uri(&session, &a.uri).await?;
    Ok(json!({}))
}

/// Whether each playlist URI is in the rootlist (cached ≤ 60 s).
pub(crate) async fn rootlist_contains(session: &Session, uris: &[String]) -> AppResult<Vec<bool>> {
    let r = rootlist(session, Duration::from_secs(60)).await?;
    Ok(uris.iter().map(|u| parse_kind(u, UriKind::Playlist).is_some_and(|p| r.contains(&p.id))).collect())
}

/// Removes duplicates while keeping order.
pub(crate) fn dedup(uris: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    uris.iter().filter(|u| seen.insert(u.as_str())).cloned().collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catalog::metadata::tests::gid;
    use protobuf::Message;

    fn attrs(name: &str) -> p4::ListAttributes {
        let mut a = p4::ListAttributes::new();
        a.set_name(name.into());
        a
    }

    fn rootlist_fixture() -> p4::SelectedListContent {
        let mut list = p4::SelectedListContent::new();
        list.set_revision(vec![0, 0, 0, 7, 0xaa, 0xbb]);
        let mut contents = p4::ListItems::new();
        contents.set_pos(0);
        contents.set_truncated(false);
        let uris = [
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            "spotify:start-group:abc123:My+Folder%21",
            "spotify:user:bob:playlist:5ihSl7a56tjMkVSzwQpSnl",
            "spotify:start-group:def456:Inner",
            "spotify:playlist:1yQ6yj6Gyd1kkMqk4YaRRd",
            "spotify:end-group:def456",
            "spotify:end-group:abc123",
            "spotify:playlist:2UZk7JjJnbTut1w8fqs3JL",
            "spotify:end-group:nonexistent",
            "spotify:playlist:not-a-valid-id",
        ];
        for (i, uri) in uris.iter().enumerate() {
            let mut item = p4::Item::new();
            item.set_uri(uri.to_string());
            contents.items.push(item);
            let mut meta = p4::MetaItem::new();
            match i {
                0 => {
                    let mut a = attrs("Today's Top Hits");
                    let mut ps = p4::PictureSize::new();
                    ps.set_target_name("default".into());
                    ps.set_url("https://i.scdn.co/image/ab67706f00000002aaaa".into());
                    a.picture_size.push(ps);
                    meta.attributes = MessageField::some(a);
                    meta.set_owner_username("spotify".into());
                    meta.set_length(50);
                }
                2 => {
                    let mut a = attrs("Bob's mix");
                    a.set_collaborative(true);
                    a.set_picture(hex::decode("ab67616d0000b27315ebbedaacef61af244262a8").unwrap());
                    meta.attributes = MessageField::some(a);
                    meta.set_owner_username("bob".into());
                }
                4 => {
                    meta.attributes = MessageField::some(attrs("Deep"));
                    meta.set_owner_username("alice".into());
                }
                _ => {}
            }
            contents.meta_items.push(meta);
        }
        list.contents = MessageField::some(contents);
        list
    }

    #[test]
    fn parses_rootlist_with_folders() {
        let bytes = rootlist_fixture().write_to_bytes().unwrap();
        let list: p4::SelectedListContent = http::proto(&bytes).unwrap();
        let items = parse_rootlist_page(&list);
        assert_eq!(items.len(), 10);
        // Playlist 7 has no decorations: resolved through `extra`.
        let mut extra = HashMap::new();
        extra.insert(
            "spotify:playlist:2UZk7JjJnbTut1w8fqs3JL".to_string(),
            PlaylistRef { uri: "spotify:playlist:2UZk7JjJnbTut1w8fqs3JL".into(), name: "Resolved".into(), ..Default::default() },
        );
        let tree = build_tree(&items, "alice", &extra);
        let v = serde_json::to_value(&tree).unwrap();
        assert_eq!(v[0]["type"], "playlist");
        assert_eq!(v[0]["name"], "Today's Top Hits");
        assert_eq!(v[0]["owner"]["displayName"], "Spotify");
        assert_eq!(v[0]["images"][0]["width"], 300);
        assert_eq!(v[1]["type"], "folder");
        assert_eq!(v[1]["name"], "My Folder!");
        assert_eq!(v[1]["uri"], "spotify:user:alice:folder:abc123");
        assert_eq!(v[1]["children"][0]["uri"], "spotify:playlist:5ihSl7a56tjMkVSzwQpSnl");
        assert_eq!(
            v[1]["children"][0]["images"][0]["url"],
            "https://i.scdn.co/image/ab67616d0000b27315ebbedaacef61af244262a8"
        );
        assert_eq!(v[1]["children"][1]["type"], "folder");
        assert_eq!(v[1]["children"][1]["children"][0]["name"], "Deep");
        // canEdit: owner == me (alice) or collaborative.
        assert_eq!(v[0]["canEdit"], false);
        assert_eq!(v[0]["collaborative"], false);
        assert_eq!(v[1]["children"][0]["collaborative"], true);
        assert_eq!(v[1]["children"][0]["canEdit"], true);
        assert_eq!(v[1]["children"][1]["children"][0]["canEdit"], true);
        assert_eq!(v[2]["name"], "Resolved");
        assert_eq!(tree.len(), 3, "invalid ids and stray end markers are ignored");

        let mut r = Rootlist { revision: list.revision().to_vec(), items, fetched: Instant::now() };
        assert_eq!(r.find("5ihSl7a56tjMkVSzwQpSnl").map(|(i, _)| i), Some(2));
        assert_eq!(r.playlists().count(), 4);
        // Only the undecorated playlist needs a header lookup, unless the rootlist reports it gone.
        assert_eq!(undecorated(&r), ["spotify:playlist:2UZk7JjJnbTut1w8fqs3JL"]);
        r.items[7].status_code = Some(404);
        assert!(undecorated(&r).is_empty());
    }

    #[test]
    fn parses_status_code_decorations() {
        let mut list = rootlist_fixture();
        list.contents.as_mut().unwrap().meta_items[7].set_status_code(404);
        let items = parse_rootlist_page(&list);
        assert_eq!(items[7].status_code, Some(404));
        assert!(items[7].is_gone() && !items[0].is_gone());
    }

    #[test]
    fn classifies_header_failures() {
        let err = |status: Option<u16>, code: ErrorCode| HttpError { status, error: AppError::new(code, "x") };
        assert!(matches!(header_failure(&err(Some(404), ErrorCode::NotFound)), HeaderOutcome::Gone));
        assert!(matches!(header_failure(&err(Some(403), ErrorCode::Unavailable)), HeaderOutcome::Gone));
        assert!(matches!(header_failure(&err(None, ErrorCode::Network)), HeaderOutcome::Failed));
        assert!(matches!(header_failure(&err(Some(429), ErrorCode::RateLimited)), HeaderOutcome::Failed));
        assert!(matches!(header_failure(&err(Some(503), ErrorCode::Unavailable)), HeaderOutcome::Failed));
    }

    #[tokio::test]
    async fn header_lookups_remember_dead_playlists_and_respect_the_budget() {
        let (dead, live, unknown) =
            ("spotify:playlist:0dGxZ1eGqsqLRFmIGHbZbS", "spotify:playlist:0DuAFMvFHpXWLn6jJBoCnw", "spotify:playlist:0Gd6DzmDyb3DMrg3kg4NFe");
        HEADERS.lock().put(dead.into(), (Instant::now(), None));
        let live_ref = PlaylistRef { uri: live.into(), name: "Live".into(), ..Default::default() };
        HEADERS.lock().put(live.into(), (Instant::now(), Some(live_ref)));
        // An unconnected session: any request would fail, so a complete answer proves none was sent.
        let session = Session::new(Default::default(), None);
        let h = headers(&session, &[dead.to_string(), live.to_string()], 10).await;
        assert_eq!(h.refs.keys().collect::<Vec<_>>(), [live]);
        assert!(!h.incomplete, "a known-dead playlist is neither fetched nor a failure");
        // Lookups beyond the budget are left for a later call.
        let h = headers(&session, &[dead.to_string(), unknown.to_string()], 0).await;
        assert!(h.incomplete && h.refs.is_empty());
        // Following a playlist forgets what was known about it.
        forget_header(dead);
        assert!(HEADERS.lock().peek(dead).is_none());
        // Expired negative entries are looked up again.
        if let Some(expired) = Instant::now().checked_sub(HEADER_NEGATIVE_TTL) {
            HEADERS.lock().put(dead.into(), (expired, None));
            assert_eq!(cached_header(&mut HEADERS.lock(), dead), None);
        }
    }

    #[test]
    fn unbalanced_folders_are_closed() {
        let items: Vec<RootItem> = ["spotify:start-group:x:Open", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"]
            .iter()
            .map(|u| RootItem {
                uri: u.to_string(),
                attrs: Some(attrs("P")),
                owner: String::new(),
                length: None,
                can_edit_items: None,
                status_code: None,
            })
            .collect();
        let tree = build_tree(&items, "u", &HashMap::new());
        assert_eq!(tree.len(), 1);
        assert_eq!(tree[0].children.len(), 1);
    }

    #[test]
    fn builds_playlist_items_keeping_indices() {
        let mut items = Vec::new();
        for (uri, ts) in [
            ("spotify:track:4uLU6hMCjMI75M1A2tKUQC", 1_700_000_000_000i64),
            ("spotify:local:Artist+Name:Album:My+Song:215", 1_700_000_000_000_000),
            ("spotify:episode:512ojhOuo1ktJprKbVcKyQ", 0),
            ("spotify:track:7GhIk7Il098yCjg4BQjzvb", 5),
            ("spotify:unknown-thing:abc", 6),
        ] {
            let mut item = p4::Item::new();
            item.set_uri(uri.into());
            let mut a = p4::ItemAttributes::new();
            a.set_timestamp(ts);
            a.set_added_by("bob".into());
            a.set_item_id(vec![0xde, 0xad]);
            item.attributes = MessageField::some(a);
            items.push(item);
        }
        let mut tracks = HashMap::new();
        let t = crate::catalog::metadata::convert_track(
            &crate::catalog::metadata::tests::sample_track(),
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            &Default::default(),
        )
        .unwrap();
        tracks.insert(t.uri.clone(), Arc::new(t));
        let out = build_items(&items, &tracks, &HashMap::new());
        assert_eq!(out.len(), 5, "one entry per playlist item");
        assert_eq!(out[0].track.as_ref().unwrap().name, "Never Gonna Give You Up");
        assert_eq!(out[0].uid.as_deref(), Some("dead"));
        assert_eq!(out[0].added_at, Some(1_700_000_000_000));
        assert_eq!(out[1].added_at, Some(1_700_000_000_000), "microseconds normalised");
        let local = out[1].track.as_ref().unwrap();
        assert_eq!(local.name, "My Song");
        assert_eq!(local.duration_ms, 215_000);
        assert!(!local.playable);
        assert_eq!(out[2].episode.as_ref().unwrap().uri, "spotify:episode:512ojhOuo1ktJprKbVcKyQ");
        assert!(!out[3].track.as_ref().unwrap().playable, "unresolved track placeholder");
        assert!(out[4].track.is_none() && out[4].episode.is_none());
        let _ = gid("4uLU6hMCjMI75M1A2tKUQC");
    }

    #[test]
    fn pages_with_failed_metadata_are_partial_or_fail() {
        use crate::catalog::pages::tests::fetched;
        let t = vec!["spotify:track:a".to_string(), "spotify:track:b".to_string()];
        let none: Vec<String> = Vec::new();
        let no_episodes = || fetched::<Episode>(&[], &[]);
        let a = Track { uri: t[0].clone(), name: "A".into(), ..Default::default() };
        // Complete (b merely has no data): not partial.
        assert!(!page_partial(&fetched(&[(t[0].as_str(), a.clone())], &[]), &t, &no_episodes(), &none).unwrap());
        // b's request failed: partial.
        assert!(page_partial(&fetched(&[(t[0].as_str(), a)], &[t[1].as_str()]), &t, &no_episodes(), &none).unwrap());
        // Every request failed: a retryable error instead of a page of placeholders.
        let err = page_partial(&fetched::<Track>(&[], &[t[0].as_str(), t[1].as_str()]), &t, &no_episodes(), &none).unwrap_err();
        assert_eq!(err.code, ErrorCode::Network);
    }

    #[test]
    fn windows_pages() {
        let v: Vec<u32> = (0..10).collect();
        assert_eq!(window(&v, 0, 3, 2), Some(vec![3, 4]));
        // Server honoured `from`: the returned items already start at the offset.
        assert_eq!(window(&v, 3, 3, 2), Some(vec![0, 1]));
        assert_eq!(window(&v, 2, 3, 2), Some(vec![1, 2]));
        assert_eq!(window(&v, 5, 3, 2), None);
        assert_eq!(window(&v, 0, 20, 2), Some(vec![]));
    }

    #[test]
    fn encodes_changes_like_the_web_player() {
        let rem = rem_ops(&[
            ("spotify:track:a".into(), 5),
            ("spotify:track:b".into(), 1),
            ("spotify:track:c".into(), 2),
            ("spotify:track:d".into(), 6),
        ]);
        let changes = list_changes(Some(vec![1, 2, 3]), rem);
        let v: Value = serde_json::from_str(&protobuf_json_mapping::print_to_string(&changes).unwrap()).unwrap();
        assert_eq!(v["baseRevision"], "AQID");
        let ops = &v["deltas"][0]["ops"];
        assert_eq!(ops[0]["kind"], "REM");
        assert_eq!(ops[0]["rem"]["fromIndex"], 5);
        assert_eq!(ops[0]["rem"]["length"], 2);
        assert_eq!(ops[0]["rem"]["items"][1]["uri"], "spotify:track:d");
        assert_eq!(ops[1]["rem"]["fromIndex"], 1);
        assert_eq!(ops[1]["rem"]["items"][0]["uri"], "spotify:track:b");
        assert_eq!(v["deltas"][0]["info"]["source"]["client"], "CLIENT");
        assert_eq!(v["wantResultingRevisions"], false);

        let add = list_changes(None, vec![add_op(&["spotify:track:x".into()], None, 42, None)]);
        let v: Value = serde_json::from_str(&protobuf_json_mapping::print_to_string(&add).unwrap()).unwrap();
        assert!(v.get("baseRevision").is_none());
        assert_eq!(v["deltas"][0]["ops"][0]["add"]["addLast"], true);
        assert_eq!(v["deltas"][0]["ops"][0]["add"]["items"][0]["attributes"]["timestamp"], "42");

        let mv = mov_op(2, 1, 5);
        let v: Value = serde_json::from_str(&protobuf_json_mapping::print_to_string(&mv).unwrap()).unwrap();
        assert_eq!(v["kind"], "MOV");
        assert_eq!(v["mov"]["toIndex"], 5);

        let upd = update_attributes_op(Some("New"), Some(""));
        let v: Value = serde_json::from_str(&protobuf_json_mapping::print_to_string(&upd).unwrap()).unwrap();
        assert_eq!(v["kind"], "UPDATE_LIST_ATTRIBUTES");
        assert_eq!(v["updateListAttributes"]["newAttributes"]["values"]["name"], "New");
        assert_eq!(v["updateListAttributes"]["newAttributes"]["noValue"][0], "LIST_DESCRIPTION");
        // Protobuf fallback encodes without required-field errors.
        assert!(changes.write_to_bytes().is_ok());
    }

    #[test]
    fn parses_write_responses() {
        assert_eq!(revision_from_json(br#"{"revision":"AAAAB6q7","resultingRevisions":[]}"#), Some(vec![0, 0, 0, 7, 0xaa, 0xbb]));
        assert_eq!(revision_from_json(br#"{"uri":"spotify:playlist:x"}"#), None);
        assert_eq!(revision_from_json(b"not json"), None);
        assert_eq!(revision_hex(&[0, 0, 0, 7, 0xaa]), Some("00000007aa".into()));
        assert!(parse_revision("zz").is_err());
        let e = map_write_error(HttpError { status: Some(409), error: AppError::internal("x") });
        assert_eq!(e.code, ErrorCode::InvalidArgument);
        assert!(e.message.contains("revision"));
    }

    #[test]
    fn picture_widths() {
        assert_eq!(picture_width("large", "https://mosaic.scdn.co/640/ab67"), Some(640));
        assert_eq!(picture_width("small", "https://i.scdn.co/image/x"), Some(60));
        assert_eq!(picture_width("other", "https://i.scdn.co/image/x"), None);
    }
}
