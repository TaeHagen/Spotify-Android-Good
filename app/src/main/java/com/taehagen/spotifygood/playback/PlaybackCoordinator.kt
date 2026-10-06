package com.taehagen.spotifygood.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.util.Log
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
                .collect { wanted -> if (wanted && !PlaybackService.isPresenceForeground) startService(PlaybackService.ACTION_START_PRESENCE) }
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
        startService(null)
    }

    private fun startService(action: String?) {
        try {
            app.startService(Intent(app, PlaybackService::class.java).setAction(action))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start the playback service now", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the playback service", e)
        }
    }

    // ---- local audio ------------------------------------------------------------------------

    private fun onLocalAudioStarted() {
        if (!sinkActive) return
        main.removeCallbacks(abandonFocus)
        focus.request()
        noisy.register()
        updateLocks()
        ensureServiceStarted()
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
        if (s.isLocallyActive() && s.status == PlaybackStatus.PLAYING) ensureServiceStarted()
    }

    private fun onEngineStopped() {
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
