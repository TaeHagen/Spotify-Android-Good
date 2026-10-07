package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.components.SessionMessenger
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.engineReach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

// AppGraph projections shared by the browse ViewModels.

/** True while the app can only use downloaded content (no network or offline mode). */
internal fun AppGraph.offlineFlow(): Flow<Boolean> =
    combine(engine.isNetworkAvailable, settings.settings) { network, settings -> !network || settings.offlineMode }
        .distinctUntilChanged()

internal fun AppGraph.nowPlayingFlow(): Flow<NowPlaying> =
    playback.snapshot.map { it.toNowPlaying() }.distinctUntilChanged()

/** Downloaded collections, newest first. */
internal fun AppGraph.downloadedCollectionsFlow(): Flow<List<DownloadedCollection>> =
    downloads.collections
        .map { list -> list.map { it.toDownloadedCollection() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** uri → download state of every known download item (only emits when a state changes). */
internal fun AppGraph.downloadStatesFlow(): Flow<Map<String, DownloadState>> =
    downloads.items
        .map { items -> items.associate { it.uri to it.state } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** Why a single track (tile, link, recent search, search result) can't be started. */
internal enum class TrackStartBlock {
    /**
     * The catalog marks it unplayable (explicit with "Hide explicit content" on, not available in
     * the country): the player refuses it and Spirc starts the album's next track instead.
     */
    UNAVAILABLE,

    /**
     * Not downloaded while the session can't stream: playback routes the album load to its
     * downloads and would start a different, downloaded track.
     */
    NOT_DOWNLOADED,
}

/**
 * [track]: its catalog metadata, null when unknown (a placeholder, i.e. metadata that failed, is
 * unknown too, not unplayable); [online]: the session is (or became) ONLINE.
 */
internal fun trackStartBlock(track: Track?, online: Boolean, downloaded: Boolean): TrackStartBlock? = when {
    track != null && !track.isPlaceholder && !track.playable -> TrackStartBlock.UNAVAILABLE
    !online && !downloaded -> TrackStartBlock.NOT_DOWNLOADED
    else -> null
}

/** How long a single-track start waits for a session that is connecting (cold start, reconnect). */
internal const val TRACK_START_SESSION_WAIT_MS = 10_000L

/** How long a single-track start waits for the track's metadata (its album, whether it's playable). */
private const val TRACK_LOOKUP_TIMEOUT_MS = 3_000L

private val Track?.isUnplayable: Boolean get() = this != null && !isPlaceholder && !playable

/** What a single-track start does ([planTrackStart]). */
internal sealed interface TrackStartPlan {
    /** Load [trackUri], within [albumUri] when known (so playback continues through the album). */
    data class Play(val trackUri: String, val albumUri: String?) : TrackStartPlan
    data class Blocked(val reason: TrackStartBlock) : TrackStartPlan

    /** Something else was started (or playback paused) while this one waited: it is dropped. */
    data object Superseded : TrackStartPlan
}

/**
 * Decides a single-track start. [known]: metadata the caller has (null for a link or a tile); a
 * connecting session is awaited first ([awaitOnline], unless the track is downloaded), then
 * unknown metadata is looked up ([lookup]) — before ONLINE that lookup fails at once, which would
 * skip both the playable check and the album. [superseded] is checked after each wait.
 */
internal suspend fun planTrackStart(
    trackUri: String,
    known: Track?,
    downloaded: Boolean,
    reach: EngineReach,
    awaitOnline: suspend () -> Boolean,
    lookup: suspend () -> Track?,
    superseded: () -> Boolean,
): TrackStartPlan {
    if (known.isUnplayable) return TrackStartPlan.Blocked(TrackStartBlock.UNAVAILABLE)
    val online = when (reach) {
        EngineReach.ONLINE -> true
        EngineReach.OFFLINE -> false
        // A download plays without the session (from the downloads, as playback routes it).
        EngineReach.CONNECTING -> !downloaded && awaitOnline()
    }
    if (superseded()) return TrackStartPlan.Superseded
    val track = known ?: if (online) lookup() else null
    if (superseded()) return TrackStartPlan.Superseded
    trackStartBlock(track, online, downloaded)?.let { return TrackStartPlan.Blocked(it) }
    return TrackStartPlan.Play(trackUri, track?.album?.uri)
}

/**
 * Whether playback changed since [start] in a way that means the user started (or paused)
 * something else: another context, playing ↔ not playing, or another track that isn't simply the
 * next one of [start] (the current song ending).
 */
internal fun startSuperseded(start: PlaybackSnapshot, now: PlaybackSnapshot): Boolean {
    if (start.context?.uri != now.context?.uri) return true
    if (start.isPlaying != now.isPlaying) return true
    val before = start.track?.uri
    val after = now.track?.uri
    return before != after && after != start.nextTracks.firstOrNull()?.uri
}

/** The single-track start in flight: a newer one replaces it (it would otherwise land later). */
private object TrackStarts {
    private val lock = Any()
    private var job: Job? = null

    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        synchronized(lock) {
            job?.cancel()
            job = scope.launch { block() }
        }
    }
}

/**
 * Starts [trackUri] within its album (so playback continues naturally), else on its own, unless
 * [planTrackStart] says it can't: then shows why instead of letting a different track play. Runs
 * in the app scope; a newer single-track start cancels this one, and a play started elsewhere
 * meanwhile wins.
 */
internal fun AppGraph.launchTrackStart(trackUri: String, track: Track?) {
    TrackStarts.launch(appScope) { startTrack(trackUri, track) }
}

private suspend fun AppGraph.startTrack(trackUri: String, track: Track?) {
    val downloaded = trackUri in downloads.downloadedUris.value
    val reach = engineReach()
    val start = playback.snapshot.value
    val messenger = SessionMessenger(app)
    if (reach == EngineReach.CONNECTING && !downloaded && !track.isUnplayable) {
        // The tap registered: the song starts once the session is back.
        messenger.post(R.string.browse_track_start_waiting)
    }
    val plan = planTrackStart(
        trackUri = trackUri,
        known = track,
        downloaded = downloaded,
        reach = reach,
        awaitOnline = { engine.awaitOnline(TRACK_START_SESSION_WAIT_MS) },
        lookup = {
            withTimeoutOrNull(TRACK_LOOKUP_TIMEOUT_MS) {
                try {
                    catalog.tracks(listOf(trackUri)).firstOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        },
        superseded = { startSuperseded(start, playback.snapshot.value) },
    )
    when (plan) {
        is TrackStartPlan.Blocked -> messenger.post(
            when (plan.reason) {
                TrackStartBlock.UNAVAILABLE -> R.string.player_unavailable
                TrackStartBlock.NOT_DOWNLOADED -> R.string.playback_error_not_available_offline
            },
        )
        is TrackStartPlan.Play -> if (plan.albumUri != null) {
            player.playContext(plan.albumUri, startUri = plan.trackUri)
        } else {
            player.playTracks(listOf(plan.trackUri))
        }
        TrackStartPlan.Superseded -> Unit
    }
}

/** Plays [track] within its album, else on its own; see [launchTrackStart]. */
internal fun AppGraph.playTrackInAlbum(track: Track) = launchTrackStart(track.uri, track)
