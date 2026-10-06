//! `catalog.tracks`, `catalog.episodes`, `catalog.album`, `catalog.artist`, `catalog.show`.

use super::context;
use super::metadata::{self, AlbumMeta};
use super::util::{parse_kind, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::models::{Album, AlbumRef, Artist, ArtistRef, Show};
use crate::rpc::{parse_args, to_value};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;

/// Upper bound for a single `catalog.tracks` / `catalog.episodes` call.
const MAX_URIS: usize = 1000;
/// Releases resolved per artist group.
const GROUP_CAP: usize = 50;
const TOP_TRACKS: usize = 10;
const RELATED_CAP: usize = 20;

#[derive(Deserialize)]
struct UrisArgs {
    #[serde(default)]
    uris: Vec<String>,
}

#[derive(Deserialize)]
pub(crate) struct UriArgs {
    pub uri: String,
}

#[derive(Deserialize)]
struct PageArgs {
    uri: String,
    #[serde(default)]
    offset: u32,
    #[serde(default = "default_show_limit")]
    limit: u32,
}

fn default_show_limit() -> u32 {
    50
}

pub(crate) async fn tracks(args: Value) -> AppResult<Value> {
    let a: UrisArgs = parse_args(args)?;
    if a.uris.len() > MAX_URIS {
        return Err(AppError::invalid(format!("at most {MAX_URIS} uris")));
    }
    let session = engine::session()?;
    let tracks = metadata::tracks(&session, &a.uris).await?;
    Ok(json!({ "tracks": tracks }))
}

pub(crate) async fn episodes(args: Value) -> AppResult<Value> {
    let a: UrisArgs = parse_args(args)?;
    if a.uris.len() > MAX_URIS {
        return Err(AppError::invalid(format!("at most {MAX_URIS} uris")));
    }
    let session = engine::session()?;
    let episodes = metadata::episodes(&session, &a.uris).await?;
    Ok(json!({ "episodes": episodes }))
}

pub(crate) async fn album(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let uri = parse_kind(&a.uri, UriKind::Album).ok_or_else(|| AppError::invalid("not an album uri"))?.uri();
    let session = engine::session()?;
    let meta = metadata::album(&session, &uri).await?;
    let tracks = metadata::tracks(&session, &meta.track_uris).await.unwrap_or_else(|e| {
        log::warn!("album tracks failed: {e}");
        Vec::new()
    });
    to_value(&build_album(&meta, tracks))
}

pub(crate) fn build_album(meta: &AlbumMeta, tracks: Vec<crate::models::Track>) -> Album {
    let a = &meta.album;
    Album {
        uri: a.uri.clone(),
        name: a.name.clone(),
        images: a.images.clone(),
        artists: a.artists.clone(),
        release_date: a.release_date.clone(),
        release_date_precision: meta.release_date_precision.clone(),
        album_type: a.album_type,
        total_tracks: Some(meta.track_uris.len() as u32).filter(|n| *n > 0).or(a.total_tracks),
        label: meta.label.clone(),
        copyrights: meta.copyrights.clone(),
        tracks,
    }
}

/// Album references for `uris` (order kept, unresolved dropped), sorted newest first when
/// `by_date` is set.
pub(crate) fn album_refs(uris: &[String], resolved: &HashMap<String, Arc<AlbumMeta>>, by_date: bool) -> Vec<AlbumRef> {
    let mut metas: Vec<&Arc<AlbumMeta>> = uris.iter().filter_map(|u| resolved.get(u)).collect();
    if by_date {
        metas.sort_by_key(|m| std::cmp::Reverse(m.date_key));
    }
    metas.into_iter().map(|m| m.album.clone()).collect()
}

pub(crate) async fn artist(args: Value) -> AppResult<Value> {
    let a: UriArgs = parse_args(args)?;
    let uri = parse_kind(&a.uri, UriKind::Artist).ok_or_else(|| AppError::invalid("not an artist uri"))?.uri();
    let session = engine::session()?;
    let meta = metadata::artist(&session, &uri).await?;

    let country = session.country();
    let top: Vec<String> = metadata::top_tracks_for(&meta, &country).iter().take(TOP_TRACKS).cloned().collect();
    let groups: [Vec<String>; 4] = [&meta.albums, &meta.singles, &meta.compilations, &meta.appears_on]
        .map(|g| g.iter().take(GROUP_CAP).cloned().collect());
    let all_albums: Vec<String> = groups.iter().flatten().cloned().collect();
    let related_missing_images: Vec<String> = meta
        .related
        .iter()
        .take(RELATED_CAP)
        .filter(|r| r.images.is_empty())
        .map(|r| r.uri.clone())
        .collect();

    let (top_tracks, albums, related_meta) = tokio::join!(
        metadata::tracks(&session, &top),
        metadata::albums(&session, &all_albums),
        metadata::artists(&session, &related_missing_images),
    );
    let top_tracks = top_tracks.unwrap_or_else(|e| {
        log::warn!("artist top tracks failed: {e}");
        Vec::new()
    });
    let albums = albums.unwrap_or_default();
    let related_meta = related_meta.unwrap_or_default();
    let related: Vec<ArtistRef> = meta
        .related
        .iter()
        .take(RELATED_CAP)
        .map(|r| match related_meta.get(&r.uri) {
            Some(m) if r.images.is_empty() => ArtistRef { images: m.artist.images.clone(), ..r.clone() },
            _ => r.clone(),
        })
        .collect();
    let [g_albums, g_singles, g_compilations, g_appears] = groups;
    to_value(&Artist {
        uri: meta.artist.uri.clone(),
        name: meta.artist.name.clone(),
        images: meta.artist.images.clone(),
        header_images: meta.header_images.clone(),
        biography: meta.biography.clone(),
        top_tracks,
        albums: album_refs(&g_albums, &albums, true),
        singles: album_refs(&g_singles, &albums, true),
        compilations: album_refs(&g_compilations, &albums, true),
        appears_on: album_refs(&g_appears, &albums, true),
        related,
        following: None,
    })
}

pub(crate) async fn show(args: Value) -> AppResult<Value> {
    let a: PageArgs = parse_args(args)?;
    let uri = parse_kind(&a.uri, UriKind::Show).ok_or_else(|| AppError::invalid("not a show uri"))?.uri();
    let session = engine::session()?;
    let meta = metadata::show(&session, &uri).await?;
    let mut episode_uris = meta.episode_uris.clone();
    if episode_uris.is_empty() {
        episode_uris = metadata::show_episode_assoc(&session, &uri).await.unwrap_or_default();
    }
    if episode_uris.is_empty() {
        episode_uris = context::resolve(&session, &uri, 2000, 20)
            .await
            .map(|items| {
                items.into_iter().filter_map(|i| parse_kind(&i.uri, UriKind::Episode)).map(|p| p.uri()).collect()
            })
            .unwrap_or_default();
    }
    let total = episode_uris.len() as u32;
    let limit = a.limit.clamp(1, 200) as usize;
    let page: Vec<String> = episode_uris.into_iter().skip(a.offset as usize).take(limit).collect();
    let episodes = metadata::episodes(&session, &page).await?;
    to_value(&Show {
        uri: meta.show.uri.clone(),
        name: meta.show.name.clone(),
        publisher: meta.show.publisher.clone(),
        images: meta.show.images.clone(),
        description: meta.description.clone(),
        episodes,
        total,
        offset: a.offset,
        following: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn meta(uri: &str, key: (i32, i32, i32)) -> Arc<AlbumMeta> {
        Arc::new(AlbumMeta {
            album: AlbumRef { uri: uri.into(), name: uri.into(), ..Default::default() },
            label: None,
            copyrights: vec![],
            release_date_precision: None,
            track_uris: vec![],
            date_key: key,
        })
    }

    #[test]
    fn album_refs_sorted_newest_first() {
        let mut m = HashMap::new();
        m.insert("a".to_string(), meta("a", (2001, 1, 1)));
        m.insert("b".to_string(), meta("b", (2020, 5, 0)));
        m.insert("c".to_string(), meta("c", (2020, 6, 1)));
        let uris = vec!["a".to_string(), "missing".to_string(), "b".to_string(), "c".to_string()];
        let refs: Vec<_> = album_refs(&uris, &m, true).into_iter().map(|a| a.uri).collect();
        assert_eq!(refs, ["c", "b", "a"]);
        let refs: Vec<_> = album_refs(&uris, &m, false).into_iter().map(|a| a.uri).collect();
        assert_eq!(refs, ["a", "b", "c"]);
    }

    #[test]
    fn builds_album_json() {
        let m = AlbumMeta {
            album: AlbumRef { uri: "spotify:album:x".into(), name: "X".into(), total_tracks: Some(3), ..Default::default() },
            label: Some("L".into()),
            copyrights: vec!["© L".into()],
            release_date_precision: Some("year".into()),
            track_uris: vec!["spotify:track:a".into(), "spotify:track:b".into()],
            date_key: (2000, 0, 0),
        };
        let v = serde_json::to_value(build_album(&m, vec![])).unwrap();
        assert_eq!(v["totalTracks"], 2);
        assert_eq!(v["releaseDatePrecision"], "year");
        assert_eq!(v["copyrights"][0], "© L");
        assert!(v["tracks"].as_array().unwrap().is_empty());
    }
}
