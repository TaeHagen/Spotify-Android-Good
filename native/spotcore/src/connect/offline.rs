//! OfflineController driver (docs/ARCHITECTURE.md §4.6): applies the [`OfflineQueue`] to the
//! shared Player while the engine has no online Spirc. Downloaded tracks only; the Player plays
//! them through the vendored offline hook without any network access.

use super::args::LoadArgs;
use super::offline_queue::{select_downloaded, Action, Elsewhere, Event, Handover, LoadSpec, OfflineQueue};
use super::{hub, now_ms, player_events, uri, Ctl};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{ActiveDeviceRef, OfflineTrackRecord, PlaybackSnapshot, RepeatMode};
use crate::{engine, events, offline as downloads};
use librespot_core::SpotifyUri;
use librespot_playback::player::PlayerEvent;
use librespot_protocol::connect::Cluster;
use parking_lot::Mutex;
use std::sync::LazyLock;

static QUEUE: LazyLock<Mutex<OfflineQueue>> = LazyLock::new(|| Mutex::new(OfflineQueue::default()));

pub(crate) fn is_active() -> bool {
    QUEUE.lock().active
}

/// What a cluster says about the other devices (for the takeover rule).
fn elsewhere(cluster: &Cluster, me: &str) -> Elsewhere {
    let device = Some(cluster.active_device_id.as_str()).filter(|id| !id.is_empty() && *id != me).map(str::to_string);
    let playing = device.is_some() && cluster.player_state.as_ref().is_some_and(|p| p.is_playing && !p.is_paused);
    Elsewhere { device, playing }
}

/// A cluster of the attached Spirc arrived: recorded for the takeover rule (see
/// [`OfflineQueue::taken_over`]), then a paused or finished queue may give way.
pub(crate) fn on_cluster(cluster: &Cluster) {
    let now = elsewhere(cluster, &hub::me());
    {
        let mut q = QUEUE.lock();
        if !q.active {
            return;
        }
        q.observe(now);
    }
    yield_to_active_device();
}

/// Online, and another device took the session over from the paused or finished queue: it
/// became active, or started playing, after the queue paused here (or first saw a cluster). A
/// device that only sits paused as the account's active one doesn't take over.
pub(crate) fn yields() -> bool {
    engine::is_online() && QUEUE.lock().gives_way()
}

/// A queue that [`yields`] is stopped (frees the Player), so commands, the snapshot and
/// transfers follow that device. A playing one keeps playing until something takes over.
pub(crate) fn yield_to_active_device() {
    if yields() {
        log::info!("another device took over, ending the paused offline queue");
        stop();
    }
}

/// The queue's snapshot while it owns the session (active and not given way, see [`yields`]).
pub(crate) fn snapshot(device: ActiveDeviceRef, volume: u16) -> Option<PlaybackSnapshot> {
    let online = engine::is_online();
    let q = QUEUE.lock();
    (q.active && !(online && q.gives_way())).then(|| q.snapshot(device, volume))
}

/// The current and up to `max_next` next items, the current position, repeat mode and whether it
/// plays, while active.
pub(crate) fn handover(max_next: usize) -> Option<Handover> {
    let q = QUEUE.lock();
    q.active.then(|| q.handover(now_ms(), max_next))
}

/// Another controller (Spirc) took the Player over: forget the queue without touching the Player.
pub(crate) fn deactivate() {
    let mut q = QUEUE.lock();
    if q.active {
        q.reset();
    }
}

/// Stops offline playback (the Player is stopped too).
pub(crate) fn stop() {
    let was_active = {
        let mut q = QUEUE.lock();
        let was = q.active;
        q.reset();
        was
    };
    if was_active {
        if let Some(player) = engine::player_host::player() {
            player.stop();
        }
        hub::publish();
    }
}

fn apply(action: Action) -> AppResult<()> {
    let player = engine::player_host::player().ok_or_else(|| AppError::unavailable("The player isn't running"))?;
    match action {
        Action::Load { uri, play, position_ms } => {
            let id = SpotifyUri::from_uri(&uri).map_err(|_| AppError::invalid(format!("bad uri {uri}")))?;
            player.load(id, play, position_ms);
        }
        Action::Preload(uri) => {
            if let Ok(id) = SpotifyUri::from_uri(&uri) {
                player.preload(id);
            }
        }
        Action::Play => player.play(),
        Action::Pause => player.pause(),
        Action::Seek(ms) => player.seek(ms),
        Action::Stop => player.stop(),
    }
    Ok(())
}

fn album_uri(r: &OfflineTrackRecord) -> Option<&str> {
    r.track.as_ref()?.album.as_ref().map(|a| a.uri.as_str())
}

/// Disc and track number (missing numbers sort last).
fn track_position(r: &OfflineTrackRecord) -> (u32, u32) {
    let t = r.track.as_ref();
    (t.and_then(|t| t.disc_number).unwrap_or(1), t.and_then(|t| t.track_number).unwrap_or(u32::MAX))
}

fn album_release_date(r: &OfflineTrackRecord) -> Option<&str> {
    r.track.as_ref()?.album.as_ref()?.release_date.as_deref()
}

fn episode_release_date(r: &OfflineTrackRecord) -> Option<&str> {
    r.episode.as_ref()?.release_date.as_deref()
}

/// Downloaded members of a context, in context order: albums by disc and track number, artists
/// by album (newest first) and track, shows newest episode first. Only album, artist and show
/// membership is known from the records; for other contexts (playlists, Liked Songs, stations)
/// the result is empty, the caller has to send the downloaded items as `trackUris`.
fn context_members(context_uri: &str, records: &[OfflineTrackRecord]) -> Vec<String> {
    let kind = uri::context_type(context_uri);
    let member = |r: &OfflineTrackRecord| match kind {
        "album" => album_uri(r) == Some(context_uri),
        "artist" => r.track.as_ref().is_some_and(|t| {
            t.artists.iter().any(|a| a.uri == context_uri)
                || t.album.as_ref().is_some_and(|al| al.artists.iter().any(|a| a.uri == context_uri))
        }),
        "show" => r.episode.as_ref().and_then(|e| e.show.as_ref()).is_some_and(|s| s.uri == context_uri),
        _ => false,
    };
    let mut members: Vec<&OfflineTrackRecord> = records.iter().filter(|r| member(r)).collect();
    match kind {
        "album" => members.sort_by(|a, b| track_position(a).cmp(&track_position(b)).then_with(|| a.uri.cmp(&b.uri))),
        "artist" => members.sort_by(|a, b| {
            album_release_date(b)
                .cmp(&album_release_date(a))
                .then_with(|| album_uri(a).cmp(&album_uri(b)))
                .then_with(|| track_position(a).cmp(&track_position(b)))
                .then_with(|| a.uri.cmp(&b.uri))
        }),
        "show" => members
            .sort_by(|a, b| episode_release_date(b).cmp(&episode_release_date(a)).then_with(|| a.uri.cmp(&b.uri))),
        _ => {}
    }
    members.into_iter().map(|r| r.uri.clone()).collect()
}

/// The downloaded items `args` asks for and where they start (see [`select_downloaded`]; a
/// start uid minted by the offline queue counts as the requested item).
pub(crate) fn resolve(args: &LoadArgs) -> super::offline_queue::Selection {
    use super::offline_queue::Selection;
    let uid_index = args.start_uid.as_deref().and_then(|u| u.strip_prefix('o')).and_then(|n| n.parse::<usize>().ok());
    let mut selection = match &args.track_uris {
        Some(tracks) if !tracks.is_empty() => select_downloaded(
            tracks,
            downloads::is_downloaded,
            args.start_index.map(|i| i as usize),
            args.start_uri.as_deref(),
        ),
        _ => {
            let none = Selection { items: Vec::new(), start: None, exact: false };
            let Some(ctx) = args.context_uri.as_deref() else { return none };
            if uri::is_track(ctx) || uri::is_episode(ctx) {
                return if downloads::is_downloaded(ctx) {
                    Selection { items: vec![ctx.to_string()], start: Some(0), exact: true }
                } else {
                    none
                };
            }
            let members = context_members(ctx, &downloads::all_records());
            // `startIndex` refers to the caller's whole context, not to the downloaded members.
            select_downloaded(&members, |_| true, None, args.start_uri.as_deref())
        }
    };
    if let Some(i) = uid_index.filter(|&i| i < selection.items.len()) {
        selection.start = Some(i);
        selection.exact = true;
    }
    selection
}

pub(crate) fn has_downloaded(args: &LoadArgs) -> bool {
    !resolve(args).items.is_empty()
}

pub(crate) async fn load(args: &LoadArgs) -> AppResult<()> {
    let selection = resolve(args);
    let (uris, start) = (selection.items, selection.start);
    if uris.is_empty() {
        return Err(AppError::unavailable("Not available offline"));
    }
    // The requested position belongs to the requested item: another start item starts at 0
    // (the requested track isn't downloaded, an artist context, …).
    let position_ms = if selection.exact { args.position_ms } else { 0 };
    // Offline playback started during an outage replaces the reconnect restore point (callers
    // through `connect::load` already dropped it).
    super::restore::clear();
    engine::player_host::ensure_player_for_offline().await?;
    let spec = LoadSpec {
        context_uri: args.context_uri.clone(),
        uris,
        start,
        position_ms,
        shuffle: args.shuffle.unwrap_or(false) || args.smart_shuffle.unwrap_or(false),
        repeat: args.repeat.unwrap_or(RepeatMode::Off),
        play: args.play,
        seed: rand::random(),
    };
    let action = QUEUE.lock().load(spec, now_ms());
    if let Some(action) = action {
        apply(action)?;
    }
    hub::publish();
    Ok(())
}

fn not_found(what: &str) -> AppError {
    AppError::not_found(format!("{what} is not in the queue"))
}

/// Handles a playback / queue command while the offline queue owns the Player.
pub(crate) fn control(cmd: &Ctl) -> AppResult<()> {
    let now = now_ms();
    let action = {
        let mut q = QUEUE.lock();
        if !q.active {
            return Err(AppError::not_connected());
        }
        match cmd {
            Ctl::Play => q.play(now),
            Ctl::Pause => q.pause(now),
            Ctl::Toggle => q.toggle(now),
            Ctl::Next => q.next(now),
            Ctl::Prev => q.prev(now),
            Ctl::Seek(ms) => q.seek(*ms, now),
            Ctl::Shuffle(enabled) => {
                q.set_shuffle(*enabled, rand::random());
                None
            }
            Ctl::SmartShuffle(true) => return Err(AppError::unavailable("Smart shuffle needs a connection")),
            Ctl::SmartShuffle(false) => None,
            Ctl::Repeat(mode) => {
                q.set_repeat(*mode);
                None
            }
            Ctl::QueueAdd(u) => {
                if !downloads::is_downloaded(u) {
                    return Err(AppError::unavailable("Not available offline"));
                }
                if !q.add_to_queue(u.clone()) {
                    return Err(AppError::unavailable("The queue is full"));
                }
                None
            }
            Ctl::QueueRemove(uid) => {
                if !q.remove(uid) {
                    return Err(not_found(uid));
                }
                None
            }
            Ctl::QueueMove(uid, to) => {
                q.move_item(uid, *to).map_err(|m| {
                    if m == "no such entry" { not_found(uid) } else { AppError::new(ErrorCode::Unavailable, m) }
                })?;
                None
            }
            Ctl::QueueClear => {
                q.clear_queue();
                None
            }
            Ctl::SkipTo(uid) => Some(q.skip_to(uid, now).ok_or_else(|| not_found(uid))?),
        }
    };
    if let Some(action) = action {
        apply(action)?;
    }
    // No yield here: a pause on this device never hands the session away.
    hub::publish();
    Ok(())
}

fn convert(event: &PlayerEvent) -> Option<Event> {
    Some(match event {
        PlayerEvent::PlayRequestIdChanged { play_request_id } => Event::RequestId(*play_request_id),
        PlayerEvent::Loading { play_request_id, .. } => Event::Loading(*play_request_id),
        PlayerEvent::Playing { play_request_id, position_ms, .. } => {
            Event::Playing { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Paused { play_request_id, position_ms, .. } => {
            Event::Paused { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Seeked { play_request_id, position_ms, .. }
        | PlayerEvent::PositionCorrection { play_request_id, position_ms, .. } => {
            Event::Position { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Stopped { play_request_id, .. } => Event::Stopped(*play_request_id),
        PlayerEvent::TimeToPreloadNextTrack { play_request_id, .. } => Event::TimeToPreload(*play_request_id),
        PlayerEvent::EndOfTrack { play_request_id, .. } => Event::EndOfTrack(*play_request_id),
        PlayerEvent::Unavailable { play_request_id, track_id, .. } => {
            Event::Unavailable { id: *play_request_id, uri: track_id.to_uri().ok()? }
        }
        PlayerEvent::TrackChanged { audio_item } => {
            Event::TrackChanged { uri: audio_item.track_id.to_uri().ok()?, duration_ms: audio_item.duration_ms }
        }
        _ => return None,
    })
}

pub(crate) fn on_player_event(event: &PlayerEvent) {
    let Some(event) = convert(event) else { return };
    let outcome = {
        let mut q = QUEUE.lock();
        if !q.active {
            return;
        }
        q.on_event(event, now_ms())
    };
    if let Some(action) = outcome.action {
        if let Err(e) = apply(action) {
            log::warn!("offline playback: {e}");
        }
    }
    if outcome.exhausted_after_error {
        let reason = player_events::take_recent_unavailable()
            .unwrap_or(librespot_playback::player::UnavailableReason::Other);
        events::emit_error(&player_events::unavailable_error(reason));
    }
    if outcome.changed {
        hub::publish();
        // the end of the queue, after another device took over meanwhile
        yield_to_active_device();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{AlbumRef, ArtistRef, Episode, ShowRef, Track};

    fn record(uri: &str, album: &str, artist: &str) -> OfflineTrackRecord {
        OfflineTrackRecord {
            uri: uri.into(),
            played_uri: None,
            file_id: "f".into(),
            format: "OGG_VORBIS_160".into(),
            key_hex: "00".into(),
            path: "/x".into(),
            size_bytes: 1,
            normalisation: Default::default(),
            track: Some(Track {
                uri: uri.into(),
                name: "n".into(),
                artists: vec![ArtistRef { uri: artist.into(), name: "a".into(), images: vec![] }],
                album: Some(AlbumRef { uri: album.into(), name: "al".into(), ..Default::default() }),
                ..Default::default()
            }),
            episode: None,
            image_path: None,
        }
    }

    fn numbered(mut r: OfflineTrackRecord, disc: u32, track: u32, released: &str) -> OfflineTrackRecord {
        let t = r.track.as_mut().unwrap();
        t.disc_number = Some(disc);
        t.track_number = Some(track);
        t.album.as_mut().unwrap().release_date = Some(released.into());
        r
    }

    fn episode(uri: &str, show: &str, released: &str) -> OfflineTrackRecord {
        OfflineTrackRecord {
            track: None,
            episode: Some(Episode {
                uri: uri.into(),
                show: Some(ShowRef { uri: show.into(), name: "s".into(), publisher: None, images: vec![] }),
                release_date: Some(released.into()),
                ..Default::default()
            }),
            ..record(uri, "", "")
        }
    }

    #[test]
    fn context_membership() {
        let records = vec![
            record("spotify:track:1", "spotify:album:a", "spotify:artist:x"),
            record("spotify:track:2", "spotify:album:b", "spotify:artist:y"),
            record("spotify:track:3", "spotify:album:a", "spotify:artist:y"),
        ];
        assert_eq!(context_members("spotify:album:a", &records), vec!["spotify:track:1", "spotify:track:3"]);
        assert_eq!(context_members("spotify:artist:y", &records), vec!["spotify:track:3", "spotify:track:2"]);
        // no membership info: nothing (instead of every download)
        assert!(context_members("spotify:playlist:p", &records).is_empty());
        assert!(context_members("spotify:user:u:collection", &records).is_empty());
    }

    #[test]
    fn context_members_in_context_order() {
        // records come sorted by uri, which isn't the album order
        let records = vec![
            numbered(record("spotify:track:a", "spotify:album:x", "spotify:artist:y"), 2, 1, "2020"),
            numbered(record("spotify:track:b", "spotify:album:x", "spotify:artist:y"), 1, 3, "2020"),
            numbered(record("spotify:track:c", "spotify:album:x", "spotify:artist:y"), 1, 1, "2020"),
            numbered(record("spotify:track:d", "spotify:album:z", "spotify:artist:y"), 1, 1, "2023"),
            episode("spotify:episode:e", "spotify:show:s", "2024-01-01"),
            episode("spotify:episode:f", "spotify:show:s", "2024-03-01"),
        ];
        assert_eq!(
            context_members("spotify:album:x", &records),
            vec!["spotify:track:c", "spotify:track:b", "spotify:track:a"]
        );
        assert_eq!(
            context_members("spotify:artist:y", &records),
            vec!["spotify:track:d", "spotify:track:c", "spotify:track:b", "spotify:track:a"]
        );
        assert_eq!(context_members("spotify:show:s", &records), vec!["spotify:episode:f", "spotify:episode:e"]);
    }
}
