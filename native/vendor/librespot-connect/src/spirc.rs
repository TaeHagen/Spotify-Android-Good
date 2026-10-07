// SPOTIFYGOOD: + AudioOutputKind, AutoplayContextRequest (smart shuffle suggestions) and the
// published snapshot types
use crate::{
    LoadContextOptions, LoadRequestOptions, PlayContext,
    context_resolver::{ContextAction, ContextResolver, ResolveContext},
    core::{
        Error,
        Session,
        SpotifyUri,
        authentication::Credentials,
        dealer::{
            manager::{BoxedStream, BoxedStreamResult, Reply, RequestReply},
            protocol::{Command, FallbackWrapper, Message, Request},
        },
        session::UserAttributes,
        // SPOTIFYGOOD: + SpClientResult (state puts next to the loop)
        spclient::{SpClientResult, TransferRequest},
    },
    model::{AudioOutputKind, LoadRequest, PlayingTrack, SpircPlayStatus},
    playback::{
        mixer::Mixer,
        // SPOTIFYGOOD: + UnavailableReason
        player::{Player, PlayerEvent, PlayerEventChannel, UnavailableReason},
    },
    protocol::{
        autoplay_context_request::AutoplayContextRequest,
        // SPOTIFYGOOD: + PutStateReason
        connect::{Cluster, ClusterUpdate, LogoutCommand, PutStateReason, SetVolumeCommand},
        context::Context,
        explicit_content_pubsub::UserAttributesUpdate,
        playlist4_external::PlaylistModificationInfo,
        social_connect_v2::SessionUpdate,
        transfer_state::TransferState,
        user_attributes::UserAttributesMutation,
    },
    snapshot::{ConnectSnapshot, SnapshotPlayStatus, SpircCommandError},
    state::{
        // SPOTIFYGOOD: queue limit of Spirc::add_to_queue
        SPOTIFY_MAX_NEXT_TRACKS_SIZE,
        StateError,
        context::{ContextType, ResetContext},
        provider::IsProvider,
        {ConnectConfig, ConnectState},
    },
};
// SPOTIFYGOOD: + BoxFuture, FutureExt (the state put in flight)
use futures_util::{
    StreamExt,
    future::{BoxFuture, FutureExt},
};
use librespot_protocol::context_page::ContextPage;
use protobuf::MessageField;
// SPOTIFYGOOD: + VecDeque (commands received before the connection was established)
use std::{
    collections::VecDeque,
    future::Future,
    sync::Arc,
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use thiserror::Error;
// SPOTIFYGOOD: broadcast/watch for the published state, timeout/Instant for shutdown and the
// smart shuffle backoff
use tokio::{
    sync::{broadcast, mpsc, watch},
    time::{Instant, error::Elapsed, sleep, timeout},
};

#[derive(Debug, Error)]
enum SpircError {
    #[error("response payload empty")]
    NoData,
    #[error("{0} had no uri")]
    NoUri(&'static str),
    #[error("message pushed for another URI")]
    InvalidUri(String),
    #[error("failed to put connect state for new device")]
    FailedDealerSetup,
    #[error("unknown endpoint: {0:#?}")]
    UnknownEndpoint(serde_json::Value),
    // SPOTIFYGOOD: reported instead of silently ignoring the command
    #[error("{0} is ignored while the device is not the active connect device")]
    NotActive(&'static str),
    // SPOTIFYGOOD: see Spirc::set_autoplay
    #[error("autoplay is overridden by the session config")]
    AutoplayOverridden,
    // SPOTIFYGOOD: see SpircTask::handle_play_stopped
    #[error("there is no track to play")]
    NothingToPlay,
}

impl From<SpircError> for Error {
    fn from(err: SpircError) -> Self {
        use SpircError::*;
        match err {
            NoData | NoUri(_) => Error::unavailable(err),
            InvalidUri(_) | FailedDealerSetup => Error::aborted(err),
            UnknownEndpoint(_) => Error::unimplemented(err),
            // SPOTIFYGOOD
            NotActive(_) | AutoplayOverridden | NothingToPlay => Error::failed_precondition(err),
        }
    }
}

struct SpircTask {
    player: Arc<Player>,
    mixer: Arc<dyn Mixer>,

    /// the state management object
    connect_state: ConnectState,
    connect_established: bool,

    play_request_id: Option<u64>,
    play_status: SpircPlayStatus,

    connection_id_update: BoxedStreamResult<String>,
    connect_state_update: BoxedStreamResult<ClusterUpdate>,
    connect_state_volume_update: BoxedStreamResult<SetVolumeCommand>,
    connect_state_logout_request: BoxedStreamResult<LogoutCommand>,
    playlist_update: BoxedStreamResult<PlaylistModificationInfo>,
    session_update: BoxedStreamResult<FallbackWrapper<SessionUpdate>>,
    connect_state_command: BoxedStream<RequestReply>,
    user_attributes_update: BoxedStreamResult<UserAttributesUpdate>,
    user_attributes_mutation: BoxedStreamResult<UserAttributesMutation>,

    commands: Option<mpsc::UnboundedReceiver<SpircCommand>>,
    player_events: Option<PlayerEventChannel>,

    context_resolver: ContextResolver,

    shutdown: bool,
    session: Session,

    /// is set when transferring, and used after resolving the contexts to finish the transfer
    pub transfer_state: Option<TransferState>,

    /// when set to true, it will update the volume after [VOLUME_UPDATE_DELAY],
    /// when no other future resolves, otherwise resets the delay
    update_volume: bool,

    /// when set to true, it will update the volume after [UPDATE_STATE_DELAY],
    /// when no other future resolves, otherwise resets the delay
    update_state: bool,

    spirc_id: usize,

    // SPOTIFYGOOD: added fields, see the corresponding Spirc methods
    /// see [ConnectConfig::auto_takeover]
    auto_takeover: bool,
    /// commands received before the connection was established (except shutdown)
    pending_commands: VecDeque<SpircCommand>,
    snapshot_tx: watch::Sender<ConnectSnapshot>,
    /// fingerprint of the last published snapshot
    snapshot_fingerprint: Option<u64>,
    cluster_tx: watch::Sender<Option<Arc<Cluster>>>,
    errors_tx: broadcast::Sender<SpircCommandError>,
    /// the last failed command, published in the snapshot
    last_error: Option<String>,
    suggestions_tx: mpsc::UnboundedSender<SuggestionResponse>,
    suggestions_rx: mpsc::UnboundedReceiver<SuggestionResponse>,
    suggestion_fetch: SuggestionFetch,
    queue_gauge: Arc<QueueGauge>,
    /// the local autoplay value set with Spirc::set_autoplay, it wins over the account value
    autoplay_override: Option<bool>,
    /// the loop ended while the player played (or was paused), pause it when the task ends
    pause_on_drop: bool,
    /// set by Spirc::release_player
    player_released: Arc<AtomicBool>,
    /// the state puts, see [StatePuts]
    state_puts: StatePuts,
}

// SPOTIFYGOOD: lets Spirc::add_to_queue reject an add right away when the queue is full, the
// command itself is only handled later by the task
#[derive(Default)]
struct QueueGauge {
    /// queued tracks in the next tracks, as of the last handled event
    queued: AtomicUsize,
    /// add_to_queue commands sent but not handled yet
    pending: AtomicUsize,
}

impl QueueGauge {
    fn add_handled(&self, queued: usize) {
        self.queued.store(queued, Ordering::Release);
        let _ = self
            .pending
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_sub(1));
    }
}

// SPOTIFYGOOD: the state puts run next to the loop. The handlers awaited them, unbounded: on a
// stalled or rate limited connection (spclient sleeps out every Retry-After) no other command and
// no player event was handled meanwhile, so e.g. a pause (headphones unplugged) waited behind the
// put of the previous command, for up to a minute.
/// What a state put announces
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StatePut {
    /// the player state (the request's own reason)
    State,
    Volume,
    AudioOutput,
}

impl StatePut {
    fn reason(self) -> Option<PutStateReason> {
        match self {
            StatePut::State => None,
            StatePut::Volume => Some(PutStateReason::VOLUME_CHANGED),
            StatePut::AudioOutput => Some(PutStateReason::AUDIO_DRIVER_INFO_CHANGED),
        }
    }
}

type StatePutResult = Result<SpClientResult, Elapsed>;

/// The state puts: at most one is in flight (polled by the loop, bounded by [STATE_PUT_TIMEOUT]),
/// the ones requested meanwhile wait, at most one of each kind in the order they were first
/// requested. A put is built when it is sent, so it carries the state of then.
#[derive(Default)]
struct StatePuts {
    in_flight: Option<(StatePut, BoxFuture<'static, StatePutResult>)>,
    waiting: VecDeque<StatePut>,
}

impl StatePuts {
    /// Requests a put, returns whether it is to be sent right away (none is in flight)
    fn request(&mut self, put: StatePut) -> bool {
        if self.in_flight.is_none() {
            return true;
        }
        if !self.waiting.contains(&put) {
            self.waiting.push_back(put)
        }
        false
    }

    fn start(&mut self, put: StatePut, request: BoxFuture<'static, StatePutResult>) {
        self.in_flight = Some((put, request));
    }

    /// The put in flight is done, returns the next one to send
    fn done(&mut self) -> Option<StatePut> {
        self.in_flight = None;
        self.waiting.pop_front()
    }

    /// Drops the put in flight (its request is cancelled) and the waiting ones
    fn cancel(&mut self) {
        self.in_flight = None;
        self.waiting.clear();
    }
}

// SPOTIFYGOOD: the result of a smart shuffle suggestion fetch, sent back into the loop
struct SuggestionResponse {
    generation: u64,
    context_uri: String,
    result: Result<Context, Error>,
}

// SPOTIFYGOOD: bookkeeping of the smart shuffle suggestion fetches
#[derive(Default)]
struct SuggestionFetch {
    /// incremented whenever outstanding results become invalid
    generation: u64,
    /// at most one fetch is in flight
    in_flight: bool,
    /// no fetch before this point in time (backoff)
    retry_at: Option<Instant>,
    /// consecutive failures, for the backoff
    failures: u32,
    /// the request in flight, it holds a strong session
    task: Option<tokio::task::AbortHandle>,
}

impl SuggestionFetch {
    /// invalidates outstanding results and allows an immediate fetch
    fn restart(&mut self) {
        self.cancel();
        self.generation += 1;
        self.retry_at = None;
        self.failures = 0;
    }

    /// aborts the request in flight
    fn cancel(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
        self.in_flight = false;
    }
}

static SPIRC_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[derive(Debug)]
enum SpircCommand {
    Play,
    PlayPause,
    Pause,
    Prev,
    Next,
    VolumeUp,
    VolumeDown,
    Shutdown,
    Shuffle(bool),
    Repeat(bool),
    RepeatTrack(bool),
    Disconnect { pause: bool },
    SetPosition(u32),
    SetVolume(u16),
    Activate,
    Transfer(Option<TransferRequest>),
    Load(LoadRequest),
    // SPOTIFYGOOD: local queue commands
    AddToQueue(String),
    RemoveFromQueue(String),
    MoveQueueItem { uid: String, to: usize },
    ClearQueue,
    SkipTo(String),
    // SPOTIFYGOOD: local smart shuffle
    SmartShuffle(bool),
    // SPOTIFYGOOD: allowed while inactive
    SetAudioOutput(AudioOutputKind, Option<String>),
    SetAutoplay(bool),
    RefreshCluster,
}

// SPOTIFYGOOD: names used in the reported command errors
impl SpircCommand {
    fn name(&self) -> &'static str {
        use SpircCommand::*;
        match self {
            Play => "play",
            PlayPause => "play_pause",
            Pause => "pause",
            Prev => "prev",
            Next => "next",
            VolumeUp => "volume_up",
            VolumeDown => "volume_down",
            Shutdown => "shutdown",
            Shuffle(_) => "shuffle",
            Repeat(_) => "repeat",
            RepeatTrack(_) => "repeat_track",
            Disconnect { .. } => "disconnect",
            SetPosition(_) => "set_position_ms",
            SetVolume(_) => "set_volume",
            Activate => "activate",
            Transfer(_) => "transfer",
            Load(_) => "load",
            AddToQueue(_) => "add_to_queue",
            RemoveFromQueue(_) => "remove_from_queue",
            MoveQueueItem { .. } => "move_queue_item",
            ClearQueue => "clear_queue",
            SkipTo(_) => "skip_to",
            SmartShuffle(_) => "smart_shuffle",
            SetAudioOutput(..) => "set_audio_output",
            SetAutoplay(_) => "set_autoplay",
            RefreshCluster => "refresh_cluster",
        }
    }
}

const CONTEXT_FETCH_THRESHOLD: usize = 2;

// SPOTIFYGOOD: upper bound for the network calls during shutdown
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);
// SPOTIFYGOOD: upper bound for starting the dealer, the task ends after it
const DEALER_START_TIMEOUT: Duration = Duration::from_secs(30);
// SPOTIFYGOOD: upper bound for every state put (see StatePuts)
const STATE_PUT_TIMEOUT: Duration = Duration::from_secs(5);
// SPOTIFYGOOD: upper bound for a request a command waits for (the transfer to this device)
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
// SPOTIFYGOOD: capacity of the command error broadcast, slow receivers lag (skip) old errors
const ERROR_CHANNEL_CAPACITY: usize = 16;
// SPOTIFYGOOD: smart shuffle fetch pacing
const SUGGESTION_MIN_INTERVAL: Duration = Duration::from_secs(30);
const SUGGESTION_EMPTY_BACKOFF: Duration = Duration::from_secs(5 * 60);
const SUGGESTION_ERROR_BACKOFF: Duration = Duration::from_secs(30);
const SUGGESTION_MAX_BACKOFF: Duration = Duration::from_secs(10 * 60);
const SUGGESTION_TIMEOUT: Duration = Duration::from_secs(20);

// SPOTIFYGOOD: upper bound for the put that announces the device (its response is the first
// cluster); the task ends after it (failed dealer setup), the app connects again
const NEW_DEVICE_PUT_TIMEOUT: Duration = Duration::from_secs(30);

// SPOTIFYGOOD: the result of a put bounded by a timeout
fn bounded_put<T>(
    what: &str,
    result: Result<Result<T, Error>, tokio::time::error::Elapsed>,
) -> Result<T, Error> {
    result.unwrap_or_else(|_| Err(Error::deadline_exceeded(format!("{what} put timed out"))))
}

// delay to update volume after a certain amount of time, instead on each update request
const VOLUME_UPDATE_DELAY: Duration = Duration::from_millis(500);
// to reduce updates to remote, we group some request by waiting for a set amount of time
const UPDATE_STATE_DELAY: Duration = Duration::from_millis(200);

/// The spotify connect handle
pub struct Spirc {
    commands: mpsc::UnboundedSender<SpircCommand>,
    // SPOTIFYGOOD: observation channels, see the subscribe_* methods
    snapshot_rx: watch::Receiver<ConnectSnapshot>,
    cluster_rx: watch::Receiver<Option<Arc<Cluster>>>,
    errors_rx: broadcast::Receiver<SpircCommandError>,
    queue_gauge: Arc<QueueGauge>,
    player_released: Arc<AtomicBool>,
}

impl Spirc {
    /// Initializes a new spotify connect device
    ///
    /// The returned tuple consists out of a handle to the [`Spirc`] that
    /// can control the local connect device when active. And a [`Future`]
    /// which represents the [`Spirc`] event loop that processes the whole
    /// connect device logic.
    pub async fn new(
        config: ConnectConfig,
        session: Session,
        credentials: Credentials,
        player: Arc<Player>,
        mixer: Arc<dyn Mixer>,
    ) -> Result<(Spirc, impl Future<Output = ()>), Error> {
        fn extract_connection_id(msg: Message) -> Result<String, Error> {
            let connection_id = msg
                .headers
                .get("Spotify-Connection-Id")
                .ok_or_else(|| SpircError::InvalidUri(msg.uri.clone()))?;
            Ok(connection_id.to_owned())
        }

        let spirc_id = SPIRC_COUNTER.fetch_add(1, Ordering::AcqRel);
        debug!("new Spirc[{spirc_id}]");

        // SPOTIFYGOOD: the config is consumed by ConnectState
        let auto_takeover = config.auto_takeover;
        let connect_state = ConnectState::new(config, &session);

        let connection_id_update = session
            .dealer()
            .listen_for("hm://pusher/v1/connections/", extract_connection_id)?;

        let connect_state_update = session
            .dealer()
            .listen_for("hm://connect-state/v1/cluster", Message::from_raw)?;

        let connect_state_volume_update = session
            .dealer()
            .listen_for("hm://connect-state/v1/connect/volume", Message::from_raw)?;

        let connect_state_logout_request = session
            .dealer()
            .listen_for("hm://connect-state/v1/connect/logout", Message::from_raw)?;

        let playlist_update = session
            .dealer()
            .listen_for("hm://playlist/v2/playlist/", Message::from_raw)?;

        let session_update = session
            .dealer()
            .listen_for("social-connect/v2/session_update", Message::try_from_json)?;

        let user_attributes_update = session
            .dealer()
            .listen_for("spotify:user:attributes:update", Message::from_raw)?;

        // can be trigger by toggling autoplay in a desktop client
        let user_attributes_mutation = session
            .dealer()
            .listen_for("spotify:user:attributes:mutated", Message::from_raw)?;

        let connect_state_command = session
            .dealer()
            .handle_for("hm://connect-state/v1/player/command")?;

        // pre-acquire client_token, preventing multiple request while running
        let _ = session.spclient().client_token().await?;

        // Connect *after* all message listeners are registered
        session.connect(credentials, true).await?;

        // pre-acquire access_token (we need to be authenticated to retrieve a token)
        let _ = session.login5().auth_token().await?;

        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();

        // SPOTIFYGOOD: observation channels
        let (snapshot_tx, snapshot_rx) = watch::channel(ConnectSnapshot::default());
        let (cluster_tx, cluster_rx) = watch::channel(None);
        let (errors_tx, errors_rx) = broadcast::channel(ERROR_CHANNEL_CAPACITY);
        let (suggestions_tx, suggestions_rx) = mpsc::unbounded_channel();

        let player_events = player.get_player_event_channel();
        // SPOTIFYGOOD
        let queue_gauge = Arc::new(QueueGauge::default());
        let player_released = Arc::new(AtomicBool::new(false));

        let mut task = SpircTask {
            player,
            mixer,

            connect_state,
            connect_established: false,

            play_request_id: None,
            play_status: SpircPlayStatus::Stopped,

            connection_id_update,
            connect_state_update,
            connect_state_volume_update,
            connect_state_logout_request,
            playlist_update,
            session_update,
            connect_state_command,
            user_attributes_update,
            user_attributes_mutation,
            commands: Some(cmd_rx),
            player_events: Some(player_events),

            context_resolver: ContextResolver::new(session.clone()),

            shutdown: false,
            session,

            transfer_state: None,
            update_volume: false,
            update_state: false,

            spirc_id,

            // SPOTIFYGOOD
            auto_takeover,
            pending_commands: VecDeque::new(),
            snapshot_tx,
            snapshot_fingerprint: None,
            cluster_tx,
            errors_tx,
            last_error: None,
            suggestions_tx,
            suggestions_rx,
            suggestion_fetch: SuggestionFetch::default(),
            queue_gauge: queue_gauge.clone(),
            autoplay_override: None,
            pause_on_drop: false,
            player_released: player_released.clone(),
            state_puts: StatePuts::default(),
        };

        let spirc = Spirc {
            commands: cmd_tx,
            // SPOTIFYGOOD
            snapshot_rx,
            cluster_rx,
            errors_rx,
            queue_gauge,
            player_released,
        };

        let initial_volume = task.connect_state.device_info().volume;
        task.connect_state.set_volume(0);

        match initial_volume.try_into() {
            Ok(volume) => {
                task.set_volume(volume);
                // we don't want to update the volume initially,
                // we just want to set the mixer to the correct volume
                task.update_volume = false;
            }
            Err(why) => error!("failed to update initial volume: {why}"),
        };

        // SPOTIFYGOOD: subscribers start with the initial state
        task.publish_snapshot();

        Ok((spirc, task.run()))
    }

    /// Safely shutdowns the spirc.
    ///
    /// This pauses the playback, disconnects the connect device and
    /// bring the future initially returned to an end.
    pub fn shutdown(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Shutdown)?)
    }

    /// Resumes the playback
    ///
    /// Does nothing if we are not the active device, or it isn't paused.
    pub fn play(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Play)?)
    }

    /// Resumes or pauses the playback
    ///
    /// Does nothing if we are not the active device.
    pub fn play_pause(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::PlayPause)?)
    }

    /// Pauses the playback
    ///
    /// Does nothing if we are not the active device, or if it isn't playing.
    pub fn pause(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Pause)?)
    }

    /// Seeks to the beginning or skips to the previous track.
    ///
    /// Seeks to the beginning when the current track position
    /// is greater than 3 seconds.
    ///
    /// Does nothing if we are not the active device.
    pub fn prev(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Prev)?)
    }

    /// Skips to the next track.
    ///
    /// Does nothing if we are not the active device.
    pub fn next(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Next)?)
    }

    /// Increases the volume by configured steps of [ConnectConfig].
    ///
    /// Does nothing if we are not the active device.
    pub fn volume_up(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::VolumeUp)?)
    }

    /// Decreases the volume by configured steps of [ConnectConfig].
    ///
    /// Does nothing if we are not the active device.
    pub fn volume_down(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::VolumeDown)?)
    }

    /// Shuffles the playback according to the value.
    ///
    /// If true shuffles/reshuffles the playback. Otherwise, does
    /// nothing (if not shuffled) or unshuffles the playback while
    /// resuming at the position of the current track.
    ///
    /// Does nothing if we are not the active device.
    pub fn shuffle(&self, shuffle: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Shuffle(shuffle))?)
    }

    /// Repeats the playback context according to the value.
    ///
    /// Does nothing if we are not the active device.
    pub fn repeat(&self, repeat: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Repeat(repeat))?)
    }

    /// Repeats the current track if true.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// Skipping to the next track disables the repeating.
    pub fn repeat_track(&self, repeat: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::RepeatTrack(repeat))?)
    }

    /// Update the volume to the given value.
    ///
    /// Does nothing if we are not the active device.
    pub fn set_volume(&self, volume: u16) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SetVolume(volume))?)
    }

    /// Updates the position to the given value.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// If value is greater than the track duration,
    /// the update is ignored.
    pub fn set_position_ms(&self, position_ms: u32) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SetPosition(position_ms))?)
    }

    /// Load a new context and replace the current.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// Does not overwrite the queue.
    pub fn load(&self, command: LoadRequest) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Load(command))?)
    }

    /// Disconnects the current device and pauses the playback according the value.
    ///
    /// Does nothing if we are not the active device.
    pub fn disconnect(&self, pause: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Disconnect { pause })?)
    }

    /// Acquires the control as active connect device.
    ///
    /// Does not [Spirc::transfer] the playback. Does nothing if we are not the active device.
    pub fn activate(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Activate)?)
    }

    /// Acquires the control as active connect device over the transfer flow.
    ///
    /// Does nothing if we are not the active device.
    pub fn transfer(&self, transfer_request: Option<TransferRequest>) -> Result<(), Error> {
        Ok(self
            .commands
            .send(SpircCommand::Transfer(transfer_request))?)
    }

    // SPOTIFYGOOD: everything below in this impl is an addition

    /// Whether the spirc task still handles commands
    ///
    /// False from the moment its loop stops taking commands: after [Spirc::shutdown] was handled,
    /// or when the loop ended by itself (for example after the connection was lost), already
    /// while the task still disconnects. Commands then fail to send.
    pub fn is_running(&self) -> bool {
        !self.commands.is_closed()
    }

    // SPOTIFYGOOD
    /// Hands the player back to the app (the app detached this spirc, which is about to shut
    /// down, and may drive the same player on its own): the task no longer touches it, not even
    /// to pause or stop it when it ends, and stops handling events (it shuts down at once).
    pub fn release_player(&self) {
        self.player_released.store(true, Ordering::Release);
    }

    /// Subscribes to snapshots of the local connect state
    ///
    /// The receiver always holds the latest snapshot (no backlog), use
    /// [watch::Receiver::changed] to wait for a change. A new snapshot is published after any
    /// handled event that changed something. `changed()` returns an error once the spirc task
    /// ended. See [ConnectSnapshot] for the position semantics.
    pub fn subscribe_state(&self) -> watch::Receiver<ConnectSnapshot> {
        self.snapshot_rx.clone()
    }

    /// Subscribes to the connect cluster (all devices of the account and the remote player state)
    ///
    /// `None` until the first cluster was received. Published from the initial device
    /// registration, from every cluster update pushed by spotify and from state update responses
    /// that contain a cluster. Remote control of other devices isn't part of [Spirc], use the
    /// spclient of the session directly.
    pub fn subscribe_cluster(&self) -> watch::Receiver<Option<Arc<Cluster>>> {
        self.cluster_rx.clone()
    }

    /// Subscribes to errors of failed commands, local and remote ones
    ///
    /// A [broadcast] channel with a small capacity: a receiver that doesn't keep up skips the
    /// oldest errors ([broadcast::error::RecvError::Lagged]). Only errors that happen after
    /// subscribing are received; the last error is also part of [ConnectSnapshot::last_error].
    pub fn subscribe_errors(&self) -> broadcast::Receiver<SpircCommandError> {
        self.errors_rx.resubscribe()
    }

    /// Adds the track or episode `uri` to the end of the user queue
    ///
    /// Fails right away (with [ErrorKind::FailedPrecondition](crate::core::error::ErrorKind))
    /// when the queued tracks, including the adds that weren't handled yet, already fill the
    /// next tracks (80 entries). The task checks again when it handles the command and reports
    /// a full queue as a command error.
    ///
    /// Does nothing if we are not the active device.
    pub fn add_to_queue(&self, uri: String) -> Result<(), Error> {
        let gauge = &self.queue_gauge;
        let in_flight = gauge.pending.fetch_add(1, Ordering::AcqRel);
        if gauge.queued.load(Ordering::Acquire) + in_flight >= SPOTIFY_MAX_NEXT_TRACKS_SIZE {
            gauge.pending.fetch_sub(1, Ordering::AcqRel);
            return Err(StateError::QueueFull(SPOTIFY_MAX_NEXT_TRACKS_SIZE).into());
        }
        if let Err(why) = self.commands.send(SpircCommand::AddToQueue(uri)) {
            gauge.pending.fetch_sub(1, Ordering::AcqRel);
            return Err(why.into());
        }
        Ok(())
    }

    /// Removes the entry with the given `uid` from the next tracks
    ///
    /// Works for queued tracks, upcoming context tracks (they are skipped until the context is
    /// loaded anew) and smart shuffle suggestions.
    ///
    /// Does nothing if we are not the active device.
    pub fn remove_from_queue(&self, uid: String) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::RemoveFromQueue(uid))?)
    }

    /// Moves the entry with the given `uid` to the index `to` of the next tracks
    ///
    /// Queued tracks always stay in front of the other next tracks, so `to` is clamped to the
    /// end of the queue. Moving a context track (or suggestion) into the queue turns it into a
    /// queued track with a new uid. Reordering context tracks among themselves fails.
    ///
    /// Does nothing if we are not the active device.
    pub fn move_queue_item(&self, uid: String, to: usize) -> Result<(), Error> {
        Ok(self
            .commands
            .send(SpircCommand::MoveQueueItem { uid, to })?)
    }

    /// Removes all queued tracks
    ///
    /// Does nothing if we are not the active device.
    pub fn clear_queue(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::ClearQueue)?)
    }

    /// Skips to the entry of the next tracks with the given `uid`
    ///
    /// Skipped context tracks move to the previous tracks. Queued tracks are kept when
    /// skipping to a context track. Fails without any change if the uid isn't in the next tracks.
    ///
    /// Does nothing if we are not the active device.
    pub fn skip_to(&self, uid: String) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SkipTo(uid))?)
    }

    /// Enables or disables smart shuffle
    ///
    /// Smart shuffle interleaves suggested tracks (fetched in the background) into the shuffled
    /// context. Enabling it also enables shuffle (with a new seed) if not already shuffled.
    /// Disabling it removes the suggestions but keeps shuffle on. Disabling shuffle or loading
    /// another context without [Options::smart_shuffle](crate::Options) also disables it.
    ///
    /// Does nothing if we are not the active device.
    pub fn smart_shuffle(&self, smart_shuffle: bool) -> Result<(), Error> {
        Ok(self
            .commands
            .send(SpircCommand::SmartShuffle(smart_shuffle))?)
    }

    /// Reports the audio output the playback currently goes to (shown by other clients)
    ///
    /// Also works while we are not the active device. Only sent if it changed.
    pub fn set_audio_output(
        &self,
        kind: AudioOutputKind,
        name: Option<String>,
    ) -> Result<(), Error> {
        Ok(self
            .commands
            .send(SpircCommand::SetAudioOutput(kind, name))?)
    }

    /// Enables or disables autoplay (continuing with similar tracks after the context ended)
    ///
    /// This sets the local `autoplay` user attribute, it isn't synced to the account. From then
    /// on, autoplay changes made elsewhere (attribute mutations and updates pushed by spotify)
    /// are ignored for this spirc. Fails if
    /// [SessionConfig::autoplay](librespot_core::SessionConfig) overrides the attribute. Also
    /// works while we are not the active device.
    pub fn set_autoplay(&self, autoplay: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SetAutoplay(autoplay))?)
    }

    /// Fetches the connect cluster (the devices of the account) from spotify again
    ///
    /// Puts the current state, the cluster in the response is published like any other (see
    /// [Spirc::subscribe_cluster]), also when it didn't change. Also works while we are not the
    /// active device.
    pub fn refresh_cluster(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::RefreshCluster)?)
    }
}

impl SpircTask {
    async fn run(mut self) {
        // simplify unwrapping of received item or parsed result
        macro_rules! unwrap {
            ( $next:expr, |$some:ident| $use_some:expr ) => {
                match $next {
                    Some($some) => $use_some,
                    None => {
                        error!("{} selected, but none received", stringify!($next));
                        break;
                    }
                }
            };
            ( $next:expr, match |$ok:ident| $use_ok:expr ) => {
                unwrap!($next, |$ok| match $ok {
                    Ok($ok) => $use_ok,
                    Err(why) => error!("could not parse {}: {}", stringify!($ok), why),
                })
            };
        }

        // SPOTIFYGOOD: the dealer start (apresolve, token, connect, handshake) has no timeout of
        // its own, and the commands weren't read meanwhile, so a shutdown waited for it (minutes
        // on a black-holed network). It is raced against shutdown and bounded; dropping the start
        // cancels it cleanly, nothing was put to spotify yet.
        let session = self.session.clone();
        let start = session.dealer().start();
        tokio::pin!(start);
        let deadline = sleep(DEALER_START_TIMEOUT);
        tokio::pin!(deadline);
        loop {
            tokio::select! {
                biased;
                result = &mut start => match result {
                    Ok(()) => break,
                    Err(why) => {
                        error!("starting dealer failed: {why}");
                        return;
                    }
                },
                _ = &mut deadline => {
                    error!("starting dealer timed out");
                    return;
                },
                cmd = async { self.commands.as_mut()?.recv().await }, if self.commands.is_some() => match cmd {
                    Some(SpircCommand::Shutdown) | None => {
                        info!("shutdown while starting the dealer");
                        self.shutdown = true;
                        if let Some(rx) = self.commands.as_mut() {
                            rx.close()
                        }
                        return;
                    }
                    Some(cmd) => self.pending_commands.push_back(cmd),
                },
            }
        }

        while !self.session.is_invalid() && !self.shutdown {
            // SPOTIFYGOOD: the app took the player back (see Spirc::release_player): no more
            // events are handled (a remote command would load into the app's playback), the
            // shutdown that follows is handled right away
            if !self.owns_player() {
                info!("the player was released, shutting down");
                self.handle_shutdown().await;
                break;
            }
            // SPOTIFYGOOD: handle the commands that arrived before the connection was established
            while self.connect_established && !self.shutdown {
                match self.pending_commands.pop_front() {
                    Some(cmd) => self.dispatch_command(cmd).await,
                    None => break,
                }
            }

            let commands = self.commands.as_mut();
            let player_events = self.player_events.as_mut();
            // SPOTIFYGOOD: see StatePuts
            let state_put = self.state_puts.in_flight.as_mut();

            // when state and volume update have a higher priority than context resolving
            // because of that the context resolving has to wait, so that the other tasks can finish
            let allow_context_resolving = !self.update_state && !self.update_volume;

            tokio::select! {
                // startup of the dealer requires a connection_id, which is retrieved at the very beginning
                connection_id_update = self.connection_id_update.next() => unwrap! {
                    connection_id_update,
                    match |connection_id| if let Err(why) = self.handle_connection_id_update(connection_id).await {
                        error!("failed handling connection id update: {why}");
                        break;
                    }
                },
                // main dealer update of any remote device updates
                cluster_update = self.connect_state_update.next() => unwrap! {
                    cluster_update,
                    match |cluster_update| if let Err(e) = self.handle_cluster_update(cluster_update).await {
                        error!("could not dispatch connect state update: {e}");
                    }
                },
                // main dealer request handling (dealer expects an answer)
                request = self.connect_state_command.next() => unwrap! {
                    request,
                    |request| if let Err(e) = self.handle_connect_state_request(request).await {
                        error!("couldn't handle connect state command: {e}");
                    }
                },
                // volume request handling is send separately (it's more like a fire forget)
                volume_update = self.connect_state_volume_update.next() => unwrap! {
                    volume_update,
                    match |volume_update| match volume_update.volume.try_into() {
                        Ok(volume) => self.set_volume(volume),
                        Err(why) => error!("can't update volume, failed to parse i32 to u16: {why}")
                    }
                },
                logout_request = self.connect_state_logout_request.next() => unwrap! {
                    logout_request,
                    |logout_request| {
                        error!("received logout request, currently not supported: {logout_request:#?}");
                        // todo: call logout handling
                    }
                },
                playlist_update = self.playlist_update.next() => unwrap! {
                    playlist_update,
                    match |playlist_update| if let Err(why) = self.handle_playlist_modification(playlist_update) {
                        error!("failed to handle playlist modification: {why}")
                    }
                },
                user_attributes_update = self.user_attributes_update.next() => unwrap! {
                    user_attributes_update,
                    match |attributes| self.handle_user_attributes_update(attributes)
                },
                user_attributes_mutation = self.user_attributes_mutation.next() => unwrap! {
                    user_attributes_mutation,
                    match |attributes| self.handle_user_attributes_mutation(attributes)
                },
                session_update = self.session_update.next() => unwrap! {
                    session_update,
                    match |session_update| self.handle_session_update(session_update)
                },
                // SPOTIFYGOOD: always polled, so that a shutdown before the connection is
                // established isn't stuck. Other commands wait in `pending_commands` until then.
                // A closed channel (all Spirc handles dropped) is handled as shutdown, it used to
                // complete this branch immediately forever (busy loop).
                cmd = async { commands?.recv().await }, if commands.is_some() => match cmd {
                    Some(cmd) if !self.connect_established && !matches!(cmd, SpircCommand::Shutdown) => {
                        self.pending_commands.push_back(cmd)
                    }
                    Some(cmd) => self.dispatch_command(cmd).await,
                    None => {
                        info!("all spirc handles were dropped, shutting down");
                        self.commands = None;
                        self.handle_shutdown().await;
                    }
                },
                event = async { player_events?.recv().await }, if player_events.is_some() => match event {
                    Some(event) => if let Err(e) = self.handle_player_event(event) {
                        error!("could not dispatch player event: {e}");
                    },
                    // SPOTIFYGOOD: the player is gone, stop polling (was a busy loop)
                    None => {
                        warn!("player event channel closed");
                        self.player_events = None;
                    }
                },
                // SPOTIFYGOOD: smart shuffle suggestions fetched in the background
                suggestions = self.suggestions_rx.recv() => if let Some(suggestions) = suggestions {
                    self.handle_suggestions(suggestions)
                },
                // SPOTIFYGOOD: the state put in flight, see StatePuts
                done = async {
                    let (put, sending) = state_put?;
                    Some((*put, sending.await))
                }, if state_put.is_some() => if let Some((put, result)) = done {
                    self.handle_state_put_done(put, result)
                },
                _ = async { sleep(UPDATE_STATE_DELAY).await }, if self.update_state => {
                    self.update_state = false;

                    // SPOTIFYGOOD: next to the loop, see StatePuts
                    self.put_state(StatePut::State);
                },
                _ = async { sleep(VOLUME_UPDATE_DELAY).await }, if self.update_volume => {
                    self.update_volume = false;

                    info!("delayed volume update for all devices: volume is now {}", self.connect_state.device_info().volume);
                    // SPOTIFYGOOD: next to the loop, see StatePuts
                    self.put_state(StatePut::Volume);

                    // for some reason the web-player does need two separate updates, so that the
                    // position of the current track is retained, other clients also send a state
                    // update before they send the volume update
                    self.put_state(StatePut::State);
                },
                // context resolver handling, the idea/reason behind it the following:
                //
                // when we request a context that has multiple pages (for example an artist)
                // resolving all pages at once can take around ~1-30sec, when we resolve
                // everything at once that would block our main loop for that time
                //
                // to circumvent this behavior, we request each context separately here and
                // finish after we received our last item of a type
                next_context = async {
                    self.context_resolver.get_next_context(|| {
                        // Sending local file URIs to this endpoint results in a Bad Request status.
                        // It's likely appropriate to filter them out anyway; Spotify's backend
                        // has no knowledge about these tracks and so can't do anything with them.
                        self.connect_state.recent_track_uris()
                            .into_iter()
                            .filter(|t| !t.starts_with("spotify:local"))
                            .collect::<Vec<_>>()
                    }).await
                }, if allow_context_resolving && self.context_resolver.has_next() => {
                    let update_state = self.handle_next_context(next_context);
                    if update_state {
                        // SPOTIFYGOOD: next to the loop, see StatePuts
                        self.put_state(StatePut::State);
                    }
                },
                else => break
            }

            // SPOTIFYGOOD: covers every path above without touching each handler
            self.maybe_fetch_suggestions();
            self.publish_snapshot();
            self.queue_gauge
                .queued
                .store(self.connect_state.queued_count(), Ordering::Release);
        }

        // SPOTIFYGOOD: no more commands are handled from here on: close the channel, so that
        // Spirc::is_running() turns false right now (it stayed true for the whole epilogue), and
        // handle a shutdown that arrived while the last handler ran (the loop ended on an invalid
        // session before reading it, so the player was never paused)
        if let Some(rx) = self.commands.as_mut() {
            rx.close();
            while let Ok(cmd) = rx.try_recv() {
                if matches!(cmd, SpircCommand::Shutdown) {
                    self.shutdown = true;
                }
            }
            if self.shutdown {
                self.handle_pause();
            }
        }
        // SPOTIFYGOOD: the player of a task that ended by itself is still playing, it is paused
        // when the task is dropped (after the epilogue), see Drop
        self.pause_on_drop = !matches!(self.play_status, SpircPlayStatus::Stopped);
        if !self.owns_player() {
            debug!("the player was released, it is left alone");
        }

        // SPOTIFYGOOD: the final snapshot is the state when the loop ended (see
        // ConnectSnapshot::ending), the disconnect below only describes the teardown (stopped,
        // inactive) and would hide what was playing when the connection was lost
        let mut final_snapshot = self.connect_state.snapshot(
            self.snapshot_status(),
            1000 * self.session.time_delta(),
            self.last_error.clone(),
        );
        final_snapshot.ending = true;

        // SPOTIFYGOOD: every network call of the epilogue is bounded, so that the task ends
        // even while offline
        if !self.shutdown && self.connect_state.is_active() {
            warn!("unexpected shutdown");
            match timeout(SHUTDOWN_TIMEOUT, self.handle_disconnect()).await {
                Ok(Ok(())) => (),
                Ok(Err(why)) => error!("error during disconnecting: {why}"),
                Err(_) => error!("timeout during disconnecting"),
            }
        }

        // this should clear the active session id, leaving an empty state
        match timeout(
            SHUTDOWN_TIMEOUT,
            self.session.spclient().delete_connect_state_request(),
        )
        .await
        {
            Ok(Ok(_)) => (),
            Ok(Err(why)) => error!("error during connect state deletion: {why}"),
            Err(_) => error!("timeout during connect state deletion"),
        }

        if timeout(SHUTDOWN_TIMEOUT, self.session.dealer().close())
            .await
            .is_err()
        {
            error!("timeout while closing the dealer")
        }

        // SPOTIFYGOOD: the final state, always sent (it differs by `ending`)
        self.snapshot_tx.send_replace(final_snapshot);
    }

    fn handle_next_context(&mut self, next_context: Result<Context, Error>) -> bool {
        let next_context = match next_context {
            Err(why) => {
                // SPOTIFYGOOD: only a context that can't be resolved is skipped for a while, a
                // transient failure (network, rate limit, server error) is retried by the next
                // request for it
                if ContextResolver::is_unavailable(&why) {
                    self.context_resolver.mark_next_unavailable();
                }
                self.context_resolver.remove_used_and_invalid();
                error!("{why}");
                return false;
            }
            Ok(ctx) => ctx,
        };

        debug!("handling next context {:?}", next_context.uri);

        // SPOTIFYGOOD: see below
        let updates_default_context = matches!(
            self.context_resolver.next_update(),
            Some(ContextType::Default)
        );

        match self
            .context_resolver
            .apply_next_context(&mut self.connect_state, next_context)
        {
            Ok(remaining) => {
                if let Some(remaining) = remaining {
                    self.context_resolver.add_list(remaining)
                }
            }
            Err(why) => {
                error!("{why}")
            }
        }

        let update_state = if self
            .context_resolver
            .try_finish(&mut self.connect_state, &mut self.transfer_state)
        {
            // SPOTIFYGOOD: the default context changed (and was possibly reshuffled), so the
            // smart shuffle suggestions are stale, fetch new ones
            if updates_default_context && self.connect_state.smart_shuffle() {
                self.connect_state.reset_suggestions();
                if let Err(why) = self.connect_state.remove_suggestions_from_next_tracks() {
                    warn!("smart shuffle: failed to remove stale suggestions: {why}")
                }
                self.suggestion_fetch.restart();
                self.handle_next_tracks_changed();
            }

            self.add_autoplay_resolving_when_required();
            true
        } else {
            false
        };

        self.context_resolver.remove_used_and_invalid();
        update_state
    }

    // todo: is the time_delta still necessary?
    fn now_ms(&self) -> i64 {
        let dur = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_else(|err| err.duration());

        dur.as_millis() as i64 + 1000 * self.session.time_delta()
    }

    // SPOTIFYGOOD: handles a command and reports its outcome (snapshot + error stream)
    async fn dispatch_command(&mut self, cmd: SpircCommand) {
        let name = cmd.name();
        let is_add = matches!(cmd, SpircCommand::AddToQueue(_));
        let result = self.handle_command(cmd).await;
        if is_add {
            self.queue_gauge
                .add_handled(self.connect_state.queued_count());
        }
        match result {
            Ok(()) => self.last_error = None,
            Err(e) => {
                debug!("could not dispatch command: {e}");
                self.report_error(name, false, &e);
            }
        }
    }

    // SPOTIFYGOOD: see Spirc::subscribe_errors and ConnectSnapshot::last_error
    fn report_error(&mut self, command: &str, remote: bool, error: &Error) {
        let message = error.error.to_string();
        self.last_error = Some(format!("{command}: {message}"));
        // fails only without any receiver
        let _ = self.errors_tx.send(SpircCommandError {
            command: command.to_string(),
            message,
            kind: error.kind,
            remote,
        });
    }

    // SPOTIFYGOOD: sets the flags first, so that the loop ends even if the network calls fail
    // (the `?` on handle_disconnect used to skip `shutdown = true` while offline)
    async fn handle_shutdown(&mut self) {
        self.shutdown = true;
        if let Some(rx) = self.commands.as_mut() {
            rx.close()
        }

        self.handle_pause();

        if self.connect_established {
            match timeout(SHUTDOWN_TIMEOUT, self.handle_disconnect()).await {
                Ok(Ok(())) => (),
                Ok(Err(why)) => error!("error during disconnecting: {why}"),
                Err(_) => error!("timeout during disconnecting"),
            }
        }
    }

    async fn handle_command(&mut self, cmd: SpircCommand) -> Result<(), Error> {
        trace!("Received SpircCommand::{cmd:?}");
        match cmd {
            SpircCommand::Shutdown => {
                trace!("Received SpircCommand::Shutdown");
                // SPOTIFYGOOD: never fails, see handle_shutdown
                self.handle_shutdown().await;
                return Ok(());
            }
            SpircCommand::Transfer(request) if !self.connect_state.is_active() => {
                let device_id = self.session.device_id();
                // SPOTIFYGOOD: bounded, the loop handles nothing else meanwhile (spclient retries
                // without a timeout and sleeps out a 429's Retry-After)
                let transfer =
                    self.session
                        .spclient()
                        .transfer(device_id, device_id, request.as_ref());
                timeout(REQUEST_TIMEOUT, transfer)
                    .await
                    .map_err(|_| Error::deadline_exceeded("the transfer request timed out"))??;
                return Ok(());
            }
            SpircCommand::Activate if !self.connect_state.is_active() => {
                trace!("Received SpircCommand::{cmd:?}");
                self.handle_activate();
                // SPOTIFYGOOD: next to the loop, see StatePuts
                self.put_state(StatePut::State);
                return Ok(());
            }
            // SPOTIFYGOOD: allowed while not active
            SpircCommand::SetAudioOutput(kind, name) => {
                self.handle_set_audio_output(kind, name);
                return Ok(());
            }
            // SPOTIFYGOOD: allowed while not active
            SpircCommand::SetAutoplay(autoplay) => self.handle_set_autoplay(autoplay)?,
            // SPOTIFYGOOD: allowed while not active, the response of the state put contains the
            // cluster. A failure isn't reported, the refresh is a background request.
            SpircCommand::RefreshCluster => {
                // SPOTIFYGOOD: next to the loop (see StatePuts), its response publishes the
                // cluster
                self.put_state(StatePut::State);
                return Ok(());
            }
            SpircCommand::Transfer(..) | SpircCommand::Activate => {
                warn!("SpircCommand::{cmd:?} will be ignored while already active")
            }
            _ if !self.connect_state.is_active() => {
                warn!("SpircCommand::{cmd:?} will be ignored while Not Active");
                // SPOTIFYGOOD: still put the state like before (next to the loop, see
                // StatePuts), but report the command as failed
                self.put_state(StatePut::State);
                return Err(SpircError::NotActive(cmd.name()).into());
            }
            SpircCommand::Disconnect { pause } => {
                if pause {
                    self.handle_pause()
                }
                return self.handle_disconnect().await;
            }
            // SPOTIFYGOOD: see play_action
            SpircCommand::Play => self.handle_play_command(false)?,
            SpircCommand::PlayPause => self.handle_play_command(true)?,
            SpircCommand::Pause => self.handle_pause(),
            SpircCommand::Prev => self.handle_prev()?,
            SpircCommand::Next => self.handle_next(None)?,
            SpircCommand::VolumeUp => self.handle_volume_up(),
            SpircCommand::VolumeDown => self.handle_volume_down(),
            SpircCommand::Shuffle(shuffle) => self.handle_shuffle(shuffle)?,
            SpircCommand::Repeat(repeat) => self.handle_repeat_context(repeat)?,
            SpircCommand::RepeatTrack(repeat) => self.handle_repeat_track(repeat),
            SpircCommand::SetPosition(position) => self.handle_seek(position),
            SpircCommand::SetVolume(volume) => self.set_volume(volume),
            SpircCommand::Load(command) => self.handle_load(command, None, None).await?,
            // SPOTIFYGOOD: local queue commands
            SpircCommand::AddToQueue(uri) => {
                self.connect_state.queue_add_uri(&uri)?;
                self.handle_next_tracks_changed();
            }
            SpircCommand::RemoveFromQueue(uid) => {
                self.connect_state.queue_remove(&uid)?;
                self.handle_next_tracks_changed();
            }
            SpircCommand::MoveQueueItem { uid, to } => {
                self.connect_state.queue_move(&uid, to)?;
                self.handle_next_tracks_changed();
            }
            SpircCommand::ClearQueue => {
                self.connect_state.queue_clear()?;
                self.handle_next_tracks_changed();
            }
            SpircCommand::SkipTo(uid) => self.handle_skip_to(&uid)?,
            // SPOTIFYGOOD: local smart shuffle
            SpircCommand::SmartShuffle(smart_shuffle) => {
                self.handle_smart_shuffle(smart_shuffle)?
            }
        };

        // SPOTIFYGOOD: the snapshot shows the command's effect (e.g. playing again) before the
        // put, which can take long on a bad network (it was only published after it). The put
        // runs next to the loop (see StatePuts): the next command doesn't wait for it.
        self.publish_snapshot();
        self.put_state(StatePut::State);
        Ok(())
    }

    fn handle_player_event(&mut self, event: PlayerEvent) -> Result<(), Error> {
        // SPOTIFYGOOD: an inactive device doesn't own the player (an app's offline playback may
        // drive the same player): never adopt its request ids or act on its events. It used to
        // stop the player at the end of every offline track. Spirc's own loads happen after it
        // became active (in this task), their events arrive later.
        if !self.connect_state.is_active() {
            return Ok(());
        }

        if let PlayerEvent::TrackChanged { audio_item } = event {
            self.connect_state.update_duration(audio_item.duration_ms);
            self.update_state = true;
            return Ok(());
        }

        // update play_request_id
        if let PlayerEvent::PlayRequestIdChanged { play_request_id } = event {
            self.play_request_id = Some(play_request_id);
            return Ok(());
        }

        let is_current_track = matches! {
            (event.get_play_request_id(), self.play_request_id),
            (Some(event_id), Some(current_id)) if event_id == current_id
        };

        // we only process events if the play_request_id matches. If it doesn't, it is
        // an event that belongs to a previous track and only arrives now due to a race
        // condition. In this case we have updated the state already and don't want to
        // mess with it.
        if !is_current_track {
            return Ok(());
        }

        match event {
            PlayerEvent::EndOfTrack { .. } => {
                let next_track = self
                    .connect_state
                    .repeat_track()
                    .then(|| self.connect_state.current_track(|t| t.uri.clone()));

                self.handle_next(next_track)?
            }
            PlayerEvent::Loading { .. } => match self.play_status {
                SpircPlayStatus::LoadingPlay { position_ms } => {
                    self.connect_state
                        .update_position(position_ms, self.now_ms());
                    trace!("==> LoadingPlay");
                }
                SpircPlayStatus::LoadingPause { position_ms } => {
                    self.connect_state
                        .update_position(position_ms, self.now_ms());
                    trace!("==> LoadingPause");
                }
                _ => {
                    self.connect_state.update_position(0, self.now_ms());
                    trace!("==> Loading");
                }
            },
            PlayerEvent::Seeked { position_ms, .. } => {
                trace!("==> Seeked");
                self.connect_state
                    .update_position(position_ms, self.now_ms())
            }
            PlayerEvent::Playing { position_ms, .. }
            | PlayerEvent::PositionCorrection { position_ms, .. } => {
                trace!("==> Playing");
                let new_nominal_start_time = self.now_ms() - position_ms as i64;
                match self.play_status {
                    SpircPlayStatus::Playing {
                        ref mut nominal_start_time,
                        ..
                    } => {
                        if (*nominal_start_time - new_nominal_start_time).abs() > 100 {
                            *nominal_start_time = new_nominal_start_time;
                            self.connect_state
                                .update_position(position_ms, self.now_ms());
                        } else {
                            return Ok(());
                        }
                    }
                    SpircPlayStatus::LoadingPlay { .. } | SpircPlayStatus::LoadingPause { .. } => {
                        self.connect_state
                            .update_position(position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Playing {
                            nominal_start_time: new_nominal_start_time,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    _ => return Ok(()),
                }
            }
            PlayerEvent::Paused {
                position_ms: new_position_ms,
                ..
            } => {
                trace!("==> Paused");
                match self.play_status {
                    SpircPlayStatus::Paused { .. } | SpircPlayStatus::Playing { .. } => {
                        self.connect_state
                            .update_position(new_position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Paused {
                            position_ms: new_position_ms,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    SpircPlayStatus::LoadingPlay { .. } | SpircPlayStatus::LoadingPause { .. } => {
                        self.connect_state
                            .update_position(new_position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Paused {
                            position_ms: new_position_ms,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    _ => return Ok(()),
                }
            }
            PlayerEvent::Stopped { .. } => {
                trace!("==> Stopped");
                match self.play_status {
                    SpircPlayStatus::Stopped => return Ok(()),
                    _ => self.play_status = SpircPlayStatus::Stopped,
                }
            }
            PlayerEvent::TimeToPreloadNextTrack { .. } => {
                self.handle_preload_next_track();
                return Ok(());
            }
            // SPOTIFYGOOD: transient failures (audio key timeout or rate limit, network) no longer
            // mark the track unavailable for the rest of the session. A failed preload only
            // preloads the track after it when the failure was specific to that track: after a
            // key denial or a transient failure the next preload most likely fails the same way,
            // and the chain walked the whole queue (one key request each) while a track played.
            // The next track is then loaded (and a failure handled like any other) when the
            // current one ends.
            PlayerEvent::Unavailable {
                track_id, reason, ..
            } => {
                let transient = matches!(
                    reason,
                    UnavailableReason::KeyTemporarilyDenied | UnavailableReason::NetworkError
                );
                let is_current =
                    self.connect_state.current_track(|t| &t.uri) == &track_id.to_uri()?;
                if !transient {
                    self.connect_state.mark_unavailable(&track_id)?;
                }
                if is_current {
                    self.handle_preload_next_track();
                    self.handle_next(None)?
                } else if !transient && reason != UnavailableReason::KeyDenied {
                    self.handle_preload_next_track();
                }
            }
            _ => return Ok(()),
        }

        self.update_state = true;
        Ok(())
    }

    async fn handle_connection_id_update(&mut self, connection_id: String) -> Result<(), Error> {
        trace!("Received connection ID update: {connection_id:?}");
        self.session.set_connection_id(&connection_id);
        // SPOTIFYGOOD: the announce below carries the whole state, a put of before (in flight or
        // waiting, see StatePuts) must not land after it
        self.state_puts.cancel();

        // SPOTIFYGOOD: bounded, see NEW_DEVICE_PUT_TIMEOUT (unbounded, a live task never
        // delivered its first cluster: the app never knew which device was active)
        let announced = timeout(
            NEW_DEVICE_PUT_TIMEOUT,
            self.connect_state.notify_new_device_appeared(&self.session),
        )
        .await;
        let cluster = match bounded_put("new device", announced) {
            Ok(res) => Cluster::parse_from_bytes(&res).ok(),
            Err(why) => {
                error!("{why:?}");
                None
            }
        }
        .ok_or(SpircError::FailedDealerSetup)?;

        // SPOTIFYGOOD: the initial cluster (device list), it was only used for the takeover
        let cluster = Arc::new(cluster);
        self.publish_cluster(cluster.clone());

        debug!(
            "successfully put connect state for {} with connection-id {connection_id}",
            self.session.device_id()
        );

        self.connect_established = true;

        let same_session = cluster.player_state.session_id == self.session.session_id()
            || cluster.player_state.session_id.is_empty();
        if !cluster.active_device_id.is_empty() || !same_session {
            info!(
                "active device is <{}> with session <{}>",
                cluster.active_device_id, cluster.player_state.session_id
            );
            return Ok(());
        } else if cluster.transfer_data.is_empty() {
            debug!("got empty transfer state, do nothing");
            return Ok(());
        } else if !self.auto_takeover {
            // SPOTIFYGOOD: gated by ConnectConfig::auto_takeover
            debug!("automatic takeover of the last session is disabled");
            return Ok(());
        } else {
            info!(
                "trying to take over control automatically, session_id: {}",
                cluster.player_state.session_id
            )
        }

        use protobuf::Message;

        match TransferState::parse_from_bytes(&cluster.transfer_data) {
            // SPOTIFYGOOD: never start audio on our own, restore the session paused
            Ok(transfer_state) => self.handle_transfer(transfer_state, true)?,
            Err(why) => error!("failed to take over control: {why}"),
        }

        Ok(())
    }

    fn handle_user_attributes_update(&mut self, update: UserAttributesUpdate) {
        trace!("Received attributes update: {update:#?}");
        let attributes: UserAttributes = update
            .pairs
            .iter()
            // SPOTIFYGOOD: keep the local autoplay value, see Spirc::set_autoplay
            .filter(|(key, _)| !(self.autoplay_override.is_some() && key.as_str() == "autoplay"))
            .map(|(key, value)| (key.to_owned(), value.to_owned()))
            .collect();
        self.session.set_user_attributes(attributes)
    }

    fn handle_user_attributes_mutation(&mut self, mutation: UserAttributesMutation) {
        for attribute in mutation.fields.iter() {
            let key = &attribute.name;

            // SPOTIFYGOOD: also for the local value of Spirc::set_autoplay. The mutation only
            // names the field, flipping the local value assumed it mirrors the account.
            if key == "autoplay"
                && (self.session.config().autoplay.is_some() || self.autoplay_override.is_some())
            {
                trace!("Autoplay override active. Ignoring mutation.");
                continue;
            }

            if let Some(old_value) = self.session.user_data().attributes.get(key) {
                let new_value = match old_value.as_ref() {
                    "0" => "1",
                    "1" => "0",
                    _ => old_value,
                };
                self.session.set_user_attribute(key, new_value);

                trace!("Received attribute mutation, {key} was {old_value} is now {new_value}");

                // SPOTIFYGOOD: emit for both values, it was only emitted when enabled
                if key == "filter-explicit-content" {
                    self.player
                        .emit_filter_explicit_content_changed_event(matches!(new_value, "1"));
                }

                if key == "autoplay" && old_value != new_value {
                    self.player
                        .emit_auto_play_changed_event(matches!(new_value, "1"));

                    self.add_autoplay_resolving_when_required()
                }
            } else {
                trace!("Received attribute mutation for {key} but key was not found!");
            }
        }
    }

    async fn handle_cluster_update(
        &mut self,
        mut cluster_update: ClusterUpdate,
    ) -> Result<(), Error> {
        let reason = cluster_update.update_reason.enum_value();

        let device_ids = cluster_update.devices_that_changed.join(", ");
        debug!(
            "cluster update: {reason:?} from {device_ids}, active device: {}",
            cluster_update.cluster.active_device_id
        );

        if let Some(cluster) = cluster_update.cluster.take() {
            // SPOTIFYGOOD: publish the device list / remote player state
            let cluster = Arc::new(cluster);
            self.publish_cluster(cluster.clone());

            let became_inactive = self.connect_state.is_active()
                && cluster.active_device_id != self.session.device_id();
            if became_inactive {
                info!("device became inactive");
                // SPOTIFYGOOD: silence the local player first (it kept playing next to the device
                // that took over until both requests below gave up, or for good when they failed),
                // and show it; the state is cleaned up after the requests, like below
                self.play_status = SpircPlayStatus::Stopped;
                self.player.stop();
                // SPOTIFYGOOD: shown inactive before the (bounded) requests, so that commands
                // follow the device that took over meanwhile (they would go to this loop, stuck
                // in the requests, and be dropped as not active afterwards)
                self.publish_inactive_snapshot();
                let res = self.handle_disconnect().await;
                self.handle_stop();
                res?;
            } else if self.connect_state.is_active() {
                // fixme: workaround fix, because of missing information why it behaves like it does
                //  background: when another device sends a connect-state update, some player's position de-syncs
                //  tried: providing session_id, playback_id, track-metadata "track_player"
                self.update_state = true;
            }
        } else if self.connect_state.is_active() {
            // SPOTIFYGOOD: also stop the local player (before the request, and even if it
            // fails), it used to keep playing while the device reported itself as inactive
            self.play_status = SpircPlayStatus::Stopped;
            self.player.stop();
            self.publish_inactive_snapshot();
            self.play_request_id = None;
            // SPOTIFYGOOD: see handle_disconnect
            self.state_puts.cancel();
            // SPOTIFYGOOD: bounded, see handle_disconnect
            let res = bounded_put(
                "inactive state",
                timeout(
                    STATE_PUT_TIMEOUT,
                    self.connect_state.became_inactive(&self.session),
                )
                .await,
            );
            self.handle_stop();
            res?;
        }

        Ok(())
    }

    async fn handle_connect_state_request(
        &mut self,
        (request, sender): RequestReply,
    ) -> Result<(), Error> {
        self.connect_state.set_last_command(request.clone());

        debug!(
            "handling: '{}' from {}",
            request.command, request.sent_by_device_id
        );

        // SPOTIFYGOOD: e.g. "endpoint: play" => "play"
        let command = request.command.to_string();
        let command = command.trim_start_matches("endpoint: ").to_string();

        let response = match self.handle_request(request).await {
            Ok(_) => {
                // SPOTIFYGOOD
                self.last_error = None;
                Reply::Success
            }
            Err(why) => {
                error!("failed to handle request: {why}");
                // SPOTIFYGOOD
                self.report_error(&command, true, &why);
                Reply::Failure
            }
        };

        sender.send(response).map_err(Into::into)
    }

    async fn handle_request(&mut self, request: Request) -> Result<(), Error> {
        use Command::*;

        match request.command {
            // errors and unknown commands
            Transfer(transfer) if transfer.data.is_none() => {
                warn!("transfer endpoint didn't contain any data to transfer");
                Err(SpircError::NoData)?
            }
            Unknown(unknown) => Err(SpircError::UnknownEndpoint(unknown))?,
            // implicit update of the connect_state
            UpdateContext(update_context) => {
                if matches!(update_context.context.uri, Some(ref uri) if uri != self.connect_state.context_uri())
                {
                    debug!(
                        "ignoring context update for <{:?}>, because it isn't the current context <{}>",
                        update_context.context.uri,
                        self.connect_state.context_uri()
                    )
                } else {
                    self.context_resolver.add(ResolveContext::from_context(
                        update_context.context,
                        ContextType::Default,
                        ContextAction::Replace,
                    ))
                }
                return Ok(());
            }
            // modification and update of the connect_state
            Transfer(transfer) => {
                // SPOTIFYGOOD: added the start_paused argument, a remote transfer is unchanged
                self.handle_transfer(transfer.data.expect("by condition checked"), false)?;
                // SPOTIFYGOOD: see handle_command
                self.publish_snapshot();
                self.put_state(StatePut::State);
                return Ok(());
            }
            Play(mut play) => {
                if !self.connect_state.is_active() {
                    self.handle_activate()
                }

                let context = match play.context.uri {
                    Some(s) => PlayContext::Uri(s),
                    None if !play.context.pages.is_empty() => PlayContext::Tracks(
                        play.context
                            .pages
                            .iter()
                            .cloned()
                            .flat_map(|p| p.tracks)
                            .flat_map(|t| t.uri)
                            .collect(),
                    ),
                    None => Err(SpircError::NoUri("context"))?,
                };

                let context_options = play
                    .options
                    .player_options_override
                    .map(Into::into)
                    .map(LoadContextOptions::Options);

                let fallback_index = play
                    .options
                    .skip_to
                    .as_ref()
                    .and_then(|s| s.track_index)
                    .map(|i| i as usize);

                self.handle_load(
                    LoadRequest {
                        context,
                        options: LoadRequestOptions {
                            start_playing: true,
                            seek_to: play.options.seek_to.unwrap_or_default(),
                            playing_track: play.options.skip_to.and_then(|s| s.try_into().ok()),
                            context_options,
                        },
                    },
                    play.context.pages.pop(),
                    fallback_index,
                )
                .await?;

                self.connect_state.set_origin(play.play_origin)
            }
            Pause(_) => self.handle_pause(),
            SeekTo(seek_to) => {
                // for some reason the position is stored in value, not in position
                trace!("seek to {seek_to:?}");
                self.handle_seek(seek_to.value)
            }
            SetShufflingContext(shuffle) => self.handle_shuffle(shuffle.value)?,
            SetRepeatingContext(repeat_context) => {
                self.handle_repeat_context(repeat_context.value)?
            }
            SetRepeatingTrack(repeat_track) => self.handle_repeat_track(repeat_track.value),
            // SPOTIFYGOOD: preload the new next track after queue changes
            AddToQueue(add_to_queue) => {
                // SPOTIFYGOOD: fails (instead of dropping the track) when the queue is full
                self.connect_state.add_to_queue(add_to_queue.track, true)?;
                self.handle_next_tracks_changed();
            }
            SetQueue(set_queue) => {
                self.connect_state.handle_set_queue(set_queue);
                self.handle_next_tracks_changed();
            }
            SetOptions(set_options) => {
                if let Some(repeat_context) = set_options.repeating_context {
                    self.handle_repeat_context(repeat_context)?
                }

                if let Some(repeat_track) = set_options.repeating_track {
                    self.handle_repeat_track(repeat_track)
                }

                let shuffle = set_options.shuffling_context;
                if let Some(shuffle) = shuffle {
                    self.handle_shuffle(shuffle)?;
                }
            }
            SkipNext(skip_next) => self.handle_next(skip_next.track.map(|t| t.uri))?,
            SkipPrev(_) => self.handle_prev()?,
            // SPOTIFYGOOD: shared with the local play (see play_action), fails without a track
            Resume(_) => self.handle_play_command(false)?,
        }

        self.update_state = true;
        Ok(())
    }

    // SPOTIFYGOOD: `start_paused` restores the transferred playback paused
    fn handle_transfer(
        &mut self,
        mut transfer: TransferState,
        start_paused: bool,
    ) -> Result<(), Error> {
        let mut ctx_uri = match transfer.current_session.context.uri {
            None => Err(SpircError::NoUri("transfer context"))?,
            // can apparently happen when a state is transferred and was started with "uris" via the api
            Some(ref uri) if uri == "-" || uri.is_empty() => None,
            Some(ref uri) => Some(uri.clone()),
        };

        self.connect_state.reset_context(
            ctx_uri
                .as_deref()
                .map(ResetContext::WhenDifferent)
                .unwrap_or(ResetContext::Completely),
        );

        match self.connect_state.current_track_from_transfer(&transfer) {
            Err(why) => warn!("didn't find initial track: {why}"),
            Ok(track) => {
                debug!("found initial track <{}>", track.uri);
                self.connect_state.set_track(track)
            }
        };

        let autoplay = self.connect_state.current_track(|t| t.is_autoplay());
        if autoplay {
            ctx_uri = ctx_uri.map(|c| c.replace("station:", ""));
        }

        let fallback = self.connect_state.current_track(|t| &t.uri).clone();
        let load_from_context_uri = ctx_uri.is_some();

        match ctx_uri {
            Some(ref uri) => {
                self.context_resolver.add(ResolveContext::from_uri(
                    uri.clone(),
                    &fallback,
                    ContextType::Default,
                    ContextAction::Replace,
                ));
            }
            None => {
                let all_tracks = transfer
                    .current_session
                    .context
                    .pages
                    .iter()
                    .cloned()
                    .flat_map(|p| p.tracks)
                    .collect::<Vec<_>>();

                if !all_tracks.is_empty() {
                    self.load_context_from_tracks(all_tracks)?;
                } else {
                    warn!(
                        "tried to transfer with an invalid state, using fallback as ctx_uri ({fallback})"
                    );
                    ctx_uri = Some(fallback.clone())
                }
            }
        };

        self.handle_activate();

        let timestamp = self.now_ms();
        let state = &mut self.connect_state;
        state.handle_initial_transfer(&mut transfer, ctx_uri.clone());

        // adjust active context, so resolve knows for which context it should set up the state
        state.active_context = if autoplay {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };

        // update position if the track continued playing
        let transfer_timestamp = transfer.playback.timestamp.unwrap_or_default();
        let position = match transfer.playback.position_as_of_timestamp {
            Some(position) if transfer.playback.is_paused.unwrap_or_default() => position.into(),
            // update position if the track continued playing
            Some(position) if position > 0 => {
                let time_since_position_update = timestamp - transfer_timestamp;
                i64::from(position) + time_since_position_update
            }
            _ => 0,
        };

        // SPOTIFYGOOD: start_paused
        let is_playing = !start_paused && !transfer.playback.is_paused();

        if self.connect_state.current_track(|t| t.is_autoplay()) || autoplay {
            if let Some(ctx_uri) = ctx_uri {
                debug!("currently in autoplay context, async resolving autoplay for {ctx_uri}");
                self.context_resolver.add(ResolveContext::from_uri(
                    ctx_uri,
                    fallback,
                    ContextType::Autoplay,
                    ContextAction::Replace,
                ))
            } else {
                warn!("couldn't resolve autoplay context without a context uri");
            }
        }

        if load_from_context_uri {
            self.transfer_state = Some(transfer);
        } else {
            match self.connect_state.get_context(ContextType::Default) {
                Err(why) => {
                    warn!("continuing transfer in an unknown state. {why}");
                    self.transfer_state = Some(transfer);
                }
                Ok(ctx) => {
                    let idx = ConnectState::find_index_in_context(ctx, |pt| {
                        self.connect_state.current_track(|t| pt.uri == t.uri)
                    })?;
                    self.connect_state.reset_playback_to_position(Some(idx))?;
                }
            }
        }

        self.load_track(is_playing, position.try_into()?)
    }

    async fn handle_disconnect(&mut self) -> Result<(), Error> {
        self.context_resolver.clear();
        // SPOTIFYGOOD: a put still in flight (or waiting) would announce the active state again
        // after the inactive one below
        self.state_puts.cancel();

        self.play_status = SpircPlayStatus::Stopped {};
        self.connect_state
            .update_position_in_relation(self.now_ms());
        // SPOTIFYGOOD: become inactive (locally) even if the state update fails, it used to
        // return early and the device kept reporting itself as active. Each request is bounded
        // on its own (spclient retries without a timeout and waits out a 429's Retry-After);
        // became_inactive resets the local state before its request.
        let notified = bounded_put("state", timeout(STATE_PUT_TIMEOUT, self.notify()).await);
        // SPOTIFYGOOD: the player isn't ours anymore, a stale id must never match its events
        self.play_request_id = None;

        let inactive = bounded_put(
            "inactive state",
            timeout(
                STATE_PUT_TIMEOUT,
                self.connect_state.became_inactive(&self.session),
            )
            .await,
        );

        self.player
            .emit_session_disconnected_event(self.session.connection_id(), self.session.username());

        notified?;
        inactive?;
        Ok(())
    }

    fn handle_stop(&mut self) {
        // SPOTIFYGOOD: see Spirc::release_player (a transfer away that ended after the release)
        if self.owns_player() {
            self.player.stop();
        }
        self.connect_state.update_position(0, self.now_ms());
        self.connect_state.clear_next_tracks();

        if let Err(why) = self.connect_state.reset_playback_to_position(None) {
            warn!("failed filling up next_track during stopping: {why}")
        }
    }

    fn handle_activate(&mut self) {
        self.connect_state.set_active(true);
        self.player
            .emit_session_connected_event(self.session.connection_id(), self.session.username());
        self.player.emit_session_client_changed_event(
            self.session.client_id(),
            self.session.client_name(),
            self.session.client_brand_name(),
            self.session.client_model_name(),
        );

        self.player
            .emit_volume_changed_event(self.connect_state.device_info().volume as u16);

        // SPOTIFYGOOD: Spirc::set_autoplay
        self.player.emit_auto_play_changed_event(self.autoplay());

        self.player
            .emit_filter_explicit_content_changed_event(self.session.filter_explicit_content());

        self.player
            .emit_shuffle_changed_event(self.connect_state.shuffling_context());

        self.player.emit_repeat_changed_event(
            self.connect_state.repeat_context(),
            self.connect_state.repeat_track(),
        );
    }

    async fn handle_load(
        &mut self,
        cmd: LoadRequest,
        page: Option<ContextPage>,
        fallback_index: Option<usize>,
    ) -> Result<(), Error> {
        let reset_completely =
            self.connect_state
                .reset_context(if let PlayContext::Uri(ref uri) = cmd.context {
                    ResetContext::WhenDifferent(uri)
                } else {
                    ResetContext::Completely
                });
        // SPOTIFYGOOD: the resolves still queued for the previous context (e.g. the album pages
        // of an artist, an autoplay or update resolve) and a pending transfer belong to it. Only
        // a load of another uri cleared them: after a track list load they were applied to the
        // new list (the artist's albums appended to it), and they deferred its shuffle.
        if reset_completely {
            self.context_resolver.clear();
            self.transfer_state = None;
        }

        self.connect_state.reset_options();

        // SPOTIFYGOOD: a load starts fresh: no smart shuffle (unless requested, see below) and
        // tracks removed from the next tracks of a previous playback are available again
        self.connect_state.clear_smart_shuffle();
        self.connect_state.clear_skipped_uids();
        self.suggestion_fetch.restart();
        let smart_shuffle = matches!(
            cmd.context_options,
            Some(LoadContextOptions::Options(ref o)) if o.smart_shuffle
        );

        let autoplay = matches!(cmd.context_options, Some(LoadContextOptions::Autoplay));
        match cmd.context {
            PlayContext::Uri(uri) => {
                self.load_context_from_uri(uri, page.as_ref(), autoplay)
                    .await?
            }
            PlayContext::Tracks(tracks) => self.load_context_from_tracks(tracks)?,
        }

        let cmd_options = cmd.options;

        self.connect_state.set_active_context(ContextType::Default);

        // for play commands with skip by uid, the context of the command contains
        // tracks with uri and uid, so we merge the new context with the resolved/existing context
        self.connect_state.merge_context(page);

        // load here, so that we clear the queue only after we definitely retrieved a new context
        self.connect_state.clear_next_tracks();
        self.connect_state.clear_restrictions();

        debug!("play track <{:?}>", cmd_options.playing_track);

        let index = match cmd_options.playing_track {
            None => None,
            Some(ref playing_track) => Some(match playing_track {
                PlayingTrack::Index(i) => Ok(*i as usize),
                PlayingTrack::Uri(uri) => {
                    let ctx = self.connect_state.get_context(ContextType::Default)?;
                    ConnectState::find_index_in_context(ctx, |t| &t.uri == uri)
                }
                PlayingTrack::Uid(uid) => {
                    let ctx = self.connect_state.get_context(ContextType::Default)?;
                    ConnectState::find_index_in_context(ctx, |t| &t.uid == uid)
                }
            }),
        }
        .map(|i| {
            i.unwrap_or_else(|why| {
                warn!(
                    "Failed to resolve index by {:?}, using fallback index: {:?} (Error: {why})",
                    cmd_options.playing_track, fallback_index
                );
                fallback_index.unwrap_or_default()
            })
        });

        if let Some(LoadContextOptions::Options(ref options)) = cmd_options.context_options {
            debug!(
                "loading with shuffle: <{}>, repeat track: <{}> context: <{}>",
                options.shuffle, options.repeat, options.repeat_track
            );

            // SPOTIFYGOOD: smart shuffle implies shuffle
            self.connect_state
                .set_shuffle(options.shuffle || options.smart_shuffle);
            self.connect_state.set_repeat_context(options.repeat);
            self.connect_state.set_repeat_track(options.repeat_track);
        }

        // SPOTIFYGOOD: smart shuffle implies shuffle
        if matches!(cmd_options.context_options, Some(LoadContextOptions::Options(ref o)) if o.shuffle || o.smart_shuffle)
        {
            if let Some(index) = index {
                self.connect_state.set_current_track(index)?;
            } else {
                self.connect_state.set_current_track_random()?;
            }

            if self.context_resolver.has_next() {
                self.connect_state.update_queue_revision()
            } else {
                self.connect_state.shuffle_new()?;
                self.add_autoplay_resolving_when_required();
            }
        } else {
            self.connect_state
                .set_current_track(index.unwrap_or_default())?;
            self.connect_state.reset_playback_to_position(index)?;
            self.add_autoplay_resolving_when_required();
        }

        if self.connect_state.current_track(MessageField::is_some) {
            self.load_track(cmd_options.start_playing, cmd_options.seek_to)?;
        } else {
            info!("No active track, stopping");
            self.handle_stop()
        }

        // SPOTIFYGOOD: the suggestions are fetched by the main loop
        if smart_shuffle {
            self.connect_state.set_smart_shuffle(true);
        }

        Ok(())
    }

    async fn load_context_from_uri(
        &mut self,
        context_uri: String,
        page: Option<&ContextPage>,
        autoplay: bool,
    ) -> Result<(), Error> {
        if !self.connect_state.is_active() {
            self.handle_activate();
        }

        let update_context = if autoplay {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };

        self.connect_state.set_active_context(update_context);

        let fallback = match page {
            // check that the uri is valid or the page has a valid uri that can be used
            Some(page) => match ConnectState::find_valid_uri(Some(&context_uri), Some(page)) {
                Some(ctx_uri) => ctx_uri,
                None => return Err(SpircError::InvalidUri(context_uri).into()),
            },
            // when there is no page, the uri should be valid
            None => &context_uri,
        };

        let current_context_uri = self.connect_state.context_uri();

        if current_context_uri == &context_uri && fallback == context_uri {
            debug!("context <{current_context_uri}> didn't change, no resolving required")
        } else {
            debug!("resolving context for load command");
            self.context_resolver.clear();
            // SPOTIFYGOOD: a pending transfer was finished against the loaded context
            self.transfer_state = None;
            let resolve = ResolveContext::from_uri(
                &context_uri,
                fallback,
                update_context,
                ContextAction::Replace,
            );
            // SPOTIFYGOOD: an explicit load always asks again, a failure of the same context a
            // moment ago (e.g. a network hiccup) refused it without any request for a minute
            self.context_resolver.forget_unavailable(&resolve);
            self.context_resolver.add(resolve);
            let context = self.context_resolver.get_next_context(Vec::new).await;
            self.handle_next_context(context);
        }

        Ok(())
    }

    fn load_context_from_tracks(&mut self, tracks: impl Into<ContextPage>) -> Result<(), Error> {
        const WEB_API_URI: &str = "spotify:web-api";
        let ctx = Context {
            // by providing values for uri/url the player in the official client's isn't frozen
            uri: Some(WEB_API_URI.into()),
            url: Some(format!("context://{WEB_API_URI}")),
            pages: vec![tracks.into()],
            ..Default::default()
        };

        let _ = self
            .connect_state
            .update_context(ctx, ContextType::Default)?;

        Ok(())
    }

    fn handle_play(&mut self) {
        match self.play_status {
            SpircPlayStatus::Paused {
                position_ms,
                preloading_of_next_track_triggered,
            } => {
                // SPOTIFYGOOD: see Spirc::release_player (a handler that resumes after the
                // release must not drive the app's playback)
                if self.owns_player() {
                    self.player.play();
                }
                self.connect_state
                    .update_position(position_ms, self.now_ms());
                self.play_status = SpircPlayStatus::Playing {
                    nominal_start_time: self.now_ms() - position_ms as i64,
                    preloading_of_next_track_triggered,
                };
            }
            SpircPlayStatus::LoadingPause { position_ms } => {
                if self.owns_player() {
                    self.player.play();
                }
                self.play_status = SpircPlayStatus::LoadingPlay { position_ms };
            }
            _ => return,
        }

        // Synchronize the volume from the mixer. This is useful on
        // systems that can switch sources from and back to librespot.
        let current_volume = self.mixer.volume();
        self.set_volume(current_volume);
    }

    // SPOTIFYGOOD: replaces handle_play_pause, see play_action
    /// A local play (`toggle`: play/pause) or a remote resume
    fn handle_play_command(&mut self, toggle: bool) -> Result<(), Error> {
        let has_track = self.connect_state.current_track(MessageField::is_some);
        match play_action(&self.play_status, toggle, has_track) {
            PlayAction::Play => self.handle_play(),
            PlayAction::Pause => self.handle_pause(),
            PlayAction::Restart => self.load_track(true, 0)?,
            PlayAction::NothingToPlay => Err(SpircError::NothingToPlay)?,
            PlayAction::Nothing => (),
        }
        Ok(())
    }

    fn handle_pause(&mut self) {
        match self.play_status {
            SpircPlayStatus::Playing {
                nominal_start_time,
                preloading_of_next_track_triggered,
            } => {
                // SPOTIFYGOOD: see Spirc::release_player
                if self.owns_player() {
                    self.player.pause();
                }
                let position_ms = (self.now_ms() - nominal_start_time) as u32;
                self.connect_state
                    .update_position(position_ms, self.now_ms());
                self.play_status = SpircPlayStatus::Paused {
                    position_ms,
                    preloading_of_next_track_triggered,
                };
            }
            SpircPlayStatus::LoadingPlay { position_ms } => {
                if self.owns_player() {
                    self.player.pause();
                }
                self.play_status = SpircPlayStatus::LoadingPause { position_ms };
            }
            _ => (),
        }
    }

    fn handle_seek(&mut self, position_ms: u32) {
        let duration = self.connect_state.player().duration;
        if i64::from(position_ms) > duration {
            warn!("tried to seek to {position_ms}ms of {duration}ms");
            return;
        }

        self.connect_state
            .update_position(position_ms, self.now_ms());
        // SPOTIFYGOOD: see Spirc::release_player
        if self.owns_player() {
            self.player.seek(position_ms);
        }
        let now = self.now_ms();
        match self.play_status {
            SpircPlayStatus::Stopped => (),
            SpircPlayStatus::LoadingPause {
                position_ms: ref mut position,
            }
            | SpircPlayStatus::LoadingPlay {
                position_ms: ref mut position,
            }
            | SpircPlayStatus::Paused {
                position_ms: ref mut position,
                ..
            } => *position = position_ms,
            SpircPlayStatus::Playing {
                ref mut nominal_start_time,
                ..
            } => *nominal_start_time = now - position_ms as i64,
        };
    }

    fn handle_shuffle(&mut self, shuffle: bool) -> Result<(), Error> {
        self.player.emit_shuffle_changed_event(shuffle);
        // SPOTIFYGOOD: unshuffling also disables smart shuffle (in the state), a reshuffle
        // starts with new suggestions; outstanding suggestions are invalid in both cases
        if self.connect_state.smart_shuffle() {
            self.connect_state.reset_suggestions();
            self.suggestion_fetch.restart();
        }
        self.connect_state.handle_shuffle(shuffle)?;
        // SPOTIFYGOOD: the next track changed
        self.handle_next_tracks_changed();
        Ok(())
    }

    // SPOTIFYGOOD: see Spirc::smart_shuffle
    fn handle_smart_shuffle(&mut self, smart_shuffle: bool) -> Result<(), Error> {
        if smart_shuffle == self.connect_state.smart_shuffle() {
            return Ok(());
        }

        let was_shuffled = self.connect_state.shuffling_context();
        self.connect_state.handle_smart_shuffle(smart_shuffle)?;
        if !was_shuffled && self.connect_state.shuffling_context() {
            self.player.emit_shuffle_changed_event(true);
        }

        self.suggestion_fetch.restart();
        self.handle_next_tracks_changed();
        Ok(())
    }

    // SPOTIFYGOOD: see Spirc::skip_to
    fn handle_skip_to(&mut self, uid: &str) -> Result<(), Error> {
        let continue_playing = self.connect_state.is_playing();
        self.connect_state.skip_to_uid(uid)?;
        self.add_autoplay_resolving_when_required();
        self.load_track(continue_playing, 0)
    }

    // SPOTIFYGOOD: preloading only happened on TimeToPreloadNextTrack, so after a change of the
    // next tracks the player would continue with the previously preloaded (wrong) track
    fn handle_next_tracks_changed(&mut self) {
        let preloading_triggered = matches!(
            self.play_status,
            SpircPlayStatus::Playing {
                preloading_of_next_track_triggered: true,
                ..
            } | SpircPlayStatus::Paused {
                preloading_of_next_track_triggered: true,
                ..
            }
        );

        if preloading_triggered {
            self.handle_preload_next_track();
        }
    }

    // SPOTIFYGOOD: see Spirc::set_audio_output
    // SPOTIFYGOOD: the put runs next to the loop (see StatePuts), a failure is only logged; the
    // state holds the new output, so every later put carries it
    fn handle_set_audio_output(&mut self, kind: AudioOutputKind, name: Option<String>) {
        if self.connect_state.set_audio_output(kind, name) {
            self.put_state(StatePut::AudioOutput);
        }
    }

    // SPOTIFYGOOD: see Spirc::set_autoplay, the local value also survives a replacement of all
    // user attributes (product info)
    fn autoplay(&self) -> bool {
        self.autoplay_override
            .unwrap_or_else(|| self.session.autoplay())
    }

    // SPOTIFYGOOD: see Spirc::set_autoplay
    fn handle_set_autoplay(&mut self, autoplay: bool) -> Result<(), Error> {
        if self.session.config().autoplay.is_some() {
            Err(SpircError::AutoplayOverridden)?
        }

        let old_value = self.autoplay();
        self.autoplay_override = Some(autoplay);
        self.session
            .set_user_attribute("autoplay", if autoplay { "1" } else { "0" });

        if old_value == autoplay {
            return Ok(());
        }

        self.player.emit_auto_play_changed_event(autoplay);

        if !self.connect_state.is_active() {
            return Ok(());
        }

        if autoplay {
            self.add_autoplay_resolving_when_required()
        } else {
            self.context_resolver.remove_autoplay();
            self.connect_state.remove_autoplay_context();
            self.handle_next_tracks_changed();
        }

        Ok(())
    }

    // SPOTIFYGOOD: smart shuffle, requests new suggestions when (almost) none are left in the
    // next tracks. Runs off the loop, at most one request at a time, with backoff.
    fn maybe_fetch_suggestions(&mut self) {
        if !self.connect_state.is_active()
            || self.suggestion_fetch.in_flight
            || matches!(self.suggestion_fetch.retry_at, Some(at) if Instant::now() < at)
            || !self.connect_state.needs_suggestions()
        {
            return;
        }

        if !self.connect_state.prune_suggestions() {
            debug!("smart shuffle: enough suggestions are waiting");
            self.suggestion_fetch.retry_at = Some(Instant::now() + SUGGESTION_MIN_INTERVAL);
            return;
        }

        let recent_track_uri = self.connect_state.sample_context_uris();
        if recent_track_uri.is_empty() {
            debug!("smart shuffle: no tracks in the context to base suggestions on");
            self.suggestion_fetch.retry_at = Some(Instant::now() + SUGGESTION_EMPTY_BACKOFF);
            return;
        }

        let context_uri = self.connect_state.context_uri().clone();
        let resolve_uri = match ConnectState::valid_resolve_uri(&context_uri) {
            Some(uri) if !uri.starts_with("spotify:web-api") => uri.to_string(),
            _ => recent_track_uri[0].clone(),
        };

        debug!("smart shuffle: requesting suggestions for <{resolve_uri}>");

        let request = AutoplayContextRequest {
            context_uri: Some(resolve_uri),
            recent_track_uri,
            ..Default::default()
        };

        self.suggestion_fetch.in_flight = true;
        let generation = self.suggestion_fetch.generation;
        let session = self.session.clone();
        let tx = self.suggestions_tx.clone();

        // the handle aborts it when the results become invalid and when the task ends (it holds
        // a strong session, and spclient retries without an overall timeout)
        let task = tokio::spawn(async move {
            let result = if session.is_invalid() {
                Err(Error::unavailable("the session is invalid"))
            } else {
                timeout(
                    SUGGESTION_TIMEOUT,
                    session.spclient().get_autoplay_context(&request),
                )
                .await
                .unwrap_or_else(|_| {
                    Err(Error::deadline_exceeded(
                        "smart shuffle suggestions timed out",
                    ))
                })
            };
            drop(session);
            // fails only if the spirc task already ended
            let _ = tx.send(SuggestionResponse {
                generation,
                context_uri,
                result,
            });
        });
        self.suggestion_fetch.task = Some(task.abort_handle());
    }

    // SPOTIFYGOOD: smart shuffle, applies fetched suggestions
    fn handle_suggestions(&mut self, response: SuggestionResponse) {
        // SPOTIFYGOOD: a result of an earlier generation (sent before its task was aborted)
        // doesn't end the current fetch
        if response.generation != self.suggestion_fetch.generation {
            debug!("smart shuffle: discarding outdated suggestions");
            return;
        }
        self.suggestion_fetch.in_flight = false;
        self.suggestion_fetch.task = None;

        if &response.context_uri != self.connect_state.context_uri()
            || !self.connect_state.smart_shuffle()
        {
            debug!("smart shuffle: discarding outdated suggestions");
            return;
        }

        let result = response
            .result
            .and_then(|ctx| self.connect_state.add_suggestions(ctx));

        let fetch = &mut self.suggestion_fetch;
        match result {
            Err(why) => {
                fetch.failures = fetch.failures.saturating_add(1);
                let backoff = SUGGESTION_ERROR_BACKOFF
                    .saturating_mul(2u32.saturating_pow(fetch.failures - 1))
                    .min(SUGGESTION_MAX_BACKOFF);
                warn!("smart shuffle: fetching suggestions failed, retry in {backoff:?}: {why}");
                fetch.retry_at = Some(Instant::now() + backoff);
            }
            Ok(0) => {
                debug!("smart shuffle: no new suggestions");
                fetch.failures = 0;
                fetch.retry_at = Some(Instant::now() + SUGGESTION_EMPTY_BACKOFF);
            }
            Ok(added) => {
                debug!("smart shuffle: added {added} suggestions");
                fetch.failures = 0;
                fetch.retry_at = Some(Instant::now() + SUGGESTION_MIN_INTERVAL);
                self.handle_next_tracks_changed();
                self.update_state = true;
            }
        }
    }

    // SPOTIFYGOOD: see Spirc::subscribe_state
    fn snapshot_status(&self) -> SnapshotPlayStatus {
        match self.play_status {
            SpircPlayStatus::Stopped => SnapshotPlayStatus::Stopped,
            SpircPlayStatus::LoadingPlay { .. } => SnapshotPlayStatus::LoadingPlay,
            SpircPlayStatus::LoadingPause { .. } => SnapshotPlayStatus::LoadingPause,
            SpircPlayStatus::Playing { .. } => SnapshotPlayStatus::Playing,
            SpircPlayStatus::Paused { .. } => SnapshotPlayStatus::Paused,
        }
    }

    // SPOTIFYGOOD: see the transfer away in handle_cluster_update: the snapshot of a device that
    // is becoming inactive (stopped), ahead of its state. The next publish_snapshot compares
    // against it afresh.
    fn publish_inactive_snapshot(&mut self) {
        let mut snapshot = self.connect_state.snapshot(
            SnapshotPlayStatus::Stopped,
            1000 * self.session.time_delta(),
            self.last_error.clone(),
        );
        snapshot.is_active = false;
        self.snapshot_fingerprint = None;
        self.snapshot_tx.send_if_modified(|current| {
            if *current != snapshot {
                *current = snapshot;
                true
            } else {
                false
            }
        });
    }

    // SPOTIFYGOOD: publishes the state, skipped cheaply if nothing visible changed
    fn publish_snapshot(&mut self) {
        let status = self.snapshot_status();
        let time_delta_ms = 1000 * self.session.time_delta();

        let fingerprint = self.connect_state.snapshot_fingerprint(
            matches!(status, SnapshotPlayStatus::Playing),
            (status, time_delta_ms, &self.last_error),
        );
        if self.snapshot_fingerprint == Some(fingerprint) {
            return;
        }
        self.snapshot_fingerprint = Some(fingerprint);

        let snapshot = self
            .connect_state
            .snapshot(status, time_delta_ms, self.last_error.clone());

        self.snapshot_tx.send_if_modified(|current| {
            if *current != snapshot {
                *current = snapshot;
                true
            } else {
                false
            }
        });
    }

    // SPOTIFYGOOD: see Spirc::subscribe_cluster
    fn publish_cluster(&self, cluster: Arc<Cluster>) {
        self.cluster_tx.send_replace(Some(cluster));
    }

    // SPOTIFYGOOD: state update responses may contain the cluster
    fn publish_cluster_from_response(&self, response: &[u8]) {
        use protobuf::Message;

        match Cluster::parse_from_bytes(response) {
            Ok(cluster) if !cluster.device.is_empty() => self.publish_cluster(Arc::new(cluster)),
            _ => trace!("state update response didn't contain a cluster"),
        }
    }

    fn handle_repeat_context(&mut self, repeat: bool) -> Result<(), Error> {
        self.player
            .emit_repeat_changed_event(repeat, self.connect_state.repeat_track());
        self.connect_state.handle_set_repeat_context(repeat)
    }

    fn handle_repeat_track(&mut self, repeat: bool) {
        self.player
            .emit_repeat_changed_event(self.connect_state.repeat_context(), repeat);
        self.connect_state.set_repeat_track(repeat);
    }

    fn handle_preload_next_track(&mut self) {
        // Requests the player thread to preload the next track
        match self.play_status {
            SpircPlayStatus::Paused {
                ref mut preloading_of_next_track_triggered,
                ..
            }
            | SpircPlayStatus::Playing {
                ref mut preloading_of_next_track_triggered,
                ..
            } => {
                *preloading_of_next_track_triggered = true;
            }
            _ => (),
        }

        if let Some(track_id) = self.connect_state.preview_next_track() {
            // SPOTIFYGOOD: see Spirc::release_player
            if self.owns_player() {
                self.player.preload(track_id);
            }
        }
    }

    // SPOTIFYGOOD: handle_unavailable (mark + preload the next track) is inlined into the
    // PlayerEvent::Unavailable handling, which now depends on the reason

    fn add_autoplay_resolving_when_required(&mut self) {
        let require_load_new = !self
            .connect_state
            .has_next_tracks(Some(CONTEXT_FETCH_THRESHOLD))
            // SPOTIFYGOOD: Spirc::set_autoplay
            && self.autoplay()
            && !self.connect_state.context_uri().is_empty();

        if !require_load_new {
            return;
        }

        let current_context = self.connect_state.context_uri();
        let fallback = self.connect_state.current_track(|t| &t.uri);

        let has_tracks = self
            .connect_state
            .get_context(ContextType::Autoplay)
            .map(|c| !c.tracks.is_empty())
            .unwrap_or_default();

        let resolve = ResolveContext::from_uri(
            current_context,
            fallback,
            ContextType::Autoplay,
            if has_tracks {
                ContextAction::Append
            } else {
                ContextAction::Replace
            },
        );

        self.context_resolver.add(resolve);
    }

    fn handle_next(&mut self, track_uri: Option<String>) -> Result<(), Error> {
        let continue_playing = self.connect_state.is_playing();

        let current_uri = self.connect_state.current_track(|t| &t.uri);
        let mut has_next_track =
            matches!(track_uri, Some(ref track_uri) if current_uri == track_uri);

        // SPOTIFYGOOD: verify the requested track is in the next tracks, otherwise the loop below
        // consumed all next tracks (or never ended) and playback stopped. Fall back to a
        // plain next instead.
        let track_uri = match track_uri {
            Some(uri) if !has_next_track && !self.connect_state.has_playable_next_track(&uri) => {
                warn!(
                    "skip to <{uri}> requested, but it isn't in the next tracks, skipping to next"
                );
                None
            }
            other => other,
        };

        if !has_next_track {
            has_next_track = loop {
                let index = self.connect_state.next_track()?;

                // SPOTIFYGOOD: no next track left
                if index.is_none() {
                    break false;
                }

                let current_uri = self.connect_state.current_track(|t| &t.uri);
                if matches!(track_uri, Some(ref track_uri) if current_uri != track_uri) {
                    continue;
                } else {
                    break index.is_some();
                }
            };
        };

        if has_next_track {
            self.add_autoplay_resolving_when_required();
            self.load_track(continue_playing, 0)
        } else {
            info!("Not playing next track because there are no more tracks left in queue.");
            self.handle_stop();
            Ok(())
        }
    }

    fn handle_prev(&mut self) -> Result<(), Error> {
        // Previous behaves differently based on the position
        // Under 3s it goes to the previous song (starts playing)
        // Over 3s it seeks to zero (retains previous play status)
        if self.position() < 3000 {
            let repeat_context = self.connect_state.repeat_context();
            match self.connect_state.prev_track()? {
                // SPOTIFYGOOD: also load the track the state was reset to
                None if repeat_context => {
                    self.connect_state.reset_playback_to_position(None)?;
                    self.load_track(self.connect_state.is_playing(), 0)?
                }
                // SPOTIFYGOOD: without a previous track, previous restarts the current one
                // (prev_track no longer touches the state then); it used to stop playback.
                None => self.handle_seek(0),
                Some(_) => self.load_track(self.connect_state.is_playing(), 0)?,
            }
        } else {
            self.handle_seek(0);
        }

        Ok(())
    }

    fn handle_volume_up(&mut self) {
        let volume = (self.connect_state.device_info().volume as u16)
            .saturating_add(self.connect_state.volume_step_size);

        self.set_volume(volume);
    }

    fn handle_volume_down(&mut self) {
        let volume = (self.connect_state.device_info().volume as u16)
            .saturating_sub(self.connect_state.volume_step_size);

        self.set_volume(volume);
    }

    fn handle_playlist_modification(
        &mut self,
        playlist_modification_info: PlaylistModificationInfo,
    ) -> Result<(), Error> {
        let uri = playlist_modification_info
            .uri
            .ok_or(SpircError::NoUri("playlist modification"))?;
        let uri = String::from_utf8(uri)?;

        if self.connect_state.context_uri() != &uri {
            debug!(
                "ignoring playlist modification update for playlist <{uri}>, because it isn't the current context"
            );
            return Ok(());
        }

        debug!("playlist modification for current context: {uri}");
        self.context_resolver.add(ResolveContext::from_uri(
            uri,
            self.connect_state.current_track(|t| &t.uri),
            ContextType::Default,
            ContextAction::Replace,
        ));

        Ok(())
    }

    fn handle_session_update(&mut self, session_update: FallbackWrapper<SessionUpdate>) {
        // we know that this enum value isn't present in our current proto definitions, by that
        // the json parsing fails because the enum isn't known as proto representation
        const WBC: &str = "WIFI_BROADCAST_CHANGED";

        let mut session_update = match session_update {
            FallbackWrapper::Inner(update) => update,
            FallbackWrapper::Fallback(value) => {
                let fallback_inner = value.to_string();
                if fallback_inner.contains(WBC) {
                    log::debug!("Received SessionUpdate::{WBC}");
                } else {
                    log::warn!("SessionUpdate couldn't be parse correctly: {value:?}");
                }
                return;
            }
        };

        let reason = session_update.reason.enum_value();

        let mut session = match session_update.session.take() {
            None => return,
            Some(session) => session,
        };

        let active_device = session.host_active_device_id.take();
        if matches!(active_device, Some(ref device) if device == self.session.device_id()) {
            info!(
                "session update: <{:?}> for self, current session_id {}, new session_id {}",
                reason,
                self.session.session_id(),
                session.session_id
            );

            if self.session.session_id() != session.session_id {
                self.session.set_session_id(&session.session_id);
                self.connect_state.set_session_id(session.session_id);
            }
        } else {
            debug!("session update: <{reason:?}> from active session host: <{active_device:?}>");
        }

        // this seems to be used for jams or handling the current session_id
        //
        // handling this event was intended to keep the playback when other clients (primarily
        // mobile) connects, otherwise they would steel the current playback when there was no
        // session_id provided on the initial PutStateReason::NEW_DEVICE state update
        //
        // by generating an initial session_id from the get-go we prevent that behavior and
        // currently don't need to handle this event, might still be useful for later "jam" support
    }

    fn position(&mut self) -> u32 {
        match self.play_status {
            SpircPlayStatus::Stopped => 0,
            SpircPlayStatus::LoadingPlay { position_ms }
            | SpircPlayStatus::LoadingPause { position_ms }
            | SpircPlayStatus::Paused { position_ms, .. } => position_ms,
            SpircPlayStatus::Playing {
                nominal_start_time, ..
            } => (self.now_ms() - nominal_start_time) as u32,
        }
    }

    fn load_track(&mut self, start_playing: bool, position_ms: u32) -> Result<(), Error> {
        if self.connect_state.current_track(MessageField::is_none) {
            debug!("current track is none, stopping playback");
            self.handle_stop();
            return Ok(());
        }

        let current_uri = self.connect_state.current_track(|t| &t.uri);
        let id = SpotifyUri::from_uri(current_uri)?;
        // SPOTIFYGOOD: see Spirc::release_player (e.g. a load whose context resolved only after
        // the release)
        if self.owns_player() {
            self.player.load(id, start_playing, position_ms);
        }

        self.connect_state
            .update_position(position_ms, self.now_ms());
        if start_playing {
            self.play_status = SpircPlayStatus::LoadingPlay { position_ms };
        } else {
            self.play_status = SpircPlayStatus::LoadingPause { position_ms };
        }
        self.connect_state.set_status(&self.play_status);

        Ok(())
    }

    // SPOTIFYGOOD: see StatePuts, the handlers request puts instead of awaiting notify
    /// Requests a state put, it is sent right away or after the one in flight
    fn put_state(&mut self, put: StatePut) {
        if self.state_puts.request(put) {
            self.send_state_put(put)
        }
    }

    fn send_state_put(&mut self, put: StatePut) {
        // like notify
        self.connect_state.set_status(&self.play_status);
        if self.connect_state.is_playing() {
            self.connect_state
                .update_position_in_relation(self.now_ms());
        }
        self.connect_state.set_now(self.now_ms() as u64);

        let request = self.connect_state.put_state_request(put.reason());
        let session = self.session.clone();
        let sending = async move {
            timeout(
                STATE_PUT_TIMEOUT,
                session.spclient().put_connect_state_request(&request),
            )
            .await
        };
        self.state_puts.start(put, sending.boxed());
    }

    fn handle_state_put_done(&mut self, put: StatePut, result: StatePutResult) {
        match result {
            // the response may contain the cluster
            Ok(Ok(response)) => self.publish_cluster_from_response(&response),
            Ok(Err(why)) => error!("{put:?} put failed: {why}"),
            Err(_) => error!("{put:?} put timed out"),
        }
        if let Some(next) = self.state_puts.done() {
            self.send_state_put(next)
        }
    }

    async fn notify(&mut self) -> Result<(), Error> {
        self.connect_state.set_status(&self.play_status);

        if self.connect_state.is_playing() {
            self.connect_state
                .update_position_in_relation(self.now_ms());
        }

        self.connect_state.set_now(self.now_ms() as u64);

        let response = self.connect_state.send_state(&self.session).await?;
        // SPOTIFYGOOD: the response may contain the cluster
        self.publish_cluster_from_response(&response);
        Ok(())
    }

    fn set_volume(&mut self, volume: u16) {
        debug!("SpircTask::set_volume({volume})");

        let old_volume = self.connect_state.device_info().volume;
        let new_volume = volume as u32;
        if old_volume != new_volume || self.mixer.volume() != volume {
            self.update_volume = true;

            self.connect_state.set_volume(new_volume);
            self.mixer.set_volume(volume);
            if let Some(cache) = self.session.cache() {
                cache.save_volume(volume)
            }
            if self.connect_state.is_active() {
                self.player.emit_volume_changed_event(volume);
            }
        }
    }
}

impl SpircTask {
    // SPOTIFYGOOD: see Spirc::release_player
    fn owns_player(&self) -> bool {
        !self.player_released.load(Ordering::Acquire)
    }
}

// SPOTIFYGOOD: see Drop for SpircTask
// SPOTIFYGOOD: what a play command does
#[derive(Debug, PartialEq, Eq)]
enum PlayAction {
    Play,
    Pause,
    /// load the current track again from its start
    Restart,
    NothingToPlay,
    Nothing,
}

/// What a play command (`toggle`: play/pause) does in the given status
///
/// Stopped but active (the context ended, or the player was halted after refused loads), the
/// current track is played again from its start, like the remote resume already did. A local
/// play was ignored and reported success without any audio, so the app never fell back to
/// loading anything.
fn play_action(status: &SpircPlayStatus, toggle: bool, has_track: bool) -> PlayAction {
    match status {
        SpircPlayStatus::Stopped if has_track => PlayAction::Restart,
        SpircPlayStatus::Stopped => PlayAction::NothingToPlay,
        SpircPlayStatus::Paused { .. } | SpircPlayStatus::LoadingPause { .. } => PlayAction::Play,
        SpircPlayStatus::Playing { .. } | SpircPlayStatus::LoadingPlay { .. } if toggle => {
            PlayAction::Pause
        }
        SpircPlayStatus::Playing { .. } | SpircPlayStatus::LoadingPlay { .. } => {
            PlayAction::Nothing
        }
    }
}

fn pauses_on_drop(owns_player: bool, pause_on_drop: bool, stopped: bool) -> bool {
    owns_player && (pause_on_drop || !stopped)
}

impl Drop for SpircTask {
    fn drop(&mut self) {
        debug!("drop Spirc[{}]", self.spirc_id);
        // SPOTIFYGOOD: covers every end of the task (also an abort of its future)
        self.suggestion_fetch.cancel();
        // SPOTIFYGOOD: nothing controls the player after this. A task that ended by itself, or
        // was aborted in the middle of a handler (handle_shutdown never ran), left it playing.
        // An inactive spirc doesn't touch it (the app may drive it, see handle_player_event),
        // neither does one whose player the app took back (its offline playback may already
        // run on it, see Spirc::release_player).
        if pauses_on_drop(
            self.owns_player(),
            self.pause_on_drop,
            matches!(self.play_status, SpircPlayStatus::Stopped),
        ) {
            self.player.pause();
        }
    }
}

// SPOTIFYGOOD
#[cfg(test)]
mod tests {
    use super::{
        PlayAction, SpircPlayStatus, StatePut, StatePutResult, StatePuts, SuggestionFetch,
        pauses_on_drop, play_action,
    };
    use futures_util::FutureExt;
    use std::time::Duration;

    #[test]
    fn state_puts_wait_once_per_kind_in_order() {
        let mut puts = StatePuts::default();
        assert!(
            puts.request(StatePut::State),
            "nothing in flight, sent right away"
        );
        puts.start(StatePut::State, std::future::pending().boxed());

        assert!(!puts.request(StatePut::Volume));
        assert!(!puts.request(StatePut::State));
        assert!(!puts.request(StatePut::Volume));
        assert!(!puts.request(StatePut::AudioOutput));
        assert!(!puts.request(StatePut::State));
        assert_eq!(
            puts.waiting,
            [StatePut::Volume, StatePut::State, StatePut::AudioOutput]
        );

        assert_eq!(puts.done(), Some(StatePut::Volume));
        assert!(puts.in_flight.is_none());
        puts.start(StatePut::Volume, std::future::pending().boxed());
        assert_eq!(puts.done(), Some(StatePut::State));
        puts.start(StatePut::State, std::future::pending().boxed());

        // a disconnect drops them all
        puts.cancel();
        assert!(puts.in_flight.is_none() && puts.waiting.is_empty());
        assert!(puts.request(StatePut::State));
    }

    #[test]
    fn a_stalled_state_put_doesnt_hold_back_a_command() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .unwrap();
        rt.block_on(async {
            let mut puts = StatePuts::default();
            // a put that never gets an answer (stalled connection, Retry-After), bounded
            let stalled = async {
                tokio::time::timeout(Duration::from_millis(50), std::future::pending()).await
            };
            puts.start(StatePut::State, stalled.boxed());
            assert!(!puts.request(StatePut::State));

            // the loop handles the command (e.g. a pause) while the put is in flight
            let (commands, mut rx) = tokio::sync::mpsc::unbounded_channel();
            commands.send("pause").unwrap();
            let state_put = puts.in_flight.as_mut();
            tokio::select! {
                _ = async { (&mut state_put.unwrap().1).await } => panic!("the put was answered"),
                cmd = rx.recv() => assert_eq!(cmd, Some("pause")),
            }

            // and the put gives up, the waiting one follows
            let (_, sending) = puts.in_flight.as_mut().unwrap();
            let result: StatePutResult = sending.await;
            assert!(result.is_err(), "timed out");
            assert_eq!(puts.done(), Some(StatePut::State));
        });
    }

    #[test]
    fn play_while_stopped_restarts_the_current_track() {
        use PlayAction::*;
        let stopped = SpircPlayStatus::Stopped;
        let paused = SpircPlayStatus::Paused {
            position_ms: 1,
            preloading_of_next_track_triggered: false,
        };
        let playing = SpircPlayStatus::Playing {
            nominal_start_time: 0,
            preloading_of_next_track_triggered: false,
        };
        let loading = SpircPlayStatus::LoadingPlay { position_ms: 0 };

        // the context ended (or the player was halted): play and toggle restart the track
        assert_eq!(play_action(&stopped, false, true), Restart);
        assert_eq!(play_action(&stopped, true, true), Restart);
        // nothing to restart: an error instead of a silent success
        assert_eq!(play_action(&stopped, false, false), NothingToPlay);
        assert_eq!(play_action(&stopped, true, false), NothingToPlay);

        assert_eq!(play_action(&paused, false, true), Play);
        assert_eq!(play_action(&paused, true, true), Play);
        assert_eq!(play_action(&playing, false, true), Nothing);
        assert_eq!(play_action(&playing, true, true), Pause);
        assert_eq!(play_action(&loading, true, true), Pause);
    }

    #[test]
    fn a_released_player_is_left_alone_when_the_task_ends() {
        // ended by itself while playing, or aborted in a handler: paused
        assert!(pauses_on_drop(true, true, true));
        assert!(pauses_on_drop(true, false, false));
        assert!(!pauses_on_drop(true, false, true));
        // the app took the player back (its offline playback may run on it): untouched
        assert!(!pauses_on_drop(false, true, false));
        assert!(!pauses_on_drop(false, false, false));
    }

    #[test]
    fn restart_aborts_the_suggestion_fetch_in_flight() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        rt.block_on(async {
            let mut fetch = SuggestionFetch::default();
            let task = tokio::spawn(std::future::pending::<()>());
            fetch.in_flight = true;
            fetch.task = Some(task.abort_handle());

            fetch.restart();
            assert!(!fetch.in_flight && fetch.task.is_none());
            assert_eq!(fetch.generation, 1);
            assert!(task.await.unwrap_err().is_cancelled());
        });
    }
}
