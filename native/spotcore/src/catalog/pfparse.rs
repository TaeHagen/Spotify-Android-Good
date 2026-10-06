//! Lenient parsers for pathfinder GraphQL entities (search results, home sections). Every
//! parser returns `None` for unknown/unavailable entities (`NotFound`, `RestrictedContent`, …)
//! instead of failing the page.

use super::util::{normalize_images, parse_uri, strip_html, UriKind};
use crate::models::{
    AlbumRef, AlbumType, ArtistRef, Episode, Image, MediaRef, MediaType, PlaylistOwner, PlaylistRef, ShowRef, Track,
};
use serde_json::Value;

pub(crate) fn typename(v: &Value) -> &str {
    v.get("__typename").and_then(Value::as_str).unwrap_or_default()
}

/// Unwraps `{item:{data:…}}`, `{data:…}`, `{content:{data:…}}` and `*ResponseWrapper` layers.
pub(crate) fn entity(v: &Value) -> &Value {
    let mut cur = v;
    for _ in 0..4 {
        let tn = typename(cur);
        let next = if !tn.is_empty() && !tn.ends_with("Wrapper") {
            None // a concrete entity
        } else {
            ["data", "item", "content"].iter().find_map(|k| cur.get(*k).filter(|n| n.is_object()))
        };
        match next {
            Some(n) => cur = n,
            None => break,
        }
    }
    cur
}

fn str_at<'a>(v: &'a Value, path: &str) -> Option<&'a str> {
    v.pointer(path).and_then(Value::as_str).filter(|s| !s.is_empty())
}

pub(crate) fn sources(v: Option<&Value>) -> Vec<Image> {
    let Some(arr) = v.and_then(|s| s.get("sources").or(Some(s))).and_then(Value::as_array) else { return Vec::new() };
    arr.iter()
        .filter_map(|s| {
            let url = s.get("url").and_then(Value::as_str).filter(|u| u.starts_with("https://"))?;
            let dim = |k: &str| s.get(k).and_then(Value::as_u64).filter(|n| *n > 0).map(|n| n as u32);
            Some(Image { url: url.to_string(), width: dim("width"), height: dim("height") })
        })
        .collect()
}

/// Cover/avatar images in any of the shapes pathfinder uses.
pub(crate) fn images(v: &Value) -> Vec<Image> {
    let mut out = sources(v.get("coverArt"));
    if out.is_empty() {
        out = sources(v.pointer("/visuals/avatarImage"));
    }
    if out.is_empty() {
        if let Some(items) = v.pointer("/images/items").and_then(Value::as_array) {
            out = items.iter().flat_map(|i| sources(Some(i))).collect();
        }
    }
    if out.is_empty() {
        out = sources(v.get("image")).into_iter().chain(sources(v.get("avatar"))).collect();
    }
    if out.is_empty() {
        out = sources(v.get("images"));
    }
    normalize_images(out)
}

fn artists(v: &Value) -> Vec<ArtistRef> {
    v.pointer("/artists/items")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(|a| {
                    let a = entity(a);
                    Some(ArtistRef {
                        uri: str_at(a, "/uri")?.to_string(),
                        name: str_at(a, "/profile/name").or_else(|| str_at(a, "/name")).unwrap_or_default().to_string(),
                        images: Vec::new(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

fn uri_of(v: &Value, kind: UriKind) -> Option<String> {
    parse_uri(str_at(v, "/uri")?).filter(|p| p.kind == kind).map(|p| p.uri())
}

fn explicit(v: &Value) -> bool {
    str_at(v, "/contentRating/label").is_some_and(|l| l.eq_ignore_ascii_case("EXPLICIT"))
}

fn playable(v: &Value) -> bool {
    v.pointer("/playability/playable").and_then(Value::as_bool).unwrap_or(true)
}

fn duration_ms(v: &Value) -> u64 {
    v.pointer("/duration/totalMilliseconds")
        .or_else(|| v.pointer("/trackDuration/totalMilliseconds"))
        .and_then(|d| d.as_u64().or_else(|| d.as_f64().map(|f| f as u64)))
        .unwrap_or(0)
}

fn date(v: &Value) -> Option<String> {
    if let Some(iso) = str_at(v, "/isoString") {
        let day = iso.split('T').next().unwrap_or(iso);
        return match str_at(v, "/precision").map(str::to_ascii_uppercase).as_deref() {
            Some("YEAR") => day.get(..4).map(str::to_string),
            Some("MONTH") => day.get(..7).map(str::to_string),
            _ => Some(day.to_string()),
        };
    }
    v.get("year").and_then(Value::as_i64).map(|y| format!("{y:04}"))
}

pub(crate) fn track(v: &Value) -> Option<Track> {
    let v = entity(v);
    if !matches!(typename(v), "Track" | "") {
        return None;
    }
    let album = v.get("albumOfTrack").map(entity).and_then(|a| {
        Some(AlbumRef {
            uri: uri_of(a, UriKind::Album)?,
            name: str_at(a, "/name").unwrap_or_default().to_string(),
            images: images(a),
            artists: artists(a),
            ..Default::default()
        })
    });
    Some(Track {
        uri: uri_of(v, UriKind::Track)?,
        name: str_at(v, "/name")?.to_string(),
        artists: artists(v),
        album,
        duration_ms: duration_ms(v),
        explicit: explicit(v),
        playable: playable(v),
        track_number: v.get("trackNumber").and_then(Value::as_u64).map(|n| n as u32),
        disc_number: v.get("discNumber").and_then(Value::as_u64).map(|n| n as u32),
        popularity: None,
        has_lyrics: None,
    })
}

pub(crate) fn album(v: &Value) -> Option<AlbumRef> {
    let v = entity(v);
    if !matches!(typename(v), "Album" | "PreRelease" | "") {
        return None;
    }
    let album_type = match str_at(v, "/type").map(str::to_ascii_uppercase).as_deref() {
        Some("ALBUM") => Some(AlbumType::Album),
        Some("SINGLE") => Some(AlbumType::Single),
        Some("EP") => Some(AlbumType::Ep),
        Some("COMPILATION") => Some(AlbumType::Compilation),
        _ => None,
    };
    Some(AlbumRef {
        uri: uri_of(v, UriKind::Album)?,
        name: str_at(v, "/name")?.to_string(),
        images: images(v),
        artists: artists(v),
        release_date: v.get("date").and_then(date),
        album_type,
        total_tracks: v.pointer("/tracks/totalCount").and_then(Value::as_u64).map(|n| n as u32),
    })
}

pub(crate) fn artist(v: &Value) -> Option<ArtistRef> {
    let v = entity(v);
    if !matches!(typename(v), "Artist" | "") {
        return None;
    }
    Some(ArtistRef {
        uri: uri_of(v, UriKind::Artist)?,
        name: str_at(v, "/profile/name").or_else(|| str_at(v, "/name"))?.to_string(),
        images: images(v),
    })
}

pub(crate) fn playlist(v: &Value) -> Option<PlaylistRef> {
    let v = entity(v);
    if !matches!(typename(v), "Playlist" | "") {
        return None;
    }
    let owner = v.get("ownerV2").or_else(|| v.get("owner")).map(entity).and_then(|o| {
        let username = str_at(o, "/username")
            .or_else(|| str_at(o, "/uri").and_then(|u| u.strip_prefix("spotify:user:")))
            .or_else(|| str_at(o, "/id"))?
            .to_string();
        Some(PlaylistOwner { username, display_name: str_at(o, "/name").map(str::to_string) })
    });
    Some(PlaylistRef {
        uri: uri_of(v, UriKind::Playlist)?,
        name: str_at(v, "/name")?.to_string(),
        description: str_at(v, "/description").map(strip_html).filter(|d| !d.is_empty()),
        images: images(v),
        owner,
        total_tracks: v.pointer("/content/totalCount").and_then(Value::as_u64).map(|n| n as u32),
    })
}

pub(crate) fn show(v: &Value) -> Option<ShowRef> {
    let v = entity(v);
    if !matches!(typename(v), "Podcast" | "Audiobook" | "") {
        return None;
    }
    Some(ShowRef {
        uri: uri_of(v, UriKind::Show)?,
        name: str_at(v, "/name")?.to_string(),
        publisher: str_at(v, "/publisher/name").map(str::to_string),
        images: images(v),
    })
}

pub(crate) fn episode(v: &Value) -> Option<Episode> {
    let v = entity(v);
    if !matches!(typename(v), "Episode" | "Chapter" | "") {
        return None;
    }
    let images = images(v);
    let show = v.get("podcastV2").or_else(|| v.get("podcast")).map(entity).and_then(|p| {
        let mut s = show(p)?;
        if s.images.is_empty() {
            s.images = images.clone();
        }
        Some(s)
    });
    Some(Episode {
        uri: uri_of(v, UriKind::Episode)?,
        name: str_at(v, "/name")?.to_string(),
        show,
        description: str_at(v, "/description").map(strip_html).unwrap_or_default(),
        duration_ms: duration_ms(v),
        release_date: v.get("releaseDate").and_then(date),
        images,
        explicit: explicit(v),
        playable: playable(v),
        resume_position_ms: v.pointer("/playedState/playPositionMilliseconds").and_then(Value::as_u64),
        fully_played: str_at(v, "/playedState/state").map(|s| s.eq_ignore_ascii_case("COMPLETED")),
    })
}

fn join(artists: &[ArtistRef]) -> Option<String> {
    super::refs::join_artists(artists)
}

/// A tile for any entity (home items, search top result).
pub(crate) fn media(v: &Value) -> Option<MediaRef> {
    let e = entity(v);
    let tn = typename(e);
    let uri = str_at(e, "/uri").unwrap_or_default();
    let kind = parse_uri(uri).map(|p| p.kind);
    let collection = tn == "PseudoPlaylist" || uri.ends_with(":collection") || uri.contains(":collection:");
    if collection {
        return Some(MediaRef {
            kind: MediaType::Collection,
            uri: uri.to_string(),
            name: str_at(e, "/name").unwrap_or("Liked Songs").to_string(),
            subtitle: None,
            images: images(e),
        });
    }
    match (tn, kind) {
        ("Track", _) | ("", Some(UriKind::Track)) => track(e).map(|t| super::refs::track_media(&t)),
        ("Album", _) | ("", Some(UriKind::Album)) => album(e).map(|a| super::refs::album_media(&a)),
        ("Artist", _) | ("", Some(UriKind::Artist)) => artist(e).map(|a| super::refs::artist_media(&a)),
        ("Playlist", _) | ("", Some(UriKind::Playlist)) => playlist(e).map(|p| super::refs::playlist_media(&p)),
        ("Podcast" | "Audiobook", _) | ("", Some(UriKind::Show)) => show(e).map(|s| super::refs::show_media(&s)),
        ("Episode" | "Chapter", _) | ("", Some(UriKind::Episode)) => episode(e).map(|ep| super::refs::episode_media(&ep)),
        _ => None,
    }
    .map(|mut m| {
        if m.subtitle.is_none() && m.kind == MediaType::Album {
            m.subtitle = join(&album(e).map(|a| a.artists).unwrap_or_default());
        }
        m
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_wrapped_entities() {
        let v = json!({"item": {"__typename": "TrackResponseWrapper", "data": {
            "__typename": "Track", "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "name": "Never Gonna",
            "albumOfTrack": {"uri": "spotify:album:6XhjNHCyCDyyGJRM5mg40G", "name": "Whenever",
                "coverArt": {"sources": [{"url": "https://i.scdn.co/image/b", "width": 640, "height": 640},
                                         {"url": "https://i.scdn.co/image/a", "width": 64, "height": 64}]}},
            "artists": {"items": [{"uri": "spotify:artist:0gxyHStUsqpMadRV0Di1Qt", "profile": {"name": "Rick Astley"}}]},
            "contentRating": {"label": "EXPLICIT"}, "duration": {"totalMilliseconds": 213573},
            "playability": {"playable": false, "reason": "REGIONAL"}}}});
        let t = track(&v).unwrap();
        assert_eq!(t.name, "Never Gonna");
        assert!(t.explicit && !t.playable);
        assert_eq!(t.album.as_ref().unwrap().images[0].width, Some(64));
        assert_eq!(t.artists[0].name, "Rick Astley");
        assert!(track(&json!({"data": {"__typename": "NotFound"}})).is_none());
        assert!(track(&json!({"data": {"__typename": "Track", "uri": "spotify:track:bad", "name": "x"}})).is_none());
    }

    #[test]
    fn parses_episode_and_dates() {
        let v = json!({"data": {"__typename": "Episode", "uri": "spotify:episode:512ojhOuo1ktJprKbVcKyQ", "name": "Ep",
            "description": "<b>Hi</b>", "releaseDate": {"isoString": "2024-02-29T10:00:00Z", "precision": "DAY"},
            "duration": {"totalMilliseconds": 1000},
            "podcastV2": {"data": {"__typename": "Podcast", "uri": "spotify:show:5CfCWKI5pZ28U0uOzXkDHe", "name": "Show",
                "publisher": {"name": "Pub"}}},
            "coverArt": {"sources": [{"url": "https://i.scdn.co/image/e"}]}}});
        let e = episode(&v).unwrap();
        assert_eq!(e.release_date.as_deref(), Some("2024-02-29"));
        assert_eq!(e.description, "Hi");
        assert_eq!(e.show.as_ref().unwrap().publisher.as_deref(), Some("Pub"));
        assert_eq!(e.show.as_ref().unwrap().images.len(), 1, "inherits episode cover");
        assert_eq!(date(&json!({"isoString": "2020-05-01T00:00:00Z", "precision": "YEAR"})).as_deref(), Some("2020"));
        assert_eq!(date(&json!({"year": 1999})).as_deref(), Some("1999"));
    }

    #[test]
    fn media_tiles() {
        let pl = json!({"uri": "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "content": {"__typename": "PlaylistResponseWrapper",
            "data": {"__typename": "Playlist", "uri": "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "name": "Today's Top Hits",
            "description": "The hottest 50.", "images": {"items": [{"sources": [{"url": "https://i.scdn.co/image/p", "width": null}]}]},
            "ownerV2": {"data": {"__typename": "User", "name": "Spotify", "uri": "spotify:user:spotify"}}}}});
        let m = media(&pl).unwrap();
        assert_eq!(m.kind, MediaType::Playlist);
        assert_eq!(m.subtitle.as_deref(), Some("The hottest 50."));
        assert_eq!(m.images.len(), 1);
        let p = playlist(&pl).unwrap();
        assert_eq!(p.owner.unwrap().username, "spotify");
        let liked = json!({"content": {"data": {"__typename": "PseudoPlaylist", "uri": "spotify:user:alice:collection", "name": "Liked Songs", "count": 12}}});
        assert_eq!(media(&liked).unwrap().kind, MediaType::Collection);
        assert!(media(&json!({"content": {"data": {"__typename": "Concert", "uri": "spotify:concert:x"}}})).is_none());
        let artist_tile = json!({"data": {"__typename": "Artist", "uri": "spotify:artist:0gxyHStUsqpMadRV0Di1Qt",
            "profile": {"name": "Rick"}, "visuals": {"avatarImage": {"sources": [{"url": "https://i.scdn.co/image/r", "width": 320}]}}}});
        let m = media(&artist_tile).unwrap();
        assert_eq!((m.kind, m.images[0].width), (MediaType::Artist, Some(320)));
    }
}
