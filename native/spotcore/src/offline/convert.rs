//! Conversions for downloads:
//! * librespot metadata (`Track`, `Episode` + raw episode proto) → app JSON models, stored in the
//!   `OfflineTrackRecord` at download time;
//! * stored app models → `AudioItem` for the Player's offline hook (no network);
//! * the availability check librespot's `AudioItem::get_file` does (re-implemented: it is private).

use crate::models::{self, AlbumRef, AlbumType, ArtistRef, Image, ShowRef};
use librespot_core::date::Date;
use librespot_core::{FileId, SpotifyId, SpotifyUri};
use librespot_metadata::album::AlbumType as ProtoAlbumType;
use librespot_metadata::artist::{ArtistRole, ArtistWithRole, ArtistsWithRole};
use librespot_metadata::audio::item::CoverImage;
use librespot_metadata::audio::{AudioFileFormat, AudioFiles, AudioItem, UniqueFields};
use librespot_metadata::availability::{Availabilities, UnavailabilityReason};
use librespot_metadata::image::{ImageSize, Images};
use librespot_metadata::restriction::Restrictions;
use librespot_metadata::{Episode, Track};
use librespot_protocol::metadata as pm;
use std::borrow::Cow;

// ---------------------------------------------------------------------------------------------
// Dates (proleptic Gregorian, UTC)
// ---------------------------------------------------------------------------------------------

const MS_PER_DAY: i64 = 86_400_000;

/// Days since 1970-01-01 (H. Hinnant's `days_from_civil`).
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (i64::from(m) + 9) % 12;
    let doy = (153 * mp + 2) / 5 + i64::from(d) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

/// `YYYY-MM-DD` for an epoch-ms timestamp; `None` before 1900 (librespot's "no date").
pub fn date_string(ms: i64) -> Option<String> {
    let (y, m, d) = civil_from_days(ms.div_euclid(MS_PER_DAY));
    (y >= 1900).then(|| format!("{y:04}-{m:02}-{d:02}"))
}

/// Parses `YYYY`, `YYYY-MM`, `YYYY-MM-DD` (optionally followed by `T…`) to epoch ms at 00:00 UTC.
pub fn parse_date_ms(s: &str) -> Option<i64> {
    let date = s.trim().split('T').next()?;
    let mut parts = date.split('-');
    let y: i64 = parts.next()?.parse().ok()?;
    let m: u32 = parts.next().map_or(Some(1), |p| p.parse().ok())?;
    let d: u32 = parts.next().map_or(Some(1), |p| p.parse().ok())?;
    if parts.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&d) || !(1..=9999).contains(&y) {
        return None;
    }
    Some(days_from_civil(y, m, d) * MS_PER_DAY)
}

// ---------------------------------------------------------------------------------------------
// Availability (mirror of librespot-metadata `audio/item.rs`, which keeps it private)
// ---------------------------------------------------------------------------------------------

/// Whether an item is playable for `country` / `catalogue` (`"premium"` when the attribute is
/// absent) at `now`. `earliest_live` is the track's embargo end (tracks only).
pub fn check_availability(
    availability: &Availabilities,
    restrictions: &Restrictions,
    earliest_live: Option<&Date>,
    country: &str,
    catalogue: &str,
    now: &Date,
) -> Result<(), UnavailabilityReason> {
    if earliest_live.is_some_and(|t| now < t) {
        return Err(UnavailabilityReason::Embargo);
    }
    if !availability.is_empty() && !availability.iter().any(|a| now >= &a.start) {
        return Err(UnavailabilityReason::Embargo);
    }
    for r in restrictions.iter().filter(|r| r.catalogue_strs.iter().any(|c| c == catalogue)) {
        if let Some(allowed) = &r.countries_allowed {
            return if allowed.iter().any(|c| c == country) { Ok(()) } else { Err(UnavailabilityReason::NotWhitelisted) };
        }
        if let Some(forbidden) = &r.countries_forbidden {
            return if forbidden.iter().any(|c| c == country) { Err(UnavailabilityReason::Blacklisted) } else { Ok(()) };
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// librespot metadata → app models
// ---------------------------------------------------------------------------------------------

fn positive(v: i32) -> Option<u32> {
    u32::try_from(v).ok().filter(|v| *v > 0)
}

/// `https://i.scdn.co/image/<hex>` images (server order; entries without an id are dropped).
pub fn images(images: &Images) -> Vec<Image> {
    images
        .iter()
        .filter(|i| i.id.0 != [0u8; 20])
        .map(|i| Image::from_file_id_hex(&hex::encode(i.id.0), positive(i.width), positive(i.height)))
        .collect()
}

/// Picks the cover to keep on disk: the largest one ≤ 640 px wide; otherwise the smallest
/// larger one; otherwise the first.
pub fn pick_cover(images: &Images) -> Option<FileId> {
    let valid: Vec<_> = images.iter().filter(|i| i.id.0 != [0u8; 20]).collect();
    valid
        .iter()
        .filter(|i| (1..=640).contains(&i.width))
        .max_by_key(|i| i.width)
        .or_else(|| valid.iter().filter(|i| i.width > 640).min_by_key(|i| i.width))
        .or_else(|| valid.first())
        .map(|i| i.id)
}

fn uri_string(uri: &SpotifyUri) -> Option<String> {
    uri.to_uri().ok()
}

fn album_type(t: ProtoAlbumType) -> Option<AlbumType> {
    match t {
        ProtoAlbumType::ALBUM => Some(AlbumType::Album),
        ProtoAlbumType::SINGLE => Some(AlbumType::Single),
        ProtoAlbumType::COMPILATION => Some(AlbumType::Compilation),
        ProtoAlbumType::EP => Some(AlbumType::Ep),
        _ => None,
    }
}

fn artist_refs(artists: &librespot_metadata::artist::Artists) -> Vec<ArtistRef> {
    artists
        .iter()
        .filter_map(|a| Some(ArtistRef { uri: uri_string(&a.id)?, name: a.name.clone(), images: Vec::new() }))
        .collect()
}

/// App `Track` JSON for a downloaded track. `uri` is the requested URI; `played` is the track
/// whose audio was downloaded (a relinked alternative, or `track` itself): its duration and
/// explicit flag describe the file.
pub fn track_model(uri: &str, track: &Track, played: &Track) -> models::Track {
    let mut artists = artist_refs(&track.artists);
    if artists.is_empty() {
        artists = track
            .artists_with_role
            .iter()
            .filter_map(|a| Some(ArtistRef { uri: uri_string(&a.id)?, name: a.name.clone(), images: Vec::new() }))
            .collect();
    }
    let album = &track.album;
    let album = uri_string(&album.id).map(|album_uri| AlbumRef {
        uri: album_uri,
        name: album.name.clone(),
        images: images(&album.covers),
        artists: artist_refs(&album.artists),
        release_date: date_string(album.date.as_timestamp_ms()),
        album_type: album_type(album.album_type),
        total_tracks: None,
    });
    models::Track {
        uri: uri.to_owned(),
        name: track.name.clone(),
        artists,
        album,
        duration_ms: u64::try_from(played.duration).unwrap_or(0),
        explicit: played.is_explicit || track.is_explicit,
        playable: true,
        track_number: positive(track.number),
        disc_number: positive(track.disc_number),
        popularity: u32::try_from(track.popularity.clamp(0, 100)).ok(),
        has_lyrics: Some(track.has_lyrics),
    }
}

/// App `Episode` JSON. `msg` is the raw proto (librespot's `Episode` drops the show id/images).
pub fn episode_model(uri: &str, episode: &Episode, msg: &pm::Episode) -> models::Episode {
    let show_msg = msg.show.get_or_default();
    let show = SpotifyId::from_raw(show_msg.gid())
        .ok()
        .and_then(|id| SpotifyUri::Show { id }.to_uri().ok())
        .map(|show_uri| ShowRef {
            uri: show_uri,
            name: if show_msg.name().is_empty() { episode.show_name.clone() } else { show_msg.name().to_owned() },
            publisher: Some(show_msg.publisher().to_owned()).filter(|p| !p.is_empty()),
            images: images(&Images::from(show_msg.cover_image.get_or_default())),
        });
    models::Episode {
        uri: uri.to_owned(),
        name: episode.name.clone(),
        show,
        description: episode.description.clone(),
        duration_ms: u64::try_from(episode.duration).unwrap_or(0),
        release_date: date_string(episode.publish_time.as_timestamp_ms()),
        images: images(&episode.covers),
        explicit: episode.is_explicit,
        playable: true,
        resume_position_ms: None,
        fully_played: None,
    }
}

/// Cover candidates of an episode: its own images, else the show's.
pub fn episode_cover_images(episode: &Episode, msg: &pm::Episode) -> Images {
    if episode.covers.iter().any(|i| i.id.0 != [0u8; 20]) {
        episode.covers.clone()
    } else {
        Images::from(msg.show.get_or_default().cover_image.get_or_default())
    }
}

// ---------------------------------------------------------------------------------------------
// Stored app models → AudioItem (offline hook)
// ---------------------------------------------------------------------------------------------

fn image_size(width: Option<u32>) -> ImageSize {
    match width {
        Some(1..=64) => ImageSize::SMALL,
        Some(65..=300) | None | Some(0) => ImageSize::DEFAULT,
        Some(301..=640) => ImageSize::LARGE,
        Some(_) => ImageSize::XLARGE,
    }
}

/// Covers as the Player reports them: the original https URLs, widest first.
fn covers(images: &[Image]) -> Vec<CoverImage> {
    let mut covers: Vec<CoverImage> = images
        .iter()
        .filter(|i| !i.url.is_empty())
        .map(|i| CoverImage {
            url: i.url.clone(),
            size: image_size(i.width),
            width: i.width.and_then(|w| i32::try_from(w).ok()).unwrap_or(0),
            height: i.height.and_then(|h| i32::try_from(h).ok()).unwrap_or(0),
        })
        .collect();
    covers.sort_by_key(|c| std::cmp::Reverse(c.width));
    covers
}

fn duration_ms(ms: u64) -> u32 {
    u32::try_from(ms).unwrap_or(u32::MAX)
}

fn files(format: AudioFileFormat, file_id: FileId) -> AudioFiles {
    let mut files = AudioFiles::default();
    files.insert(format, file_id);
    files
}

fn artist_uri(uri: &str) -> SpotifyUri {
    SpotifyUri::from_uri(uri).unwrap_or_else(|_| SpotifyUri::Unknown { kind: Cow::Borrowed("artist"), id: uri.to_owned() })
}

/// `AudioItem` for a downloaded track, built only from stored data. Availability is always
/// `Ok` (the download was checked; re-validation is the app's job), so the Player only applies
/// its explicit-content filter.
pub fn track_audio_item(uri: &SpotifyUri, uri_str: &str, t: &models::Track, format: AudioFileFormat, file_id: FileId) -> AudioItem {
    let album = t.album.as_ref();
    AudioItem {
        track_id: uri.clone(),
        uri: uri_str.to_owned(),
        files: files(format, file_id),
        name: t.name.clone(),
        covers: album.map(|a| covers(&a.images)).unwrap_or_default(),
        language: Vec::new(),
        duration_ms: duration_ms(t.duration_ms),
        is_explicit: t.explicit,
        availability: Ok(()),
        alternatives: None,
        unique_fields: UniqueFields::Track {
            artists: ArtistsWithRole(
                t.artists
                    .iter()
                    .map(|a| ArtistWithRole { id: artist_uri(&a.uri), name: a.name.clone(), role: ArtistRole::ARTIST_ROLE_MAIN_ARTIST })
                    .collect(),
            ),
            album: album.map(|a| a.name.clone()).unwrap_or_default(),
            album_artists: album.map(|a| a.artists.iter().map(|x| x.name.clone()).collect()).unwrap_or_default(),
            popularity: u8::try_from(t.popularity.unwrap_or(0).min(100)).unwrap_or(0),
            number: t.track_number.unwrap_or(0),
            disc_number: t.disc_number.unwrap_or(0),
        },
    }
}

/// `AudioItem` for a downloaded episode (see [`track_audio_item`]).
pub fn episode_audio_item(uri: &SpotifyUri, uri_str: &str, e: &models::Episode, format: AudioFileFormat, file_id: FileId) -> AudioItem {
    let cover_source = if e.images.is_empty() { e.show.as_ref().map(|s| s.images.as_slice()).unwrap_or_default() } else { &e.images };
    let publish_ms = e.release_date.as_deref().and_then(parse_date_ms).unwrap_or(0);
    AudioItem {
        track_id: uri.clone(),
        uri: uri_str.to_owned(),
        files: files(format, file_id),
        name: e.name.clone(),
        covers: covers(cover_source),
        language: Vec::new(),
        duration_ms: duration_ms(e.duration_ms),
        is_explicit: e.explicit,
        availability: Ok(()),
        alternatives: None,
        unique_fields: UniqueFields::Episode {
            description: e.description.clone(),
            publish_time: Date::from_timestamp_ms(publish_ms).unwrap_or_else(|_| Date::now_utc()),
            show_name: e.show.as_ref().map(|s| s.name.clone()).unwrap_or_default(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_metadata::availability::Availability;
    use librespot_metadata::image::Image as MetaImage;
    use librespot_metadata::restriction::{Restriction, RestrictionCatalogues, RestrictionType};

    #[test]
    fn civil_dates() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        for (y, m, d) in [(2000, 2, 29), (1999, 12, 31), (2024, 3, 1), (1900, 1, 1), (2100, 2, 28)] {
            assert_eq!(civil_from_days(days_from_civil(y, m, d)), (y, m, d));
        }
        let ms = parse_date_ms("2023-07-14").expect("date");
        assert_eq!(date_string(ms).as_deref(), Some("2023-07-14"));
        assert_eq!(parse_date_ms("2023-07-14T10:00:00Z"), Some(ms));
        assert_eq!(parse_date_ms("2023"), parse_date_ms("2023-01-01"));
        assert_eq!(parse_date_ms("2023-13-01"), None);
        assert_eq!(parse_date_ms("soon"), None);
        assert_eq!(date_string(-5_000_000_000_000), None);
    }

    fn restriction(catalogue: &str, allowed: Option<&[&str]>, forbidden: Option<&[&str]>) -> Restriction {
        let list = |l: &[&str]| l.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>();
        Restriction {
            catalogues: RestrictionCatalogues(Vec::new()),
            restriction_type: RestrictionType::STREAMING,
            catalogue_strs: vec![catalogue.to_owned()],
            countries_allowed: allowed.map(list),
            countries_forbidden: forbidden.map(list),
        }
    }

    #[test]
    fn availability_rules() {
        let now = Date::now_utc();
        let none = Availabilities::default();
        let ok = |r: Vec<Restriction>, country: &str| check_availability(&none, &Restrictions(r), None, country, "premium", &now);
        assert!(ok(vec![], "DE").is_ok());
        assert!(ok(vec![restriction("premium", Some(&["DE", "US"]), None)], "DE").is_ok());
        assert!(matches!(ok(vec![restriction("premium", Some(&["US"]), None)], "DE"), Err(UnavailabilityReason::NotWhitelisted)));
        assert!(matches!(ok(vec![restriction("premium", None, Some(&["DE"]))], "DE"), Err(UnavailabilityReason::Blacklisted)));
        assert!(ok(vec![restriction("free", Some(&["US"]), None)], "DE").is_ok(), "other catalogue ignored");

        let future = Date::from_timestamp_ms(now.as_timestamp_ms() + 86_400_000).expect("date");
        let past = Date::from_timestamp_ms(now.as_timestamp_ms() - 86_400_000).expect("date");
        let embargoed = Availabilities(vec![Availability { catalogue_strs: vec![], start: future }]);
        assert!(matches!(
            check_availability(&embargoed, &Restrictions::default(), None, "DE", "premium", &now),
            Err(UnavailabilityReason::Embargo)
        ));
        let live = Availabilities(vec![Availability { catalogue_strs: vec![], start: past }]);
        assert!(check_availability(&live, &Restrictions::default(), Some(&past), "DE", "premium", &now).is_ok());
        assert!(check_availability(&live, &Restrictions::default(), Some(&future), "DE", "premium", &now).is_err());
    }

    fn meta_image(byte: u8, width: i32) -> MetaImage {
        MetaImage { id: FileId([byte; 20]), size: ImageSize::DEFAULT, width, height: width }
    }

    #[test]
    fn cover_choice_and_urls() {
        let imgs = Images(vec![meta_image(1, 64), meta_image(2, 640), meta_image(3, 300), meta_image(4, 1000)]);
        assert_eq!(pick_cover(&imgs), Some(FileId([2; 20])));
        assert_eq!(pick_cover(&Images(vec![meta_image(4, 1000), meta_image(5, 2000)])), Some(FileId([4; 20])));
        assert_eq!(pick_cover(&Images(vec![meta_image(6, 0)])), Some(FileId([6; 20])));
        assert_eq!(pick_cover(&Images(vec![meta_image(0, 300)])), None);
        let urls = images(&imgs);
        assert_eq!(urls.len(), 4);
        assert_eq!(urls[0].url, format!("https://i.scdn.co/image/{}", "01".repeat(20)));
        assert_eq!(urls[0].width, Some(64));
    }

    #[test]
    fn audio_item_from_stored_track() {
        let uri = SpotifyUri::from_uri("spotify:track:4uLU6hMCjMI75M1A2tKUQC").expect("uri");
        let t = models::Track {
            uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC".into(),
            name: "Song".into(),
            artists: vec![
                ArtistRef { uri: "spotify:artist:0OdUWJ0sBjDrqHygGUXeCF".into(), name: "A".into(), images: vec![] },
                ArtistRef { uri: "bogus".into(), name: "B".into(), images: vec![] },
            ],
            album: Some(AlbumRef {
                uri: "spotify:album:6akEvsycLGftJxYudPjmqK".into(),
                name: "Album".into(),
                images: vec![
                    Image { url: "https://i.scdn.co/image/small".into(), width: Some(64), height: Some(64) },
                    Image { url: "https://i.scdn.co/image/large".into(), width: Some(640), height: Some(640) },
                ],
                artists: vec![ArtistRef { uri: "spotify:artist:0OdUWJ0sBjDrqHygGUXeCF".into(), name: "A".into(), images: vec![] }],
                ..Default::default()
            }),
            duration_ms: 123_456,
            explicit: true,
            playable: true,
            track_number: Some(3),
            disc_number: Some(1),
            popularity: Some(250),
            has_lyrics: None,
        };
        let item = track_audio_item(&uri, &t.uri, &t, AudioFileFormat::OGG_VORBIS_160, FileId([9; 20]));
        assert_eq!(item.track_id, uri);
        assert_eq!(item.uri, t.uri);
        assert_eq!(item.name, "Song");
        assert_eq!(item.duration_ms, 123_456);
        assert!(item.is_explicit);
        assert!(item.availability.is_ok());
        assert_eq!(item.files.get(&AudioFileFormat::OGG_VORBIS_160), Some(&FileId([9; 20])));
        assert_eq!(item.covers.len(), 2);
        assert_eq!(item.covers[0].url, "https://i.scdn.co/image/large");
        assert_eq!(item.covers[0].size, ImageSize::LARGE);
        assert_eq!(item.covers[1].size, ImageSize::SMALL);
        match &item.unique_fields {
            UniqueFields::Track { artists, album, album_artists, popularity, number, disc_number } => {
                assert_eq!(artists.len(), 2);
                assert_eq!(artists[0].id.to_uri().ok().as_deref(), Some("spotify:artist:0OdUWJ0sBjDrqHygGUXeCF"));
                assert_eq!(artists[1].name, "B");
                assert_eq!(album, "Album");
                assert_eq!(album_artists, &vec!["A".to_owned()]);
                assert_eq!((*popularity, *number, *disc_number), (100, 3, 1));
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn audio_item_from_stored_episode() {
        let uri = SpotifyUri::from_uri("spotify:episode:512ojhOuo1ktJprKbVcKyQ").expect("uri");
        let e = models::Episode {
            uri: "spotify:episode:512ojhOuo1ktJprKbVcKyQ".into(),
            name: "Ep".into(),
            show: Some(ShowRef {
                uri: "spotify:show:5CfCWKI5pZ28U0uOzXkDHe".into(),
                name: "Show".into(),
                publisher: None,
                images: vec![Image { url: "https://i.scdn.co/image/show".into(), width: Some(300), height: Some(300) }],
            }),
            description: "Desc".into(),
            duration_ms: 3_600_000,
            release_date: Some("2021-05-04".into()),
            images: vec![],
            explicit: false,
            playable: true,
            resume_position_ms: None,
            fully_played: None,
        };
        let item = episode_audio_item(&uri, &e.uri, &e, AudioFileFormat::MP3_96, FileId([1; 20]));
        assert_eq!(item.covers.len(), 1, "falls back to the show images");
        assert_eq!(item.duration_ms, 3_600_000);
        match &item.unique_fields {
            UniqueFields::Episode { description, publish_time, show_name } => {
                assert_eq!(description, "Desc");
                assert_eq!(show_name, "Show");
                assert_eq!(date_string(publish_time.as_timestamp_ms()).as_deref(), Some("2021-05-04"));
            }
            other => panic!("unexpected {other:?}"),
        }
    }
}
