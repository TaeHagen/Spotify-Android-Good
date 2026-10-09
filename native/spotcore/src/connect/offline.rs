//! OfflineController driver (docs/ARCHITECTURE.md §4.6): applies the [`OfflineQueue`] to the
//! shared Player while the engine has no online Spirc. Downloaded tracks only; the Player plays
//! them through the vendored offline hook without any network access.

use super::args::LoadArgs;
use super::offline_queue::{
    select_downloaded, Action, Adoption, Continuation, Elsewhere, Event, HandBack, Handover, LoadSpec, OfflineQueue,
    MAX_NEXT,
};
use super::{hub, now_ms, player_events, uri, Ctl};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{ActiveDeviceRef, OfflineTrackRecord, PlaybackSnapshot, RepeatMode};
use crate::{engine, events, offline as downloads};
use librespot_connect::{
    ConnectSnapshot, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, SnapshotPlayStatus,
    SnapshotTrack, TrackProvider,
};
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

/// Another device is active and plays (not just paused as the account's active one), per the
/// latest cluster.
pub(crate) fn elsewhere_playing() -> bool {
    hub::cluster().is_some_and(|c| elsewhere(&c, &hub::me()).playing)
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

/// The speed the app's sink plays at (see `connect::set_speed`): the queue's positions follow it.
pub(crate) fn set_speed(speed: f64) {
    let active = {
        let mut q = QUEUE.lock();
        q.set_speed(speed, now_ms());
        q.active
    };
    if active {
        hub::publish();
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

/// What the queue takes over from this device's Spirc playback when the session goes away (see
/// [`take_over`]): the current track and the visible tracks around it in play order (user queue
/// and downloaded suggestions included, others skipped; one pass of the context: with repeat-all
/// the queue's own repeat wraps it, see `restore::one_pass`), up to the first one that isn't
/// downloaded on either side (the queue ends there), the position at `now_ms`, the repeat mode
/// and whether it plays, and
/// where the context goes on after the window (see [`continuation`]). `None` unless this device
/// is active with a downloaded current track that isn't stopped.
#[cfg(test)]
pub(crate) fn handoff(s: &ConnectSnapshot, downloaded: impl Fn(&str) -> bool, now_ms: i64) -> Option<Adoption> {
    handoff_with(s, downloaded, None, now_ms)
}

/// [`handoff`], also of a streamed current track whose data is all in the Player (`buffered`, see
/// the vendored `Player::fully_buffered`): it plays to its end, then the downloads after it. The
/// window around it is still the downloaded tracks only, and it isn't loaded again offline
/// ([`Adoption::streamed`]). A train losing the signal cut such a song off at the network-loss
/// cap (60 s) and froze it as a restore point, and the downloads after it never played.
pub(crate) fn handoff_with(
    s: &ConnectSnapshot,
    downloaded: impl Fn(&str) -> bool,
    buffered: Option<&str>,
    now_ms: i64,
) -> Option<Adoption> {
    if !s.is_active {
        return None;
    }
    let (playing, loading) = match s.status {
        SnapshotPlayStatus::Playing => (true, false),
        SnapshotPlayStatus::Paused => (false, false),
        SnapshotPlayStatus::LoadingPlay => (true, true),
        SnapshotPlayStatus::LoadingPause => (false, true),
        SnapshotPlayStatus::Stopped => return None,
    };
    let playable = |uri: &str| downloaded(uri) || buffered == Some(uri);
    let current = s.track.as_ref().filter(|t| !t.hidden && playable(&t.uri))?;
    let streamed = !downloaded(&current.uri);
    // A suggestion that isn't downloaded can't play offline and isn't the context's: skipped, it
    // doesn't end the window (a hand-back gets new ones from Spirc).
    let keep = |t: &SnapshotTrack| t.provider != TrackProvider::Suggestion || downloaded(&t.uri);
    let mut f = s.clone();
    f.prev_tracks.retain(keep);
    f.next_tracks.retain(keep);
    // The user queue's tracks that aren't downloaded stay too: online they stream, offline the
    // queue skips them (unavailable) and plays on.
    let pass = super::restore::one_pass(&f, current, |t, _| t.provider == TrackProvider::Queue || downloaded(&t.uri));
    let order = super::restore::shuffle_order(&f, current);
    let goes_on_at = |i| continuation(&f, i, order.clone());
    let mut tracks = pass.tracks;
    let mut goes_on = pass.ended_at.and_then(goes_on_at);
    // Spirc lists at most MAX_NEXT next tracks (one less before a smart-shuffle pair): a full
    // list that ran out before the context's end goes on past it, so the window ends before its
    // last track and the context goes on there.
    if pass.ended_at.is_none() && pass.ran_out && s.next_tracks.len() + 1 >= MAX_NEXT {
        if let Some(c) = pass.last_next.and_then(goes_on_at) {
            tracks.pop();
            goes_on = Some(c);
        }
    }
    let indices = |provider: fn(&TrackProvider) -> bool| {
        tracks.iter().enumerate().filter(|(_, t)| provider(&t.provider)).map(|(i, _)| i).collect::<Vec<_>>()
    };
    // Tracks that aren't the context's (the user queue, suggestions): a push of the queue to
    // another device can't start the context there, the user queue's are queued again there.
    let outside = indices(|p| *p != TrackProvider::Context);
    let user_queued = indices(|p| *p == TrackProvider::Queue);
    let repeat = if s.repeat_track {
        RepeatMode::Track
    } else if s.repeat_context {
        RepeatMode::Context
    } else {
        RepeatMode::Off
    };
    Some(Adoption {
        context_uri: Some(s.context_uri.clone()).filter(|c| !c.is_empty()),
        uris: tracks.iter().map(|t| t.uri.clone()).collect(),
        start: pass.start,
        outside,
        user_queued,
        // As far as it was heard (a stalled stream goes on in the snapshot).
        position_ms: super::restore::position_heard(s, now_ms, super::restore::player_decoded().as_ref()).max(0) as u64,
        duration_ms: s.duration_ms.max(0) as u64,
        playing,
        loading,
        repeat,
        repeat_context: s.repeat_context,
        shuffle: s.shuffle || s.smart_shuffle,
        continuation: goes_on,
        restart: uri::is_resolvable_context(&s.context_uri).then(|| Continuation {
            context_uri: s.context_uri.clone(),
            start_uri: None,
            smart_shuffle: s.smart_shuffle,
            order: None,
            tracks: None,
        }),
        streamed,
    })
}

/// Where the context of `s` goes on after a handed-over window that ends at its next track `from`
/// (not downloaded, or the end of Spirc's full list): Spirc continues there when the session is
/// back (see `HandBack`), a shuffled session in its `order`. At the first context track from
/// there, before the context's end (suggestions are skipped: with smart shuffle on Spirc adds
/// new ones; the user queue comes before the context's tracks, so it is in the window). A plain
/// track list, whose context can't be loaded again, goes on as the rest of it Spirc listed (in
/// play order, kept as its shuffled order). `None` when nothing of it comes after the window.
fn continuation(s: &ConnectSnapshot, from: usize, order: Option<Vec<String>>) -> Option<Continuation> {
    let resolvable = uri::is_resolvable_context(&s.context_uri);
    let mut list = Vec::new();
    for t in &s.next_tracks[from..] {
        if t.uri == uri::DELIMITER_URI {
            // The context's end (repeat-all wraps there, else autoplay follows).
            break;
        }
        if t.hidden || t.provider != TrackProvider::Context {
            continue;
        }
        if resolvable {
            let (context_uri, start_uri) = (s.context_uri.clone(), Some(t.uri.clone()));
            return Some(Continuation { context_uri, start_uri, smart_shuffle: s.smart_shuffle, order, tracks: None });
        }
        list.push(t.uri.clone());
    }
    (!resolvable && !list.is_empty()).then(|| Continuation {
        context_uri: s.context_uri.clone(),
        start_uri: None,
        smart_shuffle: s.smart_shuffle,
        // (by uri: a list's uids come from its positions)
        order: (s.shuffle || s.smart_shuffle).then(|| list.clone()),
        tracks: Some(list),
    })
}

/// The end of a handed-over window while a visible session is up: Spirc goes on with the
/// context at the first track after the window (its request id then takes the Player from the
/// queue). The queue's track is stopped first (a skip may have ended the window while it
/// played), and the queue's view stays shown until Spirc has its track (see
/// `hub::set_handing_back`); a failed load makes this phone inactive (`hub`). If it can't be sent
/// the window goes on as without a continuation.
fn hand_back(back: HandBack) -> AppResult<()> {
    let view = QUEUE.lock().snapshot(hub::this_device_ref(), hub::mixer_volume());
    let sent = (|| {
        let spirc = hub::spirc().ok_or_else(AppError::not_connected)?;
        let options = Options {
            shuffle: back.shuffle || back.smart_shuffle,
            repeat: back.repeat_context,
            repeat_track: back.repeat_track,
            smart_shuffle: back.smart_shuffle,
            shuffle_order: back.order.clone(),
        };
        let options = |playing_track| LoadRequestOptions {
            start_playing: back.play,
            seek_to: 0,
            playing_track,
            context_options: Some(LoadContextOptions::Options(options.clone())),
        };
        let request = match &back.tracks {
            // A plain track list goes on as the rest of it.
            Some(tracks) => LoadRequest::from_tracks(tracks.clone(), options(Some(PlayingTrack::Index(0)))),
            None => LoadRequest::from_context_uri(back.context_uri.clone(), options(back.start_uri.clone().map(PlayingTrack::Uri))),
        };
        hub::set_handing_back(view);
        if let Some(player) = engine::player_host::player() {
            player.stop();
        }
        super::local::sent(spirc.activate())?;
        super::local::sent(spirc.load(request))
    })();
    match sent {
        Ok(()) => {
            log::info!("online again: the context goes on in Spirc");
            Ok(())
        }
        Err(e) => {
            log::info!("the context couldn't go back to Spirc ({e}), the downloads go on");
            hub::forget_hand_back();
            let action = QUEUE.lock().hand_back_failed(false, now_ms());
            apply(action)
        }
    }
}

/// A visible online session is up with its cluster, and no other device is active (that one's
/// session isn't taken away): the end of a handed-over window hands back to Spirc.
fn hand_back_allowed() -> bool {
    let me = hub::me();
    engine::is_online()
        && engine::network_available()
        && hub::spirc().is_some()
        && hub::cluster().is_some_and(|c| c.active_device_id.is_empty() || c.active_device_id == me)
}

/// The session of the Spirc `generation` goes away without a network (or offline mode was turned
/// on) while this device plays (or paused) a downloaded track: the queue takes the Player over as
/// it is, without a gap (see [`handoff`]), before anything pauses it. The Spirc lets go of the
/// Player first, no restore point is kept. Returns whether it did.
pub(crate) fn take_over(generation: u64) -> bool {
    let Some((spirc, snap)) = hub::link_snapshot(generation) else { return false };
    let now = now_ms();
    // A streamed current track whose data is all in the Player plays on too.
    let buffered = engine::player_host::player().and_then(|p| p.fully_buffered()).and_then(|u| u.to_uri().ok());
    let Some(adoption) = handoff_with(&snap, downloads::is_downloaded, buffered.as_deref(), now) else { return false };
    if engine::player_host::player().is_none() {
        return false;
    }
    // From now on the dying Spirc doesn't pause or stop the Player.
    spirc.release_player();
    log::info!("the downloads play on offline ({} tracks)", adoption.uris.len());
    let action = QUEUE.lock().adopt(adoption, now);
    if let Some(action) = action {
        // Resumes a Player that the Spirc paused when its task ended.
        if let Err(e) = apply(action) {
            log::warn!("offline playback: {e}");
        }
    }
    // Stops nothing: the queue owns the Player.
    super::restore::clear();
    hub::publish();
    true
}

/// The Player's thread died: the queue stops where it was (a play starts it again on a new
/// Player), and its request ids are forgotten. Also when the Player is released.
pub(crate) fn player_lost() {
    let active = {
        let mut q = QUEUE.lock();
        q.player_lost(now_ms());
        q.active
    };
    if active {
        hub::publish();
    }
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
    if let Action::HandBack(back) = action {
        return hand_back(back);
    }
    let is_load = matches!(action, Action::Load { .. });
    let result = apply_to_player(action);
    if is_load && result.is_err() {
        // No request id comes for it, the next one is someone else's (Spirc's).
        QUEUE.lock().load_not_sent();
    }
    result
}

fn apply_to_player(action: Action) -> AppResult<()> {
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
        Action::HandBack(_) => {}
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

/// Handles a playback / queue command while the offline queue owns the Player. A Player whose
/// thread died is replaced first (the queue stopped where it was, a play loads it again).
pub(crate) async fn control(cmd: &Ctl) -> AppResult<()> {
    if !is_active() {
        return Err(AppError::not_connected());
    }
    if engine::player_host::player().is_none() {
        engine::player_host::ensure_player_for_offline().await?;
    }
    // Online and visible, the Player is bound to the session: a queued track may stream.
    let can_stream = engine::is_online() && engine::network_available() && hub::spirc().is_some();
    let now = now_ms();
    let restarts = {
        let q = QUEUE.lock();
        super::restarts_stopped(cmd, q.stopped_with_item())
    };
    if restarts {
        // A fresh chance for the load brake, like a new load.
        player_events::on_user_load();
    }
    let action = {
        let mut q = QUEUE.lock();
        if !q.active {
            return Err(AppError::not_connected());
        }
        q.set_hand_back(can_stream);
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
                if !can_stream && !downloads::is_downloaded(u) {
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
    let result = action.map_or(Ok(()), apply);
    // No yield here: a pause on this device never hands the session away. Published also when
    // the Player refused it: the snapshot must not keep a state the queue left.
    hub::publish();
    result
}

fn convert(event: &PlayerEvent) -> Option<Event> {
    Some(match event {
        PlayerEvent::PlayRequestIdChanged { play_request_id } => Event::RequestId(*play_request_id),
        PlayerEvent::Loading { play_request_id, position_ms, .. } => {
            Event::Loading { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Playing { play_request_id, position_ms, .. } => {
            Event::Playing { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Paused { play_request_id, position_ms, .. } => {
            Event::Paused { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Seeked { play_request_id, position_ms, .. } => {
            Event::Seeked { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::PositionCorrection { play_request_id, position_ms, .. } => {
            Event::Position { id: *play_request_id, position_ms: *position_ms }
        }
        PlayerEvent::Stalled { play_request_id, position_ms, .. } => {
            Event::Stalled { id: *play_request_id, position_ms: *position_ms }
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

/// A Player event for the queue, also while it is inactive: the request ids of its loads that
/// arrive after a reset must still be counted, or a later foreign (Spirc) load is taken as its
/// own. An inactive queue only does that bookkeeping (no action, nothing changed).
fn feed(q: &mut OfflineQueue, event: &PlayerEvent, now_ms: i64) -> Option<super::offline_queue::Outcome> {
    Some(q.on_event(convert(event)?, now_ms))
}

pub(crate) fn on_player_event(event: &PlayerEvent) {
    let allowed = hand_back_allowed();
    let outcome = {
        let mut q = QUEUE.lock();
        q.set_hand_back(allowed);
        feed(&mut q, event, now_ms())
    };
    let Some(outcome) = outcome else { return };
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

/// The session is back (a cluster, the engine online): a queue stopped at the end of its window
/// offline hands back to Spirc after it (see `OfflineQueue::resume_window_end`).
/// Also keeps the queue's hand-back flag (its Next shown as possible) as the session is now.
pub(crate) fn resume_window_end() {
    let allowed = hand_back_allowed();
    let (action, active) = {
        let mut q = QUEUE.lock();
        q.set_hand_back(allowed);
        (q.resume_window_end(now_ms()), q.active)
    };
    if let Some(action) = action {
        if let Err(e) = apply(action) {
            log::warn!("offline playback: {e}");
        }
    }
    if active {
        hub::publish();
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

    fn st(uri: &str, provider: librespot_connect::TrackProvider) -> SnapshotTrack {
        SnapshotTrack { uri: uri.into(), uid: uri.into(), provider, context_index: None, hidden: false, metadata: Default::default() }
    }

    fn playing(prev: &[&str], current: &str, next: &[&str]) -> ConnectSnapshot {
        use librespot_connect::TrackProvider::*;
        ConnectSnapshot {
            is_active: true,
            status: SnapshotPlayStatus::Playing,
            position_ms: 10_000,
            position_timestamp_ms: 1_000_000,
            playback_speed: 1.0,
            duration_ms: 180_000,
            context_uri: "spotify:playlist:p".into(),
            track: Some(st(current, Context)),
            prev_tracks: prev.iter().map(|u| st(u, Context)).collect(),
            next_tracks: next.iter().map(|u| st(u, if u.contains('q') { Queue } else { Context })).collect(),
            repeat_context: true,
            ..Default::default()
        }
    }

    #[test]
    fn downloaded_playback_is_handed_over_up_to_the_first_gap() {
        let downloaded = |u: &str| !u.ends_with('x');
        let mut s = playing(&["t:x", "t:1", "t:2"], "t:3", &["t:q1", "t:4", "t:5x", "t:6"]);
        // without repeat-all a delimiter only separates the context from autoplay
        s.repeat_context = false;
        s.next_tracks.insert(1, SnapshotTrack { hidden: true, ..st("t:hidden-x", librespot_connect::TrackProvider::Context) });
        s.next_tracks.insert(2, st(uri::DELIMITER_URI, librespot_connect::TrackProvider::Context));
        let a = handoff(&s, downloaded, 1_005_000).expect("handed over");
        assert_eq!(a.uris, ["t:1", "t:2", "t:3", "t:q1", "t:4"]);
        assert_eq!(a.start, 2);
        assert_eq!(a.position_ms, 15_000, "where it is now");
        assert_eq!(a.duration_ms, 180_000);
        assert!(a.playing && !a.loading);
        assert_eq!(a.repeat, RepeatMode::Off);
        assert_eq!(a.context_uri.as_deref(), Some("spotify:playlist:p"));
        // the context goes on at the first track that isn't downloaded
        assert_eq!(a.continuation.as_ref().and_then(|c| c.start_uri.as_deref()), Some("t:5x"));
        // a new pass (repeat-all turned on later) starts at the context's start
        assert_eq!(a.restart.as_ref().map(|c| (c.context_uri.as_str(), c.start_uri.as_deref())), Some(("spotify:playlist:p", None)));
        // paused stays paused
        let paused = ConnectSnapshot { status: SnapshotPlayStatus::Paused, ..s.clone() };
        assert!(handoff(&paused, downloaded, 1_005_000).is_some_and(|a| !a.playing && a.position_ms == 10_000));
        // a streamed current track, a stopped or another device's playback: frozen as before
        assert!(handoff(&playing(&[], "t:3x", &["t:4"]), downloaded, 0).is_none());
        assert!(handoff(&ConnectSnapshot { status: SnapshotPlayStatus::Stopped, ..s.clone() }, downloaded, 0).is_none());
        assert!(handoff(&ConnectSnapshot { is_active: false, ..s }, downloaded, 0).is_none());
    }

    #[test]
    fn a_fully_buffered_streamed_track_is_handed_over_with_the_downloads_after_it() {
        let downloaded = |u: &str| !u.ends_with('x');
        let mut s = playing(&["t:1", "t:2x"], "t:3x", &["t:4", "t:5", "t:6x", "t:7"]);
        s.repeat_context = false;
        // its data is all in the Player: it plays on, then the downloads after it
        let a = handoff_with(&s, downloaded, Some("t:3x"), 1_005_000).expect("handed over");
        assert_eq!(a.uris, ["t:3x", "t:4", "t:5"], "the window around it is the downloads only");
        assert_eq!(a.start, 0);
        assert!(a.streamed && a.playing);
        assert_eq!(a.position_ms, 15_000);
        assert_eq!(a.continuation.as_ref().and_then(|c| c.start_uri.as_deref()), Some("t:6x"));
        // not all there (or another track is): frozen as before
        assert!(handoff_with(&s, downloaded, None, 1_005_000).is_none());
        assert!(handoff_with(&s, downloaded, Some("t:4"), 1_005_000).is_none());
        // a downloaded current track isn't streamed
        let d = playing(&[], "t:3", &["t:4"]);
        assert!(handoff_with(&d, downloaded, Some("t:3"), 0).is_some_and(|a| !a.streamed));
    }

    #[test]
    fn repeat_all_hands_over_one_pass() {
        use librespot_connect::TrackProvider::Context;
        let all = |_: &str| true;
        let delim = || SnapshotTrack { hidden: true, ..st(uri::DELIMITER_URI, Context) };
        // the first pass: the next tracks wrap into the context again
        let mut s = playing(&["t:1", "t:2"], "t:3", &[]);
        s.next_tracks = vec![delim(), st("t:1", Context), st("t:2", Context), st("t:3", Context), delim(), st("t:1", Context)];
        let a = handoff(&s, all, 1_000_000).expect("handed over");
        assert_eq!((a.uris.as_slice(), a.start), (["t:1", "t:2", "t:3"].map(String::from).as_slice(), 2));
        assert_eq!(a.continuation, None, "the queue's own repeat wraps");
        // a later pass: the previous tracks hold the end of the last one
        let mut s = playing(&[], "t:2", &[]);
        s.prev_tracks = vec![st("t:3", Context), delim(), st("t:1", Context)];
        s.next_tracks = vec![st("t:3", Context), delim(), st("t:1", Context), st("t:2", Context)];
        let a = handoff(&s, all, 1_000_000).expect("handed over");
        assert_eq!((a.uris.as_slice(), a.start), (["t:1", "t:2", "t:3"].map(String::from).as_slice(), 1));
        // the wrap's delimiter out of sight: a uid seen already ends the pass
        let mut s = playing(&["t:2"], "t:3", &[]);
        s.next_tracks = vec![st("t:1", Context), st("t:2", Context), st("t:3", Context)];
        let a = handoff(&s, all, 1_000_000).expect("handed over");
        assert_eq!(a.uris, ["t:2", "t:3", "t:1"]);
    }

    #[test]
    fn repeat_all_hands_over_the_whole_pass() {
        use librespot_connect::TrackProvider::Context;
        let delim = || SnapshotTrack { hidden: true, uid: String::new(), ..st(uri::DELIMITER_URI, Context) };
        let t = |n: u32| st(&format!("t:{n}"), Context);
        let names = |r: std::ops::RangeInclusive<u32>| r.map(|n| format!("t:{n}")).collect::<Vec<_>>();
        // Spirc keeps 10 previous tracks: the start of the pass comes after the wrap's delimiter
        let mut s = playing(&[], "t:18", &[]);
        s.prev_tracks = (8..=17).map(t).collect();
        s.next_tracks = [t(19), t(20), delim()].into_iter().chain((1..=20).map(t)).collect();
        let a = handoff(&s, |_| true, 1_000_000).expect("handed over");
        assert_eq!((a.uris.clone(), a.start), (names(1..=20), 17));
        assert_eq!(a.continuation, None);
        // ... up to a track that isn't downloaded: the window's wrap goes to the context's start
        // (online, see `Adoption::restart`)
        let a = handoff(&s, |u| u != "t:3", 1_000_000).expect("handed over");
        assert_eq!(a.uris[..3], ["t:1", "t:2", "t:8"]);
        assert_eq!(a.start, 12);
        assert_eq!((a.continuation, a.restart.and_then(|c| c.start_uri)), (None, None));
        let a = handoff(&s, |u| !["t:1", "t:2", "t:3", "t:4", "t:5"].contains(&u), 1_000_000).expect("handed over");
        assert_eq!((a.uris.first().map(String::as_str), a.start), (Some("t:8"), 10));
        assert!(a.continuation.is_none() && a.restart.is_some());
    }

    #[test]
    fn a_long_context_on_repeat_all_goes_on_at_its_start() {
        use librespot_connect::TrackProvider::Context;
        // 200 tracks at t190: the full list holds the pass's end and the next pass's start only
        let t = |n: u32| st(&format!("t:{n}"), Context);
        let names = |r: std::ops::RangeInclusive<u32>| r.map(|n| format!("t:{n}"));
        let delim = SnapshotTrack { hidden: true, uid: "delimiter0".into(), ..st(uri::DELIMITER_URI, Context) };
        let mut s = playing(&[], "t:190", &[]);
        s.prev_tracks = (180..=189).map(t).collect();
        s.next_tracks = (191..=200).map(t).chain([delim]).chain((1..=69).map(t)).collect();
        let a = handoff(&s, |_| true, 0).expect("handed over");
        assert_eq!(a.uris, names(1..=69).chain(names(180..=200)).collect::<Vec<_>>());
        assert_eq!(a.start, 79);
        // the window's wrap: back online Spirc plays the whole context from its start
        assert!(a.continuation.is_none() && a.restart.is_some());
    }

    #[test]
    fn a_full_list_ends_the_window_before_its_last_track() {
        use librespot_connect::TrackProvider::Context;
        // Spirc lists 80 next tracks of a longer context, all downloaded: the context goes on at
        // the last one (the window ends before it)
        let mut s = playing(&[], "t:0", &[]);
        s.repeat_context = false;
        s.next_tracks = (1..=80).map(|n| st(&format!("t:{n}"), Context)).collect();
        let a = handoff(&s, |_| true, 0).expect("handed over");
        assert_eq!(a.uris.len(), 80, "the current track and 79 next");
        assert_eq!(a.continuation.and_then(|c| c.start_uri).as_deref(), Some("t:80"));
        // hidden entries in it too
        s.next_tracks[79].hidden = true;
        let a = handoff(&s, |_| true, 0).expect("handed over");
        assert_eq!(a.continuation.and_then(|c| c.start_uri).as_deref(), Some("t:79"));
        // a short list is all there is
        s.next_tracks.truncate(5);
        let a = handoff(&s, |_| true, 0).expect("handed over");
        assert_eq!((a.uris.len(), a.continuation), (6, None));
    }

    #[test]
    fn smart_shuffle_suggestions_dont_cut_the_window() {
        use librespot_connect::TrackProvider::{Context, Suggestion};
        let downloaded = |u: &str| !u.ends_with('x');
        let mut s = playing(&[], "t:0", &[]);
        s.repeat_context = false;
        s.smart_shuffle = true;
        s.prev_tracks = vec![st("t:p2", Context), st("t:szx", Suggestion), st("t:p1", Context)];
        s.next_tracks = vec![
            st("t:1", Context),
            st("t:sxx", Suggestion),
            st("t:2", Context),
            st("t:3", Context),
            st("t:syx", Suggestion),
            st("t:4", Context),
            st("t:s", Suggestion),
        ];
        let a = handoff(&s, downloaded, 0).expect("handed over");
        assert_eq!(a.uris, ["t:p2", "t:p1", "t:0", "t:1", "t:2", "t:3", "t:4", "t:s"]);
        assert_eq!(a.start, 2);
        // a downloaded suggestion plays offline too, not as a track of the context
        assert_eq!(a.outside, [7]);
    }

    #[test]
    fn the_context_goes_on_after_a_suggestion_or_a_queued_track() {
        use librespot_connect::TrackProvider::{Context, Queue, Suggestion};
        let downloaded = |u: &str| !u.ends_with('x');
        // smart shuffle: a suggestion that isn't downloaded is skipped, not the window's end
        let mut s = playing(&[], "t:0", &[]);
        s.repeat_context = false;
        s.smart_shuffle = true;
        s.next_tracks = vec![st("t:1", Context), st("t:sx", Suggestion), st("t:2", Context)];
        let a = handoff(&s, downloaded, 0).expect("handed over");
        assert_eq!((a.uris.as_slice(), a.continuation.as_ref()), (["t:0", "t:1", "t:2"].map(String::from).as_slice(), None));
        // a gap at a context track after it: the context goes on there, Spirc adds suggestions
        s.next_tracks = vec![st("t:1", Context), st("t:sx", Suggestion), st("t:2x", Context), st("t:3", Context)];
        let a = handoff(&s, downloaded, 0).expect("handed over");
        assert_eq!(a.uris, ["t:0", "t:1"]);
        let c = a.continuation.expect("continuation");
        assert_eq!((c.start_uri.as_deref(), c.smart_shuffle), (Some("t:2x"), true));
        // a queued track that isn't downloaded stays in the window (streamed online, skipped
        // offline), the context goes on after it
        s.next_tracks = vec![st("t:qx", Queue), st("t:q2", Queue), st("t:1", Context)];
        let a = handoff(&s, downloaded, 0).expect("handed over");
        assert_eq!(a.uris, ["t:0", "t:qx", "t:q2", "t:1"]);
        assert_eq!((a.user_queued.as_slice(), a.continuation), ([1, 2].as_slice(), None));
        // the context's end before any of its tracks: none
        s.next_tracks = vec![st("t:sx", Suggestion), st(uri::DELIMITER_URI, Context), st("t:auto", Context)];
        assert_eq!(handoff(&s, downloaded, 0).expect("handed over").continuation, None);
        // a plain track list: the rest of it as listed
        s.context_uri = "spotify:web-api".into();
        s.next_tracks = vec![st("t:1x", Context)];
        let c = handoff(&s, downloaded, 0).expect("handed over").continuation.expect("continuation");
        assert_eq!((c.start_uri, c.tracks.as_deref()), (None, Some(["t:1x".to_string()].as_slice())));
    }

    #[test]
    fn a_track_list_goes_on_as_the_rest_of_it() {
        use librespot_connect::TrackProvider::{Autoplay, Context};
        let downloaded = |u: &str| !u.ends_with('x');
        // Your Episodes as a list: a streamed episode handed over alone
        let mut s = playing(&["t:1"], "t:2x", &[]);
        s.context_uri = "spotify:web-api".into();
        s.repeat_context = false;
        s.next_tracks = vec![
            st("t:3x", Context),
            st("t:4", Context),
            SnapshotTrack { hidden: true, ..st("t:hidden", Context) },
            st("t:5x", Context),
            st(uri::DELIMITER_URI, Context),
            st("t:auto", Autoplay),
        ];
        let a = handoff_with(&s, downloaded, Some("t:2x"), 0).expect("handed over");
        assert_eq!(a.uris, ["t:1", "t:2x"]);
        let c = a.continuation.expect("continuation");
        let rest = ["t:3x", "t:4", "t:5x"].map(String::from).to_vec();
        assert_eq!((c.start_uri, c.tracks.as_ref(), c.order), (None, Some(&rest), None), "up to the list's end");
        assert!(a.restart.is_none(), "its start isn't known");
        // shuffled: that order is kept (by uri)
        s.shuffle = true;
        let c = handoff_with(&s, downloaded, Some("t:2x"), 0).and_then(|a| a.continuation).expect("continuation");
        assert_eq!(c.order.as_ref(), Some(&rest));
        // nothing of it after the window: none
        s.next_tracks.clear();
        assert!(handoff_with(&s, downloaded, Some("t:2x"), 0).is_some_and(|a| a.continuation.is_none()));
    }

    #[test]
    fn an_id_arriving_after_a_reset_is_still_counted() {
        let spec = |start| LoadSpec {
            context_uri: None,
            uris: vec!["spotify:track:a".into(), "spotify:track:b".into()],
            start: Some(start),
            position_ms: 0,
            shuffle: false,
            repeat: RepeatMode::Off,
            play: true,
            seed: 1,
        };
        let id = |play_request_id| PlayerEvent::PlayRequestIdChanged { play_request_id };
        let mut q = OfflineQueue::default();
        // a next sent just before a stop (a load while the network returned): its id comes late
        q.load(spec(0), 0);
        q.reset();
        let out = feed(&mut q, &id(1), 0).expect("converted");
        assert!(!out.changed && out.action.is_none());
        q.load(spec(1), 0);
        feed(&mut q, &id(2), 0);
        assert!(q.active);
        // Spirc's load (a transfer to this phone) supersedes the queue
        let out = feed(&mut q, &id(3), 0).expect("converted");
        assert!(out.changed);
        assert!(!q.active);
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
