//! OfflineController driver (docs/ARCHITECTURE.md §4.6): applies the [`OfflineQueue`] to the
//! shared Player while the engine has no online Spirc. Downloaded tracks only; the Player plays
//! them through the vendored offline hook without any network access.

use super::args::LoadArgs;
use super::offline_queue::{select_downloaded, Action, Event, LoadSpec, OfflineQueue};
use super::{hub, now_ms, player_events, uri, Ctl};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{ActiveDeviceRef, OfflineTrackRecord, PlaybackSnapshot, RepeatMode};
use crate::{engine, events, offline as downloads};
use librespot_core::SpotifyUri;
use librespot_playback::player::PlayerEvent;
use parking_lot::Mutex;
use std::sync::LazyLock;

static QUEUE: LazyLock<Mutex<OfflineQueue>> = LazyLock::new(|| Mutex::new(OfflineQueue::default()));

pub(crate) fn is_active() -> bool {
    QUEUE.lock().active
}

pub(crate) fn snapshot(device: ActiveDeviceRef, volume: u16) -> Option<PlaybackSnapshot> {
    let q = QUEUE.lock();
    q.active.then(|| q.snapshot(device, volume))
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

/// Downloaded members of a context, in record order. Records carry album / artist / show
/// references; for other contexts (playlists, Liked Songs) every download is used.
fn context_members(context_uri: &str, records: &[OfflineTrackRecord]) -> Vec<String> {
    let kind = uri::context_type(context_uri);
    let member = |r: &OfflineTrackRecord| match kind {
        "album" => album_uri(r) == Some(context_uri),
        "artist" => r.track.as_ref().is_some_and(|t| {
            t.artists.iter().any(|a| a.uri == context_uri)
                || t.album.as_ref().is_some_and(|al| al.artists.iter().any(|a| a.uri == context_uri))
        }),
        "show" => r.episode.as_ref().and_then(|e| e.show.as_ref()).is_some_and(|s| s.uri == context_uri),
        _ => true,
    };
    records.iter().filter(|r| member(r)).map(|r| r.uri.clone()).collect()
}

/// The downloaded items `args` asks for and the start index among them.
pub(crate) fn resolve(args: &LoadArgs) -> (Vec<String>, usize) {
    let uid_index = args.start_uid.as_deref().and_then(|u| u.strip_prefix('o')).and_then(|n| n.parse::<usize>().ok());
    match &args.track_uris {
        Some(tracks) if !tracks.is_empty() => {
            let (items, start) = select_downloaded(
                tracks,
                downloads::is_downloaded,
                args.start_index.map(|i| i as usize),
                args.start_uri.as_deref(),
            );
            let start = uid_index.filter(|&i| i < items.len()).unwrap_or(start);
            (items, start)
        }
        _ => {
            let Some(ctx) = args.context_uri.as_deref() else { return (Vec::new(), 0) };
            if uri::is_track(ctx) || uri::is_episode(ctx) {
                return if downloads::is_downloaded(ctx) { (vec![ctx.to_string()], 0) } else { (Vec::new(), 0) };
            }
            let members = context_members(ctx, &downloads::all_records());
            let (items, start) =
                select_downloaded(&members, |_| true, args.start_index.map(|i| i as usize), args.start_uri.as_deref());
            let start = uid_index.filter(|&i| i < items.len()).unwrap_or(start);
            (items, start)
        }
    }
}

pub(crate) fn has_downloaded(args: &LoadArgs) -> bool {
    !resolve(args).0.is_empty()
}

pub(crate) async fn load(args: &LoadArgs) -> AppResult<()> {
    let (uris, start) = resolve(args);
    if uris.is_empty() {
        return Err(AppError::unavailable("Not available offline"));
    }
    // Offline playback started during an outage replaces the reconnect restore point (callers
    // through `connect::load` already dropped it).
    super::restore::clear();
    engine::player_host::ensure_player_for_offline().await?;
    let spec = LoadSpec {
        context_uri: args.context_uri.clone(),
        uris,
        start,
        position_ms: args.position_ms,
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
                q.add_to_queue(u.clone());
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
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{AlbumRef, ArtistRef, Track};

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

    #[test]
    fn context_membership() {
        let records = vec![
            record("spotify:track:1", "spotify:album:a", "spotify:artist:x"),
            record("spotify:track:2", "spotify:album:b", "spotify:artist:y"),
            record("spotify:track:3", "spotify:album:a", "spotify:artist:y"),
        ];
        assert_eq!(context_members("spotify:album:a", &records), vec!["spotify:track:1", "spotify:track:3"]);
        assert_eq!(context_members("spotify:artist:y", &records), vec!["spotify:track:2", "spotify:track:3"]);
        assert_eq!(context_members("spotify:playlist:p", &records).len(), 3, "no membership info: all downloads");
    }
}
