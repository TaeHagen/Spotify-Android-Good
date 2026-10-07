//! Restoring this device's playback after the engine had to rebuild the Session + Spirc
//! (network switch, lost AP connection, dead player). A new Spirc starts inactive and empty, so
//! without this every Wi-Fi ↔ cellular switch would silently end local playback.
//!
//! States (see `HubState`): **pending** (`reconnect` holds the frozen state, shown paused) →
//! **restoring** (activate + load sent to the new Spirc, `activation` set until its snapshot is
//! active) → done; or **abandoned** (`clear`: skipped, replaced, stopped).
//!
//! * The engine calls `prepare_reconnect` before tearing the old Spirc down (or right after it
//!   died): the last active snapshot is frozen and the Player paused.
//! * Once the new Spirc is online, `schedule` waits for its first cluster (never deciding without
//!   one) while no command holds the restore. If no other device took over meanwhile and nothing
//!   else was started (an explicit load or offline playback drops the restore point), it
//!   activates and reloads context, track, position, options and user queue.
//! * Commands consult the state instead of destroying it: play / pause set the restore's
//!   [`intent`](set_intent) (whether it starts playing), other controls wait for the decision and
//!   then go to the restored Spirc, a load or transfer [`hold`]s the decision while it is routed
//!   and replaces the restore point only once it is known to be valid.
//!
//! The Player outlives the Spirc and nothing else controls it: `prepare_reconnect` pauses it, a
//! dropped restore point stops the paused track nobody owns anymore (the offline queue or an
//! active Spirc excepted), and Spirc pauses it itself when its task ends or is aborted.

use super::args::LoadArgs;
use super::hub::{self, HubState, HUB};
use super::{local, now_ms, offline, uri};
use crate::models::RepeatMode;
use librespot_connect::{
    ConnectSnapshot, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, SnapshotPlayStatus,
    SnapshotTrack, TrackProvider as SpircProvider,
};
use std::time::{Duration, Instant};

/// How long the new Spirc may take to deliver its first cluster (its dealer start is bounded by
/// 30 s); without one the restore never runs (another device may have taken over).
const FIRST_CLUSTER_MAX: Duration = Duration::from_secs(60);
/// Playback only resumes playing automatically if the interruption was shorter than this.
const RESUME_PLAYING_MAX_GAP: Duration = Duration::from_secs(120);

#[derive(Debug, Clone)]
pub(crate) struct Frozen {
    pub snap: ConnectSnapshot,
    pub position_ms: i64,
    pub at_ms: i64,
    pub since: Instant,
    pub was_playing: bool,
    /// A user's play / pause while the restore was pending: whether it starts playing.
    pub intent: Option<bool>,
}

impl Frozen {
    /// Whether the restored playback starts playing after a gap of `gap`.
    fn start_playing(&self, gap: Duration) -> bool {
        self.intent.unwrap_or(self.was_playing && gap < RESUME_PLAYING_MAX_GAP)
    }
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
    Frozen { position_ms: position_now(&snap, now_ms), at_ms: now_ms, since, was_playing, snap, intent: None }
}

#[derive(Debug)]
pub(crate) struct Plan {
    pub request: LoadRequest,
    /// Added to the queue after the load (in order).
    pub queued: Vec<String>,
    /// Skip to the first queued entry after that (a queued or suggested track was playing; the
    /// context was loaded at the track before it).
    pub then_next: bool,
    /// Start playing after the skip (the load itself starts paused then).
    pub then_play: bool,
}

fn visible(t: &&SnapshotTrack) -> bool {
    !t.hidden && t.uri != uri::DELIMITER_URI
}

fn options_of(s: &ConnectSnapshot) -> Options {
    Options {
        shuffle: s.shuffle || s.smart_shuffle,
        repeat: s.repeat_context,
        repeat_track: s.repeat_track,
        smart_shuffle: s.smart_shuffle,
    }
}

/// What to load to get back to `f`. `gap`: time since the playback was interrupted.
pub(crate) fn plan(f: &Frozen, gap: Duration) -> Option<Plan> {
    let s = &f.snap;
    let current = s.track.as_ref().filter(|t| !t.hidden)?;
    let start_playing = f.start_playing(gap);
    let options = |playing_track, start_playing, seek_to| LoadRequestOptions {
        start_playing,
        seek_to,
        playing_track: Some(playing_track),
        context_options: Some(LoadContextOptions::Options(options_of(s))),
    };
    let seek_to = f.position_ms.clamp(0, u32::MAX as i64) as u32;
    let queued: Vec<String> =
        s.next_tracks.iter().filter(visible).filter(|t| t.provider == SpircProvider::Queue).map(|t| t.uri.clone()).collect();
    let resolvable = uri::is_resolvable_context(&s.context_uri);
    if resolvable && current.provider == SpircProvider::Context {
        let request = LoadRequest::from_context_uri(
            s.context_uri.clone(),
            options(PlayingTrack::Uri(current.uri.clone()), start_playing, seek_to),
        );
        return Some(Plan { request, queued, then_next: false, then_play: false });
    }
    // A queued or suggested track was playing: keep the context, loaded (paused) at the context
    // track played before it, with the track queued in front of the user queue and skipped to.
    // Its position is lost (it restarts), the playlist isn't.
    let anchor = s.prev_tracks.iter().rev().filter(visible).find(|t| t.provider == SpircProvider::Context);
    if resolvable && matches!(current.provider, SpircProvider::Queue | SpircProvider::Suggestion) {
        if let Some(anchor) = anchor {
            let request =
                LoadRequest::from_context_uri(s.context_uri.clone(), options(PlayingTrack::Uri(anchor.uri.clone()), false, 0));
            let mut requeue = vec![current.uri.clone()];
            requeue.extend(queued);
            return Some(Plan { request, queued: requeue, then_next: true, then_play: start_playing });
        }
    }
    // Plain track lists (or nothing to anchor the context at): rebuild the visible window.
    let is_ctx = |t: &&SnapshotTrack| matches!(t.provider, SpircProvider::Context | SpircProvider::Autoplay);
    let prev: Vec<String> = s.prev_tracks.iter().filter(visible).filter(is_ctx).map(|t| t.uri.clone()).collect();
    let next = s.next_tracks.iter().filter(visible).filter(is_ctx).map(|t| t.uri.clone());
    let index = prev.len() as u32;
    let mut tracks = prev;
    tracks.push(current.uri.clone());
    tracks.extend(next);
    let request = LoadRequest::from_tracks(tracks, options(PlayingTrack::Index(index), start_playing, seek_to));
    Some(Plan { request, queued, then_next: false, then_play: false })
}

/// The frozen session as a `player.load` (for another device: context, track, position, options).
pub(crate) fn load_args(f: &Frozen, play: bool) -> Option<LoadArgs> {
    let s = &f.snap;
    let current = s.track.as_ref().filter(|t| !t.hidden)?;
    let repeat = if s.repeat_track {
        RepeatMode::Track
    } else if s.repeat_context {
        RepeatMode::Context
    } else {
        RepeatMode::Off
    };
    let base = LoadArgs {
        position_ms: f.position_ms.max(0) as u64,
        shuffle: Some(s.shuffle || s.smart_shuffle),
        repeat: Some(repeat),
        play,
        ..Default::default()
    };
    Some(if uri::is_resolvable_context(&s.context_uri) {
        LoadArgs { context_uri: Some(s.context_uri.clone()), start_uri: Some(current.uri.clone()), ..base }
    } else {
        LoadArgs { track_uris: Some(vec![current.uri.clone()]), start_index: Some(0), shuffle: Some(false), ..base }
    })
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
            let ended = last.ended_at_ms.unwrap_or(now_ms).min(now_ms);
            let mut f = freeze(last.snap, ended, now);
            f.at_ms = now_ms;
            // An old restore point is never played automatically.
            if now_ms - ended > RESUME_PLAYING_MAX_GAP.as_millis() as i64 {
                f.was_playing = false;
            }
            f
        }),
    };
}

/// Freezes the current local playback before a reconnect (no-op if nothing was active) and pauses
/// the Player, after freezing (so that the restore point is the playing state). A Spirc that is
/// shut down pauses it too; one that ended by itself (lost connection, failed dealer setup) or is
/// stuck in a request doesn't, and CDN fetches don't need the session, so it would play on with
/// nothing able to control it.
pub(crate) fn prepare_reconnect() {
    let pause = {
        let mut hub = HUB.lock();
        freeze_restore_point(&mut hub, now_ms(), Instant::now());
        hub.snapshot.as_ref().is_some_and(|s| s.is_active && s.status != SnapshotPlayStatus::Stopped)
    };
    if pause && !offline::is_active() {
        if let Some(player) = crate::engine::player_host::player() {
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
    drop_restore();
}

/// Takes the pending restore point out (it won't run here): a transfer of this session to
/// another device. The paused track is stopped like by [`clear`].
pub(crate) fn take() -> Option<Frozen> {
    let frozen = HUB.lock().reconnect.clone();
    if frozen.is_some() {
        drop_restore();
    }
    frozen
}

fn drop_restore() {
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
        hub::changed();
        hub::publish();
    }
}

/// Whether a restore point is pending.
pub(crate) fn is_pending() -> bool {
    HUB.lock().reconnect.is_some()
}

/// A play (`true`) or pause (`false`) while a restore is pending: the restored playback starts
/// playing or paused. Returns whether a restore is pending.
pub(crate) fn set_intent(play: bool) -> bool {
    let mut hub = HUB.lock();
    match hub.reconnect.as_mut() {
        Some(f) => {
            f.intent = Some(play);
            true
        }
        None => false,
    }
}

/// While held, the pending restore doesn't run (a load or transfer is being routed, it replaces
/// the restore point if it goes through, and leaves it untouched if it fails).
pub(crate) struct Hold(());

pub(crate) fn hold() -> Hold {
    HUB.lock().restore_holds += 1;
    Hold(())
}

impl Drop for Hold {
    fn drop(&mut self) {
        {
            let mut hub = HUB.lock();
            hub.restore_holds = hub.restore_holds.saturating_sub(1);
        }
        hub::changed();
    }
}

#[derive(Debug)]
enum Decision {
    /// The restore point was dropped meanwhile.
    Cancelled,
    /// The first cluster isn't known yet (it shows whether another device took over).
    NoCluster,
    /// Not restoring (the reason is logged); the restore point is to be cleared.
    Skip(&'static str),
    /// Restore with this plan (the restore point was taken).
    Restore(Plan),
}

/// Whether to restore now, after the new Spirc's first cluster.
fn decide(hub: &mut HubState, me: &str, offline_active: bool) -> Decision {
    let Some(frozen) = hub.reconnect.as_ref() else { return Decision::Cancelled };
    let Some(cluster) = hub.cluster.as_ref() else { return Decision::NoCluster };
    let other_active = !cluster.active_device_id.is_empty() && cluster.active_device_id != me;
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
        let deadline = tokio::time::Instant::now() + FIRST_CLUSTER_MAX;
        loop {
            let notified = hub::CHANGED.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            {
                let hub = HUB.lock();
                if hub.link.as_ref().map(|l| l.generation) != Some(generation) {
                    return; // superseded; a newer Spirc schedules its own restore
                }
                if hub.reconnect.is_none() {
                    return; // replaced meanwhile
                }
                if hub.cluster.is_some() && hub.restore_holds == 0 {
                    break;
                }
            }
            if tokio::time::timeout_at(deadline, notified).await.is_err() {
                // Without a cluster another device may have taken over: never restore blind.
                // The restore point stays for the next Spirc (this one most likely died).
                log::warn!("no cluster from spirc {generation}, not restoring for now");
                return;
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
                Decision::Cancelled | Decision::NoCluster => return,
                Decision::Skip(reason) => reason,
                Decision::Restore(plan) => {
                    // Sent while holding the hub, so that a command routed meanwhile reaches Spirc
                    // after the restore, not before it.
                    log::info!("restoring local playback after reconnect");
                    hub.activation = Some(hub::Activation { generation, at: Instant::now() });
                    let result = local::sent(spirc.activate())
                        .and_then(|_| local::sent(spirc.load(plan.request)))
                        .and_then(|_| plan.queued.into_iter().try_for_each(|q| local::sent(spirc.add_to_queue(q))))
                        .and_then(|_| if plan.then_next { local::sent(spirc.next()) } else { Ok(()) })
                        .and_then(|_| if plan.then_play { local::sent(spirc.play()) } else { Ok(()) });
                    drop(hub);
                    if let Err(e) = result {
                        log::warn!("restore failed: {e}");
                    }
                    hub::changed();
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

    fn cluster(active: &str) -> Option<std::sync::Arc<librespot_protocol::connect::Cluster>> {
        Some(std::sync::Arc::new(librespot_protocol::connect::Cluster { active_device_id: active.into(), ..Default::default() }))
    }

    /// A frozen restore point, the new Spirc attached (inactive) with its first cluster (nobody
    /// active).
    fn frozen_hub() -> HubState {
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        freeze_restore_point(&mut hub, 1_002_000, Instant::now());
        hub::forget_previous_link(&mut hub);
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 1_003_000);
        hub.cluster = cluster("");
        assert!(hub.reconnect.is_some());
        hub
    }

    #[test]
    fn never_restores_without_the_first_cluster() {
        let mut hub = frozen_hub();
        hub.cluster = None;
        assert!(matches!(decide(&mut hub, "me", false), Decision::NoCluster));
        assert!(hub.reconnect.is_some(), "kept for when the cluster comes (or the next Spirc)");
    }

    #[test]
    fn play_and_pause_while_pending_decide_how_it_comes_back() {
        let mut hub = frozen_hub();
        // a pause while pending: back paused, even after a short gap
        hub.reconnect.as_mut().unwrap().intent = Some(false);
        let p = plan(hub.reconnect.as_ref().unwrap(), Duration::from_secs(1)).expect("plan");
        assert!(!p.request.start_playing);
        // a play: playing, even after a long gap
        hub.reconnect.as_mut().unwrap().intent = Some(true);
        let p = plan(hub.reconnect.as_ref().unwrap(), Duration::from_secs(3600)).expect("plan");
        assert!(p.request.start_playing);
        let Decision::Restore(p) = decide(&mut hub, "me", false) else { panic!("restore") };
        assert!(p.request.start_playing);
    }

    #[test]
    fn a_dropped_restore_point_never_comes_back() {
        // the restore was dropped while pending (a command, a transfer elsewhere); the dead
        // Spirc's last active snapshot is gone with the new link, so a later reconnect of the
        // idle new Spirc has nothing to restore
        let mut hub = frozen_hub();
        hub.reconnect = None;
        freeze_restore_point(&mut hub, 9_000_000, Instant::now());
        assert!(hub.reconnect.is_none());
    }

    #[test]
    fn an_old_restore_point_comes_back_paused() {
        let mut hub = HubState::default();
        hub::apply_snapshot(&mut hub, snap("spotify:album:a", SpircProvider::Context), false, 1_000_000);
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), true, 1_003_000);
        freeze_restore_point(&mut hub, 1_003_000 + 600_000, Instant::now());
        assert!(!hub.reconnect.as_ref().expect("restore point").was_playing);
    }

    #[test]
    fn a_queued_or_suggested_track_keeps_the_context() {
        for provider in [SpircProvider::Queue, SpircProvider::Suggestion] {
            let f = freeze(snap("spotify:playlist:x", provider.clone()), 1_000_000, Instant::now());
            let p = plan(&f, Duration::ZERO).expect("plan");
            let dbg = format!("{:?}", p.request);
            assert!(dbg.contains("Uri(\"spotify:playlist:x\")"), "{dbg}");
            // loaded paused at the context track before it, the track requeued first and skipped to
            assert!(matches!(p.request.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:p"));
            assert!(!p.request.start_playing);
            assert_eq!(p.queued, vec!["spotify:track:cur".to_string(), "spotify:track:q".to_string()]);
            assert!(p.then_next && p.then_play, "{provider:?}");
        }
        // without a track before it there is nothing to anchor the context at: the track list
        let mut s = snap("spotify:playlist:x", SpircProvider::Queue);
        s.prev_tracks.clear();
        let p = plan(&freeze(s, 1_000_000, Instant::now()), Duration::ZERO).expect("plan");
        assert!(format!("{:?}", p.request).contains("Tracks("));
        assert!(!p.then_next);
    }

    #[test]
    fn a_frozen_session_handed_to_another_device() {
        let mut s = snap("spotify:album:a", SpircProvider::Context);
        s.repeat_track = true;
        let f = freeze(s, 1_002_000, Instant::now());
        let a = load_args(&f, false).expect("load");
        assert_eq!(a.context_uri.as_deref(), Some("spotify:album:a"));
        assert_eq!(a.start_uri.as_deref(), Some("spotify:track:cur"));
        assert_eq!(a.position_ms, 12_000);
        assert_eq!(a.shuffle, Some(true));
        assert_eq!(a.repeat, Some(RepeatMode::Track));
        assert!(!a.play);
        let f = freeze(snap("spotify:web-api", SpircProvider::Context), 1_000_000, Instant::now());
        let a = load_args(&f, true).expect("load");
        assert_eq!(a.track_uris, Some(vec!["spotify:track:cur".to_string()]));
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
        hub.cluster = cluster("speaker");
        assert!(matches!(decide(&mut hub, "me", false), Decision::Skip(_)));
        hub.cluster = cluster("me");
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
