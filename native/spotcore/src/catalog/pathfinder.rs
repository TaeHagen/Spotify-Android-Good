//! Pathfinder GraphQL (`api-partner.spotify.com/pathfinder/v2/query`, persisted queries) with
//! runtime discovery of the operation hashes from the live web player.
//!
//! Discovery (verified against open.spotify.com on 2026-10-06):
//! * `https://open.spotify.com/?utm_source=pwa_install` (the PWA start URL) serves the desktop
//!   web player HTML even to librespot's non-browser User-Agent (plain `/` serves the mobile web
//!   player, whose bundles carry different operations). The HTML lists
//!   `open.spotifycdn.com/cdn/build/web-player/web-player.<hash>.js` and embeds
//!   `appServerConfig` (base64 JSON with `clientVersion`, sent as `Spotify-App-Version`).
//! * The main bundle defines most operations as `new X.l("<op>","query"|"mutation","<sha256>",…)`
//!   and contains webpack's chunk URL builder
//!   `u.u=e=>""+(({<id>:"<name>",…})[e]||e)+"."+({<id>:"<hash>",…})[e]+".js"` plus the public
//!   path `u.p="https://open.spotifycdn.com/cdn/build/web-player/"`. `searchDesktop` lives in
//!   the lazily loaded `xpui-routes-search` chunk.
//! * `https://open.spotify.com/service-worker.js` precaches every chunk URL; used as a fallback.
//! * CDN bodies may arrive gzip-compressed regardless of `Accept-Encoding` (inflated here).
//!
//! Hashes are persisted in `files_dir/pathfinder.json` `{fetchedAt, hashes, appVersion,
//! bundleUrl, nextAttemptAt}`, refreshed weekly in the background or immediately on
//! `PersistedQueryNotFound`. A refresh runs as a detached task (a cancelled query never aborts
//! it; a query waits for it at most 20 s before using its fallbacks), one at a time, at most one
//! attempt per hour across restarts (5 min after a network failure). Bundle inflating and
//! scanning run on the blocking pool. Shipped defaults cover first use.

use super::http::{self, HttpError, TIMEOUT};
use super::inflate;
use super::util::now_ms;
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::runtime;
use base64::Engine as _;
use bytes::Bytes;
use futures_util::future::BoxFuture;
use futures_util::FutureExt;
use ::http::header::{HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use ::http::{Method, Request};
use librespot_core::Session;
use parking_lot::Mutex;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::LazyLock;
use std::time::{Duration, Instant};

pub(crate) const ENDPOINT: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const DESKTOP_HTML: &str = "https://open.spotify.com/?utm_source=pwa_install";
const WEB_ROOT: &str = "https://open.spotify.com/";
const SERVICE_WORKER: &str = "https://open.spotify.com/service-worker.js";
const DEFAULT_PUBLIC_PATH: &str = "https://open.spotifycdn.com/cdn/build/web-player/";
const REFRESH_AFTER_MS: i64 = 7 * 24 * 3600 * 1000;
/// At most one discovery attempt per hour (persisted, so it also holds across cold starts).
const REFRESH_BACKOFF_MS: i64 = 3600 * 1000;
/// Retry delay after an attempt that failed on the network (nothing was learned).
const TRANSIENT_BACKOFF_MS: i64 = 5 * 60 * 1000;
/// Upper bound for one discovery (it holds the refresh lock).
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(120);
/// How long a query waits for a refresh before using its fallbacks; the refresh carries on.
const REFRESH_WAIT: Duration = Duration::from_secs(20);
/// The `client-token` header is optional: do not let a stalled token request hold a query.
const CLIENT_TOKEN_TIMEOUT: Duration = Duration::from_secs(5);
const LOGIN5_REJECTED_FOR: Duration = Duration::from_secs(30 * 60);
const BUNDLE_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_CHUNKS: usize = 4;
pub(crate) const DEFAULT_APP_VERSION: &str = "1.3.5.183.gd022ee5eadfb";

/// Operations the app needs; discovery keeps fetching chunks until these are found.
const REQUIRED: [&str; 2] = ["searchDesktop", "home"];

/// Hashes from the web player build of 2026-10-06 (volatile; only a starting point).
pub(crate) const DEFAULT_HASHES: &[(&str, &str)] = &[
    ("searchDesktop", "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03"),
    ("home", "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1"),
    ("searchTracks", "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751"),
    ("searchArtists", "7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d"),
    ("searchAlbums", "202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9"),
    ("searchPlaylists", "d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1"),
    ("searchPodcasts", "0195d9f61b43606d490bca64c3456e3593528cea6cc05c7e822c7c42beed0f4e"),
    ("searchEpisodes", "dbc56e15e7c1254f11d499e8647343bbf28a5594e14a8f5c8b1e7eee67a55740"),
    ("queryArtistOverview", "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6"),
    ("getAlbum", "6a74b456cd1735c9193d9e8ec8cc5184cad7ce13572210315229db3975964361"),
    ("fetchPlaylist", "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a"),
    ("libraryV3", "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"),
    ("fetchLibraryTracks", "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"),
    ("areEntitiesInLibrary", "134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed"),
    ("addToLibrary", "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148"),
    ("removeFromLibrary", "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148"),
    ("getTrack", "a8ef9e9f02b836feb0da3003c31dbb30decc6f4b473ef89ca88c882386d668de"),
    ("browseAll", "dbd8b55e09a58afc52eab438bc228ba28fd72ac2f2148c6c26354980e4579001"),
];

// ---------------------------------------------------------------------------------------------
// Bundle parsing (pure)
// ---------------------------------------------------------------------------------------------

static OP_RE: LazyLock<Option<Regex>> =
    LazyLock::new(|| Regex::new(r#""([A-Za-z0-9_]+)","(?:query|mutation)","([0-9a-f]{64})""#).ok());
static BUNDLE_RE: LazyLock<Option<Regex>> =
    LazyLock::new(|| Regex::new(r#"https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9_.~-]+\.js"#).ok());
static CONFIG_RE: LazyLock<Option<Regex>> = LazyLock::new(|| {
    Regex::new(r#"<script[^>]*id="appServerConfig"[^>]*>([^<]+)</script>"#).ok()
});
static CHUNK_RE: LazyLock<Option<Regex>> = LazyLock::new(|| {
    Regex::new(concat!(
        r#"\(\{([0-9]+:"[^"]*"(?:,[0-9]+:"[^"]*")*)\}\)\[[A-Za-z_$][A-Za-z0-9_$]*\]\|\|[A-Za-z_$][A-Za-z0-9_$]*\)"#,
        r#"\+"\."\+\(\{([0-9]+:"[0-9a-f]+"(?:,[0-9]+:"[0-9a-f]+")*)\}\)\[[A-Za-z_$][A-Za-z0-9_$]*\]\+"\.js""#,
    ))
    .ok()
});
static ENTRY_RE: LazyLock<Option<Regex>> = LazyLock::new(|| Regex::new(r#"([0-9]+):"([^"]*)""#).ok());
static PUBLIC_PATH_RE: LazyLock<Option<Regex>> =
    LazyLock::new(|| Regex::new(r#"[A-Za-z_$][A-Za-z0-9_$]*\.p="(https://[^"]+/)""#).ok());

/// `(operationName, sha256)` pairs defined in a bundle (first definition of a name wins).
pub(crate) fn extract_operations(js: &str) -> Vec<(String, String)> {
    let Some(re) = OP_RE.as_ref() else { return Vec::new() };
    let mut seen = std::collections::HashSet::new();
    re.captures_iter(js)
        .filter_map(|c| Some((c.get(1)?.as_str().to_string(), c.get(2)?.as_str().to_string())))
        .filter(|(name, _)| seen.insert(name.clone()))
        .collect()
}

/// Web player script URLs referenced by an HTML page or the service worker (order kept).
pub(crate) fn extract_bundle_urls(text: &str) -> Vec<String> {
    let Some(re) = BUNDLE_RE.as_ref() else { return Vec::new() };
    let mut seen = std::collections::HashSet::new();
    re.find_iter(text).map(|m| m.as_str().to_string()).filter(|u| seen.insert(u.clone())).collect()
}

fn file_name(url: &str) -> &str {
    url.rsplit('/').next().unwrap_or(url)
}

/// The main bundle (`web-player.<hash>.js`) among `urls`.
pub(crate) fn main_bundle(urls: &[String]) -> Option<&String> {
    urls.iter().find(|u| file_name(u).starts_with("web-player."))
}

/// `clientVersion` from the embedded `appServerConfig` (base64 JSON, or plain JSON).
pub(crate) fn extract_app_version(html: &str) -> Option<String> {
    let raw = CONFIG_RE.as_ref()?.captures(html)?.get(1)?.as_str().trim().to_string();
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(raw.as_bytes())
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
        .unwrap_or(raw);
    let v: Value = serde_json::from_str(&decoded).ok()?;
    v.get("clientVersion").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string)
}

/// `(chunk name, URL)` for every lazily loaded chunk of the webpack runtime in `js`.
pub(crate) fn extract_chunks(js: &str) -> Vec<(String, String)> {
    let (Some(chunk_re), Some(entry_re)) = (CHUNK_RE.as_ref(), ENTRY_RE.as_ref()) else { return Vec::new() };
    let public_path = PUBLIC_PATH_RE
        .as_ref()
        .and_then(|re| re.captures(js))
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_string())
        .unwrap_or_else(|| DEFAULT_PUBLIC_PATH.to_string());
    let mut out = Vec::new();
    for caps in chunk_re.captures_iter(js) {
        let (Some(names), Some(hashes)) = (caps.get(1), caps.get(2)) else { continue };
        let names: HashMap<&str, &str> = entry_re
            .captures_iter(names.as_str())
            .filter_map(|c| Some((c.get(1)?.as_str(), c.get(2)?.as_str())))
            .collect();
        for c in entry_re.captures_iter(hashes.as_str()) {
            let (Some(id), Some(hash)) = (c.get(1), c.get(2)) else { continue };
            let name = names.get(id.as_str()).copied().unwrap_or(id.as_str());
            out.push((name.to_string(), format!("{public_path}{name}.{}.js", hash.as_str())));
        }
    }
    out
}

/// Chunks worth fetching to find `op` (e.g. `searchDesktop` → names containing "search").
fn chunk_candidates(chunks: &[(String, String)], missing: &[&str]) -> Vec<String> {
    let mut scored: Vec<(u8, &String)> = chunks
        .iter()
        .filter_map(|(name, url)| {
            let n = name.to_ascii_lowercase();
            let score = missing.iter().filter_map(|op| {
                let hint = if op.starts_with("search") { "search" } else { op.trim_start_matches("query") };
                let hint = hint.to_ascii_lowercase();
                if n == format!("xpui-routes-{hint}") {
                    Some(0)
                } else if n.contains(&hint) && !n.contains("recent") {
                    Some(1)
                } else if n.contains(&hint) {
                    Some(2)
                } else {
                    None
                }
            });
            score.min().map(|s| (s, url))
        })
        .collect();
    scored.sort_by_key(|(s, _)| *s);
    let mut seen = std::collections::HashSet::new();
    scored.into_iter().map(|(_, u)| u.clone()).filter(|u| seen.insert(u.clone())).take(MAX_CHUNKS).collect()
}

// ---------------------------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------------------------

/// GET returning the (inflated) body as text.
pub(crate) type Fetch = Box<dyn Fn(String) -> BoxFuture<'static, AppResult<String>> + Send + Sync>;

#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct Discovered {
    pub hashes: HashMap<String, String>,
    pub app_version: Option<String>,
    pub bundle_url: Option<String>,
    /// True when the main bundle is the one already known (`previous_bundle`); nothing fetched.
    pub unchanged: bool,
}

pub(crate) async fn discover(fetch: &Fetch, previous_bundle: Option<&str>) -> AppResult<Discovered> {
    let mut html = fetch(DESKTOP_HTML.to_string()).await.unwrap_or_default();
    let mut urls = extract_bundle_urls(&html);
    if main_bundle(&urls).is_none() {
        html = fetch(WEB_ROOT.to_string()).await.unwrap_or_default();
        urls = extract_bundle_urls(&html);
    }
    let app_version = extract_app_version(&html);
    let mut sw_urls: Option<Vec<String>> = None;
    if main_bundle(&urls).is_none() {
        let sw = fetch(SERVICE_WORKER.to_string()).await?;
        sw_urls = Some(extract_bundle_urls(&sw));
        urls = sw_urls.clone().unwrap_or_default();
    }
    let main = main_bundle(&urls)
        .cloned()
        .ok_or_else(|| AppError::new(ErrorCode::Unavailable, "web player bundle not found"))?;
    if previous_bundle == Some(main.as_str()) {
        return Ok(Discovered { app_version, bundle_url: Some(main), unchanged: true, ..Default::default() });
    }
    let js = fetch(main.clone()).await?;
    // Regex scans over the multi-MB bundle run on the blocking pool, not on a runtime worker.
    let (operations, chunks) = off_runtime(move || (extract_operations(&js), extract_chunks(&js))).await?;
    let mut hashes: HashMap<String, String> = operations.into_iter().collect();
    let missing = |h: &HashMap<String, String>| REQUIRED.iter().copied().filter(|op| !h.contains_key(*op)).collect::<Vec<_>>();
    let mut tried = std::collections::HashSet::new();
    for round in 0..2 {
        let still = missing(&hashes);
        if still.is_empty() {
            break;
        }
        let candidates = if round == 0 {
            chunk_candidates(&chunks, &still)
        } else {
            // Fallback: chunk URLs precached by the service worker.
            if sw_urls.is_none() {
                sw_urls = Some(fetch(SERVICE_WORKER.to_string()).await.map(|s| extract_bundle_urls(&s)).unwrap_or_default());
            }
            let named: Vec<(String, String)> = sw_urls
                .iter()
                .flatten()
                .map(|u| (file_name(u).split('.').next().unwrap_or_default().to_string(), u.clone()))
                .collect();
            chunk_candidates(&named, &still)
        };
        for url in candidates {
            if !tried.insert(url.clone()) {
                continue;
            }
            match fetch(url.clone()).await {
                Ok(chunk) => {
                    for (name, hash) in off_runtime(move || extract_operations(&chunk)).await? {
                        hashes.entry(name).or_insert(hash);
                    }
                }
                Err(e) => log::warn!("pathfinder chunk {} failed: {e}", file_name(&url)),
            }
            if missing(&hashes).is_empty() {
                break;
            }
        }
    }
    if hashes.is_empty() {
        return Err(AppError::new(ErrorCode::Unavailable, "no pathfinder operations found"));
    }
    Ok(Discovered { hashes, app_version, bundle_url: Some(main), unchanged: false })
}

/// Runs CPU-bound work (inflating, regex scans of the bundles) on the blocking pool: the async
/// runtime has only two workers, which also forward RPC results and playback/Connect events.
async fn off_runtime<T: Send + 'static>(work: impl FnOnce() -> T + Send + 'static) -> AppResult<T> {
    tokio::task::spawn_blocking(work).await.map_err(|e| AppError::internal(format!("pathfinder parse task: {e}")))
}

fn web_fetcher(session: Option<Session>) -> Fetch {
    Box::new(move |url: String| {
        let session = session.clone();
        async move {
            let timeout = if url.ends_with(".js") { BUNDLE_TIMEOUT } else { TIMEOUT };
            let body = http::web_get(session.as_ref(), &url, timeout).await?;
            off_runtime(move || inflate::maybe_gunzip(body.to_vec()).map(|b| String::from_utf8_lossy(&b).into_owned()))
                .await?
                .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("{url}: {e}")))
        }
        .boxed()
    })
}

// ---------------------------------------------------------------------------------------------
// Persistent state
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub(crate) struct Persisted {
    pub fetched_at: i64,
    pub hashes: HashMap<String, String>,
    pub app_version: Option<String>,
    pub bundle_url: Option<String>,
    /// Earliest wall-clock time (ms) of the next discovery attempt (hourly budget, shorter after a
    /// network failure).
    pub next_attempt_at: i64,
}

/// Whether a discovery may start at `now`: due (weekly) or forced, and not backing off. A
/// backoff further away than the hourly budget means the clock moved backwards: ignored.
pub(crate) fn may_attempt(p: &Persisted, force: bool, now: i64) -> bool {
    let due = now - p.fetched_at >= REFRESH_AFTER_MS;
    let backing_off = now < p.next_attempt_at && p.next_attempt_at - now <= REFRESH_BACKOFF_MS;
    (force || due) && !backing_off
}

#[derive(Default)]
struct State {
    persisted: Persisted,
    loaded: bool,
    login5_rejected_until: Option<Instant>,
}

static STATE: LazyLock<Mutex<State>> = LazyLock::new(|| Mutex::new(State::default()));
static REFRESH: LazyLock<tokio::sync::Mutex<()>> = LazyLock::new(|| tokio::sync::Mutex::new(()));

fn store_path() -> Option<PathBuf> {
    runtime::is_initialized().then(|| runtime::config().files_dir.join("pathfinder.json"))
}

async fn ensure_loaded() {
    if STATE.lock().loaded {
        return;
    }
    let loaded = match store_path() {
        Some(p) => tokio::fs::read(&p).await.ok().and_then(|b| serde_json::from_slice::<Persisted>(&b).ok()),
        None => None,
    };
    let mut st = STATE.lock();
    if !st.loaded {
        if let Some(p) = loaded {
            st.persisted = p;
        }
        st.loaded = true;
    }
}

async fn persist(p: &Persisted) {
    let Some(path) = store_path() else { return };
    let Ok(json) = serde_json::to_vec_pretty(p) else { return };
    let tmp = path.with_extension("json.tmp");
    if tokio::fs::write(&tmp, json).await.is_ok() {
        if let Err(e) = tokio::fs::rename(&tmp, &path).await {
            log::warn!("pathfinder.json: {e}");
        }
    }
}

pub(crate) fn hash_for(op: &str) -> Option<String> {
    let st = STATE.lock();
    st.persisted
        .hashes
        .get(op)
        .cloned()
        .or_else(|| DEFAULT_HASHES.iter().find(|(n, _)| *n == op).map(|(_, h)| h.to_string()))
}

fn app_version() -> String {
    STATE.lock().persisted.app_version.clone().unwrap_or_else(|| DEFAULT_APP_VERSION.to_string())
}

/// Merges a discovery result into the persisted state (discovered hashes win).
pub(crate) fn merge(old: &Persisted, found: &Discovered, now: i64) -> Persisted {
    let mut next = old.clone();
    next.fetched_at = now;
    if let Some(v) = &found.app_version {
        next.app_version = Some(v.clone());
    }
    if let Some(b) = &found.bundle_url {
        next.bundle_url = Some(b.clone());
    }
    for (k, v) in &found.hashes {
        next.hashes.insert(k.clone(), v.clone());
    }
    next
}

/// Refreshes the hashes and waits for the outcome (at most `wait`). `force` ignores the weekly
/// schedule (stale-hash errors); attempts are limited to one per hour and one at a time.
/// Returns true if hashes changed.
///
/// The refresh runs as a detached task: a cancelled caller (a search superseded by the next
/// keystroke aborts its RPC task) neither aborts a discovery midway nor wastes the hourly
/// budget, and callers that stop waiting find its result in the hashes later.
async fn refresh(fetch: Fetch, force: bool, wait: Duration) -> AppResult<bool> {
    let task = tokio::spawn(refresh_now(fetch, force));
    match tokio::time::timeout(wait, task).await {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => Err(AppError::internal(format!("pathfinder refresh task: {e}"))),
        Err(_) => Err(AppError::unavailable("pathfinder refresh still running")),
    }
}

async fn refresh_now(fetch: Fetch, force: bool) -> AppResult<bool> {
    ensure_loaded().await;
    let _guard = REFRESH.lock().await;
    let started = now_ms();
    let previous = {
        let mut st = STATE.lock();
        if !may_attempt(&st.persisted, force, started) {
            return Ok(false);
        }
        st.persisted.next_attempt_at = started + REFRESH_BACKOFF_MS;
        st.persisted.clone()
    };
    // A forced refresh must re-read the bundle even if its URL did not change.
    let known_bundle = if force { None } else { previous.bundle_url.clone() };
    let found = match tokio::time::timeout(DISCOVERY_TIMEOUT, discover(&fetch, known_bundle.as_deref())).await {
        Ok(r) => r,
        Err(_) => Err(AppError::new(ErrorCode::Network, "pathfinder discovery timed out")),
    };
    let found = match found {
        Ok(f) => f,
        Err(e) => {
            let attempted = {
                let mut st = STATE.lock();
                if matches!(e.code, ErrorCode::Network | ErrorCode::RateLimited | ErrorCode::Cancelled) {
                    // Offline or throttled: nothing was learned, try again soon.
                    st.persisted.next_attempt_at = now_ms() + TRANSIENT_BACKOFF_MS;
                }
                st.persisted.clone()
            };
            persist(&attempted).await;
            return Err(e);
        }
    };
    let next = merge(&previous, &found, now_ms());
    let changed = next.hashes != previous.hashes;
    STATE.lock().persisted = next.clone();
    persist(&next).await;
    log::info!("pathfinder hashes refreshed ({} operations, changed: {changed})", next.hashes.len());
    Ok(changed)
}

fn maybe_refresh_in_background(session: &Session) {
    if may_attempt(&STATE.lock().persisted, false, now_ms()) {
        let fetch = web_fetcher(Some(session.clone()));
        tokio::spawn(async move {
            if let Err(e) = refresh_now(fetch, false).await {
                log::warn!("pathfinder background refresh failed: {e}");
            }
        });
    }
}

// ---------------------------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------------------------

#[derive(Debug)]
pub(crate) enum PfError {
    /// The persisted query hash is unknown to the server.
    StaleHash,
    /// Every available token was refused.
    Auth,
    Http(HttpError),
    GraphQl(String),
}

impl From<PfError> for AppError {
    fn from(e: PfError) -> Self {
        match e {
            PfError::StaleHash => AppError::unavailable("pathfinder operation hash rejected"),
            PfError::Auth => AppError::unavailable("pathfinder rejected the access token"),
            PfError::Http(h) => h.error,
            PfError::GraphQl(m) => AppError::unavailable(format!("pathfinder: {m}")),
        }
    }
}

/// Interprets a pathfinder response body: `data` (partial errors tolerated), a stale-hash
/// error, or a GraphQL error.
pub(crate) fn interpret(body: &[u8]) -> Result<Value, PfError> {
    let v: Value = serde_json::from_slice(body).map_err(|e| PfError::GraphQl(format!("bad response: {e}")))?;
    let errors: Vec<String> = v
        .get("errors")
        .and_then(Value::as_array)
        .map(|errs| {
            errs.iter()
                .map(|e| {
                    let msg = e.get("message").and_then(Value::as_str).unwrap_or_default();
                    let code = e.pointer("/extensions/code").and_then(Value::as_str).unwrap_or_default();
                    format!("{msg} {code}")
                })
                .collect()
        })
        .unwrap_or_default();
    let stale = errors.iter().any(|e| {
        let l = e.to_ascii_lowercase();
        l.contains("persistedquerynotfound") || l.contains("persisted_query_not_found")
    });
    match v.get("data") {
        Some(d) if !d.is_null() && !stale => Ok(d.clone()),
        _ if stale => Err(PfError::StaleHash),
        _ => Err(PfError::GraphQl(errors.first().cloned().unwrap_or_else(|| "no data".into()))),
    }
}

pub(crate) fn request_body(op: &str, hash: &str, variables: &Value) -> Value {
    json!({
        "variables": variables,
        "operationName": op,
        "extensions": { "persistedQuery": { "version": 1, "sha256Hash": hash } },
    })
}

enum TokenKind {
    Login5,
    OAuth,
}

async fn tokens(session: &Session) -> Vec<(TokenKind, String)> {
    let mut out = Vec::new();
    let login5_ok = STATE.lock().login5_rejected_until.is_none_or(|t| Instant::now() >= t);
    if login5_ok {
        match http::timed(session.login5().auth_token()).await {
            Ok(t) => out.push((TokenKind::Login5, t.access_token)),
            Err(e) => log::warn!("login5 token unavailable: {}", e.error),
        }
    }
    if let Some((token, expires_at)) = engine::oauth_token() {
        if expires_at > now_ms() + 30_000 {
            out.push((TokenKind::OAuth, token));
        }
    }
    out
}

/// The client token for the optional `client-token` header, bounded by [`CLIENT_TOKEN_TIMEOUT`]
/// (fetching an expired one goes to clienttoken.spotify.com without any timeout of its own).
async fn client_token(session: &Session) -> Option<String> {
    match tokio::time::timeout(CLIENT_TOKEN_TIMEOUT, session.spclient().client_token()).await {
        Ok(Ok(t)) => Some(t),
        Ok(Err(e)) => {
            log::debug!("client token unavailable: {e}");
            None
        }
        Err(_) => {
            log::info!("client token timed out; querying pathfinder without it");
            None
        }
    }
}

async fn post(session: &Session, op: &str, hash: &str, variables: &Value, client_token: Option<&str>) -> Result<Value, PfError> {
    let body = request_body(op, hash, variables).to_string();
    let version = app_version();
    let candidates = tokens(session).await;
    if candidates.is_empty() {
        return Err(PfError::Auth);
    }
    let mut last = PfError::Auth;
    for (kind, token) in candidates {
        let mut builder = Request::builder()
            .method(Method::POST)
            .uri(ENDPOINT)
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .header(CONTENT_TYPE, "application/json;charset=UTF-8")
            .header(ACCEPT, "application/json")
            .header("app-platform", "WebPlayer")
            .header("spotify-app-version", version.as_str());
        if let Some(ct) = client_token.and_then(|t| HeaderValue::from_str(t).ok()) {
            builder = builder.header("client-token", ct);
        }
        let req = builder.body(Bytes::from(body.clone())).map_err(|e| {
            PfError::Http(HttpError { status: None, error: AppError::internal(format!("request: {e}")) })
        })?;
        match http::send(Some(session), req, TIMEOUT).await {
            Ok(bytes) => {
                let bytes = inflate::maybe_gunzip(bytes.to_vec()).unwrap_or_default();
                return interpret(&bytes);
            }
            Err(e) if e.is_auth() => {
                if matches!(kind, TokenKind::Login5) {
                    log::info!("pathfinder refused the login5 token ({:?})", e.status);
                    STATE.lock().login5_rejected_until = Some(Instant::now() + LOGIN5_REJECTED_FOR);
                }
                last = PfError::Auth;
            }
            // Unknown hashes are answered with 400/404 by some deployments.
            Err(e) if matches!(e.status, Some(400) | Some(404)) => return Err(PfError::StaleHash),
            Err(e) => return Err(PfError::Http(e)),
        }
    }
    Err(last)
}

/// Runs a persisted query and returns its `data` object. Retries once with freshly discovered
/// hashes when the server reports an unknown hash (waiting at most [`REFRESH_WAIT`] for them).
pub(crate) async fn query(session: &Session, op: &str, variables: Value) -> Result<Value, PfError> {
    ensure_loaded().await;
    maybe_refresh_in_background(session);
    let hash = match hash_for(op) {
        Some(h) => h,
        None => {
            let _ = refresh(web_fetcher(Some(session.clone())), true, REFRESH_WAIT).await;
            hash_for(op).ok_or_else(|| PfError::GraphQl(format!("unknown operation {op}")))?
        }
    };
    // Fetched once per query, so the stale-hash retry does not wait for it again.
    let client_token = client_token(session).await;
    let client_token = client_token.as_deref();
    match post(session, op, &hash, &variables, client_token).await {
        Err(PfError::StaleHash) => {
            log::info!("pathfinder: hash for {op} rejected, refreshing");
            if let Err(e) = refresh(web_fetcher(Some(session.clone())), true, REFRESH_WAIT).await {
                log::warn!("pathfinder refresh: {e}");
            }
            // A refresh by another caller may have brought the new hash as well.
            match hash_for(op) {
                Some(h) if h != hash => post(session, op, &h, &variables, client_token).await,
                _ => Err(PfError::StaleHash),
            }
        }
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const INDEX: &str = include_str!("testdata/webplayer_index_snippet.html");
    const MAIN: &str = include_str!("testdata/webplayer_main_snippet.js");
    const SEARCH: &str = include_str!("testdata/webplayer_search_chunk_snippet.js");
    const SW: &str = include_str!("testdata/webplayer_sw_snippet.js");

    #[test]
    fn extracts_operations_from_real_bundle_snippets() {
        let ops: HashMap<String, String> = extract_operations(MAIN).into_iter().collect();
        assert_eq!(ops["home"], "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1");
        assert_eq!(ops["libraryV3"], "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455");
        assert_eq!(ops["addToLibrary"], "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148");
        assert!(!ops.contains_key("searchDesktop"), "searchDesktop lives in a lazy chunk");
        let chunk: HashMap<String, String> = extract_operations(SEARCH).into_iter().collect();
        assert_eq!(chunk["searchDesktop"], "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03");
        assert_eq!(chunk["searchAlbums"], "202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9");
    }

    #[test]
    fn extracts_html_metadata() {
        let urls = extract_bundle_urls(INDEX);
        assert_eq!(
            main_bundle(&urls).map(String::as_str),
            Some("https://open.spotifycdn.com/cdn/build/web-player/web-player.37937ab2.js")
        );
        assert!(urls.iter().any(|u| u.contains("vendor~web-player")));
        assert_eq!(extract_app_version(INDEX).as_deref(), Some("1.3.5.183.gd022ee5eadfb"));
        let plain = r#"<script id="appServerConfig" type="text/plain">{"clientVersion":"1.2.3.4.gabc"}</script>"#;
        assert_eq!(extract_app_version(plain).as_deref(), Some("1.2.3.4.gabc"));
        assert_eq!(extract_app_version("<html></html>"), None);
    }

    #[test]
    fn extracts_webpack_chunk_urls() {
        let chunks = extract_chunks(MAIN);
        assert!(chunks.len() > 100, "{}", chunks.len());
        let search: Vec<_> = chunks.iter().filter(|(n, _)| n == "xpui-routes-search").collect();
        assert_eq!(search.len(), 1);
        assert_eq!(search[0].1, "https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.21c95ff5.js");
        // Unnamed chunks use their id as the name.
        assert!(chunks.iter().any(|(n, u)| n == "1015" && u.ends_with("/1015.9eee9cf3.js")));
        let candidates = chunk_candidates(&chunks, &["searchDesktop"]);
        assert_eq!(candidates[0], search[0].1);
        assert!(candidates.len() <= MAX_CHUNKS);
        let sw_urls = extract_bundle_urls(SW);
        assert!(sw_urls.iter().any(|u| u.ends_with("xpui-routes-search.21c95ff5.js")));
    }

    fn fake_fetcher(log: std::sync::Arc<Mutex<Vec<String>>>, gzip_search: bool) -> Fetch {
        Box::new(move |url: String| {
            log.lock().push(url.clone());
            let body: AppResult<String> = match url.as_str() {
                DESKTOP_HTML => Ok(INDEX.to_string()),
                "https://open.spotifycdn.com/cdn/build/web-player/web-player.37937ab2.js" => Ok(MAIN.to_string()),
                "https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.21c95ff5.js" => {
                    if gzip_search {
                        let gz = include_bytes!("testdata/gzip_dynamic.gz").to_vec();
                        Ok(String::from_utf8(inflate::maybe_gunzip(gz).unwrap()).unwrap())
                    } else {
                        Ok(SEARCH.to_string())
                    }
                }
                SERVICE_WORKER => Ok(SW.to_string()),
                _ => Err(AppError::not_found(url.clone())),
            };
            async move { body }.boxed()
        })
    }

    #[tokio::test]
    async fn discovers_hashes_end_to_end_with_fixtures() {
        let log = std::sync::Arc::new(Mutex::new(Vec::new()));
        let found = discover(&fake_fetcher(log.clone(), true), None).await.unwrap();
        assert_eq!(found.hashes["searchDesktop"], "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03");
        assert_eq!(found.hashes["home"], "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1");
        assert_eq!(found.app_version.as_deref(), Some("1.3.5.183.gd022ee5eadfb"));
        assert!(!found.unchanged);
        let fetched = log.lock().clone();
        assert_eq!(fetched.len(), 3, "html, main bundle, search chunk: {fetched:?}");

        // Same bundle as last time: nothing but the HTML is fetched.
        let log2 = std::sync::Arc::new(Mutex::new(Vec::new()));
        let again = discover(&fake_fetcher(log2.clone(), false), found.bundle_url.as_deref()).await.unwrap();
        assert!(again.unchanged);
        assert_eq!(log2.lock().len(), 1);

        let merged = merge(&Persisted::default(), &found, 42);
        assert_eq!(merged.fetched_at, 42);
        let json = serde_json::to_value(&merged).unwrap();
        assert!(json["fetchedAt"].is_number() && json["hashes"]["home"].is_string());
        let back: Persisted = serde_json::from_value(json).unwrap();
        assert_eq!(back, merged);
    }

    #[test]
    fn refresh_budget() {
        let now = 10 * REFRESH_AFTER_MS;
        let fresh = Persisted { fetched_at: now - 1000, ..Default::default() };
        assert!(!may_attempt(&fresh, false, now), "not due");
        assert!(may_attempt(&fresh, true, now), "forced");
        let stale = Persisted { fetched_at: now - REFRESH_AFTER_MS, ..Default::default() };
        assert!(may_attempt(&stale, false, now), "weekly refresh due");
        let backing_off = Persisted { next_attempt_at: now + 1000, ..fresh.clone() };
        assert!(!may_attempt(&backing_off, true, now));
        assert!(may_attempt(&backing_off, true, now + 1000));
        // A backoff beyond the hourly budget means the clock went backwards.
        let skewed = Persisted { next_attempt_at: now + 2 * REFRESH_BACKOFF_MS, ..fresh };
        assert!(may_attempt(&skewed, true, now));
    }

    fn delayed(inner: Fetch, delay: Duration) -> Fetch {
        Box::new(move |url: String| {
            let fut = inner(url);
            async move {
                tokio::time::sleep(delay).await;
                fut.await
            }
            .boxed()
        })
    }

    #[tokio::test]
    async fn refresh_outlives_a_cancelled_caller_and_backs_off() {
        {
            let mut st = STATE.lock();
            st.loaded = true;
            st.persisted = Persisted::default();
        }
        let log = std::sync::Arc::new(Mutex::new(Vec::new()));
        // The caller stops waiting long before discovery ends (as an aborted RPC would)…
        let r = refresh(delayed(fake_fetcher(log.clone(), false), Duration::from_millis(30)), true, Duration::from_millis(1)).await;
        assert!(r.is_err());
        // …yet the refresh completes and stores the hashes.
        tokio::time::timeout(Duration::from_secs(10), async {
            while !STATE.lock().persisted.hashes.contains_key("searchDesktop") {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("detached refresh finished");
        assert_eq!(log.lock().len(), 3);
        // That one attempt spent the hourly budget: another forced refresh fetches nothing.
        let log2 = std::sync::Arc::new(Mutex::new(Vec::new()));
        assert!(!refresh(fake_fetcher(log2.clone(), false), true, Duration::from_secs(10)).await.unwrap());
        assert!(log2.lock().is_empty());

        // Offline: the attempt fails on the network and only backs off for a few minutes.
        STATE.lock().persisted.next_attempt_at = 0;
        let offline: Fetch = Box::new(|url: String| async move { Err(AppError::new(ErrorCode::Network, url)) }.boxed());
        let e = refresh(offline, true, Duration::from_secs(10)).await.unwrap_err();
        assert_eq!(e.code, ErrorCode::Network);
        let wait = STATE.lock().persisted.next_attempt_at - now_ms();
        assert!(wait > 0 && wait <= TRANSIENT_BACKOFF_MS, "{wait}");
        assert!(STATE.lock().persisted.hashes.contains_key("searchDesktop"), "known hashes kept");
    }

    #[test]
    fn interprets_responses() {
        assert!(matches!(
            interpret(br#"{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"#),
            Err(PfError::StaleHash)
        ));
        assert!(interpret(br#"{"data":{"x":1},"errors":[{"message":"partial"}]}"#).is_ok());
        assert!(matches!(interpret(br#"{"data":null,"errors":[{"message":"boom"}]}"#), Err(PfError::GraphQl(m)) if m.contains("boom")));
        assert!(matches!(interpret(b"<html>"), Err(PfError::GraphQl(_))));
        let body = request_body("home", "abc", &json!({"timeZone":"UTC"}));
        assert_eq!(body["extensions"]["persistedQuery"]["sha256Hash"], "abc");
        assert_eq!(body["operationName"], "home");
        assert!(DEFAULT_HASHES.iter().all(|(_, h)| h.len() == 64));
    }
}
