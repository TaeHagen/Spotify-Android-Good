//! Snapshot enrichment: track/episode metadata for the visible queue window and context names.
//!
//! Lookup order per uri: the catalog cache (`catalog::metadata::cached_*`), our own LRU of
//! fetched items, the offline index (downloads carry their metadata), and finally the partial
//! metadata of the playing `AudioItem` (`PlayerEvent::TrackChanged`). Missing items of the
//! window (current + next 30 + prev 10) are fetched in batches with `catalog::metadata::tracks` /
//! `episodes` (debounced, one batch in flight); results are emitted as `queueMetadata` and the
//! snapshot is republished. Uris the catalog does not return are not retried for a while.
//! Context names come from the context metadata, a built-in table, or librespot-metadata
//! (album / artist / show / playlist), cached in a small LRU.

use super::{hub, uri};
use crate::models::{AlbumRef, ArtistRef, Episode, Image, PlaybackContext, PlaybackSnapshot, PlaybackTrack, ShowRef, Track};
use crate::{catalog, engine, events, offline};
use librespot_core::{Session, SpotifyUri};
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_metadata::Metadata;
use lru::LruCache;
use parking_lot::Mutex;
use serde::Serialize;
use std::collections::{HashSet, VecDeque};
use std::num::NonZeroUsize;
use std::sync::LazyLock;
use std::time::{Duration, Instant};

const WINDOW_NEXT: usize = 30;
const WINDOW_PREV: usize = 10;
const FETCHED_CAPACITY: usize = 512;
const ITEM_CAPACITY: usize = 16;
const CONTEXT_CAPACITY: usize = 64;
const MAX_PENDING: usize = 200;
const BATCH: usize = 100;
const DEBOUNCE: Duration = Duration::from_millis(150);
const FETCH_TIMEOUT: Duration = Duration::from_secs(15);
const RETRY_AFTER: Duration = Duration::from_secs(5 * 60);
const CONTEXT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone)]
pub(crate) enum Entry {
    Track(Track),
    Episode(Episode),
}

/// Partial metadata of the playing item (from the Player's `AudioItem`).
#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct ItemInfo {
    pub name: String,
    pub artists: Vec<ArtistRef>,
    pub album_name: Option<String>,
    pub images: Vec<Image>,
    pub duration_ms: u64,
    pub explicit: bool,
    pub is_episode: bool,
    pub show_name: Option<String>,
}

struct MetaState {
    fetched: LruCache<String, Entry>,
    items: LruCache<String, ItemInfo>,
    failed: LruCache<String, Instant>,
    pending: VecDeque<String>,
    in_flight: bool,
    contexts: LruCache<String, (Option<String>, Instant)>,
    contexts_in_flight: HashSet<String>,
}

fn cap(n: usize) -> NonZeroUsize {
    NonZeroUsize::new(n).unwrap_or(NonZeroUsize::MIN)
}

static META: LazyLock<Mutex<MetaState>> = LazyLock::new(|| {
    Mutex::new(MetaState {
        fetched: LruCache::new(cap(FETCHED_CAPACITY)),
        items: LruCache::new(cap(ITEM_CAPACITY)),
        failed: LruCache::new(cap(FETCHED_CAPACITY)),
        pending: VecDeque::new(),
        in_flight: false,
        contexts: LruCache::new(cap(CONTEXT_CAPACITY)),
        contexts_in_flight: HashSet::new(),
    })
});

#[derive(Serialize)]
struct QueueMetadata<'a> {
    tracks: &'a [Track],
    episodes: &'a [Episode],
}

fn fetchable(u: &str) -> bool {
    uri::is_track(u) || uri::is_episode(u)
}

/// Full metadata for `uri`, if known locally.
pub(crate) fn lookup(u: &str) -> Option<Entry> {
    if uri::is_track(u) {
        if let Some(t) = catalog::metadata::cached_track(u) {
            return Some(Entry::Track(t));
        }
    } else if uri::is_episode(u) {
        if let Some(e) = catalog::metadata::cached_episode(u) {
            return Some(Entry::Episode(e));
        }
    } else {
        return None;
    }
    if let Some(e) = META.lock().fetched.get(u).cloned() {
        return Some(e);
    }
    let record = offline::downloaded_record(u)?;
    record.track.map(Entry::Track).or(record.episode.map(Entry::Episode))
}

pub(crate) fn info_from_audio_item(item: &AudioItem) -> ItemInfo {
    let images = item
        .covers
        .iter()
        .map(|c| Image {
            url: c.url.clone(),
            width: (c.width > 0).then_some(c.width as u32),
            height: (c.height > 0).then_some(c.height as u32),
        })
        .collect();
    let mut info = ItemInfo {
        name: item.name.clone(),
        images,
        duration_ms: item.duration_ms as u64,
        explicit: item.is_explicit,
        ..Default::default()
    };
    match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => {
            info.artists = artists
                .0
                .iter()
                .map(|a| ArtistRef { uri: a.id.to_uri().unwrap_or_default(), name: a.name.clone(), images: Vec::new() })
                .collect();
            info.album_name = Some(album.clone()).filter(|a| !a.is_empty());
        }
        UniqueFields::Episode { show_name, .. } => {
            info.is_episode = true;
            info.show_name = Some(show_name.clone()).filter(|s| !s.is_empty());
        }
        UniqueFields::Local { artists, album, .. } => {
            info.artists = artists.iter().map(|a| ArtistRef { uri: String::new(), name: a.clone(), images: Vec::new() }).collect();
            info.album_name = album.clone();
        }
    }
    info
}

/// Records the playing item's metadata (`PlayerEvent::TrackChanged`).
pub(crate) fn on_track_changed(item: &AudioItem) {
    let Ok(u) = item.track_id.to_uri() else { return };
    let info = info_from_audio_item(item);
    let changed = {
        let mut st = META.lock();
        let changed = st.items.peek(&u) != Some(&info);
        st.items.put(u, info);
        changed
    };
    if changed {
        hub::publish();
    }
}

pub(crate) fn apply_entry(t: &mut PlaybackTrack, entry: &Entry) {
    match entry {
        Entry::Track(track) => {
            t.name = Some(track.name.clone());
            t.artists = track.artists.clone();
            t.album = track.album.as_ref().map(|a| AlbumRef { artists: Vec::new(), ..a.clone() });
            t.duration_ms = (track.duration_ms > 0).then_some(track.duration_ms);
            t.explicit = track.explicit;
            t.is_episode = false;
            t.show = None;
        }
        Entry::Episode(ep) => {
            t.name = Some(ep.name.clone());
            t.artists = Vec::new();
            t.album = None;
            let mut show = ep.show.clone().unwrap_or_default();
            if show.images.is_empty() {
                show.images = ep.images.clone();
            }
            t.show = Some(show);
            t.duration_ms = (ep.duration_ms > 0).then_some(ep.duration_ms);
            t.explicit = ep.explicit;
            t.is_episode = true;
        }
    }
}

pub(crate) fn apply_info(t: &mut PlaybackTrack, info: &ItemInfo) {
    t.name = Some(info.name.clone()).filter(|n| !n.is_empty()).or(t.name.take());
    t.artists = info.artists.clone();
    t.duration_ms = (info.duration_ms > 0).then_some(info.duration_ms);
    t.explicit = info.explicit;
    t.is_episode = info.is_episode;
    if info.is_episode {
        t.show = Some(ShowRef {
            uri: String::new(),
            name: info.show_name.clone().unwrap_or_default(),
            publisher: None,
            images: info.images.clone(),
        });
    } else {
        t.album = Some(AlbumRef {
            uri: String::new(),
            name: info.album_name.clone().unwrap_or_default(),
            images: info.images.clone(),
            ..Default::default()
        });
    }
}

/// Fills one track; returns true if it still lacks full metadata.
fn fill(t: &mut PlaybackTrack) -> bool {
    if let Some(entry) = lookup(&t.uri) {
        apply_entry(t, &entry);
        return false;
    }
    if !fetchable(&t.uri) {
        return false;
    }
    if let Some(info) = META.lock().items.get(&t.uri).cloned() {
        apply_info(t, &info);
    }
    true
}

/// Enriches the snapshot in place and schedules fetches for what is missing.
pub(crate) fn enrich(snap: &mut PlaybackSnapshot) {
    let mut missing = Vec::new();
    if let Some(t) = snap.track.as_mut() {
        if fill(t) {
            missing.push(t.uri.clone());
        }
    }
    for t in snap.next_tracks.iter_mut().take(WINDOW_NEXT) {
        if fill(t) {
            missing.push(t.uri.clone());
        }
    }
    let prev_len = snap.prev_tracks.len();
    for t in snap.prev_tracks.iter_mut().skip(prev_len.saturating_sub(WINDOW_PREV)) {
        if fill(t) {
            missing.push(t.uri.clone());
        }
    }
    if let Some(ctx) = snap.context.as_mut() {
        fill_context(ctx);
    }
    if !missing.is_empty() {
        request(missing);
    }
}

fn request(uris: Vec<String>) {
    let Some(_session) = engine::try_session() else { return };
    let spawn = {
        let mut st = META.lock();
        for u in uris {
            if st.pending.len() >= MAX_PENDING {
                break;
            }
            if st.pending.contains(&u) || st.fetched.contains(&u) {
                continue;
            }
            if st.failed.peek(&u).is_some_and(|at| at.elapsed() < RETRY_AFTER) {
                continue;
            }
            st.pending.push_back(u);
        }
        if st.in_flight || st.pending.is_empty() {
            false
        } else {
            st.in_flight = true;
            true
        }
    };
    if spawn {
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            handle.spawn(fetch_loop());
        } else {
            META.lock().in_flight = false;
        }
    }
}

async fn fetch_batch(session: &Session, batch: &[String]) -> (Vec<Track>, Vec<Episode>) {
    let tracks: Vec<String> = batch.iter().filter(|u| uri::is_track(u)).cloned().collect();
    let episodes: Vec<String> = batch.iter().filter(|u| uri::is_episode(u)).cloned().collect();
    let mut got_tracks = Vec::new();
    let mut got_episodes = Vec::new();
    if !tracks.is_empty() {
        match tokio::time::timeout(FETCH_TIMEOUT, catalog::metadata::tracks(session, &tracks)).await {
            Ok(Ok(v)) => got_tracks = v,
            Ok(Err(e)) => log::debug!("track metadata failed: {e}"),
            Err(_) => log::debug!("track metadata timed out"),
        }
    }
    if !episodes.is_empty() {
        match tokio::time::timeout(FETCH_TIMEOUT, catalog::metadata::episodes(session, &episodes)).await {
            Ok(Ok(v)) => got_episodes = v,
            Ok(Err(e)) => log::debug!("episode metadata failed: {e}"),
            Err(_) => log::debug!("episode metadata timed out"),
        }
    }
    (got_tracks, got_episodes)
}

async fn fetch_loop() {
    tokio::time::sleep(DEBOUNCE).await;
    loop {
        let batch: Vec<String> = {
            let mut st = META.lock();
            let n = st.pending.len().min(BATCH);
            let batch: Vec<String> = st.pending.drain(..n).collect();
            if batch.is_empty() {
                st.in_flight = false;
                return;
            }
            batch
        };
        let Some(session) = engine::try_session() else {
            let mut st = META.lock();
            st.pending.clear();
            st.in_flight = false;
            return;
        };
        let (tracks, episodes) = fetch_batch(&session, &batch).await;
        drop(session);
        {
            let mut st = META.lock();
            let mut found: HashSet<&str> = HashSet::new();
            for t in &tracks {
                found.insert(t.uri.as_str());
                st.fetched.put(t.uri.clone(), Entry::Track(t.clone()));
            }
            for e in &episodes {
                found.insert(e.uri.as_str());
                st.fetched.put(e.uri.clone(), Entry::Episode(e.clone()));
            }
            for u in batch.iter().filter(|u| !found.contains(u.as_str())) {
                st.failed.put(u.clone(), Instant::now());
            }
        }
        if !tracks.is_empty() || !episodes.is_empty() {
            events::emit(events::QUEUE_METADATA, &QueueMetadata { tracks: &tracks, episodes: &episodes });
            hub::publish();
        }
    }
}

/// Name of a context from the downloads (offline mode).
fn offline_context_name(u: &str) -> Option<String> {
    let kind = uri::context_type(u);
    offline::all_records().into_iter().find_map(|r| match kind {
        "album" => r.track.as_ref().and_then(|t| t.album.as_ref()).filter(|a| a.uri == u).map(|a| a.name.clone()),
        "artist" => r.track.as_ref().and_then(|t| t.artists.iter().find(|a| a.uri == u)).map(|a| a.name.clone()),
        "show" => r.episode.as_ref().and_then(|e| e.show.as_ref()).filter(|s| s.uri == u).map(|s| s.name.clone()),
        _ => None,
    })
}

fn fill_context(ctx: &mut PlaybackContext) {
    if ctx.name.is_some() {
        return;
    }
    if let Some(name) = uri::builtin_context_name(&ctx.uri) {
        ctx.name = Some(name);
        return;
    }
    if !matches!(ctx.kind.as_str(), "album" | "artist" | "show" | "playlist") {
        return;
    }
    let online = engine::try_session().is_some();
    {
        let mut st = META.lock();
        match st.contexts.get(&ctx.uri) {
            Some((Some(name), _)) => {
                ctx.name = Some(name.clone());
                return;
            }
            Some((None, at)) if at.elapsed() < RETRY_AFTER => return,
            _ => {}
        }
        if st.contexts_in_flight.contains(&ctx.uri) {
            return;
        }
        if online {
            st.contexts_in_flight.insert(ctx.uri.clone());
        }
    }
    if !online {
        let name = offline_context_name(&ctx.uri);
        META.lock().contexts.put(ctx.uri.clone(), (name.clone(), Instant::now()));
        ctx.name = name;
        return;
    }
    let u = ctx.uri.clone();
    let Ok(handle) = tokio::runtime::Handle::try_current() else {
        META.lock().contexts_in_flight.remove(&u);
        return;
    };
    handle.spawn(async move {
        let name = match engine::try_session() {
            Some(session) => match tokio::time::timeout(CONTEXT_TIMEOUT, resolve_context_name(&session, &u)).await {
                Ok(Ok(name)) => name,
                Ok(Err(e)) => {
                    log::debug!("context name for {u} failed: {e}");
                    None
                }
                Err(_) => None,
            },
            None => None,
        };
        let found = name.is_some();
        {
            let mut st = META.lock();
            st.contexts_in_flight.remove(&u);
            st.contexts.put(u, (name, Instant::now()));
        }
        if found {
            hub::publish();
        }
    });
}

async fn playlist_name(session: &Session, id: &SpotifyUri) -> Result<Option<String>, librespot_core::Error> {
    use protobuf::Message;
    let base62 = id.to_id()?;
    let endpoint = format!("/playlist/v2/playlist/{base62}?from=0&length=1");
    let bytes = session.spclient().request(&http::Method::GET, &endpoint, None, None).await?;
    let list = librespot_protocol::playlist4_external::SelectedListContent::parse_from_bytes(&bytes)?;
    Ok(list.attributes.as_ref().map(|a| a.name().to_string()).filter(|n| !n.is_empty()))
}

async fn resolve_context_name(session: &Session, u: &str) -> Result<Option<String>, librespot_core::Error> {
    let id = SpotifyUri::from_uri(u)?;
    let name = match &id {
        SpotifyUri::Album { .. } => Some(librespot_metadata::Album::get(session, &id).await?.name),
        SpotifyUri::Artist { .. } => Some(librespot_metadata::Artist::get(session, &id).await?.name),
        SpotifyUri::Show { .. } => Some(librespot_metadata::Show::get(session, &id).await?.name),
        SpotifyUri::Playlist { .. } => playlist_name(session, &id).await?,
        _ => None,
    };
    Ok(name.filter(|n| !n.is_empty()))
}

/// Forgets everything (logout).
pub(crate) fn clear() {
    let mut st = META.lock();
    st.fetched.clear();
    st.items.clear();
    st.failed.clear();
    st.pending.clear();
    st.contexts.clear();
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::TrackProvider;

    fn track() -> Track {
        Track {
            uri: "spotify:track:t".into(),
            name: "Song".into(),
            artists: vec![ArtistRef { uri: "spotify:artist:a".into(), name: "Artist".into(), images: vec![] }],
            album: Some(AlbumRef {
                uri: "spotify:album:b".into(),
                name: "Album".into(),
                images: vec![Image { url: "https://i.scdn.co/image/x".into(), width: Some(300), height: Some(300) }],
                artists: vec![ArtistRef::default()],
                ..Default::default()
            }),
            duration_ms: 1000,
            explicit: true,
            ..Default::default()
        }
    }

    #[test]
    fn apply_track_entry() {
        let mut t = PlaybackTrack { uri: "spotify:track:t".into(), uid: "u".into(), provider: TrackProvider::Queue, ..Default::default() };
        apply_entry(&mut t, &Entry::Track(track()));
        assert_eq!(t.name.as_deref(), Some("Song"));
        assert_eq!(t.album.as_ref().map(|a| a.uri.as_str()), Some("spotify:album:b"));
        assert!(t.album.as_ref().is_some_and(|a| a.artists.is_empty()), "album artists stripped");
        assert_eq!(t.duration_ms, Some(1000));
        assert!(t.explicit && !t.is_episode);
        assert_eq!(t.provider, TrackProvider::Queue, "queue fields untouched");
    }

    #[test]
    fn apply_episode_entry_uses_episode_images_for_show() {
        let ep = Episode {
            uri: "spotify:episode:e".into(),
            name: "Ep".into(),
            show: Some(ShowRef { uri: "spotify:show:s".into(), name: "Show".into(), publisher: None, images: vec![] }),
            images: vec![Image { url: "https://i.scdn.co/image/e".into(), width: None, height: None }],
            duration_ms: 5,
            ..Default::default()
        };
        let mut t = PlaybackTrack { uri: ep.uri.clone(), ..Default::default() };
        apply_entry(&mut t, &Entry::Episode(ep));
        assert!(t.is_episode);
        assert_eq!(t.show.as_ref().map(|s| s.images.len()), Some(1));
        assert!(t.album.is_none());
    }

    #[test]
    fn apply_partial_info() {
        let info = ItemInfo {
            name: "Live".into(),
            album_name: Some("Alb".into()),
            images: vec![Image { url: "u".into(), width: None, height: None }],
            duration_ms: 3,
            ..Default::default()
        };
        let mut t = PlaybackTrack { uri: "spotify:track:x".into(), ..Default::default() };
        apply_info(&mut t, &info);
        assert_eq!(t.name.as_deref(), Some("Live"));
        assert_eq!(t.album.as_ref().map(|a| a.name.as_str()), Some("Alb"));
        assert_eq!(t.duration_ms, Some(3));
    }
}
