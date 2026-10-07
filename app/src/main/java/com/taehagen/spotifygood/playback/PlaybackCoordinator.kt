package com.taehagen.spotifygood.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.Clock
import androidx.media3.common.util.WakeLockManager
import androidx.media3.common.util.WifiLockManager
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-wide glue between the native audio sink and the Android audio policy
 * (docs/ARCHITECTURE.md §9.4–§9.6). It is the [AudioSinkBridge.Listener]:
 * * sink started (local audio audible) → request audio focus, register the becoming-noisy
 *   receiver, hold wake + Wi-Fi locks;
 * * sink stopped → release locks, unregister the receiver, abandon focus after
 *   [AudioFocusController.RESUME_WINDOW_MS] (or immediately on stop / remote playback / engine stop);
 * * mixer volume → `STREAM_MUSIC` via [VolumeSync], which also observes the system volume while the
 *   engine runs;
 * * the system equalizer's audio-effect session is opened when local audio first starts and
 *   closed when the engine stops or the playback service is destroyed (not on pauses).
 *
 * It also makes sure the [PlaybackService] runs when playback is requested while the app is
 * visible, and starts the opt-in Connect presence while the app is visible.
 *
 * Local audio never plays without the service's media foreground (docs §9.4, §10). When it starts
 * while the app is in the background and the service is not running — a remote "play on this
 * phone" during the idle grace or a download — the service is started with
 * `startForegroundService` (allowed before Android 12, and later while another foreground service
 * such as a download runs); audio focus waits until it is in the foreground (Android 15 refuses
 * focus to background apps). If that start is refused or the foreground is not reached in time,
 * playback is paused (Connect sees it) and a "Tap to resume" alert is posted ([ResumeAlert]).
 *
 * Installed once ([install]) by the playback service, the UI connector or — as a last resort — the
 * audio sink itself. Holds only the application context.
 */
class PlaybackCoordinator private constructor(private val app: App) : AudioSinkBridge.Listener {
    private val graph: AppGraph = app.graph
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Connect volume ↔ STREAM_MUSIC. */
    val volumeSync = VolumeSync(app, graph.rpc, graph.playback)

    /** Engine / downloads knowledge of [PlayerController] (cold starts, offline context loads). */
    val environment: PlaybackEnvironment = AppPlaybackEnvironment(graph)

    init {
        // Right away (not in initOnMain): the first command may be queued before that runs.
        graph.player.environment = environment
    }

    private val focus = AudioFocusController(
        app,
        object : AudioFocusController.Callbacks {
            override fun pauseForFocus() = graph.player.pause()
            override fun resumeAfterFocus() = graph.player.resume()
            override fun setDuck(ducked: Boolean) =
                graph.audioSink.setDuckVolume(if (ducked) AudioFocusController.DUCK_VOLUME else 1f)
            override fun isSpeech(): Boolean = graph.playback.snapshot.value.track?.isEpisode == true
            override fun isPlayingLocally(): Boolean = sinkActive || graph.playback.snapshot.value.isLocallyActive()
        },
    )
    private val noisy = BecomingNoisyReceiver(app) { graph.player.pause() }
    private val wakeLock = WakeLockManager(app, Looper.getMainLooper(), Clock.DEFAULT).apply { setEnabled(true) }
    private val wifiLock = WifiLockManager(app, Looper.getMainLooper(), Clock.DEFAULT).apply { setEnabled(true) }

    @Volatile private var sinkActive = false
    private val foreground = MutableStateFlow(false)

    /** Visibility of the app (ProcessLifecycleOwner STARTED). */
    val appVisible: StateFlow<Boolean> = foreground.asStateFlow()

    /**
     * Main thread. Local audio started in the background and the service is not in the foreground
     * yet (we just started it, or it runs without a foreground): no second start, and audio focus is
     * requested only once it is in the foreground (Android 15 refuses focus to background apps).
     */
    private var awaitingForeground = false
    /** Audio focus is held back until the service is in the foreground ([awaitingForeground]). */
    private var focusAfterForeground = false
    /** Local playback of this activation was paused for want of a foreground service. */
    private var refusedThisActivation = false
    private val foregroundTimeout = Runnable {
        if (awaitingForeground && isPlayingLocally()) {
            Log.w(TAG, "Playback service did not reach the foreground; pausing local playback")
            refuseBackgroundPlayback()
        }
        awaitingForeground = false
        focusAfterForeground = false
    }

    /** The system audio-effect (equalizer) control session is open. Main thread. */
    private var effectSessionOpen = false

    /** True while any activity of the app is started (ProcessLifecycleOwner). */
    val isAppInForeground: Boolean get() = foreground.value

    private val abandonFocus = Runnable {
        if (!sinkActive && !graph.playback.snapshot.value.isLocallyActive()) focus.abandon()
    }

    private fun initOnMain() {
        graph.player.onPlaybackRequested = ::ensureServiceStarted
        graph.player.errorMessages = PlaybackErrorMessages.fromResources(app)
        graph.sleepTimer.fader = { graph.audioSink.setFadeVolume(it) }
        graph.devices.onTransferToThisDevice = ::ensureServiceStarted
        // Starts/stops with the engine (AppGraph wires it) and reports the output to Connect.
        graph.outputs

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.value = false
            }
        })

        scope.launch {
            graph.engine.isRunning.collect { running ->
                if (running) {
                    volumeSync.start()
                } else {
                    volumeSync.stop()
                    onEngineStopped()
                }
            }
        }
        scope.launch {
            graph.playback.snapshot.collect(::onSnapshot)
        }
        scope.launch {
            // Logging out cancels a pending sleep timer.
            graph.engine.isLoggedIn.drop(1).filter { !it }.collect { graph.sleepTimer.cancel() }
        }
        scope.launch {
            // Opt-in Connect presence must be started while the app is visible (FGS start rules).
            combine(
                graph.settings.settings.map { it.connectPresence }.distinctUntilChanged(),
                foreground,
                graph.engine.isLoggedIn,
            ) { presence, visible, loggedIn -> presence && visible && loggedIn }
                .distinctUntilChanged()
                .collect { wanted ->
                    if (wanted && !PlaybackService.isPresenceForeground) startService(PlaybackService.internalIntent(app, PlaybackService.ACTION_START_PRESENCE))
                }
        }
    }

    // ---- AudioSinkBridge.Listener ------------------------------------------------------------

    override fun onSinkStarted() {
        sinkActive = true
        main.post(::onLocalAudioStarted)
    }

    override fun onSinkStopped() {
        sinkActive = false
        main.post(::onLocalAudioStopped)
    }

    override fun onRoutedDeviceChanged(device: AudioDeviceInfo?) {
        // STREAM_MUSIC indices are stored per output device.
        volumeSync.refresh()
    }

    override fun onConnectVolume(volume: Int) {
        volumeSync.applyConnectVolume(volume)
    }

    // ---- service --------------------------------------------------------------------------

    /**
     * Starts the [PlaybackService] (plain start; Media3 promotes it to foreground once playback is
     * ongoing) if it is not running and the app is visible — starting services from the background
     * is not allowed. Any thread.
     */
    fun ensureServiceStarted() {
        if (PlaybackService.isRunning || !isAppInForeground) return
        startService()
    }

    private fun startService(intent: Intent = PlaybackService.internalIntent(app, null)) {
        try {
            app.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start the playback service now", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the playback service", e)
        }
    }

    /**
     * Makes sure local audio that is (about to be) audible runs under the playback service. Returns
     * false when it must not play (paused, alert posted). Main thread.
     */
    private fun ensureServiceForLocalAudio(): Boolean {
        if (refusedThisActivation) return false
        if (awaitingForeground) return true
        if (PlaybackService.isRunning) {
            // Running but not in the foreground while the app is in the background (e.g. started for
            // "Tap to resume", or bound by Auto): Media3 asks for the foreground once the session
            // player reports playing; until then (bounded) no focus.
            if (!isAppInForeground && !PlaybackService.isMediaForeground && !PlaybackService.isPresenceForeground) awaitForeground()
            return true
        }
        if (isAppInForeground) {
            startService()
            return true
        }
        // In the background without the service: a plain start would be refused, and audio must
        // not play without the media foreground anyway.
        val started = try {
            ContextCompat.startForegroundService(app, PlaybackService.internalIntent(app, PlaybackService.ACTION_LOCAL_PLAYBACK))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "Cannot start the playback service from the background", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the playback service", e)
            false
        }
        if (!started) {
            refuseBackgroundPlayback()
            return false
        }
        Log.i(TAG, "Local playback started in the background; starting the playback service")
        awaitForeground()
        return true
    }

    private fun awaitForeground() {
        awaitingForeground = true
        main.removeCallbacks(foregroundTimeout)
        main.postDelayed(foregroundTimeout, FOREGROUND_TIMEOUT_MS)
    }

    /**
     * Local playback cannot run here without the media foreground service: pause it (so Connect
     * and the session stay consistent) and ask the user to resume. Main thread.
     */
    fun refuseBackgroundPlayback() {
        if (refusedThisActivation) return
        refusedThisActivation = true
        graph.player.pause()
        ResumeAlert.post(app, graph.playback.snapshot.value.track?.name)
    }

    /** Audio plays (or is about to) on this phone. */
    fun isPlayingLocally(): Boolean = sinkActive || graph.playback.snapshot.value.isLocallyActive()

    /** The playback service is in the media foreground. Main thread. */
    fun onServiceForeground() {
        main.removeCallbacks(foregroundTimeout)
        awaitingForeground = false
        if (focusAfterForeground) {
            focusAfterForeground = false
            if (sinkActive) focus.request()
        }
    }

    // ---- local audio ------------------------------------------------------------------------

    private fun onLocalAudioStarted() {
        if (!sinkActive) return
        main.removeCallbacks(abandonFocus)
        if (!ensureServiceForLocalAudio()) {
            updateLocks()
            return
        }
        if (awaitingForeground) {
            // Background apps without a foreground service get no focus (Android 15+).
            focusAfterForeground = true
        } else {
            focus.request()
        }
        noisy.register()
        updateLocks()
        openEffectSession()
    }

    private fun onLocalAudioStopped() {
        if (sinkActive) return
        noisy.unregister()
        updateLocks()
        main.removeCallbacks(abandonFocus)
        main.postDelayed(abandonFocus, AudioFocusController.RESUME_WINDOW_MS)
    }

    private fun onSnapshot(s: PlaybackSnapshot) {
        updateLocks()
        val stopped = s.source == PlaybackSource.REMOTE ||
            (s.status == PlaybackStatus.STOPPED && !sinkActive && !focus.isWaitingForGain)
        if (stopped) {
            // Nothing will play locally soon: give focus back right away.
            main.removeCallbacks(abandonFocus)
            focus.abandon()
            if (!sinkActive) noisy.unregister()
        }
        if (!s.isLocallyActive()) {
            // A new activation may try again (e.g. after "Tap to resume").
            refusedThisActivation = false
            if (!sinkActive) focusAfterForeground = false
        } else if (s.status == PlaybackStatus.PLAYING) {
            ensureServiceForLocalAudio()
        }
    }

    private fun onEngineStopped() {
        focusAfterForeground = false
        main.removeCallbacks(abandonFocus)
        focus.abandon()
        noisy.unregister()
        updateLocks()
        closeEffectSession() // the AudioTrack is released with the engine
    }

    // ---- system equalizer ---------------------------------------------------------------------

    /**
     * Lets equalizer / audio-effect apps attach to the (stable) AudioTrack session: opened once
     * when local audio starts, kept across pauses, closed by [closeEffectSession]. Main thread.
     */
    private fun openEffectSession() {
        if (effectSessionOpen) return
        effectSessionOpen = true
        sendEffectBroadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
    }

    /** Playback ended for good (engine stopped, playback service destroyed). Main thread. */
    fun closeEffectSession() {
        if (!effectSessionOpen) return
        effectSessionOpen = false
        sendEffectBroadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
    }

    private fun sendEffectBroadcast(action: String) {
        try {
            app.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, graph.audioSink.audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, app.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio effect session broadcast failed", e)
        }
    }

    /** Wake + Wi-Fi locks only while audio plays (or loads) locally (docs §1, §9.4). */
    private fun updateLocks() {
        val stayAwake = sinkActive || graph.playback.snapshot.value.isLocallyActive()
        wakeLock.setStayAwake(stayAwake)
        wifiLock.setStayAwake(stayAwake)
    }

    private fun PlaybackSnapshot.isLocallyActive(): Boolean =
        source == PlaybackSource.LOCAL && track != null &&
            (status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING)

    companion object {
        private const val TAG = "PlaybackCoordinator"
        /** Time for the service to reach the media foreground once local audio started in the background. */
        private const val FOREGROUND_TIMEOUT_MS = 5_000L

        @Volatile private var instance: PlaybackCoordinator? = null

        /** Installs (once) and returns the coordinator. Any thread; wiring happens on the main thread. */
        fun install(context: Context): PlaybackCoordinator {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext as App
                    PlaybackCoordinator(app).also { coordinator ->
                        instance = coordinator
                        app.graph.audioSink.listener = coordinator
                        Handler(Looper.getMainLooper()).post(coordinator::initOnMain)
                    }
                }
            }
        }

        /** The installed coordinator, if any. */
        val current: PlaybackCoordinator? get() = instance
    }
}
