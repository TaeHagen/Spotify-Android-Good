//! `catalog.search`: pathfinder `searchDesktop` → spclient `searchview/km/v4` → context-resolve
//! `spotify:search:<q>` (tracks only). A source that answers (even with no hits) wins; only
//! errors fall through to the next one, and a transport error (offline, rate limited) of
//! searchview ends the chain: context-resolve would hit the same spclient.

use super::context;
use super::http::{self, JSON};
use super::metadata;
use super::pathfinder;
use super::pfparse;
use super::util::{encode_component, encode_search_query, parse_kind, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{AlbumRef, ArtistRef, Episode, Image, MediaRef, MediaType, PlaylistOwner, PlaylistRef, SearchResults, ShowRef, Track};
use crate::rpc::{parse_args, to_value};
use librespot_core::Session;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

#[derive(Deserialize)]
struct Args {
    query: String,
    #[serde(default)]
    types: Vec<String>,
    #[serde(default)]
    offset: u32,
    #[serde(default = "default_limit")]
    limit: u32,
}

fn default_limit() -> u32 {
    20
}

/// Requested result types (all when empty or unrecognised).
#[derive(Debug, Clone, Copy)]
pub(crate) struct Types {
    pub tracks: bool,
    pub artists: bool,
    pub albums: bool,
    pub playlists: bool,
    pub shows: bool,
    pub episodes: bool,
}

impl Types {
    pub(crate) fn from_list(types: &[String]) -> Self {
        let set: HashSet<String> = types.iter().map(|t| t.trim().to_ascii_lowercase()).collect();
        let has = |names: &[&str]| names.iter().any(|n| set.contains(*n));
        let t = Types {
            tracks: has(&["track", "tracks"]),
            artists: has(&["artist", "artists"]),
            albums: has(&["album", "albums"]),
            playlists: has(&["playlist", "playlists"]),
            shows: has(&["show", "shows", "podcast", "podcasts"]),
            episodes: has(&["episode", "episodes"]),
        };
        if t.tracks || t.artists || t.albums || t.playlists || t.shows || t.episodes {
            t
        } else {
            Types { tracks: true, artists: true, albums: true, playlists: true, shows: true, episodes: true }
        }
    }

    fn all(&self) -> bool {
        self.tracks && self.artists && self.albums && self.playlists && self.shows && self.episodes
    }

    fn apply(&self, mut r: SearchResults) -> SearchResults {
        if !self.tracks {
            r.tracks.clear();
        }
        if !self.artists {
            r.artists.clear();
        }
        if !self.albums {
            r.albums.clear();
        }
        if !self.playlists {
            r.playlists.clear();
        }
        if !self.shows {
            r.shows.clear();
        }
        if !self.episodes {
            r.episodes.clear();
        }
        if !self.all() {
            r.top_result = None;
        }
        let wanted = [
            ("tracks", self.tracks),
            ("artists", self.artists),
            ("albums", self.albums),
            ("playlists", self.playlists),
            ("shows", self.shows),
            ("episodes", self.episodes),
        ];
        r.totals.retain(|k, _| wanted.iter().any(|(name, on)| *on && name == k));
        r
    }
}

/// "Hide explicit content" (docs §4.3) for search results, which do not come from the metadata
/// lookups that apply it elsewhere: explicit tracks and episodes stay listed but are not
/// playable, and a top result that is an explicit (or now unplayable) track/episode is dropped.
pub(crate) fn hide_explicit(r: &mut SearchResults, top_explicit: bool) {
    for t in r.tracks.iter_mut().filter(|t| t.explicit) {
        t.playable = false;
    }
    for e in r.episodes.iter_mut().filter(|e| e.explicit) {
        e.playable = false;
    }
    let unplayable = |uri: &str| {
        r.tracks.iter().any(|t| t.uri == uri && !t.playable) || r.episodes.iter().any(|e| e.uri == uri && !e.playable)
    };
    let drop_top = r.top_result.as_ref().is_some_and(|m| {
        matches!(m.kind, MediaType::Track | MediaType::Episode) && (top_explicit || unplayable(&m.uri))
    });
    if drop_top {
        r.top_result = None;
    }
}

/// Requested from the server per page: more than `limit`, so that entities the parsers drop
/// (unavailable, malformed) do not shorten the page; the extra ones come again on the next
/// page (offsets advance by what was returned) and are deduplicated by the client.
pub(crate) fn fetch_limit(limit: u32) -> u32 {
    (limit + limit.div_ceil(2)).min(MAX_LIMIT)
}

const MAX_LIMIT: u32 = 50;

/// `/<key>/<field>` as a count.
fn count(root: &Value, key: &str, field: &str) -> Option<u32> {
    root.pointer(&format!("/{key}/{field}")).and_then(Value::as_u64).map(|n| n.min(u32::MAX as u64) as u32)
}

fn items(root: &Value, key: &str) -> Vec<Value> {
    root.pointer(&format!("/{key}/items")).and_then(Value::as_array).cloned().unwrap_or_default()
}

/// `data.searchV2` of a `searchDesktop` response → at most `limit` results per type
/// (unparseable entities skipped) with the server's `totalCount`s; `filter_explicit` applies
/// [`hide_explicit`].
pub(crate) fn parse_pathfinder(data: &Value, limit: usize, filter_explicit: bool) -> SearchResults {
    let root = data.get("searchV2").unwrap_or(data);
    fn parsed<T>(root: &Value, key: &str, limit: usize, parse: fn(&Value) -> Option<T>) -> Vec<T> {
        items(root, key).iter().filter_map(parse).take(limit).collect()
    }
    let albums_key = if root.get("albumsV2").is_some() { "albumsV2" } else { "albums" };
    let tracks_key = if root.get("tracksV2").is_some() { "tracksV2" } else { "tracks" };
    let top_item = root
        .pointer("/topResultsV2/itemsV2")
        .or_else(|| root.pointer("/topResults/itemsV2"))
        .or_else(|| root.pointer("/topResults/items"))
        .and_then(Value::as_array)
        .and_then(|a| a.iter().find_map(|v| Some((v, pfparse::media(v)?))));
    let top_explicit = top_item.as_ref().is_some_and(|(v, _)| {
        pfparse::track(v).is_some_and(|t| t.explicit) || pfparse::episode(v).is_some_and(|e| e.explicit)
    });
    let totals = [
        ("tracks", tracks_key),
        ("artists", "artists"),
        ("albums", albums_key),
        ("playlists", "playlists"),
        ("shows", "podcasts"),
        ("episodes", "episodes"),
    ]
    .into_iter()
    .filter_map(|(name, key)| Some((name.to_string(), count(root, key, "totalCount")?)))
    .collect();
    let mut r = SearchResults {
        tracks: parsed(root, tracks_key, limit, pfparse::track),
        artists: parsed(root, "artists", limit, pfparse::artist),
        albums: parsed(root, albums_key, limit, pfparse::album),
        playlists: parsed(root, "playlists", limit, pfparse::playlist),
        shows: parsed(root, "podcasts", limit, pfparse::show),
        episodes: parsed(root, "episodes", limit, pfparse::episode),
        top_result: top_item.map(|(_, m)| m),
        totals,
    };
    if filter_explicit {
        hide_explicit(&mut r, top_explicit);
    }
    r
}

fn hit_image(h: &Value) -> Vec<Image> {
    h.get("image")
        .and_then(Value::as_str)
        .filter(|u| u.starts_with("https://"))
        .map(|u| vec![Image { url: u.to_string(), width: None, height: None }])
        .unwrap_or_default()
}

fn hit_artists(h: &Value) -> Vec<ArtistRef> {
    h.get("artists")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|x| {
                    Some(ArtistRef {
                        uri: x.get("uri").and_then(Value::as_str)?.to_string(),
                        name: x.get("name").and_then(Value::as_str).unwrap_or_default().to_string(),
                        images: Vec::new(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

fn hits<'a>(results: &'a Value, keys: &[&str]) -> Vec<&'a Value> {
    keys.iter()
        .find_map(|k| results.pointer(&format!("/{k}/hits")).and_then(Value::as_array))
        .map(|a| a.iter().collect())
        .unwrap_or_default()
}

fn hit_str(h: &Value, key: &str) -> Option<String> {
    h.get(key).and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string)
}

/// searchview (`results.<type>.hits[]`) response → results.
pub(crate) fn parse_searchview(body: &Value, limit: usize, filter_explicit: bool) -> SearchResults {
    let results = body.get("results").unwrap_or(body);
    let mut r = searchview_results(results);
    r.tracks.truncate(limit);
    r.artists.truncate(limit);
    r.albums.truncate(limit);
    r.playlists.truncate(limit);
    r.shows.truncate(limit);
    r.episodes.truncate(limit);
    let first_count = |keys: &[&str]| keys.iter().find_map(|k| count(results, k, "total"));
    r.totals = [
        ("tracks", &["tracks"][..]),
        ("artists", &["artists"]),
        ("albums", &["albums"]),
        ("playlists", &["playlists"]),
        ("shows", &["shows", "podcasts"]),
        ("episodes", &["audioepisodes", "episodes"]),
    ]
    .into_iter()
    .filter_map(|(name, keys)| Some((name.to_string(), first_count(keys)?)))
    .collect();
    if filter_explicit {
        let top_explicit = hits(results, &["topHit"]).first().is_some_and(|h| h.get("explicit").and_then(Value::as_bool) == Some(true));
        hide_explicit(&mut r, top_explicit);
    }
    r
}

/// searchview `results.<type>.hits[]` → results (unparseable hits skipped, not truncated).
fn searchview_results(results: &Value) -> SearchResults {
    let uri_ok = |h: &Value, kind: UriKind| hit_str(h, "uri").and_then(|u| parse_kind(&u, kind)).map(|p| p.uri());
    let tracks = hits(results, &["tracks"])
        .into_iter()
        .filter_map(|h| {
            Some(Track {
                uri: uri_ok(h, UriKind::Track)?,
                name: hit_str(h, "name")?,
                artists: hit_artists(h),
                album: h.get("album").and_then(|a| {
                    Some(AlbumRef {
                        uri: a.get("uri").and_then(Value::as_str)?.to_string(),
                        name: a.get("name").and_then(Value::as_str).unwrap_or_default().to_string(),
                        images: hit_image(h),
                        ..Default::default()
                    })
                }),
                duration_ms: h.get("duration").and_then(Value::as_u64).unwrap_or(0),
                explicit: h.get("explicit").and_then(Value::as_bool).unwrap_or(false),
                playable: h.get("playable").and_then(Value::as_bool).unwrap_or(true),
                popularity: h.get("popularity").and_then(Value::as_u64).map(|p| p.min(100) as u32),
                ..Default::default()
            })
        })
        .collect();
    let artists = hits(results, &["artists"])
        .into_iter()
        .filter_map(|h| Some(ArtistRef { uri: uri_ok(h, UriKind::Artist)?, name: hit_str(h, "name")?, images: hit_image(h) }))
        .collect();
    let albums = hits(results, &["albums"])
        .into_iter()
        .filter_map(|h| {
            Some(AlbumRef {
                uri: uri_ok(h, UriKind::Album)?,
                name: hit_str(h, "name")?,
                images: hit_image(h),
                artists: hit_artists(h),
                ..Default::default()
            })
        })
        .collect();
    let playlists = hits(results, &["playlists"])
        .into_iter()
        .filter_map(|h| {
            Some(PlaylistRef {
                uri: uri_ok(h, UriKind::Playlist)?,
                name: hit_str(h, "name")?,
                images: hit_image(h),
                owner: hit_str(h, "author").map(|a| PlaylistOwner { username: a, display_name: None }),
                ..Default::default()
            })
        })
        .collect();
    let shows = hits(results, &["shows", "podcasts"])
        .into_iter()
        .filter_map(|h| {
            Some(ShowRef {
                uri: uri_ok(h, UriKind::Show)?,
                name: hit_str(h, "name")?,
                publisher: hit_str(h, "publisher").or_else(|| hit_str(h, "showName")),
                images: hit_image(h),
            })
        })
        .collect();
    let episodes = hits(results, &["audioepisodes", "episodes"])
        .into_iter()
        .filter_map(|h| {
            Some(Episode {
                uri: uri_ok(h, UriKind::Episode)?,
                name: hit_str(h, "name")?,
                images: hit_image(h),
                duration_ms: h.get("duration").and_then(Value::as_u64).unwrap_or(0),
                explicit: h.get("explicit").and_then(Value::as_bool).unwrap_or(false),
                // Not `Default` (false): hits are playable unless they say otherwise, as tracks.
                playable: h.get("playable").and_then(Value::as_bool).unwrap_or(true),
                ..Default::default()
            })
        })
        .collect();
    let top_result = hits(results, &["topHit"]).first().and_then(|h| {
        let uri = hit_str(h, "uri")?;
        let kind = match super::util::parse_uri(&uri)?.kind {
            UriKind::Track => MediaType::Track,
            UriKind::Album => MediaType::Album,
            UriKind::Artist => MediaType::Artist,
            UriKind::Playlist => MediaType::Playlist,
            UriKind::Show => MediaType::Show,
            UriKind::Episode => MediaType::Episode,
        };
        Some(MediaRef { kind, uri, name: hit_str(h, "name")?, subtitle: None, images: hit_image(h) })
    });
    SearchResults { tracks, artists, albums, playlists, shows, episodes, top_result, totals: HashMap::new() }
}

pub(crate) fn variables(query: &str, offset: u32, limit: u32) -> Value {
    json!({
        "searchTerm": query,
        "offset": offset,
        "limit": limit,
        "numberOfTopResults": 5,
        "includeAudiobooks": true,
        "includeArtistHasConcertsField": false,
        "includePreReleases": true,
        "includeAlbumPreReleases": false,
        "includeAuthors": false,
        "includeEpisodeContentRatingsV2": false,
        "isPrefix": null,
        "sectionFilters": ["GENERIC"],
    })
}

async fn via_searchview(session: &Session, query: &str, offset: u32, limit: u32) -> AppResult<SearchResults> {
    let user = engine::username().unwrap_or_else(|| session.username());
    let country = session.country();
    let endpoint = format!(
        "/searchview/km/v4/search/{}?limit={}&offset={offset}&entityVersion=2&imageSize=large&catalogue=premium&country={}&locale=en&username={}",
        encode_component(query),
        fetch_limit(limit),
        encode_component(&country),
        encode_component(&user)
    );
    let body = http::spc_get_plain(session, &endpoint, Some(JSON)).await?;
    Ok(parse_searchview(&http::json(&body)?, limit as usize, session.filter_explicit_content()))
}

async fn via_context(session: &Session, query: &str, offset: u32, limit: u32) -> AppResult<SearchResults> {
    let uri = format!("spotify:search:{}", encode_search_query(query));
    // A failed follow-up page only shortens the hit list. Fetching ahead keeps a page full when
    // some tracks have no metadata (the metadata lookup applies the explicit filter).
    let wanted = (offset + fetch_limit(limit)) as usize;
    let items = context::resolve_prefix(session, &uri, wanted, 3).await?.items;
    let uris: Vec<String> = items
        .into_iter()
        .filter_map(|i| parse_kind(&i.uri, UriKind::Track))
        .map(|p| p.uri())
        .skip(offset as usize)
        .collect();
    let mut tracks = metadata::tracks(session, &uris).await?;
    tracks.truncate(limit as usize);
    Ok(SearchResults { tracks, ..Default::default() })
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let query = a.query.trim();
    if query.is_empty() {
        return to_value(&SearchResults::default());
    }
    let types = Types::from_list(&a.types);
    let limit = a.limit.clamp(1, MAX_LIMIT);
    let session = engine::session()?;
    let filter_explicit = session.filter_explicit_content();
    let first = match pathfinder::query(&session, "searchDesktop", variables(query, a.offset, fetch_limit(limit))).await {
        Ok(data) => return to_value(&types.apply(parse_pathfinder(&data, limit as usize, filter_explicit))),
        Err(e) => {
            let e: AppError = e.into();
            log::info!("pathfinder search failed: {e}");
            // Pathfinder runs on another host with its own limits, so searchview is still worth
            // trying when it was offline or throttled; not when the call was cancelled.
            if e.code == ErrorCode::Cancelled {
                return Err(e);
            }
            e
        }
    };
    match via_searchview(&session, query, a.offset, limit).await {
        Ok(r) => return to_value(&types.apply(r)),
        Err(e) => {
            log::info!("searchview failed: {e}");
            // context-resolve uses the same spclient, which has just retried with access-point
            // failover: another request would only wait out its timeout or burn rate limit.
            if stops_chain(&e) {
                return Err(e);
            }
        }
    }
    match via_context(&session, query, a.offset, limit).await {
        Ok(r) => to_value(&types.apply(r)),
        Err(e) => {
            log::info!("context-resolve search failed: {e}");
            Err(first)
        }
    }
}

/// Errors after which the next spclient-based source is not tried.
fn stops_chain(e: &AppError) -> bool {
    matches!(e.code, ErrorCode::Network | ErrorCode::RateLimited | ErrorCode::Cancelled)
}

#[cfg(test)]
mod tests {
    use super::*;

    const PATHFINDER: &str = include_str!("testdata/pathfinder_search.json");
    const SEARCHVIEW: &str = include_str!("testdata/searchview.json");

    #[test]
    fn parses_pathfinder_search() {
        let v: Value = serde_json::from_str(PATHFINDER).unwrap();
        let r = parse_pathfinder(&v["data"], 20, false);
        assert_eq!(r.tracks.len(), 2, "NotFound entity skipped");
        assert_eq!(r.totals["tracks"], 800, "the server's count, not the parsed one");
        assert_eq!(r.totals["albums"], 120);
        assert_eq!(r.totals["shows"], 3);
        assert!(r.tracks[1].explicit && r.tracks[1].playable, "no filter: as the server says");
        assert_eq!(r.tracks[0].uri, "spotify:track:4uLU6hMCjMI75M1A2tKUQC");
        assert_eq!(r.tracks[0].album.as_ref().unwrap().name, "Whenever You Need Somebody");
        assert_eq!(r.tracks[0].duration_ms, 213573);
        assert!(r.tracks[1].explicit);
        assert_eq!(r.artists[0].name, "Rick Astley");
        assert_eq!(r.artists[0].images[0].width, Some(160));
        assert_eq!(r.albums[0].album_type, Some(crate::models::AlbumType::Album));
        assert_eq!(r.albums[0].release_date.as_deref(), Some("1987"));
        assert_eq!(r.playlists[0].owner.as_ref().unwrap().display_name.as_deref(), Some("Spotify"));
        assert_eq!(r.shows[0].publisher.as_deref(), Some("Rick Talks"));
        assert_eq!(r.episodes[0].show.as_ref().unwrap().name, "The Rick Show");
        let top = r.top_result.as_ref().unwrap();
        assert_eq!((top.kind, top.name.as_str()), (MediaType::Artist, "Rick Astley"));
        let limited = parse_pathfinder(&v["data"], 1, false);
        assert_eq!(limited.tracks.len(), 1);
        // The limit counts parsed results: the NotFound entity between them does not cost a slot.
        let two = parse_pathfinder(&v["data"], 2, false);
        assert_eq!(two.tracks.iter().map(|t| t.uri.as_str()).collect::<Vec<_>>(), [
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "spotify:track:7GhIk7Il098yCjg4BQjzvb"
        ]);
        let json = serde_json::to_value(Types::from_list(&["track".into()]).apply(r)).unwrap();
        assert_eq!(json["tracks"].as_array().unwrap().len(), 2);
        assert!(json["artists"].as_array().unwrap().is_empty());
        assert!(json.get("topResult").is_none());
        assert_eq!(json["totals"], serde_json::json!({"tracks": 800}), "totals of the requested types only");
    }

    #[test]
    fn hides_explicit_results_when_the_filter_is_on() {
        let v: Value = serde_json::from_str(PATHFINDER).unwrap();
        let r = parse_pathfinder(&v["data"], 20, true);
        assert!(r.tracks[0].playable, "clean track untouched");
        assert!(r.tracks[1].explicit && !r.tracks[1].playable, "explicit track listed but not playable");
        assert!(r.top_result.is_some(), "an artist top result stays");

        // Top results that are explicit (or now unplayable) tracks/episodes are dropped.
        let explicit = |uri: &str| Track { uri: uri.into(), name: "x".into(), explicit: true, playable: true, ..Default::default() };
        let top = |uri: &str, kind| Some(MediaRef { kind, uri: uri.into(), name: "x".into(), subtitle: None, images: vec![] });
        let mut r = SearchResults { tracks: vec![explicit("spotify:track:a")], top_result: top("spotify:track:a", MediaType::Track), ..Default::default() };
        hide_explicit(&mut r, false);
        assert!(r.top_result.is_none() && !r.tracks[0].playable);
        let mut r = SearchResults { top_result: top("spotify:episode:e", MediaType::Episode), ..Default::default() };
        hide_explicit(&mut r, true);
        assert!(r.top_result.is_none(), "explicit top result not in the lists");
        let mut r = SearchResults { top_result: top("spotify:album:b", MediaType::Album), ..Default::default() };
        hide_explicit(&mut r, true);
        assert!(r.top_result.is_some(), "only tracks and episodes are filtered");

        let sv: Value = serde_json::from_str(SEARCHVIEW).unwrap();
        let mut body = sv.clone();
        body["results"]["tracks"]["hits"][0]["explicit"] = Value::Bool(true);
        let r = parse_searchview(&body, 20, true);
        assert!(!r.tracks[0].playable);
        assert!(parse_searchview(&body, 20, false).tracks[0].playable);
    }

    #[test]
    fn requests_ahead_of_the_page() {
        assert_eq!(fetch_limit(30), 45);
        assert_eq!(fetch_limit(20), 30);
        assert_eq!(fetch_limit(1), 2);
        assert_eq!(fetch_limit(50), MAX_LIMIT, "never more than the server accepts");
    }

    #[test]
    fn parses_searchview() {
        let v: Value = serde_json::from_str(SEARCHVIEW).unwrap();
        let r = parse_searchview(&v, 20, false);
        assert_eq!(r.tracks.len(), 1);
        assert_eq!(r.tracks[0].artists[0].name, "Rick Astley");
        assert_eq!(r.tracks[0].album.as_ref().unwrap().images.len(), 1);
        assert_eq!(r.albums.len(), 1);
        assert_eq!(r.artists.len(), 1);
        assert_eq!(r.playlists[0].owner.as_ref().unwrap().username, "spotify");
        assert_eq!(r.episodes.len(), 1);
        assert!(r.episodes[0].playable, "searchview episodes are playable unless they say otherwise");
        assert_eq!(r.totals["tracks"], 800);
        assert_eq!(r.totals["episodes"], 1);
        assert_eq!(r.top_result.unwrap().kind, MediaType::Artist);
        assert!(parse_searchview(&v, 0, false).tracks.is_empty(), "truncated to the page");
    }

    #[test]
    fn transport_errors_end_the_spclient_fallbacks() {
        for code in [ErrorCode::Network, ErrorCode::RateLimited, ErrorCode::Cancelled] {
            assert!(stops_chain(&AppError::new(code, "x")), "{code:?}");
        }
        assert!(!stops_chain(&AppError::unavailable("searchview: bad response")));
        assert!(!stops_chain(&AppError::not_found("404")));
    }

    #[test]
    fn types_and_variables() {
        assert!(Types::from_list(&[]).all());
        assert!(Types::from_list(&["bogus".into()]).all());
        let t = Types::from_list(&["Show".into(), "episode".into()]);
        assert!(t.shows && t.episodes && !t.tracks);
        let v = variables("rick", 10, 20);
        assert_eq!(v["searchTerm"], "rick");
        assert_eq!(v["sectionFilters"][0], "GENERIC");
    }
}
