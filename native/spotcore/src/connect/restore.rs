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
//!   activates and reloads context, track, position, options and user queue. A first cluster
//!   later than [`FIRST_CLUSTER_MAX`] still decides it; until then the restore is *overdue*:
//!   commands no longer wait for it, and a play restores right away (the user asked for it here).
//! * Commands consult the state instead of destroying it: play / pause set the restore's
//!   [`intent`](set_intent) (whether it starts playing; a play only for [`PLAY_INTENT_MAX`], and
//!   not at all if it couldn't be answered, see [`answer`]), other controls wait for the decision
//!   and then go to the restored Spirc, a load or transfer [`hold`]s the decision while it is
//!   routed and replaces the restore point only once it is known to be valid.
//! * Gaps are measured on the wall clock too ([`elapsed`]): `Instant` stops while the phone sleeps.
//!
//! The Player outlives the Spirc and nothing else controls it: `prepare_reconnect` pauses it, a
//! dropped restore point stops the paused track nobody owns anymore (the offline queue or an
//! active Spirc excepted), and Spirc pauses it itself when its task ends or is aborted.

use super::args::LoadArgs;
use super::hub::{self, HubState, HUB};
use super::{local, now_ms, offline, uri};
use crate::error::{AppResult, ErrorCode};
use crate::models::RepeatMode;
use librespot_connect::{
    ConnectSnapshot, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, SnapshotPlayStatus,
    SnapshotTrack, Spirc, TrackProvider as SpircProvider,
};
use std::time::{Duration, Instant};

/// The new Spirc's first cluster normally comes well within this (its dealer start and the
/// announcement PUT are bounded by 30 s each). Without one the restore never runs on its own
/// (another device may have taken over); past this it is overdue (see the module docs).
const FIRST_CLUSTER_MAX: Duration = Duration::from_secs(60);
/// Playback only resumes playing automatically if the interruption was shorter than this.
const RESUME_PLAYING_MAX_GAP: Duration = Duration::from_secs(120);
/// A play pressed while the restore was pending counts for this long (a pause always counts).
const PLAY_INTENT_MAX: Duration = Duration::from_secs(30);
/// Spirc's queue holds at most this many entries (its next tracks).
const QUEUE_MAX: usize = super::snapshot::MAX_NEXT;
/// A frozen session handed to another device as a track list carries at most this many next
/// tracks (like the offline handover).
const HANDOVER_NEXT: usize = 50;

/// A play / pause pressed while the restore was pending (see [`set_intent`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Intent {
    pub play: bool,
    pub since: Instant,
    pub at_ms: i64,
}

#[derive(Debug, Clone)]
pub(crate) struct Frozen {
    pub snap: ConnectSnapshot,
    pub position_ms: i64,
    /// When the playback was interrupted, local epoch ms ...
    pub at_ms: i64,
    /// ... and on the monotonic clock.
    pub since: Instant,
    pub was_playing: bool,
    /// A user's play / pause while the restore was pending: whether it starts playing.
    pub intent: Option<Intent>,
}

/// Time from `since` / `at_ms` (the same moment on both clocks) to `now` / `now_ms`. `Instant`
/// (CLOCK_MONOTONIC on Android) stops while the phone sleeps, the wall clock doesn't: the longer
/// of the two counts. A wall clock set back before that moment counts as a long time (so an old
/// restore point never plays by itself).
pub(crate) fn elapsed(since: Instant, at_ms: i64, now: Instant, now_ms: i64) -> Duration {
    const CLOCK_SLACK_MS: i64 = 1000;
    if now_ms.saturating_add(CLOCK_SLACK_MS) < at_ms {
        return Duration::MAX;
    }
    let wall = Duration::from_millis(now_ms.saturating_sub(at_ms).max(0) as u64);
    now.saturating_duration_since(since).max(wall)
}

impl Frozen {
    /// Time since the playback was interrupted.
    pub(crate) fn age(&self, now: Instant, now_ms: i64) -> Duration {
        elapsed(self.since, self.at_ms, now, now_ms)
    }

    /// Whether the restored playback starts playing now: as a pause or a recent play asked while
    /// it was pending, otherwise if it played and the interruption was short.
    fn start_playing(&self, now: Instant, now_ms: i64) -> bool {
        match self.intent {
            Some(i) if !i.play => false,
            Some(i) if elapsed(i.since, i.at_ms, now, now_ms) < PLAY_INTENT_MAX => true,
            _ => self.was_playing && self.age(now, now_ms) < RESUME_PLAYING_MAX_GAP,
        }
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
    /// Turn repeat-one on after the skip (a skip turns it off).
    pub then_repeat_track: bool,
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

/// What to load to get back to `f`, starting to play or not.
pub(crate) fn plan(f: &Frozen, start_playing: bool) -> Option<Plan> {
    let s = &f.snap;
    let current = s.track.as_ref().filter(|t| !t.hidden)?;
    let options = |playing_track, start_playing, seek_to, o| LoadRequestOptions {
        start_playing,
        seek_to,
        playing_track: Some(playing_track),
        context_options: Some(LoadContextOptions::Options(o)),
    };
    let seek_to = f.position_ms.clamp(0, u32::MAX as i64) as u32;
    let mut queued: Vec<String> =
        s.next_tracks.iter().filter(visible).filter(|t| t.provider == SpircProvider::Queue).map(|t| t.uri.clone()).collect();
    queued.truncate(QUEUE_MAX);
    let resolvable = uri::is_resolvable_context(&s.context_uri);
    if resolvable && current.provider == SpircProvider::Context {
        let request = LoadRequest::from_context_uri(
            s.context_uri.clone(),
            options(PlayingTrack::Uri(current.uri.clone()), start_playing, seek_to, options_of(s)),
        );
        return Some(Plan { request, queued, then_next: false, then_repeat_track: false, then_play: false });
    }
    // A queued or suggested track was playing: keep the context, loaded (paused) at the context
    // track played before it, with the track queued in front of the user queue (the tail of a
    // full queue makes room for it) and skipped to. Its position is lost (it restarts), the
    // playlist isn't. Repeat-one comes after the skip, which would turn it off.
    let anchor = s.prev_tracks.iter().rev().filter(visible).find(|t| t.provider == SpircProvider::Context);
    if resolvable && matches!(current.provider, SpircProvider::Queue | SpircProvider::Suggestion) {
        if let Some(anchor) = anchor {
            let o = Options { repeat_track: false, ..options_of(s) };
            let request =
                LoadRequest::from_context_uri(s.context_uri.clone(), options(PlayingTrack::Uri(anchor.uri.clone()), false, 0, o));
            let mut requeue = vec![current.uri.clone()];
            requeue.extend(queued);
            requeue.truncate(QUEUE_MAX);
            return Some(Plan {
                request,
                queued: requeue,
                then_next: true,
                then_repeat_track: s.repeat_track,
                then_play: start_playing,
            });
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
    let request = LoadRequest::from_tracks(tracks, options(PlayingTrack::Index(index), start_playing, seek_to, options_of(s)));
    Some(Plan { request, queued, then_next: false, then_repeat_track: false, then_play: false })
}

/// The frozen session as a `player.load` (for another device: context, track, position, options).
/// A queued or suggested track isn't part of the context (the target would start the context's
/// first track at its position): then, like a plain track list, the visible window in play order
/// (previous tracks, the current one, the user queue and the next tracks).
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
    if uri::is_resolvable_context(&s.context_uri) && current.provider == SpircProvider::Context {
        return Some(LoadArgs { context_uri: Some(s.context_uri.clone()), start_uri: Some(current.uri.clone()), ..base });
    }
    let mut tracks: Vec<String> = s.prev_tracks.iter().filter(visible).map(|t| t.uri.clone()).collect();
    let start_index = tracks.len() as u32;
    tracks.push(current.uri.clone());
    tracks.extend(s.next_tracks.iter().filter(visible).take(HANDOVER_NEXT).map(|t| t.uri.clone()));
    Some(LoadArgs { track_uris: Some(tracks), start_index: Some(start_index), shuffle: Some(false), ..base })
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

/// The new Spirc's first cluster is overdue for the pending restore (see the module docs).
fn overdue_in(hub: &HubState) -> bool {
    hub.reconnect.is_some()
        && hub.restore_overdue.is_some_and(|g| hub.link.as_ref().is_some_and(|l| l.generation == g))
}

pub(crate) fn overdue() -> bool {
    overdue_in(&HUB.lock())
}

/// A restore is pending, nothing holds it and it isn't overdue: its decision is on its way
/// (commands wait for it).
pub(crate) fn deciding(hub: &HubState) -> bool {
    hub.reconnect.is_some() && hub.restore_holds == 0 && !overdue_in(hub)
}

/// A play (`true`) or pause (`false`) while a restore is pending: the restored playback starts
/// playing or paused. Returns the previous intent if a restore is pending (see [`answer`]).
pub(crate) fn set_intent(play: bool) -> Option<Option<Intent>> {
    set_intent_in(&mut HUB.lock(), play, Instant::now(), now_ms())
}

fn set_intent_in(hub: &mut HubState, play: bool, now: Instant, now_ms: i64) -> Option<Option<Intent>> {
    let f = hub.reconnect.as_mut()?;
    let prev = f.intent;
    f.intent = Some(Intent { play, since: now, at_ms: now_ms });
    Some(prev)
}

/// What a pending restore does with a play / pause, once the command waited for its decision.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Answer {
    /// It answers it: it comes back as asked once it decides (it is on its way, or held by a
    /// load in flight), or a pause (it comes back paused whenever it runs).
    Answered,
    /// The new Spirc's first cluster is overdue: a play restores right away.
    RestoreNow,
    /// Not answered, routed as usual: the restore point is gone (restored, skipped, replaced),
    /// or nothing will decide it soon (no session) and a play fails or falls back like any other.
    Routed,
}

#[derive(Debug, Clone, Copy)]
struct AnswerInput {
    pending: bool,
    /// Online with a Spirc attached.
    connected: bool,
    overdue: bool,
    play: bool,
    /// A pause without a session counts as answered (`control`), or not (`transfer`).
    offline_pause_answered: bool,
}

fn answer_for(i: AnswerInput) -> Answer {
    if !i.pending {
        return Answer::Routed;
    }
    if !i.connected {
        return if !i.play && i.offline_pause_answered { Answer::Answered } else { Answer::Routed };
    }
    if i.overdue && i.play { Answer::RestoreNow } else { Answer::Answered }
}

/// After a play / pause set the pending restore's intent (`prev`: what [`set_intent`] returned)
/// and waited for its decision: whether the restore answered the command. A play it can't answer
/// gets its intent put back, so that a failed play (no session) doesn't make an old restore
/// point play whenever the network returns.
pub(crate) fn answer(play: bool, prev: Option<Intent>, offline_pause_answered: bool) -> AppResult<bool> {
    let online = crate::engine::is_online();
    let (a, generation) = {
        let mut hub = HUB.lock();
        (answer_in(&mut hub, online, play, prev, offline_pause_answered), hub.link.as_ref().map(|l| l.generation))
    };
    match (a, generation) {
        (Answer::Answered, _) => Ok(true),
        (Answer::RestoreNow, Some(generation)) => {
            log::info!("the restore's first cluster is overdue, restoring on play");
            decide_and_restore(generation, true)
        }
        _ => Ok(false),
    }
}

/// [`answer`] on the state: a routed play gets the previous intent back.
fn answer_in(hub: &mut HubState, online: bool, play: bool, prev: Option<Intent>, offline_pause_answered: bool) -> Answer {
    let a = answer_for(AnswerInput {
        pending: hub.reconnect.is_some(),
        connected: online && hub.link.is_some(),
        overdue: overdue_in(hub),
        play,
        offline_pause_answered,
    });
    if a == Answer::Routed && play {
        if let Some(f) = hub.reconnect.as_mut() {
            f.intent = prev;
        }
    }
    a
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

/// Whether to restore now, after the new Spirc's first cluster (`without_cluster`: a play asked
/// for it while the cluster is overdue).
fn decide(hub: &mut HubState, me: &str, offline_active: bool, now: Instant, now_ms: i64, without_cluster: bool) -> Decision {
    let Some(frozen) = hub.reconnect.as_ref() else { return Decision::Cancelled };
    let cluster = hub.cluster.as_ref();
    if cluster.is_none() && !without_cluster {
        return Decision::NoCluster;
    }
    let other_active = cluster.is_some_and(|c| !c.active_device_id.is_empty() && c.active_device_id != me);
    if other_active || hub.snapshot.as_ref().is_some_and(|s| s.is_active) {
        return Decision::Skip("another device is active");
    }
    if offline_active {
        // Started during the outage (a paused offline queue still owns the Player).
        return Decision::Skip("offline playback is in progress");
    }
    let Some(plan) = plan(frozen, frozen.start_playing(now, now_ms)) else { return Decision::Skip("nothing to restore") };
    hub.reconnect = None;
    hub.last_active = None;
    Decision::Restore(plan)
}

/// Sends the restore to Spirc `generation`. Called while holding the hub, so that a command
/// routed meanwhile reaches Spirc after the restore, not before it. A full queue drops the rest
/// of the requeued tracks, not the skip and the play.
fn send_plan(hub: &mut HubState, generation: u64, spirc: &Spirc, plan: Plan) -> AppResult<()> {
    hub.activation = Some(hub::Activation { generation, at: Instant::now() });
    local::sent(spirc.activate())?;
    local::sent(spirc.load(plan.request))?;
    for uri in &plan.queued {
        match local::queue_add(spirc, uri) {
            Ok(()) => {}
            Err(e) if e.code == ErrorCode::NotConnected => return Err(e),
            Err(e) => {
                log::warn!("restore: {e}, the rest of the queue is dropped");
                break;
            }
        }
    }
    if plan.then_next {
        local::sent(spirc.next())?;
        if plan.then_repeat_track {
            local::sent(spirc.repeat_track(true))?;
        }
    }
    if plan.then_play {
        local::sent(spirc.play())?;
    }
    Ok(())
}

/// Decides the pending restore for the attached Spirc `generation` and sends it (see [`decide`]
/// for `without_cluster`). Returns whether it restored; a skipped restore point is cleared.
fn decide_and_restore(generation: u64, without_cluster: bool) -> AppResult<bool> {
    let me = hub::me();
    let offline_active = offline::is_active();
    let skip = {
        let mut hub = HUB.lock();
        let Some(spirc) = hub.link.as_ref().filter(|l| l.generation == generation).map(|l| l.spirc.clone()) else {
            return Ok(false); // superseded; a newer Spirc schedules its own restore
        };
        match decide(&mut hub, &me, offline_active, Instant::now(), now_ms(), without_cluster) {
            Decision::Cancelled | Decision::NoCluster => return Ok(false),
            Decision::Skip(reason) => reason,
            Decision::Restore(plan) => {
                log::info!("restoring local playback after reconnect");
                let result = send_plan(&mut hub, generation, &spirc, plan);
                drop(hub);
                if let Err(e) = &result {
                    log::warn!("restore failed: {e}");
                }
                hub::changed();
                hub::publish();
                return result.map(|_| true);
            }
        }
    };
    log::info!("not restoring playback: {skip}");
    clear();
    Ok(false)
}

/// Called once the Spirc `generation` is online after a reconnect. Waits for its first cluster as
/// long as the Spirc is attached and the restore point pending (both changes wake it); past
/// [`FIRST_CLUSTER_MAX`] the restore is overdue.
pub(crate) fn schedule(generation: u64) {
    if HUB.lock().reconnect.is_none() {
        return;
    }
    crate::runtime::handle().spawn(async move {
        let overdue_at = tokio::time::Instant::now() + FIRST_CLUSTER_MAX;
        let mut overdue = false;
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
                    return; // restored on play, or replaced meanwhile
                }
                if hub.cluster.is_some() && hub.restore_holds == 0 {
                    break;
                }
            }
            if overdue {
                notified.await;
            } else if tokio::time::timeout_at(overdue_at, notified).await.is_err() {
                // Without a cluster another device may have taken over: never restore blind. A
                // late cluster still decides it; meanwhile commands don't wait for it and a play
                // restores right away.
                log::warn!("no cluster from spirc {generation} yet, the restore waits for a play or the cluster");
                overdue = true;
                HUB.lock().restore_overdue = Some(generation);
                hub::changed();
            }
        }
        let _ = decide_and_restore(generation, false);
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

    /// Whether `f` starts playing `secs` after it was frozen (both clocks advancing alike).
    fn playing_after(f: &Frozen, secs: u64) -> bool {
        f.start_playing(f.since + Duration::from_secs(secs), f.at_ms + secs as i64 * 1000)
    }

    fn intent(play: bool, f: &Frozen) -> Option<Intent> {
        Some(Intent { play, since: f.since, at_ms: f.at_ms })
    }

    fn dec(hub: &mut HubState, offline_active: bool) -> Decision {
        let (since, at_ms) = hub.reconnect.as_ref().map(|f| (f.since, f.at_ms)).unwrap_or((Instant::now(), 0));
        decide(hub, "me", offline_active, since + Duration::from_secs(1), at_ms + 1000, false)
    }

    #[test]
    fn plan_context_restore() {
        let f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_002_000, Instant::now());
        assert!(f.was_playing);
        let p = plan(&f, playing_after(&f, 5)).expect("plan");
        assert!(p.request.start_playing);
        assert_eq!(p.request.seek_to, 12_000);
        assert!(matches!(p.request.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:cur"));
        assert!(matches!(p.request.context_options, Some(LoadContextOptions::Options(ref o)) if o.shuffle));
        assert!(format!("{:?}", p.request).contains("Uri(\"spotify:album:a\")"));
        assert_eq!(p.queued, vec!["spotify:track:q".to_string()]);
        // a long gap restores paused
        let p = plan(&f, playing_after(&f, 600)).expect("plan");
        assert!(!p.request.start_playing);
    }

    #[test]
    fn the_gap_counts_the_time_the_phone_slept() {
        let t0 = Instant::now();
        let f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_000_000, t0);
        assert!(f.start_playing(t0 + Duration::from_secs(5), 1_005_000));
        // the monotonic clock counted 30 s while 45 minutes passed (suspended in a bag)
        let (now, now_ms) = (t0 + Duration::from_secs(30), 1_000_000 + 45 * 60_000);
        assert!(!f.start_playing(now, now_ms));
        assert!(f.age(now, now_ms) >= Duration::from_secs(45 * 60), "the placeholder goes too");
        // a wall clock set back: a long gap, never auto-played
        assert!(!f.start_playing(t0 + Duration::from_secs(5), 1_000_000 - 10 * 60_000));
        // a wall clock off by less than the slack doesn't matter
        assert!(f.start_playing(t0 + Duration::from_secs(5), 999_500));
    }

    #[test]
    fn a_play_intent_only_counts_for_a_while() {
        let t0 = Instant::now();
        let mut f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_000_000, t0);
        f.was_playing = false;
        f.intent = Some(Intent { play: true, since: t0, at_ms: 1_000_000 });
        assert!(f.start_playing(t0 + Duration::from_secs(10), 1_010_000));
        // a play long ago falls back to the gap rule (paused before), also across a sleep
        assert!(!f.start_playing(t0 + Duration::from_secs(40), 1_040_000));
        assert!(!f.start_playing(t0 + Duration::from_secs(10), 1_000_000 + 3_600_000));
        // a pause always counts
        f.was_playing = true;
        f.intent = Some(Intent { play: false, since: t0, at_ms: 1_000_000 });
        assert!(!f.start_playing(t0 + Duration::from_secs(10), 1_010_000));
        assert!(!f.start_playing(t0 + Duration::from_secs(100), 1_100_000));
    }

    #[test]
    fn a_play_that_cant_be_answered_keeps_no_intent() {
        // pending during an outage (no session): the play fails / falls back, its intent goes
        let mut hub = frozen_hub();
        let f = hub.reconnect.clone().unwrap();
        let prev = set_intent_in(&mut hub, true, f.since, f.at_ms).expect("pending");
        assert_eq!(prev, None);
        assert_eq!(answer_in(&mut hub, false, true, prev, true), Answer::Routed);
        assert_eq!(hub.reconnect.as_ref().unwrap().intent, None);
        // an earlier pause stays
        let prev = set_intent_in(&mut hub, false, f.since, f.at_ms).expect("pending");
        assert_eq!(answer_in(&mut hub, false, false, prev, true), Answer::Answered, "a pause needs no session");
        let prev = set_intent_in(&mut hub, true, f.since, f.at_ms).expect("pending");
        assert_eq!(answer_in(&mut hub, false, true, prev, true), Answer::Routed);
        assert!(hub.reconnect.as_ref().unwrap().intent.is_some_and(|i| !i.play));
        // a transfer's pause isn't answered without a session (NOT_CONNECTED), the intent stays
        let prev = set_intent_in(&mut hub, false, f.since, f.at_ms).expect("pending");
        assert_eq!(answer_in(&mut hub, false, false, prev, false), Answer::Routed);
        assert!(hub.reconnect.as_ref().unwrap().intent.is_some_and(|i| !i.play));
        // nothing pending: routed, nothing recorded
        hub.reconnect = None;
        assert_eq!(set_intent_in(&mut hub, true, f.since, f.at_ms), None);
    }

    #[test]
    fn commands_and_the_restore() {
        let i = AnswerInput { pending: true, connected: true, overdue: false, play: true, offline_pause_answered: true };
        // deciding (or held by a load in flight): it comes back as asked
        assert_eq!(answer_for(i), Answer::Answered);
        assert_eq!(answer_for(AnswerInput { play: false, ..i }), Answer::Answered);
        // the first cluster is overdue: a play restores now, a pause is recorded
        assert_eq!(answer_for(AnswerInput { overdue: true, ..i }), Answer::RestoreNow);
        assert_eq!(answer_for(AnswerInput { overdue: true, play: false, ..i }), Answer::Answered);
        // gone (restored, skipped, replaced): routed
        assert_eq!(answer_for(AnswerInput { pending: false, ..i }), Answer::Routed);
        // no session
        assert_eq!(answer_for(AnswerInput { connected: false, ..i }), Answer::Routed);
    }

    #[test]
    fn an_overdue_restore_runs_on_play_without_the_cluster() {
        let mut hub = frozen_hub();
        hub.cluster = None;
        let (since, at_ms) = { let f = hub.reconnect.as_ref().unwrap(); (f.since, f.at_ms) };
        let now = since + Duration::from_secs(70);
        assert!(matches!(decide(&mut hub, "me", false, now, at_ms + 70_000, false), Decision::NoCluster));
        hub.reconnect.as_mut().unwrap().intent = Some(Intent { play: true, since: now, at_ms: at_ms + 70_000 });
        let Decision::Restore(p) = decide(&mut hub, "me", false, now, at_ms + 70_000, true) else { panic!("restore") };
        assert!(p.request.start_playing);
        // a cluster that came meanwhile still decides
        let mut hub = frozen_hub();
        hub.cluster = cluster("speaker");
        assert!(matches!(decide(&mut hub, "me", false, now, at_ms + 70_000, true), Decision::Skip(_)));
    }

    #[test]
    fn plan_track_list_restore() {
        let f = freeze(snap("spotify:web-api", SpircProvider::Context), 1_000_000, Instant::now());
        let p = plan(&f, true).expect("plan");
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
        assert!(matches!(dec(&mut hub, false), Decision::NoCluster));
        assert!(hub.reconnect.is_some(), "kept for when the cluster comes (or the next Spirc)");
    }

    #[test]
    fn play_and_pause_while_pending_decide_how_it_comes_back() {
        let mut hub = frozen_hub();
        // a pause while pending: back paused, even after a short gap
        let f = hub.reconnect.clone().unwrap();
        hub.reconnect.as_mut().unwrap().intent = intent(false, &f);
        assert!(!playing_after(hub.reconnect.as_ref().unwrap(), 1));
        // a (recent) play: playing, even after a long gap
        let mut late = f.clone();
        late.intent = Some(Intent { play: true, since: f.since + Duration::from_secs(3590), at_ms: f.at_ms + 3_590_000 });
        assert!(playing_after(&late, 3600));
        hub.reconnect.as_mut().unwrap().intent = intent(true, &f);
        let Decision::Restore(p) = dec(&mut hub, false) else { panic!("restore") };
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
            let p = plan(&f, true).expect("plan");
            let dbg = format!("{:?}", p.request);
            assert!(dbg.contains("Uri(\"spotify:playlist:x\")"), "{dbg}");
            // loaded paused at the context track before it, the track requeued first and skipped to
            assert!(matches!(p.request.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:p"));
            assert!(!p.request.start_playing);
            assert_eq!(p.queued, vec!["spotify:track:cur".to_string(), "spotify:track:q".to_string()]);
            assert!(p.then_next && p.then_play, "{provider:?}");
            assert!(!p.then_repeat_track);
        }
        // repeat-one is turned on after the skip (which turns it off), not with the load
        let mut s = snap("spotify:playlist:x", SpircProvider::Queue);
        s.repeat_track = true;
        let p = plan(&freeze(s, 1_000_000, Instant::now()), true).expect("plan");
        assert!(p.then_repeat_track);
        assert!(matches!(p.request.context_options, Some(LoadContextOptions::Options(ref o)) if !o.repeat_track));
        // a full queue: the tail makes room for the current track, which comes first
        let mut s = snap("spotify:playlist:x", SpircProvider::Queue);
        s.next_tracks = (0..QUEUE_MAX).map(|i| st(&format!("spotify:track:q{i}"), &format!("q{i}"), SpircProvider::Queue)).collect();
        let p = plan(&freeze(s, 1_000_000, Instant::now()), true).expect("plan");
        assert_eq!(p.queued.len(), QUEUE_MAX);
        assert_eq!(p.queued[0], "spotify:track:cur");
        assert_eq!(p.queued[QUEUE_MAX - 1], format!("spotify:track:q{}", QUEUE_MAX - 2));
        // without a track before it there is nothing to anchor the context at: the track list
        let mut s = snap("spotify:playlist:x", SpircProvider::Queue);
        s.prev_tracks.clear();
        let p = plan(&freeze(s, 1_000_000, Instant::now()), true).expect("plan");
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
        // a plain track list: the visible window, from the current track on
        let window: Vec<String> =
            ["spotify:track:p", "spotify:track:cur", "spotify:track:q", "spotify:track:n"].map(String::from).to_vec();
        let f = freeze(snap("spotify:web-api", SpircProvider::Context), 1_000_000, Instant::now());
        let a = load_args(&f, true).expect("load");
        assert_eq!(a.track_uris.as_ref(), Some(&window));
        assert_eq!(a.start_index, Some(1));
        assert_eq!(a.shuffle, Some(false), "in play order");
        // a queued or suggested track isn't in the context: the same window, at its position
        for provider in [SpircProvider::Queue, SpircProvider::Suggestion] {
            let f = freeze(snap("spotify:playlist:x", provider), 1_002_000, Instant::now());
            let a = load_args(&f, true).expect("load");
            assert_eq!(a.context_uri, None);
            assert_eq!(a.start_uri, None);
            assert_eq!(a.track_uris.as_ref(), Some(&window));
            assert_eq!(a.start_index, Some(1));
            assert_eq!(a.position_ms, 12_000);
        }
    }

    #[test]
    fn offline_playback_started_during_the_outage_is_not_replaced() {
        let mut hub = frozen_hub();
        assert!(matches!(dec(&mut hub, true), Decision::Skip(_)));
        assert!(hub.reconnect.is_some(), "cleared (and the orphan stopped) by the caller");
        // without offline playback the restore takes the restore point
        assert!(matches!(dec(&mut hub, false), Decision::Restore(_)));
        assert!(hub.reconnect.is_none() && hub.last_active.is_none());
        // a restore point dropped meanwhile (explicit load) cancels it
        assert!(matches!(dec(&mut hub, false), Decision::Cancelled));
    }

    #[test]
    fn restore_skipped_when_another_device_took_over() {
        let mut hub = frozen_hub();
        hub.cluster = cluster("speaker");
        assert!(matches!(dec(&mut hub, false), Decision::Skip(_)));
        hub.cluster = cluster("me");
        assert!(matches!(dec(&mut hub, false), Decision::Restore(_)));
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
        let p = plan(f, playing_after(f, 5)).expect("plan");
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
        assert!(plan(&freeze(s, 0, Instant::now()), true).is_none());
    }
}
