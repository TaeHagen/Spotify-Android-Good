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

/** Spotify's played state of an episode, as a fresh answer reported it (its resume point). */
@Serializable
data class PlayedPoint(val positionMs: Long? = null, val fullyPlayed: Boolean? = null) {
    /** Partly played: a point worth resuming from. */
    val inProgress: Boolean get() = fullyPlayed != true && (positionMs ?: 0) > 0
}

/**
 * The best known resume point of an episode: where this phone left it, or Spotify's newer one
 * ([mergeProgress]). [positionMs] (0 once [fullyPlayed]) at [updatedAt]. [server]: Spotify's
 * played state last seen live for it when this was written (null: never seen); a different live
 * one later means it was played elsewhere afterwards.
 */
@Serializable
data class EpisodeProgress(
    val positionMs: Long,
    val fullyPlayed: Boolean,
    val updatedAt: Long,
    val server: PlayedPoint? = null,
)

/**
 * Spotify's played state carried by this episode (null when it carries none). Only a fresh answer
 * carries one (docs §6.5): cached pages and download metadata are stripped
 * ([withoutPlayedState]), so a frozen copy can never stand for Spotify's current state.
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
 * The resume point to keep for an episode after a page showed [server] (Spotify's state from a
 * fresh answer, null: none) while [local] was kept; null keeps nothing. The episode then shows
 * what is kept, or its own (Spotify's) state.
 *
 * Neither side carries a time the other could be compared with, so the live state seen when the
 * point was written ([EpisodeProgress.server]) is the reference:
 * * unchanged since: this phone's progress is newer (made here, possibly offline, never reported
 *   to Spotify) and stays;
 * * changed since: the episode was played elsewhere afterwards, and Spotify's point becomes this
 *   phone's resume point (a not-started one keeps nothing), so the downloads, Android Auto and
 *   offline plays follow it too;
 * * no reference yet (played offline before any live state was seen): this phone's stays and
 *   adopts the one seen now as its reference;
 * * nothing kept: Spotify's point is kept when the episode is partly played, so the same paths can
 *   resume it later (not-started and finished ones are not, they would only crowd out progress).
 */
internal fun mergeProgress(server: PlayedPoint?, local: EpisodeProgress?, now: Long): EpisodeProgress? = when {
    server == null -> local
    local == null -> if (server.inProgress) spotifyPoint(server, now) else null
    local.server == null -> local.copy(server = server)
    local.server == server -> local
    else -> if (server.inProgress || server.fullyPlayed == true) spotifyPoint(server, now) else null
}

private fun spotifyPoint(server: PlayedPoint, now: Long): EpisodeProgress {
    val played = server.fullyPlayed == true
    return EpisodeProgress(positionMs = if (played) 0 else server.positionMs ?: 0, fullyPlayed = played, updatedAt = now, server = server)
}

/**
 * Podcast resume points (docs §6.5), per episode: progress made on this phone, or Spotify's when
 * a fresh page showed a newer one; a small JSON file in the app's files, LRU-bounded, wiped with
 * the account's data (§9.3). Nothing here is reported back to Spotify, so progress made on this
 * phone (offline above all) stays on this phone.
 *
 * [record] is fed by [recordFrom] (local playback of an episode); [merge] overlays the progress on
 * an [Episode] (pages, downloads); [resumeMs] gives where a play of an episode starts. [version]
 * is bumped on every change, for live overlays. Thread-safe.
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
    /** Least recently updated first. */
    private val entries = LinkedHashMap<String, EpisodeProgress>()
    private val lastServer = object : LinkedHashMap<String, PlayedPoint>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayedPoint>?) = size > MAX_SERVER_STATES
    }
    /** Bumped by [clear]: a load or write that started before it must not bring the data back. */
    private var generation = 0L
    private val fileMutex = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val _version = MutableStateFlow(0L)

    /** Bumped after every change of what [merge] / [resumeMs] give. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        scope.launch(io) {
            load()
            for (signal in writes) persist()
        }
    }

    /**
     * Records that local playback of [uri] got to [positionMs] of [durationMs]. Within
     * [COMPLETE_MARGIN_MS] of the end it is fully played (resumes from the start). A position of 0
     * that is not the end records nothing (a load before its seek to the resume point).
     */
    fun record(uri: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs - COMPLETE_MARGIN_MS
        if (!finished && positionMs <= 0) return
        synchronized(lock) {
            val previous = entries.remove(uri)
            entries[uri] = EpisodeProgress(
                positionMs = if (finished) 0 else positionMs,
                fullyPlayed = finished,
                updatedAt = clock(),
                server = lastServer[uri] ?: previous?.server,
            )
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        changed()
    }

    /**
     * [episode] with the best known resume point ([mergeProgress]). Its played state, if any, must
     * be Spotify's from a fresh answer ([playedPoint]); one without (a cached page, a download)
     * only shows what is kept and changes nothing.
     */
    fun merge(episode: Episode): Episode {
        val server = episode.playedPoint()
        val (kept, changed) = synchronized(lock) {
            if (server != null) lastServer[episode.uri] = server
            val local = entries[episode.uri]
            val next = mergeProgress(server, local, clock())
            if (next != local) {
                if (next == null) {
                    entries.remove(episode.uri)
                } else {
                    entries[episode.uri] = next
                    while (entries.size > maxEntries) entries.remove(entries.keys.first())
                }
            }
            next to (next != local)
        }
        if (changed) changed()
        return kept?.let { episode.copy(resumePositionMs = it.positionMs, fullyPlayed = it.fullyPlayed) } ?: episode
    }

    /** Where a play of [uri] resumes: the best known point ([merge]); null: none, or fully played. */
    fun resumeMs(uri: String): Long? = synchronized(lock) { entries[uri] }
        ?.takeIf { !it.fullyPlayed && it.positionMs > 0 }
        ?.positionMs

    /** Forgets everything (logout / another account), also on disk. */
    suspend fun clear() {
        synchronized(lock) {
            entries.clear()
            lastServer.clear()
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
     * Records local playback of episodes from [snapshots] until cancelled: on pause, on a change of
     * item (where the outgoing episode got to: the end when it finished), when playback leaves the
     * phone, and every [SAVE_INTERVAL_MS] while one plays.
     */
    suspend fun recordFrom(snapshots: Flow<PlaybackSnapshot>, now: () -> Long = System::currentTimeMillis) {
        val tracker = EpisodeProgressTracker(::record)
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
                return // unreadable: start over
            }
        }
        synchronized(lock) {
            if (generation != started) return
            // Recorded before the file was read: newer.
            val recorded = LinkedHashMap(entries)
            entries.clear()
            stored.entries.sortedBy { it.value.updatedAt }.forEach { (uri, progress) -> entries[uri] = progress }
            recorded.forEach { (uri, progress) ->
                entries.remove(uri)
                entries[uri] = progress
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
        /** Episodes remembered (least recently played ones go first). */
        const val MAX_ENTRIES = 500
        private const val MAX_SERVER_STATES = 2_000
        /** This close to the end an episode counts as played. */
        const val COMPLETE_MARGIN_MS = 30_000L
        /** While an episode plays, its position is saved this often (as the resume state is). */
        const val SAVE_INTERVAL_MS = 15_000L
        private val SERIALIZER = MapSerializer(String.serializer(), EpisodeProgress.serializer())
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Turns local playback snapshots into [record] calls (see [EpisodeProgressStore.recordFrom]).
 * Only episodes played on this phone count (a remote device reports its own progress); only a
 * playing or paused state has a position worth keeping (a load reports 0 before its seek).
 */
internal class EpisodeProgressTracker(private val record: (uri: String, positionMs: Long, durationMs: Long) -> Unit) {
    private var current: PlaybackSnapshot? = null

    fun onSnapshot(s: PlaybackSnapshot, nowMs: Long) {
        val episode = s.track?.takeIf { s.source == PlaybackSource.LOCAL && it.isEpisode }
        val previous = current
        if (previous != null && previous.hasPosition()) {
            val previousUri = previous.track?.uri
            val left = previousUri != episode?.uri
            // Stopped at the end (the last item): where the playing episode got to, i.e. the end.
            val stopped = !left && previous.isPlaying && s.status == PlaybackStatus.STOPPED
            if ((left || stopped) && previousUri != null) {
                record(previousUri, previous.positionAt(nowMs), previous.durationOrTrack())
            }
        }
        current = if (episode != null) s else null
        if (episode != null && s.hasPosition()) record(episode.uri, s.positionAt(nowMs), s.durationOrTrack())
    }

    private fun PlaybackSnapshot.hasPosition(): Boolean = status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED

    private fun PlaybackSnapshot.durationOrTrack(): Long = durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0
}
