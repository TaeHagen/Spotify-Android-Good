package com.taehagen.spotifygood.nativebridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.taehagen.spotifygood.playback.PlaybackCoordinator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * PCM output for the native librespot player (docs/ARCHITECTURE.md §3.2, §4.4).
 *
 * [start], [stop] and [write] are called only on the librespot player thread. Everything else
 * may be called from any thread. librespot treats a failing start/stop as fatal, so those never
 * throw; [write] reports fatal errors with -1 (the player then pauses).
 */
class AudioSinkBridge(context: Context) {

    interface Listener {
        /** The sink started playing audio (local playback is audible). Player thread. */
        fun onSinkStarted() {}

        /** The sink paused (librespot pause/stop or idle watchdog). Player/watchdog thread. */
        fun onSinkStopped() {}

        /** The routed output device changed (Bluetooth connected, headphones unplugged, ...). Main thread. */
        fun onRoutedDeviceChanged(device: AudioDeviceInfo?) {}

        /** librespot's mixer wants this Connect volume (0..65535) applied. Any thread; must not block. */
        fun onConnectVolume(volume: Int) {}
    }

    private val appContext: Context = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Shared with native code (address taken once at init). Float32 stereo frames. */
    @JvmField
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_FRAMES * BYTES_PER_FRAME).order(ByteOrder.nativeOrder())

    /** Stable session id so the system equalizer / audio effects survive track recreation. */
    val audioSessionId: Int = audioManager.generateAudioSessionId()

    @Volatile var listener: Listener? = null

    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var started = false
    @Volatile private var preferredDevice: AudioDeviceInfo? = null
    /** Playback speed (podcasts), pitch kept; also applied to tracks created later. */
    @Volatile private var speed = 1f
    @Volatile private var trackVolume = 1f
    @Volatile private var duckVolume = 1f
    @Volatile private var fadeVolume = 1f
    private val routingListeners = CopyOnWriteArraySet<(AudioDeviceInfo?) -> Unit>()
    @Volatile private var lastWriteMs = 0L

    private var watchdogThread: HandlerThread? = null
    private var watchdog: Handler? = null

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        val device = router.routedDevice
        listener?.onRoutedDeviceChanged(device)
        routingListeners.forEach { it(device) }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!started) return
            val idleFor = SystemClock.elapsedRealtime() - lastWriteMs
            if (idleFor >= IDLE_PAUSE_MS) {
                // librespot keeps the sink "running" after the last track ends; do not keep the
                // audio hardware (and the device) awake playing silence.
                Log.d(TAG, "No audio for ${idleFor}ms, pausing AudioTrack")
                synchronized(lock) { track?.let { runCatching { it.pause(); it.flush() } } }
                started = false
                listener?.onSinkStopped()
            } else {
                watchdog?.postDelayed(this, IDLE_CHECK_MS)
            }
        }
    }

    // ---- called by native (player thread) -------------------------------------------------

    /** Sink::start. Never throws. */
    fun start(): Boolean {
        ensureListener()
        return try {
            val t = synchronized(lock) { track ?: createTrack().also { track = it } }
            t.play()
            started = true
            lastWriteMs = SystemClock.elapsedRealtime()
            ensureWatchdog().apply {
                removeCallbacks(idleCheck)
                postDelayed(idleCheck, IDLE_CHECK_MS)
            }
            listener?.onSinkStarted()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack start failed", t)
            false
        }
    }

    /** Sink::stop: instant pause (drops at most one buffer of audio). Never throws. */
    fun stop() {
        try {
            started = false
            watchdog?.removeCallbacks(idleCheck)
            synchronized(lock) { track?.let { it.pause(); it.flush() } }
            listener?.onSinkStopped()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack stop failed", t)
        }
    }

    /** Blocking write of [frames] frames from [buffer]. Returns frames written or -1. */
    fun write(frames: Int): Int {
        if (frames <= 0) return 0
        val bytes = frames * BYTES_PER_FRAME
        lastWriteMs = SystemClock.elapsedRealtime()
        if (!started) {
            // The idle watchdog paused us while librespot kept writing (e.g. very long gap); resume.
            if (!start()) return -1
        }
        for (attempt in 0..1) {
            val t = track ?: synchronized(lock) { track ?: createTrack().also { track = it } }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
            buffer.position(0).limit(bytes)
            var written = 0
            while (written < bytes) {
                val n = t.write(buffer, bytes - written, AudioTrack.WRITE_BLOCKING)
                if (n < 0) break
                if (n == 0) {
                    // Track paused/stopped underneath us (e.g. stop() raced); give up this packet.
                    return frames
                }
                written += n
            }
            if (written >= bytes) return frames
            // ERROR_DEAD_OBJECT (route change, audio server restart) or invalid state: recreate once.
            Log.w(TAG, "AudioTrack write failed, recreating track")
            synchronized(lock) {
                track?.let { releaseTrack(it) }
                track = null
            }
        }
        return -1
    }

    /** AndroidMixer::set_volume. Never blocks. */
    fun onVolume(volume: Int) {
        listener?.onConnectVolume(volume)
    }

    // ---- app side -------------------------------------------------------------------------

    /** Route to a specific output (null = system default). Applied immediately and to future tracks. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        synchronized(lock) { track?.preferredDevice = device }
    }

    /**
     * Playback speed (podcasts, 0.5..3.5) with the pitch kept: the track consumes PCM faster or
     * slower, and the blocking [write] throttles the decoder to it, so librespot's position stays
     * media time. Applied immediately (also to the audio already buffered) and to future tracks
     * (a track recreated after a dead object or a route change keeps it). Any thread.
     */
    fun setPlaybackSpeed(value: Float) {
        if (value == speed) return
        speed = value
        synchronized(lock) { track?.let(::applySpeed) }
    }

    private fun applySpeed(t: AudioTrack) {
        try {
            t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Playback speed $speed not supported", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Playback speed not applied", e)
        }
    }

    fun routedDevice(): AudioDeviceInfo? = track?.routedDevice

    /**
     * Base per-track gain (system volume is untouched). The effective AudioTrack gain is
     * `trackVolume * duckVolume * fadeVolume`, so ducking (audio focus) and fades (sleep timer)
     * can overlap without clobbering each other.
     */
    fun setTrackVolume(volume: Float) {
        trackVolume = volume.coerceIn(0f, 1f)
        applyGain()
    }

    /** Audio-focus ducking gain (1 = not ducked). */
    fun setDuckVolume(volume: Float) {
        duckVolume = volume.coerceIn(0f, 1f)
        applyGain()
    }

    /** Fade gain (sleep timer; 1 = no fade). */
    fun setFadeVolume(volume: Float) {
        fadeVolume = volume.coerceIn(0f, 1f)
        applyGain()
    }

    /** Additional observers of [AudioRouting] changes (main thread), e.g. the output route manager. */
    fun addRoutingListener(listener: (AudioDeviceInfo?) -> Unit) {
        routingListeners += listener
    }

    fun removeRoutingListener(listener: (AudioDeviceInfo?) -> Unit) {
        routingListeners -= listener
    }

    private fun effectiveGain(): Float = trackVolume * duckVolume * fadeVolume

    private fun applyGain() {
        synchronized(lock) { track?.setVolume(effectiveGain()) }
    }

    /**
     * The playback coordinator (audio focus, noisy receiver, locks, volume sync) is normally
     * installed by the playback service or the UI; if audio starts before either exists (e.g. a
     * Connect transfer while only a download holds the engine), install it now.
     */
    private fun ensureListener() {
        if (listener != null) return
        runCatching { PlaybackCoordinator.install(appContext) }
            .onFailure { Log.w(TAG, "Could not install the playback coordinator", it) }
    }

    val isPlaying: Boolean get() = started

    /** Releases the AudioTrack (engine stop / logout). The next start() recreates it. */
    fun release() {
        started = false
        watchdog?.removeCallbacks(idleCheck)
        synchronized(lock) {
            track?.let { releaseTrack(it) }
            track = null
        }
        watchdogThread?.quitSafely()
        watchdogThread = null
        watchdog = null
    }

    // ---- internals ------------------------------------------------------------------------

    private fun createTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // ~250 ms: enough to ride out scheduling hiccups, small enough for responsive pause/seek.
        val targetBytes = SAMPLE_RATE / 4 * BYTES_PER_FRAME
        val bufferBytes = maxOf(minBytes * 2, targetBytes)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setSessionId(audioSessionId)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
        t.preferredDevice = preferredDevice
        t.setVolume(effectiveGain())
        if (speed != 1f) applySpeed(t)
        t.addOnRoutingChangedListener(routingListener, android.os.Handler(android.os.Looper.getMainLooper()))
        return t
    }

    private fun releaseTrack(t: AudioTrack) {
        runCatching { t.removeOnRoutingChangedListener(routingListener) }
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }
    }

    private fun ensureWatchdog(): Handler {
        watchdog?.let { return it }
        val thread = HandlerThread("audio-sink-watchdog").also { it.start() }
        watchdogThread = thread
        return Handler(thread.looper).also { watchdog = it }
    }

    companion object {
        private const val TAG = "AudioSinkBridge"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 4 // float32
        const val BUFFER_FRAMES = 4096
        private const val IDLE_PAUSE_MS = 3_000L
        private const val IDLE_CHECK_MS = 1_000L
    }
}
