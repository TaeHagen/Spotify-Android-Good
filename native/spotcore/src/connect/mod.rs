//! Playback commands routed to the active device (local Spirc, offline controller, or a remote
//! Connect device), playback snapshots and the device list (docs/ARCHITECTURE.md §6.2, §8).
//!
//! * Routing ([`route`]): this device's Spirc if it is active or nothing is active (activating
//!   first for loads), the OfflineController while it owns the Player or when the engine has no
//!   session (downloaded items only), otherwise connect-state commands to the active device.
//! * State flows out as events: `playback` (composed in [`hub`], enriched in [`metadata`]),
//!   `devices`, `queueMetadata` and `error` (context `connect` / `playback`).
//! * The engine drives the lifecycle through the crate-internal API below ([`attach`],
//!   [`detach`], [`on_player_event`], reconnect restore hooks).

mod args;
mod devices;
mod hub;
mod local;
mod metadata;
mod offline;
mod offline_queue;
mod player_events;
mod remote;
mod restore;
mod route;
mod snapshot;
mod uri;

use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::RepeatMode;
use crate::rpc::{ok, parse_args, to_value};
use crate::engine;
use args::{
    AudioOutputArgs, EnabledArgs, LoadArgs, MoveArgs, RepeatArgs, SeekArgs, SpeedArgs, TransferArgs, UidArgs, UriArgs,
    VolumeArgs,
};
use librespot_core::dealer::protocol::TransferOptions;
use librespot_core::spclient::TransferRequest;
use librespot_playback::mixer::Mixer;
use route::{CommandKind, RouteInput, Target};
use serde_json::Value;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// How long a playback command waits for a connect attempt in flight (below Kotlin's 15 s
/// command timeout).
const CONNECTING_WAIT: Duration = Duration::from_secs(10);

pub(crate) use hub::Attachment;
pub(crate) use player_events::on_player_event;

/// Local wall clock in epoch ms (the clock of `PlaybackSnapshot.positionTimestampMs`).
pub(crate) fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// A playback or queue command (everything except load / volume / output / transfer).
#[derive(Debug, Clone)]
pub(crate) enum Ctl {
    Play,
    Pause,
    Toggle,
    Next,
    Prev,
    Seek(u64),
    Shuffle(bool),
    SmartShuffle(bool),
    Repeat(RepeatMode),
    QueueAdd(String),
    QueueRemove(String),
    QueueMove(String, usize),
    QueueClear,
    SkipTo(String),
}

impl Ctl {
    fn kind(&self) -> CommandKind {
        match self {
            Ctl::QueueAdd(_) | Ctl::QueueRemove(_) | Ctl::QueueMove(..) | Ctl::QueueClear | Ctl::SkipTo(_) => {
                CommandKind::Queue
            }
            _ => CommandKind::Control,
        }
    }
}

pub async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "player.load" => load(parse_args::<LoadArgs>(args)?.validate()?).await,
        "player.play" => control(Ctl::Play).await,
        "player.pause" => control(Ctl::Pause).await,
        "player.togglePlay" => control(Ctl::Toggle).await,
        "player.next" => control(Ctl::Next).await,
        "player.prev" => control(Ctl::Prev).await,
        "player.seek" => control(Ctl::Seek(parse_args::<SeekArgs>(args)?.position_ms)).await,
        "player.setShuffle" => control(Ctl::Shuffle(parse_args::<EnabledArgs>(args)?.enabled)).await,
        "player.setSmartShuffle" => control(Ctl::SmartShuffle(parse_args::<EnabledArgs>(args)?.enabled)).await,
        "player.setRepeat" => control(Ctl::Repeat(parse_args::<RepeatArgs>(args)?.mode)).await,
        "player.setVolume" => set_volume(parse_args(args)?),
        "player.setAudioOutput" => set_audio_output(parse_args(args)?),
        "player.setSpeed" => set_speed(parse_args::<SpeedArgs>(args)?.validate()?),
        "player.applySettings" => engine::apply_player_settings(args),
        "queue.add" => control(Ctl::QueueAdd(parse_args::<UriArgs>(args)?.uri)).await,
        "queue.remove" => control(Ctl::QueueRemove(parse_args::<UidArgs>(args)?.uid)).await,
        "queue.move" => {
            let a: MoveArgs = parse_args(args)?;
            control(Ctl::QueueMove(a.uid, a.to_index)).await
        }
        "queue.clear" => control(Ctl::QueueClear).await,
        "queue.skipTo" => control(Ctl::SkipTo(parse_args::<UidArgs>(args)?.uid)).await,
        "connect.transfer" => transfer(parse_args(args)?).await,
        "connect.refreshDevices" => to_value(&hub::refresh_devices().await),
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

/// The active device per the cluster, or the one a play was just sent to (see
/// `hub::remote_activating`).
fn active_device() -> Option<String> {
    hub::active_device_id().or_else(hub::remote_activating)
}

fn decide(kind: CommandKind, downloaded: bool) -> AppResult<Target> {
    let me = hub::me();
    let active = active_device();
    let input = RouteInput {
        online: engine::is_online(),
        network: engine::network_available(),
        spirc: hub::spirc().is_some(),
        // An activation just sent (a load, the restore) counts: commands queue behind it.
        local_active: hub::local_active_or_activating(),
        offline_active: offline::is_active(),
        offline_yields: offline::yields(),
        active_device: active.as_deref(),
        me: &me,
    };
    route::route(&input, kind, downloaded)
}

/// What [`should_wait`] looks at.
#[derive(Debug, Clone, Copy)]
struct WaitInput {
    online: bool,
    /// Android reports a usable network.
    network: bool,
    /// A Spirc is attached (visible to Spotify Connect).
    spirc: bool,
    /// The attached Spirc's first cluster arrived (it tells which device is active).
    cluster_known: bool,
    /// A reconnect restore is pending, nothing holds it and it isn't overdue: its decision is on
    /// its way.
    restore_deciding: bool,
    /// A connect attempt is in flight (`engine::is_connecting`).
    connecting: bool,
    /// The settings want this device visible to Spotify Connect.
    visible: bool,
    /// Offline mode (`settings.offline`).
    offline_mode: bool,
    offline_active: bool,
}

impl WaitInput {
    fn now() -> Self {
        let (spirc, cluster_known, restore_deciding) = {
            let hub = hub::HUB.lock();
            (hub.link.is_some(), hub.cluster.is_some(), restore::deciding(&hub))
        };
        WaitInput {
            online: engine::is_online(),
            network: engine::network_available(),
            spirc,
            cluster_known,
            restore_deciding,
            connecting: engine::is_connecting(),
            visible: engine::settings().connect_visible,
            offline_mode: engine::settings().offline,
            offline_active: offline::is_active(),
        }
    }
}

/// Whether a command waits before it is routed: a connect attempt is in flight (cold start,
/// reconnect, or the engine logging in again to become visible to Spotify Connect), the new
/// Spirc's first cluster (which device is active) isn't known yet, or (`for_restore`) a pending
/// reconnect restore is about to decide. Otherwise the command would fail, be routed offline, or
/// see "nobody is active" while another device is. A running offline queue answers controls
/// right away; a load replaces it, so it waits too.
fn should_wait(kind: CommandKind, i: WaitInput, for_restore: bool) -> bool {
    if kind != CommandKind::Load && i.offline_active {
        return false;
    }
    if !i.network {
        // Nothing comes without a network (a session still online in its network-loss grace
        // gets no cluster and decides no restore).
        return false;
    }
    if !i.online {
        return i.connecting;
    }
    if !i.spirc {
        // Online but hidden while the settings already want it visible: Spirc is on its way.
        return i.visible;
    }
    !i.cluster_known || (for_restore && i.restore_deciding)
}

/// Holds a command for at most [`CONNECTING_WAIT`] while [`should_wait`]; stops early once the
/// condition is gone or the attempt ended (error, offline, stop, backoff).
async fn await_ready(kind: CommandKind, for_restore: bool) {
    hold_while(|i| should_wait(kind, i, for_restore)).await
}

/// What a load's downloads mean for its route (docs/ARCHITECTURE.md §4.6).
#[derive(Debug, Clone, Copy)]
struct Downloads {
    /// Something of the load is downloaded.
    any: bool,
    /// ... and so is the requested start item (or no start was asked for: a shuffle begins
    /// anywhere), so the offline queue starts exactly there.
    start: bool,
}

impl Downloads {
    fn of(args: &LoadArgs) -> Self {
        Self::of_selection(&offline::resolve(args))
    }

    fn of_selection(s: &offline_queue::Selection) -> Self {
        let any = !s.items.is_empty();
        Downloads { any, start: any && s.exact }
    }

    /// Whether the load plays from the downloads (`route`'s `downloaded`). Streaming plays
    /// nothing from them. Offline (no network, or offline mode; also while the session still
    /// reads online without a network) any download plays: a start that isn't downloaded moves
    /// on to the next one. While the session is only on its way (connecting, a reconnect
    /// backoff) another download is never swapped in for the one asked for: only a downloaded
    /// start plays offline, otherwise the load waits for the session
    /// ([`Downloads::await_session`]) and then fails as "not available offline".
    fn play_offline(self, i: WaitInput) -> bool {
        if !i.network || i.offline_mode {
            self.any
        } else {
            !i.online && self.start
        }
    }

    /// A load whose start isn't downloaded waits for the session (bounded) also when no attempt
    /// is in flight (a reconnect backoff), as long as it may still come.
    fn await_session(self, i: WaitInput) -> bool {
        !i.online && i.network && !i.offline_mode && !self.start
    }
}

/// Holds a command for at most [`CONNECTING_WAIT`] while `holds`; stops early once it is false.
async fn hold_while(holds: impl Fn(WaitInput) -> bool) {
    let mut status = engine::status_watch();
    let mut online = engine::online_watch();
    let wait = async {
        loop {
            // Enabled before the check: `notify_waiters` stores no permit.
            let changed = hub::CHANGED.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            if !holds(WaitInput::now()) {
                break;
            }
            tokio::select! {
                r = online.changed() => if r.is_err() { break },
                r = status.changed() => if r.is_err() { break },
                _ = &mut changed => {},
            }
        }
    };
    if tokio::time::timeout(CONNECTING_WAIT, wait).await.is_err() {
        log::info!("the session isn't ready yet, routing the command anyway");
    }
}

fn spirc() -> AppResult<std::sync::Arc<librespot_connect::Spirc>> {
    hub::spirc().ok_or_else(AppError::not_connected)
}

/// An explicit pull to this phone (`LoadArgs::local`, e.g. a media-session resume): with a
/// network and a visible session it plays here whatever another device does, taking its
/// session over like a transfer to this phone. Otherwise (no network, offline, hidden) the load
/// is routed as usual.
fn explicit_local(local: bool, online: bool, network: bool, spirc: bool, local_active: bool) -> Option<Target> {
    (local && online && network && spirc).then_some(Target::Local { activate: !local_active })
}

/// The device a load names, unless it is this phone (see `LoadArgs::device_id`).
fn load_target(args: &LoadArgs, me: &str) -> Option<String> {
    args.device_id.as_deref().map(str::trim).filter(|d| !d.is_empty() && *d != me).map(str::to_string)
}

async fn load(args: LoadArgs) -> AppResult<Value> {
    if let Some(device) = load_target(&args, &hub::me()) {
        return load_on(&device, &args).await;
    }
    player_events::on_user_load();
    // An explicit load replaces whatever a reconnect would restore, but only once it is known to
    // go through: the restore doesn't run meanwhile, and stays if the load fails.
    let _hold = restore::hold();
    let wanted = Downloads::of(&args);
    hold_while(|i| should_wait(CommandKind::Load, i, false) || wanted.await_session(i)).await;
    let downloaded = Downloads::of(&args).play_offline(WaitInput::now());
    let local = explicit_local(
        args.local,
        engine::is_online(),
        engine::network_available(),
        hub::spirc().is_some(),
        hub::local_active_or_activating(),
    );
    let target = match local {
        Some(target) => target,
        None => decide(CommandKind::Load, downloaded)?,
    };
    match target {
        Target::Local { activate } => {
            let spirc = spirc()?;
            let request = local::load_request(&args)?;
            // Until it plays, a push sends this load to the target (see `push`); recorded before
            // the restore or a hand-back it replaces go (their loads are ahead of it).
            hub::set_loading(&args);
            restore::clear();
            // The offline queue hands the Player over to Spirc (and a hand-back on its way is
            // replaced: its failure must not make this phone inactive).
            offline::stop();
            hub::forget_hand_back();
            let sent = (|| {
                if activate {
                    local::sent(spirc.activate())?;
                    // Commands that arrive before the Spirc reports itself active go to it.
                    hub::set_activating();
                }
                local::sent(spirc.load(request))
            })();
            if sent.is_err() {
                hub::forget_local_load();
            }
            sent?;
        }
        Target::Remote(device) => {
            restore::clear();
            remote::send(&device, remote::play(&args, &uri::random_command_id())).await.map_err(remote::remote_error)?;
        }
        Target::Offline => {
            if engine::is_online() {
                // No network, but the session still reads online (its network-loss grace): Spirc
                // lets go of the Player now (it can't load anything without the network), and
                // the session goes offline without a restore point.
                hub::detach_current();
                engine::go_offline();
            }
            // Drops the restore point once the downloads are known.
            offline::load(&args).await?
        }
    }
    ok()
}

/// A load for another Connect device (picked while nothing played anywhere): a play command
/// there, whatever is active (the same body as a transfer's resume: context or tracks, start,
/// position, shuffle, repeat). Once it went through, this device lets go of its own playback
/// (restore point, offline queue).
async fn load_on(device: &str, args: &LoadArgs) -> AppResult<Value> {
    let _hold = restore::hold();
    await_ready(CommandKind::Load, false).await;
    if !engine::is_online() || !engine::network_available() {
        return Err(AppError::not_connected());
    }
    remote::send(device, remote::play(args, &uri::random_command_id())).await.map_err(remote::remote_error)?;
    // Commands right after it follow it there (the cluster naming it comes later).
    hub::set_remote_activating(device);
    restore::clear();
    offline::stop();
    ok()
}

/// The play intent of a play / pause / toggle (the reconnect placeholder shows paused, so a
/// toggle means play).
fn play_intent(cmd: &Ctl) -> Option<bool> {
    match cmd {
        Ctl::Play | Ctl::Toggle => Some(true),
        Ctl::Pause => Some(false),
        _ => None,
    }
}

async fn control(mut cmd: Ctl) -> AppResult<Value> {
    // A pending reconnect restore is this device's session: a play / pause decides whether it
    // comes back playing; other commands wait for it and then go to the restored Spirc.
    if let Some(play) = play_intent(&cmd).filter(|_| !offline::is_active()) {
        if let Some(prev) = restore::set_intent(play) {
            if !restore::overdue() {
                await_ready(cmd.kind(), true).await;
            }
            // Answered: it comes back as asked (or a play restored it right away, its first
            // cluster being overdue). Otherwise the command is routed as usual: restored meanwhile
            // (a toggle seen on the paused placeholder means play), skipped, replaced, or no
            // session (a play is left to the app's fallback, without its intent).
            if restore::answer(play, prev, true)? {
                return ok();
            }
            if matches!(cmd, Ctl::Toggle) {
                cmd = Ctl::Play;
            }
        }
    }
    // While the restore is applied, a play / pause is recorded on its restore point too (and a
    // toggle on the paused placeholder means play).
    if let Some(play) = restore::while_restoring(&cmd) {
        cmd = if play { Ctl::Play } else { Ctl::Pause };
    }
    await_ready(cmd.kind(), true).await;
    match decide(cmd.kind(), false)? {
        // Active with nothing loaded (a load failed after its activation): nothing to play there,
        // the app's own resume loads its session instead.
        Target::Local { activate: false } if matches!(cmd, Ctl::Play | Ctl::Toggle) && hub::local_active_empty() => {
            return Err(nothing_active());
        }
        Target::Local { activate } => local_control(&cmd, activate)?,
        Target::Remote(device) => remote_control(&cmd, &device).await.map_err(remote::remote_error)?,
        Target::Offline => offline::control(&cmd).await?,
    }
    ok()
}

/// Playback this device stopped (the context ended, the load brake halted it) with a track to
/// play again.
fn stopped_with_track(s: Option<&librespot_connect::ConnectSnapshot>) -> bool {
    s.is_some_and(|s| s.is_active && s.track.is_some() && s.status == librespot_connect::SnapshotPlayStatus::Stopped)
}

/// A play / toggle of stopped playback loads its track again (Spirc's and the offline queue's
/// play from stopped): like a new load, the load brake gets a fresh chance (latched, it stopped
/// the first failure again).
fn restarts_stopped(cmd: &Ctl, stopped_with_track: bool) -> bool {
    matches!(cmd, Ctl::Play | Ctl::Toggle) && stopped_with_track
}

fn local_control(cmd: &Ctl, activate: bool) -> AppResult<()> {
    let spirc = spirc()?;
    if activate {
        local::sent(spirc.activate())?;
    }
    if restarts_stopped(cmd, stopped_with_track(hub::local_snapshot().as_ref())) {
        player_events::on_user_load();
    }
    match cmd {
        Ctl::Play => local::sent(spirc.play()),
        Ctl::Pause => local::sent(spirc.pause()),
        Ctl::Toggle => local::sent(spirc.play_pause()),
        Ctl::Next => local::sent(spirc.next()),
        Ctl::Prev => local::sent(spirc.prev()),
        Ctl::Seek(ms) => local::sent(spirc.set_position_ms((*ms).min(u32::MAX as u64) as u32)),
        Ctl::Shuffle(enabled) => local::sent(spirc.shuffle(*enabled)),
        Ctl::SmartShuffle(enabled) => local::sent(spirc.smart_shuffle(*enabled)),
        Ctl::Repeat(mode) => local::set_repeat(&spirc, *mode),
        Ctl::QueueAdd(u) => local::queue_add(&spirc, u),
        Ctl::QueueRemove(uid) => local::sent(spirc.remove_from_queue(uid.clone())),
        Ctl::QueueMove(uid, to) => {
            let raw = hub::local_snapshot()
                .map(|s| {
                    let hidden: Vec<bool> =
                        s.next_tracks.iter().map(|t| t.hidden || t.uri == uri::DELIMITER_URI).collect();
                    let uids: Vec<&str> = s.next_tracks.iter().map(|t| t.uid.as_str()).collect();
                    snapshot::raw_move_index(&hidden, &uids, uid, *to)
                })
                .unwrap_or(*to);
            local::sent(spirc.move_queue_item(uid.clone(), raw))
        }
        Ctl::QueueClear => local::sent(spirc.clear_queue()),
        Ctl::SkipTo(uid) => local::sent(spirc.skip_to(uid.clone())),
    }
}

fn remote_state() -> AppResult<(Vec<librespot_protocol::player::ProvidedTrack>, Vec<librespot_protocol::player::ProvidedTrack>, String, bool)> {
    let cluster = hub::cluster().ok_or_else(|| AppError::unavailable("The remote player state is unknown"))?;
    let ps = cluster.player_state.as_ref().ok_or_else(|| AppError::unavailable("The remote player state is unknown"))?;
    let paused = !ps.is_playing || ps.is_paused;
    Ok((ps.next_tracks.clone(), ps.prev_tracks.clone(), ps.queue_revision.clone(), paused))
}

async fn remote_control(cmd: &Ctl, device: &str) -> AppResult<()> {
    let id = uri::random_command_id();
    let bodies = match cmd {
        Ctl::Play => vec![remote::simple("resume", &id)],
        Ctl::Pause => vec![remote::simple("pause", &id)],
        Ctl::Toggle => {
            let paused = remote_state().map(|s| s.3).unwrap_or(false);
            vec![remote::simple(if paused { "resume" } else { "pause" }, &id)]
        }
        Ctl::Next => vec![remote::simple("skip_next", &id)],
        Ctl::Prev => vec![remote::simple("skip_prev", &id)],
        Ctl::Seek(ms) => vec![remote::seek_to(*ms, &id)],
        Ctl::Shuffle(enabled) => vec![remote::set_value("set_shuffling_context", *enabled, &id)],
        Ctl::SmartShuffle(_) => {
            return Err(AppError::unavailable("Smart shuffle isn't supported on the active device"));
        }
        Ctl::Repeat(mode) => match mode {
            RepeatMode::Off => vec![
                remote::set_value("set_repeating_track", false, &id),
                remote::set_value("set_repeating_context", false, &uri::random_command_id()),
            ],
            RepeatMode::Context => vec![
                remote::set_value("set_repeating_track", false, &id),
                remote::set_value("set_repeating_context", true, &uri::random_command_id()),
            ],
            RepeatMode::Track => vec![remote::set_value("set_repeating_track", true, &id)],
        },
        Ctl::QueueAdd(u) => vec![remote::add_to_queue(u, &id)],
        Ctl::QueueRemove(uid) => {
            let (next, prev, revision, _) = remote_state()?;
            let next = remote::queue_without(&next, uid).ok_or_else(|| AppError::not_found("Not in the queue"))?;
            vec![remote::set_queue(&next, &prev, &revision, &id)]
        }
        Ctl::QueueMove(uid, to) => {
            let (next, prev, revision, _) = remote_state()?;
            let next = remote::queue_moved(&next, uid, *to).ok_or_else(|| AppError::not_found("Not in the queue"))?;
            vec![remote::set_queue(&next, &prev, &revision, &id)]
        }
        Ctl::QueueClear => {
            let (next, prev, revision, _) = remote_state()?;
            vec![remote::set_queue(&remote::queue_cleared(&next), &prev, &revision, &id)]
        }
        Ctl::SkipTo(uid) => {
            let (next, ..) = remote_state()?;
            let track = next
                .iter()
                .find(|t| &t.uid == uid && !snapshot::is_hidden_provided(t))
                .ok_or_else(|| AppError::not_found("Not in the queue"))?;
            vec![remote::skip_next_to(track, &id)]
        }
    };
    remote::send_all(device, bodies).await
}

fn set_volume(args: VolumeArgs) -> AppResult<Value> {
    let volume = args.volume.min(u16::MAX as u32) as u16;
    let mixer = engine::player_host::mixer();
    if args.from_system {
        // Android already applied it: record it, and tell Connect only when we are the active device.
        if let Some(m) = &mixer {
            m.report_system_volume(volume);
        }
        if hub::local_active() {
            if let Some(spirc) = hub::spirc() {
                local::sent(spirc.set_volume(volume))?;
            }
        }
        hub::publish();
        return ok();
    }
    match decide(CommandKind::Volume, false)? {
        Target::Local { .. } if hub::local_active() => local::sent(spirc()?.set_volume(volume))?,
        Target::Local { .. } | Target::Offline => {
            if let Some(m) = &mixer {
                m.set_volume(volume);
            }
        }
        Target::Remote(device) => remote::set_volume(&device, volume),
    }
    hub::publish();
    ok()
}

/// `player.setSpeed`: the speed the app's sink plays at (podcasts; the app sends 1 for music). The
/// Spirc reports it as the playback speed while playing (also every later Spirc, see
/// `hub::attach`) and the offline queue extrapolates with it, so positions here and on the other
/// clients follow the real rate. The Player measures its position corrections against it (also
/// every later Player).
fn set_speed(speed: f64) -> AppResult<Value> {
    hub::HUB.lock().playback_speed = Some(speed);
    if let Some(spirc) = hub::spirc() {
        if let Err(e) = spirc.set_playback_speed(speed) {
            log::debug!("playback speed not reported: {e}");
        }
    }
    offline::set_speed(speed);
    crate::engine::player_host::set_playback_speed(speed);
    ok()
}

fn set_audio_output(args: AudioOutputArgs) -> AppResult<Value> {
    let info = crate::models::AudioOutputInfo { kind: args.kind, name: args.name.filter(|n| !n.is_empty()) };
    hub::HUB.lock().audio_output = Some(info.clone());
    if let Some(spirc) = hub::spirc() {
        local::sent(spirc.set_audio_output(local::audio_output_kind(&info.kind), info.name))?;
    }
    hub::publish_devices();
    ok()
}

/// The commands handing an offline queue over to another device: its context at its current
/// track when that is a track of one that can be loaded again (the whole context plays there),
/// else its items in play order (so no shuffle on the target); its repeat mode and position,
/// paused unless it played (Spirc-based targets start playing whatever `initially_paused` says).
/// Returns the start (play, pause) and, for the context form, the user queue's adds after it.
fn handover_bodies(h: offline_queue::Handover, play: bool) -> (Vec<Value>, Vec<Value>) {
    let base = LoadArgs { position_ms: h.position_ms, repeat: Some(h.repeat), play, ..Default::default() };
    let (load, queued) = match h.context {
        Some(c) => (
            LoadArgs { context_uri: Some(c.context_uri), start_uri: Some(c.track_uri), shuffle: Some(h.shuffle), ..base },
            h.queued,
        ),
        None => (LoadArgs { track_uris: Some(h.uris), start_index: Some(0), shuffle: Some(false), ..base }, Vec::new()),
    };
    let mut start = vec![remote::play(&load, &uri::random_command_id())];
    if !play {
        start.push(remote::simple("pause", &uri::random_command_id()));
    }
    let adds = queued.iter().map(|u| remote::add_to_queue(u, &uri::random_command_id())).collect();
    (start, adds)
}

/// Sends a pushed queue's adds to `device` in the background, best effort (the session moved
/// already: nothing waits for them, the first failure drops the rest).
fn send_queue_adds(device: &str, adds: Vec<Value>) {
    if adds.is_empty() {
        return;
    }
    let device = device.to_string();
    crate::runtime::handle().spawn(async move {
        for body in adds {
            if let Err(e) = remote::send(&device, body).await {
                log::warn!("the handed-over queue is incomplete: {e}");
                break;
            }
        }
    });
}

/// What a transfer to this phone does (see [`pull`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Pull {
    /// This phone's session plays on (a play if asked).
    Here,
    /// The offline queue owns the session here: it plays on.
    Offline,
    /// Another device's session comes here (a Connect transfer).
    TakeOver,
    /// Nothing is active: the given session starts here.
    Resume,
}

/// A transfer to this phone: its own session (Spirc, or the offline queue that hasn't given way)
/// stays; another device's session is taken only if there is none here, or that device actually
/// plays (one that only sits paused as the account's active device doesn't own the session, the
/// same rule as `route`).
fn pull(local_session: bool, offline_owns: bool, other_active: bool, other_playing: bool) -> Pull {
    if local_session {
        Pull::Here
    } else if offline_owns && !other_playing {
        Pull::Offline
    } else if other_active {
        Pull::TakeOver
    } else {
        Pull::Resume
    }
}

/// Whether a push to another device starts a session there (a connect-state `play` of the
/// frozen or the given session) instead of transferring this phone's: a restore is still being
/// applied here, or there is no session to transfer (nothing active, or this phone active with
/// nothing loaded and nothing on its way).
fn push_starts_session(applying: bool, local_session: bool, other_active: bool) -> bool {
    applying || (!local_session && !other_active)
}

fn nothing_active() -> AppError {
    AppError::new(ErrorCode::NotActiveDevice, "Nothing is playing on any device")
}

async fn transfer(args: TransferArgs) -> AppResult<Value> {
    let me = hub::me();
    // A pending reconnect restore is this device's session: to this phone it is restored (playing
    // or not, as asked), to another device it is handed over there.
    let mut frozen = None;
    if args.device_id == me {
        if let Some(prev) = restore::set_intent(args.play) {
            if !restore::overdue() {
                await_ready(CommandKind::Load, true).await;
            }
            // Not answered without a session: NOT_CONNECTED below.
            if restore::answer(args.play, prev, false)? {
                return ok();
            }
        }
    }
    // A push of a pending restore holds its decision and takes the restore point only once the
    // target accepted it (a failed push leaves it, or the restore being applied, as it was).
    let _hold = (args.device_id != me).then(restore::hold);
    if args.device_id != me {
        frozen = restore::peek();
    }
    await_ready(CommandKind::Load, false).await;
    if !engine::is_online() || (args.device_id != me && !engine::network_available()) {
        return Err(AppError::not_connected());
    }
    if hub::spirc().is_none() {
        // Online but hidden from Spotify Connect: no cluster to tell what is active.
        return Err(route::hidden());
    }
    let other_active = active_device().is_some_and(|id| id != me);
    // A paused or finished offline queue gives way to a device that took over (see `route`).
    let offline_owns = offline::is_active() && !offline::yields();
    if args.device_id == me {
        let spirc = spirc()?;
        let local_session = hub::local_active_or_activating() && !hub::local_active_empty();
        match pull(local_session, offline_owns, other_active, offline::elsewhere_playing()) {
            Pull::Here => {
                if args.play {
                    local::sent(spirc.play())?;
                }
            }
            Pull::Offline => {
                // Already playing here (downloads).
                if args.play {
                    offline::control(&Ctl::Play).await?;
                }
            }
            Pull::TakeOver => {
                let request = TransferRequest {
                    transfer_options: TransferOptions {
                        restore_paused: Some(if args.play { "restore" } else { "pause" }.to_string()),
                        ..Default::default()
                    },
                };
                local::sent(spirc.transfer(Some(request)))?;
            }
            Pull::Resume => {
                // Nothing is active anywhere: start the given session here.
                let resume = args.resume.as_ref().and_then(|r| r.load_args(args.play)).ok_or_else(nothing_active)?;
                return load(resume).await;
            }
        }
        return ok();
    }
    let loading = hub::local_load();
    push(&args, other_active, offline_owns, frozen.as_ref(), loading.as_ref()).await?;
    let mut let_go = loading.is_some();
    if let Some(taken) = frozen {
        // The target took it: the restore point goes.
        restore::clear();
        let_go |= taken.applying;
    }
    if loading.is_some() {
        hub::forget_local_load();
    }
    if let_go {
        // A start that was on its way here (a restore being applied, a load) went to the target:
        // this phone lets go, after that start's commands (its Spirc handles them in order; also
        // if it took meanwhile).
        if let Some(spirc) = hub::spirc() {
            if let Err(e) = spirc.disconnect(true) {
                log::debug!("spirc gone: {e}");
            }
        }
    }
    ok()
}

/// The push of `transfer` to another device.
async fn push(
    args: &TransferArgs,
    other_active: bool,
    offline_owns: bool,
    frozen: Option<&restore::Taken>,
    loading: Option<&LoadArgs>,
) -> AppResult<()> {
    if offline_owns {
        // The offline queue has no Connect state to transfer: hand it over as a play command,
        // then stop locally.
        if let Some(handover) = offline::handover(50).filter(|h| !h.uris.is_empty()) {
            let playing = args.play && handover.playing;
            let (start, adds) = handover_bodies(handover, playing);
            remote::send_all(&args.device_id, start).await.map_err(remote::remote_error)?;
            // The target plays: the phone stops right away, the user queue follows.
            offline::stop();
            send_queue_adds(&args.device_id, adds);
            return Ok(());
        }
    }
    let applying = frozen.is_some_and(|t| t.applying);
    let local_session = hub::local_active_or_activating() && !hub::local_active_empty();
    if push_starts_session(applying || loading.is_some(), local_session, other_active) {
        // Nothing to transfer, or a start still on its way here (a restore being applied, a
        // load fetching its context): start the frozen session, that load or the given session
        // on the target.
        let resume = frozen
            .and_then(|t| restore::load_args(&t.frozen, args.play))
            .or_else(|| loading.map(|l| LoadArgs { play: args.play, ..l.clone() }))
            .or_else(|| args.resume.as_ref().and_then(|r| r.load_args(args.play)))
            .ok_or_else(nothing_active)?;
        player_events::on_user_load();
        remote::send(&args.device_id, remote::play(&resume, &uri::random_command_id()))
            .await
            .map_err(remote::remote_error)?;
        hub::set_remote_activating(&args.device_id);
        return Ok(());
    }
    remote::transfer(&args.device_id, args.play).await.map_err(remote::remote_error)
}

// ---------------------------------------------------------------------------------------------
// Crate-internal API for the engine
// ---------------------------------------------------------------------------------------------

/// A new Spirc is up (not yet declared online).
pub(crate) fn attach(attachment: Attachment) {
    hub::attach(attachment);
}

/// The Spirc `generation` is gone.
pub(crate) fn detach(generation: u64) {
    hub::detach(generation);
}

/// Forgets whatever Spirc is attached (forced cleanup).
pub(crate) fn detach_all() {
    hub::detach_all();
}

/// Before a reconnect teardown (or right after the Spirc died): remember what was playing.
pub(crate) fn prepare_reconnect() {
    restore::prepare_reconnect();
}

/// The Spirc `generation` is online; restores the playback frozen by `prepare_reconnect`.
pub(crate) fn after_online(generation: u64) {
    restore::schedule(generation);
}

/// No restore will follow (stop, logout, offline mode, terminal error).
pub(crate) fn clear_restore() {
    restore::clear();
}

/// The session of the Spirc `generation` goes away to come back (without a network, or a reconnect
/// with one: `connector::keep_playing_offline`), or offline mode was turned on, while this device
/// plays a downloaded (or fully buffered) track: the OfflineController takes the playback over
/// without a gap instead of a restore point being frozen. Called before the teardown pauses
/// anything; returns whether it did (docs/ARCHITECTURE.md §4.6).
pub(crate) fn hand_off_to_offline(generation: u64) -> bool {
    offline::take_over(generation)
}

/// The Player's thread died (see `offline::player_lost`).
pub(crate) fn on_player_lost() {
    offline::player_lost();
}

/// Android's network availability changed: commands waiting for a cluster or a restore check
/// again (nothing comes without a network, see `should_wait`). Called by
/// `engine::set_network_available`.
pub(crate) fn on_network_changed() {
    hub::changed();
    // (and whether a handed-over window can go back to Spirc)
    offline::resume_window_end();
}

/// The engine's session state changed (online / offline …): recompute what is shown.
pub(crate) fn on_engine_state_changed() {
    if engine::is_online() {
        metadata::on_online();
        // The first cluster can arrive before the session is declared online.
        offline::yield_to_active_device();
        if hub::spirc().is_none() {
            // Online but hidden from Spotify Connect: nothing to restore into (the engine only
            // restores into a visible Spirc), so the frozen state goes.
            restore::clear();
        }
    } else {
        // Offline / stopped: a cluster kept while hidden is stale now.
        hub::drop_stale_cluster();
    }
    // A handed-over window goes back to Spirc once the session and its first cluster are there
    // (another device may have taken over meanwhile); offline its Next isn't offered as such.
    offline::resume_window_end();
    hub::changed();
    hub::publish();
    hub::publish_devices();
}

/// `session.stop`: `release_player` also ends offline playback (the Player goes away).
pub(crate) fn on_engine_stopped(release_player: bool) {
    restore::clear();
    if release_player {
        offline::stop();
        // A new Player numbers its requests anew.
        offline::player_lost();
    }
    on_engine_state_changed();
}

/// The Connect device ids currently in the cluster (the devices in the account's cluster).
/// Used by the ZeroConf client to tell whether a freshly logged-in device has joined, and to
/// hide devices that are already in the cluster from the local-network list.
pub(crate) fn cluster_device_ids() -> Vec<String> {
    hub::cluster().map(|c| c.device.keys().cloned().collect()).unwrap_or_default()
}

/// Looks for `device_id` among the cluster devices (exact match, then case-insensitive),
/// returning the cluster's own id for it. The ZeroConf `deviceID` is normally the same string
/// the device registers as on the dealer, so an exact match is the common case.
pub(crate) fn find_cluster_device(device_id: &str) -> Option<String> {
    let ids = cluster_device_ids();
    ids.iter()
        .find(|id| id.as_str() == device_id)
        .or_else(|| ids.iter().find(|id| id.eq_ignore_ascii_case(device_id)))
        .cloned()
}

/// The `(id, name)` of every device in the cluster. Used by the Cast client to recognise a
/// receiver that joined under an id other than the one it was given.
pub(crate) fn cluster_device_names() -> Vec<(String, String)> {
    hub::cluster().map(|c| c.device.iter().map(|(id, d)| (id.clone(), d.name.clone())).collect()).unwrap_or_default()
}

/// Woken on every cluster update (a new device joining pushes a cluster update to us).
pub(crate) fn cluster_changed() -> &'static tokio::sync::Notify {
    &hub::CLUSTER_CHANGED
}

/// Logout: forget everything user-specific.
pub(crate) fn reset() {
    offline::stop();
    restore::clear();
    metadata::clear();
    {
        let mut hub = hub::HUB.lock();
        hub.refused_error = None;
        hub.audio_output = None;
        // Never show the previous account's devices or playback.
        hub.cluster = None;
        hub.last_active = None;
        hub.activation = None;
    }
    on_engine_state_changed();
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONNECTING: WaitInput = WaitInput {
        online: false,
        network: true,
        spirc: false,
        cluster_known: false,
        restore_deciding: false,
        connecting: true,
        visible: true,
        offline_mode: false,
        offline_active: false,
    };
    const READY: WaitInput = WaitInput { online: true, spirc: true, cluster_known: true, connecting: false, ..CONNECTING };

    #[test]
    fn commands_wait_only_for_an_attempt_in_flight() {
        use CommandKind::*;
        assert!(should_wait(Load, CONNECTING, false));
        assert!(should_wait(Control, CONNECTING, true));
        assert!(should_wait(Queue, CONNECTING, true));
        // a running offline queue answers controls, a load replaces it
        let offline_queue = WaitInput { offline_active: true, ..CONNECTING };
        assert!(!should_wait(Control, offline_queue, true));
        assert!(should_wait(Load, offline_queue, false));
        // online with the first cluster, or no attempt in flight (offline mode, no network, …)
        assert!(!should_wait(Load, READY, false));
        assert!(!should_wait(Control, READY, true));
        let idle = WaitInput { connecting: false, ..CONNECTING };
        assert!(!should_wait(Load, idle, false));
        assert!(!should_wait(Control, idle, true));
    }

    #[test]
    fn nothing_waits_without_a_network() {
        use CommandKind::*;
        // the session still online in its network-loss grace: no cluster, no restore comes
        let no_network = WaitInput { network: false, cluster_known: false, restore_deciding: true, ..READY };
        assert!(!should_wait(Load, no_network, false));
        assert!(!should_wait(Control, no_network, true));
    }

    #[test]
    fn commands_wait_for_the_first_cluster() {
        use CommandKind::*;
        // online, but the new Spirc doesn't know yet which device is active
        let no_cluster = WaitInput { cluster_known: false, ..READY };
        assert!(should_wait(Load, no_cluster, false));
        assert!(should_wait(Control, no_cluster, true));
        assert!(!should_wait(Control, WaitInput { offline_active: true, ..no_cluster }, true));
    }

    #[test]
    fn controls_wait_for_a_pending_restore() {
        use CommandKind::*;
        let deciding = WaitInput { restore_deciding: true, ..READY };
        assert!(should_wait(Control, deciding, true));
        assert!(should_wait(Queue, deciding, true));
        // a load replaces the restore (it holds the decision instead of waiting for it)
        assert!(!should_wait(Load, deciding, false));
    }

    #[test]
    fn only_offline_swaps_in_another_download() {
        // a stored track-list resume [X, Y, Z] at X where only Z is downloaded
        let list: Vec<String> = ["x", "y", "z"].iter().map(|t| format!("spotify:track:{t}")).collect();
        let only_z = |u: &str| u == "spotify:track:z";
        let x_missing =
            Downloads::of_selection(&offline_queue::select_downloaded(&list, only_z, Some(0), None));
        let x_there =
            Downloads::of_selection(&offline_queue::select_downloaded(&list, |_| true, Some(0), None));
        let nothing = Downloads::of_selection(&offline_queue::select_downloaded(&list, |_| false, Some(0), None));
        let shuffle = Downloads::of_selection(&offline_queue::select_downloaded(&list, only_z, None, None));
        let backoff = WaitInput { connecting: false, ..CONNECTING };

        for (name, i) in [("connecting", CONNECTING), ("backoff", backoff)] {
            assert!(!x_missing.play_offline(i), "{name}: Z is never played for X");
            assert!(x_missing.await_session(i), "{name}: X waits for the session");
            assert!(x_there.play_offline(i), "{name}: a downloaded start plays offline at once");
            assert!(!x_there.await_session(i), "{name}");
            assert!(!nothing.play_offline(i) && nothing.await_session(i), "{name}");
            assert!(shuffle.play_offline(i), "{name}: no start asked for, a shuffle begins anywhere");
        }
        // the backoff wait (no attempt in flight) comes from the load's start alone
        assert!(!should_wait(CommandKind::Load, backoff, false));

        // offline: the next download starts (resumptions rely on it), nothing waits
        let no_network = WaitInput { network: false, connecting: false, ..CONNECTING };
        let offline_mode = WaitInput { offline_mode: true, connecting: false, ..CONNECTING };
        let grace = WaitInput { network: false, ..READY };
        for (name, i) in [("no network", no_network), ("offline mode", offline_mode), ("network-loss grace", grace)] {
            assert!(x_missing.play_offline(i), "{name}");
            assert!(!x_missing.await_session(i), "{name}");
            assert!(!nothing.play_offline(i), "{name}");
        }
        // streaming: Spirc plays it
        assert!(!x_there.play_offline(READY));
        assert!(!x_missing.await_session(READY));
    }

    #[test]
    fn commands_wait_while_the_engine_becomes_visible() {
        use CommandKind::*;
        // online and hidden, the settings want it visible: the engine logs in again with Spirc
        let becoming_visible = WaitInput { online: true, spirc: false, connecting: false, ..CONNECTING };
        assert!(should_wait(Load, becoming_visible, false));
        assert!(should_wait(Control, becoming_visible, true));
        // staying hidden: no wait, the command fails right away
        assert!(!should_wait(Load, WaitInput { visible: false, ..becoming_visible }, false));
        // the re-login itself is an attempt in flight
        assert!(should_wait(Load, WaitInput { online: false, connecting: true, ..becoming_visible }, false));
    }

    #[test]
    fn offline_handover_keeps_order_repeat_and_pause() {
        let h = offline_queue::Handover {
            uris: vec!["spotify:track:a".into(), "spotify:track:b".into()],
            position_ms: 42_000,
            repeat: RepeatMode::Track,
            playing: false,
            context: None,
            shuffle: true,
            queued: vec!["spotify:track:b".into()],
        };
        let (start, adds) = handover_bodies(h, false);
        assert_eq!(start.len(), 2, "play, pause");
        assert!(adds.is_empty(), "the queue is in the list");
        let body = start[0].to_string();
        assert!(body.contains("\"shuffling_context\":false"), "{body}");
        assert!(body.contains("\"repeating_track\":true"), "{body}");
        assert!(body.contains("\"initially_paused\":true"), "{body}");
        assert!(body.contains("42000"), "{body}");
        assert!(start[1].to_string().contains("pause"));
    }

    #[test]
    fn an_offline_queue_of_a_context_is_handed_over_as_that_context() {
        let h = offline_queue::Handover {
            uris: vec!["spotify:track:a".into(), "spotify:track:q".into()],
            position_ms: 42_000,
            repeat: RepeatMode::Context,
            playing: true,
            context: Some(offline_queue::ContextStart {
                context_uri: "spotify:playlist:p".into(),
                track_uri: "spotify:track:a".into(),
            }),
            shuffle: true,
            queued: vec!["spotify:track:q".into()],
        };
        let (start, adds) = handover_bodies(h.clone(), true);
        assert_eq!(start.len(), 1);
        let body = start[0].to_string();
        assert!(body.contains("spotify:playlist:p") && body.contains("spotify:track:a"), "{body}");
        assert!(!body.contains("spotify:track:q"), "{body}");
        assert!(body.contains("\"shuffling_context\":true") && body.contains("\"repeating_context\":true"), "{body}");
        assert!(body.contains("42000"), "{body}");
        // the user queue after it, sent once the phone stopped
        assert_eq!(adds.len(), 1);
        assert!(adds[0].to_string().contains("add_to_queue") && adds[0].to_string().contains("spotify:track:q"));
        // paused: the pause comes right after the play, before the adds
        let (start, adds) = handover_bodies(h, false);
        assert_eq!(start.len(), 2);
        assert!(start[1].to_string().contains("pause") && !start[1].to_string().contains("add_to_queue"));
        assert_eq!(adds.len(), 1);
    }

    #[test]
    fn a_pull_keeps_the_offline_queue_over_a_paused_device() {
        // the offline queue plays here, the cluster still names a paused speaker
        assert_eq!(pull(false, true, true, false), Pull::Offline);
        // ... a speaker that plays is taken over
        assert_eq!(pull(false, true, true, true), Pull::TakeOver);
        // this phone's own session, another device's, or nothing
        assert_eq!(pull(true, false, true, true), Pull::Here);
        assert_eq!(pull(false, false, true, false), Pull::TakeOver);
        assert_eq!(pull(false, true, false, false), Pull::Offline);
        assert_eq!(pull(false, false, false, false), Pull::Resume);
    }

    #[test]
    fn a_push_from_a_phone_with_nothing_loaded_starts_the_session_there() {
        // active with nothing loaded (a failed load) or nothing active: the given session
        assert!(push_starts_session(false, false, false));
        // a session here, or another device's: transferred
        assert!(!push_starts_session(false, true, false));
        assert!(!push_starts_session(false, false, true));
        // a restore still being applied here: started there
        assert!(push_starts_session(true, true, false));
    }

    #[test]
    fn a_play_of_stopped_playback_gets_a_fresh_load_brake() {
        use librespot_connect::{ConnectSnapshot, SnapshotPlayStatus, SnapshotTrack, TrackProvider};
        let track = SnapshotTrack {
            uri: "spotify:track:a".into(),
            uid: "a".into(),
            provider: TrackProvider::Context,
            context_index: None,
            hidden: false,
            metadata: Default::default(),
        };
        let halted = ConnectSnapshot { is_active: true, status: SnapshotPlayStatus::Stopped, track: Some(track), ..Default::default() };
        assert!(stopped_with_track(Some(&halted)));
        assert!(restarts_stopped(&Ctl::Play, true));
        assert!(restarts_stopped(&Ctl::Toggle, true));
        assert!(!restarts_stopped(&Ctl::Pause, true));
        assert!(!restarts_stopped(&Ctl::Next, true));
        assert!(!restarts_stopped(&Ctl::Play, false));
        // paused, inactive, or nothing to play again: an ordinary play
        let paused = ConnectSnapshot { status: SnapshotPlayStatus::Paused, ..halted.clone() };
        assert!(!stopped_with_track(Some(&paused)));
        assert!(!stopped_with_track(Some(&ConnectSnapshot { is_active: false, ..halted.clone() })));
        assert!(!stopped_with_track(Some(&ConnectSnapshot { track: None, ..halted })));
        assert!(!stopped_with_track(None));
    }

    #[test]
    fn an_explicit_pull_plays_here() {
        // another device active, this one not yet: activated here
        assert_eq!(explicit_local(true, true, true, true, false), Some(Target::Local { activate: true }));
        assert_eq!(explicit_local(true, true, true, true, true), Some(Target::Local { activate: false }));
        // no network, offline, hidden, or not asked for: routed as usual
        assert_eq!(explicit_local(true, true, false, true, false), None);
        assert_eq!(explicit_local(true, false, true, true, false), None);
        assert_eq!(explicit_local(true, true, true, false, false), None);
        assert_eq!(explicit_local(false, true, true, true, false), None);
    }

    #[test]
    fn a_load_for_another_device_goes_there() {
        let args = |device: Option<&str>| LoadArgs { device_id: device.map(str::to_string), ..Default::default() };
        assert_eq!(load_target(&args(Some("speaker")), "me").as_deref(), Some("speaker"));
        // absent, blank or this phone: routed as usual
        assert_eq!(load_target(&args(None), "me"), None);
        assert_eq!(load_target(&args(Some(" ")), "me"), None);
        assert_eq!(load_target(&args(Some("me")), "me"), None);
    }

    #[test]
    fn play_and_pause_set_the_restore_intent() {
        assert_eq!(play_intent(&Ctl::Play), Some(true));
        assert_eq!(play_intent(&Ctl::Toggle), Some(true), "the placeholder shows paused");
        assert_eq!(play_intent(&Ctl::Pause), Some(false));
        assert_eq!(play_intent(&Ctl::Next), None);
        assert_eq!(play_intent(&Ctl::Seek(5)), None);
    }
}
