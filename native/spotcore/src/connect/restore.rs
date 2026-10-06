//! Restoring this device's playback after the engine had to rebuild the Session + Spirc
//! (network switch, lost AP connection, dead player). A new Spirc starts inactive and empty, so
//! without this every Wi-Fi ↔ cellular switch would silently end local playback.
//!
//! Flow: the engine calls `prepare_reconnect` before tearing the old Spirc down (or right after it
//! died); the last active snapshot is frozen (shown paused meanwhile, see `hub::compose`). After
//! the new Spirc is online, `schedule` waits for its first cluster: if no other device took over
//! in the meantime, nothing else was started (an explicit load or offline playback drops the
//! restore point) it activates and reloads context, track, position, options and user queue.
//!
//! The Player outlives the Spirc. A Spirc that is shut down pauses it; one that ended by itself
//! (lost connection, failed dealer setup) leaves it playing, so `prepare_reconnect` pauses it, and
//! a restore point that is dropped stops the paused track nobody owns anymore.

use super::hub::{self, HubState, CLUSTER_CHANGED, HUB};
use super::{local, now_ms, offline, uri};
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

/// Whether the attached Spirc still runs (and with that controls the Player it played on).
fn spirc_running(hub: &HubState) -> bool {
    hub.link.as_ref().is_some_and(|l| l.spirc.is_running())
}

/// Freezes the restore point (no-op if one is pending or nothing was active). The playback is
/// considered interrupted now: a Spirc that ended by itself left the Player playing up to here.
pub(crate) fn freeze_restore_point(hub: &mut HubState, now_ms: i64, now: Instant) {
    if hub.reconnect.is_some() {
        return;
    }
    let active = hub.snapshot.clone().filter(|s| s.is_active && s.track.is_some());
    hub.reconnect = match active {
        Some(s) => Some(freeze(s, now_ms, now)),
        None => hub.last_active.take().map(|last| {
            let mut f = freeze(last.snap, last.ended_at_ms.unwrap_or(now_ms).min(now_ms), now);
            f.at_ms = now_ms;
            f
        }),
    };
}

/// Freezes the current local playback before a reconnect (no-op if nothing was active). If the
/// Spirc task already ended by itself, the Player it drove plays on (CDN fetches don't need the
/// session) while nothing can control it: it is paused here, after freezing (so that the restore
/// point is the playing state). On a deliberate teardown Spirc's shutdown pauses it.
pub(crate) fn prepare_reconnect() {
    let pause = {
        let mut hub = HUB.lock();
        freeze_restore_point(&mut hub, now_ms(), Instant::now());
        !spirc_running(&hub) && hub.snapshot.as_ref().is_some_and(|s| s.is_active)
    };
    if pause && !offline::is_active() {
        if let Some(player) = crate::engine::player_host::player() {
            log::info!("spirc ended by itself, pausing its player");
            player.pause();
        }
    }
    hub::publish();
}

/// Forgets a pending restore (stop, logout, offline mode, terminal errors, an explicit load,
/// skipped restore). The paused track of a dropped restore point (or the playing one of a Spirc
/// that ended by itself) has no owner anymore and is stopped, unless the offline queue or an
/// active Spirc owns the Player.
pub(crate) fn clear() {
    let (dropped, orphaned) = {
        let mut hub = HUB.lock();
        hub.last_active = None;
        let dropped = hub.reconnect.take().is_some();
        let running = spirc_running(&hub);
        let owned = running && hub.snapshot.as_ref().is_some_and(|s| s.is_active);
        (dropped, (dropped && !owned) || (hub.link.is_some() && !running))
    };
    if orphaned && !offline::is_active() {
        if let Some(player) = crate::engine::player_host::player() {
            player.stop();
        }
    }
    if dropped {
        hub::publish();
    }
}

#[derive(Debug)]
enum Decision {
    /// The restore point was dropped meanwhile.
    Cancelled,
    /// Not restoring (the reason is logged); the restore point is to be cleared.
    Skip(&'static str),
    /// Restore with this plan (the restore point was taken).
    Restore(Plan),
}

/// Whether to restore now, after the new Spirc's first cluster (or the wait timed out).
fn decide(hub: &mut HubState, me: &str, offline_active: bool) -> Decision {
    let Some(frozen) = hub.reconnect.as_ref() else { return Decision::Cancelled };
    let other_active =
        hub.cluster.as_ref().is_some_and(|c| !c.active_device_id.is_empty() && c.active_device_id != me);
    if other_active || hub.snapshot.as_ref().is_some_and(|s| s.is_active) {
        return Decision::Skip("another device is active");
    }
    if offline_active {
        // Started during the outage (a paused offline queue still owns the Player).
        return Decision::Skip("offline playback is in progress");
    }
    let Some(plan) = plan(frozen, frozen.since.elapsed()) else { return Decision::Skip("nothing to restore") };
    hub.reconnect = None;
    hub.last_active = None;
    Decision::Restore(plan)
}

/// Called once the Spirc `generation` is online after a reconnect.
pub(crate) fn schedule(generation: u64) {
    if HUB.lock().reconnect.is_none() {
        return;
    }
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
        let offline_active = offline::is_active();
        let skip = {
            let mut hub = HUB.lock();
            let Some(spirc) = hub.link.as_ref().filter(|l| l.generation == generation).map(|l| l.spirc.clone())
            else {
                return; // superseded; a newer Spirc schedules its own restore
            };
            match decide(&mut hub, &me, offline_active) {
                Decision::Cancelled => return,
                Decision::Skip(reason) => reason,
                Decision::Restore(plan) => {
                    // Sent while holding the hub, so that a concurrent explicit load (which clears
                    // the restore point first) reaches Spirc after the restore, not before it.
                    log::info!("restoring local playback after reconnect");
                    let result = local::sent(spirc.activate())
                        .and_then(|_| local::sent(spirc.load(plan.request)))
                        .and_then(|_| plan.queued.into_iter().try_for_each(|q| local::sent(spirc.add_to_queue(q))));
                    drop(hub);
                    if let Err(e) = result {
                        log::warn!("restore failed: {e}");
                    }
                    hub::publish();
                    return;
                }
            }
        };
        log::info!("not restoring playback: {skip}");
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

    fn frozen_hub() -> HubState {
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        freeze_restore_point(&mut hub, 1_002_000, Instant::now());
        // the new Spirc: attached, inactive
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 1_003_000);
        assert!(hub.reconnect.is_some());
        hub
    }

    #[test]
    fn offline_playback_started_during_the_outage_is_not_replaced() {
        let mut hub = frozen_hub();
        assert!(matches!(decide(&mut hub, "me", true), Decision::Skip(_)));
        assert!(hub.reconnect.is_some(), "cleared (and the orphan stopped) by the caller");
        // without offline playback the restore takes the restore point
        assert!(matches!(decide(&mut hub, "me", false), Decision::Restore(_)));
        assert!(hub.reconnect.is_none() && hub.last_active.is_none());
        // a restore point dropped meanwhile (explicit load) cancels it
        assert!(matches!(decide(&mut hub, "me", false), Decision::Cancelled));
    }

    #[test]
    fn restore_skipped_when_another_device_took_over() {
        let mut hub = frozen_hub();
        hub.cluster = Some(std::sync::Arc::new(librespot_protocol::connect::Cluster {
            active_device_id: "speaker".into(),
            ..Default::default()
        }));
        assert!(matches!(decide(&mut hub, "me", false), Decision::Skip(_)));
        hub.cluster = Some(std::sync::Arc::new(librespot_protocol::connect::Cluster {
            active_device_id: "me".into(),
            ..Default::default()
        }));
        assert!(matches!(decide(&mut hub, "me", false), Decision::Restore(_)));
    }

    #[test]
    fn final_snapshot_of_an_ended_spirc_keeps_the_playing_state() {
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        // the task ended by itself (lost connection): its final snapshot is the state before the
        // disconnect, even though the session is still valid (failed dealer setup)
        let last = ConnectSnapshot { ending: true, ..snap("spotify:album:a", SpircProvider::Context) };
        hub::apply_snapshot(&mut hub, last, false, 1_001_000);
        let now = Instant::now();
        freeze_restore_point(&mut hub, 1_005_000, now);
        let f = hub.reconnect.as_ref().expect("restore point");
        assert!(f.was_playing);
        assert_eq!(f.since, now, "interrupted when frozen, not when the snapshot last changed");
        assert_eq!(f.position_ms, 15_000, "the Player played on until it was paused");
        let p = plan(f, Duration::from_secs(5)).expect("plan");
        assert!(p.request.start_playing);
    }

    #[test]
    fn restore_point_of_a_dying_session_ends_when_spirc_went_inactive() {
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        // inactive while the session is invalid: not deliberate, Spirc stopped the Player here
        let inactive = ConnectSnapshot { status: SnapshotPlayStatus::Stopped, ..Default::default() };
        hub::apply_snapshot(&mut hub, inactive.clone(), true, 1_003_000);
        hub::apply_snapshot(&mut hub, inactive, true, 1_004_000);
        let now = Instant::now();
        freeze_restore_point(&mut hub, 1_009_000, now);
        let f = hub.reconnect.as_ref().expect("restore point");
        assert!(f.was_playing);
        assert_eq!(f.position_ms, 13_000);
        assert_eq!(f.since, now);
        assert_eq!(f.at_ms, 1_009_000);

        // deliberately inactive (valid session, nothing pending): nothing to restore
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 1_003_000);
        freeze_restore_point(&mut hub, 1_009_000, Instant::now());
        assert!(hub.reconnect.is_none());
    }

    #[test]
    fn nothing_to_restore_without_track() {
        let mut s = snap("spotify:album:a", SpircProvider::Context);
        s.track = None;
        assert!(plan(&freeze(s, 0, Instant::now()), Duration::ZERO).is_none());
    }
}
