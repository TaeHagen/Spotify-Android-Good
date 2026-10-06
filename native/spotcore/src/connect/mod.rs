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

use crate::error::{AppError, AppResult};
use crate::models::RepeatMode;
use crate::rpc::{ok, parse_args, to_value};
use crate::engine;
use args::{AudioOutputArgs, EnabledArgs, LoadArgs, MoveArgs, RepeatArgs, SeekArgs, TransferArgs, UidArgs, UriArgs, VolumeArgs};
use librespot_core::dealer::protocol::TransferOptions;
use librespot_core::spclient::TransferRequest;
use librespot_playback::mixer::Mixer;
use route::{CommandKind, RouteInput, Target};
use serde_json::Value;
use std::time::{SystemTime, UNIX_EPOCH};

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
        "connect.refreshDevices" => to_value(&hub::force_publish_devices()),
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

fn decide(kind: CommandKind, downloaded: bool) -> AppResult<Target> {
    let me = hub::me();
    let active = hub::active_device_id();
    let input = RouteInput {
        online: engine::is_online() && hub::spirc().is_some(),
        local_active: hub::local_active(),
        offline_active: offline::is_active(),
        active_device: active.as_deref(),
        me: &me,
    };
    route::route(&input, kind, downloaded)
}

fn spirc() -> AppResult<std::sync::Arc<librespot_connect::Spirc>> {
    hub::spirc().ok_or_else(AppError::not_connected)
}

async fn load(args: LoadArgs) -> AppResult<Value> {
    player_events::on_user_load();
    let downloaded = !engine::is_online() && offline::has_downloaded(&args);
    let target = decide(CommandKind::Load, downloaded)?;
    // An explicit load replaces whatever a reconnect would have restored.
    restore::clear();
    match target {
        Target::Local { activate } => {
            let spirc = spirc()?;
            let request = local::load_request(&args)?;
            // The offline queue hands the Player over to Spirc.
            offline::stop();
            if activate {
                local::sent(spirc.activate())?;
            }
            local::sent(spirc.load(request))?;
        }
        Target::Remote(device) => {
            remote::send(&device, remote::play(&args, &uri::random_command_id())).await.map_err(remote::remote_error)?;
        }
        Target::Offline => offline::load(&args).await?,
    }
    ok()
}

async fn control(cmd: Ctl) -> AppResult<Value> {
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

async fn transfer(args: TransferArgs) -> AppResult<Value> {
    if !engine::is_online() {
        return Err(AppError::not_connected());
    }
    let me = hub::me();
    if args.device_id == me {
        let spirc = spirc()?;
        if hub::local_active() {
            if args.play {
                local::sent(spirc.play())?;
            }
        } else if hub::active_device_id().is_some_and(|id| id != me) {
            let request = TransferRequest {
                transfer_options: TransferOptions {
                    restore_paused: Some(if args.play { "restore" } else { "pause" }.to_string()),
                    ..Default::default()
                },
            };
            local::sent(spirc.transfer(Some(request)))?;
        } else {
            local::sent(spirc.activate())?;
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

/// Logout: forget everything user-specific.
pub(crate) fn reset() {
    offline::stop();
    restore::clear();
    metadata::clear();
    {
        let mut hub = hub::HUB.lock();
        hub.refused_error = None;
        hub.audio_output = None;
    }
    on_engine_state_changed();
}
