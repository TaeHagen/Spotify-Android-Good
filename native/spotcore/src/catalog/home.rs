//! `catalog.home`: pathfinder `home` (sections of tiles), falling back to a feed assembled
//! locally from recently played, the rootlist, followed artists and radio playlists. A failing
//! source only drops its sections and marks the feed `partial`; when every source fails the
//! call fails (an empty feed would pass for the user's home and be cached as such).

use super::collection;
use super::http::{self, JSON};
use super::metadata;
use super::pathfinder;
use super::pfparse;
use super::playlist;
use super::radio;
use super::recent;
use super::refs;
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{ArtistRef, HomeSection, MediaRef, MediaType, PlaylistRef};
use crate::rpc::{parse_args, to_value};
use futures_util::future::join_all;
use librespot_core::Session;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::time::Duration;

const SECTION_ITEMS: usize = 10;

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct Args {
    /// IANA time zone of the device (extension; defaults to UTC). Spotify uses it for
    /// time-of-day sections.
    #[serde(default)]
    time_zone: Option<String>,
}

fn label(v: &Value) -> Option<String> {
    match v {
        Value::String(s) if !s.trim().is_empty() => Some(s.trim().to_string()),
        Value::Object(o) => ["transformedLabel", "text", "translatedBaseText", "baseText", "originalLabel"]
            .iter()
            .find_map(|k| o.get(*k).and_then(label)),
        _ => None,
    }
}

/// `data.home.sectionContainer.sections.items[]` → sections (empty ones dropped).
pub(crate) fn parse_home(data: &Value) -> Vec<HomeSection> {
    let sections = data
        .pointer("/home/sectionContainer/sections/items")
        .or_else(|| data.pointer("/home/sections/items"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    sections
        .iter()
        .enumerate()
        .filter_map(|(i, s)| {
            let items: Vec<MediaRef> = s
                .pointer("/sectionItems/items")
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(pfparse::media).collect())
                .unwrap_or_default();
            if items.is_empty() {
                return None;
            }
            let title = s.pointer("/data/title").and_then(label).or_else(|| s.get("title").and_then(label)).unwrap_or_default();
            let id = s.get("uri").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| format!("section-{i}"));
            Some(HomeSection { id, title, items })
        })
        .collect()
}

pub(crate) fn variables(time_zone: &str) -> Value {
    json!({
        "timeZone": time_zone,
        "sp_t": "",
        "facet": "",
        "sectionItemsLimit": SECTION_ITEMS,
        "homeEndUserIntegration": "INTEGRATION_WEB_PLAYER",
        "includeHomeChapterVideoCards": false,
        "includeEpisodeContentRatingsV2": false,
    })
}

fn section(id: &str, title: &str, items: Vec<MediaRef>) -> Option<HomeSection> {
    (!items.is_empty()).then(|| HomeSection { id: id.into(), title: title.into(), items })
}

/// The radio section and whether a seed lookup failed (then the feed is partial).
async fn radio_section(session: &Session, seeds: Vec<String>) -> (Option<HomeSection>, bool) {
    let lookups = seeds.into_iter().take(3).map(|seed| async move {
        http::spc_get(session, &format!("/inspiredby-mix/v2/seed_to_playlist/{seed}?response-format=json"), Some(JSON))
            .await
            .map(|body| radio::parse_inspiredby(&body))
    });
    let mut failed = false;
    let mut uris: Vec<String> = Vec::new();
    for r in join_all(lookups).await {
        match r {
            Ok(uri) => uris.extend(uri),
            Err(e) => {
                log::info!("home: radio seed failed: {}", e.error);
                failed = true;
            }
        }
    }
    let tiles = refs::resolve(session, &uris).await;
    (section("local:radio", "Radio for you", tiles), failed)
}

/// What the local feed's sources returned.
#[derive(Debug, Default)]
pub(crate) struct Sources {
    pub recent: Vec<MediaRef>,
    pub rootlist: Vec<PlaylistRef>,
    pub artists: Vec<ArtistRef>,
    /// Some source failed: the feed lacks its sections.
    pub partial: bool,
}

fn transport(e: &AppError) -> bool {
    matches!(e.code, ErrorCode::Network | ErrorCode::RateLimited)
}

/// Combines the local feed's sources. Every source failing is an error (a retryable one), never
/// an empty feed that would pass for the user's home; some failing make the feed partial.
pub(crate) fn combine_sources(
    recent: AppResult<Vec<MediaRef>>,
    rootlist: AppResult<Vec<PlaylistRef>>,
    artists: AppResult<Vec<ArtistRef>>,
) -> AppResult<Sources> {
    fn keep<T>(errors: &mut Vec<AppError>, name: &str, r: AppResult<T>) -> Option<T> {
        match r {
            Ok(v) => Some(v),
            Err(e) => {
                log::info!("home: {name} failed: {e}");
                errors.push(e);
                None
            }
        }
    }
    let mut errors: Vec<AppError> = Vec::new();
    let recent = keep(&mut errors, "recently played", recent);
    let rootlist = keep(&mut errors, "rootlist", rootlist);
    let artists = keep(&mut errors, "followed artists", artists);
    if recent.is_none() && rootlist.is_none() && artists.is_none() {
        let first = errors.iter().position(transport).unwrap_or(0);
        return Err(metadata::page_error(errors.swap_remove(first)));
    }
    Ok(Sources {
        partial: !errors.is_empty(),
        recent: recent.unwrap_or_default(),
        rootlist: rootlist.unwrap_or_default(),
        artists: artists.unwrap_or_default(),
    })
}

/// Feed assembled from the user's own data, and whether it is partial (a source failed).
pub(crate) async fn local_feed(session: &Session) -> AppResult<(Vec<HomeSection>, bool)> {
    let (recent, rootlist, artists) = tokio::join!(
        recent::recently_played(session, 20),
        playlist::rootlist_refs(session, Duration::from_secs(120)),
        collection::followed_artists(session, SECTION_ITEMS),
    );
    let Sources { recent, rootlist, artists, partial } = combine_sources(recent, rootlist, artists)?;

    let seeds: Vec<String> = recent
        .iter()
        .filter(|m| m.kind == MediaType::Artist)
        .map(|m| m.uri.clone())
        .chain(artists.iter().map(|a| a.uri.clone()))
        .collect::<Vec<_>>();
    let mut seen = HashSet::new();
    let seeds: Vec<String> = seeds.into_iter().filter(|s| seen.insert(s.clone())).collect();
    let (radio, radio_failed) = radio_section(session, seeds).await;

    let made_for_you: Vec<MediaRef> = rootlist
        .iter()
        .filter(|p| p.owner.as_ref().is_some_and(|o| o.username == "spotify"))
        .take(SECTION_ITEMS)
        .map(refs::playlist_media)
        .collect();
    let yours: Vec<MediaRef> = rootlist.iter().take(SECTION_ITEMS).map(refs::playlist_media).collect();
    let jump_back: Vec<MediaRef> =
        recent.iter().filter(|m| m.kind == MediaType::Album).take(SECTION_ITEMS).cloned().collect();

    let sections = [
        section("local:recently-played", "Recently played", recent.into_iter().take(SECTION_ITEMS).collect()),
        section("local:made-for-you", "Made for you", made_for_you),
        section("local:your-playlists", "Your playlists", yours),
        section("local:jump-back-in", "Jump back in", jump_back),
        section("local:your-artists", "Your artists", artists.iter().map(refs::artist_media).collect()),
        radio,
    ]
    .into_iter()
    .flatten()
    .collect();
    Ok((sections, partial || radio_failed))
}

/// The `catalog.home` result: sections, plus `"partial": true` when part of the feed failed.
pub(crate) fn feed_value(sections: Vec<HomeSection>, partial: bool) -> Value {
    let mut out = json!({ "sections": sections });
    if partial {
        out["partial"] = json!(true);
    }
    out
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = if args.is_null() { Args::default() } else { parse_args(args)? };
    let session = engine::session()?;
    let tz = a.time_zone.filter(|t| !t.is_empty()).unwrap_or_else(|| "UTC".to_string());
    let pathfinder_error = match pathfinder::query(&session, "home", variables(&tz)).await {
        Ok(data) => {
            let sections = parse_home(&data);
            if !sections.is_empty() {
                return to_value(&feed_value(sections, false));
            }
            log::info!("pathfinder home returned no sections; using the local feed");
            None
        }
        Err(e) => {
            let e = AppError::from(e);
            log::info!("pathfinder home failed: {e}");
            Some(e)
        }
    };
    match local_feed(&session).await {
        Ok((sections, partial)) => to_value(&feed_value(sections, partial)),
        // Offline or throttled says more than whichever local source failed last.
        Err(e) => Err(match pathfinder_error {
            Some(p) if transport(&p) && !transport(&e) => p,
            _ => e,
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_home_sections() {
        let v: Value = serde_json::from_str(include_str!("testdata/pathfinder_home.json")).unwrap();
        let sections = parse_home(&v["data"]);
        assert_eq!(sections.len(), 3, "empty section dropped");
        assert_eq!(sections[0].title, "Made For Alice");
        assert_eq!(sections[0].id, "spotify:section:0JQ5DAqbMKFHOzuVTgTizF");
        assert_eq!(sections[0].items.len(), 2, "unknown item types skipped");
        assert_eq!(sections[0].items[0].kind, MediaType::Playlist);
        assert_eq!(sections[0].items[0].subtitle.as_deref(), Some("Your weekly mixtape of fresh music."));
        assert_eq!(sections[0].items[1].kind, MediaType::Album);
        assert_eq!(sections[0].items[1].subtitle.as_deref(), Some("Rick Astley"));
        assert_eq!(sections[1].title, "");
        assert_eq!(sections[1].items[0].kind, MediaType::Collection);
        assert_eq!(sections[2].title, "Popular podcasts");
        assert_eq!(sections[2].items[0].kind, MediaType::Show);
        assert_eq!(sections[2].items[1].kind, MediaType::Artist);
        let json = serde_json::to_value(&sections).unwrap();
        assert_eq!(json[0]["items"][0]["type"], "playlist");
    }

    #[test]
    fn local_feed_sources_never_fail_into_an_empty_feed() {
        let net = || AppError::new(ErrorCode::Network, "offline");
        let artist = ArtistRef { uri: "spotify:artist:0gxyHStUsqpMadRV0Di1Qt".into(), name: "Rick".into(), images: vec![] };
        // Everything answered: complete (even when there is little to show).
        let s = combine_sources(Ok(vec![]), Ok(vec![]), Ok(vec![artist.clone()])).unwrap();
        assert!(!s.partial && s.artists.len() == 1);
        // Some sources failed: what arrived, marked partial.
        let s = combine_sources(Err(net()), Ok(vec![]), Ok(vec![artist])).unwrap();
        assert!(s.partial && s.recent.is_empty() && s.artists.len() == 1);
        // All failed: a retryable error, preferring the transport one.
        let e = combine_sources(Err(AppError::not_found("404")), Err(net()), Err(AppError::unavailable("403"))).unwrap_err();
        assert_eq!(e.code, ErrorCode::Network);
        let e = combine_sources(Err(AppError::not_found("404")), Err(AppError::internal("x")), Err(AppError::internal("y"))).unwrap_err();
        assert_eq!(e.code, ErrorCode::Unavailable, "never NOT_FOUND for the home feed");

        assert_eq!(feed_value(vec![], true)["partial"], true);
        assert!(feed_value(vec![], false).get("partial").is_none());
    }

    #[test]
    fn labels_and_variables() {
        assert_eq!(label(&json!({"transformedLabel": "Hi"})).as_deref(), Some("Hi"));
        assert_eq!(label(&json!({"originalLabel": {"baseText": "Base"}})).as_deref(), Some("Base"));
        assert_eq!(label(&json!({"text": "  "})), None);
        assert_eq!(variables("Europe/Berlin")["timeZone"], "Europe/Berlin");
        assert!(section("x", "X", vec![]).is_none());
    }
}
