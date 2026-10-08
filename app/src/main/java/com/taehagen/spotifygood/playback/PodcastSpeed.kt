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

    /**
     * The speeds to try for [speed] on an output that may refuse it: [speed], then the steps
     * between it and normal speed from the closest on, then normal speed. The first one the
     * output takes is the highest it supports up to [speed] (an AudioTrack needs more buffer the
     * faster it plays).
     */
    fun fallbacks(speed: Float): List<Float> = buildList {
        add(speed)
        when {
            speed > NORMAL -> STEPS.filter { it < speed && !same(it, speed) && it > NORMAL && !same(it, NORMAL) }.sortedDescending()
            speed < NORMAL -> STEPS.filter { it > speed && !same(it, speed) && it < NORMAL && !same(it, NORMAL) }.sorted()
            else -> emptyList()
        }.let(::addAll)
        if (!same(speed, NORMAL)) add(NORMAL)
    }

    /**
     * Whether the output is known to refuse [step]: [inEffect] fell back from [chosen], so every
     * speed from it toward [chosen] was refused ([fallbacks]), and one beyond [chosen] needs even
     * more. Nothing is known while the chosen speed plays.
     */
    fun refused(step: Float, chosen: Float, inEffect: Float): Boolean = when {
        same(chosen, inEffect) || same(step, inEffect) -> false
        chosen > inEffect -> step > inEffect
        else -> step < inEffect
    }
}

/**
 * The podcast playback speed (docs/ARCHITECTURE.md §9.4): one global speed for every episode, as
 * Spotify keeps it, remembered across restarts. It is applied in the PCM sink (AudioTrack
 * `PlaybackParams`, pitch kept) and reported to the engine (`player.setSpeed`) whenever the
 * effective speed changes: at a change of the chosen speed, and when playback moves between an
 * episode and music or between this phone and another device. The engine's position stays media
 * time (the decoder is throttled by the sink), and its snapshots and Spotify Connect state carry
 * the speed, so positions extrapolate at the real rate here and on the other clients.
 *
 * The sink decides: an output may refuse a speed (AudioTrack needs more buffer the faster it
 * plays, a Bluetooth output more than the speaker), and then plays the highest it takes below
 * it. The engine is told only the speed the sink plays at, also when it changes by itself (a new
 * track or output), and [inEffect] shows it; the chosen speed stays, and is tried again on every
 * new track and output.
 */
class PodcastSpeed internal constructor(
    private val scope: CoroutineScope,
    snapshots: Flow<PlaybackSnapshot>,
    private val store: DataStore<Preferences>,
    /** Applies a speed to the sink; returns the speed in effect (published on [sinkSpeed] too). */
    private val applyToSink: (Float) -> Float,
    /** The speed the sink plays at; it may change by itself (a new track or output checks it again). */
    private val sinkSpeed: StateFlow<Float>,
    /** Reports a speed to the engine (`player.setSpeed`); it starts at normal speed. */
    private val report: (Float) -> Unit,
) {
    constructor(context: Context, scope: CoroutineScope, playback: PlaybackRepository, sink: AudioSinkBridge, rpc: NativeRpc) : this(
        scope,
        playback.snapshot,
        context.applicationContext.speedDataStore,
        sink::setPlaybackSpeed,
        sink.speedInEffect,
        { speed -> rpc.fire("player.setSpeed", buildJsonObject { put("speed", speed.toDouble()) }) },
    )

    /** The speed last reported to the engine (it starts at normal speed). Collector only. */
    private var reported = PodcastSpeeds.NORMAL
    @Volatile private var chosenByUser = false

    private val _speed = MutableStateFlow(PodcastSpeeds.NORMAL)

    /** The chosen podcast speed. */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _inEffect = MutableStateFlow(PodcastSpeeds.NORMAL)

    /**
     * The speed an episode plays at here: while one plays, the speed the sink took (the chosen
     * one, or the highest the output takes below it); otherwise the chosen speed.
     */
    val inEffect: StateFlow<Float> = _inEffect.asStateFlow()

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
            // The target speed last applied to the sink.
            var applied: Float? = null
            combine(snapshots.map(PodcastSpeeds::appliesTo).distinctUntilChanged(), _speed, sinkSpeed) { episode, chosen, _ ->
                episode to chosen
            }.collect { (episode, chosen) ->
                val target = if (episode) chosen else PodcastSpeeds.NORMAL
                val playing = if (applied?.let { PodcastSpeeds.same(it, target) } != true) {
                    applied = target
                    applyToSink(target)
                } else {
                    // The sink changed by itself: what it plays at now (never an older value).
                    sinkSpeed.value
                }
                _inEffect.value = if (episode) playing else chosen
                if (!PodcastSpeeds.same(playing, reported)) {
                    reported = playing
                    report(playing)
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
