//! The OfflineController's queue model (docs/ARCHITECTURE.md §4.6): a context of downloaded
//! items with seeded, reversible shuffle, repeat context/track, a user queue and Spirc-like
//! prev/next semantics. Pure: it returns [`Action`]s that the driver applies to the Player, and
//! consumes player events, so it is fully unit-testable.

use super::snapshot::MAX_PREV;
use super::uri;
use crate::models::{
    ActiveDeviceRef, PlaybackContext, PlaybackRestrictions, PlaybackSnapshot, PlaybackSource, PlaybackStatus,
    PlaybackTrack, RepeatMode, TrackProvider,
};
use rand::rngs::SmallRng;
use rand::seq::SliceRandom;
use rand::SeedableRng;
use std::collections::{HashSet, VecDeque};

/// Below this position `prev` goes to the previous track, above it restarts the current one.
const PREV_RESTART_MS: u64 = 3000;
/// Upper bound of the next tracks put into a snapshot.
pub(crate) const MAX_NEXT: usize = super::snapshot::MAX_NEXT;
/// A window that ended playing this long ago at most goes on playing when the session is back
/// (the reconnect restore's rule).
const RESUME_PLAYING_MAX_GAP_MS: i64 = 120_000;
/// Shown when a play finds nothing more to play offline (see [`OfflineQueue::play`]).
pub(crate) const NOTHING_OFFLINE: &str = "Nothing more to play offline";

/// What the driver must do with the Player.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Action {
    Load { uri: String, play: bool, position_ms: u32 },
    Preload(String),
    Play,
    Pause,
    Seek(u32),
    Stop,
    /// Hand the playback back to Spirc (see [`HandBack`]).
    HandBack(HandBack),
}

/// Where handed-over playback continues in its context once its downloaded window ends (the
/// first track after it isn't downloaded): recorded at the handoff, see [`OfflineQueue::adopt`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Continuation {
    pub context_uri: String,
    /// `None`: the context's start (a new pass, see [`Adoption::restart`]).
    pub start_uri: Option<String>,
    pub smart_shuffle: bool,
    /// A shuffled session's order to keep (see `Options::shuffle_order`).
    pub order: Option<Vec<String>>,
    /// A plain track list (its context can't be loaded again): the rest of it as it was listed,
    /// in play order, loaded as a list (from its first track) instead of the context.
    pub tracks: Option<Vec<String>>,
}

/// Spirc loads the context at the continuation (online again): the end of the handed-over window
/// was reached while a visible session is up.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct HandBack {
    pub context_uri: String,
    /// `None`: the context's start.
    pub start_uri: Option<String>,
    pub play: bool,
    pub shuffle: bool,
    pub smart_shuffle: bool,
    pub repeat_context: bool,
    pub repeat_track: bool,
    /// See [`Continuation::order`].
    pub order: Option<Vec<String>>,
    /// See [`Continuation::tracks`].
    pub tracks: Option<Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Item {
    pub uri: String,
    pub uid: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Current {
    /// Index into `items`.
    Context(usize),
    Queue(Item),
}

/// Player events, reduced to what the queue needs (decoupled from librespot for tests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Event {
    RequestId(u64),
    Loading(u64),
    Playing { id: u64, position_ms: u32 },
    Paused { id: u64, position_ms: u32 },
    Position { id: u64, position_ms: u32 },
    Stopped(u64),
    TimeToPreload(u64),
    EndOfTrack(u64),
    Unavailable { id: u64, uri: String },
    TrackChanged { uri: String, duration_ms: u32 },
}

/// Result of feeding an event.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct Outcome {
    pub action: Option<Action>,
    /// The state visible in snapshots changed.
    pub changed: bool,
    /// Playback ended because nothing playable was left after an unavailable item.
    pub exhausted_after_error: bool,
}

/// The other Connect devices as a cluster shows them (see [`OfflineQueue::taken_over`]).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct Elsewhere {
    /// The cluster's active device, unless it is this one (`None`: nobody, or this device).
    pub device: Option<String>,
    /// ... and it plays.
    pub playing: bool,
}

impl Elsewhere {
    /// `now` took the session over from what this mark recorded: another device became active,
    /// or the marked one started playing.
    fn taken_over_by(&self, now: &Elsewhere) -> bool {
        now.device.is_some() && (now.device != self.device || (now.playing && !self.playing))
    }
}

#[derive(Debug, Clone)]
pub(crate) struct OfflineQueue {
    pub active: bool,
    context_uri: Option<String>,
    items: Vec<Item>,
    order: Vec<usize>,
    /// Position in `order` of the current (or last played) context item.
    pos: usize,
    queue: VecDeque<Item>,
    current: Option<Current>,
    shuffle: bool,
    seed: u64,
    repeat: RepeatMode,
    /// Repeat context is on (Spirc keeps it separately from repeat-track, which a manual skip
    /// turns off: then the mode falls back to this).
    repeat_context: bool,
    /// Whether the current item plays or is meant to (also while it loads: a paused load stays
    /// paused through next / unavailable).
    play_intent: bool,
    skipped: HashSet<usize>,
    unavailable: HashSet<String>,
    next_queue_id: u64,
    status: PlaybackStatus,
    position_ms: u64,
    position_ts: i64,
    duration_ms: u64,
    own_request: Option<u64>,
    /// Loads sent to the Player whose `PlayRequestIdChanged` hasn't arrived yet. Every
    /// `Player::load` produces exactly one new request id, in command order.
    pending_loads: u32,
    /// The latest cluster's view of the other devices (`None`: no cluster since the load).
    elsewhere: Option<Elsewhere>,
    /// The other devices when the queue first saw a cluster or last paused here (followed while
    /// nothing takes over): only a change since then is a takeover. Spotify keeps a paused
    /// device as the account's active one for hours, that one doesn't take over.
    takeover_mark: Option<Elsewhere>,
    /// The Player's latest request id, whoever loaded it (see [`OfflineQueue::adopt`]).
    last_request: Option<u64>,
    /// ... and its track ended while nobody (no active queue) handled the end.
    ended_request: Option<u64>,
    /// See [`Continuation`] (kept until a load or a shuffle toggle changes the window).
    continuation: Option<Continuation>,
    /// See [`Adoption::restart`].
    restart: Option<Continuation>,
    /// The handed-over window ended (stopped at its end) while no session could take it back,
    /// with the context going on after it ([`OfflineQueue::continuation`]): when (local epoch ms)
    /// and whether it played. A play or the session coming back hands back there (see
    /// [`OfflineQueue::resume_window_end`]) instead of replaying the window.
    window_ended: Option<(i64, bool)>,
    /// Said in the snapshot (`lastError`) until the next load: a play found nothing more to play
    /// offline.
    notice: Option<&'static str>,
    /// Items that aren't tracks of `context_uri` (see [`Adoption::outside`]).
    outside: Vec<usize>,
    /// The driver: a visible online session is up, the window's end hands back to Spirc.
    hand_back: bool,
    /// The speed the app's sink plays at, in thousandths (podcasts; see `connect::set_speed`):
    /// positions extrapolate with it. Kept across loads and resets.
    speed_milli: u64,
}

/// Playback the Player is already doing (Spirc's, on a downloaded track) that the queue takes
/// over as it is (see [`OfflineQueue::adopt`]).
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Adoption {
    pub context_uri: Option<String>,
    /// In play order; `uris[start]` is the track the Player has loaded.
    pub uris: Vec<String>,
    pub start: usize,
    /// Indices of `uris` that aren't tracks of the context (the user queue's, suggestions).
    pub outside: Vec<usize>,
    /// ... of which these are the user queue's: the ones after `start` are this queue's user
    /// queue again (the "Next in queue" entries, before later adds, cleared with it).
    pub user_queued: Vec<usize>,
    pub position_ms: u64,
    pub duration_ms: u64,
    /// It plays, or loads to play.
    pub playing: bool,
    /// It is still loading.
    pub loading: bool,
    pub repeat: RepeatMode,
    /// Repeat context is on (also under repeat-track).
    pub repeat_context: bool,
    pub shuffle: bool,
    /// Where the context continues after the window (see [`Continuation`]).
    pub continuation: Option<Continuation>,
    /// The context's start, where a new pass begins: a window end that wraps (repeat-all, also
    /// turned on after the handoff) hands back there instead of the queue wrapping a part of the
    /// context, unless the context goes on after the window ([`Adoption::continuation`]).
    pub restart: Option<Continuation>,
    /// `uris[start]` isn't downloaded: a streamed track whose data is all in the Player. It plays
    /// on (seeks within it too), but it is never loaded again offline: the queue doesn't go back
    /// to it (it counts as unavailable).
    pub streamed: bool,
}

impl Default for OfflineQueue {
    fn default() -> Self {
        Self {
            active: false,
            context_uri: None,
            items: Vec::new(),
            order: Vec::new(),
            pos: 0,
            queue: VecDeque::new(),
            current: None,
            shuffle: false,
            seed: 0,
            repeat: RepeatMode::Off,
            repeat_context: false,
            play_intent: false,
            skipped: HashSet::new(),
            unavailable: HashSet::new(),
            next_queue_id: 0,
            status: PlaybackStatus::Stopped,
            position_ms: 0,
            position_ts: 0,
            duration_ms: 0,
            own_request: None,
            pending_loads: 0,
            elsewhere: None,
            takeover_mark: None,
            last_request: None,
            ended_request: None,
            continuation: None,
            restart: None,
            window_ended: None,
            notice: None,
            outside: Vec::new(),
            hand_back: false,
            speed_milli: 1000,
        }
    }
}

/// Load parameters (items already filtered to downloaded ones).
#[derive(Debug, Clone)]
pub(crate) struct LoadSpec {
    pub context_uri: Option<String>,
    pub uris: Vec<String>,
    /// The start item; `None`: none was asked for (a shuffle starts anywhere).
    pub start: Option<usize>,
    pub position_ms: u64,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub play: bool,
    pub seed: u64,
}

fn shuffled(len: usize, seed: u64) -> Vec<usize> {
    let mut order: Vec<usize> = (0..len).collect();
    let mut rng = SmallRng::seed_from_u64(seed);
    order.shuffle(&mut rng);
    order
}

fn shuffled_order(len: usize, first: usize, seed: u64) -> Vec<usize> {
    let mut order = shuffled(len, seed);
    if let Some(p) = order.iter().position(|&i| i == first) {
        order.swap(0, p);
    }
    order
}

impl OfflineQueue {
    #[cfg(test)]
    pub fn status(&self) -> PlaybackStatus {
        self.status
    }

    /// Stopped (the end, a halt, a dead Player) with an item a play starts again.
    pub fn stopped_with_item(&self) -> bool {
        self.active && self.status == PlaybackStatus::Stopped && self.current.is_some()
    }

    /// Playing, or loading to play.
    pub fn is_playing(&self) -> bool {
        self.status == PlaybackStatus::Playing || (self.status == PlaybackStatus::Loading && self.play_intent)
    }

    /// Records a cluster's view of the other devices. The mark follows it while nothing takes
    /// over, so a marked device that pauses and later resumes does take over.
    pub fn observe(&mut self, now: Elsewhere) {
        if !self.takeover_mark.as_ref().is_some_and(|m| m.taken_over_by(&now)) {
            self.takeover_mark = Some(now.clone());
        }
        self.elsewhere = Some(now);
    }

    /// Another device took the session over since the queue first saw a cluster or last paused
    /// here: a device became active, or the active one started playing. Not with no cluster seen.
    pub fn taken_over(&self) -> bool {
        match (&self.takeover_mark, &self.elsewhere) {
            (Some(mark), Some(now)) => mark.taken_over_by(now),
            _ => false,
        }
    }

    /// A paused or finished queue that another device took over gives way to it (a playing one
    /// keeps playing until something takes the Player over).
    pub fn gives_way(&self) -> bool {
        self.active && !self.is_playing() && self.taken_over()
    }

    /// Paused here: whatever the other devices do right now is no takeover (a pause on this
    /// device never hands the session away, e.g. to a device that played meanwhile).
    fn paused_here(&mut self) {
        self.takeover_mark = self.elsewhere.clone();
    }

    pub fn current_uri(&self) -> Option<&str> {
        match self.current.as_ref()? {
            Current::Context(i) => self.items.get(*i).map(|it| it.uri.as_str()),
            Current::Queue(item) => Some(item.uri.as_str()),
        }
    }

    fn current_item(&self) -> Option<Item> {
        match self.current.as_ref()? {
            Current::Context(i) => self.items.get(*i).cloned(),
            Current::Queue(item) => Some(item.clone()),
        }
    }

    /// The speed the sink plays at (see `speed_milli`); the position is re-anchored at `now_ms`.
    pub fn set_speed(&mut self, speed: f64, now_ms: i64) {
        let milli = (speed * 1000.0).round().clamp(1.0, 10_000.0) as u64;
        if milli == self.speed_milli {
            return;
        }
        if self.status == PlaybackStatus::Playing {
            self.position_ms = self.position_at(now_ms);
            self.position_ts = now_ms;
        }
        self.speed_milli = milli;
    }

    /// Position extrapolated to `now_ms` (at the playback speed).
    pub fn position_at(&self, now_ms: i64) -> u64 {
        if self.status == PlaybackStatus::Playing {
            let elapsed = (now_ms - self.position_ts).max(0) as u64 * self.speed_milli / 1000;
            let p = self.position_ms + elapsed;
            if self.duration_ms > 0 { p.min(self.duration_ms) } else { p }
        } else {
            self.position_ms
        }
    }

    fn playable(&self, idx: usize) -> bool {
        !self.skipped.contains(&idx) && self.items.get(idx).is_some_and(|it| !self.unavailable.contains(&it.uri))
    }

    /// Next context position in `order` after `from` (wrapping with repeat context, also under
    /// repeat-track entered from it, like Spirc).
    fn next_context_pos(&self, from: Option<usize>) -> Option<usize> {
        let start = from.map(|p| p + 1).unwrap_or(0);
        if let Some(p) = (start..self.order.len()).find(|&p| self.playable(self.order[p])) {
            return Some(p);
        }
        if self.repeat_context {
            return (0..self.order.len()).find(|&p| self.playable(self.order[p]));
        }
        None
    }

    fn prev_context_pos(&self, from: usize) -> Option<usize> {
        if let Some(p) = (0..from).rev().find(|&p| self.playable(self.order[p])) {
            return Some(p);
        }
        if self.repeat_context {
            return (from + 1..self.order.len()).rev().find(|&p| self.playable(self.order[p]));
        }
        None
    }

    fn begin_load(&mut self, uri: String, play: bool, position_ms: u64, now_ms: i64) -> Action {
        self.window_ended = None;
        self.notice = None;
        self.pending_loads = self.pending_loads.saturating_add(1);
        self.status = PlaybackStatus::Loading;
        self.play_intent = play;
        self.position_ms = position_ms;
        self.position_ts = now_ms;
        self.duration_ms = 0;
        Action::Load { uri, play, position_ms: position_ms.min(u32::MAX as u64) as u32 }
    }

    pub fn load(&mut self, spec: LoadSpec, now_ms: i64) -> Option<Action> {
        if spec.uris.is_empty() {
            return None;
        }
        let items: Vec<Item> =
            spec.uris.iter().enumerate().map(|(i, u)| Item { uri: u.clone(), uid: format!("o{i}") }).collect();
        let order = match (spec.shuffle, spec.start) {
            (true, Some(start)) => shuffled_order(items.len(), start.min(items.len() - 1), spec.seed),
            // No start asked for: a shuffle starts anywhere (like Spirc).
            (true, None) => shuffled(items.len(), spec.seed),
            (false, _) => (0..items.len()).collect(),
        };
        let start = spec.start.map(|s| s.min(items.len() - 1)).unwrap_or(order[0]);
        // The user queue survives a load, like Spirc's (a queue left by a superseded session
        // doesn't: it was reset, or another controller took the Player over).
        let queue = if self.active { std::mem::take(&mut self.queue) } else { VecDeque::new() };
        *self = OfflineQueue {
            active: true,
            context_uri: spec.context_uri,
            order,
            items,
            queue,
            shuffle: spec.shuffle,
            seed: spec.seed,
            repeat: spec.repeat,
            repeat_context: spec.repeat == RepeatMode::Context,
            next_queue_id: self.next_queue_id,
            pending_loads: self.pending_loads,
            last_request: self.last_request,
            ended_request: self.ended_request,
            speed_milli: self.speed_milli,
            ..Default::default()
        };
        self.pos = self.order.iter().position(|&i| i == start).unwrap_or(0);
        self.current = Some(Current::Context(start));
        let uri = self.items[start].uri.clone();
        Some(self.begin_load(uri, spec.play, spec.position_ms, now_ms))
    }

    /// Starts the given context position.
    fn play_context_pos(&mut self, p: usize, play: bool, now_ms: i64) -> Action {
        self.pos = p;
        let idx = self.order[p];
        self.current = Some(Current::Context(idx));
        let uri = self.items[idx].uri.clone();
        self.begin_load(uri, play, 0, now_ms)
    }

    fn stop_at_end(&mut self) -> Action {
        self.status = PlaybackStatus::Stopped;
        self.play_intent = false;
        self.position_ms = 0;
        Action::Stop
    }

    /// A manual skip leaves repeat-track (like Spirc): back to repeat-context or off.
    fn leave_repeat_track(&mut self) {
        if self.repeat == RepeatMode::Track {
            self.repeat = if self.repeat_context { RepeatMode::Context } else { RepeatMode::Off };
        }
    }

    /// `auto`: end of track (repeat-track applies); otherwise a user skip.
    fn advance(&mut self, auto: bool, play: bool, now_ms: i64) -> Action {
        if auto && self.repeat == RepeatMode::Track {
            if let Some(item) = self.current_item() {
                return self.begin_load(item.uri, play, 0, now_ms);
            }
        }
        if let Some(item) = self.queue.pop_front() {
            let uri = item.uri.clone();
            self.current = Some(Current::Queue(item));
            return self.begin_load(uri, play, 0, now_ms);
        }
        let from = if self.order.is_empty() { None } else { Some(self.pos) };
        let next = self.next_context_pos(from);
        let goes_on = self.goes_on_after().cloned();
        if let Some(c) = goes_on.as_ref().filter(|_| self.hand_back) {
            return self.back_to_spirc(c, play);
        }
        match next {
            Some(p) => self.play_context_pos(p, play, now_ms),
            None => {
                // The window's end without a session to take it back: kept for when it is back.
                if goes_on.is_some() {
                    self.window_ended = Some((now_ms, play));
                }
                self.stop_at_end()
            }
        }
    }

    /// Where Spirc goes on (online, see [`OfflineQueue::set_hand_back`]) if the queue advances now:
    /// no user queue comes first, the handed-over window ends here (it would stop, or wrap with
    /// repeat-all, as it is now), and the context goes on after the window, or at its start for a
    /// new pass.
    fn goes_on_after(&self) -> Option<&Continuation> {
        if !self.queue.is_empty() {
            return None;
        }
        let from = if self.order.is_empty() { None } else { Some(self.pos) };
        let next = self.next_context_pos(from);
        let wraps = next.is_some_and(|p| from.is_some_and(|f| p <= f));
        if next.is_none() || wraps {
            self.continuation.as_ref().or(self.restart.as_ref().filter(|_| wraps))
        } else {
            None
        }
    }

    /// The queue itself can go on at its window's end: a user queue, or the window again with
    /// repeat-all.
    fn goes_on_here(&self) -> bool {
        !self.queue.is_empty() || (self.repeat_context && self.next_context_pos(Some(self.pos)).is_some())
    }

    /// Spirc goes on with the context at `c` (see [`HandBack`]).
    fn back_to_spirc(&mut self, c: &Continuation, play: bool) -> Action {
        let back = HandBack {
            context_uri: c.context_uri.clone(),
            start_uri: c.start_uri.clone(),
            play,
            shuffle: self.shuffle,
            smart_shuffle: c.smart_shuffle,
            repeat_context: self.repeat_context,
            repeat_track: self.repeat == RepeatMode::Track,
            order: c.order.clone(),
            tracks: c.tracks.clone(),
        };
        self.window_ended = None;
        self.notice = None;
        self.status = PlaybackStatus::Loading;
        self.play_intent = play;
        Action::HandBack(back)
    }

    /// The window ended while no session could take it back ([`OfflineQueue::window_ended`]),
    /// and now one can (the driver set [`OfflineQueue::set_hand_back`]): Spirc goes on after it,
    /// playing if it played and ended less than [`RESUME_PLAYING_MAX_GAP_MS`] ago, else paused.
    pub fn resume_window_end(&mut self, now_ms: i64) -> Option<Action> {
        let (ended, played) = self.window_ended?;
        if !self.active || !self.hand_back || self.status != PlaybackStatus::Stopped {
            return None;
        }
        let play = played && now_ms.saturating_sub(ended) < RESUME_PLAYING_MAX_GAP_MS;
        if self.goes_on_here() {
            // The user queue first (online it streams), Spirc goes on at its end.
            self.window_ended = None;
            return Some(self.advance(false, play, now_ms));
        }
        let c = self.continuation.clone()?;
        Some(self.back_to_spirc(&c, play))
    }

    /// Whether the end of a handed-over window hands back to Spirc (see [`Continuation`]).
    pub fn set_hand_back(&mut self, allowed: bool) {
        self.hand_back = allowed;
    }

    /// The hand-back couldn't be sent: the window goes on (wraps) or stops as without one.
    pub fn hand_back_failed(&mut self, auto: bool, now_ms: i64) -> Action {
        self.continuation = None;
        self.restart = None;
        self.window_ended = None;
        let play = self.play_intent;
        self.advance(auto, play, now_ms)
    }

    pub fn play(&mut self, now_ms: i64) -> Option<Action> {
        match self.status {
            PlaybackStatus::Paused => {
                self.status = PlaybackStatus::Playing;
                self.play_intent = true;
                self.position_ts = now_ms;
                Some(Action::Play)
            }
            PlaybackStatus::Stopped if self.window_ended.is_some() => {
                if self.goes_on_here() {
                    // A user queue (added since), or the window again with repeat-all.
                    self.window_ended = None;
                    return Some(self.advance(false, true, now_ms));
                }
                // At the window's end the context goes on in Spirc, nothing replays the window.
                match self.continuation.clone().filter(|_| self.hand_back) {
                    Some(c) => Some(self.back_to_spirc(&c, true)),
                    None => {
                        self.notice = Some(NOTHING_OFFLINE);
                        None
                    }
                }
            }
            PlaybackStatus::Stopped => {
                // At the kept position (0 after the end; where it was when the Player died).
                let item = self.current_item()?;
                let position_ms = self.position_ms;
                Some(self.begin_load(item.uri, true, position_ms, now_ms))
            }
            PlaybackStatus::Loading | PlaybackStatus::Playing => {
                self.play_intent = true;
                Some(Action::Play)
            }
        }
    }

    pub fn pause(&mut self, now_ms: i64) -> Option<Action> {
        match self.status {
            PlaybackStatus::Playing => {
                self.position_ms = self.position_at(now_ms);
                self.status = PlaybackStatus::Paused;
                self.play_intent = false;
                self.position_ts = now_ms;
                self.paused_here();
                Some(Action::Pause)
            }
            PlaybackStatus::Loading => {
                self.play_intent = false;
                self.paused_here();
                Some(Action::Pause)
            }
            _ => None,
        }
    }

    pub fn toggle(&mut self, now_ms: i64) -> Option<Action> {
        if self.is_playing() { self.pause(now_ms) } else { self.play(now_ms) }
    }

    pub fn next(&mut self, now_ms: i64) -> Option<Action> {
        self.current.as_ref()?;
        self.leave_repeat_track();
        let play = self.play_intent;
        Some(self.advance(false, play, now_ms))
    }

    pub fn prev(&mut self, now_ms: i64) -> Option<Action> {
        self.current.as_ref()?;
        let play = self.play_intent;
        if self.position_at(now_ms) > PREV_RESTART_MS {
            return self.seek(0, now_ms);
        }
        match self.current {
            // From a queued track back to the context track that was playing before it.
            Some(Current::Queue(_)) if !self.order.is_empty() && self.playable(self.order[self.pos]) => {
                let p = self.pos;
                Some(self.play_context_pos(p, play, now_ms))
            }
            _ => match self.prev_context_pos(self.pos) {
                Some(p) if !self.order.is_empty() => Some(self.play_context_pos(p, play, now_ms)),
                _ => self.seek(0, now_ms),
            },
        }
    }

    pub fn seek(&mut self, position_ms: u64, now_ms: i64) -> Option<Action> {
        self.current.as_ref()?;
        self.position_ms = position_ms;
        self.position_ts = now_ms;
        Some(Action::Seek(position_ms.min(u32::MAX as u64) as u32))
    }

    /// Enables (new seed, current item first) or disables (original order) shuffle.
    pub fn set_shuffle(&mut self, enabled: bool, seed: u64) {
        // Another order: the window no longer ends where its context continues.
        self.continuation = None;
        self.window_ended = None;
        if self.items.is_empty() {
            self.shuffle = enabled;
            return;
        }
        let anchor = match self.current {
            Some(Current::Context(i)) => i,
            _ => self.order.get(self.pos).copied().unwrap_or(0),
        };
        if enabled {
            self.seed = seed;
            self.order = shuffled_order(self.items.len(), anchor, seed);
            self.pos = 0;
        } else {
            self.order = (0..self.items.len()).collect();
            self.pos = anchor;
        }
        self.shuffle = enabled;
    }

    pub fn set_repeat(&mut self, mode: RepeatMode) {
        match mode {
            RepeatMode::Off => self.repeat_context = false,
            RepeatMode::Context => self.repeat_context = true,
            // like Spirc's repeat-track flag, on top of the context flag
            RepeatMode::Track => {}
        }
        self.repeat = mode;
    }

    /// Adds to the user queue; false (not added) when it already holds [`MAX_NEXT`] entries,
    /// the same limit as Spirc's next tracks.
    pub fn add_to_queue(&mut self, uri: String) -> bool {
        if self.queue.len() >= MAX_NEXT {
            return false;
        }
        let uid = format!("q{}", self.next_queue_id);
        self.next_queue_id += 1;
        self.queue.push_back(Item { uri, uid });
        true
    }

    fn context_index_of(&self, uid: &str) -> Option<usize> {
        let idx: usize = uid.strip_prefix('o')?.parse().ok()?;
        (idx < self.items.len()).then_some(idx)
    }

    fn is_upcoming_context(&self, idx: usize) -> bool {
        if matches!(self.current, Some(Current::Context(c)) if c == idx) {
            return false;
        }
        self.order.iter().position(|&i| i == idx).is_some_and(|p| p > self.pos || self.repeat_context)
    }

    /// Removes a queued entry or skips an upcoming context entry. False if `uid` isn't upcoming.
    pub fn remove(&mut self, uid: &str) -> bool {
        if let Some(p) = self.queue.iter().position(|q| q.uid == uid) {
            self.queue.remove(p);
            return true;
        }
        match self.context_index_of(uid) {
            Some(idx) if self.is_upcoming_context(idx) => {
                self.skipped.insert(idx);
                true
            }
            _ => false,
        }
    }

    /// Moves `uid` to `to` in the displayed next tracks (queue first). Context entries can only
    /// be moved into the queue. Err = message.
    pub fn move_item(&mut self, uid: &str, to: usize) -> Result<(), &'static str> {
        if let Some(p) = self.queue.iter().position(|q| q.uid == uid) {
            let item = self.queue.remove(p).ok_or("no such entry")?;
            let to = to.min(self.queue.len());
            self.queue.insert(to, item);
            return Ok(());
        }
        let idx = self.context_index_of(uid).filter(|&i| self.is_upcoming_context(i)).ok_or("no such entry")?;
        if to > self.queue.len() {
            return Err("context tracks can only be moved into the queue");
        }
        self.skipped.insert(idx);
        let uri = self.items[idx].uri.clone();
        let uid = format!("q{}", self.next_queue_id);
        self.next_queue_id += 1;
        self.queue.insert(to, Item { uri, uid });
        Ok(())
    }

    pub fn clear_queue(&mut self) {
        self.queue.clear();
    }

    /// Jumps to an upcoming entry. Queued entries before it are dropped; skipping to a context
    /// entry keeps the queue.
    pub fn skip_to(&mut self, uid: &str, now_ms: i64) -> Option<Action> {
        let play = self.play_intent;
        if let Some(p) = self.queue.iter().position(|q| q.uid == uid) {
            self.leave_repeat_track();
            self.queue.drain(..p);
            let item = self.queue.pop_front()?;
            let uri = item.uri.clone();
            self.current = Some(Current::Queue(item));
            return Some(self.begin_load(uri, play, 0, now_ms));
        }
        let idx = self.context_index_of(uid)?;
        if !self.playable(idx) {
            return None;
        }
        let p = self.order.iter().position(|&i| i == idx)?;
        self.leave_repeat_track();
        Some(self.play_context_pos(p, play, now_ms))
    }

    /// A load returned by the queue never reached the Player (no Player): no request id comes
    /// for it.
    pub fn load_not_sent(&mut self) {
        self.pending_loads = self.pending_loads.saturating_sub(1);
        if self.active && self.status == PlaybackStatus::Loading {
            // Nothing loads: stopped where it was meant to start (a play loads it again).
            self.status = PlaybackStatus::Stopped;
            self.play_intent = false;
        }
    }

    /// The Player's thread died: none of its request ids will come, and the queue's playback
    /// is gone. It stops where it was (a play loads it again there, on a new Player).
    pub fn player_lost(&mut self, now_ms: i64) {
        self.pending_loads = 0;
        self.own_request = None;
        self.last_request = None;
        self.ended_request = None;
        if self.active && self.status != PlaybackStatus::Stopped {
            self.position_ms = self.position_at(now_ms);
            self.position_ts = now_ms;
            self.status = PlaybackStatus::Stopped;
            self.play_intent = false;
        }
    }

    /// Takes over what the Player is playing for someone else (Spirc, with the session going
    /// away): `a.uris[a.start]` is the loaded track, the latest request. Nothing is loaded
    /// again, so it plays on without a gap; returns `Play` to resume a Player paused meanwhile.
    /// Without a known request the track is loaded at the position (a short gap).
    pub fn adopt(&mut self, a: Adoption, now_ms: i64) -> Option<Action> {
        let request = self.last_request;
        // Its track ended before the takeover (the dying Spirc no longer handled the end): the
        // end is handled now, a Play would do nothing on an ended track.
        let ended = request.is_some() && request == self.ended_request;
        let spec = LoadSpec {
            context_uri: a.context_uri,
            uris: a.uris,
            start: Some(a.start),
            position_ms: a.position_ms,
            shuffle: false,
            repeat: if a.repeat_context { RepeatMode::Context } else { RepeatMode::Off },
            play: a.playing,
            seed: 0,
        };
        // The user queue is in the handed over tracks (taken out of them below).
        self.queue.clear();
        let load = self.load(spec, now_ms)?;
        // See Adoption::streamed (after the load, which starts the queue afresh).
        if a.streamed {
            if let Some(item) = self.items.get(a.start) {
                self.unavailable.insert(item.uri.clone());
            }
        }
        // Spirc's queued tracks after the current one are the user queue here too, in order.
        for i in a.user_queued.into_iter().filter(|&i| i > a.start && i < self.items.len()) {
            let uid = format!("q{}", self.next_queue_id);
            self.next_queue_id += 1;
            self.queue.push_back(Item { uri: self.items[i].uri.clone(), uid });
            self.skipped.insert(i);
        }
        if a.repeat == RepeatMode::Track {
            self.set_repeat(RepeatMode::Track);
        }
        // Already in play order: shown as shuffled, a toggle reshuffles or keeps this order.
        self.shuffle = a.shuffle;
        self.continuation = a.continuation;
        self.restart = a.restart;
        self.outside = a.outside;
        let Some(request) = request else { return Some(load) };
        self.pending_loads = self.pending_loads.saturating_sub(1);
        self.own_request = Some(request);
        self.status = if a.loading {
            PlaybackStatus::Loading
        } else if a.playing {
            PlaybackStatus::Playing
        } else {
            PlaybackStatus::Paused
        };
        self.play_intent = a.playing;
        self.position_ms = a.position_ms;
        self.position_ts = now_ms;
        self.duration_ms = a.duration_ms;
        if ended {
            return Some(self.advance(true, a.playing, now_ms));
        }
        a.playing.then_some(Action::Play)
    }

    /// Stops and forgets everything (another controller took over, or a user stop).
    pub fn reset(&mut self) {
        let next_queue_id = self.next_queue_id;
        let pending_loads = self.pending_loads;
        let (last_request, ended_request) = (self.last_request, self.ended_request);
        let speed_milli = self.speed_milli;
        *self = OfflineQueue { next_queue_id, pending_loads, last_request, ended_request, speed_milli, ..Default::default() };
    }

    fn own(&self, id: u64) -> bool {
        self.own_request == Some(id)
    }

    /// Next uri to preload (repeat-track: the current one).
    fn peek_next_uri(&self) -> Option<String> {
        if self.repeat == RepeatMode::Track {
            return self.current_uri().map(str::to_string);
        }
        if let Some(q) = self.queue.front() {
            return Some(q.uri.clone());
        }
        let from = if self.order.is_empty() { None } else { Some(self.pos) };
        self.next_context_pos(from).map(|p| self.items[self.order[p]].uri.clone())
    }

    pub fn on_event(&mut self, event: Event, now_ms: i64) -> Outcome {
        let mut out = Outcome::default();
        if let Event::RequestId(id) = event {
            self.last_request = Some(id);
            self.ended_request = None;
            // Bookkeeping also while inactive, so that ids of loads we sent before a reset are
            // never mistaken for later (foreign) loads.
            if self.own(id) {
                // a seek while loading restarts our load with the same id
            } else if self.pending_loads > 0 {
                self.pending_loads -= 1;
                self.own_request = Some(id);
            } else if self.active && self.own_request.is_some() {
                // Someone else (Spirc) loaded a track: the offline queue is no longer in charge.
                self.active = false;
                out.changed = true;
            }
            return out;
        }
        if !self.active {
            if let Event::EndOfTrack(id) = event {
                if self.last_request == Some(id) {
                    self.ended_request = Some(id);
                }
            }
            return out;
        }
        match event {
            Event::RequestId(_) => {}
            Event::Loading(id) if self.own(id) => {
                out.changed = self.status != PlaybackStatus::Loading;
                self.status = PlaybackStatus::Loading;
            }
            Event::Playing { id, position_ms } if self.own(id) => {
                self.status = PlaybackStatus::Playing;
                self.play_intent = true;
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            Event::Paused { id, position_ms } if self.own(id) => {
                if self.is_playing() {
                    self.paused_here();
                }
                self.status = PlaybackStatus::Paused;
                self.play_intent = false;
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            Event::Position { id, position_ms } if self.own(id) => {
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            // (a load of the queue on its way supersedes a stop of the track before it)
            Event::Stopped(id) if self.own(id) && self.pending_loads == 0 => {
                out.changed = self.status != PlaybackStatus::Stopped;
                self.status = PlaybackStatus::Stopped;
                self.play_intent = false;
                self.position_ms = 0;
            }
            Event::TimeToPreload(id) if self.own(id) => {
                out.action = self.peek_next_uri().map(Action::Preload);
            }
            Event::EndOfTrack(id) if self.own(id) => {
                // As it plays or not: the Player also ends a paused track (an explicit download
                // when the filter turns on), the next one then loads paused.
                let play = self.play_intent;
                out.action = Some(self.advance(true, play, now_ms));
                out.changed = true;
            }
            Event::Unavailable { id, uri } if self.own(id) => {
                self.unavailable.insert(uri.clone());
                if self.current_uri() == Some(uri.as_str()) {
                    let play = self.play_intent;
                    let action = self.advance(false, play, now_ms);
                    out.exhausted_after_error = action == Action::Stop;
                    out.action = Some(action);
                }
                out.changed = true;
            }
            Event::TrackChanged { uri, duration_ms } if self.current_uri() == Some(uri.as_str()) => {
                out.changed = self.duration_ms != duration_ms as u64;
                self.duration_ms = duration_ms as u64;
            }
            _ => {}
        }
        out
    }

    fn track(item: &Item, provider: TrackProvider) -> PlaybackTrack {
        PlaybackTrack {
            uri: item.uri.clone(),
            uid: item.uid.clone(),
            provider,
            is_episode: uri::is_episode(&item.uri),
            ..Default::default()
        }
    }

    fn next_tracks(&self) -> Vec<PlaybackTrack> {
        let mut next: Vec<PlaybackTrack> =
            self.queue.iter().take(MAX_NEXT).map(|q| Self::track(q, TrackProvider::Queue)).collect();
        let current_idx = match self.current {
            Some(Current::Context(i)) => Some(i),
            _ => None,
        };
        // One pass over the positions after the current one (wrapping with repeat context, to the
        // current one: after a queued track that is the context track played before it, like
        // Spirc), so that every playable item is listed once. Counting only the playable items
        // visited listed some twice when items were skipped or unavailable.
        let len = self.order.len();
        for off in 1..=len {
            if next.len() >= MAX_NEXT {
                break;
            }
            let p = if self.repeat_context {
                (self.pos + off) % len
            } else if self.pos + off < len {
                self.pos + off
            } else {
                break;
            };
            let idx = self.order[p];
            if self.playable(idx) && Some(idx) != current_idx {
                next.push(Self::track(&self.items[idx], TrackProvider::Context));
            }
        }
        next
    }

    fn prev_tracks(&self) -> Vec<PlaybackTrack> {
        let end = match self.current {
            Some(Current::Queue(_)) => (self.pos + 1).min(self.order.len()),
            _ => self.pos.min(self.order.len()),
        };
        let start = end.saturating_sub(MAX_PREV);
        self.order[start..end]
            .iter()
            .filter(|&&i| !self.skipped.contains(&i))
            .map(|&i| Self::track(&self.items[i], TrackProvider::Context))
            .collect()
    }

    /// What to hand over to another device: the current item followed by up to `max_next` next
    /// items, the position at `now_ms` (the snapshot only carries the last anchor), and the
    /// context at the current item when it is one of its tracks, with the user queue.
    pub fn handover(&self, now_ms: i64, max_next: usize) -> Handover {
        let mut uris: Vec<String> = self.current_uri().map(str::to_string).into_iter().collect();
        uris.extend(self.next_tracks().into_iter().take(max_next).map(|t| t.uri));
        // A plain track list goes on after the window as it was listed (without repeat-all,
        // which wraps the window).
        let rest = self.continuation.as_ref().and_then(|c| c.tracks.as_ref()).filter(|_| !self.repeat_context);
        let room = max_next.saturating_sub(uris.len().saturating_sub(1));
        uris.extend(rest.into_iter().flatten().take(room).cloned());
        let context = match &self.current {
            Some(Current::Context(i)) if !self.outside.contains(i) => self
                .context_uri
                .as_ref()
                .filter(|c| uri::is_resolvable_context(c))
                .zip(self.items.get(*i))
                .map(|(context_uri, item)| ContextStart { context_uri: context_uri.clone(), track_uri: item.uri.clone() }),
            _ => None,
        };
        let queued = if context.is_some() { self.user_queue() } else { Vec::new() };
        Handover {
            uris,
            position_ms: self.position_at(now_ms),
            repeat: self.repeat,
            playing: self.is_playing(),
            context,
            shuffle: self.shuffle,
            queued,
        }
    }

    /// The user queue still ahead, in the order it plays (also Spirc's, see
    /// [`Adoption::user_queued`]); at most [`MAX_NEXT`].
    fn user_queue(&self) -> Vec<String> {
        self.queue.iter().map(|q| q.uri.clone()).take(MAX_NEXT).collect()
    }

    /// The snapshot (bare tracks; metadata is filled by the caller). Positions are reported as
    /// the last anchor (position + timestamp), so the JSON only changes when the state does.
    pub fn snapshot(&self, device: ActiveDeviceRef, volume: u16) -> PlaybackSnapshot {
        let track = match &self.current {
            Some(Current::Context(i)) => self.items.get(*i).map(|it| Self::track(it, TrackProvider::Context)),
            Some(Current::Queue(item)) => Some(Self::track(item, TrackProvider::Queue)),
            None => None,
        };
        let has_track = track.is_some();
        let next = self.next_tracks();
        let playing = self.status == PlaybackStatus::Playing;
        PlaybackSnapshot {
            source: PlaybackSource::Local,
            offline: true,
            active_device: Some(device),
            status: self.status,
            position_ms: self.position_ms,
            position_timestamp_ms: self.position_ts,
            playback_speed: if playing { self.speed_milli as f64 / 1000.0 } else { 0.0 },
            duration_ms: self.duration_ms,
            context: self.context_uri.as_ref().map(|u| PlaybackContext {
                uri: u.clone(),
                name: None,
                kind: uri::context_type(u).to_string(),
            }),
            track,
            prev_tracks: self.prev_tracks(),
            restrictions: PlaybackRestrictions {
                can_skip_prev: has_track,
                // (also where a next hands back to Spirc)
                can_skip_next: !next.is_empty() || (self.hand_back && self.goes_on_after().is_some()),
                can_seek: has_track,
                can_toggle_shuffle: true,
                can_toggle_repeat: true,
                can_pause: has_track,
            },
            next_tracks: next,
            shuffle: self.shuffle,
            smart_shuffle: false,
            repeat: self.repeat,
            is_playing_autoplay: false,
            volume,
            last_error: self.notice.map(str::to_string),
        }
    }
}

/// What a transfer to another device takes over (see [`OfflineQueue::handover`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Handover {
    /// The current item and the next ones, in play order.
    pub uris: Vec<String>,
    pub position_ms: u64,
    pub repeat: RepeatMode,
    pub playing: bool,
    /// The queue's context at its current item, when that is a track of a context that can be
    /// loaded again (the target plays the whole context, not just the downloads).
    pub context: Option<ContextStart>,
    pub shuffle: bool,
    /// The user queue (after the current item).
    pub queued: Vec<String>,
}

/// See [`Handover::context`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ContextStart {
    pub context_uri: String,
    pub track_uri: String,
}

/// The downloaded subset of a request and where it starts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Selection {
    pub items: Vec<String>,
    /// `None`: no start was asked for.
    pub start: Option<usize>,
    /// The start item is the one asked for (so a requested position applies to it).
    pub exact: bool,
}

/// Picks the downloaded subset of `uris` and the start index within it.
/// `start_index` refers to `uris`; if that item isn't downloaded the next downloaded one starts.
pub(crate) fn select_downloaded(
    uris: &[String],
    is_downloaded: impl Fn(&str) -> bool,
    start_index: Option<usize>,
    start_uri: Option<&str>,
) -> Selection {
    let mut items = Vec::new();
    let mut start = None;
    let requested = start_uri.is_some() || start_index.is_some();
    let mut exact = !requested;
    let wanted_index = start_uri.and_then(|u| uris.iter().position(|x| x == u)).or(start_index);
    for (i, u) in uris.iter().enumerate() {
        if !is_downloaded(u) {
            continue;
        }
        if start.is_none() && wanted_index.is_some_and(|w| i >= w) {
            start = Some(items.len());
            exact = wanted_index == Some(i);
        }
        items.push(u.clone());
    }
    // Asked for a start after the last download: the first one.
    let start = start.or(wanted_index.map(|_| 0));
    Selection { items, start, exact }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dev() -> ActiveDeviceRef {
        ActiveDeviceRef { id: "me".into(), name: "Phone".into(), kind: "smartphone".into() }
    }

    fn uris(n: usize) -> Vec<String> {
        (0..n).map(|i| format!("spotify:track:{i}")).collect()
    }

    fn spec(n: usize, start: usize, shuffle: bool, repeat: RepeatMode) -> LoadSpec {
        LoadSpec {
            context_uri: Some("spotify:album:a".into()),
            uris: uris(n),
            start: Some(start),
            position_ms: 0,
            shuffle,
            repeat,
            play: true,
            seed: 42,
        }
    }

    fn load_uri(a: &Option<Action>) -> Option<String> {
        match a {
            Some(Action::Load { uri, .. }) => Some(uri.clone()),
            _ => None,
        }
    }

    /// Feeds the request id + playing events the Player would emit for a load.
    fn started(q: &mut OfflineQueue, id: u64) {
        q.on_event(Event::RequestId(id), 0);
        q.on_event(Event::Playing { id, position_ms: 0 }, 0);
    }

    #[test]
    fn load_next_prev_and_end() {
        let mut q = OfflineQueue::default();
        let a = q.load(spec(3, 1, false, RepeatMode::Off), 0);
        assert_eq!(load_uri(&a).as_deref(), Some("spotify:track:1"));
        started(&mut q, 1);
        assert_eq!(q.status(), PlaybackStatus::Playing);
        let a = q.next(0);
        assert_eq!(load_uri(&a).as_deref(), Some("spotify:track:2"));
        started(&mut q, 2);
        // prev within 3 s goes back
        let a = q.prev(1000);
        assert_eq!(load_uri(&a).as_deref(), Some("spotify:track:1"));
        started(&mut q, 3);
        // prev after 3 s restarts
        assert_eq!(q.prev(5000), Some(Action::Seek(0)));
        // end of the last track stops (battery: the sink pauses)
        q.next(0);
        started(&mut q, 4);
        let out = q.on_event(Event::EndOfTrack(4), 0);
        assert_eq!(out.action, Some(Action::Stop));
        assert_eq!(q.status(), PlaybackStatus::Stopped);
        // play after the end restarts the last item
        assert_eq!(load_uri(&q.play(0)).as_deref(), Some("spotify:track:2"));
    }

    #[test]
    fn repeat_context_and_track() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 1, false, RepeatMode::Context), 0);
        started(&mut q, 1);
        let out = q.on_event(Event::EndOfTrack(1), 0);
        assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:0"), "wraps around");
        started(&mut q, 2);
        q.set_repeat(RepeatMode::Track);
        let out = q.on_event(Event::EndOfTrack(2), 0);
        assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:0"), "repeats the track");
        started(&mut q, 3);
        // preload with repeat track = the current track
        assert_eq!(q.on_event(Event::TimeToPreload(3), 0).action, Some(Action::Preload("spotify:track:0".into())));
        // a manual next advances and leaves repeat-track (back to repeat context, like Spirc)
        assert_eq!(load_uri(&q.next(0)).as_deref(), Some("spotify:track:1"));
        assert_eq!(q.snapshot(dev(), 0).repeat, RepeatMode::Context);
        started(&mut q, 4);
        assert_eq!(q.on_event(Event::TimeToPreload(4), 0).action, Some(Action::Preload("spotify:track:0".into())), "wraps");

        // repeat-track without repeat context: a skip goes back to off
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.set_repeat(RepeatMode::Track);
        q.next(0);
        assert_eq!(q.snapshot(dev(), 0).repeat, RepeatMode::Off);
        // and so does skipping to an entry; a skip to nothing changes nothing
        q.set_repeat(RepeatMode::Track);
        assert!(q.skip_to("nothing", 0).is_none());
        assert_eq!(q.snapshot(dev(), 0).repeat, RepeatMode::Track);
        assert!(q.skip_to("o2", 0).is_some());
        assert_eq!(q.snapshot(dev(), 0).repeat, RepeatMode::Off);
    }

    #[test]
    fn repeat_one_entered_from_repeat_all_wraps() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 2, false, RepeatMode::Context), 0);
        started(&mut q, 1);
        q.set_repeat(RepeatMode::Track);
        let s = q.snapshot(dev(), 0);
        assert_eq!(s.next_tracks.first().map(|t| t.uri.as_str()), Some("spotify:track:0"));
        assert!(s.restrictions.can_skip_next, "Next stays offered on the last item");
        assert_eq!(load_uri(&q.next(0)).as_deref(), Some("spotify:track:0"));
        assert_eq!(q.snapshot(dev(), 0).repeat, RepeatMode::Context);
        started(&mut q, 2);
        // back to repeat-one on the first item: prev goes to the last one
        q.set_repeat(RepeatMode::Track);
        assert_eq!(load_uri(&q.prev(0)).as_deref(), Some("spotify:track:2"));

        // from off, repeat-one on the last item doesn't wrap
        let mut q = OfflineQueue::default();
        q.load(spec(3, 2, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.set_repeat(RepeatMode::Track);
        let s = q.snapshot(dev(), 0);
        assert!(s.next_tracks.is_empty());
        assert!(!s.restrictions.can_skip_next);
    }

    fn elsewhere(device: Option<&str>, playing: bool) -> Elsewhere {
        Elsewhere { device: device.map(str::to_string), playing }
    }

    #[test]
    fn a_device_sitting_paused_as_the_active_one_never_takes_over() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        assert!(!q.taken_over(), "no cluster seen yet");
        // the first cluster after the network came back: the desktop left paused at the office
        q.observe(elsewhere(Some("desktop"), false));
        assert!(!q.taken_over());
        // a pause here (call, headphones unplugged) keeps the session
        q.pause(0);
        assert!(!q.gives_way());
        q.observe(elsewhere(Some("desktop"), false));
        assert!(!q.gives_way());
        // the desktop starts playing: it took over
        q.observe(elsewhere(Some("desktop"), true));
        assert!(q.gives_way());

        // another device becomes active
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.observe(elsewhere(Some("desktop"), false));
        q.pause(0);
        q.observe(elsewhere(Some("speaker"), false));
        assert!(q.gives_way());
        // nobody active any more: it doesn't
        q.observe(elsewhere(None, false));
        assert!(!q.gives_way());
    }

    #[test]
    fn a_pause_here_never_hands_the_session_away() {
        // the desktop started playing while the queue played on: the next pause here (a call)
        // keeps the session, Play resumes here
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.observe(elsewhere(None, false));
        q.observe(elsewhere(Some("desktop"), true));
        assert!(q.taken_over() && !q.gives_way(), "a playing queue keeps playing");
        q.pause(0);
        assert!(!q.gives_way());
        q.observe(elsewhere(Some("desktop"), true));
        assert!(!q.gives_way());
        // the same through the Player's pause event
        q.play(0);
        q.observe(elsewhere(Some("speaker"), true));
        q.on_event(Event::Paused { id: 1, position_ms: 0 }, 0);
        assert!(!q.gives_way());
        // the speaker pauses, then resumes: that is a takeover
        q.observe(elsewhere(Some("speaker"), false));
        assert!(!q.gives_way());
        q.observe(elsewhere(Some("speaker"), true));
        assert!(q.gives_way());
    }

    #[test]
    fn a_finished_queue_gives_way_to_a_device_that_took_over_meanwhile() {
        let mut q = OfflineQueue::default();
        q.load(spec(1, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.observe(elsewhere(Some("desktop"), false));
        q.observe(elsewhere(Some("desktop"), true));
        assert!(!q.gives_way());
        q.on_event(Event::EndOfTrack(1), 0);
        assert!(q.gives_way());
        // a new load starts over (no cluster seen)
        q.load(spec(1, 0, false, RepeatMode::Off), 0);
        assert!(!q.taken_over());
        q.reset();
        assert!(!q.taken_over() && !q.gives_way());
    }

    #[test]
    fn a_paused_load_stays_paused() {
        let paused = |n| LoadSpec { play: false, ..spec(n, 0, false, RepeatMode::Off) };
        let play_of = |a: Option<Action>| match a {
            Some(Action::Load { play, .. }) => play,
            other => panic!("{other:?}"),
        };
        let mut q = OfflineQueue::default();
        q.load(paused(4), 0);
        assert!(!play_of(q.next(0)), "next during a paused load");
        assert!(!play_of(q.next(0)), "and again before any player event");
        // the current file can't be read: the next one loads paused too
        let mut q = OfflineQueue::default();
        q.load(paused(4), 0);
        q.on_event(Event::RequestId(1), 0);
        let out = q.on_event(Event::Unavailable { id: 1, uri: "spotify:track:0".into() }, 0);
        assert!(!play_of(out.action));
        // a toggle during a paused load plays
        let mut q = OfflineQueue::default();
        q.load(paused(4), 0);
        assert_eq!(q.toggle(0), Some(Action::Play));
        // pausing a playing load, then the file fails: the next one stays paused
        let mut q = OfflineQueue::default();
        q.load(spec(4, 0, false, RepeatMode::Off), 0);
        q.on_event(Event::RequestId(1), 0);
        assert_eq!(q.pause(0), Some(Action::Pause));
        let out = q.on_event(Event::Unavailable { id: 1, uri: "spotify:track:0".into() }, 0);
        assert!(!play_of(out.action));
        // a paused track that ends (the explicit filter turned on): the next one loads paused,
        // also with repeat-one; one that ends while playing plays on
        for repeat in [RepeatMode::Off, RepeatMode::Track] {
            let mut q = OfflineQueue::default();
            q.load(spec(4, 0, false, repeat), 0);
            q.on_event(Event::RequestId(1), 0);
            q.on_event(Event::Playing { id: 1, position_ms: 0 }, 0);
            q.on_event(Event::Paused { id: 1, position_ms: 5_000 }, 0);
            assert!(!play_of(q.on_event(Event::EndOfTrack(1), 0).action), "{repeat:?}");
            let mut q = OfflineQueue::default();
            q.load(spec(4, 0, false, repeat), 0);
            q.on_event(Event::RequestId(1), 0);
            q.on_event(Event::Playing { id: 1, position_ms: 0 }, 0);
            assert!(play_of(q.on_event(Event::EndOfTrack(1), 0).action), "{repeat:?}");
        }
    }

    #[test]
    fn a_shuffle_without_a_start_starts_anywhere() {
        let firsts = (0..20u64)
            .map(|seed| {
                let mut q = OfflineQueue::default();
                q.load(LoadSpec { start: None, seed, ..spec(10, 0, true, RepeatMode::Off) }, 0);
                let mut sorted = q.order.clone();
                sorted.sort();
                assert_eq!(sorted, (0..10).collect::<Vec<_>>(), "a permutation");
                assert_eq!(q.current_uri().map(str::to_string), Some(format!("spotify:track:{}", q.order[0])));
                q.order[0]
            })
            .collect::<HashSet<_>>();
        assert!(firsts.len() > 1, "{firsts:?}");
        // without shuffle it starts at the first item
        let mut q = OfflineQueue::default();
        q.load(LoadSpec { start: None, ..spec(10, 0, false, RepeatMode::Off) }, 0);
        assert_eq!(q.current_uri(), Some("spotify:track:0"));
    }

    #[test]
    fn the_user_queue_is_capped_like_spirc() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 0, false, RepeatMode::Off), 0);
        for i in 0..MAX_NEXT {
            assert!(q.add_to_queue(format!("spotify:track:q{i}")));
        }
        assert!(!q.add_to_queue("spotify:track:more".into()));
        assert!(q.snapshot(dev(), 0).next_tracks.len() <= MAX_NEXT);
    }

    #[test]
    fn shuffle_is_seeded_reversible_and_starts_with_current() {
        let mut q = OfflineQueue::default();
        q.load(spec(10, 3, true, RepeatMode::Off), 0);
        assert_eq!(q.current_uri(), Some("spotify:track:3"), "start item plays first");
        let order_a = q.order.clone();
        assert_eq!(order_a[0], 3);
        let mut sorted = order_a.clone();
        sorted.sort();
        assert_eq!(sorted, (0..10).collect::<Vec<_>>(), "a permutation");
        // same seed → same order
        let mut q2 = OfflineQueue::default();
        q2.load(spec(10, 3, true, RepeatMode::Off), 0);
        assert_eq!(q2.order, order_a);
        // unshuffle restores the original order at the current item
        q.set_shuffle(false, 0);
        assert_eq!(q.order, (0..10).collect::<Vec<_>>());
        assert_eq!(q.pos, 3);
        let next = q.snapshot(dev(), 0).next_tracks;
        assert_eq!(next.first().map(|t| t.uri.as_str()), Some("spotify:track:4"));
        // reshuffle keeps the current item first
        q.set_shuffle(true, 7);
        assert_eq!(q.order[0], 3);
        assert_eq!(q.pos, 0);
    }

    #[test]
    fn user_queue_operations() {
        let mut q = OfflineQueue::default();
        q.load(spec(4, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.add_to_queue("spotify:track:x".into());
        q.add_to_queue("spotify:track:y".into());
        let snap = q.snapshot(dev(), 0);
        let uids: Vec<&str> = snap.next_tracks.iter().map(|t| t.uid.as_str()).collect();
        assert_eq!(uids, vec!["q0", "q1", "o1", "o2", "o3"]);
        assert_eq!(snap.next_tracks[0].provider, TrackProvider::Queue);

        // move q1 to the front, move a context item into the queue
        q.move_item("q1", 0).expect("move");
        q.move_item("o3", 1).expect("into queue");
        let uids: Vec<String> = q.snapshot(dev(), 0).next_tracks.iter().map(|t| t.uid.clone()).collect();
        assert_eq!(uids, vec!["q1", "q2", "q0", "o1", "o2"]);
        assert!(q.move_item("o2", 5).is_err(), "context items can't be reordered");

        // remove a context item and a queued item
        assert!(q.remove("o1"));
        assert!(q.remove("q0"));
        assert!(!q.remove("o0"), "the current item can't be removed");
        let uris: Vec<String> = q.snapshot(dev(), 0).next_tracks.iter().map(|t| t.uri.clone()).collect();
        assert_eq!(uris, vec!["spotify:track:y", "spotify:track:3", "spotify:track:2"]);

        // next plays the queue first
        assert_eq!(load_uri(&q.next(0)).as_deref(), Some("spotify:track:y"));
        started(&mut q, 2);
        // skip to a queued entry drops the ones before it
        q.add_to_queue("spotify:track:z".into());
        let a = q.skip_to("q3", 0);
        assert_eq!(load_uri(&a).as_deref(), Some("spotify:track:z"));
        assert!(q.queue.is_empty());
        started(&mut q, 3);
        // skip to a context entry keeps the queue
        q.add_to_queue("spotify:track:w".into());
        assert_eq!(load_uri(&q.skip_to("o2", 0)).as_deref(), Some("spotify:track:2"));
        assert_eq!(q.queue.len(), 1);
        q.clear_queue();
        assert!(q.queue.is_empty());
    }

    #[test]
    fn unavailable_skips_and_reports_exhaustion() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 0, false, RepeatMode::Off), 0);
        q.on_event(Event::RequestId(1), 0);
        let out = q.on_event(Event::Unavailable { id: 1, uri: "spotify:track:0".into() }, 0);
        assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:1"));
        assert!(!out.exhausted_after_error);
        q.on_event(Event::RequestId(2), 0);
        let out = q.on_event(Event::Unavailable { id: 2, uri: "spotify:track:1".into() }, 0);
        assert_eq!(out.action, Some(Action::Stop));
        assert!(out.exhausted_after_error);
    }

    #[test]
    fn rapid_loads_and_seek_while_loading_keep_ownership() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        q.next(0);
        // ids arrive after both loads were sent; a seek while loading repeats an id
        q.on_event(Event::RequestId(1), 0);
        q.on_event(Event::RequestId(1), 0);
        q.on_event(Event::RequestId(2), 0);
        assert!(q.active);
        q.on_event(Event::Playing { id: 2, position_ms: 0 }, 0);
        assert_eq!(q.status(), PlaybackStatus::Playing);
        // a reset before the id arrived: the late id is still recognised as ours
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        q.reset();
        q.on_event(Event::RequestId(3), 0);
        q.load(spec(3, 1, false, RepeatMode::Off), 0);
        q.on_event(Event::RequestId(4), 0);
        q.on_event(Event::Playing { id: 4, position_ms: 0 }, 0);
        assert!(q.active);
        assert_eq!(q.status(), PlaybackStatus::Playing);
        // a foreign load supersedes
        q.on_event(Event::RequestId(5), 0);
        assert!(!q.active);
    }

    #[test]
    fn a_load_keeps_the_user_queue() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        assert!(q.add_to_queue("spotify:track:x".into()));
        assert!(q.add_to_queue("spotify:track:y".into()));
        q.load(spec(2, 1, false, RepeatMode::Off), 0);
        started(&mut q, 2);
        let next: Vec<String> = q.snapshot(dev(), 0).next_tracks.iter().map(|t| t.uri.clone()).collect();
        assert_eq!(next, ["spotify:track:x", "spotify:track:y"], "queued first, the new context has nothing after its last item");
        // a bulk add still running appends to the same queue, uids stay unique
        assert!(q.add_to_queue("spotify:track:z".into()));
        let uids: HashSet<String> = q.snapshot(dev(), 0).next_tracks.iter().map(|t| t.uid.clone()).collect();
        assert_eq!(uids.len(), 3);
        // queue.clear still clears it
        q.clear_queue();
        assert!(q.snapshot(dev(), 0).next_tracks.is_empty());
        // a queue superseded by another controller doesn't come back
        assert!(q.add_to_queue("spotify:track:x".into()));
        q.on_event(Event::RequestId(9), 0);
        assert!(!q.active);
        q.load(spec(2, 1, false, RepeatMode::Off), 0);
        assert!(q.snapshot(dev(), 0).next_tracks.is_empty());
        q.add_to_queue("spotify:track:x".into());
        q.reset();
        q.load(spec(2, 1, false, RepeatMode::Off), 0);
        assert!(q.snapshot(dev(), 0).next_tracks.is_empty());
    }

    #[test]
    fn the_end_of_a_handed_over_window_goes_back_to_spirc_when_online() {
        let continuation = Continuation {
            context_uri: "spotify:playlist:p".into(),
            start_uri: Some("spotify:track:gap".into()),
            smart_shuffle: false,
            order: None,
            tracks: None,
        };
        for repeat_context in [false, true] {
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            q.adopt(Adoption { continuation: Some(continuation.clone()), repeat_context, ..adoption(2, 1) }, 0);
            // offline: it stops (or wraps) at the end as before
            let out = q.on_event(Event::EndOfTrack(7), 1_000);
            if repeat_context {
                assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:0"));
            } else {
                assert_eq!(out.action, Some(Action::Stop));
            }
            // online and visible: the context goes on in Spirc at the first track after the window
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            q.adopt(Adoption { continuation: Some(continuation.clone()), repeat_context, ..adoption(2, 1) }, 0);
            q.set_hand_back(true);
            let out = q.on_event(Event::EndOfTrack(7), 1_000);
            let Some(Action::HandBack(back)) = out.action else { panic!("hand back: {:?}", out.action) };
            assert_eq!((back.context_uri.as_str(), back.start_uri.as_deref()), ("spotify:playlist:p", Some("spotify:track:gap")));
            assert!(back.play);
            assert_eq!(back.repeat_context, repeat_context);
            // it couldn't be sent: as without one
            let action = q.hand_back_failed(true, 1_000);
            assert!(matches!(action, Action::Stop | Action::Load { .. }));
        }
        // not before the window's end, and not after a shuffle toggle
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { continuation: Some(continuation), ..adoption(3, 0) }, 0);
        q.set_hand_back(true);
        assert_eq!(load_uri(&q.on_event(Event::EndOfTrack(7), 0).action).as_deref(), Some("spotify:track:1"));
        q.set_shuffle(true, 1);
        q.set_shuffle(false, 1);
        q.on_event(Event::RequestId(8), 0);
        q.on_event(Event::Playing { id: 8, position_ms: 0 }, 0);
        assert!(!matches!(q.next(0), Some(Action::HandBack(_))));
    }

    #[test]
    fn a_window_that_ends_offline_goes_on_in_spirc_once_the_session_is_back() {
        let continuation = Continuation {
            context_uri: "spotify:playlist:p".into(),
            start_uri: Some("spotify:track:next".into()),
            smart_shuffle: false,
            order: None,
            tracks: None,
        };
        // a streamed song handed over alone, the playlist goes on after it
        let ended = || {
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            q.adopt(Adoption { continuation: Some(continuation.clone()), ..adoption(1, 0) }, 0);
            q.on_event(Event::Playing { id: 7, position_ms: 0 }, 0);
            assert_eq!(q.on_event(Event::EndOfTrack(7), 1_000_000).action, Some(Action::Stop), "offline: the end");
            q
        };
        // a play while still offline: nothing more to play, said so, the song isn't replayed
        let mut q = ended();
        assert_eq!(q.play(1_001_000), None);
        assert_eq!(q.snapshot(dev(), 0).last_error.as_deref(), Some(NOTHING_OFFLINE));
        // online again: a play goes on after the window
        q.set_hand_back(true);
        let Some(Action::HandBack(back)) = q.play(1_002_000) else { panic!("hand back") };
        assert_eq!((back.start_uri.as_deref(), back.play), (Some("spotify:track:next"), true));
        assert_eq!(q.snapshot(dev(), 0).last_error, None);
        // the session coming back: it goes on by itself, playing after a short gap
        let mut q = ended();
        assert_eq!(q.resume_window_end(1_010_000), None, "not without a session");
        q.set_hand_back(true);
        let Some(Action::HandBack(back)) = q.resume_window_end(1_010_000) else { panic!("hand back") };
        assert!(back.play);
        assert_eq!(q.resume_window_end(1_011_000), None, "once");
        // ... paused after a long one
        let mut q = ended();
        q.set_hand_back(true);
        let Some(Action::HandBack(back)) = q.resume_window_end(1_000_000 + RESUME_PLAYING_MAX_GAP_MS) else { panic!("hand back") };
        assert!(!back.play);
        // a load (or a shuffle toggle) replaces the window: nothing to resume
        let mut q = ended();
        q.set_hand_back(true);
        q.load(spec(2, 0, false, RepeatMode::Off), 1_001_000);
        assert_eq!(q.resume_window_end(1_002_000), None);
        // a window that ends with nothing after it plays again as before
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(adoption(1, 0), 0);
        q.on_event(Event::EndOfTrack(7), 0);
        assert_eq!(load_uri(&q.play(0)).as_deref(), Some("spotify:track:0"));
    }

    fn goes_on() -> Continuation {
        Continuation {
            context_uri: "spotify:playlist:p".into(),
            start_uri: Some("spotify:track:next".into()),
            smart_shuffle: false,
            order: None,
            tracks: None,
        }
    }

    #[test]
    fn next_is_offered_where_it_hands_back() {
        // a streamed song handed over alone: offline nothing comes after it
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { continuation: Some(goes_on()), ..adoption(1, 0) }, 0);
        assert!(!q.snapshot(dev(), 0).restrictions.can_skip_next);
        // the session is back: Next hands back to Spirc, so it is offered
        q.set_hand_back(true);
        assert!(q.snapshot(dev(), 0).restrictions.can_skip_next);
        assert!(matches!(q.next(0), Some(Action::HandBack(_))));
        // repeat-all: offered either way (the window wraps, or a new pass goes on in Spirc)
        let restart = Continuation { start_uri: None, ..goes_on() };
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { restart: Some(restart), repeat_context: true, ..adoption(2, 1) }, 0);
        assert!(q.snapshot(dev(), 0).restrictions.can_skip_next);
        q.set_hand_back(true);
        assert!(q.snapshot(dev(), 0).restrictions.can_skip_next);
        // nothing goes on after the window: not offered, also online
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(adoption(1, 0), 0);
        q.set_hand_back(true);
        assert!(!q.snapshot(dev(), 0).restrictions.can_skip_next);
    }

    #[test]
    fn at_a_window_that_ended_offline_the_queue_itself_goes_on_first() {
        let ended = || {
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            q.adopt(Adoption { continuation: Some(goes_on()), ..adoption(2, 1) }, 0);
            q.on_event(Event::Playing { id: 7, position_ms: 0 }, 0);
            assert_eq!(q.on_event(Event::EndOfTrack(7), 1_000).action, Some(Action::Stop));
            q
        };
        // a queued download added since: Play plays it, no notice
        let mut q = ended();
        q.add_to_queue("spotify:track:q".into());
        assert_eq!(load_uri(&q.play(2_000)).as_deref(), Some("spotify:track:q"));
        assert_eq!(q.snapshot(dev(), 0).last_error, None);
        // repeat-all turned on since: Play wraps the window
        let mut q = ended();
        q.set_repeat(RepeatMode::Context);
        assert_eq!(load_uri(&q.play(2_000)).as_deref(), Some("spotify:track:0"));
        // the session back with a user queue: it plays first (online it streams), Spirc after it
        let mut q = ended();
        q.add_to_queue("spotify:track:q".into());
        q.set_hand_back(true);
        assert_eq!(load_uri(&q.resume_window_end(2_000)).as_deref(), Some("spotify:track:q"));
        q.on_event(Event::RequestId(8), 2_000);
        assert!(matches!(q.on_event(Event::EndOfTrack(8), 3_000).action, Some(Action::HandBack(_))));
    }

    #[test]
    fn a_track_list_window_goes_on_as_its_rest() {
        let rest = vec!["spotify:track:a".to_string(), "spotify:track:b".to_string()];
        let continuation = Continuation {
            context_uri: "spotify:web-api".into(),
            start_uri: None,
            smart_shuffle: false,
            order: None,
            tracks: Some(rest.clone()),
        };
        let adopt = || {
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            let a = Adoption { context_uri: Some("spotify:web-api".into()), continuation: Some(continuation.clone()), ..adoption(1, 0) };
            q.adopt(a, 0);
            q
        };
        // pushed to another device: the window, then the rest of the list
        assert_eq!(adopt().handover(0, 50).uris, ["spotify:track:0", "spotify:track:a", "spotify:track:b"]);
        // its end offline, then the session is back: the rest of the list goes on in Spirc
        let mut q = adopt();
        q.on_event(Event::Playing { id: 7, position_ms: 0 }, 0);
        assert_eq!(q.on_event(Event::EndOfTrack(7), 1_000).action, Some(Action::Stop));
        q.set_hand_back(true);
        let Some(Action::HandBack(back)) = q.resume_window_end(2_000) else { panic!("hand back") };
        assert_eq!((back.tracks, back.start_uri, back.play), (Some(rest), None, true));
    }

    #[test]
    fn a_restart_hands_back_only_where_the_window_wraps() {
        let restart = Continuation { context_uri: "spotify:playlist:p".into(), start_uri: None, smart_shuffle: false, order: None, tracks: None };
        let adopted = |repeat_context| {
            let mut q = OfflineQueue::default();
            q.on_event(Event::RequestId(7), 0);
            q.adopt(Adoption { restart: Some(restart.clone()), repeat_context, ..adoption(2, 1) }, 0);
            q.set_hand_back(true);
            q
        };
        // repeat-all: the window's wrap hands back, a new pass at the context's start
        let mut q = adopted(true);
        let Some(Action::HandBack(back)) = q.on_event(Event::EndOfTrack(7), 0).action else { panic!("hand back") };
        assert_eq!((back.start_uri, back.repeat_context), (None, true));
        // repeat-all turned off meanwhile: it stops at the end
        let mut q = adopted(true);
        q.set_repeat(RepeatMode::Off);
        assert_eq!(q.on_event(Event::EndOfTrack(7), 0).action, Some(Action::Stop));
        // turned on after a handoff without it: it hands back instead of looping the window
        let mut q = adopted(false);
        q.set_repeat(RepeatMode::Context);
        assert!(matches!(q.on_event(Event::EndOfTrack(7), 0).action, Some(Action::HandBack(_))));
        // offline: the queue's own repeat wraps the window
        let mut q = adopted(true);
        q.set_hand_back(false);
        assert_eq!(load_uri(&q.on_event(Event::EndOfTrack(7), 0).action).as_deref(), Some("spotify:track:0"));
    }

    #[test]
    fn a_queued_track_that_cant_play_offline_is_skipped() {
        // the handed-over window holds a queued track that isn't downloaded
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        let uris = vec!["spotify:track:0".into(), "spotify:track:qx".into(), "spotify:track:1".into()];
        q.adopt(Adoption { uris, outside: vec![1], user_queued: vec![1], ..adoption(3, 0) }, 0);
        assert_eq!(load_uri(&q.on_event(Event::EndOfTrack(7), 0).action).as_deref(), Some("spotify:track:qx"));
        q.on_event(Event::RequestId(8), 0);
        let out = q.on_event(Event::Unavailable { id: 8, uri: "spotify:track:qx".into() }, 0);
        assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:1"), "the playlist plays on");
        assert!(!out.exhausted_after_error);
    }

    #[test]
    fn spirc_queued_tracks_stay_the_user_queue_after_a_handoff() {
        // the window [current, q1, q2, c1]: q1 and q2 were Spirc's user queue
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        let uris = vec!["spotify:track:c0".into(), "spotify:track:q1".into(), "spotify:track:q2".into(), "spotify:track:c1".into()];
        q.adopt(Adoption { uris, outside: vec![1, 2], user_queued: vec![1, 2], ..adoption(4, 0) }, 0);
        q.add_to_queue("spotify:track:q3".into());
        let next = q.snapshot(dev(), 0).next_tracks;
        let shown: Vec<(&str, TrackProvider)> = next.iter().map(|t| (t.uri.as_str(), t.provider)).collect();
        assert_eq!(
            shown,
            [
                ("spotify:track:q1", TrackProvider::Queue),
                ("spotify:track:q2", TrackProvider::Queue),
                ("spotify:track:q3", TrackProvider::Queue),
                ("spotify:track:c1", TrackProvider::Context),
            ],
            "a later add after them, all in Next in queue"
        );
        assert_eq!(load_uri(&q.on_event(Event::EndOfTrack(7), 0).action).as_deref(), Some("spotify:track:q1"));
        // Clear queue clears them all
        q.clear_queue();
        let next = q.snapshot(dev(), 0).next_tracks;
        assert_eq!(next.iter().map(|t| t.uri.as_str()).collect::<Vec<_>>(), ["spotify:track:c1"]);
    }

    #[test]
    fn a_handover_names_the_context_at_a_track_of_it() {
        let context = |h: &Handover| h.context.as_ref().map(|c| (c.context_uri.clone(), c.track_uri.clone()));
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { shuffle: true, ..adoption(3, 1) }, 0);
        q.add_to_queue("spotify:track:q".into());
        let h = q.handover(0, 50);
        assert_eq!(context(&h), Some(("spotify:playlist:p".into(), "spotify:track:1".into())));
        assert!(h.shuffle);
        assert_eq!(h.queued, ["spotify:track:q"]);
        assert_eq!(h.uris, ["spotify:track:1", "spotify:track:q", "spotify:track:2"], "the window as before");
        // the current track came from the user queue or suggestions: not a track of the context
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { outside: vec![1], ..adoption(3, 1) }, 0);
        assert_eq!(context(&q.handover(0, 50)), None);
        // a context that can't be loaded again
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.adopt(Adoption { context_uri: Some("spotify:web-api".into()), ..adoption(3, 1) }, 0);
        assert_eq!(context(&q.handover(0, 50)), None);
    }

    #[test]
    fn a_handover_as_the_context_keeps_the_adopted_user_queue() {
        // Spirc's user queue: 2 adopted after the current track (3 is a suggestion); then one
        // queued here
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        let a = Adoption { outside: vec![2, 3], user_queued: vec![2], ..adoption(5, 1) };
        q.adopt(a, 0);
        q.add_to_queue("spotify:track:here".into());
        let h = q.handover(0, 50);
        assert_eq!(h.context.as_ref().map(|c| c.track_uri.as_str()), Some("spotify:track:1"));
        assert_eq!(h.queued, ["spotify:track:2", "spotify:track:here"], "in the order they play");
        // played past: not any more (the one queued here played first)
        q.on_event(Event::EndOfTrack(7), 0);
        q.on_event(Event::RequestId(8), 0);
        q.on_event(Event::EndOfTrack(8), 0);
        q.on_event(Event::RequestId(9), 0);
        q.on_event(Event::EndOfTrack(9), 0);
        q.on_event(Event::RequestId(10), 0);
        assert_eq!(q.current_uri(), Some("spotify:track:3"));
        assert_eq!(q.handover(0, 50).context, None, "a suggestion plays");
        q.on_event(Event::EndOfTrack(10), 0);
        assert_eq!(q.current_uri(), Some("spotify:track:4"));
        assert!(q.handover(0, 50).queued.is_empty());
    }

    fn adoption(n: usize, start: usize) -> Adoption {
        Adoption {
            context_uri: Some("spotify:playlist:p".into()),
            uris: uris(n),
            start,
            outside: Vec::new(),
            user_queued: Vec::new(),
            position_ms: 42_000,
            duration_ms: 200_000,
            playing: true,
            loading: false,
            repeat: RepeatMode::Off,
            repeat_context: false,
            shuffle: false,
            continuation: None,
            restart: None,
            streamed: false,
        }
    }

    #[test]
    fn an_adopted_streamed_track_plays_on_but_isnt_loaded_again() {
        let mut q = OfflineQueue::default();
        // Spirc's load of the current track (a streamed one, its data all in the Player)
        q.on_event(Event::RequestId(7), 0);
        assert_eq!(q.adopt(Adoption { streamed: true, ..adoption(3, 1) }, 1_000), Some(Action::Play));
        assert_eq!(q.current_uri(), Some("spotify:track:1"));
        // it plays to its end, then the downloads after it
        let next = q.on_event(Event::EndOfTrack(7), 200_000).action;
        assert!(matches!(next, Some(Action::Load { ref uri, .. }) if uri == "spotify:track:2"));
        // the queue never goes back to it (offline it can't be loaded again)
        assert!(!q.playable(1));
        assert!(q.playable(0) && q.playable(2));
    }

    #[test]
    fn handed_over_playback_plays_on_without_a_reload() {
        let mut q = OfflineQueue::default();
        // Spirc's load of the current track (the queue was inactive)
        q.on_event(Event::RequestId(7), 0);
        let action = q.adopt(Adoption { repeat: RepeatMode::Track, repeat_context: true, shuffle: true, ..adoption(3, 1) }, 1_000);
        assert_eq!(action, Some(Action::Play), "resumes a Player paused meanwhile, no load");
        let s = q.snapshot(dev(), 0);
        assert_eq!(s.status, PlaybackStatus::Playing);
        assert_eq!(s.track.as_ref().map(|t| t.uri.as_str()), Some("spotify:track:1"));
        assert_eq!((s.position_ms, s.position_timestamp_ms, s.duration_ms), (42_000, 1_000, 200_000));
        assert_eq!((s.repeat, s.shuffle), (RepeatMode::Track, true));
        assert_eq!(s.next_tracks.first().map(|t| t.uri.as_str()), Some("spotify:track:2"));
        // the Player's events for that request are the queue's now
        let out = q.on_event(Event::EndOfTrack(7), 2_000);
        assert_eq!(load_uri(&out.action).as_deref(), Some("spotify:track:1"), "repeat one");
        // a paused one stays paused
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(3), 0);
        assert_eq!(q.adopt(Adoption { playing: false, ..adoption(2, 0) }, 0), None);
        assert_eq!(q.snapshot(dev(), 0).status, PlaybackStatus::Paused);
        q.on_event(Event::Playing { id: 3, position_ms: 42_000 }, 10);
        assert_eq!(q.snapshot(dev(), 0).status, PlaybackStatus::Playing);
        // no request seen: loaded at the position
        let mut q = OfflineQueue::default();
        let action = q.adopt(adoption(2, 0), 0);
        assert!(matches!(action, Some(Action::Load { ref uri, play: true, position_ms: 42_000 }) if uri == "spotify:track:0"));
    }

    #[test]
    fn a_track_that_ended_before_the_takeover_moves_on() {
        // the dying Spirc's track ended during its epilogue: nobody handled the end
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.on_event(Event::EndOfTrack(7), 0);
        let a = q.adopt(adoption(3, 1), 1_000);
        assert_eq!(load_uri(&a).as_deref(), Some("spotify:track:2"), "the next one, not a dead Play");
        assert_eq!(q.status(), PlaybackStatus::Loading);
        // at the end without repeat: stopped
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.on_event(Event::EndOfTrack(7), 0);
        assert_eq!(q.adopt(adoption(3, 2), 1_000), Some(Action::Stop));
        assert_eq!(q.status(), PlaybackStatus::Stopped);
        // a newer load since: an ordinary takeover
        let mut q = OfflineQueue::default();
        q.on_event(Event::RequestId(7), 0);
        q.on_event(Event::EndOfTrack(7), 0);
        q.on_event(Event::RequestId(8), 0);
        assert_eq!(q.adopt(adoption(3, 1), 1_000), Some(Action::Play));
    }

    #[test]
    fn a_dead_player_stops_the_queue_where_it_was() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        q.on_event(Event::RequestId(1), 0);
        q.on_event(Event::Playing { id: 1, position_ms: 10_000 }, 0);
        q.next(1_000);
        q.player_lost(5_000);
        assert_eq!(q.status(), PlaybackStatus::Stopped);
        assert!(q.stopped_with_item(), "a play starts it again");
        assert!(!q.is_playing());
        // a play loads it again at that position, on the new Player
        let a = q.play(6_000);
        assert!(matches!(a, Some(Action::Load { ref uri, play: true, .. }) if uri == "spotify:track:1"));
        // the new Player's ids start over: its first load is ours, a later foreign one isn't
        q.on_event(Event::RequestId(1), 6_000);
        assert!(q.active);
        q.on_event(Event::RequestId(2), 6_000);
        assert!(!q.active);
        // while playing: the position is kept
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        q.on_event(Event::Playing { id: 1, position_ms: 10_000 }, 0);
        q.player_lost(5_000);
        assert_eq!(q.snapshot(dev(), 0).position_ms, 15_000);
        assert!(matches!(q.play(6_000), Some(Action::Load { position_ms: 15_000, .. })));
        // a load that never reached a Player doesn't stay loading
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        q.load_not_sent();
        assert_eq!(q.status(), PlaybackStatus::Stopped);
    }

    #[test]
    fn a_load_that_never_reached_the_player_owns_no_id() {
        let mut q = OfflineQueue::default();
        q.load(spec(3, 0, false, RepeatMode::Off), 0);
        q.load_not_sent();
        q.load(spec(3, 1, false, RepeatMode::Off), 0);
        q.on_event(Event::RequestId(1), 0);
        assert!(q.active);
        // Spirc's load afterwards isn't taken as ours
        q.on_event(Event::RequestId(2), 0);
        assert!(!q.active);
    }

    #[test]
    fn up_next_lists_every_item_once_after_a_queued_track() {
        let uids = |q: &OfflineQueue| q.snapshot(dev(), 0).next_tracks.iter().map(|t| t.uid.clone()).collect::<Vec<_>>();
        for repeat_one in [false, true] {
            let mut q = OfflineQueue::default();
            q.load(spec(4, 0, false, RepeatMode::Context), 0);
            started(&mut q, 1);
            assert!(q.remove("o2"));
            assert!(q.add_to_queue("spotify:track:x".into()));
            q.next(0);
            started(&mut q, 2);
            if repeat_one {
                // repeat-one on the queued track, entered from repeat-all (wraps too)
                q.set_repeat(RepeatMode::Track);
            }
            assert_eq!(uids(&q), ["o1", "o3", "o0"], "repeat one: {repeat_one}");
            let h = q.handover(0, 50);
            let unique: HashSet<&String> = h.uris.iter().collect();
            assert_eq!(unique.len(), h.uris.len(), "{:?}", h.uris);
        }
        // on a context track the cycle ends before it
        let mut q = OfflineQueue::default();
        q.load(spec(4, 1, false, RepeatMode::Context), 0);
        started(&mut q, 1);
        assert!(q.remove("o3"));
        assert_eq!(uids(&q), ["o2", "o0"]);
        // without repeat the list ends with the context
        let mut q = OfflineQueue::default();
        q.load(spec(4, 1, false, RepeatMode::Off), 0);
        started(&mut q, 1);
        assert!(q.remove("o2"));
        assert_eq!(uids(&q), ["o3"]);
    }

    #[test]
    fn foreign_events_are_ignored_and_supersede() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 0, false, RepeatMode::Off), 0);
        started(&mut q, 5);
        // events of another request are ignored
        let out = q.on_event(Event::EndOfTrack(9), 0);
        assert_eq!(out.action, None);
        // a load by someone else deactivates the queue
        let out = q.on_event(Event::RequestId(9), 0);
        assert!(out.changed);
        assert!(!q.active);
    }

    #[test]
    fn positions_follow_the_playback_speed() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 0, false, RepeatMode::Off), 1000);
        q.on_event(Event::RequestId(1), 1000);
        q.on_event(Event::TrackChanged { uri: "spotify:track:0".into(), duration_ms: 600_000 }, 1000);
        q.on_event(Event::Playing { id: 1, position_ms: 0 }, 1000);
        // 4 s at normal speed, then 1.5x from there (re-anchored, no jump)
        q.set_speed(1.5, 5000);
        assert_eq!(q.position_at(5000), 4000);
        assert_eq!(q.position_at(7000), 7000);
        let s = q.snapshot(dev(), 0);
        assert_eq!(s.playback_speed, 1.5);
        // a later load keeps it
        q.load(spec(2, 1, false, RepeatMode::Off), 8000);
        assert_eq!(q.speed_milli, 1500);
    }

    #[test]
    fn pause_play_and_position() {
        let mut q = OfflineQueue::default();
        q.load(spec(2, 0, false, RepeatMode::Off), 1000);
        q.on_event(Event::RequestId(1), 1000);
        q.on_event(Event::TrackChanged { uri: "spotify:track:0".into(), duration_ms: 60_000 }, 1000);
        q.on_event(Event::Playing { id: 1, position_ms: 0 }, 1000);
        assert_eq!(q.position_at(6000), 5000);
        assert_eq!(q.pause(6000), Some(Action::Pause));
        assert_eq!(q.position_at(100_000), 5000);
        assert_eq!(q.play(7000), Some(Action::Play));
        assert_eq!(q.position_at(8000), 6000);
        // a transfer hands over the extrapolated position, not the last anchor
        let h = q.handover(68_000, 50);
        assert_eq!(h.uris, vec!["spotify:track:0", "spotify:track:1"]);
        assert_eq!(h.position_ms, 60_000, "clamped to the duration");
        assert!(h.playing);
        assert_eq!(h.repeat, RepeatMode::Off);
        let h = q.handover(30_000, 0);
        assert_eq!((h.uris, h.position_ms), (vec!["spotify:track:0".to_string()], 28_000));
        q.set_repeat(RepeatMode::Track);
        q.pause(31_000);
        let h = q.handover(40_000, 0);
        assert!(!h.playing);
        assert_eq!(h.repeat, RepeatMode::Track);
        q.set_repeat(RepeatMode::Off);
        q.play(41_000);
        let s = q.snapshot(dev(), 123);
        assert!(s.offline);
        assert_eq!(s.source, PlaybackSource::Local);
        assert_eq!(s.duration_ms, 60_000);
        assert_eq!(s.volume, 123);
        assert_eq!(s.context.map(|c| c.kind), Some("album".to_string()));
    }

    #[test]
    fn select_downloaded_items() {
        let all = uris(5);
        let dl = |u: &str| u != "spotify:track:1" && u != "spotify:track:2";
        let s = select_downloaded(&all, dl, Some(1), None);
        assert_eq!(s.items, vec!["spotify:track:0", "spotify:track:3", "spotify:track:4"]);
        assert_eq!(s.start, Some(1), "next downloaded after the requested start");
        assert!(!s.exact, "not the requested item: its position doesn't apply");
        let s = select_downloaded(&all, dl, None, Some("spotify:track:4"));
        assert_eq!((s.start, s.exact), (Some(2), true));
        let s = select_downloaded(&all, |_| false, Some(0), None);
        assert!(s.items.is_empty());
        assert_eq!(s.start, Some(0));
        // no start asked for (a shuffle starts anywhere)
        let s = select_downloaded(&all, dl, None, None);
        assert_eq!((s.start, s.exact), (None, true));
        // a start uri that isn't in the list
        let s = select_downloaded(&all, dl, None, Some("spotify:track:9"));
        assert_eq!((s.start, s.exact), (None, false));
    }
}
