package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.Show
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

// Podcast resume points (docs §6.5). One point per episode, with the time it was learned: the
// wall time of this phone's last save, or the request time of the fresh answer that brought
// Spotify's. The newest wins. A fresh answer counts as news only when Spotify's state differs from
// the last one seen (nothing here is reported to Spotify, so its state otherwise lags this phone's
// progress), and an answer requested before the last one seen is ignored. Connect handoffs mark
// what the next fresh state means. Every play path resumes from the point.

/** Spotify's played state of an episode, as a fresh answer reported it. */
@Serializable
data class PlayedPoint(val positionMs: Long? = null, val fullyPlayed: Boolean? = null) {
    /** Partly played: a point worth resuming from. */
    val inProgress: Boolean get() = fullyPlayed != true && (positionMs ?: 0) > 0
}

/** Where to resume an episode ([positionMs]; 0 once [fullyPlayed]) and when that was learned ([at]). */
@Serializable
data class ResumePoint(val positionMs: Long, val fullyPlayed: Boolean, val at: Long)

/** What the next fresh Spotify state means ([observeSpotify]). */
@Serializable
enum class SpotifyRef {
    /** [SpotifySeen.state] is the last fresh state seen: a different one is news. */
    STATE,

    /** Playback was handed to this phone, which continues the other device's position: the next fresh state (that device's, older than this phone's progress) only becomes the reference. */
    ADOPT_NEXT,

    /** Another device played the episode: the next fresh state is news, whatever it is. */
    NEWER_NEXT,
}

/** The last fresh Spotify state seen for an episode (requested at [at]), or a Connect handoff mark. */
@Serializable
data class SpotifySeen(val state: PlayedPoint? = null, val at: Long, val ref: SpotifyRef = SpotifyRef.STATE)

/** What is kept for one episode: its resume [point] and what [seen] of Spotify's state. */
@Serializable
data class EpisodeResume(val point: ResumePoint? = null, val seen: SpotifySeen? = null)

/**
 * Spotify's played state carried by this episode (null when it carries none). Only a fresh answer
 * carries one: cached pages and download metadata are stripped ([withoutPlayedState]).
 */
internal fun Episode.playedPoint(): PlayedPoint? =
    if (resumePositionMs == null && fullyPlayed == null) null else PlayedPoint(resumePositionMs, fullyPlayed)

/** This episode without Spotify's played state: for copies that are not a fresh answer. */
fun Episode.withoutPlayedState(): Episode =
    if (resumePositionMs == null && fullyPlayed == null) this else copy(resumePositionMs = null, fullyPlayed = null)

/** This show page without Spotify's played state on its episodes ([withoutPlayedState]). */
fun Show.withoutPlayedState(): Show =
    if (episodes.none { it.resumePositionMs != null || it.fullyPlayed != null }) this
    else copy(episodes = episodes.map { it.withoutPlayedState() })

/**
 * What is kept for an episode after a fresh answer, requested at [fetchedAt], reported Spotify's
 * [state] while [current] was kept (null: nothing kept, nor worth keeping).
 * * An answer requested before (or with) the last one seen is ignored: an older page that finishes
 *   later can't undo a newer one.
 * * News (the state differs from the last one seen; or another device played it since; or nothing
 *   was ever known): Spotify's point, learned at [fetchedAt], replaces an older point — unless this
 *   phone saved progress after the request started. A not-started state keeps no point.
 * * No news (unchanged; or the first state seen after this phone played it offline or took it
 *   over from another device): the point stays and the state becomes the reference.
 * Episodes never played anywhere (not started, or finished, with nothing kept) keep nothing, so
 * browsing doesn't crowd out real points.
 */
internal fun observeSpotify(current: EpisodeResume?, state: PlayedPoint, fetchedAt: Long): EpisodeResume? {
    val seen = current?.seen
    if (seen != null && fetchedAt <= seen.at) return current
    val point = current?.point
    val news = when {
        seen == null -> point == null
        seen.ref == SpotifyRef.ADOPT_NEXT -> false
        seen.ref == SpotifyRef.NEWER_NEXT -> true
        else -> seen.state != state
    }
    val next = when {
        !news -> point
        point != null && point.at > fetchedAt -> point
        state.fullyPlayed == true -> ResumePoint(0, fullyPlayed = true, at = fetchedAt)
        state.inProgress -> ResumePoint(state.positionMs ?: 0, fullyPlayed = false, at = fetchedAt)
        else -> null
    }
    if (current == null && (next == null || next.fullyPlayed)) return null
    return EpisodeResume(next, SpotifySeen(state, fetchedAt))
}

/**
 * Podcast resume points (docs §6.5), per episode ([EpisodeResume]): a small JSON file in the app's
 * files, LRU-bounded, wiped with the account's data (§9.3). Nothing here is reported back to
 * Spotify, so progress made on this phone (offline above all) stays on this phone.
 *
 * Fresh answers are [observe]d once, where they arrive (the catalog and search repositories);
 * everything that shows an episode only [overlay]s the point (no side effects); [resumeMs] is where a
 * play of an episode starts ([com.taehagen.spotifygood.playback.PlayerController.episodeResume]);
 * [recordFrom] records local playback, marks Connect handoffs and resumes an episode playback
 * moved to by itself (auto-advance, next). [version] is bumped on every change. Thread-safe.
 */
class EpisodeProgressStore internal constructor(
    private val file: File?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    constructor(file: File, scope: CoroutineScope) : this(file, scope, System::currentTimeMillis)

    private val lock = Any()
    /** Least recently changed first. */
    private val entries = LinkedHashMap<String, EpisodeResume>()
    /** Bumped by [clear]: a load or write that started before it must not bring the data back. */
    private var generation = 0L
    private val fileMutex = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val _version = MutableStateFlow(0L)

    /** Bumped after every change of what [overlay] / [resumeMs] give. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        scope.launch(io) {
            load()
            for (signal in writes) persist()
        }
    }

    /** Learns Spotify's played state from a fresh answer requested at [fetchedAt] ([observeSpotify]). */
    fun observe(episodes: List<Episode>, fetchedAt: Long) {
        var changed = false
        synchronized(lock) {
            for (episode in episodes) {
                val state = episode.playedPoint() ?: continue
                val current = entries[episode.uri]
                val next = observeSpotify(current, state, fetchedAt)
                if (next != current) {
                    put(episode.uri, next)
                    changed = true
                }
            }
        }
        if (changed) changed()
    }

    /** [episode] showing its resume point, if one is kept (no side effects: safe in any combine). */
    fun overlay(episode: Episode): Episode {
        val point = synchronized(lock) { entries[episode.uri]?.point } ?: return episode
        return episode.copy(resumePositionMs = point.positionMs, fullyPlayed = point.fullyPlayed)
    }

    /**
     * Records that local playback of [uri] got to [positionMs] of [durationMs] now. Within
     * [COMPLETE_MARGIN_MS] of the end it is fully played (resumes from the start). A position of 0
     * that is not the end records nothing (a load before its seek to the resume point).
     */
    fun record(uri: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs - COMPLETE_MARGIN_MS
        if (!finished && positionMs <= 0) return
        synchronized(lock) {
            val current = entries[uri]
            val point = ResumePoint(if (finished) 0 else positionMs, finished, clock())
            put(uri, EpisodeResume(point, current?.seen), touch = true)
        }
        changed()
    }

    /** Another device plays [uri] (this phone is its remote, or handed it over): Spotify's next state is news. */
    fun playedElsewhere(uri: String) = mark(uri, SpotifyRef.NEWER_NEXT)

    /** This phone took [uri] over from another device: Spotify's next state is that device's, older than this phone's progress. */
    fun continuedHere(uri: String) = mark(uri, SpotifyRef.ADOPT_NEXT)

    private fun mark(uri: String, ref: SpotifyRef) {
        synchronized(lock) {
            val current = entries[uri]
            if (current == null && ref == SpotifyRef.NEWER_NEXT) return // nothing kept: any state is news anyway
            put(uri, EpisodeResume(current?.point, SpotifySeen(null, clock(), ref)))
        }
        writes.trySend(Unit) // nothing shown changes
    }

    /** Where a play of [uri] resumes; null: no point, or fully played. */
    fun resumeMs(uri: String): Long? = synchronized(lock) { entries[uri]?.point }
        ?.takeIf { !it.fullyPlayed && it.positionMs > 0 }
        ?.positionMs

    /** Forgets everything (logout / another account), also on disk. */
    suspend fun clear() {
        synchronized(lock) {
            entries.clear()
            generation++
        }
        _version.update { it + 1 }
        val target = file ?: return
        withContext(io) {
            fileMutex.withLock {
                target.delete()
                tmpOf(target).delete()
            }
        }
    }

    /**
     * Follows local playback from [snapshots] until cancelled ([EpisodeProgressTracker]): records
     * episodes (on pause, on a change of item, when playback leaves the phone, every
     * [SAVE_INTERVAL_MS] while one plays), marks Connect handoffs, and [seek]s an episode that
     * playback moved to by itself (auto-advance, next) to its resume point.
     */
    suspend fun recordFrom(
        snapshots: Flow<PlaybackSnapshot>,
        seek: (Long) -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ) {
        val tracker = EpisodeProgressTracker(object : ResumeSink {
            override fun record(uri: String, positionMs: Long, durationMs: Long) = this@EpisodeProgressStore.record(uri, positionMs, durationMs)
            override fun playedElsewhere(uri: String) = this@EpisodeProgressStore.playedElsewhere(uri)
            override fun continuedHere(uri: String) = this@EpisodeProgressStore.continuedHere(uri)
            override fun resumeMs(uri: String): Long? = this@EpisodeProgressStore.resumeMs(uri)
            override fun pointMs(uri: String): Long? = synchronized(lock) { entries[uri]?.point }?.positionMs
            override fun seek(positionMs: Long) = seek(positionMs)
        })
        snapshots
            .transformLatest { s ->
                emit(s)
                if (s.isPlaying && s.source == PlaybackSource.LOCAL && s.track?.isEpisode == true) {
                    while (true) {
                        delay(SAVE_INTERVAL_MS)
                        emit(s)
                    }
                }
            }
            .collect { tracker.onSnapshot(it, now()) }
    }

    /** Caller holds [lock]. [touch]: moves it to the most recent end (a play), not for marks / observations. */
    private fun put(uri: String, value: EpisodeResume?, touch: Boolean = false) {
        if (value == null) {
            entries.remove(uri)
            return
        }
        if (touch || uri !in entries) entries.remove(uri)
        entries[uri] = value
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun changed() {
        _version.update { it + 1 }
        writes.trySend(Unit)
    }

    private suspend fun load() {
        val target = file ?: return
        val started = synchronized(lock) { generation }
        val stored = fileMutex.withLock {
            try {
                if (!target.exists()) return
                json.decodeFromString(SERIALIZER, target.readText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return // unreadable (or an older format): start over
            }
        }
        synchronized(lock) {
            if (generation != started) return
            // Changed before the file was read: newer.
            val changedMeanwhile = LinkedHashMap(entries)
            entries.clear()
            stored.forEach { (uri, value) -> entries[uri] = value }
            changedMeanwhile.forEach { (uri, value) ->
                entries.remove(uri)
                entries[uri] = value
            }
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        _version.update { it + 1 }
    }

    private suspend fun persist() {
        val target = file ?: return
        fileMutex.withLock {
            val snapshot = synchronized(lock) { LinkedHashMap(entries) }
            // Written aside, then renamed over: a crash mid-write keeps the previous file.
            val tmp = tmpOf(target)
            try {
                tmp.writeText(json.encodeToString(SERIALIZER, snapshot))
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            } catch (e: Exception) {
                tmp.delete()
            }
        }
    }

    private fun tmpOf(target: File) = File(target.path + ".tmp")

    companion object {
        /** Episodes remembered (least recently changed ones go first). */
        const val MAX_ENTRIES = 1_000
        /** This close to the end an episode counts as played. */
        const val COMPLETE_MARGIN_MS = 30_000L
        /** While an episode plays, its position is saved this often (as the resume state is). */
        const val SAVE_INTERVAL_MS = 15_000L
        private val SERIALIZER = MapSerializer(String.serializer(), EpisodeResume.serializer())
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** What [EpisodeProgressTracker] drives ([EpisodeProgressStore.recordFrom]). */
internal interface ResumeSink {
    fun record(uri: String, positionMs: Long, durationMs: Long)
    fun playedElsewhere(uri: String)
    fun continuedHere(uri: String)
    /** Where a play resumes (null: none, or fully played). */
    fun resumeMs(uri: String): Long?
    /** The kept point's position, also when fully played (0). */
    fun pointMs(uri: String): Long?
    /** Seeks the local playback. */
    fun seek(positionMs: Long)
}

/**
 * Turns playback snapshots into resume points ([ResumeSink]).
 * * Only a playing or paused local episode has a position worth keeping (a load reports 0 before
 *   its seek); it is recorded on every snapshot, and where the outgoing one got to when it is left
 *   (its end when it finished or stopped there).
 * * A remote device playing an episode (this phone is its remote, or handed it over) marks it
 *   [ResumeSink.playedElsewhere]; local playback arriving at an episode another device was playing
 *   (a transfer to this phone: seen remote just before, or its position is far from the kept point)
 *   marks it [ResumeSink.continuedHere].
 * * Local playback arriving at a partly played episode near its start by itself (auto-advance,
 *   next: the app's own loads already start at the point) seeks to the point once; until it lands
 *   (at most [RESUME_GRACE_MS]) nothing records the episode below it.
 */
internal class EpisodeProgressTracker(private val sink: ResumeSink) {
    private var current: PlaybackSnapshot? = null
    /** The local episode the arrival decision was made for. */
    private var arrived: String? = null
    /** The episode a remote device was playing, until local playback takes over. */
    private var remoteEpisode: String? = null
    private var pending: Pending? = null

    private class Pending(val uri: String, val target: Long, val until: Long)

    fun onSnapshot(s: PlaybackSnapshot, nowMs: Long) {
        val local = s.source == PlaybackSource.LOCAL
        val episodeUri = s.track?.takeIf { it.isEpisode }?.uri
        val previous = current
        if (previous != null && previous.hasPosition()) {
            val previousUri = previous.track?.uri
            val left = !local || previousUri != episodeUri
            // Stopped at the end (the last item): where the playing episode got to, i.e. the end.
            val stopped = !left && previous.isPlaying && s.status == PlaybackStatus.STOPPED
            if ((left || stopped) && previousUri != null) {
                record(previousUri, previous.positionAt(nowMs), previous.durationOrTrack(), nowMs)
            }
        }
        if (!local) {
            current = null
            arrived = null
            pending = null
            if (s.source == PlaybackSource.REMOTE) {
                if (episodeUri != null && episodeUri != remoteEpisode) sink.playedElsewhere(episodeUri)
                remoteEpisode = episodeUri
            }
            return
        }
        if (episodeUri == null) {
            current = null
            arrived = null
            pending = null
            remoteEpisode = null
            return
        }
        current = s
        if (!s.hasPosition()) return
        val position = s.positionAt(nowMs)
        if (arrived != episodeUri) {
            arrived = episodeUri
            arrive(episodeUri, position, nowMs)
        }
        record(episodeUri, position, s.durationOrTrack(), nowMs)
    }

    private fun arrive(uri: String, position: Long, nowMs: Long) {
        val point = sink.pointMs(uri)
        val handedOver = remoteEpisode == uri ||
            (position > ARRIVAL_START_MS && abs(position - (point ?: 0)) > HANDOFF_MARGIN_MS)
        remoteEpisode = null
        pending = null
        if (handedOver) {
            sink.continuedHere(uri)
            return
        }
        val resume = sink.resumeMs(uri) ?: return
        if (position < ARRIVAL_START_MS && resume > ARRIVAL_START_MS) {
            pending = Pending(uri, resume, nowMs + RESUME_GRACE_MS)
            sink.seek(resume)
        }
    }

    /** Records unless a resume seek of [uri] is pending and [position] is still below it. */
    private fun record(uri: String, position: Long, duration: Long, nowMs: Long) {
        val p = pending
        if (p != null && p.uri == uri) {
            if (position < p.target - SEEK_TOLERANCE_MS && nowMs < p.until) return
            pending = null
        }
        sink.record(uri, position, duration)
    }

    private fun PlaybackSnapshot.hasPosition(): Boolean = status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED

    private fun PlaybackSnapshot.durationOrTrack(): Long = durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0

    companion object {
        /** An episode this close to its start was started from the beginning. */
        const val ARRIVAL_START_MS = 5_000L
        /** Arriving this far from the kept point means another device's position was taken over. */
        const val HANDOFF_MARGIN_MS = 30_000L
        /** How long a resume seek may take to land before recording resumes. */
        const val RESUME_GRACE_MS = 10_000L
        private const val SEEK_TOLERANCE_MS = 2_000L
    }
}
