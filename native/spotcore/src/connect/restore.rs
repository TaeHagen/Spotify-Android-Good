//! Restoring this device's playback after the engine had to rebuild the Session + Spirc
//! (network switch, lost AP connection, dead player). A new Spirc starts inactive and empty, so
//! without this every Wi-Fi ↔ cellular switch would silently end local playback.
//!
//! States (see `HubState`): **pending** (`reconnect` holds the frozen state, shown paused) →
//! **restoring** (activate + load sent to the new Spirc, `activation` set until its snapshot is
//! active) → done; or **abandoned** (`clear`: skipped, replaced, stopped; or its load failed,
//! see `hub::on_local_load_failed`: the app's own resume is the fallback then).
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
//!   [`intent`](set_intent) (whether it starts playing; not at all if it couldn't be answered,
//!   see [`answer`]; a play answered as handled counts for that Spirc's decision, which it makes
//!   at the latest when the first cluster is overdue, any other play only for
//!   [`PLAY_INTENT_MAX`]), other controls wait for the decision and then go to the restored
//!   Spirc, a load or transfer [`hold`]s the decision while it is routed and replaces the
//!   restore point only once it is known to be valid.
//! * While the restore is applied (sent, the Spirc not active with its track yet) the restore
//!   point is kept as [`Restoring`]: a connection lost meanwhile freezes it again.
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
use std::collections::HashSet;
use std::time::{Duration, Instant};

/// The new Spirc's first cluster normally comes well within this (its dealer start and the
/// announcement PUT are bounded by 30 s each). Without one the restore never runs on its own
/// (another device may have taken over); past this it is overdue (see the module docs).
const FIRST_CLUSTER_MAX: Duration = Duration::from_secs(60);
/// Playback only resumes playing automatically if the interruption was shorter than this.
const RESUME_PLAYING_MAX_GAP: Duration = Duration::from_secs(120);
/// A play pressed while the restore was pending counts for this long (a pause always counts).
const PLAY_INTENT_MAX: Duration = Duration::from_secs(30);
/// A play reported as handled while the restore was deciding counts for that Spirc's decision,
/// which comes at the latest when the first cluster is overdue; for at most this long (so that
/// a phone asleep in between still doesn't play hours later).
const ANSWERED_PLAY_MAX: Duration = FIRST_CLUSTER_MAX.saturating_add(super::CONNECTING_WAIT);
/// A restore being applied is kept this long at most (see [`Restoring`]).
pub(crate) const RESTORING_MAX: Duration = Duration::from_secs(60);
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
    /// The Spirc generation for which the command was reported as handled (see [`answer`]).
    pub answered: Option<u64>,
}

/// The restore point while its restore is applied: activate + load were sent to Spirc
/// `generation`, which isn't active with its track yet (that takes a few requests on a weak
/// link). Gone once it is, or it went inactive, or the restore point was dropped.
#[derive(Debug, Clone)]
pub(crate) struct Restoring {
    pub generation: u64,
    pub frozen: Frozen,
    pub at: Instant,
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

    /// Whether the restored playback, decided for Spirc `generation`, starts playing now: as a
    /// pause or a play asked while it was pending (a play answered for this Spirc, or a recent
    /// one), otherwise if it played and the interruption was short.
    fn start_playing(&self, now: Instant, now_ms: i64, generation: Option<u64>) -> bool {
        let resumes = || self.was_playing && self.age(now, now_ms) < RESUME_PLAYING_MAX_GAP;
        match self.intent {
            Some(i) if !i.play => false,
            Some(i) => {
                let max = if i.answered.is_some() && i.answered == generation { ANSWERED_PLAY_MAX } else { PLAY_INTENT_MAX };
                elapsed(i.since, i.at_ms, now, now_ms) < max || resumes()
            }
            None => resumes(),
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
        let Some(anchor) = anchor else {
            // Nothing of the context played before it: the context with the track in front
            // (Spirc plays a start track that isn't in the context before it).
            let request = LoadRequest::from_context_uri(
                s.context_uri.clone(),
                options(PlayingTrack::Uri(current.uri.clone()), start_playing, seek_to, options_of(s)),
            );
            return Some(Plan { request, queued, then_next: false, then_repeat_track: false, then_play: false });
        };
        let o = Options { repeat_track: false, ..options_of(s) };
        let request =
            LoadRequest::from_context_uri(s.context_uri.clone(), options(PlayingTrack::Uri(anchor.uri.clone()), false, 0, o));
        let mut requeue = vec![current.uri.clone()];
        requeue.extend(queued);
        requeue.truncate(QUEUE_MAX);
        return Some(Plan { request, queued: requeue, then_next: true, then_repeat_track: s.repeat_track, then_play: start_playing });
    }
    // A context that can't be loaded again (a plain track list) or autoplay: one pass of the
    // visible tracks (the user queue is queued again after it).
    let is_ctx = |t: &SnapshotTrack| matches!(t.provider, SpircProvider::Context | SpircProvider::Autoplay);
    let pass = one_pass(s, current, |_, _| true);
    let index = pass.tracks[..pass.start].iter().filter(|t| is_ctx(t)).count() as u32;
    let tracks: Vec<String> = pass
        .tracks
        .iter()
        .enumerate()
        .filter(|(i, t)| *i == pass.start || is_ctx(t))
        .map(|(_, t)| t.uri.clone())
        .collect();
    let request = LoadRequest::from_tracks(tracks, options(PlayingTrack::Index(index), start_playing, seek_to, options_of(s)));
    Some(Plan { request, queued, then_next: false, then_repeat_track: false, then_play: false })
}

/// One pass of a snapshot's context around its current track (see [`one_pass`]).
pub(crate) struct Pass<'a> {
    /// In play order; `tracks[start]` is the current track.
    pub tracks: Vec<&'a SnapshotTrack>,
    pub start: usize,
    /// The next track `take` refused before the context's end (its index in `next_tracks`).
    pub ended_at: Option<usize>,
}

/// The visible tracks around `s`'s current track in play order, one pass of its context. With
/// repeat-all the next tracks go on past the context's end (a delimiter, then the context
/// again), and so do the previous ones after a wrap: the previous side stops at the wrap, the
/// next side goes on into the next pass up to a track seen already, and those tracks (the start
/// of this pass that Spirc no longer keeps as previous tracks) go in front. A uid seen already
/// ends a side too (a later pass whose delimiter is out of sight). Hidden entries and other
/// delimiters are skipped. A track `take(track, next)` refuses ends its side (`next`: the next
/// side).
pub(crate) fn one_pass<'a>(
    s: &'a ConnectSnapshot,
    current: &'a SnapshotTrack,
    mut take: impl FnMut(&SnapshotTrack, bool) -> bool,
) -> Pass<'a> {
    let wraps = |t: &SnapshotTrack| s.repeat_context && t.uri == uri::DELIMITER_URI;
    let skipped = |t: &SnapshotTrack| t.hidden || t.uri == uri::DELIMITER_URI;
    let mut seen: HashSet<&'a str> = HashSet::new();
    let mut first_time = |t: &'a SnapshotTrack| t.uid.is_empty() || seen.insert(t.uid.as_str());
    first_time(current);
    let mut tracks = Vec::new();
    for t in s.prev_tracks.iter().rev() {
        if wraps(t) {
            break;
        }
        if skipped(t) {
            continue;
        }
        if !take(t, false) || !first_time(t) {
            break;
        }
        tracks.push(t);
    }
    tracks.reverse();
    let prev = tracks.len();
    tracks.push(current);
    let (mut head, mut wrapped, mut ended_at) = (Vec::new(), false, None);
    for (i, t) in s.next_tracks.iter().enumerate() {
        if wraps(t) {
            if wrapped {
                break;
            }
            wrapped = true;
            continue;
        }
        if skipped(t) {
            continue;
        }
        if !first_time(t) {
            break;
        }
        if !take(t, true) {
            ended_at = (!wrapped).then_some(i);
            break;
        }
        if wrapped { head.push(t) } else { tracks.push(t) }
    }
    let start = head.len() + prev;
    head.extend(tracks);
    Pass { tracks: head, start, ended_at }
}

/// The frozen session as a `player.load` (for another device: context, track, position, options).
/// A queued or suggested track isn't part of the context (the target would start the context's
/// first track at its position): then, like a plain track list, one pass of the visible tracks in
/// play order (previous tracks, the current one, the user queue and the next tracks).
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
    let mut next = 0;
    let pass = one_pass(s, current, |_, is_next| {
        next += usize::from(is_next);
        next <= HANDOVER_NEXT
    });
    let tracks = pass.tracks.iter().map(|t| t.uri.clone()).collect();
    Some(LoadArgs { track_uris: Some(tracks), start_index: Some(pass.start as u32), shuffle: Some(false), ..base })
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
    // (only the restore into the Spirc going away now, and not one that never took)
    let link = hub.link.as_ref().map(|l| l.generation);
    let restoring = hub
        .restoring
        .take()
        .filter(|r| link.is_none_or(|g| g == r.generation) && now.saturating_duration_since(r.at) < RESTORING_MAX);
    hub.reconnect = match (active, hub.last_active.take()) {
        (Some(s), _) => Some(freeze(s, now_ms, now)),
        (None, Some(last)) => {
            let ended = last.ended_at_ms.unwrap_or(now_ms).min(now_ms);
            let mut f = freeze(last.snap, ended, now);
            f.at_ms = now_ms;
            // An old restore point is never played automatically.
            if now_ms - ended > RESUME_PLAYING_MAX_GAP.as_millis() as i64 {
                f.was_playing = false;
            }
            Some(f)
        }
        // The restore was being applied (the Spirc wasn't active with its track yet): the same
        // restore point again, as it was (the Player is paused since the first interruption, so
        // its gap and position still hold).
        (None, None) => restoring.map(|r| r.frozen),
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
        // Whatever the attached Spirc published last: it can lag a handler that started the
        // Player (a resume or a remote transfer whose state put hangs).
        hub.link.is_some()
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

/// A restore point taken out for a transfer (see [`take`]).
pub(crate) struct Taken {
    pub frozen: Frozen,
    /// Its restore was being applied to the attached Spirc (see [`Restoring`]).
    pub applying: bool,
}

/// The attached Spirc `generation` reported a failed load of this phone: if the restore was being
/// applied to it, the restore point goes (the app's own resume loads its stored session instead,
/// see `hub::on_local_load_failed`). Returns whether it did.
pub(crate) fn load_failed_in(hub: &mut HubState, generation: u64) -> bool {
    if !hub.restoring.as_ref().is_some_and(|r| r.generation == generation) {
        return false;
    }
    log::warn!("the restore's load failed, not restoring");
    hub.restoring = None;
    hub.last_active = None;
    true
}

/// The pending restore point, or the one being applied, without touching it: a transfer of this
/// session to another device sends it there first (holding the decision), and only then drops it
/// ([`clear`]).
pub(crate) fn peek() -> Option<Taken> {
    peek_in(&HUB.lock())
}

fn peek_in(hub: &HubState) -> Option<Taken> {
    hub.reconnect
        .clone()
        .map(|frozen| Taken { frozen, applying: false })
        .or_else(|| hub.restoring.as_ref().map(|r| Taken { frozen: r.frozen.clone(), applying: true }))
}

fn drop_restore() {
    let (dropped, orphaned) = {
        let mut hub = HUB.lock();
        hub.last_active = None;
        hub.restoring = None;
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
    f.intent = Some(Intent { play, since: now, at_ms: now_ms, answered: None });
    Some(prev)
}

/// While the restore is applied (see [`Restoring`]): the play / pause a play, pause or toggle
/// means (a toggle on the paused placeholder means play, on the restored playback it toggles),
/// recorded on the restore point too, so that it comes back as asked if the connection drops
/// before the restore took. `None` otherwise.
pub(crate) fn while_restoring(cmd: &super::Ctl) -> Option<bool> {
    let mut hub = HUB.lock();
    let playing = hub.snapshot.as_ref().filter(|s| s.is_active).is_some_and(|s| {
        matches!(s.status, SnapshotPlayStatus::Playing | SnapshotPlayStatus::LoadingPlay)
    });
    let r = hub.restoring.as_mut()?;
    let play = match cmd {
        super::Ctl::Play => true,
        super::Ctl::Pause => false,
        super::Ctl::Toggle => !playing,
        _ => return None,
    };
    r.frozen.intent = Some(Intent { play, since: Instant::now(), at_ms: now_ms(), answered: None });
    Some(play)
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
    // In its network-loss grace the session still reads online, but no cluster (no decision)
    // can come, and a restore now couldn't load: not connected.
    let online = crate::engine::is_online() && crate::engine::network_available();
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

/// [`answer`] on the state: a routed play gets the previous intent back, a play answered on an
/// attached Spirc counts for that Spirc's decision (see [`Frozen::start_playing`]).
fn answer_in(hub: &mut HubState, online: bool, play: bool, prev: Option<Intent>, offline_pause_answered: bool) -> Answer {
    let generation = hub.link.as_ref().map(|l| l.generation);
    let connected = online && generation.is_some();
    let a = answer_for(AnswerInput {
        pending: hub.reconnect.is_some(),
        connected,
        overdue: overdue_in(hub),
        play,
        offline_pause_answered,
    });
    if let Some(f) = hub.reconnect.as_mut().filter(|_| play) {
        match a {
            Answer::Routed => f.intent = prev,
            Answer::Answered if connected => {
                if let Some(i) = f.intent.as_mut() {
                    i.answered = generation;
                }
            }
            _ => {}
        }
    }
    a
}

/// A play was answered for Spirc `generation` while its restore was deciding.
fn answered_play(hub: &HubState, generation: u64) -> bool {
    hub.reconnect.as_ref().and_then(|f| f.intent).is_some_and(|i| i.play && i.answered == Some(generation))
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
    /// Restore with this plan (the restore point was taken, it is kept as [`Restoring`]).
    Restore(Plan, Box<Frozen>),
}

/// Whether to restore now into Spirc `generation`, after its first cluster (`without_cluster`: a
/// play asked for it while the cluster is overdue).
fn decide(
    hub: &mut HubState,
    me: &str,
    offline_active: bool,
    (now, now_ms): (Instant, i64),
    generation: Option<u64>,
    without_cluster: bool,
) -> Decision {
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
    let Some(plan) = plan(frozen, frozen.start_playing(now, now_ms, generation)) else {
        return Decision::Skip("nothing to restore");
    };
    let Some(frozen) = hub.reconnect.take() else { return Decision::Cancelled };
    hub.last_active = None;
    Decision::Restore(plan, Box::new(frozen))
}

/// The restore of `frozen` into Spirc `generation` is sent (see [`Restoring`]).
fn begin_restoring(hub: &mut HubState, generation: u64, frozen: Frozen, now: Instant) {
    hub.restoring = Some(Restoring { generation, frozen, at: now });
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
        match decide(&mut hub, &me, offline_active, (Instant::now(), now_ms()), Some(generation), without_cluster) {
            Decision::Cancelled | Decision::NoCluster => return Ok(false),
            Decision::Skip(reason) => reason,
            Decision::Restore(plan, frozen) => {
                log::info!("restoring local playback after reconnect");
                begin_restoring(&mut hub, generation, *frozen, Instant::now());
                let result = send_plan(&mut hub, generation, &spirc, plan);
                if result.is_err() {
                    // The Spirc is gone already: the restore point stays for the next one.
                    hub.activation = None;
                    hub.reconnect = hub.restoring.take().map(|r| r.frozen);
                }
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
                // restores right away, also one that was answered as handled already.
                overdue = true;
                let play = {
                    let mut hub = HUB.lock();
                    hub.restore_overdue = Some(generation);
                    hub.restore_holds == 0 && answered_play(&hub, generation)
                };
                if play {
                    log::info!("no cluster from spirc {generation} yet, restoring for the play answered meanwhile");
                    let _ = decide_and_restore(generation, true);
                    return;
                }
                log::warn!("no cluster from spirc {generation} yet, the restore waits for a play or the cluster");
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
        f.start_playing(f.since + Duration::from_secs(secs), f.at_ms + secs as i64 * 1000, None)
    }

    fn intent(play: bool, f: &Frozen) -> Option<Intent> {
        Some(Intent { play, since: f.since, at_ms: f.at_ms, answered: None })
    }

    fn dec(hub: &mut HubState, offline_active: bool) -> Decision {
        let (since, at_ms) = hub.reconnect.as_ref().map(|f| (f.since, f.at_ms)).unwrap_or((Instant::now(), 0));
        decide(hub, "me", offline_active, (since + Duration::from_secs(1), at_ms + 1000), None, false)
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
        assert!(f.start_playing(t0 + Duration::from_secs(5), 1_005_000, None));
        // the monotonic clock counted 30 s while 45 minutes passed (suspended in a bag)
        let (now, now_ms) = (t0 + Duration::from_secs(30), 1_000_000 + 45 * 60_000);
        assert!(!f.start_playing(now, now_ms, None));
        assert!(f.age(now, now_ms) >= Duration::from_secs(45 * 60), "the placeholder goes too");
        // a wall clock set back: a long gap, never auto-played
        assert!(!f.start_playing(t0 + Duration::from_secs(5), 1_000_000 - 10 * 60_000, None));
        // a wall clock off by less than the slack doesn't matter
        assert!(f.start_playing(t0 + Duration::from_secs(5), 999_500, None));
    }

    #[test]
    fn a_play_intent_only_counts_for_a_while() {
        let t0 = Instant::now();
        let mut f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_000_000, t0);
        f.was_playing = false;
        f.intent = Some(Intent { play: true, since: t0, at_ms: 1_000_000, answered: None });
        assert!(f.start_playing(t0 + Duration::from_secs(10), 1_010_000, None));
        // a play long ago falls back to the gap rule (paused before), also across a sleep
        assert!(!f.start_playing(t0 + Duration::from_secs(40), 1_040_000, None));
        assert!(!f.start_playing(t0 + Duration::from_secs(10), 1_000_000 + 3_600_000, None));
        // a pause always counts
        f.was_playing = true;
        f.intent = Some(Intent { play: false, since: t0, at_ms: 1_000_000, answered: None });
        assert!(!f.start_playing(t0 + Duration::from_secs(10), 1_010_000, None));
        assert!(!f.start_playing(t0 + Duration::from_secs(100), 1_100_000, None));
    }

    #[test]
    fn a_play_answered_for_a_spirc_counts_for_its_decision() {
        // answered at +3 s while the new Spirc (7) decided, its first cluster came at +40 s
        let t0 = Instant::now();
        let mut f = freeze(snap("spotify:album:a", SpircProvider::Context), 1_000_000, t0);
        f.was_playing = false;
        f.intent = Some(Intent { play: true, since: t0, at_ms: 1_000_000, answered: Some(7) });
        let (now, now_ms) = (t0 + Duration::from_secs(37), 1_037_000);
        assert!(f.start_playing(now, now_ms, Some(7)));
        // not for another Spirc (a later reconnect), and not after a long sleep
        assert!(!f.start_playing(now, now_ms, Some(8)));
        assert!(!f.start_playing(now, now_ms, None));
        assert!(!f.start_playing(now, 1_000_000 + 3_600_000, Some(7)));
        // unstamped: the short limit
        f.intent = Some(Intent { play: true, since: t0, at_ms: 1_000_000, answered: None });
        assert!(!f.start_playing(now, now_ms, Some(7)));
    }

    #[test]
    fn a_routed_play_is_undone_an_answered_one_stamped() {
        let mut hub = frozen_hub();
        let f = hub.reconnect.clone().unwrap();
        // no Spirc attached (no session): routed, the previous intent is back
        let prev = set_intent_in(&mut hub, true, f.since, f.at_ms).expect("pending");
        assert_eq!(answer_in(&mut hub, true, true, prev, true), Answer::Routed);
        assert_eq!(hub.reconnect.as_ref().unwrap().intent, None);
        // answered without a Spirc to stamp (a pause offline): nothing stamped
        let prev = set_intent_in(&mut hub, false, f.since, f.at_ms).expect("pending");
        assert_eq!(answer_in(&mut hub, false, false, prev, true), Answer::Answered);
        assert!(hub.reconnect.as_ref().unwrap().intent.is_some_and(|i| !i.play && i.answered.is_none()));
        assert!(!answered_play(&hub, 7));
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
        let clock = (now, at_ms + 70_000);
        assert!(matches!(decide(&mut hub, "me", false, clock, Some(7), false), Decision::NoCluster));
        hub.reconnect.as_mut().unwrap().intent = Some(Intent { play: true, since: now, at_ms: at_ms + 70_000, answered: None });
        let Decision::Restore(p, _) = decide(&mut hub, "me", false, clock, Some(7), true) else { panic!("restore") };
        assert!(p.request.start_playing);
        // a cluster that came meanwhile still decides
        let mut hub = frozen_hub();
        hub.cluster = cluster("speaker");
        assert!(matches!(decide(&mut hub, "me", false, clock, Some(7), true), Decision::Skip(_)));
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
        late.intent =
            Some(Intent { play: true, since: f.since + Duration::from_secs(3590), at_ms: f.at_ms + 3_590_000, answered: None });
        assert!(playing_after(&late, 3600));
        hub.reconnect.as_mut().unwrap().intent = intent(true, &f);
        let Decision::Restore(p, _) = dec(&mut hub, false) else { panic!("restore") };
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
        // nothing of the context played before it: the context, the track played in front of it
        let mut s = snap("spotify:playlist:x", SpircProvider::Queue);
        s.prev_tracks.clear();
        let p = plan(&freeze(s, 1_000_000, Instant::now()), true).expect("plan");
        assert!(format!("{:?}", p.request).contains("Uri(\"spotify:playlist:x\")"));
        assert!(matches!(p.request.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:cur"));
        assert!(p.request.start_playing && p.request.seek_to == 10_000);
        assert!(!p.then_next);
    }

    fn uris(p: &Plan) -> String {
        format!("{:?}", p.request)
    }

    #[test]
    fn a_track_list_is_restored_as_one_pass() {
        use SpircProvider::Context as C;
        let delim = || SnapshotTrack { hidden: true, ..st(uri::DELIMITER_URI, "", C) };
        let t = |n: u32| st(&format!("spotify:track:{n}"), &format!("u{n}"), C);
        let list = |r: std::ops::RangeInclusive<u32>| r.map(|n| format!("spotify:track:{n}")).collect::<Vec<_>>();
        // repeat-all: the next tracks wrap into the list again
        let mut s = snap("spotify:web-api", C);
        s.repeat_context = true;
        s.prev_tracks = vec![t(1), t(2)];
        s.track = Some(t(3));
        s.next_tracks = vec![delim(), t(1), t(2), t(3), delim(), t(1)];
        let f = freeze(s.clone(), 1_000_000, Instant::now());
        let p = plan(&f, true).expect("plan");
        assert!(uris(&p).contains(&format!("Tracks({:?})", list(1..=3))), "{}", uris(&p));
        assert!(matches!(p.request.playing_track, Some(PlayingTrack::Index(2))));
        let a = load_args(&f, true).expect("load");
        assert_eq!((a.track_uris, a.start_index), (Some(list(1..=3)), Some(2)));
        // the wrap's delimiter scrolled out of the previous tracks: a uid seen already ends them
        s.prev_tracks = vec![t(2), t(3), t(1), t(2)];
        s.next_tracks = vec![];
        let p = plan(&freeze(s.clone(), 1_000_000, Instant::now()), true).expect("plan");
        assert!(uris(&p).contains(&format!("Tracks({:?})", list(1..=3))), "{}", uris(&p));
        // one track on repeat-all: once
        s.prev_tracks = vec![t(1), delim(), t(1), delim()];
        s.track = Some(t(1));
        s.next_tracks = vec![delim(), t(1), delim(), t(1)];
        let a = load_args(&freeze(s.clone(), 1_000_000, Instant::now()), true).expect("load");
        assert_eq!((a.track_uris, a.start_index), (Some(list(1..=1)), Some(0)));
        // the start of the pass that Spirc no longer keeps as previous tracks comes in front
        s.prev_tracks = (8..=17).map(t).collect();
        s.track = Some(t(18));
        s.next_tracks = [t(19), t(20), delim()].into_iter().chain((1..=20).map(t)).collect();
        let a = load_args(&freeze(s.clone(), 1_000_000, Instant::now()), true).expect("load");
        assert_eq!((a.track_uris, a.start_index), (Some(list(1..=20)), Some(17)));
        // without repeat-all a delimiter only separates the list from autoplay
        let mut s = snap("spotify:web-api", C);
        s.next_tracks = vec![t(4), delim(), st("spotify:track:auto", "a", SpircProvider::Autoplay)];
        let p = plan(&freeze(s, 1_000_000, Instant::now()), true).expect("plan");
        assert!(uris(&p).contains("\"spotify:track:4\", \"spotify:track:auto\""), "{}", uris(&p));
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
        assert!(matches!(dec(&mut hub, false), Decision::Restore(..)));
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
        assert!(matches!(dec(&mut hub, false), Decision::Restore(..)));
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

    /// The restore of `frozen_hub` decided and sent to Spirc 7.
    fn restoring_hub() -> (HubState, Frozen) {
        let mut hub = frozen_hub();
        let Decision::Restore(_, frozen) = dec(&mut hub, false) else { panic!("restore") };
        assert!(hub.reconnect.is_none());
        let now = frozen.since + Duration::from_secs(2);
        begin_restoring(&mut hub, 7, (*frozen).clone(), now);
        (hub, *frozen)
    }

    #[test]
    fn a_connection_lost_while_the_restore_is_applied_keeps_the_session() {
        let (mut hub, frozen) = restoring_hub();
        // the restored Spirc isn't active with its track yet when its connection drops too
        hub::apply_snapshot(&mut hub, ConnectSnapshot { is_active: true, ..Default::default() }, true, 1_005_000);
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), true, 1_006_000);
        freeze_restore_point(&mut hub, 1_007_000, frozen.since + Duration::from_secs(5));
        let again = hub.reconnect.as_ref().expect("frozen again");
        assert_eq!(again.position_ms, frozen.position_ms);
        assert_eq!(again.at_ms, frozen.at_ms, "the gap counts from the first interruption");
        assert!(again.was_playing);
        assert!(hub.restoring.is_none());
    }

    #[test]
    fn a_restore_that_took_is_frozen_from_its_playback() {
        let (mut hub, frozen) = restoring_hub();
        let mut live = snap("spotify:album:a", SpircProvider::Context);
        live.position_ms = 20_000;
        live.position_timestamp_ms = 1_010_000;
        hub::apply_snapshot(&mut hub, live, false, 1_010_000);
        assert!(hub.restoring.is_none());
        freeze_restore_point(&mut hub, 1_010_000, frozen.since + Duration::from_secs(10));
        assert_eq!(hub.reconnect.as_ref().expect("frozen").position_ms, 20_000);
    }

    #[test]
    fn a_failed_restore_load_drops_the_restore_point() {
        let (mut hub, frozen) = restoring_hub();
        assert!(!load_failed_in(&mut hub, 8), "another Spirc's restore");
        assert!(hub.restoring.is_some());
        assert!(load_failed_in(&mut hub, 7));
        assert!(hub.reconnect.is_none() && hub.restoring.is_none());
        // nothing comes back on a later reconnect (the app's own resume loads the session)
        freeze_restore_point(&mut hub, 1_010_000, frozen.since + Duration::from_secs(10));
        assert!(hub.reconnect.is_none());
    }

    #[test]
    fn a_restore_that_never_took_is_forgotten() {
        // taken over meanwhile (active, then deliberately inactive)
        let (mut hub, frozen) = restoring_hub();
        hub::apply_snapshot(&mut hub, ConnectSnapshot { is_active: true, ..Default::default() }, false, 1_005_000);
        hub::apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 1_006_000);
        assert!(hub.restoring.is_none());
        // a load that never made it active: gone after a while
        let (mut hub, _) = restoring_hub();
        freeze_restore_point(&mut hub, 1_100_000, frozen.since + RESTORING_MAX + Duration::from_secs(5));
        assert!(hub.reconnect.is_none() && hub.restoring.is_none());
        // a new link starts without it
        let (mut hub, _) = restoring_hub();
        hub::forget_previous_link(&mut hub);
        assert!(hub.restoring.is_none());
    }

    #[test]
    fn a_transfer_peeks_without_dropping() {
        let mut hub = frozen_hub();
        let t = peek_in(&hub).expect("pending");
        assert!(!t.applying);
        assert!(hub.reconnect.is_some(), "kept until the push went through");
        let (restoring, _) = restoring_hub();
        let t = peek_in(&restoring).expect("being applied");
        assert!(t.applying && restoring.restoring.is_some());
        hub.reconnect = None;
        assert!(peek_in(&hub).is_none());
    }

    #[test]
    fn nothing_to_restore_without_track() {
        let mut s = snap("spotify:album:a", SpircProvider::Context);
        s.track = None;
        assert!(plan(&freeze(s, 0, Instant::now()), true).is_none());
    }
}
