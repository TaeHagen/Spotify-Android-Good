package com.taehagen.spotifygood.playback

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.text.NumberFormat
import kotlin.math.abs

private val Context.speedDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_speed")

/** Spotify's podcast playback speeds (0.5× to 3.5×) and the rules around them. */
internal object PodcastSpeeds {
    const val MIN = 0.5f
    const val MAX = 3.5f
    const val NORMAL = 1f

    /** The choices Spotify offers. */
    val STEPS: List<Float> = listOf(0.5f, 0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f, 3f, 3.5f)

    /** Within range, on 0.05 steps (a controller may send any value); NaN is normal speed. */
    fun clamp(speed: Float): Float {
        if (speed.isNaN()) return NORMAL
        val c = speed.coerceIn(MIN, MAX)
        return Math.round(c * 20f) / 20f
    }

    /**
     * The speed playback runs at: the chosen podcast speed for an episode played here (the
     * offline queue included), normal speed for music and while another device plays (Spotify
     * Connect has no speed command).
     */
    fun effective(chosen: Float, snapshot: PlaybackSnapshot): Float =
        if (appliesTo(snapshot)) chosen else NORMAL

    /** Whether the podcast speed applies to what [snapshot] plays (an episode, on this phone). */
    fun appliesTo(snapshot: PlaybackSnapshot): Boolean =
        snapshot.source == PlaybackSource.LOCAL && snapshot.track?.isEpisode == true

    /** "1×", "1.5×" (in the user's number format). */
    fun label(speed: Float): String {
        val format = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        return format.format(speed.toDouble()) + "×"
    }

    fun same(a: Float, b: Float): Boolean = abs(a - b) < 0.001f
}

/**
 * The podcast playback speed (docs/ARCHITECTURE.md §9.4): one global speed for every episode, as
 * Spotify keeps it, remembered across restarts. It is applied in the PCM sink (AudioTrack
 * `PlaybackParams`, pitch kept) and reported to the engine (`player.setSpeed`) whenever the
 * effective speed changes: at a change of the chosen speed, and when playback moves between an
 * episode and music or between this phone and another device. The engine's position stays media
 * time (the decoder is throttled by the sink), and its snapshots and Spotify Connect state carry
 * the speed, so positions extrapolate at the real rate here and on the other clients.
 */
class PodcastSpeed internal constructor(
    private val scope: CoroutineScope,
    snapshots: Flow<PlaybackSnapshot>,
    private val store: DataStore<Preferences>,
    /** Applies a speed to the sink. */
    private val applyToSink: (Float) -> Unit,
    /** Reports a speed to the engine (`player.setSpeed`); it starts at normal speed. */
    private val report: (Float) -> Unit,
) {
    constructor(context: Context, scope: CoroutineScope, playback: PlaybackRepository, sink: AudioSinkBridge, rpc: NativeRpc) : this(
        scope,
        playback.snapshot,
        context.applicationContext.speedDataStore,
        sink::setPlaybackSpeed,
        { speed -> rpc.fire("player.setSpeed", buildJsonObject { put("speed", speed.toDouble()) }) },
    )

    /** The speed last reported to the engine (it starts at normal speed). Collector only. */
    private var reported = PodcastSpeeds.NORMAL
    @Volatile private var chosenByUser = false

    private val _speed = MutableStateFlow(PodcastSpeeds.NORMAL)

    /** The chosen podcast speed. */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    init {
        scope.launch {
            try {
                val stored = store.data.first()[KEY]
                if (stored != null && !chosenByUser) _speed.value = PodcastSpeeds.clamp(stored)
            } catch (e: IOException) {
                warn("Cannot read the podcast speed", e)
            }
        }
        scope.launch {
            combine(snapshots.map(PodcastSpeeds::appliesTo).distinctUntilChanged(), _speed) { episode, chosen ->
                if (episode) chosen else PodcastSpeeds.NORMAL
            }.distinctUntilChanged().collect { speed ->
                applyToSink(speed)
                if (!PodcastSpeeds.same(speed, reported)) {
                    reported = speed
                    report(speed)
                }
            }
        }
    }

    /** Chooses [speed] for all episodes (clamped, see [PodcastSpeeds.clamp]). Any thread. */
    fun set(speed: Float) {
        val s = PodcastSpeeds.clamp(speed)
        chosenByUser = true
        if (PodcastSpeeds.same(s, _speed.value)) return
        _speed.value = s
        scope.launch {
            try {
                store.edit { it[KEY] = s }
            } catch (e: IOException) {
                warn("Cannot save the podcast speed", e)
            }
        }
    }

    private fun warn(message: String, e: Throwable) {
        try {
            Log.w(TAG, message, e)
        } catch (_: RuntimeException) {
            // JVM tests: android.util.Log is not available.
        }
    }

    private companion object {
        const val TAG = "PodcastSpeed"
        val KEY = floatPreferencesKey("podcast")
    }
}
