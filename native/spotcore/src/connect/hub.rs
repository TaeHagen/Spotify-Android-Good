//! Shared Connect state and event publication.
//!
//! * The engine attaches each new Spirc ([`attach`]); an observer task per Spirc mirrors its
//!   snapshot / cluster / command-error streams into [`HubState`] and republishes.
//! * [`publish`] composes the current `PlaybackSnapshot` (local Spirc → offline queue →
//!   reconnect placeholder → remote device → none), enriches it with metadata and emits it only
//!   when the serialised JSON changed. [`publish_devices`] does the same for the device list.
//!
//! Lock order: `EMIT` may be held while the leaf locks (`HUB`, offline queue, metadata caches)
//! are taken briefly; no code takes `EMIT` while holding a leaf lock.

use super::devices::{self, ThisDevice};
use super::{metadata, offline, player_events, restore, snapshot};
use crate::error::{AppError, ErrorCode};
use crate::models::{ActiveDeviceRef, AudioOutputInfo, DeviceList, PlaybackSnapshot, PlaybackStatus};
use crate::{bridge, engine, events, runtime};
use librespot_connect::{ConnectSnapshot, Spirc, SpircCommandError};
use librespot_core::Session;
use librespot_protocol::connect::Cluster;
use parking_lot::Mutex;
use std::sync::{Arc, LazyLock};
use std::time::Duration;
use tokio::sync::{broadcast, watch, Notify};

/// A placeholder of the last local playback is shown while reconnecting, for at most this long.
const RECONNECT_PLACEHOLDER_MAX: Duration = Duration::from_secs(30 * 60);

pub(crate) struct Link {
    pub generation: u64,
    pub spirc: Arc<Spirc>,
}

#[derive(Default)]
pub(crate) struct HubState {
    pub link: Option<Link>,
    /// Latest snapshot of the attached Spirc.
    pub snapshot: Option<ConnectSnapshot>,
    pub cluster: Option<Arc<Cluster>>,
    /// Last `player.setAudioOutput` (re-applied to every new Spirc).
    pub audio_output: Option<AudioOutputInfo>,
    /// Last snapshot while this device was active (reconnect restore point).
    pub last_active: Option<LastActive>,
    /// A reconnect with a pending restore is in progress (frozen playback state).
    pub reconnect: Option<restore::Frozen>,
    /// `lastError` override after the load brake stopped playback (see `player_events`).
    pub refused_error: Option<String>,
}

/// The reconnect restore point while the attached Spirc is not active (anymore).
#[derive(Debug, Clone)]
pub(crate) struct LastActive {
    pub snap: ConnectSnapshot,
    /// Local epoch ms at which the device stopped being active (`None`: it still is). The
    /// position is extrapolated up to this point only.
    pub ended_at_ms: Option<i64>,
}

pub(crate) static HUB: LazyLock<Mutex<HubState>> = LazyLock::new(|| Mutex::new(HubState::default()));

/// Woken on every cluster update (restore waits for the first cluster of a new Spirc).
pub(crate) static CLUSTER_CHANGED: Notify = Notify::const_new();

#[derive(Default)]
struct EmitState {
    playback: String,
    devices: String,
}

static EMIT: Mutex<EmitState> = parking_lot::const_mutex(EmitState { playback: String::new(), devices: String::new() });

/// Everything a new Spirc hands over to the connect layer.
pub(crate) struct Attachment {
    pub generation: u64,
    pub spirc: Arc<Spirc>,
    pub session: Session,
    pub state: watch::Receiver<ConnectSnapshot>,
    pub cluster: watch::Receiver<Option<Arc<Cluster>>>,
    pub errors: broadcast::Receiver<SpircCommandError>,
}

pub(crate) fn me() -> String {
    runtime::config().device_id.clone()
}

pub(crate) fn this_device_ref() -> ActiveDeviceRef {
    ActiveDeviceRef { id: me(), name: engine::device_name(), kind: "smartphone".into() }
}

pub(crate) fn mixer_volume() -> u16 {
    engine::player_host::mixer().map(|m| librespot_playback::mixer::Mixer::volume(m.as_ref())).unwrap_or(0)
}

pub(crate) fn spirc() -> Option<Arc<Spirc>> {
    HUB.lock().link.as_ref().map(|l| l.spirc.clone())
}

pub(crate) fn local_snapshot() -> Option<ConnectSnapshot> {
    HUB.lock().snapshot.clone()
}

pub(crate) fn local_active() -> bool {
    HUB.lock().snapshot.as_ref().is_some_and(|s| s.is_active)
}

pub(crate) fn cluster() -> Option<Arc<Cluster>> {
    HUB.lock().cluster.clone()
}

pub(crate) fn active_device_id() -> Option<String> {
    HUB.lock().cluster.as_ref().map(|c| c.active_device_id.clone()).filter(|id| !id.is_empty())
}

/// Called by the engine for every new Spirc (before the session is declared online).
pub(crate) fn attach(a: Attachment) {
    let Attachment { generation, spirc, session, state, cluster, errors } = a;
    let audio_output = {
        let mut hub = HUB.lock();
        hub.link = Some(Link { generation, spirc: spirc.clone() });
        hub.snapshot = None;
        hub.cluster = None;
        hub.audio_output.clone()
    };
    if let Some(out) = audio_output {
        let kind = super::local::audio_output_kind(&out.kind);
        if let Err(e) = spirc.set_audio_output(kind, out.name) {
            log::debug!("audio output not reported: {e}");
        }
    }
    runtime::handle().spawn(observe(generation, session, state, cluster, errors));
}

/// Called by the engine when a Spirc goes away (teardown or death).
pub(crate) fn detach(generation: u64) {
    {
        let mut hub = HUB.lock();
        if hub.link.as_ref().is_some_and(|l| l.generation == generation) {
            hub.link = None;
            hub.snapshot = None;
            hub.cluster = None;
        }
    }
    publish();
    publish_devices();
}

/// Forgets any attached Spirc (forced cleanup after an aborted supervisor).
pub(crate) fn detach_all() {
    {
        let mut hub = HUB.lock();
        hub.link = None;
        hub.snapshot = None;
        hub.cluster = None;
    }
    publish();
    publish_devices();
}

async fn observe(
    generation: u64,
    session: Session,
    mut state: watch::Receiver<ConnectSnapshot>,
    mut cluster: watch::Receiver<Option<Arc<Cluster>>>,
    mut errors: broadcast::Receiver<SpircCommandError>,
) {
    let initial = state.borrow_and_update().clone();
    on_snapshot(generation, initial, &session);
    let initial_cluster = cluster.borrow_and_update().clone();
    if let Some(c) = initial_cluster {
        on_cluster(generation, c);
    }
    loop {
        tokio::select! {
            r = state.changed() => {
                if r.is_err() {
                    break;
                }
                let s = state.borrow_and_update().clone();
                on_snapshot(generation, s, &session);
            }
            r = cluster.changed() => {
                if r.is_err() {
                    break;
                }
                let c = cluster.borrow_and_update().clone();
                if let Some(c) = c {
                    on_cluster(generation, c);
                }
            }
            e = errors.recv() => match e {
                Ok(err) => on_spirc_error(err),
                Err(broadcast::error::RecvError::Lagged(n)) => log::debug!("skipped {n} spirc errors"),
                Err(broadcast::error::RecvError::Closed) => break,
            },
        }
    }
    log::debug!("spirc observer {generation} ended");
}

fn on_snapshot(generation: u64, snap: ConnectSnapshot, session: &Session) {
    let became_active = {
        let mut hub = HUB.lock();
        if hub.link.as_ref().map(|l| l.generation) != Some(generation) {
            return;
        }
        apply_snapshot(&mut hub, snap.clone(), session.is_invalid(), super::now_ms())
    };
    if snap.ending {
        log::debug!("spirc {generation} ended (active: {}, {:?})", snap.is_active, snap.status);
    }
    if became_active {
        // Spirc owns the Player now.
        offline::deactivate();
    }
    player_events::check_exhausted(&snap);
    publish();
}

/// Records a snapshot of the attached Spirc; returns whether this device became active.
/// `session_invalid`: the session is gone, so an inactive snapshot is not a deliberate stop.
pub(crate) fn apply_snapshot(hub: &mut HubState, snap: ConnectSnapshot, session_invalid: bool, now_ms: i64) -> bool {
    let was_active = hub.snapshot.as_ref().is_some_and(|s| s.is_active);
    let became_active = snap.is_active && !was_active;
    if snap.is_active && snap.track.is_some() {
        hub.last_active = Some(LastActive { snap: snap.clone(), ended_at_ms: None });
    } else if !snap.is_active {
        if hub.reconnect.is_none() && !session_invalid {
            // Deliberately inactive (another device took over, user stop): nothing to restore.
            hub.last_active = None;
        } else if let Some(last) = hub.last_active.as_mut() {
            // Spirc stops the Player when it becomes inactive.
            last.ended_at_ms.get_or_insert(now_ms);
        }
    }
    hub.snapshot = Some(snap);
    became_active
}

fn on_cluster(generation: u64, cluster: Arc<Cluster>) {
    {
        let mut hub = HUB.lock();
        if hub.link.as_ref().map(|l| l.generation) != Some(generation) {
            return;
        }
        hub.cluster = Some(cluster);
    }
    CLUSTER_CHANGED.notify_waiters();
    publish_devices();
    publish();
}

fn on_spirc_error(err: SpircCommandError) {
    if snapshot::is_not_active_error(&err.message) {
        log::debug!("ignored inactive-device error for {}", err.command);
        return;
    }
    use librespot_core::error::ErrorKind;
    let code = match err.kind {
        ErrorKind::Unavailable | ErrorKind::DeadlineExceeded | ErrorKind::Aborted => ErrorCode::Network,
        ErrorKind::NotFound => ErrorCode::NotFound,
        ErrorKind::ResourceExhausted => ErrorCode::RateLimited,
        ErrorKind::InvalidArgument => ErrorCode::InvalidArgument,
        _ => ErrorCode::Unavailable,
    };
    let origin = if err.remote { "Remote command" } else { "Command" };
    log::warn!("{origin} {} failed: {}", err.command, err.message);
    events::emit_error(&AppError::new(code, format!("{origin} {} failed: {}", err.command, err.message)).with_context("connect"));
}

fn time_delta_s() -> i64 {
    engine::try_session().map(|s| s.time_delta()).unwrap_or(0)
}

/// Composes the snapshot from the current sources (without emitting).
pub(crate) fn compose() -> PlaybackSnapshot {
    let device = this_device_ref();
    let (local, cluster, placeholder, refused) = {
        let hub = HUB.lock();
        let local = hub.snapshot.clone().filter(|s| s.is_active);
        let placeholder = hub.reconnect.as_ref().filter(|f| f.since.elapsed() < RECONNECT_PLACEHOLDER_MAX).cloned();
        (local, hub.cluster.clone(), placeholder, hub.refused_error.clone())
    };
    let mut snap = if let Some(s) = local {
        snapshot::map_local(&s, device.clone())
    } else if let Some(s) = offline::snapshot(device.clone(), mixer_volume()) {
        s
    } else if let Some(f) = placeholder {
        // Reconnecting: keep showing what was playing (paused) instead of flashing "nothing".
        let mut p = snapshot::map_local(&f.snap, device.clone());
        p.position_ms = f.position_ms.max(0) as u64;
        p.position_timestamp_ms = f.at_ms;
        p.status = PlaybackStatus::Paused;
        p.playback_speed = 0.0;
        p
    } else if let Some(r) = cluster.as_deref().and_then(|c| snapshot::map_remote(c, &device.id, time_delta_s())) {
        r
    } else {
        snapshot::none_snapshot(mixer_volume())
    };
    metadata::enrich(&mut snap);
    if let Some(err) = refused {
        if snap.source == crate::models::PlaybackSource::Local {
            snap.last_error = Some(err);
        }
    }
    snap
}

/// Emits a `playback` event if the snapshot changed.
pub(crate) fn publish() {
    if !runtime::is_initialized() {
        return;
    }
    let mut emit = EMIT.lock();
    let snap = compose();
    match serde_json::to_string(&snap) {
        Ok(json) if json != emit.playback => {
            bridge::post_event(events::PLAYBACK, &json);
            emit.playback = json;
        }
        Ok(_) => {}
        Err(e) => log::error!("playback snapshot serialisation failed: {e}"),
    }
}

/// The current device list.
pub(crate) fn device_list() -> DeviceList {
    let (cluster, audio_output) = {
        let hub = HUB.lock();
        (hub.cluster.clone(), hub.audio_output.clone())
    };
    let me = ThisDevice { id: me(), name: engine::device_name(), volume: mixer_volume(), audio_output };
    devices::device_list(cluster.as_deref(), &me)
}

/// Emits a `devices` event if the list changed; returns the list.
pub(crate) fn publish_devices() -> DeviceList {
    if !runtime::is_initialized() {
        return DeviceList::default();
    }
    let list = device_list();
    let mut emit = EMIT.lock();
    match serde_json::to_string(&list) {
        Ok(json) if json != emit.devices => {
            bridge::post_event(events::DEVICES, &json);
            emit.devices = json;
        }
        Ok(_) => {}
        Err(e) => log::error!("device list serialisation failed: {e}"),
    }
    list
}

/// Re-emits the device list even if unchanged (`connect.refreshDevices`).
pub(crate) fn force_publish_devices() -> DeviceList {
    EMIT.lock().devices.clear();
    publish_devices()
}
