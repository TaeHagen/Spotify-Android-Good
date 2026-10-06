//! Resolving arbitrary entity/context URIs into `MediaRef` tiles (recently played, home).

use super::metadata;
use super::playlist;
use super::util::{parse_uri, UriKind};
use crate::models::{AlbumRef, ArtistRef, MediaRef, MediaType, PlaylistRef, ShowRef, Track};
use librespot_core::Session;
use std::collections::{HashMap, HashSet};

pub(crate) fn join_artists(artists: &[ArtistRef]) -> Option<String> {
    let s = artists.iter().map(|a| a.name.as_str()).filter(|n| !n.is_empty()).collect::<Vec<_>>().join(", ");
    (!s.is_empty()).then_some(s)
}

pub(crate) fn album_media(a: &AlbumRef) -> MediaRef {
    MediaRef {
        kind: MediaType::Album,
        uri: a.uri.clone(),
        name: a.name.clone(),
        subtitle: join_artists(&a.artists),
        images: a.images.clone(),
    }
}

pub(crate) fn artist_media(a: &ArtistRef) -> MediaRef {
    MediaRef { kind: MediaType::Artist, uri: a.uri.clone(), name: a.name.clone(), subtitle: None, images: a.images.clone() }
}

pub(crate) fn playlist_media(p: &PlaylistRef) -> MediaRef {
    MediaRef {
        kind: MediaType::Playlist,
        uri: p.uri.clone(),
        name: p.name.clone(),
        subtitle: p
            .description
            .clone()
            .or_else(|| p.owner.as_ref().map(|o| o.display_name.clone().unwrap_or_else(|| o.username.clone()))),
        images: p.images.clone(),
    }
}

pub(crate) fn show_media(s: &ShowRef) -> MediaRef {
    MediaRef { kind: MediaType::Show, uri: s.uri.clone(), name: s.name.clone(), subtitle: s.publisher.clone(), images: s.images.clone() }
}

pub(crate) fn track_media(t: &Track) -> MediaRef {
    MediaRef {
        kind: MediaType::Track,
        uri: t.uri.clone(),
        name: t.name.clone(),
        subtitle: join_artists(&t.artists),
        images: t.album.as_ref().map(|a| a.images.clone()).unwrap_or_default(),
    }
}

pub(crate) fn episode_media(e: &crate::models::Episode) -> MediaRef {
    MediaRef {
        kind: MediaType::Episode,
        uri: e.uri.clone(),
        name: e.name.clone(),
        subtitle: e.show.as_ref().map(|s| s.name.clone()),
        images: e.images.clone(),
    }
}

/// `spotify:user:<u>:collection` (Liked Songs) and `…:collection:your-episodes`.
pub(crate) fn collection_media(uri: &str) -> Option<MediaRef> {
    let parts: Vec<&str> = uri.split(':').collect();
    let name = match parts.as_slice() {
        ["spotify", "user", _, "collection"] | ["spotify", "collection", "tracks"] => "Liked Songs",
        ["spotify", "user", _, "collection", "your-episodes"] | ["spotify", "collection", "your-episodes"] => {
            "Your Episodes"
        }
        _ => return None,
    };
    Some(MediaRef { kind: MediaType::Collection, uri: uri.to_string(), name: name.to_string(), subtitle: None, images: Vec::new() })
}

/// Resolves `uris` into tiles (order kept, duplicates and unresolvable URIs dropped). Every
/// lookup is batched per kind; failures of one kind only drop that kind.
pub(crate) async fn resolve(session: &Session, uris: &[String]) -> Vec<MediaRef> {
    let mut seen = HashSet::new();
    let mut ordered: Vec<(String, Option<UriKind>)> = Vec::new();
    for uri in uris {
        let parsed = parse_uri(uri);
        let key = parsed.as_ref().map(|p| p.uri()).unwrap_or_else(|| uri.clone());
        if seen.insert(key.clone()) {
            ordered.push((key, parsed.map(|p| p.kind)));
        }
    }
    let of = |k: UriKind| -> Vec<String> {
        ordered.iter().filter(|(_, kind)| *kind == Some(k)).map(|(u, _)| u.clone()).collect()
    };
    let (album_uris, artist_uris, show_uris, track_uris, episode_uris, playlist_uris) = (
        of(UriKind::Album),
        of(UriKind::Artist),
        of(UriKind::Show),
        of(UriKind::Track),
        of(UriKind::Episode),
        of(UriKind::Playlist),
    );
    let (albums, artists, shows, tracks, episodes, playlists) = tokio::join!(
        metadata::albums(session, &album_uris),
        metadata::artists(session, &artist_uris),
        metadata::shows(session, &show_uris),
        metadata::track_map(session, &track_uris),
        metadata::episode_map(session, &episode_uris),
        playlist::headers(session, &playlist_uris),
    );
    let albums = albums.unwrap_or_default();
    let artists = artists.unwrap_or_default();
    let shows = shows.unwrap_or_default();
    let tracks = tracks.unwrap_or_default();
    let episodes = episodes.unwrap_or_default();
    let playlists: HashMap<String, PlaylistRef> = playlists;
    ordered
        .iter()
        .filter_map(|(uri, kind)| match kind {
            Some(UriKind::Album) => albums.get(uri).map(|a| album_media(&a.album)),
            Some(UriKind::Artist) => artists.get(uri).map(|a| artist_media(&a.artist)),
            Some(UriKind::Show) => shows.get(uri).map(|s| show_media(&s.show)),
            Some(UriKind::Track) => tracks.get(uri).map(|t| track_media(t)),
            Some(UriKind::Episode) => episodes.get(uri).map(|e| episode_media(e)),
            Some(UriKind::Playlist) => playlists.get(uri).map(playlist_media),
            None => collection_media(uri),
        })
        .filter(|m| !m.name.is_empty())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collection_tiles() {
        assert_eq!(collection_media("spotify:user:alice:collection").unwrap().name, "Liked Songs");
        assert_eq!(collection_media("spotify:user:alice:collection:your-episodes").unwrap().name, "Your Episodes");
        assert!(collection_media("spotify:user:alice:collection:artist:x").is_none());
        assert!(collection_media("spotify:station:artist:x").is_none());
    }

    #[test]
    fn media_subtitles() {
        let a = AlbumRef {
            uri: "spotify:album:x".into(),
            name: "X".into(),
            artists: vec![ArtistRef { uri: "a".into(), name: "A".into(), images: vec![] }, ArtistRef {
                uri: "b".into(),
                name: "B".into(),
                images: vec![],
            }],
            ..Default::default()
        };
        assert_eq!(album_media(&a).subtitle.as_deref(), Some("A, B"));
        let v = serde_json::to_value(album_media(&a)).unwrap();
        assert_eq!(v["type"], "album");
    }
}
