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
use std::time::{Duration, Instant};
use tokio::sync::{broadcast, watch, Notify};

/// A placeholder of the last local playback is shown while reconnecting, for at most this long.
const RECONNECT_PLACEHOLDER_MAX: Duration = Duration::from_secs(30 * 60);
/// A local activation (load or restore) counts as "this device is active" for this long until
/// the Spirc's snapshot says so (or it failed).
const ACTIVATION_GRACE: Duration = Duration::from_secs(10);

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
    /// Commands holding the pending restore's decision (see `restore::hold`).
    pub restore_holds: u32,
    /// The Spirc generation whose first cluster is overdue for the pending restore (see
    /// `restore::schedule`).
    pub restore_overdue: Option<u64>,
    /// Activate + load were sent to the attached Spirc (a local load or the restore) and its
    /// snapshot isn't active yet: commands go to it, not "nobody is active".
    pub activation: Option<Activation>,
    /// `lastError` override after the load brake stopped playback (see `player_events`).
    pub refused_error: Option<String>,
}

/// See [`HubState::activation`].
#[derive(Debug, Clone, Copy)]
pub(crate) struct Activation {
    pub generation: u64,
    pub at: Instant,
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

/// Woken whenever something commands and the restore wait for changes: a Spirc attached or
/// detached, a cluster, this device becoming active, the restore decided, held or dropped.
pub(crate) static CHANGED: Notify = Notify::const_new();

pub(crate) fn changed() {
    CHANGED.notify_waiters();
}

#[derive(Default)]
pub(crate) struct EmitState {
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

/// This device is active, or an activation was just sent to its Spirc (a load, the restore).
pub(crate) fn local_active_or_activating() -> bool {
    activating_or_active(&HUB.lock())
}

pub(crate) fn activating_or_active(hub: &HubState) -> bool {
    hub.snapshot.as_ref().is_some_and(|s| s.is_active)
        || hub.activation.is_some_and(|a| {
            hub.link.as_ref().is_some_and(|l| l.generation == a.generation) && a.at.elapsed() < ACTIVATION_GRACE
        })
}

/// A local load with activation was sent to the attached Spirc.
pub(crate) fn set_activating() {
    let mut hub = HUB.lock();
    if let Some(generation) = hub.link.as_ref().map(|l| l.generation) {
        hub.activation = Some(Activation { generation, at: Instant::now() });
    }
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
        forget_previous_link(&mut hub);
        hub.link = Some(Link { generation, spirc: spirc.clone() });
        hub.audio_output.clone()
    };
    changed();
    if let Some(out) = audio_output {
        let kind = super::local::audio_output_kind(&out.kind);
        if let Err(e) = spirc.set_audio_output(kind, out.name) {
            log::debug!("audio output not reported: {e}");
        }
    }
    runtime::handle().spawn(observe(generation, session, state, cluster, errors));
}

/// A new Spirc starts with nothing of the previous one. Its restore point was frozen before it
/// went (or there is none): its last active snapshot must not come back on a later reconnect.
pub(crate) fn forget_previous_link(hub: &mut HubState) {
    hub.snapshot = None;
    hub.cluster = None;
    hub.activation = None;
    hub.last_active = None;
}

/// [`detach`] on the state. `online`: the session stays (hidden from Spotify Connect), so the last
/// cluster is kept for the remote player state; once there is neither a link nor a session, a
/// kept cluster is dropped too.
pub(crate) fn detach_state(hub: &mut HubState, generation: u64, online: bool) {
    if hub.link.as_ref().is_some_and(|l| l.generation == generation) {
        hub.link = None;
        hub.snapshot = None;
        hub.activation = None;
    }
    if hub.link.is_none() && !online {
        hub.cluster = None;
    }
}

/// Called by the engine when a Spirc goes away (teardown or death, or hiding from Spotify
/// Connect while the session stays online).
pub(crate) fn detach(generation: u64) {
    // Hidden (the session stays online): keep the last cluster for the remote player state. A
    // later teardown of the hidden session (offline, stopped) drops it, there is no link then.
    let online = engine::is_online();
    detach_state(&mut HUB.lock(), generation, online);
    changed();
    publish();
    publish_devices();
}

/// The engine is neither online nor attached to a Spirc: a cluster kept while hidden is stale.
pub(crate) fn drop_stale_cluster() {
    let mut hub = HUB.lock();
    if hub.link.is_none() && !engine::is_online() {
        hub.cluster = None;
    }
}

/// Forgets any attached Spirc (forced cleanup after an aborted supervisor).
pub(crate) fn detach_all() {
    {
        let mut hub = HUB.lock();
        hub.link = None;
        hub.snapshot = None;
        hub.cluster = None;
        hub.activation = None;
    }
    changed();
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
    let mut last = initial.clone();
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
                last = s.clone();
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
    on_spirc_ended(generation, &last);
}

/// The Spirc task is gone (its state channel closed): if it was the active device playing, the
/// Player is released (Spirc pauses it itself when it ends; this also covers a task whose
/// future was dropped before its last snapshot). Not if a newer Spirc or the offline queue
/// owns the Player by now.
fn on_spirc_ended(generation: u64, last: &ConnectSnapshot) {
    let playing = last.is_active
        && matches!(
            last.status,
            librespot_connect::SnapshotPlayStatus::Playing | librespot_connect::SnapshotPlayStatus::LoadingPlay
        );
    if !playing {
        return;
    }
    let newer_owner = {
        let hub = HUB.lock();
        hub.link.as_ref().is_some_and(|l| l.generation != generation) && activating_or_active(&hub)
    };
    if newer_owner || offline::is_active() {
        return;
    }
    if let Some(player) = engine::player_host::player() {
        log::info!("spirc {generation} ended while playing, pausing its player");
        player.pause();
    }
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
        changed();
    }
    player_events::check_exhausted(&snap);
    publish();
}

/// Records a snapshot of the attached Spirc; returns whether this device became active.
/// `session_invalid`: the session is gone, so an inactive snapshot is not a deliberate stop.
pub(crate) fn apply_snapshot(hub: &mut HubState, snap: ConnectSnapshot, session_invalid: bool, now_ms: i64) -> bool {
    let was_active = hub.snapshot.as_ref().is_some_and(|s| s.is_active);
    let became_active = snap.is_active && !was_active;
    if became_active {
        hub.activation = None;
    }
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
        hub.cluster = Some(cluster.clone());
    }
    CLUSTER_CHANGED.notify_waiters();
    changed();
    // A paused or finished offline queue gives way to a device that took over.
    offline::on_cluster(&cluster);
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
        let (now, now_ms) = (Instant::now(), super::now_ms());
        let placeholder = hub.reconnect.as_ref().filter(|f| f.age(now, now_ms) < RECONNECT_PLACEHOLDER_MAX).cloned();
        (local, hub.cluster.clone(), placeholder, hub.refused_error.clone())
    };
    // None once a paused or finished offline queue gave way to a device that took over.
    let offline = offline::snapshot(device.clone(), mixer_volume());
    let mut snap = if let Some(s) = local {
        snapshot::map_local(&s, device.clone())
    } else if let Some(s) = offline {
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

/// The current device list. While the online session is hidden from Spotify Connect, this
/// device isn't listed (it can't be controlled or transferred to).
pub(crate) fn device_list() -> DeviceList {
    let (cluster, audio_output, attached) = {
        let hub = HUB.lock();
        (hub.cluster.clone(), hub.audio_output.clone(), hub.link.is_some())
    };
    let me = ThisDevice { id: me(), name: engine::device_name(), volume: mixer_volume(), audio_output };
    let mut list = devices::device_list(cluster.as_deref(), &me);
    if !attached && engine::is_online() {
        list.devices.retain(|d| !d.is_this_device);
    }
    list
}

/// Emits a `devices` event if the list changed; returns the list.
pub(crate) fn publish_devices() -> DeviceList {
    if !runtime::is_initialized() {
        return DeviceList::default();
    }
    publish_devices_locked(&mut EMIT.lock())
}

/// Composes under `EMIT` (like `publish`), so that a caller holding an older hub state can't
/// emit its list after a newer one.
fn publish_devices_locked(emit: &mut EmitState) -> DeviceList {
    let list = device_list();
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

/// Re-emits the device list even if unchanged.
pub(crate) fn force_publish_devices() -> DeviceList {
    if !runtime::is_initialized() {
        return DeviceList::default();
    }
    let mut emit = EMIT.lock();
    emit.devices.clear();
    publish_devices_locked(&mut emit)
}

/// At most one cluster refresh from Spotify per this interval (`connect.refreshDevices`).
const REFRESH_MIN_INTERVAL: Duration = Duration::from_millis(2500);
/// How long `connect.refreshDevices` waits for the refreshed cluster.
const REFRESH_WAIT: Duration = Duration::from_secs(3);

static LAST_REFRESH: Mutex<Option<std::time::Instant>> = parking_lot::const_mutex(None);

/// Whether a refresh may be sent now (records it).
fn refresh_due(last: &mut Option<std::time::Instant>, now: std::time::Instant) -> bool {
    let due = last.is_none_or(|at| now.saturating_duration_since(at) >= REFRESH_MIN_INTERVAL);
    if due {
        *last = Some(now);
    }
    due
}

/// `connect.refreshDevices`: fetches the cluster (device list) from Spotify again, debounced, and
/// emits and returns the new list (the cached one if the refresh is debounced, fails or times out).
pub(crate) async fn refresh_devices() -> DeviceList {
    let spirc = if engine::is_online() { spirc() } else { None };
    if let Some(spirc) = spirc {
        if refresh_due(&mut LAST_REFRESH.lock(), std::time::Instant::now()) {
            let notified = CLUSTER_CHANGED.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            match spirc.refresh_cluster() {
                Ok(()) => {
                    if tokio::time::timeout(REFRESH_WAIT, notified).await.is_err() {
                        log::debug!("no cluster after the device refresh");
                    }
                }
                Err(e) => log::debug!("device refresh not sent: {e}"),
            }
        }
    }
    force_publish_devices()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn device_refresh_is_debounced() {
        let mut last = None;
        let t0 = Instant::now();
        assert!(refresh_due(&mut last, t0));
        assert!(!refresh_due(&mut last, t0 + Duration::from_millis(1000)));
        assert!(!refresh_due(&mut last, t0 + Duration::from_millis(2400)));
        assert!(refresh_due(&mut last, t0 + Duration::from_millis(2600)));
        assert!(!refresh_due(&mut last, t0 + Duration::from_millis(3000)));
    }
}

#[cfg(test)]
mod hub_tests {
    use super::*;

    fn cluster(active: &str) -> Arc<Cluster> {
        Arc::new(Cluster { active_device_id: active.into(), ..Default::default() })
    }

    #[test]
    fn a_cluster_kept_while_hidden_goes_with_the_session() {
        let mut hub = HubState { cluster: Some(cluster("tv")), ..Default::default() };
        // hiding (online, the link already gone): the remote state is kept
        detach_state(&mut hub, 1, true);
        assert!(hub.cluster.is_some());
        // a later teardown of the hidden session (offline, stopped): no stale "Playing on tv"
        detach_state(&mut hub, 1, false);
        assert!(hub.cluster.is_none());
    }

    #[test]
    fn a_new_link_forgets_the_previous_restore_point() {
        let mut hub = HubState { cluster: Some(cluster("")), ..Default::default() };
        let snap = ConnectSnapshot {
            is_active: true,
            track: Some(librespot_connect::SnapshotTrack {
                uri: "spotify:track:a".into(),
                uid: "a".into(),
                provider: librespot_connect::TrackProvider::Context,
                context_index: None,
                hidden: false,
                metadata: Default::default(),
            }),
            ..Default::default()
        };
        apply_snapshot(&mut hub, snap, false, 0);
        assert!(hub.last_active.is_some());
        forget_previous_link(&mut hub);
        assert!(hub.last_active.is_none() && hub.cluster.is_none() && hub.snapshot.is_none());
    }
}
