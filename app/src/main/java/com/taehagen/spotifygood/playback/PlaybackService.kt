package com.taehagen.spotifygood.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.Util
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.Notifications
import com.taehagen.spotifygood.MainActivity
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.connect.SystemRoutes
import com.taehagen.spotifygood.download.DownloadedCollection
import com.taehagen.spotifygood.engine.EngineHolder
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.widget.NowPlayingWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's media session host (docs/ARCHITECTURE.md §9.4): a [MediaLibraryService] whose session
 * player is [SpotifyPlayer]. It provides the media notification (Media3 default provider),
 * lock-screen / SysUI / Bluetooth / Wear controls, the Android Auto browse tree ([LibraryTree]),
 * search, playback resumption ([ResumeStore]) and the opt-in Connect presence
 * ([PresenceController]).
 *
 * Foreground handling is Media3's (mediaPlayback while playback is ongoing, 10 min after a pause),
 * except that with Connect presence enabled the service stays foreground as `connectedDevice`
 * instead of leaving the foreground ([onUpdateNotificationAsync]).
 *
 * Holds the [HolderType.PLAYBACK] engine holder from the first playback command (or local /
 * mirrored playback, the media foreground, our own playback starts) to [onDestroy] or the end of
 * the paused lifetime (10 min of wall time paused, [PausedIdle]), never for a
 * browse-only bind: SysUI's resumption card and Bluetooth's player discovery bind at boot and get
 * the root and the stored session without the engine. Catalog browsing and search hold it while
 * the browser keeps browsing ([holdForBrowsing]).
 */
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: AppGraph
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var player: SpotifyPlayer
    private lateinit var tree: LibraryTree
    private lateinit var resumeStore: ResumeStore
    private lateinit var presence: PresenceController
    /** The volume keys for another device while it plays ([RemotePlayback]). */
    private lateinit var remoteVolumeKeys: RemoteVolumeKeys
    /** Connect devices in Android's output switcher (API 30+, [SystemRouting]). */
    private var systemRouting: SystemRouting? = null
    private var session: MediaLibrarySession? = null
    /** Guarded by [holderLock]. */
    private var playbackHolder: EngineHolder? = null
    /** Held while a browser reads catalog content (guarded by [holderLock]). */
    private var browseHolder: EngineHolder? = null
    private val holderLock = Any()
    /** Wall-clock bound of the paused lifetime, see [PausedIdle]. */
    private val pausedIdle = PausedIdle()
    /** Main thread. */
    private var armedIdleAt: Long? = null
    /** The paused lifetime ended: no media foreground until playback is asked for again. */
    @Volatile private var pausedIdleExpired = false
    private val releaseBrowseHolder = Runnable {
        synchronized(holderLock) {
            browseHolder?.release()
            browseHolder = null
        }
    }

    /** Media3 currently keeps the service foreground with the media notification. */
    private var mediaForeground = false
        set(value) {
            field = value
            isMediaForeground = value
        }
    private var buttons: List<CommandButton> = emptyList()
    /**
     * The media foreground was refused while mirroring a remote device: until that changes (or the
     * app is visible again) the mirroring notification is posted without asking for the foreground.
     */
    private var remoteForegroundRefused = false
    private val main = Handler(Looper.getMainLooper())
    /** A start that may have been a startForegroundService must reach the foreground in time. */
    private val foregroundDeadline = Runnable { onForegroundDeadline() }
    /** Start id of a foreign start behind the pending [foregroundDeadline] (null: ours or Media3's). */
    private var deadlineStartId: Int? = null
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()
    /** uri → downloaded cover path of completed downloads (offline artwork). */
    @Volatile private var downloadedImages: Map<String, String> = emptyMap()
    /** The downloaded collections ([downloadedQueue]: what is downloaded on its own). */
    @Volatile private var downloadedCollections: List<DownloadedCollection> = emptyList()
    /** The stored credentials were read: "logged out" is real from then on. */
    @Volatile private var engineReady = false
    /** Last published player error (the same instance while unchanged, so controllers see it once). */
    private var publishedError: Pair<PlayerErrorInfo, PlaybackException>? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        current = WeakReference(this)
        graph = (application as App).graph
        coordinator = PlaybackCoordinator.install(this)
        resumeStore = graph.resumeStore
        tree = LibraryTree(this, graph)
        presence = PresenceController(this, graph) { coordinator.isAppInForeground }
        player = SpotifyPlayer(
            context = this,
            playback = graph.playback,
            controller = graph.player,
            devices = graph.devices,
            volume = coordinator.volumeSync,
            audioSessionId = graph.audioSink.audioSessionId,
            downloadedQueue = ::downloadedQueue,
            downloadedImage = { uri -> downloadedImages[uri] },
            playerError = ::currentPlayerError,
            onRetry = ::retryAfterError,
            onCommand = ::ensurePlaybackHolder,
            podcastSpeed = { graph.podcastSpeed.inEffect.value },
            onSpeed = graph.podcastSpeed::set,
            requester = { session?.controllerForCurrentRequest?.packageName },
            bluetoothOutput = { graph.outputs.bluetoothOutput.value },
            routingControllerId = { systemRouting?.controllerId },
        )
        remoteVolumeKeys = RemoteVolumeKeys(
            context = this,
            sessionActivity = sessionActivity(),
            setVolume = { percent -> player.setDeviceVolume(percent, 0) },
            adjustVolume = { direction -> if (direction > 0) player.increaseDeviceVolume(0) else player.decreaseDeviceVolume(0) },
            playRequested = { caller ->
                if (player.playMeansPause(caller)) {
                    graph.player.pause()
                } else {
                    graph.player.resume()
                }
            },
            pauseRequested = { graph.player.pause() },
            nextRequested = { graph.player.next() },
            previousRequested = { graph.player.previous() },
        )
        if (Build.VERSION.SDK_INT >= SystemRoutes.MIN_SDK) {
            // Lives with the service: the route provider is enabled meanwhile, never longer.
            systemRouting = SystemRouting(
                context = this,
                graph = graph,
                onControllerChanged = {
                    player.refresh()
                    updateRemoteVolumeKeys()
                },
                setVolume = { percent -> player.setDeviceVolume(percent, 0) },
            ).also { it.start() }
        }
        // Follows what the session publishes, also when a Bluetooth output comes or goes while
        // another device plays: one switch between the two volume key sessions.
        player.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateRemoteVolumeKeys()
            },
        )

        val provider = PlaybackNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(PresenceAwareNotificationProvider(provider))

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .apply { sessionActivity()?.let(::setSessionActivity) }
            .setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this, lifecycleScope)))
            .build()
            // Media3 adds a session only when a controller binds (or for a media button). Our own
            // starts (Tap to resume, background local audio) have no controller, and only an added
            // session gets the media notification and the foreground promotion.
            .also(::addSession)

        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() = onForegroundStartNotAllowed()
        })

        observeState()
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // The service is exported (controllers, Auto): our own starts carry the in-process token,
        // and our own actions only count with it, never from another app's explicit intent.
        val ours = intent?.getStringExtra(EXTRA_TOKEN) == token
        val internal = action in INTERNAL_ACTIONS && ours
        if (action in INTERNAL_ACTIONS && !internal) Log.w(TAG, "Ignoring $action without the app's token")
        when (action.takeIf { internal }) {
            ACTION_START_PRESENCE -> {
                if (isPresenceWanted()) presence.enable(showNow = !mediaForeground)
                updatePausedIdle()
                if (intent?.getBooleanExtra(EXTRA_FOREGROUND_START, false) == true && !presence.isForeground && !mediaForeground) {
                    // The restore after a reboot or an update (PresenceRestore) is a
                    // startForegroundService: meet that contract even when presence did not come
                    // up, as connectedDevice (Android 15 refuses mediaPlayback from boot), and
                    // tell the user it waits for the app.
                    satisfyForegroundContract(if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                    if (isPresenceWanted()) PresenceRestore.postNotice(this)
                }
                // Presence not up (not wanted, or refused: also silently, for a restricted app) and
                // nothing else to do: this start is over; the app starts presence again when visible.
                if (!presence.isForeground && !mediaForeground && !player.isPlaying) stopSelf(startId)
            }
            ACTION_RESUME -> {
                ensurePlaybackHolder()
                ResumeAlert.cancel(this)
                resumeFromAlert()
            }
            ACTION_LOCAL_PLAYBACK -> ensurePlaybackHolder()
        }
        // Any start may have been a startForegroundService (ours for ACTION_LOCAL_PLAYBACK, Media3's
        // media-button receiver, or any other app: the service is exported), and a service that
        // does not call startForeground in time is killed with the app. Media3 calls it for its own
        // start-self intents; our other starts are plain startService calls. For the rest, Media3
        // goes foreground when the player plays; otherwise the deadline enters and leaves it.
        val media3StartSelf = intent?.hasExtra(MEDIA3_START_SELF_EXTRA) == true
        val ourPlainStart = ours && action != ACTION_LOCAL_PLAYBACK
        if (intent != null && !media3StartSelf && !ourPlainStart && !mediaForeground && !presence.isForeground) {
            // Only a start nothing in the app knows (not ours, not a Media3 media button or
            // notification action) is stopped again when it left nothing to do.
            val foreign = !ours && action != Intent.ACTION_MEDIA_BUTTON && action != MEDIA3_CUSTOM_ACTION
            deadlineStartId = if (foreign) startId else null
            main.removeCallbacks(foregroundDeadline)
            main.postDelayed(foregroundDeadline, FOREGROUND_DEADLINE_MS)
        }
        super.onStartCommand(intent, flags, startId)
        // A sticky restart after process death would happen in the background, where the
        // service cannot go foreground: it would only wake the engine for nothing.
        return START_NOT_STICKY
    }

    /**
     * Keeps Media3's notification behaviour, except while Connect presence is enabled: instead of
     * leaving the foreground (Media3 would call `stopForeground`) the service switches to the
     * presence notification as a `connectedDevice` foreground service (research §4.4).
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> {
        // Mirroring a remote device whose media foreground was refused: keep the controls as a
        // normal notification instead of failing (and being told so) on every state change.
        // A play (from any controller) asks for the foreground again at once, inside its allowlist.
        if (session.player.playWhenReady) pausedIdleExpired = false
        val foregroundRequired = startInForegroundRequired && !pausedIdleExpired &&
            !(remoteForegroundRefused && graph.playback.snapshot.value.source == PlaybackSource.REMOTE)
        val idle = !foregroundRequired || session.player.currentTimeline.isEmpty
        if (presence.isEnabled && idle && (presence.isForeground || presence.showForeground())) {
            mediaForeground = false
            return Futures.immediateFuture(null)
        }
        val future = super.onUpdateNotificationAsync(session, foregroundRequired)
        future.addListener(
            {
                val succeeded = runCatching { future.get() }.isSuccess
                if (!succeeded) return@addListener
                // With an empty timeline Media3 removes the notification and leaves the
                // foreground, even when the foreground is still required.
                mediaForeground = !idle
                if (!idle) {
                    ensurePlaybackHolder()
                    presence.onMediaForeground()
                    main.removeCallbacks(foregroundDeadline)
                    coordinator.onServiceForeground()
                } else if (presence.isEnabled) {
                    // Media3 just left the foreground although presence is on: take it back.
                    presence.showForeground()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
        return future
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // With Connect presence the user asked the phone to stay available: keep running.
        if (presence.isEnabled && (presence.isForeground || mediaForeground)) return
        // Another device plays: Media3 keeps a foreground service whose session plays, but this one
        // reads suppressed (RemotePlayback), so it would pause that device with the swiped-away
        // app. Its own rule, with "plays elsewhere" for "plays": mirroring goes on.
        if (isPlaybackOngoing && RemotePlayback.playsElsewhere(graph.playback.snapshot.value)) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        current = null
        mediaForeground = false
        PausedIdleAlarm.cancel(this)
        main.removeCallbacks(foregroundDeadline)
        coordinator.closeEffectSession()
        presence.release()
        // Before the session: its routing session goes with it, and the route provider is disabled.
        systemRouting?.stop()
        systemRouting = null
        remoteVolumeKeys.release()
        clearListener()
        session?.release()
        session = null
        player.release()
        main.removeCallbacks(releaseBrowseHolder)
        synchronized(holderLock) {
            playbackHolder?.release()
            playbackHolder = null
            browseHolder?.release()
            browseHolder = null
        }
        super.onDestroy()
    }

    // ---- paused lifetime ----------------------------------------------------------------------

    /**
     * Hands the volume keys to [RemoteVolumeKeys] while another device plays and the session reads
     * paused (a Bluetooth output is connected), else back to the session. Main thread.
     */
    private fun updateRemoteVolumeKeys() {
        val s = graph.playback.snapshot.value
        val supported = player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        val active = RemotePlayback.volumeKeysSession(s, player.readsPaused, supported)
        remoteVolumeKeys.update(active, player.deviceVolume, s.track?.name, s.track?.artistLine, systemRouting?.controllerId)
    }

    /** Playing or loading (here or on the mirrored device), or Connect presence keeps it up. */
    private fun isBusy(): Boolean {
        val status = graph.playback.snapshot.value.status
        return status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING || presence.isEnabled
    }

    /**
     * Arms (or cancels) the paused-idle alarm for the current state, and lets go once its wall
     * time passed ([PausedIdle]). Main thread: state changes, the alarm, wake-up events.
     */
    private fun updatePausedIdle() {
        if (!isRunning) return
        val now = SystemClock.elapsedRealtime()
        val busy = isBusy()
        // Playing again: Media3's own foreground handling applies again.
        if (busy) pausedIdleExpired = false
        val at = pausedIdle.update(busy, now)
        if (pausedIdle.isDue(busy, now)) {
            letGoAfterPause()
            return
        }
        if (at == armedIdleAt) return
        armedIdleAt = at
        if (at == null) PausedIdleAlarm.cancel(this) else PausedIdleAlarm.schedule(this, at)
    }

    /**
     * Ten minutes of wall time after a pause: what Media3's (uptime) timeout means. The service
     * leaves the media foreground (the paused notification stays, dismissable), the engine
     * holders go (the engine stops after its idle grace unless the app is visible), and the
     * service stops once no controller is bound. Nothing is paused: nothing plays (checked), and
     * a pause could reach a remote device that started playing meanwhile. Media3's own timeout
     * is left alone (disabling it would end its pause grace for good in this service).
     */
    private fun letGoAfterPause() {
        pausedIdle.expire()
        armedIdleAt = null
        PausedIdleAlarm.cancel(this)
        Log.i(TAG, "Paused for 10 minutes: leaving the foreground and releasing the engine")
        pausedIdleExpired = true
        triggerNotificationUpdate()
        main.removeCallbacks(releaseBrowseHolder)
        synchronized(holderLock) {
            // The paused lifetime was the grace: without other holders the engine hides from
            // Connect and stops now, not 20 s / 60 s of (sleeping) uptime later.
            playbackHolder?.releaseNow()
            playbackHolder = null
            browseHolder?.releaseNow()
            browseHolder = null
        }
        stopSelf()
    }

    // ---- engine holders -----------------------------------------------------------------------

    /**
     * The service has playback work: hold the engine (and Connect) until [onDestroy] or until it
     * stayed paused for the paused lifetime ([letGoAfterPause]), counted again from now. Any thread.
     */
    private fun ensurePlaybackHolder() {
        pausedIdleExpired = false
        pausedIdle.restart(SystemClock.elapsedRealtime())
        main.post(::updatePausedIdle)
        synchronized(holderLock) {
            if (playbackHolder == null && isRunning) playbackHolder = graph.engine.acquire(HolderType.PLAYBACK)
        }
    }

    /**
     * A browser reads catalog content (Auto's tabs, search): the session runs while it keeps
     * browsing, and [BROWSE_HOLD_MS] after its last request, so its next tap plays at once.
     * Any thread; acquired before returning, so a following session wait sees it.
     */
    private fun holdForBrowsing() {
        synchronized(holderLock) {
            if (!isRunning) return
            if (browseHolder == null) browseHolder = graph.engine.acquire(HolderType.PLAYBACK)
            main.removeCallbacks(releaseBrowseHolder)
            main.postDelayed(releaseBrowseHolder, BROWSE_HOLD_MS)
        }
    }

    // ---- state observation ------------------------------------------------------------------

    private fun observeState() {
        val playback = graph.playback
        lifecycleScope.launch {
            // Android Auto orders its tabs by the network (Downloads first offline): re-read the
            // root when that flips (also when a stopped engine's flag is refreshed on a read).
            combine(graph.engine.isNetworkAvailable, graph.settings.settings.map { it.offlineMode }) { network, offline -> network && !offline }
                .distinctUntilChanged()
                .drop(1)
                .collect { session?.notifyChildrenChanged(LibraryTree.ROOT, ROOT_TABS, null) }
        }
        lifecycleScope.launch {
            // Local or mirrored playback (e.g. "play on this phone" from another device) keeps the
            // engine; a browse-only bind (nothing loaded) does not start it.
            playback.snapshot.map { it.source != PlaybackSource.NONE }.distinctUntilChanged().filter { it }
                .collect { ensurePlaybackHolder() }
        }
        lifecycleScope.launch {
            merge(
                playback.snapshot.map { },
                graph.devices.devices.map { },
                coordinator.volumeSync.streamIndex.map { },
                // Inputs of the player error.
                graph.engine.state.map { },
                graph.player.failure.map { },
                graph.podcastSpeed.inEffect.map { },
                // A Bluetooth output decides how another device's playback reads (RemotePlayback).
                graph.outputs.bluetoothOutput.map { },
            ).collect {
                player.refresh()
                updateRemoteVolumeKeys()
                // Runs on most wake-ups (engine and snapshot events): a cheap check of the
                // paused-idle deadline besides its alarm.
                if (pausedIdle.isDue(isBusy(), SystemClock.elapsedRealtime())) updatePausedIdle()
            }
        }
        lifecycleScope.launch {
            // Paused (or stopped) here or on the mirrored device: bound the paused lifetime.
            playback.snapshot.map { it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.LOADING }
                .distinctUntilChanged()
                .collect { updatePausedIdle() }
        }
        lifecycleScope.launch {
            graph.engine.awaitReady()
            engineReady = true
            player.refresh()
        }
        lifecycleScope.launch {
            // Asynchronous playback failures (e.g. nothing in the context could be played) reach
            // the session as well, not only the in-app snackbar.
            graph.events.errors
                .filter { it.context == "playback" && it.code != NativeErrorCode.CANCELLED && it.code != NativeErrorCode.PLAYBACK_REFUSED }
                .collect { e -> PlaybackErrorKind.fromCode(e.code)?.let { graph.player.noteFailure(it, e.message) } }
        }
        lifecycleScope.launch {
            playback.isPlaying.filter { it }.collect { if (ResumeAlert.isPosted) ResumeAlert.cancel(this@PlaybackService) }
        }
        lifecycleScope.launch {
            // A refused remote-mirroring foreground is retried once the situation changed.
            combine(playback.snapshot.map { it.source }.distinctUntilChanged(), coordinator.appVisible) { source, visible ->
                source != PlaybackSource.REMOTE || visible
            }.filter { it }.collect {
                if (remoteForegroundRefused) {
                    remoteForegroundRefused = false
                    triggerNotificationUpdate()
                }
            }
        }
        lifecycleScope.launch {
            graph.downloads.collections
                .catch { Log.w(TAG, "Downloaded collections unavailable", it) }
                .collect { downloadedCollections = it }
        }
        lifecycleScope.launch {
            // Changes only with the set of completed downloads (not on download progress).
            graph.downloads.downloadedImages
                .catch { Log.w(TAG, "Downloaded artwork unavailable", it) }
                .collect { images ->
                    downloadedImages = images
                    player.refresh()
                }
        }
        lifecycleScope.launch {
            combine(playback.snapshot, likedState()) { s, liked ->
                PlaybackSessionCommands.ButtonState(
                    hasItem = s.track != null,
                    liked = liked,
                    shuffle = s.shuffleMode,
                    smartShuffleAvailable = s.isSmartShuffleAvailable,
                    canShuffle = s.restrictions.canToggleShuffle,
                    repeat = s.repeat,
                    isEpisode = s.track?.isEpisode == true,
                    canSeek = s.restrictions.canSeek,
                )
            }.distinctUntilChanged().collect { state ->
                buttons = PlaybackSessionCommands.buttons(this@PlaybackService, state)
                session?.setMediaButtonPreferences(buttons)
            }
        }
        lifecycleScope.launch {
            // Home-screen widgets (docs §9.4): track, play state, like and device, only while one is
            // placed; once the service is gone they show the stored session.
            NowPlayingWidgets.follow(this@PlaybackService, likedState())
        }
        lifecycleScope.launch {
            // Resume state: the local session on every relevant change and every 15 s while it
            // plays, frozen once playback leaves the phone; logging out forgets it. One collector,
            // so a save in progress never lands after the clear.
            ResumeSaver.actions(playback.snapshot, graph.engine.isLoggedIn, RESUME_SAVE_INTERVAL_MS).collect { action ->
                when (action) {
                    is ResumeSaver.Action.Save -> resumeStore.save(action.state)
                    ResumeSaver.Action.Clear -> resumeStore.clear()
                }
            }
        }
        lifecycleScope.launch {
            combine(
                graph.settings.settings.map { it.connectPresence }.distinctUntilChanged(),
                graph.engine.isLoggedIn,
            ) { enabled, loggedIn -> enabled && loggedIn }
                .distinctUntilChanged()
                .collect { wanted ->
                    if (wanted) {
                        presence.enable(showNow = !mediaForeground)
                    } else if (presence.disable()) {
                        afterPresenceDisabled()
                    }
                    updatePausedIdle()
                }
        }
    }

    // ---- player error ---------------------------------------------------------------------------

    /** What controllers should be told while nothing plays (see [PlayerErrors]). Main thread. */
    private fun currentPlayerError(): PlaybackException? {
        val info = PlayerErrors.select(
            ready = engineReady,
            loggedIn = graph.engine.isLoggedIn.value,
            accountErrorCode = graph.engine.state.value.error?.code,
            snapshot = graph.playback.snapshot.value,
            failure = graph.player.failure.value,
            messages = graph.player.errorMessages,
        )
        if (info == null) {
            publishedError = null
            return null
        }
        publishedError?.takeIf { it.first == info }?.let { return it.second }
        val error = PlaybackException(info.message, null, info.code, if (info.signIn) signInExtras() else Bundle.EMPTY)
        publishedError = info to error
        return error
    }

    /** A controller retried (`prepare()`): give account errors a fresh chance, like the app's "Try again". */
    private fun retryAfterError() {
        val code = graph.engine.state.value.error?.code
        if (code == NativeErrorCode.PLAYBACK_REFUSED || code == NativeErrorCode.PREMIUM_REQUIRED) {
            graph.engine.clearError()
            graph.engine.retry()
        }
    }

    /** "Sign in" resolution (Android Auto shows it with the error): opens the app's login. */
    private fun signInExtras(): Bundle {
        val launch = MainActivity.launchIntent(this)
        val intent = PendingIntent.getActivity(this, REQUEST_SIGN_IN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, getString(R.string.playback_error_action_sign_in))
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, intent)
        }
    }

    /**
     * Browsing while logged out or without Premium: an error (with "Sign in") instead of empty
     * tabs; Media3 replicates these codes to the platform session for Auto.
     */
    private suspend fun accountLibraryError(): SessionError? {
        if (!graph.engine.isLoggedIn.value) {
            if (withTimeoutOrNull(LOGIN_WAIT_MS) { graph.engine.awaitReady() } == null) return null
            if (!graph.engine.isLoggedIn.value) {
                val message = graph.player.errorMessages.message(PlaybackErrorKind.NOT_LOGGED_IN, null)
                return SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, message, withContext(Dispatchers.Main) { signInExtras() })
            }
        }
        if (graph.engine.state.value.error?.code == NativeErrorCode.PREMIUM_REQUIRED) {
            val message = graph.player.errorMessages.message(PlaybackErrorKind.PREMIUM_REQUIRED, null)
            return SessionError(SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED, message)
        }
        return null
    }

    /**
     * Liked state of the current track; null while unknown (lookup pending, or failed offline: it is
     * retried once the session is online), which hides the heart button rather than showing a guess.
     */
    private fun likedState(): Flow<Boolean?> = graph.playback.currentTrack
        .map { it?.uri }
        .distinctUntilChanged()
        .flatMapLatest { uri ->
            if (uri == null) {
                flowOf(null)
            } else {
                runCatching { graph.library.isSaved(uri) }.getOrElse { flowOf(null) }
                    .onStart { emit(null) }
                    .catch { emit(null) }
            }
        }

    private fun isPresenceWanted(): Boolean =
        graph.settings.settings.value.connectPresence && graph.engine.isLoggedIn.value

    private fun afterPresenceDisabled() {
        // Let Media3 show its own notification (if any) again.
        triggerNotificationUpdate()
        if (!mediaForeground && !player.isPlaying) stopSelf()
    }

    private fun setLiked(uri: String?, liked: Boolean) {
        uri ?: return
        lifecycleScope.launch {
            runCatching { graph.library.setSaved(listOf(uri), liked) }
                .onFailure { Log.w(TAG, "Like failed", it) }
        }
    }

    // ---- notifications ------------------------------------------------------------------------

    private fun sessionActivity(): PendingIntent? {
        val launch = MainActivity.launchIntent(this)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_PLAYER, true)
        return PendingIntent.getActivity(this, REQUEST_SESSION, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * "Tap to resume". Through the session player so Media3 asks for the foreground right away
     * (BUFFERING + play-when-ready), inside the tap's short allowlist, instead of after a cold
     * engine start: the current items when there are some, else the stored session.
     */
    private fun resumeFromAlert() {
        if (player.mediaItemCount > 0) {
            player.play()
            return
        }
        lifecycleScope.launch {
            val last = resumeStore.read()
            if (last == null) {
                graph.player.resume()
                return@launch
            }
            player.setMediaItem(tree.resumeItem(last, downloadedImages[last.trackUri]), last.positionMs)
            player.prepare()
            player.play()
        }
    }

    /** Media3 could not start the foreground service from the background (API 31+). */
    private fun onForegroundStartNotAllowed() {
        val s = graph.playback.snapshot.value
        if (s.source != PlaybackSource.LOCAL) {
            // Mirroring another device: nothing plays here, so nothing to pause (a pause would go
            // to that device). Show its controls as a normal notification instead.
            Log.i(TAG, "Foreground start refused while mirroring ${s.source}; posting the notification without it")
            remoteForegroundRefused = true
            session?.let { onUpdateNotificationAsync(it, false) }
            return
        }
        if (coordinator.isAppInForeground || presence.isForeground) {
            // The visible app (or presence's own connectedDevice foreground) keeps the process:
            // the audio goes on, and Media3 asks for the foreground again on its next update.
            // Pausing here would stop a play the user just started in the app.
            Log.w(TAG, "Media foreground refused while the app is visible or presence is up; playing on")
            return
        }
        Log.w(TAG, "Foreground service start not allowed; asking the user to resume")
        // Local audio never plays without the media foreground service: pause (Connect sees it).
        coordinator.refuseBackgroundPlayback()
    }

    /**
     * A start that may have been a `startForegroundService` did not reach the media foreground in
     * time (e.g. playback was paused meanwhile so Media3 did not ask for it, or another app started
     * the exported service): satisfy the contract by entering and leaving the foreground. Local
     * audio must not keep playing in the background without it; a foreign start that left nothing
     * to do stops again.
     */
    private fun onForegroundDeadline() {
        val foreignStartId = deadlineStartId
        deadlineStartId = null
        if (mediaForeground || presence.isForeground) return
        satisfyForegroundContract()
        when {
            coordinator.isPlayingLocally() -> if (!coordinator.isAppInForeground) {
                Log.w(TAG, "Media foreground not reached in time; pausing local playback")
                coordinator.refuseBackgroundPlayback()
            }
            foreignStartId != null && !presence.isEnabled && !player.isPlaying -> stopSelf(foreignStartId)
        }
    }

    /**
     * A `startForegroundService` start (a media button, see [onForegroundDeadline]) must reach the
     * foreground even when nothing plays; enter and leave it immediately (like Media3's own
     * shutdown path), with a notification of its own so a (paused) media notification stays.
     */
    private fun satisfyForegroundContract(
        type: Int = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
    ) {
        if (mediaForeground || presence.isForeground) return
        try {
            val notifications = NotificationManagerCompat.from(this)
            if (notifications.getNotificationChannelCompat(Notifications.CHANNEL_PLAYBACK) == null) {
                notifications.createNotificationChannel(
                    NotificationChannelCompat.Builder(Notifications.CHANNEL_PLAYBACK, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(getString(R.string.playback_channel_name))
                        .build(),
                )
            }
            val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_PLAYBACK)
                .setSmallIcon(R.drawable.ic_notification)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
            ServiceCompat.startForeground(this, CONTRACT_NOTIFICATION_ID, notification, type)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground not allowed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground refused", e)
        }
    }

    /**
     * Media3's notification, whose text also says "Playing on <device>" for remote playback
     * (docs §8): the default provider shows only title and artist, and the device line is the
     * metadata subtitle. On API 30+ the artist already carries it (see [SpotifyPlayer]); it is never
     * added twice.
     */
    private class PlaybackNotificationProvider(private val context: Context) : DefaultMediaNotificationProvider(
        context,
        { Notifications.ID_PLAYBACK },
        Notifications.CHANNEL_PLAYBACK,
        R.string.playback_channel_name,
    ) {
        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
            DeviceLine.join(context, super.getNotificationContentText(metadata), metadata.subtitle)

        /**
         * Another device plays: the session reads paused ([RemotePlayback]), but where this
         * notification draws its own buttons (below API 33) it shows pause, which (a play/pause
         * key, toggled on `playWhenReady`) pauses that device. Unchanged otherwise: only remote
         * playback is ever suppressed.
         */
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> = super.getMediaButtons(
            session,
            playerCommands,
            mediaButtonPreferences,
            showPauseButton || !Util.shouldShowPlayButton(session.player, /* shouldShowPlayIfSuppressed= */ false),
        )
    }

    /** Drops late (artwork) updates of the media notification while presence owns the foreground. */
    private inner class PresenceAwareNotificationProvider(
        private val delegate: MediaNotification.Provider,
    ) : MediaNotification.Provider by delegate {
        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { notification ->
            ContextCompat.getMainExecutor(this@PlaybackService).execute {
                // Forwarding would make Media3 call stopForeground and drop the presence foreground.
                if (presence.isForeground && !mediaForeground) return@execute
                onNotificationChangedCallback.onNotificationChanged(notification)
            }
        }
    }

    // ---- session callback -------------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnectAsync(session: MediaSession, controller: ControllerInfo): ListenableFuture<ConnectionResult> {
            val builder = ConnectionResult.AcceptedResultBuilder(session)
            if (isTrusted(session, controller)) {
                val commands = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                PlaybackSessionCommands.ALL.forEach { commands.add(it) }
                builder.setAvailableSessionCommands(commands.build())
                    .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .setMediaButtonPreferences(buttons)
            } else {
                // Unknown apps: read-only player state, no library (the user's playlists, Liked
                // Songs and recent searches are not for any installed app to read).
                builder.setAvailableSessionCommands(ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(ConnectionResult.DEFAULT_UNTRUSTED_PLAYER_COMMANDS)
            }
            return Futures.immediateFuture(builder.build())
        }

        /**
         * SysUI / notification-listener apps (Media3's `isTrusted`: MEDIA_CONTENT_CONTROL or an
         * enabled notification listener), the media notification, Auto / Automotive, our own process
         * (the app's controller, media buttons dispatched through the legacy session) and the known
         * system packages — by package name only when Media3 verified it against the caller's uid.
         * Connection hints are never trusted: any app can set them.
         */
        private fun isTrusted(session: MediaSession, controller: ControllerInfo): Boolean =
            controller.isTrusted ||
                session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller) ||
                controller.uid == Process.myUid() ||
                (controller.isPackageNameVerified && controller.packageName in TRUSTED_PACKAGES)

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val uri = graph.playback.snapshot.value.track?.uri
            when (customCommand.customAction) {
                PlaybackSessionCommands.ACTION_LIKE -> setLiked(uri, true)
                PlaybackSessionCommands.ACTION_UNLIKE -> setLiked(uri, false)
                PlaybackSessionCommands.ACTION_CYCLE_SHUFFLE -> graph.player.cycleShuffle()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSetRating(session: MediaSession, controller: ControllerInfo, rating: Rating): ListenableFuture<SessionResult> =
            rate(graph.playback.snapshot.value.track?.uri, rating)

        override fun onSetRating(
            session: MediaSession,
            controller: ControllerInfo,
            mediaId: String,
            rating: Rating,
        ): ListenableFuture<SessionResult> = rate(MediaIds.itemUriOf(mediaId) ?: mediaId, rating)

        private fun rate(uri: String?, rating: Rating): ListenableFuture<SessionResult> {
            val heart = rating as? HeartRating
            if (heart == null || !heart.isRated || uri == null) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            setLiked(uri, heart.isHeart)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root(params), tree.rootParams(params)))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = lifecycleScope.future(Dispatchers.Default) {
            if (LibraryTree.itemNeedsSession(mediaId)) holdForBrowsing()
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = lifecycleScope.future(Dispatchers.Default) {
            accountLibraryError()?.let { return@future LibraryResult.ofError(it) }
            // Auto browses right after connecting, often on a cold engine: let the session come up
            // first instead of answering from an empty cache. The local parents (root, recent,
            // downloads) never start the engine, nor does a downloaded collection browsed offline.
            if (LibraryTree.needsSession(parentId) && !tree.browsesDownloads(parentId)) {
                holdForBrowsing()
                coordinator.environment.awaitSessionStart()
            }
            // Paged by the tree: the downloads are read a page at a time.
            val children = tree.pagedChildren(parentId, page, pageSize, params)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            holdForBrowsing()
            lifecycleScope.launch(Dispatchers.Default) {
                val results = try {
                    coordinator.environment.awaitSessionStart()
                    tree.search(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Search failed", e)
                    emptyList()
                }
                cacheSearch(query, results)
                withContext(Dispatchers.Main) {
                    session.notifySearchResultChanged(browser, query, results.size, params)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = lifecycleScope.future(Dispatchers.Default) {
            val results = searchCache[query] ?: try {
                holdForBrowsing()
                coordinator.environment.awaitSessionStart()
                tree.search(query).also { cacheSearch(query, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                return@future LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
            LibraryResult.ofItemList(results.page(page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            // Our player needs only the media ids (queue.add); keep whatever metadata came along.
            Futures.immediateFuture(mediaItems.filter { MediaIds.itemUriOf(it.mediaId) != null })

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = lifecycleScope.future(Dispatchers.Default) {
            // A playback request (voice search needs the session too).
            ensurePlaybackHolder()
            val first = mediaItems.firstOrNull()
            val query = first?.requestMetadata?.searchQuery
            if (mediaItems.size == 1 && query != null) {
                // Voice: "play X" (empty query = "play something": resume the last context). The
                // same resolver as the activity's MEDIA_PLAY_FROM_SEARCH (LibraryTree.resolveVoice).
                val voice = VoiceRequest.of(query, first.requestMetadata.extras)
                // "Play X" right after a cold start: search needs the session (NOT_CONNECTED
                // otherwise); ensurePlaybackHolder above lets an idle-stopped one start.
                when (val outcome = VoiceEntry.resolve(voice, tree::isOffline, coordinator.environment::awaitSessionStart, tree::resolveVoice)) {
                    VoiceOutcome.PlaySomething -> {
                        val last = resumeStore.read() ?: noVoiceMatch(PlaybackErrorKind.NOT_ACTIVE_DEVICE)
                        MediaItemsWithStartPosition(listOf(tree.resumeItem(last, downloadedImages[last.trackUri])), 0, last.positionMs)
                    }
                    is VoiceOutcome.NoMatch -> noVoiceMatch(outcome.kind)
                    is VoiceOutcome.Play -> MediaItemsWithStartPosition(outcome.items, 0, C.TIME_UNSET)
                }
            } else {
                MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = lifecycleScope.future {
            // A Bluetooth / headset play starts the engine at once; SysUI's resumption card
            // (not for playback) only reads the stored session.
            if (isForPlayback) ensurePlaybackHolder()
            val last = resumeStore.read()
            // Logged out: neither resume nor offer (SysUI resumption card) the previous session.
            // Once the stored credentials are read (awaitReady) isLoggedIn is accurate.
            val loggedIn = graph.engine.isLoggedIn.value ||
                (withTimeoutOrNull(LOGIN_WAIT_MS) { graph.engine.awaitReady() } != null && graph.engine.isLoggedIn.value)
            if (last == null || !loggedIn) {
                if (isForPlayback) satisfyForegroundContract()
                throw UnsupportedOperationException("Nothing to resume")
            }
            MediaItemsWithStartPosition(listOf(tree.resumeItem(last, downloadedImages[last.trackUri])), 0, last.positionMs)
        }
    }

    private fun cacheSearch(query: String, results: List<MediaItem>) {
        if (searchCache.size >= MAX_CACHED_SEARCHES) searchCache.clear()
        searchCache[query] = results
    }

    /**
     * A voice request ("play X", or "play something" with no stored session) that found nothing:
     * shown as the player's error, and the request fails. An empty answer would not do: Media3
     * would still prepare and play, resuming whatever was loaded instead.
     */
    private fun noVoiceMatch(kind: PlaybackErrorKind): Nothing {
        graph.player.noteFailure(kind, null)
        throw UnsupportedOperationException("Nothing matches the voice request")
    }

    /**
     * The downloads a `dl|` item (a song or episode the Downloads tab lists on its own) plays
     * with: its section of the tab, newest first, as the app's Downloads screen plays it.
     */
    private fun downloadedQueue(startUri: String): List<String> =
        OfflineTree.singles(graph.downloads.downloadedUris.value, downloadedCollections).sectionOf(startUri)

    private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE || (page <= 0 && pageSize >= size)) return ImmutableList.copyOf(this)
        val from = page.toLong() * pageSize
        if (from >= size) return ImmutableList.of()
        return ImmutableList.copyOf(subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt()))
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** The root's tabs (Home, Library, Downloads, Browse), for the children-changed hint. */
        private const val ROOT_TABS = 4

        /** Start (while the app is visible) to bring up the opt-in Connect presence. */
        const val ACTION_START_PRESENCE = "com.taehagen.spotifygood.playback.START_PRESENCE"
        /**
         * Boolean extra of an [ACTION_START_PRESENCE] sent with `startForegroundService` (the
         * restore after a reboot or an update, [PresenceRestore]): the service reaches the
         * foreground even when presence cannot come up.
         */
        const val EXTRA_FOREGROUND_START = "com.taehagen.spotifygood.playback.extra.FOREGROUND_START"
        /** "Tap to resume" after a refused background start (forwarded by [PlaybackActionReceiver]). */
        const val ACTION_RESUME = "com.taehagen.spotifygood.playback.RESUME"
        /**
         * Sent with `startForegroundService` by [PlaybackCoordinator] when local audio starts
         * while the app is in the background and the service is not running.
         */
        const val ACTION_LOCAL_PLAYBACK = "com.taehagen.spotifygood.playback.LOCAL_PLAYBACK"
        /** Boolean extra on the session activity intent: open the Now Playing screen. */
        const val EXTRA_OPEN_PLAYER = "com.taehagen.spotifygood.extra.OPEN_PLAYER"

        private const val REQUEST_SESSION = 1
        private const val REQUEST_SIGN_IN = 3
        /** Well inside the system's startForeground deadline (5–10 s). */
        private const val FOREGROUND_DEADLINE_MS = 3_000L
        /** Media3's notification custom actions (`DefaultActionFactory`). */
        private const val MEDIA3_CUSTOM_ACTION = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
        /** Media3's start-self intent extra (Media3 calls startForeground right after that start). */
        private const val MEDIA3_START_SELF_EXTRA = "androidx.media3.session.intent.uid"
        /** The momentary foreground of [satisfyForegroundContract] (not the media notification's id). */
        private const val CONTRACT_NOTIFICATION_ID = 1090
        private const val RESUME_SAVE_INTERVAL_MS = 15_000L
        /** How long the session is held after a browser's last catalog request. */
        private const val BROWSE_HOLD_MS = 60_000L
        private const val LOGIN_WAIT_MS = 3_000L
        private const val MAX_CACHED_SEARCHES = 8

        /** Media controllers we fully trust besides Media3's own trust checks. */
        private val TRUSTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.bluetooth",
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.google.android.wearable.app",
            "com.google.android.apps.wear.companion",
            "com.google.android.apps.automotive.media",
        )

        private const val EXTRA_TOKEN = "com.taehagen.spotifygood.playback.extra.TOKEN"
        private val INTERNAL_ACTIONS = setOf(ACTION_START_PRESENCE, ACTION_RESUME, ACTION_LOCAL_PLAYBACK)

        /**
         * Unguessable per-process proof that an intent comes from this app: the service must be
         * exported, and a service cannot see who started it. Intents that outlive the process
         * (notification actions) go through the non-exported [PlaybackActionReceiver] instead.
         */
        private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** An intent for one of our own actions ([ACTION_START_PRESENCE], [ACTION_RESUME], [ACTION_LOCAL_PLAYBACK]). */
        internal fun internalIntent(context: Context, action: String?): Intent =
            Intent(context, PlaybackService::class.java).setAction(action).putExtra(EXTRA_TOKEN, token)

        /** True while Media3 keeps the service in the foreground with the media notification. */
        @Volatile var isMediaForeground: Boolean = false
            private set

        /** True between [onCreate] and [onDestroy]. */
        @Volatile var isRunning: Boolean = false
            private set

        @Volatile private var current: WeakReference<PlaybackService>? = null

        /** The paused-idle alarm fired ([PausedIdleAlarmReceiver], main thread). */
        internal fun onPausedIdleAlarm() {
            current?.get()?.updatePausedIdle()
        }

        /** True while the presence notification is the foreground notification. */
        @Volatile var isPresenceForeground: Boolean = false
            internal set
    }
}
