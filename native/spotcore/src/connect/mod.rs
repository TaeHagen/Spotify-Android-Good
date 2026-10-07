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
use args::{AudioOutputArgs, EnabledArgs, LoadArgs, MoveArgs, RepeatArgs, SeekArgs, TransferArgs, UidArgs, UriArgs, VolumeArgs};
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

fn decide(kind: CommandKind, downloaded: bool) -> AppResult<Target> {
    let me = hub::me();
    let active = hub::active_device_id();
    let input = RouteInput {
        online: engine::is_online(),
        spirc: hub::spirc().is_some(),
        // An activation just sent (a load, the restore) counts: commands queue behind it.
        local_active: hub::local_active_or_activating(),
        offline_active: offline::is_active(),
        active_device: active.as_deref(),
        me: &me,
    };
    route::route(&input, kind, downloaded)
}

/// What [`should_wait`] looks at.
#[derive(Debug, Clone, Copy)]
struct WaitInput {
    online: bool,
    /// A Spirc is attached (visible to Spotify Connect).
    spirc: bool,
    /// The attached Spirc's first cluster arrived (it tells which device is active).
    cluster_known: bool,
    /// A reconnect restore is pending and nothing holds it: its decision is on its way.
    restore_deciding: bool,
    /// A connect attempt is in flight (`engine::is_connecting`).
    connecting: bool,
    /// The settings want this device visible to Spotify Connect.
    visible: bool,
    offline_active: bool,
}

impl WaitInput {
    fn now() -> Self {
        let (spirc, cluster_known, restore_deciding) = {
            let hub = hub::HUB.lock();
            (hub.link.is_some(), hub.cluster.is_some(), hub.reconnect.is_some() && hub.restore_holds == 0)
        };
        WaitInput {
            online: engine::is_online(),
            spirc,
            cluster_known,
            restore_deciding,
            connecting: engine::is_connecting(),
            visible: engine::settings().connect_visible,
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
    let mut status = engine::status_watch();
    let mut online = engine::online_watch();
    let wait = async {
        loop {
            // Enabled before the check: `notify_waiters` stores no permit.
            let changed = hub::CHANGED.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            if !should_wait(kind, WaitInput::now(), for_restore) {
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

async fn load(args: LoadArgs) -> AppResult<Value> {
    player_events::on_user_load();
    // An explicit load replaces whatever a reconnect would restore, but only once it is known to
    // go through: the restore doesn't run meanwhile, and stays if the load fails.
    let _hold = restore::hold();
    await_ready(CommandKind::Load, false).await;
    let downloaded = !engine::is_online() && offline::has_downloaded(&args);
    match decide(CommandKind::Load, downloaded)? {
        Target::Local { activate } => {
            let spirc = spirc()?;
            let request = local::load_request(&args)?;
            restore::clear();
            // The offline queue hands the Player over to Spirc.
            offline::stop();
            if activate {
                local::sent(spirc.activate())?;
                // Commands that arrive before the Spirc reports itself active go to it.
                hub::set_activating();
            }
            local::sent(spirc.load(request))?;
        }
        Target::Remote(device) => {
            restore::clear();
            remote::send(&device, remote::play(&args, &uri::random_command_id())).await.map_err(remote::remote_error)?;
        }
        // Drops the restore point once the downloads are known.
        Target::Offline => offline::load(&args).await?,
    }
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

async fn control(cmd: Ctl) -> AppResult<Value> {
    // A pending reconnect restore is this device's session: a play / pause decides whether it
    // comes back playing; other commands wait for it and then go to the restored Spirc.
    if let Some(play) = play_intent(&cmd).filter(|_| !offline::is_active()) {
        if restore::set_intent(play) {
            await_ready(cmd.kind(), true).await;
            // Restored with the intent, or still pending (it keeps the intent). Otherwise (skipped,
            // replaced) the command is routed as usual. Pending during an outage, a play is left
            // to the app's fallback (downloads).
            let applied = restore::is_pending() || hub::activating();
            if applied && (engine::is_online() || !play) {
                return ok();
            }
        }
    }
    await_ready(cmd.kind(), true).await;
    match decide(cmd.kind(), false)? {
        Target::Local { activate } => local_control(&cmd, activate)?,
        Target::Remote(device) => remote_control(&cmd, &device).await.map_err(remote::remote_error)?,
        Target::Offline => offline::control(&cmd)?,
    }
    ok()
}

fn local_control(cmd: &Ctl, activate: bool) -> AppResult<()> {
    let spirc = spirc()?;
    if activate {
        local::sent(spirc.activate())?;
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

fn set_audio_output(args: AudioOutputArgs) -> AppResult<Value> {
    let info = crate::models::AudioOutputInfo { kind: args.kind, name: args.name.filter(|n| !n.is_empty()) };
    hub::HUB.lock().audio_output = Some(info.clone());
    if let Some(spirc) = hub::spirc() {
        local::sent(spirc.set_audio_output(local::audio_output_kind(&info.kind), info.name))?;
    }
    hub::publish_devices();
    ok()
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
        if restore::set_intent(args.play) {
            await_ready(CommandKind::Load, true).await;
            if restore::is_pending() || hub::activating() {
                return ok();
            }
        }
    } else {
        frozen = restore::take();
    }
    await_ready(CommandKind::Load, false).await;
    if !engine::is_online() {
        return Err(AppError::not_connected());
    }
    if hub::spirc().is_none() {
        // Online but hidden from Spotify Connect: no cluster to tell what is active.
        return Err(route::hidden());
    }
    let other_active = hub::active_device_id().is_some_and(|id| id != me);
    if args.device_id == me {
        let spirc = spirc()?;
        if hub::local_active_or_activating() {
            if args.play {
                local::sent(spirc.play())?;
            }
        } else if other_active {
            let request = TransferRequest {
                transfer_options: TransferOptions {
                    restore_paused: Some(if args.play { "restore" } else { "pause" }.to_string()),
                    ..Default::default()
                },
            };
            local::sent(spirc.transfer(Some(request)))?;
        } else if offline::is_active() {
            // Already playing here (downloads).
            if args.play {
                offline::control(&Ctl::Play)?;
            }
        } else {
            // Nothing is active anywhere: start the given session here.
            let resume = args.resume.as_ref().and_then(|r| r.load_args(args.play)).ok_or_else(nothing_active)?;
            return load(resume).await;
        }
        return ok();
    }
    if offline::is_active() {
        // The offline queue has no Connect state to transfer: hand its tracks over as a play
        // command, then stop locally.
        if let Some((uris, position_ms)) = offline::handover(50) {
            if !uris.is_empty() {
                let load = LoadArgs {
                    track_uris: Some(uris),
                    start_index: Some(0),
                    position_ms,
                    play: args.play,
                    ..Default::default()
                };
                remote::send(&args.device_id, remote::play(&load, &uri::random_command_id()))
                    .await
                    .map_err(remote::remote_error)?;
                offline::stop();
                return ok();
            }
        }
    }
    if !hub::local_active_or_activating() && !other_active {
        // Nothing to transfer: start the frozen (or the given) session on the target.
        let resume = frozen
            .as_ref()
            .and_then(|f| restore::load_args(f, args.play))
            .or_else(|| args.resume.as_ref().and_then(|r| r.load_args(args.play)))
            .ok_or_else(nothing_active)?;
        player_events::on_user_load();
        remote::send(&args.device_id, remote::play(&resume, &uri::random_command_id()))
            .await
            .map_err(remote::remote_error)?;
        return ok();
    }
    remote::transfer(&args.device_id, args.play).await.map_err(remote::remote_error)?;
    ok()
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

/// The engine's session state changed (online / offline …): recompute what is shown.
pub(crate) fn on_engine_state_changed() {
    if engine::is_online() {
        metadata::on_online();
        if hub::spirc().is_none() {
            // Online but hidden from Spotify Connect: nothing to restore into (the engine only
            // restores into a visible Spirc), so the frozen state goes.
            restore::clear();
        }
    } else {
        // Offline / stopped: a cluster kept while hidden is stale now.
        hub::drop_stale_cluster();
    }
    hub::changed();
    hub::publish();
    hub::publish_devices();
}

/// `session.stop`: `release_player` also ends offline playback (the Player goes away).
pub(crate) fn on_engine_stopped(release_player: bool) {
    restore::clear();
    if release_player {
        offline::stop();
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
        spirc: false,
        cluster_known: false,
        restore_deciding: false,
        connecting: true,
        visible: true,
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
    fn play_and_pause_set_the_restore_intent() {
        assert_eq!(play_intent(&Ctl::Play), Some(true));
        assert_eq!(play_intent(&Ctl::Toggle), Some(true), "the placeholder shows paused");
        assert_eq!(play_intent(&Ctl::Pause), Some(false));
        assert_eq!(play_intent(&Ctl::Next), None);
        assert_eq!(play_intent(&Ctl::Seek(5)), None);
    }
}
