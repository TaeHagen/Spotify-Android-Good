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

use super::args::LoadArgs;
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
    /// The restore point while its restore is applied (see `restore::Restoring`).
    pub restoring: Option<restore::Restoring>,
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
    /// A play was just sent to another device (nothing was active): until a cluster names the
    /// active device, commands follow it there.
    pub remote_activation: Option<RemoteActivation>,
    /// The offline queue's window ended and Spirc loads its context (see `offline::hand_back`):
    /// until Spirc has its track, the queue's last view is shown.
    pub handing_back: Option<HandingBack>,
    /// A user load sent to the attached Spirc that hasn't played yet (the Spirc fetches its
    /// context, its activation's empty snapshot isn't "nothing loaded"): a push meanwhile starts
    /// that load on the target. Gone once the Spirc has a track, the load failed, or it went
    /// inactive.
    pub loading: Option<LocalLoad>,
    /// A start of this layer failed on the attached Spirc, which goes inactive (its disconnect is
    /// on its way, see [`on_local_load_failed`]): its empty active snapshots read inactive until
    /// an inactive one or one with a track arrives (a play meanwhile gets `NOT_ACTIVE_DEVICE`, a
    /// load activates it again).
    pub deactivating: bool,
}

/// See [`HubState::loading`].
#[derive(Debug, Clone)]
pub(crate) struct LocalLoad {
    pub generation: u64,
    pub at: Instant,
    pub args: LoadArgs,
}

/// See [`HubState::handing_back`].
#[derive(Debug, Clone)]
pub(crate) struct HandingBack {
    pub generation: u64,
    pub at: Instant,
    pub view: PlaybackSnapshot,
}

/// See [`HubState::remote_activation`].
#[derive(Debug, Clone)]
pub(crate) struct RemoteActivation {
    pub device: String,
    pub generation: u64,
    pub at: Instant,
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
    let link = hub.link.as_ref().map(|l| l.generation);
    hub.snapshot.as_ref().is_some_and(|s| s.is_active) || activating(hub, link, Instant::now()) || starting(hub, link, Instant::now())
}

/// An activation was sent to the attached Spirc (`link`) within [`ACTIVATION_GRACE`].
fn activating(hub: &HubState, link: Option<u64>, now: Instant) -> bool {
    hub.activation.is_some_and(|a| link == Some(a.generation) && now.saturating_duration_since(a.at) < ACTIVATION_GRACE)
}

/// A restore is being applied to the attached Spirc (`link`): commands queue behind it there,
/// also once its activation outlived [`ACTIVATION_GRACE`] (the activation's state put alone can
/// take longer on a weak link).
fn restore_applying(restoring: Option<&restore::Restoring>, link: Option<u64>, now: Instant) -> bool {
    restoring.is_some_and(|r| link == Some(r.generation) && now.saturating_duration_since(r.at) < restore::RESTORING_MAX)
}

/// The hand-back's view while it is on its way to the attached Spirc (`link`), for as long as a
/// restore would be.
fn handing_back_view(hub: &HubState, link: Option<u64>, now: Instant) -> Option<&PlaybackSnapshot> {
    hub.handing_back
        .as_ref()
        .filter(|h| link == Some(h.generation) && now.saturating_duration_since(h.at) < restore::RESTORING_MAX)
        .map(|h| &h.view)
}

/// A start of this layer is on its way to the attached Spirc (the restore, a hand-back): its
/// activation's empty snapshot isn't the playback yet.
fn starting(hub: &HubState, link: Option<u64>, now: Instant) -> bool {
    restore_applying(hub.restoring.as_ref(), link, now) || handing_back_view(hub, link, now).is_some()
}

/// The offline queue hands its playback back to the attached Spirc (activate + load follow):
/// `view` (the queue's) stays shown until Spirc has its track, commands go to Spirc.
pub(crate) fn set_handing_back(view: PlaybackSnapshot) {
    let mut hub = HUB.lock();
    hub.remote_activation = None;
    if let Some(generation) = hub.link.as_ref().map(|l| l.generation) {
        let at = Instant::now();
        hub.activation = Some(Activation { generation, at });
        hub.handing_back = Some(HandingBack { generation, at, view });
    }
}

/// The hand-back isn't on its way (it wasn't sent), or a load replaces it.
pub(crate) fn forget_hand_back() {
    HUB.lock().handing_back = None;
}

/// A user load (`args`) was sent to the attached Spirc (see [`HubState::loading`]).
pub(crate) fn set_loading(args: &LoadArgs) {
    let mut hub = HUB.lock();
    if let Some(generation) = hub.link.as_ref().map(|l| l.generation) {
        hub.loading = Some(LocalLoad { generation, at: Instant::now(), args: args.clone() });
    }
}

/// The user load on its way to the attached Spirc, if any (for as long as a restore would be).
pub(crate) fn local_load() -> Option<LoadArgs> {
    let hub = HUB.lock();
    local_load_in(&hub, hub.link.as_ref().map(|l| l.generation), Instant::now()).cloned()
}

fn local_load_in(hub: &HubState, link: Option<u64>, now: Instant) -> Option<&LoadArgs> {
    hub.loading
        .as_ref()
        .filter(|l| link == Some(l.generation) && now.saturating_duration_since(l.at) < restore::RESTORING_MAX)
        .map(|l| &l.args)
}

/// The user load went elsewhere (pushed to another device).
pub(crate) fn forget_local_load() {
    HUB.lock().loading = None;
}

/// The Spirc `generation` (if attached) and its latest snapshot.
pub(crate) fn link_snapshot(generation: u64) -> Option<(Arc<Spirc>, ConnectSnapshot)> {
    let hub = HUB.lock();
    let link = hub.link.as_ref().filter(|l| l.generation == generation)?;
    Some((link.spirc.clone(), hub.snapshot.clone()?))
}

/// Detaches whatever Spirc is attached (see [`detach`]): the session goes offline for a load of
/// downloads without a network (`connect::load`).
pub(crate) fn detach_current() {
    let generation = HUB.lock().link.as_ref().map(|l| l.generation);
    if let Some(generation) = generation {
        detach(generation);
    }
}

/// A local load with activation was sent to the attached Spirc.
pub(crate) fn set_activating() {
    let mut hub = HUB.lock();
    hub.remote_activation = None;
    if let Some(generation) = hub.link.as_ref().map(|l| l.generation) {
        hub.activation = Some(Activation { generation, at: Instant::now() });
    }
}

/// A play was sent to `device` (a load for it, or a transfer starting a session there).
pub(crate) fn set_remote_activating(device: &str) {
    let mut hub = HUB.lock();
    if let Some(generation) = hub.link.as_ref().map(|l| l.generation) {
        hub.remote_activation = Some(RemoteActivation { device: device.to_string(), generation, at: Instant::now() });
    }
}

/// The device a play was just sent to, until a cluster names the active one (at most
/// [`ACTIVATION_GRACE`]).
pub(crate) fn remote_activating() -> Option<String> {
    let hub = HUB.lock();
    remote_activating_in(&hub, hub.link.as_ref().map(|l| l.generation), Instant::now())
}

fn remote_activating_in(hub: &HubState, link: Option<u64>, now: Instant) -> Option<String> {
    hub.remote_activation
        .as_ref()
        .filter(|r| link == Some(r.generation) && now.saturating_duration_since(r.at) < ACTIVATION_GRACE)
        .map(|r| r.device.clone())
}

/// This device is the active one with nothing loaded (a load failed after its activation), and
/// no activation or restore is on its way: a play there would do nothing.
pub(crate) fn local_active_empty() -> bool {
    let hub = HUB.lock();
    local_active_empty_in(&hub, hub.link.as_ref().map(|l| l.generation), Instant::now())
}

fn local_active_empty_in(hub: &HubState, link: Option<u64>, now: Instant) -> bool {
    let empty = hub.snapshot.as_ref().is_some_and(|s| {
        s.is_active && s.track.is_none() && s.status == librespot_connect::SnapshotPlayStatus::Stopped
    });
    empty && !activating(hub, link, now) && !starting(hub, link, now) && local_load_in(hub, link, now).is_none()
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
    hub.remote_activation = None;
    hub.last_active = None;
    hub.restoring = None;
    hub.handing_back = None;
    hub.loading = None;
    hub.deactivating = false;
}

/// [`detach`] on the state. `online`: the session stays (hidden from Spotify Connect), so the last
/// cluster is kept for the remote player state; once there is neither a link nor a session, a
/// kept cluster is dropped too.
pub(crate) fn detach_state(hub: &mut HubState, generation: u64, online: bool) {
    if hub.link.as_ref().is_some_and(|l| l.generation == generation) {
        hub.link = None;
        hub.snapshot = None;
        hub.activation = None;
        hub.remote_activation = None;
        hub.handing_back = None;
        hub.loading = None;
        hub.deactivating = false;
    }
    if hub.link.is_none() && !online {
        hub.cluster = None;
    }
}

/// The engine is done with the attached Spirc: it no longer touches the shared Player, not even
/// while it shuts down (the offline queue may take the Player over right away, see
/// `Spirc::release_player`). Returns whether a Spirc was released: the Player is then paused
/// here instead (see [`pauses_released`]).
fn release_link(hub: &HubState) -> bool {
    let Some(link) = hub.link.as_ref() else { return false };
    link.spirc.release_player();
    true
}

/// Whether the Player is paused after a release. Not by the released Spirc's last snapshot: it
/// can lag a whole handler (a resume whose state put hangs still shows paused). Only that Spirc
/// or the offline queue drive the Player, and pausing an idle Player does nothing.
fn pauses_released(released: bool, offline_active: bool) -> bool {
    released && !offline_active
}

fn pause_released(released: bool) {
    if pauses_released(released, offline::is_active()) {
        if let Some(player) = engine::player_host::player() {
            log::info!("pausing the player of the detached spirc");
            player.pause();
        }
    }
}

/// Called by the engine when a Spirc goes away (teardown or death, or hiding from Spotify
/// Connect while the session stays online).
pub(crate) fn detach(generation: u64) {
    // Hidden (the session stays online): keep the last cluster for the remote player state. A
    // later teardown of the hidden session (offline, stopped) drops it, there is no link then.
    let online = engine::is_online();
    let released = {
        let mut hub = HUB.lock();
        let current = hub.link.as_ref().is_some_and(|l| l.generation == generation);
        let released = current && release_link(&hub);
        detach_state(&mut hub, generation, online);
        released
    };
    pause_released(released);
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
    let released = {
        let mut hub = HUB.lock();
        let released = release_link(&hub);
        hub.link = None;
        hub.snapshot = None;
        hub.cluster = None;
        hub.activation = None;
        hub.remote_activation = None;
        hub.handing_back = None;
        hub.loading = None;
        hub.deactivating = false;
        released
    };
    pause_released(released);
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
/// Player is released (Spirc pauses it itself when it ends, unless it was detached, then
/// `detach` did; this also covers a task whose future was dropped before its last snapshot).
/// Not if a newer Spirc or the offline queue owns the Player by now.
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
pub(crate) fn apply_snapshot(hub: &mut HubState, mut snap: ConnectSnapshot, session_invalid: bool, now_ms: i64) -> bool {
    if hub.deactivating {
        // Going inactive (see `HubState::deactivating`): until it is, or something plays there
        // again, its empty active snapshots are stale.
        if snap.is_active && snap.track.is_none() {
            snap.is_active = false;
        } else {
            hub.deactivating = false;
        }
    }
    let was_active = hub.snapshot.as_ref().is_some_and(|s| s.is_active);
    let became_active = snap.is_active && !was_active;
    if became_active {
        hub.activation = None;
    }
    if snap.is_active && snap.track.is_some() {
        hub.last_active = Some(LastActive { snap: snap.clone(), ended_at_ms: None });
        // A restore being applied, a hand-back or a load took (the Spirc publishes nothing while
        // it fetches a load's context).
        hub.restoring = None;
        hub.handing_back = None;
        hub.loading = None;
    } else if !snap.is_active {
        if was_active {
            // Taken over or stopped before the hand-back or the load took.
            hub.handing_back = None;
            hub.loading = None;
        }
        if was_active && !session_invalid {
            // Deliberately inactive (taken over, user stop) before a restore being applied took.
            hub.restoring = None;
        }
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
        if !cluster.active_device_id.is_empty() {
            // The device a play was sent to is known (or another one took over).
            hub.remote_activation = None;
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
    if !err.remote && err.command == "load" {
        HUB.lock().loading = None;
        on_local_load_failed();
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
    let message = format!("{origin} {} failed: {}", err.command, err.message);
    events::emit_error(&AppError::new(code, message).with_context(error_context(&err.command, err.remote)));
}

/// A load of this phone failed on the attached Spirc. If it was a start this layer sent (the
/// restore, the offline queue's hand-back), the Spirc is active with nothing loaded: the session
/// that start carried goes (the app's own resume is the fallback: a play gets `NOT_ACTIVE_DEVICE`
/// and the app loads its stored session), and this phone goes inactive right away (see
/// [`HubState::deactivating`]). A failed load of the user's leaves things as they are (the
/// playback before it plays on; with nothing loaded a play gets `NOT_ACTIVE_DEVICE` too).
fn on_local_load_failed() {
    let (spirc, failed) = {
        let mut hub = HUB.lock();
        let Some((generation, spirc)) = hub.link.as_ref().map(|l| (l.generation, l.spirc.clone())) else { return };
        let Some(failed) = local_load_failed_in(&mut hub, generation) else { return };
        (spirc, failed)
    };
    if failed.deactivated {
        if let Err(e) = spirc.disconnect(false) {
            log::debug!("spirc gone: {e}");
        }
    }
    if failed.orphaned && !offline::is_active() {
        // The interrupted session's paused track has no owner anymore (the hand-back stopped
        // the queue's before it was sent).
        if let Some(player) = engine::player_host::player() {
            player.stop();
        }
    }
    changed();
    publish();
}

/// What [`local_load_failed_in`] did.
#[derive(Debug, PartialEq, Eq)]
struct LoadFailed {
    /// The Spirc is made inactive (to disconnect).
    deactivated: bool,
    /// The restore's paused track has no owner anymore (to stop).
    orphaned: bool,
}

/// [`on_local_load_failed`] on the state of the attached Spirc `generation`: `None` if it wasn't
/// a start of this layer.
fn local_load_failed_in(hub: &mut HubState, generation: u64) -> Option<LoadFailed> {
    let restore = restore::load_failed_in(hub, generation);
    let hand_back = hub.handing_back.take_if(|h| h.generation == generation).is_some();
    if !restore && !hand_back {
        return None;
    }
    if hand_back {
        log::warn!("the hand-back's load failed");
    }
    // (unless something plays there by now: a transfer to this phone meanwhile)
    let empty = hub.snapshot.as_ref().is_none_or(|s| s.track.is_none());
    if empty {
        deactivate(hub);
    }
    Some(LoadFailed { deactivated: empty, orphaned: restore && empty })
}

/// The attached Spirc is made inactive (its disconnect is sent): see
/// [`HubState::deactivating`].
fn deactivate(hub: &mut HubState) {
    hub.activation = None;
    hub.deactivating = true;
    if let Some(s) = hub.snapshot.as_mut() {
        s.is_active = false;
    }
}

/// The error context of a failed Spirc command: `playback` for this phone's own commands that
/// start playback (a failed load, a play with nothing to play: the player shows them), else
/// `connect`.
fn error_context(command: &str, remote: bool) -> &'static str {
    if !remote && matches!(command, "load" | "play" | "play_pause" | "skip_to") { "playback" } else { "connect" }
}

fn time_delta_s() -> i64 {
    engine::try_session().map(|s| s.time_delta()).unwrap_or(0)
}

/// The local side of [`compose`] (see [`local_view`]).
#[derive(Debug, Default)]
struct LocalView {
    /// This device's active snapshot.
    active: Option<ConnectSnapshot>,
    /// The offline queue's last view while it hands back to Spirc.
    hand_back: Option<PlaybackSnapshot>,
    /// The reconnect placeholder (the frozen session, also while its restore is applied).
    placeholder: Option<restore::Frozen>,
    /// An activation of this device is on its way (a load here, e.g. a media-session resume):
    /// another device's playback isn't shown meanwhile.
    activating: bool,
}

/// What this device shows, on Spirc `link`. Until a start of this layer (the restore, a
/// hand-back) has its track, its activation's empty snapshot doesn't replace that start's view
/// (the notification and the media session would go away in between).
fn local_view(hub: &HubState, link: Option<u64>, now: Instant, now_ms: i64) -> LocalView {
    let starting = starting(hub, link, now);
    let active = hub.snapshot.clone().filter(|s| s.is_active && (s.track.is_some() || !starting));
    let frozen = hub.reconnect.as_ref().or(hub.restoring.as_ref().map(|r| &r.frozen));
    LocalView {
        active,
        hand_back: handing_back_view(hub, link, now).cloned(),
        placeholder: frozen.filter(|f| f.age(now, now_ms) < RECONNECT_PLACEHOLDER_MAX).cloned(),
        activating: activating(hub, link, now),
    }
}

/// Composes the snapshot from the current sources (without emitting).
pub(crate) fn compose() -> PlaybackSnapshot {
    let device = this_device_ref();
    let (view, cluster, refused) = {
        let hub = HUB.lock();
        let link = hub.link.as_ref().map(|l| l.generation);
        (local_view(&hub, link, Instant::now(), super::now_ms()), hub.cluster.clone(), hub.refused_error.clone())
    };
    // None once a paused or finished offline queue gave way to a device that took over.
    let offline = offline::snapshot(device.clone(), mixer_volume());
    let remote = || cluster.as_deref().and_then(|c| snapshot::map_remote(c, &device.id, time_delta_s()));
    let mut snap = if let Some(s) = view.active {
        snapshot::map_local(&s, device.clone())
    } else if let Some(s) = view.hand_back {
        s
    } else if let Some(s) = offline {
        s
    } else if let Some(f) = view.placeholder {
        // Reconnecting: keep showing what was playing (paused) instead of flashing "nothing".
        let mut p = snapshot::map_local(&f.snap, device.clone());
        p.position_ms = f.position_ms.max(0) as u64;
        p.position_timestamp_ms = f.at_ms;
        p.status = PlaybackStatus::Paused;
        p.playback_speed = 0.0;
        p
    } else if let Some(r) = remote().filter(|_| !view.activating) {
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
    fn the_placeholder_stays_until_the_restored_spirc_has_its_track() {
        let now = Instant::now();
        let s = ConnectSnapshot {
            is_active: true,
            status: librespot_connect::SnapshotPlayStatus::Playing,
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
        let frozen = restore::freeze(s.clone(), 1_000_000, now);
        let activation = ConnectSnapshot {
            is_active: true,
            status: librespot_connect::SnapshotPlayStatus::Stopped,
            ..Default::default()
        };
        let mut hub = HubState {
            snapshot: Some(activation),
            restoring: Some(restore::Restoring { generation: 3, frozen, at: now }),
            ..Default::default()
        };
        // the activation's empty snapshot: the frozen track stays shown
        let view = local_view(&hub, Some(3), now, 1_001_000);
        assert!(view.active.is_none());
        assert_eq!(view.placeholder.and_then(|f| f.snap.track).map(|t| t.uri).as_deref(), Some("spotify:track:a"));
        // with its track the restored playback is shown
        hub.snapshot = Some(s);
        assert!(local_view(&hub, Some(3), now, 1_001_000).active.is_some());
        // an empty active snapshot without a restore in flight is shown as it is (a real stop)
        hub.snapshot = Some(ConnectSnapshot { is_active: true, ..Default::default() });
        hub.restoring = None;
        assert!(local_view(&hub, Some(3), now, 1_001_000).active.is_some());
    }

    fn playing_track() -> ConnectSnapshot {
        ConnectSnapshot {
            is_active: true,
            status: librespot_connect::SnapshotPlayStatus::Playing,
            track: Some(librespot_connect::SnapshotTrack {
                uri: "spotify:track:b".into(),
                uid: "b".into(),
                provider: librespot_connect::TrackProvider::Context,
                context_index: None,
                hidden: false,
                metadata: Default::default(),
            }),
            ..Default::default()
        }
    }

    fn empty_active() -> ConnectSnapshot {
        ConnectSnapshot { is_active: true, status: librespot_connect::SnapshotPlayStatus::Stopped, ..Default::default() }
    }

    #[test]
    fn a_hand_back_shows_the_queue_until_spirc_has_its_track() {
        let now = Instant::now();
        let view = PlaybackSnapshot { status: PlaybackStatus::Loading, ..Default::default() };
        let hand_back = || Some(HandingBack { generation: 3, at: now, view: view.clone() });
        let mut hub = HubState { snapshot: Some(empty_active()), handing_back: hand_back(), ..Default::default() };
        // the activation's empty snapshot: the queue's view stays, commands go to Spirc
        let v = local_view(&hub, Some(3), now, 0);
        assert!(v.active.is_none());
        assert_eq!(v.hand_back.map(|s| s.status), Some(PlaybackStatus::Loading));
        assert!(!local_active_empty_in(&hub, Some(3), now + ACTIVATION_GRACE));
        // another Spirc, or past the bound: not any more
        assert!(local_view(&hub, Some(4), now, 0).hand_back.is_none());
        assert!(local_view(&hub, Some(3), now + restore::RESTORING_MAX, 0).hand_back.is_none());
        // Spirc's track: shown, the hand-back is done
        apply_snapshot(&mut hub, playing_track(), false, 0);
        assert!(hub.handing_back.is_none());
        // taken over (or stopped) before it took
        let mut hub = HubState { snapshot: Some(empty_active()), handing_back: hand_back(), ..Default::default() };
        apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 0);
        assert!(hub.handing_back.is_none());
    }

    #[test]
    fn a_failed_start_makes_this_phone_inactive_at_once() {
        let now = Instant::now();
        let frozen = restore::freeze(playing_track(), 0, now);
        // the restore's load failed: its restore point goes, the empty Spirc reads inactive
        let mut hub = HubState {
            snapshot: Some(empty_active()),
            restoring: Some(restore::Restoring { generation: 3, frozen, at: now }),
            ..Default::default()
        };
        assert_eq!(local_load_failed_in(&mut hub, 4), None, "another Spirc's");
        assert_eq!(local_load_failed_in(&mut hub, 3), Some(LoadFailed { deactivated: true, orphaned: true }));
        assert!(hub.restoring.is_none() && hub.reconnect.is_none());
        assert!(!activating_or_active(&hub) && !local_active_empty_in(&hub, Some(3), now));
        let v = local_view(&hub, Some(3), now, 0);
        assert!(v.active.is_none() && v.placeholder.is_none());
        // the Spirc's empty active snapshots before its disconnect stay inactive ...
        apply_snapshot(&mut hub, empty_active(), false, 0);
        assert!(!activating_or_active(&hub));
        // ... until its inactive one
        apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 0);
        assert!(!hub.deactivating);
        apply_snapshot(&mut hub, empty_active(), false, 0);
        assert!(activating_or_active(&hub), "activated again (a load)");
        // or until something plays there again
        let mut hub = HubState { snapshot: Some(empty_active()), deactivating: true, ..Default::default() };
        apply_snapshot(&mut hub, playing_track(), false, 0);
        assert!(!hub.deactivating && activating_or_active(&hub));
        // a failed hand-back: the same, nothing to stop
        let view = PlaybackSnapshot::default();
        let mut hub = HubState {
            snapshot: Some(empty_active()),
            handing_back: Some(HandingBack { generation: 3, at: now, view }),
            ..Default::default()
        };
        assert_eq!(local_load_failed_in(&mut hub, 3), Some(LoadFailed { deactivated: true, orphaned: false }));
        assert!(hub.handing_back.is_none() && hub.deactivating);
        // a failed load of the user's: left as it is
        let mut hub = HubState { snapshot: Some(empty_active()), ..Default::default() };
        assert_eq!(local_load_failed_in(&mut hub, 3), None);
        // something plays there by now (a transfer here meanwhile): not torn down
        let frozen = restore::freeze(playing_track(), 0, now);
        let mut hub = HubState {
            snapshot: Some(playing_track()),
            restoring: Some(restore::Restoring { generation: 3, frozen, at: now }),
            ..Default::default()
        };
        assert_eq!(local_load_failed_in(&mut hub, 3), Some(LoadFailed { deactivated: false, orphaned: false }));
        assert!(activating_or_active(&hub));
    }

    #[test]
    fn a_load_fetching_its_context_isnt_nothing_loaded() {
        let now = Instant::now();
        let loading = || Some(LocalLoad { generation: 3, at: now, args: LoadArgs::default() });
        // the activation's empty snapshot while the load fetches its context (past the grace)
        let mut hub = HubState { snapshot: Some(empty_active()), loading: loading(), ..Default::default() };
        assert!(local_load_in(&hub, Some(3), now + ACTIVATION_GRACE).is_some());
        assert!(!local_active_empty_in(&hub, Some(3), now + ACTIVATION_GRACE));
        // another Spirc's, or past the bound
        assert!(local_active_empty_in(&hub, Some(4), now));
        assert!(local_active_empty_in(&hub, Some(3), now + restore::RESTORING_MAX));
        // it played
        apply_snapshot(&mut hub, playing_track(), false, 0);
        assert!(hub.loading.is_none());
        // taken over before it played
        let mut hub = HubState { snapshot: Some(empty_active()), loading: loading(), ..Default::default() };
        apply_snapshot(&mut hub, ConnectSnapshot::default(), false, 0);
        assert!(hub.loading.is_none());
    }

    #[test]
    fn an_activation_here_hides_another_devices_playback() {
        let now = Instant::now();
        let hub = HubState { activation: Some(Activation { generation: 3, at: now }), ..Default::default() };
        assert!(local_view(&hub, Some(3), now, 0).activating);
        assert!(!local_view(&hub, Some(3), now + ACTIVATION_GRACE, 0).activating);
        assert!(!local_view(&hub, Some(4), now, 0).activating);
    }

    #[test]
    fn commands_follow_a_play_sent_to_another_device() {
        let now = Instant::now();
        let mut hub = HubState {
            remote_activation: Some(RemoteActivation { device: "speaker".into(), generation: 3, at: now }),
            ..Default::default()
        };
        assert_eq!(remote_activating_in(&hub, Some(3), now + Duration::from_secs(2)).as_deref(), Some("speaker"));
        assert_eq!(remote_activating_in(&hub, Some(3), now + ACTIVATION_GRACE), None, "the grace ran out");
        assert_eq!(remote_activating_in(&hub, Some(4), now), None, "another Spirc");
        forget_previous_link(&mut hub);
        assert_eq!(remote_activating_in(&hub, Some(3), now), None);
    }

    #[test]
    fn an_empty_active_spirc_is_no_place_to_play() {
        let now = Instant::now();
        let empty = ConnectSnapshot { is_active: true, status: librespot_connect::SnapshotPlayStatus::Stopped, ..Default::default() };
        let mut hub = HubState { snapshot: Some(empty), ..Default::default() };
        assert!(local_active_empty_in(&hub, Some(3), now));
        // an activation on its way, or a restore being applied: not yet
        hub.activation = Some(Activation { generation: 3, at: now });
        assert!(!local_active_empty_in(&hub, Some(3), now + Duration::from_secs(1)));
        assert!(local_active_empty_in(&hub, Some(3), now + ACTIVATION_GRACE));
        hub.activation = None;
        let frozen = restore::freeze(ConnectSnapshot::default(), 0, now);
        hub.restoring = Some(restore::Restoring { generation: 3, frozen, at: now });
        assert!(!local_active_empty_in(&hub, Some(3), now));
        hub.restoring = None;
        // inactive, or with a track: an ordinary play
        hub.snapshot = Some(ConnectSnapshot::default());
        assert!(!local_active_empty_in(&hub, Some(3), now));
    }

    #[test]
    fn own_failed_starts_are_playback_errors() {
        assert_eq!(error_context("load", false), "playback");
        assert_eq!(error_context("play", false), "playback");
        assert_eq!(error_context("play_pause", false), "playback");
        assert_eq!(error_context("skip_to", false), "playback");
        // commands from other devices that failed here, and the rest
        assert_eq!(error_context("load", true), "connect");
        assert_eq!(error_context("shuffle", false), "connect");
    }

    #[test]
    fn a_restore_being_applied_counts_as_an_activation() {
        let now = Instant::now();
        let frozen = restore::freeze(ConnectSnapshot::default(), 0, now);
        let r = restore::Restoring { generation: 3, frozen, at: now };
        assert!(restore_applying(Some(&r), Some(3), now + Duration::from_secs(15)), "past the activation grace");
        assert!(!restore_applying(Some(&r), Some(4), now), "another Spirc");
        assert!(!restore_applying(Some(&r), None, now));
        assert!(!restore_applying(Some(&r), Some(3), now + restore::RESTORING_MAX), "never took");
        assert!(!restore_applying(None, Some(3), now));
    }

    #[test]
    fn a_released_spirc_is_paused_whatever_it_published() {
        // its last snapshot may say paused or stopped while a resume is stuck in its state put
        assert!(pauses_released(true, false));
        // the offline queue owns the Player: left alone
        assert!(!pauses_released(true, true));
        assert!(!pauses_released(false, false));
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
