//! Batched, cached metadata through spclient extended-metadata (`TRACK_V4`, `EPISODE_V4`,
//! `ALBUM_V4`, `ARTIST_V4`, `SHOW_V4`). Used by the catalog RPCs and by `connect` to enrich
//! playback snapshots.
//!
//! * Requests are batched (≤ [`BATCH_SIZE`] entities per POST, ≤ 3 POSTs in flight), so a page
//!   of 100 tracks costs one rate-limiter token.
//! * Results are kept in LRU caches (tracks 4096, episodes 1024, albums 512, artists/shows 256).
//! * Concurrent requests for the same URIs share one in-flight fetch. Fetches run as detached
//!   tasks (bounded by the HTTP timeout) so a cancelled RPC never strands other waiters.
//! * Raw protobufs are converted leniently: an entity that fails to convert is skipped, it never
//!   fails the batch.
//! * Lookups tell "the server has no data for this URI" (omitted) apart from "the request for it
//!   failed" ([`Fetched::failed`]), so pages can mark themselves partial instead of presenting a
//!   network blip as missing items. A lookup fails as a whole only when nothing resolved.

use super::context;
use super::http;
use super::util::{
    format_date, image_from_file_id, normalize_images, parse_kind, strip_html, uri_from_gid, UriKind,
};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{AlbumRef, AlbumType, ArtistRef, Episode, Image, ShowRef, Track};
use futures_util::future::{join_all, BoxFuture, FutureExt, Shared};
use futures_util::stream::{self, StreamExt};
use librespot_core::Session;
use librespot_protocol::extended_metadata::{
    BatchedEntityRequest, BatchedEntityRequestHeader, BatchedExtensionResponse, EntityRequest, ExtensionQuery,
};
use librespot_protocol::extension_kind::ExtensionKind;
use librespot_protocol::metadata as pm;
use lru::LruCache;
use parking_lot::Mutex;
use protobuf::{EnumOrUnknown, MessageField};
use std::collections::{HashMap, HashSet};
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

/// Maximum entities per extended-metadata request.
pub(crate) const BATCH_SIZE: usize = 100;
/// Concurrent extended-metadata requests per lookup.
const CONCURRENCY: usize = 3;
/// Cached entries older than this are refetched (stale entries still serve `cached_*`).
const MAX_AGE: Duration = Duration::from_secs(12 * 3600);
/// Artist pages change (top tracks, new releases) more often.
const ARTIST_MAX_AGE: Duration = Duration::from_secs(30 * 60);
/// Show episode lists resolved without `SHOW_V4` (kept as long as the show metadata).
const SHOW_EPISODES_MAX_AGE: Duration = ARTIST_MAX_AGE;
/// Episodes of a show read from context-resolve when `SHOW_V4_EPISODES_ASSOC` has none.
const SHOW_CONTEXT_MAX_ITEMS: usize = 2000;
const SHOW_CONTEXT_MAX_PAGES: usize = 20;

// ---------------------------------------------------------------------------------------------
// Public API (stable contract, used by `connect`)
// ---------------------------------------------------------------------------------------------

/// Returns metadata for `uris` (track URIs), from the cache where possible, fetching the rest in
/// batches. Unknown/unavailable URIs are omitted. Order follows `uris`.
pub async fn tracks(session: &Session, uris: &[String]) -> AppResult<Vec<Track>> {
    let (order, f) = lookup(&TRACKS, session, uris, UriKind::Track, fetch_tracks).await?;
    Ok(order.iter().filter_map(|u| f.map.get(u)).map(|t| (**t).clone()).collect())
}

/// Same as [`tracks`] for episode URIs.
pub async fn episodes(session: &Session, uris: &[String]) -> AppResult<Vec<Episode>> {
    let (order, f) = lookup(&EPISODES, session, uris, UriKind::Episode, fetch_episodes).await?;
    Ok(order.iter().filter_map(|u| f.map.get(u)).map(|e| (**e).clone()).collect())
}

/// Cached track metadata without network access.
pub fn cached_track(uri: &str) -> Option<Track> {
    let uri = parse_kind(uri, UriKind::Track)?.uri();
    TRACKS.peek(&uri).map(|t| (*t).clone())
}

/// Cached episode metadata without network access.
pub fn cached_episode(uri: &str) -> Option<Episode> {
    let uri = parse_kind(uri, UriKind::Episode)?.uri();
    EPISODES.peek(&uri).map(|e| (*e).clone())
}

// ---------------------------------------------------------------------------------------------
// Crate-internal typed lookups
// ---------------------------------------------------------------------------------------------

/// Album data needed by album pages and album references.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct AlbumMeta {
    pub album: AlbumRef,
    pub label: Option<String>,
    pub copyrights: Vec<String>,
    pub release_date_precision: Option<String>,
    /// Track URIs in disc order.
    pub track_uris: Vec<String>,
    /// Sort key (year, month, day) for discographies.
    pub date_key: (i32, i32, i32),
}

/// Artist data needed by artist pages and artist references.
#[derive(Debug, Clone, PartialEq, Default)]
pub(crate) struct ArtistMeta {
    pub artist: ArtistRef,
    pub header_images: Vec<Image>,
    pub biography: Option<String>,
    /// `(country, track URIs)`; country `""` is the global list.
    pub top_tracks: Vec<(String, Vec<String>)>,
    pub albums: Vec<String>,
    pub singles: Vec<String>,
    pub compilations: Vec<String>,
    pub appears_on: Vec<String>,
    pub related: Vec<ArtistRef>,
}

/// Show data needed by show pages and show references.
#[derive(Debug, Clone, PartialEq, Default)]
pub(crate) struct ShowMeta {
    pub show: ShowRef,
    pub description: String,
    pub episode_uris: Vec<String>,
}

/// Outcome of a batched lookup.
#[derive(Debug)]
pub(crate) struct Fetched<T> {
    /// Resolved entities by canonical URI.
    pub map: HashMap<String, Arc<T>>,
    /// Requested URIs whose request failed (network, rate limit, server error): their data is
    /// unknown right now. URIs in neither `map` nor `failed` have no data on the server.
    pub failed: HashSet<String>,
    /// First fetch error; set whenever `failed` is non-empty.
    pub error: Option<AppError>,
}

impl<T> Default for Fetched<T> {
    fn default() -> Self {
        Self { map: HashMap::new(), failed: HashSet::new(), error: None }
    }
}

impl<T> Fetched<T> {
    /// True when `uri` is unresolved because its request failed.
    pub(crate) fn is_failed(&self, uri: &str) -> bool {
        !self.map.contains_key(uri) && self.failed.contains(uri)
    }

    /// Whether some of `uris` are unresolved because their request failed.
    pub(crate) fn any_failed<'a>(&self, mut uris: impl Iterator<Item = &'a String>) -> bool {
        !self.failed.is_empty() && uris.any(|u| self.is_failed(u))
    }

    /// The outcome of a lookup, with a lookup that failed as a whole turned into one in which
    /// every one of `uris` failed.
    pub(crate) fn or_all_failed(r: AppResult<Self>, uris: &[String]) -> Self {
        r.unwrap_or_else(|e| Self { map: HashMap::new(), failed: uris.iter().cloned().collect(), error: Some(e) })
    }
}

impl<T: Clone> Fetched<T> {
    /// Entities for `uris` in order. Unresolved URIs become `placeholder(uri)`: every one when
    /// `keep_missing` (lists whose slots must stay aligned), else only those whose request failed
    /// (URIs the server has no data for are dropped).
    pub(crate) fn ordered(&self, uris: &[String], keep_missing: bool, placeholder: impl Fn(&str) -> T) -> Vec<T> {
        uris.iter()
            .filter_map(|u| match self.map.get(u) {
                Some(v) => Some((**v).clone()),
                None if keep_missing || self.failed.contains(u) => Some(placeholder(u)),
                None => None,
            })
            .collect()
    }
}

/// Stand-in for a track without metadata: only the URI, `playable:false` (docs §6.5).
pub(crate) fn placeholder_track(uri: &str) -> Track {
    Track { uri: uri.to_string(), playable: false, ..Default::default() }
}

/// Stand-in for an episode without metadata: only the URI, `playable:false` (docs §6.5).
pub(crate) fn placeholder_episode(uri: &str) -> Episode {
    Episode { uri: uri.to_string(), playable: false, ..Default::default() }
}

/// An item-metadata failure reported for a whole page: transport and session codes are kept,
/// anything else (e.g. NOT_FOUND for the batch request itself) becomes UNAVAILABLE, so callers
/// never mistake "item metadata unavailable right now" for "this page does not exist".
pub(crate) fn page_error(e: AppError) -> AppError {
    match e.code {
        ErrorCode::Network
        | ErrorCode::RateLimited
        | ErrorCode::Unavailable
        | ErrorCode::Cancelled
        | ErrorCode::NotLoggedIn
        | ErrorCode::NotConnected => e,
        _ => AppError { code: ErrorCode::Unavailable, ..e },
    }
}

pub(crate) async fn track_lookup(session: &Session, uris: &[String]) -> AppResult<Fetched<Track>> {
    Ok(lookup(&TRACKS, session, uris, UriKind::Track, fetch_tracks).await?.1)
}

pub(crate) async fn episode_lookup(session: &Session, uris: &[String]) -> AppResult<Fetched<Episode>> {
    Ok(lookup(&EPISODES, session, uris, UriKind::Episode, fetch_episodes).await?.1)
}

pub(crate) async fn album_lookup(session: &Session, uris: &[String]) -> AppResult<Fetched<AlbumMeta>> {
    Ok(lookup(&ALBUMS, session, uris, UriKind::Album, fetch_albums).await?.1)
}

pub(crate) async fn artist_lookup(session: &Session, uris: &[String]) -> AppResult<Fetched<ArtistMeta>> {
    Ok(lookup(&ARTISTS, session, uris, UriKind::Artist, fetch_artists).await?.1)
}

pub(crate) async fn show_lookup(session: &Session, uris: &[String]) -> AppResult<Fetched<ShowMeta>> {
    Ok(lookup(&SHOWS, session, uris, UriKind::Show, fetch_shows).await?.1)
}

pub(crate) async fn track_map(session: &Session, uris: &[String]) -> AppResult<HashMap<String, Arc<Track>>> {
    Ok(track_lookup(session, uris).await?.map)
}

pub(crate) async fn episode_map(session: &Session, uris: &[String]) -> AppResult<HashMap<String, Arc<Episode>>> {
    Ok(episode_lookup(session, uris).await?.map)
}

pub(crate) async fn albums(session: &Session, uris: &[String]) -> AppResult<HashMap<String, Arc<AlbumMeta>>> {
    Ok(album_lookup(session, uris).await?.map)
}

pub(crate) async fn artists(session: &Session, uris: &[String]) -> AppResult<HashMap<String, Arc<ArtistMeta>>> {
    Ok(artist_lookup(session, uris).await?.map)
}

pub(crate) async fn shows(session: &Session, uris: &[String]) -> AppResult<HashMap<String, Arc<ShowMeta>>> {
    Ok(show_lookup(session, uris).await?.map)
}

/// Single album (NOT_FOUND when the server has no data).
pub(crate) async fn album(session: &Session, uri: &str) -> AppResult<Arc<AlbumMeta>> {
    single(albums(session, &[uri.to_string()]).await?, uri, "album")
}

pub(crate) async fn artist(session: &Session, uri: &str) -> AppResult<Arc<ArtistMeta>> {
    single(artists(session, &[uri.to_string()]).await?, uri, "artist")
}

pub(crate) async fn show(session: &Session, uri: &str) -> AppResult<Arc<ShowMeta>> {
    single(shows(session, &[uri.to_string()]).await?, uri, "show")
}

fn single<T>(mut map: HashMap<String, Arc<T>>, uri: &str, what: &str) -> AppResult<Arc<T>> {
    map.drain().next().map(|(_, v)| v).ok_or_else(|| AppError::not_found(format!("{what} {uri} not found")))
}

/// Episode URIs of a show via the `SHOW_V4_EPISODES_ASSOC` extension (used when `SHOW_V4`
/// carries no episode list).
pub(crate) async fn show_episode_assoc(session: &Session, show_uri: &str) -> AppResult<Vec<String>> {
    let raw = fetch_extended(session, ExtensionKind::SHOW_V4_EPISODES_ASSOC, &[show_uri.to_string()]).await?;
    if let Some(e) = raw.error {
        return Err(e);
    }
    let Some(bytes) = raw.found.into_values().next() else { return Ok(Vec::new()) };
    let assoc: librespot_protocol::entity_extension_data::Assoc = http::proto(&bytes)?;
    Ok(assoc.plain_list.entity_uri.iter().filter_map(|u| parse_kind(u, UriKind::Episode)).map(|p| p.uri()).collect())
}

/// Episode URIs (newest first) of a show whose `SHOW_V4` carries no episode list:
/// `SHOW_V4_EPISODES_ASSOC`, else context-resolve. Cached and shared between pages, so paging a
/// show costs one resolution. Fails (instead of returning an empty list) when the lookups failed.
pub(crate) async fn show_episode_uris(session: &Session, show_uri: &str) -> AppResult<Arc<Vec<String>>> {
    let (_, f) = lookup(&SHOW_EPISODES, session, &[show_uri.to_string()], UriKind::Show, fetch_show_episodes).await?;
    match f.map.into_values().next() {
        Some(list) => Ok(list),
        None => match f.error {
            Some(e) => Err(e),
            // Both sources answered and the show has no episodes (empty lists are not cached).
            None => Ok(Arc::new(Vec::new())),
        },
    }
}

fn fetch_show_episodes(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<Vec<String>>>> {
    async move {
        let mut out = Partial::default();
        for uri in uris {
            match resolve_show_episodes(&session, &uri).await {
                Ok(list) if !list.is_empty() => {
                    out.found.insert(uri, list);
                }
                Ok(_) => {}
                Err(e) => {
                    out.failed.push(uri);
                    out.error.get_or_insert(e);
                }
            }
        }
        Ok(out)
    }
    .boxed()
}

async fn resolve_show_episodes(session: &Session, show_uri: &str) -> AppResult<Vec<String>> {
    let assoc = show_episode_assoc(session, show_uri).await;
    if let Ok(list) = &assoc {
        if !list.is_empty() {
            return Ok(list.clone());
        }
    }
    let ctx = context::resolve(session, show_uri, SHOW_CONTEXT_MAX_ITEMS, SHOW_CONTEXT_MAX_PAGES).await;
    show_episodes_outcome(assoc, ctx.map(|items| items.into_iter().map(|i| i.uri).collect()))
}

/// Combines the two episode-list sources: a list from either wins; "no episodes" needs both to
/// agree (context-resolve answers NOT_FOUND for an empty context); otherwise the most telling
/// error is returned, never an empty list.
pub(crate) fn show_episodes_outcome(assoc: AppResult<Vec<String>>, ctx: AppResult<Vec<String>>) -> AppResult<Vec<String>> {
    let episodes = |uris: Vec<String>| -> Vec<String> {
        uris.iter().filter_map(|u| parse_kind(u, UriKind::Episode)).map(|p| p.uri()).collect()
    };
    match (assoc, ctx) {
        (Ok(a), _) if !a.is_empty() => Ok(a),
        (_, Ok(c)) => Ok(episodes(c)),
        (Ok(_), Err(c)) if c.code == ErrorCode::NotFound => Ok(Vec::new()),
        (Ok(_), Err(c)) => Err(c),
        (Err(a), Err(c)) => {
            let transport = |e: &AppError| matches!(e.code, ErrorCode::Network | ErrorCode::RateLimited);
            Err(if !transport(&a) && transport(&c) { c } else { a })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Context for availability decisions
// ---------------------------------------------------------------------------------------------

/// Session-derived inputs of the availability check.
#[derive(Debug, Clone, Default)]
pub(crate) struct Ctx {
    pub country: String,
    pub catalogue: String,
    pub filter_explicit: bool,
    pub now_ms: i64,
}

impl Ctx {
    pub(crate) fn new(session: &Session) -> Self {
        Self {
            country: session.country(),
            catalogue: session.get_user_attribute("catalogue").unwrap_or_else(|| "premium".into()),
            filter_explicit: session.filter_explicit_content(),
            now_ms: super::util::now_ms(),
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Raw batched fetch
// ---------------------------------------------------------------------------------------------

/// What a fetch returned: decoded entities plus the requested URIs whose request failed.
#[derive(Debug)]
pub(crate) struct Partial<T> {
    pub found: HashMap<String, T>,
    pub failed: Vec<String>,
    /// First error; set whenever `failed` is non-empty.
    pub error: Option<AppError>,
}

impl<T> Default for Partial<T> {
    fn default() -> Self {
        Self { found: HashMap::new(), failed: Vec::new(), error: None }
    }
}

impl<T> Partial<T> {
    fn map_found<U>(self, f: impl FnOnce(HashMap<String, T>) -> HashMap<String, U>) -> Partial<U> {
        Partial { found: f(self.found), failed: self.failed, error: self.error }
    }
}

/// Fetches one extension kind for `uris` in batches. Returns `entity_uri → Any.value` for the
/// entities the server answered and the URIs of failed batches; fails only if every batch failed.
pub(crate) async fn fetch_extended(session: &Session, kind: ExtensionKind, uris: &[String]) -> AppResult<Partial<Vec<u8>>> {
    let mut seen = HashSet::new();
    let uniq: Vec<String> = uris.iter().filter(|u| seen.insert(u.as_str())).cloned().collect();
    if uniq.is_empty() {
        return Ok(Partial::default());
    }
    let ctx = Ctx::new(session);
    let batches: Vec<Vec<String>> = uniq.chunks(BATCH_SIZE).map(|c| c.to_vec()).collect();
    type Reply = (Vec<String>, AppResult<HashMap<String, Vec<u8>>>);
    let results: Vec<Reply> = stream::iter(batches)
        .map(|batch| {
            let ctx = &ctx;
            async move {
                let req = build_request(kind, &batch, ctx);
                let resp = http::timed(session.spclient().get_extended_metadata(req)).await;
                (batch, resp.map(parse_response).map_err(AppError::from))
            }
        })
        .buffer_unordered(CONCURRENCY)
        .collect()
        .await;
    let mut out = Partial::default();
    let mut any_ok = false;
    for (batch, r) in results {
        match r {
            Ok(m) => {
                any_ok = true;
                out.found.extend(m);
            }
            Err(e) => {
                log::warn!("extended-metadata {kind:?} batch failed: {e}");
                out.failed.extend(batch);
                out.error.get_or_insert(e);
            }
        }
    }
    match out.error {
        Some(e) if !any_ok => Err(e),
        _ => Ok(out),
    }
}

fn build_request(kind: ExtensionKind, uris: &[String], ctx: &Ctx) -> BatchedEntityRequest {
    let mut req = BatchedEntityRequest::new();
    if !ctx.country.is_empty() {
        let mut header = BatchedEntityRequestHeader::new();
        header.country = ctx.country.clone();
        header.catalogue = ctx.catalogue.clone();
        req.header = MessageField::some(header);
    }
    req.entity_request = uris
        .iter()
        .map(|uri| {
            let mut q = ExtensionQuery::new();
            q.extension_kind = EnumOrUnknown::new(kind);
            let mut e = EntityRequest::new();
            e.entity_uri = uri.clone();
            e.query.push(q);
            e
        })
        .collect();
    req
}

fn parse_response(resp: BatchedExtensionResponse) -> HashMap<String, Vec<u8>> {
    let mut out = HashMap::new();
    for array in resp.extended_metadata {
        for d in array.extension_data {
            let status = d.header.as_ref().map(|h| h.status_code).unwrap_or(0);
            if status != 0 && !(200..300).contains(&status) {
                continue;
            }
            if let Some(any) = d.extension_data.into_option() {
                if !any.value.is_empty() && !d.entity_uri.is_empty() {
                    out.insert(d.entity_uri, any.value);
                }
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------------------------
// Caches with in-flight coalescing
// ---------------------------------------------------------------------------------------------

/// Result of one shared fetch: what resolved and which of its URIs failed.
struct Batch<T> {
    map: HashMap<String, Arc<T>>,
    failed: HashSet<String>,
    error: Option<AppError>,
}

type BatchResult<T> = Result<Arc<Batch<T>>, AppError>;
type BatchFuture<T> = Shared<BoxFuture<'static, BatchResult<T>>>;
type Fetcher<T> = fn(Session, Vec<String>) -> BoxFuture<'static, AppResult<Partial<T>>>;

struct Entry<T> {
    at: Instant,
    value: Arc<T>,
}

struct Inflight<T> {
    id: u64,
    started: Instant,
    fut: BatchFuture<T>,
}

pub(crate) struct Store<T: Send + Sync + 'static> {
    lru: Mutex<LruCache<String, Entry<T>>>,
    inflight: Mutex<HashMap<String, Inflight<T>>>,
    seq: AtomicU64,
    max_age: Duration,
}

impl<T: Send + Sync + 'static> Store<T> {
    fn new(capacity: usize, max_age: Duration) -> Self {
        Self {
            lru: Mutex::new(LruCache::new(NonZeroUsize::new(capacity).unwrap_or(NonZeroUsize::MIN))),
            inflight: Mutex::new(HashMap::new()),
            seq: AtomicU64::new(1),
            max_age,
        }
    }

    fn clear(&self) {
        self.lru.lock().clear();
    }

    /// Any cached value (regardless of age), without touching LRU order.
    pub(crate) fn peek(&self, uri: &str) -> Option<Arc<T>> {
        self.lru.lock().peek(uri).map(|e| e.value.clone())
    }

    fn fresh(&self, uri: &str) -> Option<Arc<T>> {
        let mut lru = self.lru.lock();
        let e = lru.get(uri)?;
        (e.at.elapsed() < self.max_age).then(|| e.value.clone())
    }

    /// Looks `uris` up (fresh cache entries, joined in-flight fetches, one new fetch for the
    /// rest). Fails only when nothing resolved and a fetch failed.
    async fn get_many(&'static self, session: &Session, uris: &[String], fetch: Fetcher<T>) -> AppResult<Fetched<T>> {
        let mut out = Fetched::default();
        let mut pending = Vec::new();
        for uri in uris {
            match self.fresh(uri) {
                Some(v) => {
                    out.map.insert(uri.clone(), v);
                }
                None => pending.push(uri.clone()),
            }
        }
        if pending.is_empty() {
            return Ok(out);
        }
        // Each awaited fetch with the pending URIs it answers for us.
        let mut waits: Vec<(BatchFuture<T>, Vec<String>)> = Vec::new();
        {
            let mut inflight = self.inflight.lock();
            let mut joined: HashMap<u64, usize> = HashMap::new();
            let mut missing = Vec::new();
            for uri in &pending {
                match inflight.get(uri) {
                    // Entries older than two timeouts belong to a stuck/abandoned fetch.
                    Some(f) if f.started.elapsed() < http::TIMEOUT * 2 => {
                        let i = *joined.entry(f.id).or_insert_with(|| {
                            waits.push((f.fut.clone(), Vec::new()));
                            waits.len() - 1
                        });
                        waits[i].1.push(uri.clone());
                    }
                    _ => missing.push(uri.clone()),
                }
            }
            if !missing.is_empty() {
                let id = self.seq.fetch_add(1, Ordering::Relaxed);
                let fut = self.spawn_fetch(session.clone(), missing.clone(), fetch, id);
                let started = Instant::now();
                for uri in &missing {
                    inflight.insert(uri.clone(), Inflight { id, started, fut: fut.clone() });
                }
                waits.push((fut, missing));
            }
        }
        let results = join_all(waits.iter().map(|(f, _)| f.clone())).await;
        for ((_, covered), r) in waits.into_iter().zip(results) {
            match r {
                Ok(batch) => {
                    for uri in covered {
                        if let Some(v) = batch.map.get(&uri) {
                            out.map.insert(uri, v.clone());
                        } else if batch.failed.contains(&uri) {
                            if out.error.is_none() {
                                out.error = batch.error.clone();
                            }
                            out.failed.insert(uri);
                        }
                    }
                }
                Err(e) => {
                    out.failed.extend(covered);
                    out.error.get_or_insert(e);
                }
            }
        }
        if !out.failed.is_empty() && out.error.is_none() {
            out.error = Some(AppError::unavailable("metadata fetch failed"));
        }
        if out.map.is_empty() {
            if let Some(e) = out.error.take() {
                return Err(e);
            }
        }
        Ok(out)
    }

    fn spawn_fetch(&'static self, session: Session, uris: Vec<String>, fetch: Fetcher<T>, id: u64) -> BatchFuture<T> {
        let handle = tokio::spawn(async move {
            let result = fetch(session, uris.clone()).await;
            let out: BatchResult<T> = match result {
                Ok(partial) => {
                    let now = Instant::now();
                    let mut lru = self.lru.lock();
                    let map = partial
                        .found
                        .into_iter()
                        .map(|(k, v)| {
                            let a = Arc::new(v);
                            lru.put(k.clone(), Entry { at: now, value: a.clone() });
                            (k, a)
                        })
                        .collect();
                    drop(lru);
                    Ok(Arc::new(Batch { map, failed: partial.failed.into_iter().collect(), error: partial.error }))
                }
                Err(e) => Err(e),
            };
            let mut inflight = self.inflight.lock();
            for uri in &uris {
                if inflight.get(uri).is_some_and(|f| f.id == id) {
                    inflight.remove(uri);
                }
            }
            out
        });
        async move {
            match handle.await {
                Ok(r) => r,
                Err(e) => Err(AppError::internal(format!("metadata task failed: {e}"))),
            }
        }
        .boxed()
        .shared()
    }
}

/// Forgets all cached entities: `playable` is computed with the session's country and explicit
/// filter, so a change of either (logout, "Hide explicit content") makes them stale.
pub(crate) fn clear_cache() {
    TRACKS.clear();
    EPISODES.clear();
    ALBUMS.clear();
    ARTISTS.clear();
    SHOWS.clear();
}

static TRACKS: LazyLock<Store<Track>> = LazyLock::new(|| Store::new(4096, MAX_AGE));
static EPISODES: LazyLock<Store<Episode>> = LazyLock::new(|| Store::new(1024, MAX_AGE));
static ALBUMS: LazyLock<Store<AlbumMeta>> = LazyLock::new(|| Store::new(512, MAX_AGE));
static ARTISTS: LazyLock<Store<ArtistMeta>> = LazyLock::new(|| Store::new(256, ARTIST_MAX_AGE));
static SHOWS: LazyLock<Store<ShowMeta>> = LazyLock::new(|| Store::new(256, ARTIST_MAX_AGE));
static SHOW_EPISODES: LazyLock<Store<Vec<String>>> = LazyLock::new(|| Store::new(64, SHOW_EPISODES_MAX_AGE));

/// Normalises `uris` to canonical URIs of `kind` (invalid ones dropped, order kept) and looks
/// them up.
async fn lookup<T: Send + Sync + 'static>(
    store: &'static LazyLock<Store<T>>,
    session: &Session,
    uris: &[String],
    kind: UriKind,
    fetch: Fetcher<T>,
) -> AppResult<(Vec<String>, Fetched<T>)> {
    let order: Vec<String> = uris.iter().filter_map(|u| parse_kind(u, kind)).map(|p| p.uri()).collect();
    let mut seen = HashSet::new();
    let uniq: Vec<String> = order.iter().filter(|u| seen.insert(u.as_str())).cloned().collect();
    if uniq.is_empty() {
        return Ok((order, Fetched::default()));
    }
    let store: &'static Store<T> = store;
    let fetched = store.get_many(session, &uniq, fetch).await?;
    Ok((order, fetched))
}

fn fetch_tracks(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<Track>>> {
    async move {
        let raw = fetch_extended(&session, ExtensionKind::TRACK_V4, &uris).await?;
        let ctx = Ctx::new(&session);
        Ok(raw.map_found(|found| tracks_from_raw(found, &uris, &ctx)))
    }
    .boxed()
}

/// Decodes TRACK_V4 payloads keyed by the response's `entity_uri`. Relinked tracks are also
/// keyed by their canonical gid URI, and by a requested URI that only appears among the
/// track's alternatives, so lookups by queue URI and by canonical URI both hit the cache.
/// Every alias carries its own key as `uri`.
pub(crate) fn tracks_from_raw(raw: HashMap<String, Vec<u8>>, requested: &[String], ctx: &Ctx) -> HashMap<String, Track> {
    let wanted: HashSet<&str> = requested.iter().map(String::as_str).collect();
    let mut out: HashMap<String, Track> = HashMap::new();
    let mut aliases: Vec<(String, Track)> = Vec::new();
    for (uri, bytes) in &raw {
        let Ok(msg) = http::proto::<pm::Track>(bytes) else { continue };
        let Some(track) = convert_track(&msg, uri, ctx) else { continue };
        let canonical = uri_from_gid(UriKind::Track, msg.gid()).filter(|c| c != uri);
        let requested_alternatives = msg
            .alternative
            .iter()
            .filter_map(|a| uri_from_gid(UriKind::Track, a.gid()))
            .filter(|a| a != uri && wanted.contains(a.as_str()));
        for alias in canonical.into_iter().chain(requested_alternatives) {
            if !raw.contains_key(&alias) {
                aliases.push((alias.clone(), Track { uri: alias, ..track.clone() }));
            }
        }
        out.insert(uri.clone(), track);
    }
    for (alias, track) in aliases {
        out.entry(alias).or_insert(track);
    }
    out
}

fn fetch_episodes(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<Episode>>> {
    async move {
        let raw = fetch_extended(&session, ExtensionKind::EPISODE_V4, &uris).await?;
        let ctx = Ctx::new(&session);
        Ok(raw.map_found(|found| {
            found.into_iter().filter_map(|(uri, b)| decode_episode(&b, &uri, &ctx).map(|e| (uri, e))).collect()
        }))
    }
    .boxed()
}

fn fetch_albums(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<AlbumMeta>>> {
    async move {
        let raw = fetch_extended(&session, ExtensionKind::ALBUM_V4, &uris).await?;
        Ok(raw.map_found(|found| found.into_iter().filter_map(|(uri, b)| decode_album(&b, &uri).map(|a| (uri, a))).collect()))
    }
    .boxed()
}

fn fetch_artists(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<ArtistMeta>>> {
    async move {
        let raw = fetch_extended(&session, ExtensionKind::ARTIST_V4, &uris).await?;
        Ok(raw.map_found(|found| found.into_iter().filter_map(|(uri, b)| decode_artist(&b, &uri).map(|a| (uri, a))).collect()))
    }
    .boxed()
}

fn fetch_shows(session: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<ShowMeta>>> {
    async move {
        let raw = fetch_extended(&session, ExtensionKind::SHOW_V4, &uris).await?;
        Ok(raw.map_found(|found| found.into_iter().filter_map(|(uri, b)| decode_show(&b, &uri).map(|s| (uri, s))).collect()))
    }
    .boxed()
}

// ---------------------------------------------------------------------------------------------
// Protobuf → model conversion (lenient)
// ---------------------------------------------------------------------------------------------

pub(crate) fn decode_episode(bytes: &[u8], uri: &str, ctx: &Ctx) -> Option<Episode> {
    let msg: pm::Episode = http::proto(bytes).ok()?;
    convert_episode(&msg, uri, ctx)
}

pub(crate) fn decode_album(bytes: &[u8], uri: &str) -> Option<AlbumMeta> {
    let msg: pm::Album = http::proto(bytes).ok()?;
    convert_album(&msg, uri)
}

pub(crate) fn decode_artist(bytes: &[u8], uri: &str) -> Option<ArtistMeta> {
    let msg: pm::Artist = http::proto(bytes).ok()?;
    convert_artist(&msg, uri)
}

pub(crate) fn decode_show(bytes: &[u8], uri: &str) -> Option<ShowMeta> {
    let msg: pm::Show = http::proto(bytes).ok()?;
    convert_show(&msg, uri)
}

fn image_dims(img: &pm::Image) -> (Option<u32>, Option<u32>) {
    let w = img.width.filter(|w| *w > 0).map(|w| w as u32);
    let h = img.height.filter(|h| *h > 0).map(|h| h as u32);
    if w.is_some() {
        return (w, h);
    }
    // Typical sizes of Spotify's size buckets when the server omits dimensions.
    let approx = match img.size.map(|s| s.value()) {
        Some(1) => Some(64),
        Some(0) | None => Some(300),
        Some(2) => Some(640),
        _ => None,
    };
    (approx, approx)
}

pub(crate) fn images(list: &[pm::Image]) -> Vec<Image> {
    normalize_images(
        list.iter()
            .filter_map(|i| {
                let (w, h) = image_dims(i);
                image_from_file_id(i.file_id(), w, h)
            })
            .collect(),
    )
}

fn album_images(msg: &pm::Album) -> Vec<Image> {
    let group = images(&msg.cover_group.image);
    if group.is_empty() {
        images(&msg.cover)
    } else {
        group
    }
}

fn artist_ref_basic(a: &pm::Artist) -> Option<ArtistRef> {
    let uri = uri_from_gid(UriKind::Artist, a.gid())?;
    Some(ArtistRef { uri, name: a.name().to_string(), images: images(&a.portrait_group.image) })
}

fn date_parts(d: &pm::Date) -> Option<(String, &'static str, (i32, i32, i32))> {
    let year = d.year.filter(|y| *y > 0)?;
    let (s, precision) = format_date(year, d.month, d.day);
    Some((s, precision, (year, d.month.unwrap_or(0), d.day.unwrap_or(0))))
}

fn album_type(msg: &pm::Album) -> Option<AlbumType> {
    match msg.type_.map(|t| t.value()) {
        Some(1) => Some(AlbumType::Album),
        Some(2) => Some(AlbumType::Single),
        Some(3) => Some(AlbumType::Compilation),
        Some(4) => Some(AlbumType::Ep),
        _ => match msg.type_str().to_ascii_lowercase().as_str() {
            "album" => Some(AlbumType::Album),
            "single" => Some(AlbumType::Single),
            "compilation" => Some(AlbumType::Compilation),
            "ep" => Some(AlbumType::Ep),
            _ => None,
        },
    }
}

/// Album reference from a (possibly partial) album message, e.g. the one embedded in a track.
pub(crate) fn album_ref(msg: &pm::Album) -> Option<AlbumRef> {
    let uri = uri_from_gid(UriKind::Album, msg.gid())?;
    let total: usize = msg.disc.iter().map(|d| d.track.len()).sum();
    Some(AlbumRef {
        uri,
        name: msg.name().to_string(),
        images: album_images(msg),
        artists: msg.artist.iter().filter_map(artist_ref_basic).collect(),
        release_date: msg.date.as_ref().and_then(date_parts).map(|(s, _, _)| s),
        album_type: album_type(msg),
        total_tracks: (total > 0).then_some(total as u32),
    })
}

fn country_listed(list: &str, country: &str) -> bool {
    // Concatenated two-letter codes ("DEGBUS…"); odd trailing bytes are ignored (no panics).
    !country.is_empty() && list.as_bytes().chunks_exact(2).any(|c| c == country.as_bytes())
}

/// librespot's availability rules (`metadata/src/audio/item.rs`), without the panics.
fn allowed(restrictions: &[pm::Restriction], ctx: &Ctx) -> bool {
    use pm::restriction::Country_restriction;
    for r in restrictions.iter().filter(|r| r.catalogue_str.contains(&ctx.catalogue)) {
        match &r.country_restriction {
            Some(Country_restriction::CountriesAllowed(list)) => return country_listed(list, &ctx.country),
            Some(Country_restriction::CountriesForbidden(list)) => return !country_listed(list, &ctx.country),
            _ => {}
        }
    }
    true
}

fn date_ms(d: &pm::Date) -> Option<i64> {
    let y = d.year? as i64;
    let m = d.month.unwrap_or(1).clamp(1, 12) as i64;
    let day = d.day.unwrap_or(1).clamp(1, 31) as i64;
    // Days from civil (Howard Hinnant's algorithm).
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = if y2 >= 0 { y2 } else { y2 - 399 } / 400;
    let yoe = y2 - era * 400;
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    let ms = ((days * 24 + d.hour.unwrap_or(0) as i64) * 60 + d.minute.unwrap_or(0) as i64) * 60_000;
    Some(ms)
}

fn available(availability: &[pm::Availability], ctx: &Ctx) -> bool {
    availability.is_empty()
        || availability.iter().any(|a| a.start.as_ref().and_then(date_ms).is_none_or(|start| start <= ctx.now_ms))
}

fn track_playable(msg: &pm::Track, ctx: &Ctx) -> bool {
    if ctx.filter_explicit && msg.explicit() {
        return false;
    }
    let live = msg.earliest_live_timestamp();
    if live > 0 && ctx.now_ms > 0 && ctx.now_ms < live {
        return false;
    }
    if available(&msg.availability, ctx) && allowed(&msg.restriction, ctx) {
        return true;
    }
    // Relinking: an alternative version may be playable here.
    msg.alternative.iter().any(|alt| available(&alt.availability, ctx) && allowed(&alt.restriction, ctx))
}

pub(crate) fn convert_track(msg: &pm::Track, uri: &str, ctx: &Ctx) -> Option<Track> {
    if msg.name().is_empty() && msg.gid().is_empty() {
        return None;
    }
    let mut artists: Vec<ArtistRef> = msg.artist.iter().filter_map(artist_ref_basic).collect();
    if artists.is_empty() {
        artists = msg
            .artist_with_role
            .iter()
            .filter_map(|a| {
                Some(ArtistRef {
                    uri: uri_from_gid(UriKind::Artist, a.artist_gid())?,
                    name: a.artist_name().to_string(),
                    images: Vec::new(),
                })
            })
            .collect();
    }
    Some(Track {
        uri: uri.to_string(),
        name: msg.name().to_string(),
        artists,
        album: msg.album.as_ref().and_then(album_ref),
        duration_ms: msg.duration().max(0) as u64,
        explicit: msg.explicit(),
        playable: track_playable(msg, ctx),
        track_number: msg.number.filter(|n| *n > 0).map(|n| n as u32),
        disc_number: msg.disc_number.filter(|n| *n > 0).map(|n| n as u32),
        popularity: msg.popularity.map(|p| p.clamp(0, 100) as u32),
        has_lyrics: msg.has_lyrics,
    })
}

pub(crate) fn show_ref(msg: &pm::Show) -> Option<ShowRef> {
    Some(ShowRef {
        uri: uri_from_gid(UriKind::Show, msg.gid())?,
        name: msg.name().to_string(),
        publisher: Some(msg.publisher().to_string()).filter(|p| !p.is_empty()),
        images: images(&msg.cover_image.image),
    })
}

pub(crate) fn convert_episode(msg: &pm::Episode, uri: &str, ctx: &Ctx) -> Option<Episode> {
    if msg.name().is_empty() && msg.gid().is_empty() {
        return None;
    }
    let images = images(&msg.cover_image.image);
    let show = msg.show.as_ref().and_then(show_ref).map(|mut s| {
        if s.images.is_empty() {
            s.images = images.clone();
        }
        s
    });
    let playable = !(ctx.filter_explicit && msg.explicit())
        && available(&msg.availability, ctx)
        && allowed(&msg.restriction, ctx);
    Some(Episode {
        uri: uri.to_string(),
        name: msg.name().to_string(),
        show,
        description: msg.description().trim().to_string(),
        duration_ms: msg.duration().max(0) as u64,
        release_date: msg.publish_time.as_ref().and_then(date_parts).map(|(s, _, _)| s),
        images,
        explicit: msg.explicit(),
        playable,
        resume_position_ms: None,
        fully_played: None,
    })
}

fn copyright_text(c: &pm::Copyright) -> Option<String> {
    let text = c.text().trim();
    if text.is_empty() {
        return None;
    }
    let lower = text.to_ascii_lowercase();
    if text.starts_with(['©', '℗']) || lower.starts_with("(c)") || lower.starts_with("(p)") {
        return Some(text.to_string());
    }
    let symbol = if c.type_.map(|t| t.value()) == Some(0) { '℗' } else { '©' };
    Some(format!("{symbol} {text}"))
}

pub(crate) fn convert_album(msg: &pm::Album, uri: &str) -> Option<AlbumMeta> {
    let mut album = album_ref(msg)?;
    album.uri = uri.to_string();
    let date = msg.date.as_ref().and_then(date_parts);
    let track_uris: Vec<String> =
        msg.disc.iter().flat_map(|d| d.track.iter()).filter_map(|t| uri_from_gid(UriKind::Track, t.gid())).collect();
    Some(AlbumMeta {
        label: Some(msg.label().trim().to_string()).filter(|l| !l.is_empty()),
        copyrights: msg.copyright.iter().filter_map(copyright_text).collect(),
        release_date_precision: date.as_ref().map(|(_, p, _)| p.to_string()),
        date_key: date.map(|(_, _, k)| k).unwrap_or_default(),
        track_uris,
        album,
    })
}

fn group_uris(groups: &[pm::AlbumGroup]) -> Vec<String> {
    let mut seen = HashSet::new();
    groups
        .iter()
        .filter_map(|g| g.album.first())
        .filter_map(|a| uri_from_gid(UriKind::Album, a.gid()))
        .filter(|u| seen.insert(u.clone()))
        .collect()
}

pub(crate) fn convert_artist(msg: &pm::Artist, uri: &str) -> Option<ArtistMeta> {
    if msg.name().is_empty() && msg.gid().is_empty() {
        return None;
    }
    let mut portraits = images(&msg.portrait_group.image);
    if portraits.is_empty() {
        portraits = images(&msg.portrait);
    }
    let bio = msg.biography.first();
    let mut header_images = bio
        .map(|b| {
            let mut all: Vec<Image> = b.portrait_group.iter().flat_map(|g| images(&g.image)).collect();
            all.extend(images(&b.portrait));
            normalize_images(all)
        })
        .unwrap_or_default();
    header_images.retain(|i| !portraits.iter().any(|p| p.url == i.url));
    Some(ArtistMeta {
        artist: ArtistRef { uri: uri.to_string(), name: msg.name().to_string(), images: portraits },
        header_images,
        biography: bio.map(|b| strip_html(b.text())).filter(|t| !t.is_empty()),
        top_tracks: msg
            .top_track
            .iter()
            .map(|t| {
                (
                    t.country().to_string(),
                    t.track.iter().filter_map(|tr| uri_from_gid(UriKind::Track, tr.gid())).collect(),
                )
            })
            .collect(),
        albums: group_uris(&msg.album_group),
        singles: group_uris(&msg.single_group),
        compilations: group_uris(&msg.compilation_group),
        appears_on: group_uris(&msg.appears_on_group),
        related: msg.related.iter().filter_map(artist_ref_basic).collect(),
    })
}

pub(crate) fn convert_show(msg: &pm::Show, uri: &str) -> Option<ShowMeta> {
    if msg.name().is_empty() && msg.gid().is_empty() {
        return None;
    }
    let mut show = show_ref(msg).unwrap_or_default();
    show.uri = uri.to_string();
    if show.name.is_empty() {
        show.name = msg.name().to_string();
    }
    Some(ShowMeta {
        show,
        description: strip_html(msg.description()),
        episode_uris: msg.episode.iter().filter_map(|e| uri_from_gid(UriKind::Episode, e.gid())).collect(),
    })
}

/// Top tracks for `country`, falling back to the global list and then the first list.
pub(crate) fn top_tracks_for<'a>(meta: &'a ArtistMeta, country: &str) -> &'a [String] {
    let pick = |c: &str| meta.top_tracks.iter().find(|(tc, t)| tc == c && !t.is_empty());
    pick(country)
        .or_else(|| pick(""))
        .or_else(|| meta.top_tracks.iter().find(|(_, t)| !t.is_empty()))
        .map(|(_, t)| t.as_slice())
        .unwrap_or(&[])
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use librespot_core::SpotifyId;
    use librespot_protocol::entity_extension_data::{EntityExtensionData, EntityExtensionDataHeader};
    use librespot_protocol::extended_metadata::EntityExtensionDataArray;
    use protobuf::well_known_types::any::Any;
    use protobuf::Message;

    pub(crate) fn gid(b62: &str) -> Vec<u8> {
        SpotifyId::from_base62(b62).unwrap().to_raw().to_vec()
    }

    fn img(hex_id: &str, size: i32, w: i32) -> pm::Image {
        let mut i = pm::Image::new();
        i.set_file_id(hex::decode(hex_id).unwrap());
        i.size = Some(EnumOrUnknown::from_i32(size));
        if w > 0 {
            i.set_width(w);
            i.set_height(w);
        }
        i
    }

    fn ctx() -> Ctx {
        Ctx { country: "DE".into(), catalogue: "premium".into(), filter_explicit: false, now_ms: 1_790_000_000_000 }
    }

    pub(crate) fn sample_track() -> pm::Track {
        let mut t = pm::Track::new();
        t.set_gid(gid("4uLU6hMCjMI75M1A2tKUQC"));
        t.set_name("Never Gonna Give You Up".into());
        t.set_duration(213_573);
        t.set_number(1);
        t.set_disc_number(1);
        t.set_popularity(77);
        t.set_explicit(false);
        t.set_has_lyrics(true);
        let mut artist = pm::Artist::new();
        artist.set_gid(gid("0gxyHStUsqpMadRV0Di1Qt"));
        artist.set_name("Rick Astley".into());
        t.artist.push(artist);
        let mut album = pm::Album::new();
        album.set_gid(gid("6XhjNHCyCDyyGJRM5mg40G"));
        album.set_name("Whenever You Need Somebody".into());
        let mut date = pm::Date::new();
        date.set_year(1987);
        date.set_month(11);
        date.set_day(12);
        album.date = MessageField::some(date);
        let mut group = pm::ImageGroup::new();
        group.image.push(img("ab67616d0000b27315ebbedaacef61af244262a8", 2, 640));
        group.image.push(img("ab67616d0000485115ebbedaacef61af244262a8", 1, 0));
        album.cover_group = MessageField::some(group);
        t.album = MessageField::some(album);
        let mut r = pm::Restriction::new();
        r.catalogue_str.push("premium".into());
        r.country_restriction = Some(pm::restriction::Country_restriction::CountriesAllowed("ATCHDEFR".into()));
        t.restriction.push(r);
        t
    }

    #[test]
    fn converts_track() {
        let t = convert_track(&sample_track(), "spotify:track:4uLU6hMCjMI75M1A2tKUQC", &ctx()).unwrap();
        assert_eq!(t.name, "Never Gonna Give You Up");
        assert_eq!(t.artists[0].uri, "spotify:artist:0gxyHStUsqpMadRV0Di1Qt");
        let album = t.album.as_ref().unwrap();
        assert_eq!(album.uri, "spotify:album:6XhjNHCyCDyyGJRM5mg40G");
        assert_eq!(album.release_date.as_deref(), Some("1987-11-12"));
        // Sorted by width ascending; missing width inferred from the SMALL size bucket.
        assert_eq!(album.images[0].url, "https://i.scdn.co/image/ab67616d0000485115ebbedaacef61af244262a8");
        assert_eq!(album.images[0].width, Some(64));
        assert_eq!(album.images[1].width, Some(640));
        assert_eq!(t.duration_ms, 213_573);
        assert!(t.playable);
        assert_eq!(t.track_number, Some(1));
        assert_eq!(t.popularity, Some(77));
        assert_eq!(t.has_lyrics, Some(true));
        let json = serde_json::to_value(&t).unwrap();
        assert_eq!(json["durationMs"], 213_573);
        assert_eq!(json["album"]["images"][0]["width"], 64);
    }

    #[test]
    fn availability_rules() {
        let mut c = ctx();
        c.country = "US".into();
        let t = convert_track(&sample_track(), "spotify:track:4uLU6hMCjMI75M1A2tKUQC", &c).unwrap();
        assert!(!t.playable, "US not whitelisted");
        // A relinked alternative without restrictions makes it playable.
        let mut msg = sample_track();
        let mut alt = pm::Track::new();
        alt.set_gid(gid("7GhIk7Il098yCjg4BQjzvb"));
        msg.alternative.push(alt);
        assert!(convert_track(&msg, "spotify:track:4uLU6hMCjMI75M1A2tKUQC", &c).unwrap().playable);
        // Forbidden list with an odd length never panics.
        let mut msg = sample_track();
        msg.restriction[0].country_restriction =
            Some(pm::restriction::Country_restriction::CountriesForbidden("USD".into()));
        assert!(!convert_track(&msg, "spotify:track:x", &c).unwrap().playable);
        // Explicit filter.
        let mut msg = sample_track();
        msg.set_explicit(true);
        let mut c2 = ctx();
        c2.filter_explicit = true;
        assert!(!convert_track(&msg, "spotify:track:x", &c2).unwrap().playable);
        // Embargo (availability start in the future).
        let mut msg = sample_track();
        let mut a = pm::Availability::new();
        let mut d = pm::Date::new();
        d.set_year(2100);
        a.start = MessageField::some(d);
        msg.availability.push(a);
        assert!(!convert_track(&msg, "spotify:track:x", &ctx()).unwrap().playable);
        assert_eq!(date_ms(&{
            let mut d = pm::Date::new();
            d.set_year(1970);
            d.set_month(1);
            d.set_day(2);
            d
        }), Some(86_400_000));
    }

    #[test]
    fn converts_album_with_tracks_and_copyrights() {
        let mut a = pm::Album::new();
        a.set_gid(gid("6XhjNHCyCDyyGJRM5mg40G"));
        a.set_name("Whenever You Need Somebody".into());
        a.type_ = Some(EnumOrUnknown::from_i32(99)); // unknown enum value
        a.set_type_str("single".into());
        a.set_label("RCA".into());
        let mut d = pm::Date::new();
        d.set_year(1987);
        a.date = MessageField::some(d);
        for (i, id) in ["4uLU6hMCjMI75M1A2tKUQC", "7GhIk7Il098yCjg4BQjzvb"].iter().enumerate() {
            let mut disc = pm::Disc::new();
            disc.set_number(i as i32 + 1);
            let mut t = pm::Track::new();
            t.set_gid(gid(id));
            disc.track.push(t);
            let mut bad = pm::Track::new();
            bad.set_gid(vec![1, 2, 3]); // invalid gid → skipped
            disc.track.push(bad);
            a.disc.push(disc);
        }
        let mut c = pm::Copyright::new();
        c.set_text("1987 RCA".into());
        c.type_ = Some(EnumOrUnknown::from_i32(0));
        a.copyright.push(c);
        let mut c = pm::Copyright::new();
        c.set_text("© 1987 BMG".into());
        a.copyright.push(c);
        let bytes = a.write_to_bytes().unwrap();
        let meta = decode_album(&bytes, "spotify:album:6XhjNHCyCDyyGJRM5mg40G").unwrap();
        assert_eq!(meta.album.album_type, Some(AlbumType::Single));
        assert_eq!(meta.album.release_date.as_deref(), Some("1987"));
        assert_eq!(meta.release_date_precision.as_deref(), Some("year"));
        assert_eq!(meta.label.as_deref(), Some("RCA"));
        assert_eq!(meta.copyrights, vec!["℗ 1987 RCA".to_string(), "© 1987 BMG".to_string()]);
        assert_eq!(meta.track_uris, vec!["spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:track:7GhIk7Il098yCjg4BQjzvb"]);
        assert_eq!(meta.album.total_tracks, Some(4));
    }

    #[test]
    fn converts_artist() {
        let mut a = pm::Artist::new();
        a.set_gid(gid("0gxyHStUsqpMadRV0Di1Qt"));
        a.set_name("Rick Astley".into());
        let mut pg = pm::ImageGroup::new();
        pg.image.push(img("ab6761610000e5eb2e5c4bdc5ab7c4b4c4b4c4b4", 0, 640));
        a.portrait_group = MessageField::some(pg);
        let mut bio = pm::Biography::new();
        bio.set_text("<a href=\"spotify:artist:x\">Rick</a> &amp; friends".into());
        a.biography.push(bio);
        for (country, id) in [("US", "4uLU6hMCjMI75M1A2tKUQC"), ("DE", "7GhIk7Il098yCjg4BQjzvb")] {
            let mut tt = pm::TopTracks::new();
            tt.set_country(country.into());
            let mut t = pm::Track::new();
            t.set_gid(gid(id));
            tt.track.push(t);
            a.top_track.push(tt);
        }
        let mut g = pm::AlbumGroup::new();
        let mut al = pm::Album::new();
        al.set_gid(gid("6XhjNHCyCDyyGJRM5mg40G"));
        g.album.push(al);
        a.album_group.push(g.clone());
        a.album_group.push(g); // duplicate group → deduped
        let mut rel = pm::Artist::new();
        rel.set_gid(gid("1dfeR4HaWDbWqFHLkxsg1d"));
        rel.set_name("Queen".into());
        a.related.push(rel);
        let mut period = pm::ActivityPeriod::new();
        period.set_end_year(1999); // neither decade nor start: librespot fails, we ignore
        a.activity_period.push(period);
        let meta = decode_artist(&a.write_to_bytes().unwrap(), "spotify:artist:0gxyHStUsqpMadRV0Di1Qt").unwrap();
        assert_eq!(meta.biography.as_deref(), Some("Rick & friends"));
        assert_eq!(meta.albums, vec!["spotify:album:6XhjNHCyCDyyGJRM5mg40G"]);
        assert_eq!(top_tracks_for(&meta, "DE"), ["spotify:track:7GhIk7Il098yCjg4BQjzvb"]);
        assert_eq!(top_tracks_for(&meta, "FR"), ["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]);
        assert_eq!(meta.related[0].name, "Queen");
        assert_eq!(meta.artist.images.len(), 1);
    }

    #[test]
    fn converts_episode_and_show() {
        let mut e = pm::Episode::new();
        e.set_gid(gid("512ojhOuo1ktJprKbVcKyQ"));
        e.set_name("Episode 1".into());
        e.set_duration(3_600_000);
        e.set_description("  About things  ".into());
        let mut d = pm::Date::new();
        d.set_year(2024);
        d.set_month(2);
        d.set_day(29);
        e.publish_time = MessageField::some(d);
        let mut cover = pm::ImageGroup::new();
        cover.image.push(img("ab6765630000ba8a0000000000000000000000aa", 0, 0));
        e.cover_image = MessageField::some(cover);
        let mut s = pm::Show::new();
        s.set_gid(gid("5CfCWKI5pZ28U0uOzXkDHe"));
        s.set_name("The Show".into());
        s.set_publisher("Publisher".into());
        e.show = MessageField::some(s.clone());
        let ep = decode_episode(&e.write_to_bytes().unwrap(), "spotify:episode:512ojhOuo1ktJprKbVcKyQ", &ctx()).unwrap();
        assert_eq!(ep.release_date.as_deref(), Some("2024-02-29"));
        assert_eq!(ep.description, "About things");
        let show = ep.show.as_ref().unwrap();
        assert_eq!(show.uri, "spotify:show:5CfCWKI5pZ28U0uOzXkDHe");
        assert_eq!(show.images, ep.images, "show inherits the episode cover");
        let mut ep_ref = pm::Episode::new();
        ep_ref.set_gid(gid("512ojhOuo1ktJprKbVcKyQ"));
        s.episode.push(ep_ref);
        s.set_description("<p>Desc</p>".into());
        let meta = decode_show(&s.write_to_bytes().unwrap(), "spotify:show:5CfCWKI5pZ28U0uOzXkDHe").unwrap();
        assert_eq!(meta.description, "Desc");
        assert_eq!(meta.episode_uris, vec!["spotify:episode:512ojhOuo1ktJprKbVcKyQ"]);
        assert_eq!(meta.show.publisher.as_deref(), Some("Publisher"));
    }

    #[test]
    fn parses_batched_response_skipping_errors() {
        let mut resp = BatchedExtensionResponse::new();
        let mut arr = EntityExtensionDataArray::new();
        for (uri, status, payload) in [
            ("spotify:track:4uLU6hMCjMI75M1A2tKUQC", 200, sample_track().write_to_bytes().unwrap()),
            ("spotify:track:7GhIk7Il098yCjg4BQjzvb", 404, vec![1, 2, 3]),
            ("spotify:track:2takcwOaAZWiXQijPHIx7B", 0, b"\xff\xff garbage".to_vec()),
        ] {
            let mut d = EntityExtensionData::new();
            d.entity_uri = uri.into();
            let mut h = EntityExtensionDataHeader::new();
            h.status_code = status;
            d.header = MessageField::some(h);
            let mut any = Any::new();
            any.value = payload;
            d.extension_data = MessageField::some(any);
            arr.extension_data.push(d);
        }
        resp.extended_metadata.push(arr);
        let bytes = resp.write_to_bytes().unwrap();
        let parsed = parse_response(BatchedExtensionResponse::parse_from_bytes(&bytes).unwrap());
        assert_eq!(parsed.len(), 2, "404 entity skipped");
        let c = ctx();
        let ok = tracks_from_raw(parsed.clone(), &[], &c);
        assert_eq!(ok.len(), 1, "garbage entity skipped, not fatal");
        let req = build_request(ExtensionKind::TRACK_V4, &["spotify:track:4uLU6hMCjMI75M1A2tKUQC".into()], &c);
        assert_eq!(req.header.country, "DE");
        assert_eq!(req.entity_request[0].query[0].extension_kind.value(), ExtensionKind::TRACK_V4 as i32);
    }

    #[test]
    fn relinked_tracks_are_cached_under_requested_and_canonical_uris() {
        let a = "spotify:track:7GhIk7Il098yCjg4BQjzvb".to_string();
        let b = "spotify:track:4uLU6hMCjMI75M1A2tKUQC".to_string(); // sample_track's gid
        // Server echoes the requested URI but returns the relinked (canonical) track.
        let mut raw = HashMap::new();
        raw.insert(a.clone(), sample_track().write_to_bytes().unwrap());
        let out = tracks_from_raw(raw, std::slice::from_ref(&a), &ctx());
        assert_eq!(out[&a].uri, a);
        assert_eq!(out[&b].uri, b);
        assert_eq!(out[&a].name, out[&b].name);
        // Server answers with the canonical URI only; the requested one is an alternative.
        let mut msg = sample_track();
        let mut alt = pm::Track::new();
        alt.set_gid(gid("7GhIk7Il098yCjg4BQjzvb"));
        msg.alternative.push(alt);
        let mut raw = HashMap::new();
        raw.insert(b.clone(), msg.write_to_bytes().unwrap());
        let out = tracks_from_raw(raw, std::slice::from_ref(&a), &ctx());
        assert_eq!(out[&a].uri, a);
        assert_eq!(out.len(), 2);
        // Unrequested alternatives are not aliased.
        let mut raw = HashMap::new();
        raw.insert(b.clone(), {
            let mut m = sample_track();
            let mut alt = pm::Track::new();
            alt.set_gid(gid("2takcwOaAZWiXQijPHIx7B"));
            m.alternative.push(alt);
            m.write_to_bytes().unwrap()
        });
        assert_eq!(tracks_from_raw(raw, std::slice::from_ref(&b), &ctx()).len(), 1);
    }

    #[tokio::test]
    async fn coalesces_inflight_fetches() {
        use std::sync::atomic::AtomicUsize;
        static CALLS: AtomicUsize = AtomicUsize::new(0);
        static STORE: LazyLock<Store<String>> = LazyLock::new(|| Store::new(16, MAX_AGE));
        fn fetch(_s: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<String>>> {
            async move {
                CALLS.fetch_add(1, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(50)).await;
                Ok(Partial { found: uris.into_iter().map(|u| (u.clone(), format!("v:{u}"))).collect(), ..Default::default() })
            }
            .boxed()
        }
        // A Session is needed only to be cloned into the fetcher; build an unconnected one.
        let session = Session::new(Default::default(), None);
        let store: &'static Store<String> = &STORE;
        let a = vec!["a".to_string(), "b".to_string()];
        let b = vec!["b".to_string()];
        let (r1, r2) = tokio::join!(store.get_many(&session, &a, fetch), store.get_many(&session, &b, fetch));
        assert_eq!(r1.unwrap().map.len(), 2);
        assert_eq!(r2.unwrap().map.get("b").map(|v| v.as_str()), Some("v:b"));
        assert_eq!(CALLS.load(Ordering::SeqCst), 1, "second lookup joined the in-flight fetch");
        // Now cached.
        let r3 = store.get_many(&session, &b, fetch).await.unwrap();
        assert_eq!(r3.map.len(), 1);
        assert_eq!(CALLS.load(Ordering::SeqCst), 1);
        assert!(store.inflight.lock().is_empty());
    }

    #[tokio::test]
    async fn reports_failed_uris_apart_from_missing_ones() {
        static STORE: LazyLock<Store<String>> = LazyLock::new(|| Store::new(16, MAX_AGE));
        // "ok*" resolve, "gone*" have no data, "fail*" sit in a batch that failed.
        fn fetch(_s: Session, uris: Vec<String>) -> BoxFuture<'static, AppResult<Partial<String>>> {
            async move {
                tokio::time::sleep(Duration::from_millis(20)).await;
                let mut out = Partial::default();
                for u in uris {
                    if u.starts_with("ok") {
                        out.found.insert(u.clone(), format!("v:{u}"));
                    } else if u.starts_with("fail") {
                        out.failed.push(u);
                        out.error.get_or_insert(AppError::new(ErrorCode::RateLimited, "429"));
                    }
                }
                if out.found.is_empty() {
                    if let Some(e) = out.error.take() {
                        return Err(e);
                    }
                }
                Ok(out)
            }
            .boxed()
        }
        let session = Session::new(Default::default(), None);
        let store: &'static Store<String> = &STORE;
        let first = vec!["ok1".to_string(), "fail1".to_string(), "gone1".to_string()];
        let joiner = vec!["fail1".to_string(), "ok2".to_string()];
        let (r1, r2) = tokio::join!(store.get_many(&session, &first, fetch), store.get_many(&session, &joiner, fetch));
        let r1 = r1.unwrap();
        assert_eq!(r1.map.len(), 1);
        assert!(r1.is_failed("fail1") && !r1.is_failed("gone1") && !r1.is_failed("ok1"));
        assert!(r1.any_failed(first.iter()));
        assert!(!r1.any_failed(["ok1".to_string(), "gone1".to_string()].iter()));
        assert_eq!(r1.error.as_ref().map(|e| e.code), Some(ErrorCode::RateLimited));
        // The second lookup joined the first fetch for "fail1" and sees its failure too.
        let r2 = r2.unwrap();
        assert!(r2.is_failed("fail1") && r2.map.contains_key("ok2"));
        // Nothing resolved and a fetch failed: the lookup fails as a whole.
        let err = store.get_many(&session, &["fail2".to_string()], fetch).await.unwrap_err();
        assert_eq!(err.code, ErrorCode::RateLimited);
        // Only missing (no failure): an empty, successful lookup.
        let none = store.get_many(&session, &["gone2".to_string()], fetch).await.unwrap();
        assert!(none.map.is_empty() && none.failed.is_empty() && none.error.is_none());
        // Failures are not cached: the next lookup retries.
        assert!(store.peek("fail1").is_none());
    }

    #[test]
    fn page_errors_are_retryable() {
        assert_eq!(page_error(AppError::not_found("batch 404")).code, ErrorCode::Unavailable);
        assert_eq!(page_error(AppError::internal("x")).code, ErrorCode::Unavailable);
        assert_eq!(page_error(AppError::new(ErrorCode::Network, "x")).code, ErrorCode::Network);
        assert_eq!(page_error(AppError::new(ErrorCode::RateLimited, "x")).code, ErrorCode::RateLimited);
    }

    #[test]
    fn show_episode_sources_never_turn_failures_into_empty_lists() {
        let ep = |id: &str| format!("spotify:episode:{id}");
        let list = vec![ep("512ojhOuo1ktJprKbVcKyQ")];
        let net = || AppError::new(ErrorCode::Network, "offline");
        let unavailable = || AppError::unavailable("bad");
        assert_eq!(show_episodes_outcome(Ok(list.clone()), Err(net())).unwrap(), list);
        assert_eq!(show_episodes_outcome(Err(net()), Ok(list.clone())).unwrap(), list);
        assert_eq!(
            show_episodes_outcome(Ok(vec![]), Ok(vec![ep("512ojhOuo1ktJprKbVcKyQ"), "spotify:track:x".into()])).unwrap(),
            list,
            "context items that are not episodes are dropped"
        );
        assert!(show_episodes_outcome(Ok(vec![]), Err(AppError::not_found("empty context"))).unwrap().is_empty());
        assert_eq!(show_episodes_outcome(Ok(vec![]), Err(net())).unwrap_err().code, ErrorCode::Network);
        assert_eq!(show_episodes_outcome(Err(unavailable()), Err(net())).unwrap_err().code, ErrorCode::Network);
        assert_eq!(
            show_episodes_outcome(Err(net()), Err(AppError::not_found("empty context"))).unwrap_err().code,
            ErrorCode::Network
        );
    }
}
