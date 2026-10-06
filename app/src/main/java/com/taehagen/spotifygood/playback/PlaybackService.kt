package com.taehagen.spotifygood.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
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
import androidx.media3.common.Rating
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
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
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.engine.EngineHolder
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.model.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Holds the [HolderType.PLAYBACK] engine holder from [onCreate] to [onDestroy].
 */
class PlaybackService : MediaLibraryService() {
    private lateinit var graph: AppGraph
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var player: SpotifyPlayer
    private lateinit var tree: LibraryTree
    private lateinit var resumeStore: ResumeStore
    private lateinit var presence: PresenceController
    private var session: MediaLibrarySession? = null
    private var playbackHolder: EngineHolder? = null

    /** Media3 currently keeps the service foreground with the media notification. */
    private var mediaForeground = false
    private var buttons: List<CommandButton> = emptyList()
    private var resumeAlertPosted = false
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        graph = (application as App).graph
        coordinator = PlaybackCoordinator.install(this)
        playbackHolder = graph.engine.acquire(HolderType.PLAYBACK)
        resumeStore = graph.resumeStore
        tree = LibraryTree(this, graph)
        presence = PresenceController(this, graph)
        player = SpotifyPlayer(
            context = this,
            playback = graph.playback,
            controller = graph.player,
            devices = graph.devices,
            volume = coordinator.volumeSync,
            audioSessionId = graph.audioSink.audioSessionId,
            downloadedUris = { graph.downloads.downloadedUris.value.toList() },
        )

        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(Notifications.CHANNEL_PLAYBACK)
            .setChannelName(R.string.playback_channel_name)
            .setNotificationId(Notifications.ID_PLAYBACK)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(PresenceAwareNotificationProvider(provider))

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .apply { sessionActivity()?.let(::setSessionActivity) }
            .setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this, lifecycleScope)))
            .build()

        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() = onForegroundStartNotAllowed()
        })

        observeState()
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PRESENCE -> {
                if (isPresenceWanted()) presence.enable(showNow = !mediaForeground)
                if (!presence.isEnabled && !mediaForeground && !player.isPlaying) stopSelf(startId)
            }
            ACTION_STOP_PRESENCE -> {
                lifecycleScope.launch {
                    runCatching { graph.settings.update { it.copy(connectPresence = false) } }
                        .onFailure { Log.w(TAG, "Cannot turn off Connect presence", it) }
                }
                if (presence.disable()) afterPresenceDisabled()
            }
            ACTION_RESUME -> {
                cancelResumeAlert()
                // Through the session player, so Media3 goes foreground right away (the tap's
                // temporary allowlist is short) instead of waiting for the engine's snapshot.
                if (player.mediaItemCount > 0) player.play() else graph.player.resume()
            }
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
        val idle = !startInForegroundRequired || session.player.currentTimeline.isEmpty
        if (presence.isEnabled && idle && (presence.isForeground || presence.showForeground())) {
            mediaForeground = false
            return Futures.immediateFuture(null)
        }
        val future = super.onUpdateNotificationAsync(session, startInForegroundRequired)
        future.addListener(
            {
                val succeeded = runCatching { future.get() }.isSuccess
                if (!succeeded) return@addListener
                mediaForeground = startInForegroundRequired
                if (startInForegroundRequired) {
                    presence.onMediaForeground()
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
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        presence.release()
        clearListener()
        session?.release()
        session = null
        player.release()
        playbackHolder?.release()
        playbackHolder = null
        super.onDestroy()
    }

    // ---- state observation ------------------------------------------------------------------

    private fun observeState() {
        val playback = graph.playback
        lifecycleScope.launch {
            merge(
                playback.snapshot.map { },
                graph.devices.devices.map { },
                coordinator.volumeSync.streamIndex.map { },
            ).collect { player.refresh() }
        }
        lifecycleScope.launch {
            playback.isPlaying.filter { it }.collect { if (resumeAlertPosted) cancelResumeAlert() }
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
                )
            }.distinctUntilChanged().collect { state ->
                buttons = PlaybackSessionCommands.buttons(this@PlaybackService, state)
                session?.setMediaButtonPreferences(buttons)
            }
        }
        lifecycleScope.launch {
            // Resume state: on every relevant change (pause, seek, track) and every 15 s while
            // playing; the periodic part only exists while playing (transformLatest cancels it).
            playback.snapshot
                .filter { it.source == PlaybackSource.LOCAL && it.track != null }
                .distinctUntilChanged { a, b ->
                    a.track?.uri == b.track?.uri && a.context?.uri == b.context?.uri &&
                        a.status == b.status && a.positionMs == b.positionMs
                }
                .transformLatest { s ->
                    emit(ResumeState.from(s))
                    if (s.isPlaying) {
                        while (true) {
                            delay(RESUME_SAVE_INTERVAL_MS)
                            emit(ResumeState.from(s))
                        }
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { resumeStore.save(it) }
        }
        lifecycleScope.launch {
            // Logging out forgets what to resume.
            graph.engine.isLoggedIn.drop(1).filter { !it }.collect { resumeStore.clear() }
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
                }
        }
    }

    /** Liked state of the current track (null while unknown). */
    private fun likedState(): Flow<Boolean?> = graph.playback.currentTrack
        .map { it?.uri }
        .distinctUntilChanged()
        .flatMapLatest { uri ->
            if (uri == null) {
                flowOf(null)
            } else {
                runCatching { graph.library.isSaved(uri) }.getOrElse { flowOf(false) }
                    .map<Boolean, Boolean?> { it }
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
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_PLAYER, true)
        return PendingIntent.getActivity(this, REQUEST_SESSION, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Media3 could not start the foreground service from the background (API 31+). */
    private fun onForegroundStartNotAllowed() {
        Log.w(TAG, "Foreground service start not allowed; asking the user to resume")
        // Keep Connect and the session consistent: we cannot play without a foreground service.
        graph.player.pause()
        postResumeAlert()
    }

    private fun postResumeAlert() {
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) return
        if (notifications.getNotificationChannelCompat(Notifications.CHANNEL_ALERTS) == null) {
            notifications.createNotificationChannel(
                NotificationChannelCompat.Builder(Notifications.CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(getString(R.string.playback_alerts_channel_name))
                    .build(),
            )
        }
        val resume = PendingIntent.getService(
            this,
            REQUEST_RESUME,
            Intent(this, PlaybackService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val track = graph.playback.snapshot.value.track
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.name ?: getString(R.string.playback_resume_title))
            .setContentText(getString(R.string.playback_resume_text))
            .setContentIntent(resume)
            .addAction(R.drawable.pb_ic_play, getString(R.string.playback_resume_text), resume)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            notifications.notify(Notifications.ID_RESUME_ALERT, notification)
            resumeAlertPosted = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post the resume notification", e)
        }
    }

    private fun cancelResumeAlert() {
        resumeAlertPosted = false
        NotificationManagerCompat.from(this).cancel(Notifications.ID_RESUME_ALERT)
    }

    /**
     * A media-button start (`startForegroundService`) must reach the foreground even when nothing
     * can be resumed; enter and leave it immediately (like Media3's own shutdown path).
     */
    private fun satisfyForegroundContract() {
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
            ServiceCompat.startForeground(
                this,
                Notifications.ID_PLAYBACK,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground not allowed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground refused", e)
        }
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
            val builder = ConnectionResult.AcceptedResultBuilder(session, controller)
            if (isTrusted(session, controller)) {
                val commands = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                PlaybackSessionCommands.ALL.forEach { commands.add(it) }
                builder.setAvailableSessionCommands(commands.build())
                    .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .setMediaButtonPreferences(buttons)
            }
            // Others (unknown third-party apps) get Media3's read-only defaults.
            return Futures.immediateFuture(builder.build())
        }

        private fun isTrusted(session: MediaSession, controller: ControllerInfo): Boolean =
            controller.isTrusted ||
                session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller) ||
                controller.packageName in TRUSTED_PACKAGES ||
                controller.connectionHints.getString(MediaSessionService.CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE) ==
                Intent.ACTION_MEDIA_BUTTON

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
            val children = tree.children(parentId, params)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(children.page(page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            lifecycleScope.launch(Dispatchers.Default) {
                val results = try {
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
            val first = mediaItems.firstOrNull()
            val query = first?.requestMetadata?.searchQuery
            if (mediaItems.size == 1 && query != null) {
                // Voice: "play X" (empty query = "play something": resume the last context).
                if (query.isBlank()) {
                    val last = resumeStore.read()
                        ?: return@future MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                    MediaItemsWithStartPosition(listOf(tree.resumeItem(last)), 0, last.positionMs)
                } else {
                    val item = runCatching { tree.resolveVoiceQuery(query, first.requestMetadata.extras) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    MediaItemsWithStartPosition(listOfNotNull(item), 0, C.TIME_UNSET)
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
            val last = resumeStore.read()
            val loggedIn = !isForPlayback || graph.engine.isLoggedIn.value ||
                withTimeoutOrNull(LOGIN_WAIT_MS) { graph.engine.isLoggedIn.first { it } } == true
            if (last == null || !loggedIn) {
                if (isForPlayback) satisfyForegroundContract()
                throw UnsupportedOperationException("Nothing to resume")
            }
            MediaItemsWithStartPosition(listOf(tree.resumeItem(last)), 0, last.positionMs)
        }
    }

    private fun cacheSearch(query: String, results: List<MediaItem>) {
        if (searchCache.size >= MAX_CACHED_SEARCHES) searchCache.clear()
        searchCache[query] = results
    }

    private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE || (page <= 0 && pageSize >= size)) return ImmutableList.copyOf(this)
        val from = page.toLong() * pageSize
        if (from >= size) return ImmutableList.of()
        return ImmutableList.copyOf(subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt()))
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** Start (while the app is visible) to bring up the opt-in Connect presence. */
        const val ACTION_START_PRESENCE = "com.taehagen.spotifygood.playback.START_PRESENCE"
        /** "Stop" action of the presence notification: turns the setting off. */
        const val ACTION_STOP_PRESENCE = "com.taehagen.spotifygood.playback.STOP_PRESENCE"
        /** "Tap to resume" after a refused background start. */
        const val ACTION_RESUME = "com.taehagen.spotifygood.playback.RESUME"
        /** Boolean extra on the session activity intent: open the Now Playing screen. */
        const val EXTRA_OPEN_PLAYER = "com.taehagen.spotifygood.extra.OPEN_PLAYER"

        private const val REQUEST_SESSION = 1
        private const val REQUEST_RESUME = 2
        private const val RESUME_SAVE_INTERVAL_MS = 15_000L
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

        /** True between [onCreate] and [onDestroy]. */
        @Volatile var isRunning: Boolean = false
            private set

        /** True while the presence notification is the foreground notification. */
        @Volatile var isPresenceForeground: Boolean = false
            internal set
    }
}
