//! Pure mapping of Spirc / cluster state onto the `PlaybackSnapshot` contract
//! (docs/ARCHITECTURE.md §5). Metadata enrichment happens afterwards (see `metadata`).
//!
//! Clocks: `ConnectSnapshot::position_timestamp_ms` is already local epoch ms
//! (`System.currentTimeMillis()` clock) and is passed through unchanged. The cluster's
//! `PlayerState::timestamp` is a server timestamp; it is converted to the local clock with
//! `session.time_delta()` (server − local, seconds).

use super::uri::{self, DELIMITER_URI};
use crate::models::{
    ActiveDeviceRef, PlaybackContext, PlaybackRestrictions, PlaybackSnapshot, PlaybackSource, PlaybackStatus,
    PlaybackTrack, RepeatMode, TrackProvider,
};
use librespot_connect::{ConnectSnapshot, SnapshotPlayStatus, SnapshotTrack, TrackProvider as SpircProvider};
use librespot_protocol::connect::Cluster;
use librespot_protocol::player::{PlayerState, ProvidedTrack};
use std::collections::HashMap;

/// Upper bounds of the queue windows in a snapshot.
pub(crate) const MAX_NEXT: usize = 80;
pub(crate) const MAX_PREV: usize = 10;

/// Metadata key Spirc uses to mark a local smart shuffle suggestion.
const SUGGESTION_KEY: &str = "smart_shuffle.suggested";

pub(crate) fn status_from(status: SnapshotPlayStatus) -> PlaybackStatus {
    match status {
        SnapshotPlayStatus::Stopped => PlaybackStatus::Stopped,
        SnapshotPlayStatus::LoadingPlay => PlaybackStatus::Loading,
        // A paused load is paused for the app ("loading" means it will play): resumable.
        SnapshotPlayStatus::LoadingPause => PlaybackStatus::Paused,
        SnapshotPlayStatus::Playing => PlaybackStatus::Playing,
        SnapshotPlayStatus::Paused => PlaybackStatus::Paused,
    }
}

pub(crate) fn repeat_mode(context: bool, track: bool) -> RepeatMode {
    if track {
        RepeatMode::Track
    } else if context {
        RepeatMode::Context
    } else {
        RepeatMode::Off
    }
}

pub(crate) fn provider_of(provider: &SpircProvider) -> TrackProvider {
    match provider {
        SpircProvider::Context => TrackProvider::Context,
        SpircProvider::Queue => TrackProvider::Queue,
        SpircProvider::Autoplay => TrackProvider::Autoplay,
        SpircProvider::Unavailable => TrackProvider::Unavailable,
        SpircProvider::Suggestion => TrackProvider::Suggestion,
        SpircProvider::Other(other) => provider_from_str(other, &HashMap::new()),
    }
}

/// Provider of a track in another device's state (provider string + metadata flags).
pub(crate) fn provider_from_str(provider: &str, metadata: &HashMap<String, String>) -> TrackProvider {
    let flag = |key: &str| metadata.get(key).is_some_and(|v| v == "true");
    if flag(SUGGESTION_KEY) {
        return TrackProvider::Suggestion;
    }
    match provider {
        "queue" => TrackProvider::Queue,
        "autoplay" => TrackProvider::Autoplay,
        "unavailable" => TrackProvider::Unavailable,
        _ if flag("is_queued") => TrackProvider::Queue,
        _ if flag("autoplay.is_autoplay") => TrackProvider::Autoplay,
        _ => TrackProvider::Context,
    }
}

fn bare_track(uri: &str, uid: &str, provider: TrackProvider) -> PlaybackTrack {
    PlaybackTrack {
        uri: uri.to_string(),
        uid: uid.to_string(),
        provider,
        is_episode: uri::is_episode(uri),
        ..Default::default()
    }
}

pub(crate) fn snapshot_track(track: &SnapshotTrack) -> PlaybackTrack {
    bare_track(&track.uri, &track.uid, provider_of(&track.provider))
}

/// True for queue entries that must not be shown (delimiters, hidden entries).
pub(crate) fn is_hidden_provided(track: &ProvidedTrack) -> bool {
    track.uri == DELIMITER_URI
        || track.uid.starts_with("delimiter")
        || track.metadata.get("hidden").is_some_and(|v| v == "true")
}

pub(crate) fn provided_track(track: &ProvidedTrack) -> PlaybackTrack {
    let mut out = bare_track(&track.uri, &track.uid, provider_from_str(&track.provider, &track.metadata));
    // Official clients put a few display hints into the metadata; used until the catalog answers.
    if let Some(title) = track.metadata.get("title").filter(|t| !t.is_empty()) {
        out.name = Some(title.clone());
    }
    out
}

fn visible_snapshot_tracks(tracks: &[SnapshotTrack]) -> impl Iterator<Item = &SnapshotTrack> {
    tracks.iter().filter(|t| !t.hidden && t.uri != DELIMITER_URI)
}

/// Keeps the last `max` items (prev tracks are oldest first; the newest ones matter).
fn tail<T>(mut items: Vec<T>, max: usize) -> Vec<T> {
    if items.len() > max {
        items.drain(..items.len() - max);
    }
    items
}

fn context_from(uri: &str, metadata: &HashMap<String, String>) -> Option<PlaybackContext> {
    if uri.is_empty() {
        return None;
    }
    let name = metadata.get("context_description").filter(|n| !n.is_empty()).cloned();
    Some(PlaybackContext { uri: uri.to_string(), name, kind: uri::context_type(uri).to_string() })
}

fn clamp_u64(v: i64) -> u64 {
    v.max(0) as u64
}

/// Maps the local Spirc snapshot (this device active). Metadata fields are filled later.
pub(crate) fn map_local(s: &ConnectSnapshot, device: ActiveDeviceRef) -> PlaybackSnapshot {
    let status = status_from(s.status);
    let prev: Vec<PlaybackTrack> = visible_snapshot_tracks(&s.prev_tracks).map(snapshot_track).collect();
    let next: Vec<PlaybackTrack> = visible_snapshot_tracks(&s.next_tracks).take(MAX_NEXT).map(snapshot_track).collect();
    let track = s.track.as_ref().filter(|t| !t.hidden).map(snapshot_track);
    let has_track = track.is_some();
    PlaybackSnapshot {
        source: PlaybackSource::Local,
        offline: false,
        active_device: Some(device),
        status,
        position_ms: clamp_u64(s.position_ms),
        position_timestamp_ms: s.position_timestamp_ms,
        playback_speed: if status == PlaybackStatus::Playing { s.playback_speed } else { 0.0 },
        duration_ms: clamp_u64(s.duration_ms),
        context: context_from(&s.context_uri, &s.context_metadata),
        track,
        prev_tracks: tail(prev, MAX_PREV),
        next_tracks: next,
        shuffle: s.shuffle || s.smart_shuffle,
        smart_shuffle: s.smart_shuffle,
        repeat: repeat_mode(s.repeat_context, s.repeat_track),
        is_playing_autoplay: s.playing_autoplay,
        restrictions: PlaybackRestrictions {
            can_skip_prev: has_track && s.can_skip_prev,
            can_skip_next: s.can_skip_next,
            can_seek: has_track && s.duration_ms > 0,
            can_toggle_shuffle: s.can_toggle_shuffle,
            can_toggle_repeat: s.can_toggle_repeat,
            can_pause: has_track,
        },
        volume: s.volume,
        last_error: s.last_error.clone().filter(|e| !is_not_active_error(e)),
    }
}

/// Spirc reports commands sent while inactive as failed; our own activate + command sequences can
/// hit that in races, and it is never useful to show.
pub(crate) fn is_not_active_error(message: &str) -> bool {
    message.contains("ignored while the device is not the active connect device")
}

pub(crate) fn device_type_str_of(cluster: &Cluster, device_id: &str) -> (String, String) {
    match cluster.device.get(device_id) {
        Some(info) => (info.name.clone(), super::devices::device_type_str(info.device_type.enum_value_or_default()).to_string()),
        None => (String::new(), "unknown".to_string()),
    }
}

/// Maps the player state of another active device. `None` when no device is active or the
/// active device is this one.
pub(crate) fn map_remote(cluster: &Cluster, me: &str, time_delta_s: i64) -> Option<PlaybackSnapshot> {
    let active = cluster.active_device_id.as_str();
    if active.is_empty() || active == me {
        return None;
    }
    let ps: &PlayerState = cluster.player_state.as_ref()?;
    let (name, kind) = device_type_str_of(cluster, active);
    let status = if !ps.is_playing {
        PlaybackStatus::Stopped
    } else if ps.is_paused {
        PlaybackStatus::Paused
    } else if ps.is_buffering {
        PlaybackStatus::Loading
    } else {
        PlaybackStatus::Playing
    };
    let speed = if status == PlaybackStatus::Playing {
        if ps.playback_speed > 0.0 { ps.playback_speed } else { 1.0 }
    } else {
        0.0
    };
    let options = ps.options.as_ref();
    let shuffle = options.map(|o| o.shuffling_context).unwrap_or(false);
    let repeat = options.map(|o| repeat_mode(o.repeating_context, o.repeating_track)).unwrap_or_default();
    let r = ps.restrictions.as_ref();
    let allowed = |f: fn(&librespot_protocol::player::Restrictions) -> &Vec<String>| r.map(|r| f(r).is_empty()).unwrap_or(true);
    let track = ps.track.as_ref().filter(|t| !is_hidden_provided(t)).map(provided_track);
    let has_track = track.is_some();
    let next: Vec<PlaybackTrack> =
        ps.next_tracks.iter().filter(|t| !is_hidden_provided(t)).take(MAX_NEXT).map(provided_track).collect();
    let prev: Vec<PlaybackTrack> = ps.prev_tracks.iter().filter(|t| !is_hidden_provided(t)).map(provided_track).collect();
    let volume = cluster.device.get(active).map(|d| d.volume.min(u16::MAX as u32) as u16).unwrap_or(0);
    let can_skip_next = !next.is_empty() && allowed(|r| &r.disallow_skipping_next_reasons);
    let is_autoplay = ps.track.as_ref().is_some_and(|t| t.provider == "autoplay");
    Some(PlaybackSnapshot {
        source: PlaybackSource::Remote,
        offline: false,
        active_device: Some(ActiveDeviceRef { id: active.to_string(), name, kind }),
        status,
        position_ms: clamp_u64(ps.position_as_of_timestamp),
        position_timestamp_ms: if ps.timestamp > 0 { ps.timestamp - time_delta_s * 1000 } else { 0 },
        playback_speed: speed,
        duration_ms: clamp_u64(ps.duration),
        context: context_from(&ps.context_uri, &ps.context_metadata),
        track,
        prev_tracks: tail(prev, MAX_PREV),
        next_tracks: next,
        shuffle,
        smart_shuffle: false,
        repeat,
        is_playing_autoplay: is_autoplay,
        restrictions: PlaybackRestrictions {
            can_skip_prev: has_track && allowed(|r| &r.disallow_skipping_prev_reasons),
            can_skip_next,
            can_seek: has_track && allowed(|r| &r.disallow_seeking_reasons),
            can_toggle_shuffle: allowed(|r| &r.disallow_toggling_shuffle_reasons),
            can_toggle_repeat: allowed(|r| &r.disallow_toggling_repeat_context_reasons)
                && allowed(|r| &r.disallow_toggling_repeat_track_reasons),
            can_pause: has_track && allowed(|r| &r.disallow_pausing_reasons),
        },
        volume,
        last_error: None,
    })
}

/// Nothing is playing anywhere (or nothing is known).
pub(crate) fn none_snapshot(volume: u16) -> PlaybackSnapshot {
    PlaybackSnapshot {
        source: PlaybackSource::None,
        status: PlaybackStatus::Stopped,
        playback_speed: 0.0,
        restrictions: PlaybackRestrictions {
            can_skip_prev: false,
            can_skip_next: false,
            can_seek: false,
            can_toggle_shuffle: false,
            can_toggle_repeat: false,
            can_pause: false,
        },
        volume,
        ..Default::default()
    }
}

/// The visible (displayed) next tracks of a Spirc snapshot with their raw indices.
pub(crate) fn visible_next_indices(next: &[SnapshotTrack]) -> Vec<usize> {
    next.iter().enumerate().filter(|(_, t)| !t.hidden && t.uri != DELIMITER_URI).map(|(i, _)| i).collect()
}

/// Converts a target index in the displayed next tracks (hidden entries removed, `uid` removed)
/// into the raw index Spirc's `move_queue_item` expects (index in the raw list without the
/// item): right after the displayed item that will precede it.
pub(crate) fn raw_move_index(hidden: &[bool], uids: &[&str], uid: &str, to_visible: usize) -> usize {
    if to_visible == 0 {
        return 0;
    }
    let mut visible_seen = 0usize;
    let mut raw = 0usize;
    for (h, u) in hidden.iter().zip(uids.iter()) {
        if *u == uid {
            continue;
        }
        raw += 1;
        if !*h {
            visible_seen += 1;
            if visible_seen == to_visible {
                return raw;
            }
        }
    }
    raw
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::connect::DeviceInfo;
    use librespot_protocol::devices::DeviceType as ProtoDeviceType;
    use librespot_protocol::player::{ContextPlayerOptions, Restrictions};

    fn st(uri: &str, uid: &str, provider: SpircProvider, hidden: bool) -> SnapshotTrack {
        SnapshotTrack { uri: uri.into(), uid: uid.into(), provider, context_index: None, hidden, metadata: HashMap::new() }
    }

    fn device() -> ActiveDeviceRef {
        ActiveDeviceRef { id: "me".into(), name: "Phone".into(), kind: "smartphone".into() }
    }

    fn base_snapshot() -> ConnectSnapshot {
        ConnectSnapshot {
            is_active: true,
            status: SnapshotPlayStatus::Playing,
            position_ms: 1234,
            position_timestamp_ms: 1_700_000_000_000,
            playback_speed: 1.0,
            duration_ms: 200_000,
            context_uri: "spotify:album:abc".into(),
            track: Some(st("spotify:track:cur", "c1", SpircProvider::Context, false)),
            prev_tracks: (0..15).map(|i| st(&format!("spotify:track:p{i}"), &format!("p{i}"), SpircProvider::Context, false)).collect(),
            next_tracks: vec![
                st("spotify:track:q", "q0", SpircProvider::Queue, false),
                st("spotify:track:s", "s-1", SpircProvider::Suggestion, false),
                st("spotify:delimiter", "delimiter0", SpircProvider::Context, true),
                st("spotify:track:a", "a1", SpircProvider::Autoplay, false),
            ],
            shuffle: true,
            smart_shuffle: true,
            repeat_context: true,
            repeat_track: false,
            can_skip_prev: true,
            can_skip_next: true,
            can_toggle_shuffle: true,
            can_toggle_repeat: false,
            volume: 30000,
            last_error: Some("load: load is ignored while the device is not the active connect device".into()),
            ..Default::default()
        }
    }

    #[test]
    fn local_mapping() {
        let s = map_local(&base_snapshot(), device());
        assert_eq!(s.source, PlaybackSource::Local);
        assert_eq!(s.status, PlaybackStatus::Playing);
        assert_eq!(s.position_ms, 1234);
        assert_eq!(s.position_timestamp_ms, 1_700_000_000_000);
        assert_eq!(s.playback_speed, 1.0);
        assert_eq!(s.prev_tracks.len(), MAX_PREV);
        assert_eq!(s.prev_tracks.last().map(|t| t.uri.as_str()), Some("spotify:track:p14"));
        assert_eq!(s.next_tracks.len(), 3, "delimiter removed");
        assert_eq!(s.next_tracks[0].provider, TrackProvider::Queue);
        assert_eq!(s.next_tracks[1].provider, TrackProvider::Suggestion);
        assert_eq!(s.next_tracks[2].provider, TrackProvider::Autoplay);
        assert_eq!(s.repeat, RepeatMode::Context);
        assert!(s.smart_shuffle && s.shuffle);
        assert!(!s.restrictions.can_toggle_repeat);
        assert_eq!(s.context.as_ref().map(|c| c.kind.as_str()), Some("album"));
        assert_eq!(s.last_error, None, "NotActive errors are not user visible");
        assert_eq!(s.volume, 30000);
    }

    #[test]
    fn local_paused_has_zero_speed_and_statuses_map() {
        let mut snap = base_snapshot();
        snap.status = SnapshotPlayStatus::Paused;
        assert_eq!(map_local(&snap, device()).playback_speed, 0.0);
        assert_eq!(status_from(SnapshotPlayStatus::LoadingPause), PlaybackStatus::Paused);
        assert_eq!(status_from(SnapshotPlayStatus::LoadingPlay), PlaybackStatus::Loading);
        assert_eq!(status_from(SnapshotPlayStatus::Stopped), PlaybackStatus::Stopped);
        assert_eq!(repeat_mode(true, true), RepeatMode::Track);
        assert_eq!(repeat_mode(false, false), RepeatMode::Off);
    }

    fn pt(uri: &str, uid: &str, provider: &str) -> ProvidedTrack {
        ProvidedTrack { uri: uri.into(), uid: uid.into(), provider: provider.into(), ..Default::default() }
    }

    fn cluster() -> Cluster {
        let mut c = Cluster::new();
        c.active_device_id = "other".into();
        let mut info = DeviceInfo::new();
        info.name = "Desk".into();
        info.volume = 40000;
        info.device_type = ProtoDeviceType::COMPUTER.into();
        c.device.insert("other".into(), info);
        let mut ps = PlayerState::new();
        ps.timestamp = 1_700_000_010_000;
        ps.position_as_of_timestamp = 5000;
        ps.duration = 180_000;
        ps.is_playing = true;
        ps.context_uri = "spotify:playlist:xyz".into();
        ps.context_metadata.insert("context_description".into(), "Mix".into());
        let mut t = pt("spotify:track:cur", "u1", "context");
        t.metadata.insert("title".into(), "Song".into());
        ps.track = Some(t).into();
        ps.next_tracks = vec![pt("spotify:track:n1", "u2", "queue"), pt("spotify:delimiter", "delimiter0", "context")];
        let mut opts = ContextPlayerOptions::new();
        opts.shuffling_context = true;
        opts.repeating_track = true;
        ps.options = Some(opts).into();
        let mut r = Restrictions::new();
        r.disallow_seeking_reasons.push("ad".into());
        ps.restrictions = Some(r).into();
        c.player_state = Some(ps).into();
        c
    }

    #[test]
    fn remote_mapping() {
        let s = map_remote(&cluster(), "me", 2).expect("remote");
        assert_eq!(s.source, PlaybackSource::Remote);
        assert_eq!(s.status, PlaybackStatus::Playing);
        assert_eq!(s.position_ms, 5000);
        assert_eq!(s.position_timestamp_ms, 1_700_000_008_000, "server clock converted to local");
        assert_eq!(s.playback_speed, 1.0);
        assert_eq!(s.volume, 40000);
        assert_eq!(s.active_device.as_ref().map(|d| d.kind.as_str()), Some("computer"));
        assert_eq!(s.track.as_ref().and_then(|t| t.name.as_deref()), Some("Song"));
        assert_eq!(s.next_tracks.len(), 1);
        assert_eq!(s.next_tracks[0].provider, TrackProvider::Queue);
        assert!(s.shuffle);
        assert_eq!(s.repeat, RepeatMode::Track);
        assert!(!s.restrictions.can_seek);
        assert_eq!(s.context.as_ref().and_then(|c| c.name.as_deref()), Some("Mix"));
    }

    #[test]
    fn remote_none_when_self_or_nobody() {
        let mut c = cluster();
        assert!(map_remote(&c, "other", 0).is_none());
        c.active_device_id.clear();
        assert!(map_remote(&c, "me", 0).is_none());
    }

    #[test]
    fn remote_paused_status() {
        let mut c = cluster();
        if let Some(ps) = c.player_state.as_mut() {
            ps.is_paused = true;
        }
        let s = map_remote(&c, "me", 0).expect("remote");
        assert_eq!(s.status, PlaybackStatus::Paused);
        assert_eq!(s.playback_speed, 0.0);
    }

    #[test]
    fn provider_strings() {
        let mut m = HashMap::new();
        assert_eq!(provider_from_str("context", &m), TrackProvider::Context);
        assert_eq!(provider_from_str("queue", &m), TrackProvider::Queue);
        assert_eq!(provider_from_str("something", &m), TrackProvider::Context);
        m.insert("is_queued".to_string(), "true".to_string());
        assert_eq!(provider_from_str("something", &m), TrackProvider::Queue);
        m.insert(SUGGESTION_KEY.to_string(), "true".to_string());
        assert_eq!(provider_from_str("context", &m), TrackProvider::Suggestion);
    }

    #[test]
    fn move_index_mapping() {
        // raw: q0, q1, c1, [delim], c2
        let hidden = [false, false, false, true, false];
        let uids = ["q0", "q1", "c1", "delimiter0", "c2"];
        // move q0 to visible index 1 (after q1) => raw index 1 in the list without q0
        assert_eq!(raw_move_index(&hidden, &uids, "q0", 1), 1);
        // move c1 to the front
        assert_eq!(raw_move_index(&hidden, &uids, "c1", 0), 0);
        // to the visible end
        assert_eq!(raw_move_index(&hidden, &uids, "q0", 3), 4);
        // displayed index 2 (without q0: q1, c1 | c2) -> right after c1, before the delimiter
        assert_eq!(raw_move_index(&hidden, &uids, "q0", 2), 2);
    }
}
