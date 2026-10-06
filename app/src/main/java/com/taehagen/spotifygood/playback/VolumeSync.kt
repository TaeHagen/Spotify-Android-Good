package com.taehagen.spotifygood.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Conversions between Connect volume (0..65535), Android stream indices and percent. */
internal object VolumeMath {
    const val CONNECT_MAX = 65_535

    /** Nearest stream index for a Connect volume, clamped to `[minIndex, maxIndex]`. */
    fun connectToIndex(volume: Int, minIndex: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val index = (volume.coerceIn(0, CONNECT_MAX).toDouble() * maxIndex / CONNECT_MAX).roundToInt()
        return index.coerceIn(minIndex.coerceAtMost(maxIndex), maxIndex)
    }

    /** Connect volume of a stream index (exact inverse of [connectToIndex] on index values). */
    fun indexToConnect(index: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return (index.coerceIn(0, maxIndex).toDouble() * CONNECT_MAX / maxIndex).roundToInt().coerceIn(0, CONNECT_MAX)
    }

    fun connectToPercent(volume: Int): Int = (volume.coerceIn(0, CONNECT_MAX) * 100.0 / CONNECT_MAX).roundToInt()

    fun percentToConnect(percent: Int): Int = (percent.coerceIn(0, 100) * CONNECT_MAX / 100.0).roundToInt()
}

/**
 * Keeps the Connect volume of this device and Android's `STREAM_MUSIC` in sync
 * (docs/ARCHITECTURE.md §4.4, §9.6):
 * * mixer callbacks ([applyConnectVolume]) set the stream index without UI (`flags = 0`);
 * * hardware/system volume changes are observed (ContentObserver on `Settings.System` plus the
 *   `VOLUME_CHANGED_ACTION` broadcast) while [start]ed — i.e. while the engine runs — and reported
 *   with `player.setVolume {fromSystem:true}`, debounced, only while this phone is the active device.
 *
 * Changes we caused ourselves are recognised through a short-lived "expected index" so they are not
 * echoed back to Connect. All state lives on the main thread.
 */
class VolumeSync internal constructor(
    context: Context,
    private val rpc: NativeRpc,
    private val playback: PlaybackRepository,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    val maxIndex: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val minIndex: Int =
        if (Build.VERSION.SDK_INT >= 28) audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0

    private val _streamIndex = MutableStateFlow(readIndex())

    /** Current `STREAM_MUSIC` index (refreshed while started and on our own changes). */
    val streamIndex: StateFlow<Int> = _streamIndex.asStateFlow()

    private var started = false
    private var expectedIndex = -1
    private var expectedAtMs = 0L
    private var lastReportedIndex = -1

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = onSystemVolumeMaybeChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (stream == AudioManager.STREAM_MUSIC) onSystemVolumeMaybeChanged()
        }
    }

    private val report = Runnable { reportIndex(_streamIndex.value) }

    /** Registers the observers. Main thread; idempotent. */
    fun start() {
        if (started) return
        started = true
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lastReportedIndex = -1
        _streamIndex.value = readIndex()
    }

    /** Unregisters everything. Main thread; idempotent. */
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(report)
        appContext.contentResolver.unregisterContentObserver(observer)
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** librespot's mixer wants [volume] (0..65535). Any thread; never blocks the caller. */
    fun applyConnectVolume(volume: Int) {
        main.post {
            if (audioManager.isVolumeFixed) return@post
            val index = VolumeMath.connectToIndex(volume, minIndex, maxIndex)
            if (index == readIndex()) return@post
            expectedIndex = index
            expectedAtMs = SystemClock.elapsedRealtime()
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            } catch (e: SecurityException) {
                // Do-not-disturb policy may forbid volume changes.
                Log.w(TAG, "setStreamVolume refused", e)
            }
            _streamIndex.value = readIndex()
            lastReportedIndex = _streamIndex.value
        }
    }

    /** Re-reads the index (e.g. after an output route change; the index is stored per device). */
    fun refresh() {
        main.post { onSystemVolumeMaybeChanged() }
    }

    private fun onSystemVolumeMaybeChanged() {
        val index = readIndex()
        if (index == _streamIndex.value && index == lastReportedIndex) return
        _streamIndex.value = index
        if (index == expectedIndex && SystemClock.elapsedRealtime() - expectedAtMs < EXPECTED_WINDOW_MS) {
            // Our own setStreamVolume echoing back.
            expectedIndex = -1
            lastReportedIndex = index
            return
        }
        if (!started) return
        main.removeCallbacks(report)
        main.postDelayed(report, DEBOUNCE_MS)
    }

    private fun reportIndex(index: Int) {
        if (!started || index == lastReportedIndex) return
        if (playback.snapshot.value.source != PlaybackSource.LOCAL) return
        lastReportedIndex = index
        rpc.fire(
            "player.setVolume",
            buildJsonObject {
                put("volume", VolumeMath.indexToConnect(index, maxIndex))
                put("fromSystem", true)
            },
        )
    }

    private fun readIndex(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private companion object {
        const val TAG = "VolumeSync"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val DEBOUNCE_MS = 150L
        const val EXPECTED_WINDOW_MS = 1_500L
    }
}
