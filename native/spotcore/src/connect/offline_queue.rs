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
const MAX_NEXT: usize = super::snapshot::MAX_NEXT;

/// What the driver must do with the Player.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Action {
    Load { uri: String, play: bool, position_ms: u32 },
    Preload(String),
    Play,
    Pause,
    Seek(u32),
    Stop,
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
            skipped: HashSet::new(),
            unavailable: HashSet::new(),
            next_queue_id: 0,
            status: PlaybackStatus::Stopped,
            position_ms: 0,
            position_ts: 0,
            duration_ms: 0,
            own_request: None,
            pending_loads: 0,
        }
    }
}

/// Load parameters (items already filtered to downloaded ones).
#[derive(Debug, Clone)]
pub(crate) struct LoadSpec {
    pub context_uri: Option<String>,
    pub uris: Vec<String>,
    pub start: usize,
    pub position_ms: u64,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub play: bool,
    pub seed: u64,
}

fn shuffled_order(len: usize, first: usize, seed: u64) -> Vec<usize> {
    let mut order: Vec<usize> = (0..len).collect();
    let mut rng = SmallRng::seed_from_u64(seed);
    order.shuffle(&mut rng);
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

    pub fn is_playing(&self) -> bool {
        matches!(self.status, PlaybackStatus::Playing | PlaybackStatus::Loading)
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

    /// Position extrapolated to `now_ms`.
    pub fn position_at(&self, now_ms: i64) -> u64 {
        if self.status == PlaybackStatus::Playing {
            let elapsed = (now_ms - self.position_ts).max(0) as u64;
            let p = self.position_ms + elapsed;
            if self.duration_ms > 0 { p.min(self.duration_ms) } else { p }
        } else {
            self.position_ms
        }
    }

    fn playable(&self, idx: usize) -> bool {
        !self.skipped.contains(&idx) && self.items.get(idx).is_some_and(|it| !self.unavailable.contains(&it.uri))
    }

    /// Next context position in `order` after `from` (wrapping with repeat context).
    fn next_context_pos(&self, from: Option<usize>) -> Option<usize> {
        let start = from.map(|p| p + 1).unwrap_or(0);
        if let Some(p) = (start..self.order.len()).find(|&p| self.playable(self.order[p])) {
            return Some(p);
        }
        if self.repeat == RepeatMode::Context {
            return (0..self.order.len()).find(|&p| self.playable(self.order[p]));
        }
        None
    }

    fn prev_context_pos(&self, from: usize) -> Option<usize> {
        if let Some(p) = (0..from).rev().find(|&p| self.playable(self.order[p])) {
            return Some(p);
        }
        if self.repeat == RepeatMode::Context {
            return (from + 1..self.order.len()).rev().find(|&p| self.playable(self.order[p]));
        }
        None
    }

    fn begin_load(&mut self, uri: String, play: bool, position_ms: u64, now_ms: i64) -> Action {
        self.pending_loads = self.pending_loads.saturating_add(1);
        self.status = PlaybackStatus::Loading;
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
        let start = spec.start.min(items.len() - 1);
        *self = OfflineQueue {
            active: true,
            context_uri: spec.context_uri,
            order: if spec.shuffle { shuffled_order(items.len(), start, spec.seed) } else { (0..items.len()).collect() },
            items,
            shuffle: spec.shuffle,
            seed: spec.seed,
            repeat: spec.repeat,
            next_queue_id: self.next_queue_id,
            pending_loads: self.pending_loads,
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
        self.position_ms = 0;
        Action::Stop
    }

    /// `auto`: end of track (repeat-track applies); otherwise a user skip.
    fn advance(&mut self, auto: bool, play: bool, now_ms: i64) -> Action {
        if auto && self.repeat == RepeatMode::Track {
            if let Some(item) = self.current_item() {
                return self.begin_load(item.uri, true, 0, now_ms);
            }
        }
        if let Some(item) = self.queue.pop_front() {
            let uri = item.uri.clone();
            self.current = Some(Current::Queue(item));
            return self.begin_load(uri, play, 0, now_ms);
        }
        let from = if self.order.is_empty() { None } else { Some(self.pos) };
        match self.next_context_pos(from) {
            Some(p) => self.play_context_pos(p, play, now_ms),
            None => self.stop_at_end(),
        }
    }

    pub fn play(&mut self, now_ms: i64) -> Option<Action> {
        match self.status {
            PlaybackStatus::Paused => {
                self.status = PlaybackStatus::Playing;
                self.position_ts = now_ms;
                Some(Action::Play)
            }
            PlaybackStatus::Stopped => {
                let item = self.current_item()?;
                Some(self.begin_load(item.uri, true, 0, now_ms))
            }
            PlaybackStatus::Loading | PlaybackStatus::Playing => Some(Action::Play),
        }
    }

    pub fn pause(&mut self, now_ms: i64) -> Option<Action> {
        match self.status {
            PlaybackStatus::Playing => {
                self.position_ms = self.position_at(now_ms);
                self.status = PlaybackStatus::Paused;
                self.position_ts = now_ms;
                Some(Action::Pause)
            }
            PlaybackStatus::Loading => Some(Action::Pause),
            _ => None,
        }
    }

    pub fn toggle(&mut self, now_ms: i64) -> Option<Action> {
        if self.is_playing() { self.pause(now_ms) } else { self.play(now_ms) }
    }

    pub fn next(&mut self, now_ms: i64) -> Option<Action> {
        self.current.as_ref()?;
        let play = self.status != PlaybackStatus::Paused;
        Some(self.advance(false, play, now_ms))
    }

    pub fn prev(&mut self, now_ms: i64) -> Option<Action> {
        self.current.as_ref()?;
        let play = self.status != PlaybackStatus::Paused;
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
        self.repeat = mode;
    }

    pub fn add_to_queue(&mut self, uri: String) {
        let uid = format!("q{}", self.next_queue_id);
        self.next_queue_id += 1;
        self.queue.push_back(Item { uri, uid });
    }

    fn context_index_of(&self, uid: &str) -> Option<usize> {
        let idx: usize = uid.strip_prefix('o')?.parse().ok()?;
        (idx < self.items.len()).then_some(idx)
    }

    fn is_upcoming_context(&self, idx: usize) -> bool {
        if matches!(self.current, Some(Current::Context(c)) if c == idx) {
            return false;
        }
        self.order.iter().position(|&i| i == idx).is_some_and(|p| p > self.pos || self.repeat == RepeatMode::Context)
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
        let play = self.status != PlaybackStatus::Paused;
        if let Some(p) = self.queue.iter().position(|q| q.uid == uid) {
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
        Some(self.play_context_pos(p, play, now_ms))
    }

    /// Stops and forgets everything (another controller took over, or a user stop).
    pub fn reset(&mut self) {
        let next_queue_id = self.next_queue_id;
        let pending_loads = self.pending_loads;
        *self = OfflineQueue { next_queue_id, pending_loads, ..Default::default() };
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
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            Event::Paused { id, position_ms } if self.own(id) => {
                self.status = PlaybackStatus::Paused;
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            Event::Position { id, position_ms } if self.own(id) => {
                self.position_ms = position_ms as u64;
                self.position_ts = now_ms;
                out.changed = true;
            }
            Event::Stopped(id) if self.own(id) => {
                out.changed = self.status != PlaybackStatus::Stopped;
                self.status = PlaybackStatus::Stopped;
                self.position_ms = 0;
            }
            Event::TimeToPreload(id) if self.own(id) => {
                out.action = self.peek_next_uri().map(Action::Preload);
            }
            Event::EndOfTrack(id) if self.own(id) => {
                out.action = Some(self.advance(true, true, now_ms));
                out.changed = true;
            }
            Event::Unavailable { id, uri } if self.own(id) => {
                self.unavailable.insert(uri.clone());
                if self.current_uri() == Some(uri.as_str()) {
                    let play = self.status != PlaybackStatus::Paused;
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
        let mut next: Vec<PlaybackTrack> = self.queue.iter().map(|q| Self::track(q, TrackProvider::Queue)).collect();
        let current_idx = match self.current {
            Some(Current::Context(i)) => Some(i),
            _ => None,
        };
        let mut p = if self.order.is_empty() { None } else { Some(self.pos) };
        // Walk forward (wrapping with repeat context) until MAX_NEXT or a full cycle.
        let mut steps = 0usize;
        while next.len() < MAX_NEXT && steps < self.order.len() {
            let Some(np) = self.next_context_pos(p) else { break };
            if Some(np) <= p && self.repeat != RepeatMode::Context {
                break;
            }
            let idx = self.order[np];
            if Some(idx) == current_idx && steps > 0 {
                break;
            }
            if Some(idx) != current_idx {
                next.push(Self::track(&self.items[idx], TrackProvider::Context));
            }
            p = Some(np);
            steps += 1;
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
    /// items, and the position at `now_ms` (the snapshot only carries the last anchor).
    pub fn handover(&self, now_ms: i64, max_next: usize) -> (Vec<String>, u64) {
        let mut uris: Vec<String> = self.current_uri().map(str::to_string).into_iter().collect();
        uris.extend(self.next_tracks().into_iter().take(max_next).map(|t| t.uri));
        (uris, self.position_at(now_ms))
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
            playback_speed: if playing { 1.0 } else { 0.0 },
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
                can_skip_next: !next.is_empty(),
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
            last_error: None,
        }
    }
}

/// Picks the downloaded subset of `uris` and the start index within it.
/// `start_index` refers to `uris`; if that item isn't downloaded the next downloaded one starts.
pub(crate) fn select_downloaded(
    uris: &[String],
    is_downloaded: impl Fn(&str) -> bool,
    start_index: Option<usize>,
    start_uri: Option<&str>,
) -> (Vec<String>, usize) {
    let mut selected = Vec::new();
    let mut start = None;
    let wanted_index = start_uri.and_then(|u| uris.iter().position(|x| x == u)).or(start_index);
    for (i, u) in uris.iter().enumerate() {
        if !is_downloaded(u) {
            continue;
        }
        if start.is_none() && wanted_index.is_some_and(|w| i >= w) {
            start = Some(selected.len());
        }
        selected.push(u.clone());
    }
    (selected, start.unwrap_or(0))
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
            start,
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
        // a manual next still advances with repeat-track
        assert_eq!(load_uri(&q.next(0)).as_deref(), Some("spotify:track:1"));
        // preload with repeat track = the current track
        started(&mut q, 4);
        assert_eq!(q.on_event(Event::TimeToPreload(4), 0).action, Some(Action::Preload("spotify:track:1".into())));
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
        let (items, position) = q.handover(68_000, 50);
        assert_eq!(items, vec!["spotify:track:0", "spotify:track:1"]);
        assert_eq!(position, 60_000, "clamped to the duration");
        assert_eq!(q.handover(30_000, 0), (vec!["spotify:track:0".to_string()], 28_000));
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
        let (sel, start) = select_downloaded(&all, dl, Some(1), None);
        assert_eq!(sel, vec!["spotify:track:0", "spotify:track:3", "spotify:track:4"]);
        assert_eq!(start, 1, "next downloaded after the requested start");
        let (_, start) = select_downloaded(&all, dl, None, Some("spotify:track:4"));
        assert_eq!(start, 2);
        let (sel, start) = select_downloaded(&all, |_| false, Some(0), None);
        assert!(sel.is_empty());
        assert_eq!(start, 0);
    }
}
