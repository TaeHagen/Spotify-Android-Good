//! Snapshot enrichment: track/episode metadata for the visible queue window and context names.
//!
//! Lookup order per uri: the catalog cache (`catalog::metadata::cached_*`), our own LRU of
//! fetched items, the offline index (downloads carry their metadata), and finally the partial
//! metadata of the playing `AudioItem` (`PlayerEvent::TrackChanged`). Missing items of the
//! window (current + next 50, the Media3 queue window + prev 10) are fetched in batches with
//! `catalog::metadata::tracks` / `episodes` (debounced, one batch in flight); results are emitted
//! as `queueMetadata` and the snapshot is republished. Uris a successful response omits are not
//! retried for 5 minutes; after a failed or timed out request they are retried after 20 s, or as
//! soon as the session is online again.
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

const WINDOW_NEXT: usize = 50;
const WINDOW_PREV: usize = 10;
const FETCHED_CAPACITY: usize = 512;
const ITEM_CAPACITY: usize = 16;
const CONTEXT_CAPACITY: usize = 64;
const MAX_PENDING: usize = 200;
const BATCH: usize = 100;
const DEBOUNCE: Duration = Duration::from_millis(150);
const FETCH_TIMEOUT: Duration = Duration::from_secs(15);
const RETRY_AFTER: Duration = Duration::from_secs(5 * 60);
/// Backoff after a failed request (network error, timeout).
const RETRY_AFTER_ERROR: Duration = Duration::from_secs(20);
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
    /// Uris not to request again for a while: when, and for how long.
    failed: LruCache<String, (Instant, Duration)>,
    pending: VecDeque<String>,
    in_flight: bool,
    /// Context names: the name (or none), when, and for how long a missing name isn't looked
    /// up again.
    contexts: LruCache<String, (Option<String>, Instant, Duration)>,
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
            if st.failed.peek(&u).is_some_and(|(at, backoff)| at.elapsed() < *backoff) {
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

/// The result of one kind of a batch: the returned items, or `Err` if the request failed.
type Fetched<T> = Result<Vec<T>, ()>;

async fn fetch_batch(session: &Session, batch: &[String]) -> (Fetched<Track>, Fetched<Episode>) {
    let tracks: Vec<String> = batch.iter().filter(|u| uri::is_track(u)).cloned().collect();
    let episodes: Vec<String> = batch.iter().filter(|u| uri::is_episode(u)).cloned().collect();
    let mut got_tracks = Ok(Vec::new());
    let mut got_episodes = Ok(Vec::new());
    if !tracks.is_empty() {
        got_tracks = match tokio::time::timeout(FETCH_TIMEOUT, catalog::metadata::tracks(session, &tracks)).await {
            Ok(Ok(v)) => Ok(v),
            Ok(Err(e)) => {
                log::debug!("track metadata failed: {e}");
                Err(())
            }
            Err(_) => {
                log::debug!("track metadata timed out");
                Err(())
            }
        };
    }
    if !episodes.is_empty() {
        got_episodes = match tokio::time::timeout(FETCH_TIMEOUT, catalog::metadata::episodes(session, &episodes)).await {
            Ok(Ok(v)) => Ok(v),
            Ok(Err(e)) => {
                log::debug!("episode metadata failed: {e}");
                Err(())
            }
            Err(_) => {
                log::debug!("episode metadata timed out");
                Err(())
            }
        };
    }
    (got_tracks, got_episodes)
}

/// How long not to request `u` again after a batch: omitted from a successful response (the
/// catalog doesn't know it) for long, after a failed request briefly.
fn backoff_for(u: &str, tracks_ok: bool, episodes_ok: bool) -> Duration {
    let ok = if uri::is_episode(u) { episodes_ok } else { tracks_ok };
    if ok {
        RETRY_AFTER
    } else {
        RETRY_AFTER_ERROR
    }
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
        let (tracks_ok, episodes_ok) = (tracks.is_ok(), episodes.is_ok());
        let (tracks, episodes) = (tracks.unwrap_or_default(), episodes.unwrap_or_default());
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
            let now = Instant::now();
            for u in batch.iter().filter(|u| !found.contains(u.as_str())) {
                st.failed.put(u.clone(), (now, backoff_for(u, tracks_ok, episodes_ok)));
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
            Some((Some(name), ..)) => {
                ctx.name = Some(name.clone());
                return;
            }
            Some((None, at, backoff)) if at.elapsed() < *backoff => return,
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
        // The downloads only know album / artist / show names: a miss is looked up again soon
        // (and as soon as the session is online).
        let name = offline_context_name(&ctx.uri);
        let backoff = context_backoff(&ContextLookup::Offline);
        META.lock().contexts.put(ctx.uri.clone(), (name.clone(), Instant::now(), backoff));
        ctx.name = name;
        return;
    }
    let u = ctx.uri.clone();
    let Ok(handle) = tokio::runtime::Handle::try_current() else {
        META.lock().contexts_in_flight.remove(&u);
        return;
    };
    handle.spawn(async move {
        let lookup = match engine::try_session() {
            Some(session) => match tokio::time::timeout(CONTEXT_TIMEOUT, resolve_context_name(&session, &u)).await {
                Ok(Ok(name)) => ContextLookup::Answered(name),
                Ok(Err(e)) => {
                    log::debug!("context name for {u} failed: {e}");
                    ContextLookup::Failed
                }
                Err(_) => ContextLookup::Failed,
            },
            None => ContextLookup::Failed,
        };
        let backoff = context_backoff(&lookup);
        let name = match lookup {
            ContextLookup::Answered(name) => name,
            _ => None,
        };
        let found = name.is_some();
        {
            let mut st = META.lock();
            st.contexts_in_flight.remove(&u);
            st.contexts.put(u, (name, Instant::now(), backoff));
        }
        if found {
            hub::publish();
        }
    });
}

/// How a context name lookup went.
enum ContextLookup {
    /// The catalog answered (with or without a name).
    Answered(Option<String>),
    /// The request failed or timed out, or there was no session.
    Failed,
    /// Looked up in the downloads only.
    Offline,
}

/// How long a missing context name isn't looked up again: long only when the catalog said there
/// is none.
fn context_backoff(lookup: &ContextLookup) -> Duration {
    match lookup {
        ContextLookup::Answered(None) => RETRY_AFTER,
        _ => RETRY_AFTER_ERROR,
    }
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

/// The session is online (again): items and context names whose lookup failed meanwhile (or
/// that only the downloads were asked for) are fetched right away.
pub(crate) fn on_online() {
    let mut st = META.lock();
    let transient: Vec<String> =
        st.failed.iter().filter(|(_, (_, backoff))| *backoff < RETRY_AFTER).map(|(u, _)| u.clone()).collect();
    for u in transient {
        st.failed.pop(&u);
    }
    let unnamed: Vec<String> = st
        .contexts
        .iter()
        .filter(|(_, (name, _, backoff))| name.is_none() && *backoff < RETRY_AFTER)
        .map(|(u, _)| u.clone())
        .collect();
    for u in unnamed {
        st.contexts.pop(&u);
    }
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
    fn failed_requests_back_off_briefly() {
        // a successful response that omits a uri: the catalog doesn't know it
        assert_eq!(backoff_for("spotify:track:x", true, false), RETRY_AFTER);
        assert_eq!(backoff_for("spotify:episode:x", false, true), RETRY_AFTER);
        // the request failed (offline, timeout): retried soon
        assert_eq!(backoff_for("spotify:track:x", false, true), RETRY_AFTER_ERROR);
        assert_eq!(backoff_for("spotify:episode:x", true, false), RETRY_AFTER_ERROR);
        assert!(RETRY_AFTER_ERROR < RETRY_AFTER);
    }

    #[test]
    fn missing_context_names_back_off_by_cause() {
        // the catalog has no name: not asked again for a while
        assert_eq!(context_backoff(&ContextLookup::Answered(None)), RETRY_AFTER);
        // a failed or timed out request, or offline: looked up again soon
        assert_eq!(context_backoff(&ContextLookup::Failed), RETRY_AFTER_ERROR);
        assert_eq!(context_backoff(&ContextLookup::Offline), RETRY_AFTER_ERROR);
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
