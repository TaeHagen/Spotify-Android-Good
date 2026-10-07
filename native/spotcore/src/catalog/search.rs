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
use std::collections::HashSet;

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
        r
    }
}

fn items(root: &Value, key: &str) -> Vec<Value> {
    root.pointer(&format!("/{key}/items")).and_then(Value::as_array).cloned().unwrap_or_default()
}

/// `data.searchV2` of a `searchDesktop` response → results (unparseable entities skipped).
pub(crate) fn parse_pathfinder(data: &Value, limit: usize) -> SearchResults {
    let root = data.get("searchV2").unwrap_or(data);
    let take = |key: &str| -> Vec<Value> { items(root, key).into_iter().take(limit).collect() };
    let albums_key = if root.get("albumsV2").is_some() { "albumsV2" } else { "albums" };
    let tracks_key = if root.get("tracksV2").is_some() { "tracksV2" } else { "tracks" };
    let top = root
        .pointer("/topResultsV2/itemsV2")
        .or_else(|| root.pointer("/topResults/itemsV2"))
        .or_else(|| root.pointer("/topResults/items"))
        .and_then(Value::as_array)
        .and_then(|a| a.iter().find_map(pfparse::media));
    SearchResults {
        tracks: take(tracks_key).iter().filter_map(pfparse::track).collect(),
        artists: take("artists").iter().filter_map(pfparse::artist).collect(),
        albums: take(albums_key).iter().filter_map(pfparse::album).collect(),
        playlists: take("playlists").iter().filter_map(pfparse::playlist).collect(),
        shows: take("podcasts").iter().filter_map(pfparse::show).collect(),
        episodes: take("episodes").iter().filter_map(pfparse::episode).collect(),
        top_result: top,
    }
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
pub(crate) fn parse_searchview(body: &Value) -> SearchResults {
    let results = body.get("results").unwrap_or(body);
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
    SearchResults { tracks, artists, albums, playlists, shows, episodes, top_result }
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
        "/searchview/km/v4/search/{}?limit={limit}&offset={offset}&entityVersion=2&imageSize=large&catalogue=premium&country={}&locale=en&username={}",
        encode_component(query),
        encode_component(&country),
        encode_component(&user)
    );
    let body = http::spc_get_plain(session, &endpoint, Some(JSON)).await?;
    Ok(parse_searchview(&http::json(&body)?))
}

async fn via_context(session: &Session, query: &str, offset: u32, limit: u32) -> AppResult<SearchResults> {
    let uri = format!("spotify:search:{}", encode_search_query(query));
    // A failed follow-up page only shortens the hit list.
    let items = context::resolve_prefix(session, &uri, (offset + limit) as usize, 3).await?.items;
    let uris: Vec<String> = items
        .into_iter()
        .filter_map(|i| parse_kind(&i.uri, UriKind::Track))
        .map(|p| p.uri())
        .skip(offset as usize)
        .take(limit as usize)
        .collect();
    Ok(SearchResults { tracks: metadata::tracks(session, &uris).await?, ..Default::default() })
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let query = a.query.trim();
    if query.is_empty() {
        return to_value(&SearchResults::default());
    }
    let types = Types::from_list(&a.types);
    let limit = a.limit.clamp(1, 50);
    let session = engine::session()?;
    let first = match pathfinder::query(&session, "searchDesktop", variables(query, a.offset, limit)).await {
        Ok(data) => return to_value(&types.apply(parse_pathfinder(&data, limit as usize))),
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
        let r = parse_pathfinder(&v["data"], 20);
        assert_eq!(r.tracks.len(), 2, "NotFound entity skipped");
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
        let limited = parse_pathfinder(&v["data"], 1);
        assert_eq!(limited.tracks.len(), 1);
        let json = serde_json::to_value(Types::from_list(&["track".into()]).apply(r)).unwrap();
        assert_eq!(json["tracks"].as_array().unwrap().len(), 2);
        assert!(json["artists"].as_array().unwrap().is_empty());
        assert!(json.get("topResult").is_none());
    }

    #[test]
    fn parses_searchview() {
        let v: Value = serde_json::from_str(SEARCHVIEW).unwrap();
        let r = parse_searchview(&v);
        assert_eq!(r.tracks.len(), 1);
        assert_eq!(r.tracks[0].artists[0].name, "Rick Astley");
        assert_eq!(r.tracks[0].album.as_ref().unwrap().images.len(), 1);
        assert_eq!(r.albums.len(), 1);
        assert_eq!(r.artists.len(), 1);
        assert_eq!(r.playlists[0].owner.as_ref().unwrap().username, "spotify");
        assert_eq!(r.episodes.len(), 1);
        assert_eq!(r.top_result.unwrap().kind, MediaType::Artist);
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
