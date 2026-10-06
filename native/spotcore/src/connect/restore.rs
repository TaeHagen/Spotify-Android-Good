//! Restoring this device's playback after the engine had to rebuild the Session + Spirc
//! (network switch, lost AP connection, dead player). A new Spirc starts inactive and empty, so
//! without this every Wi-Fi ↔ cellular switch would silently end local playback.
//!
//! Flow: the engine calls `prepare_reconnect` before tearing the old Spirc down (or right after it
//! died); the last active snapshot is frozen (shown paused meanwhile, see `hub::compose`). After
//! the new Spirc is online, `schedule` waits for its first cluster: if no other device took over
//! in the meantime it activates and reloads context, track, position, options and user queue.

use super::hub::{self, CLUSTER_CHANGED, HUB};
use super::{local, now_ms, uri};
use librespot_connect::{
    ConnectSnapshot, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, SnapshotPlayStatus,
    TrackProvider as SpircProvider,
};
use std::time::{Duration, Instant};

/// How long the new Spirc may take to deliver its first cluster.
const CLUSTER_WAIT: Duration = Duration::from_secs(15);
/// Playback only resumes playing automatically if the interruption was shorter than this.
const RESUME_PLAYING_MAX_GAP: Duration = Duration::from_secs(120);

#[derive(Debug, Clone)]
pub(crate) struct Frozen {
    pub snap: ConnectSnapshot,
    pub position_ms: i64,
    pub at_ms: i64,
    pub since: Instant,
    pub was_playing: bool,
}

/// Position of `s` at `now_ms` (local epoch ms).
pub(crate) fn position_now(s: &ConnectSnapshot, now_ms: i64) -> i64 {
    let mut p = s.position_ms;
    if s.status == SnapshotPlayStatus::Playing {
        p += ((now_ms - s.position_timestamp_ms).max(0) as f64 * s.playback_speed) as i64;
    }
    if s.duration_ms > 0 {
        p = p.min(s.duration_ms);
    }
    p.max(0)
}

pub(crate) fn freeze(snap: ConnectSnapshot, now_ms: i64, since: Instant) -> Frozen {
    let was_playing = matches!(snap.status, SnapshotPlayStatus::Playing | SnapshotPlayStatus::LoadingPlay);
    Frozen { position_ms: position_now(&snap, now_ms), at_ms: now_ms, since, was_playing, snap }
}

#[derive(Debug)]
pub(crate) struct Plan {
    pub request: LoadRequest,
    pub queued: Vec<String>,
}

fn visible(t: &&librespot_connect::SnapshotTrack) -> bool {
    !t.hidden && t.uri != uri::DELIMITER_URI
}

/// What to load to get back to `f`. `gap`: time since the playback was interrupted.
pub(crate) fn plan(f: &Frozen, gap: Duration) -> Option<Plan> {
    let s = &f.snap;
    let current = s.track.as_ref().filter(|t| !t.hidden)?;
    let options = |playing_track| LoadRequestOptions {
        start_playing: f.was_playing && gap < RESUME_PLAYING_MAX_GAP,
        seek_to: f.position_ms.clamp(0, u32::MAX as i64) as u32,
        playing_track: Some(playing_track),
        context_options: Some(LoadContextOptions::Options(Options {
            shuffle: s.shuffle || s.smart_shuffle,
            repeat: s.repeat_context,
            repeat_track: s.repeat_track,
            smart_shuffle: s.smart_shuffle,
        })),
    };
    let queued: Vec<String> =
        s.next_tracks.iter().filter(visible).filter(|t| t.provider == SpircProvider::Queue).map(|t| t.uri.clone()).collect();
    let current_in_context = current.provider == SpircProvider::Context;
    let request = if uri::is_resolvable_context(&s.context_uri) && current_in_context {
        LoadRequest::from_context_uri(s.context_uri.clone(), options(PlayingTrack::Uri(current.uri.clone())))
    } else {
        // Plain track lists (or a queued/suggested current track): rebuild the visible window.
        let is_ctx = |t: &&librespot_connect::SnapshotTrack| {
            matches!(t.provider, SpircProvider::Context | SpircProvider::Autoplay)
        };
        let prev: Vec<String> = s.prev_tracks.iter().filter(visible).filter(is_ctx).map(|t| t.uri.clone()).collect();
        let next = s.next_tracks.iter().filter(visible).filter(is_ctx).map(|t| t.uri.clone());
        let index = prev.len() as u32;
        let mut tracks = prev;
        tracks.push(current.uri.clone());
        tracks.extend(next);
        LoadRequest::from_tracks(tracks, options(PlayingTrack::Index(index)))
    };
    Some(Plan { request, queued })
}

/// Freezes the current local playback before a reconnect (no-op if nothing was active).
pub(crate) fn prepare_reconnect() {
    {
        let mut hub = HUB.lock();
        if hub.reconnect.is_some() {
            return;
        }
        let active = hub.snapshot.clone().filter(|s| s.is_active && s.track.is_some());
        let source = active.map(|s| (s, Instant::now())).or_else(|| hub.last_active.take());
        hub.reconnect = source.map(|(s, since)| freeze(s, now_ms(), since));
    }
    hub::publish();
}

/// Forgets a pending restore (stop, logout, offline mode, terminal errors).
pub(crate) fn clear() {
    let changed = {
        let mut hub = HUB.lock();
        hub.last_active = None;
        hub.reconnect.take().is_some()
    };
    if changed {
        hub::publish();
    }
}

/// Called once the Spirc `generation` is online after a reconnect.
pub(crate) fn schedule(generation: u64) {
    let Some(frozen) = HUB.lock().reconnect.clone() else { return };
    crate::runtime::handle().spawn(async move {
        let deadline = tokio::time::Instant::now() + CLUSTER_WAIT;
        loop {
            let notified = CLUSTER_CHANGED.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            {
                let hub = HUB.lock();
                if hub.link.as_ref().map(|l| l.generation) != Some(generation) {
                    return; // superseded; a newer Spirc schedules its own restore
                }
                if hub.cluster.is_some() {
                    break;
                }
            }
            if tokio::time::timeout_at(deadline, notified).await.is_err() {
                break;
            }
        }
        let me = hub::me();
        let other_active = hub::active_device_id().is_some_and(|id| id != me);
        if other_active || hub::local_active() {
            log::info!("not restoring playback: another device is active");
            clear();
            return;
        }
        let Some(plan) = plan(&frozen, frozen.since.elapsed()) else {
            clear();
            return;
        };
        let Some(spirc) = hub::spirc() else { return };
        log::info!("restoring local playback after reconnect");
        let result = local::sent(spirc.activate())
            .and_then(|_| local::sent(spirc.load(plan.request)))
            .and_then(|_| plan.queued.into_iter().try_for_each(|q| local::sent(spirc.add_to_queue(q))));
        if let Err(e) = result {
            log::warn!("restore failed: {e}");
        }
        clear();
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_connect::SnapshotTrack;
    use std::collections::HashMap;

    fn st(uri: &str, uid: &str, provider: SpircProvider) -> SnapshotTrack {
        SnapshotTrack { uri: uri.into(), uid: uid.into(), provider, context_index: None, hidden: false, metadata: HashMap::new() }
    }

    fn snap(ctx: &str, current: SpircProvider) -> ConnectSnapshot {
        ConnectSnapshot {
            is_active: true,
            status: SnapshotPlayStatus::Playing,
            position_ms: 10_000,
            position_timestamp_ms: 1_000_000,
            playback_speed: 1.0,
            duration_ms: 30_000,
            context_uri: ctx.into(),
            track: Some(st("spotify:track:cur", "c", current)),
            prev_tracks: vec![st("spotify:track:p", "p", SpircProvider::Context)],
            next_tracks: vec![
                st("spotify:track:q", "q0", SpircProvider::Queue),
                st("spotify:track:n", "n", SpircProvider::Context),
            ],
            shuffle: true,
            ..Default::default()
        }
    }

    #[test]
    fn position_extrapolation() {
        let s = snap("spotify:album:a", SpircProvider::Context);
        assert_eq!(position_now(&s, 1_005_000), 15_000);
        assert_eq!(position_now(&s, 2_000_000), 30_000, "clamped to the duration");
        let mut paused = s.clone();
        paused.status = SnapshotPlayStatus::Paused;
        assert_eq!(position_now(&paused, 2_000_000), 10_000);
    }

    #[test]
    fn plan_context_restore() {
        let f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_002_000, Instant::now());
        assert!(f.was_playing);
        let p = plan(&f, Duration::from_secs(5)).expect("plan");
        assert!(p.request.start_playing);
        assert_eq!(p.request.seek_to, 12_000);
        assert!(matches!(p.request.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:cur"));
        assert!(matches!(p.request.context_options, Some(LoadContextOptions::Options(ref o)) if o.shuffle));
        assert!(format!("{:?}", p.request).contains("Uri(\"spotify:album:a\")"));
        assert_eq!(p.queued, vec!["spotify:track:q".to_string()]);
        // a long gap restores paused
        let p = plan(&f, Duration::from_secs(600)).expect("plan");
        assert!(!p.request.start_playing);
    }

    #[test]
    fn plan_track_list_restore() {
        let f = freeze(snap("spotify:web-api", SpircProvider::Context), 1_000_000, Instant::now());
        let p = plan(&f, Duration::ZERO).expect("plan");
        assert!(matches!(p.request.playing_track, Some(PlayingTrack::Index(1))));
        let dbg = format!("{:?}", p.request);
        assert!(dbg.contains("Tracks([\"spotify:track:p\", \"spotify:track:cur\", \"spotify:track:n\"])"), "{dbg}");
    }

    #[test]
    fn nothing_to_restore_without_track() {
        let mut s = snap("spotify:album:a", SpircProvider::Context);
        s.track = None;
        assert!(plan(&freeze(s, 0, Instant::now()), Duration::ZERO).is_none());
    }
}
