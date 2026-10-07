package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

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

/** Longest wait for the engine to apply a changed explicit filter (as Settings waits). */
private const val EXPLICIT_APPLY_TIMEOUT_MS = 15_000L

/**
 * Emits each time "Hide explicit content" changed and the engine applies it, so lists fetched
 * under the old filter (their playable flags) can be fetched again. Waiting for the engine keeps a
 * refetch from getting (and caching) the old flags again.
 */
internal fun AppGraph.explicitFilterChanges(): Flow<Boolean> =
    settings.settings
        .map { it.hideExplicit }
        .distinctUntilChanged()
        .drop(1)
        .mapLatest { hide ->
            engine.awaitSettingsApplied(EXPLICIT_APPLY_TIMEOUT_MS) { it.filterExplicit == hide }
            hide
        }

/**
 * Whether explicit content is filtered: "Hide explicit content", or the account's own filter.
 * The engine marks catalog results unplayable then; metadata stored with downloads isn't, so
 * lists built from it apply [withExplicitFilter].
 */
internal fun AppGraph.explicitFilterFlow(): Flow<Boolean> =
    combine(settings.settings.map { it.hideExplicit }, engine.user.map { it?.explicitFilter == true }) { hide, account ->
        hide || account
    }.distinctUntilChanged()

/** [filter]ed explicit tracks are unplayable (the player refuses them and skips to the next). */
internal fun Track.withExplicitFilter(filter: Boolean): Track = if (filter && explicit && playable) copy(playable = false) else this

internal fun Episode.withExplicitFilter(filter: Boolean): Episode = if (filter && explicit && playable) copy(playable = false) else this

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
 * Whether playback changed since [start] in a way that means the user started something else on
 * this phone meanwhile: another local track (not just the next song of [start]).
 *
 * Changes the session makes by itself while coming online are not that, and are ignored: the
 * first cluster after a reconnect (another device's REMOTE playback replacing the empty snapshot),
 * the restore of the reconnect placeholder (same track, playing again), anything from an empty
 * snapshot. So is the load a previous single-track start already sent landing ([ownTargets]):
 * this newer tap replaces it anyway. Pausing or resuming doesn't drop the tap either.
 */
internal fun startSuperseded(start: PlaybackSnapshot, now: PlaybackSnapshot, ownTargets: Set<String> = emptySet()): Boolean {
    // Nothing (or only a placeholder without a track) to compare with: can't tell who changed it.
    if (start.track == null || start.source == PlaybackSource.NONE) return false
    // Only a load on this phone; what other devices play is the cluster, not this user's taps here.
    if (now.source != PlaybackSource.LOCAL) return false
    // Another track, not the next one (the song ending). The same track in another context is a
    // hand-back or restore of the session (e.g. offline queue to Spirc), not a new start.
    val after = now.track?.uri
    val trackChanged = start.track.uri != after && after != start.nextTracks.firstOrNull()?.uri
    if (!trackChanged) return false
    return now.context?.uri !in ownTargets && after !in ownTargets
}

/** The single-track start in flight: a newer one replaces it (it would otherwise land later). */
private object TrackStarts {
    private val lock = Any()
    private var job: Job? = null

    /** Album and track of the last load a single-track start sent (see [startSuperseded]). */
    @Volatile var lastSent: Set<String> = emptySet()

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
        superseded = { startSuperseded(start, playback.snapshot.value, TrackStarts.lastSent) },
    )
    when (plan) {
        is TrackStartPlan.Blocked -> messenger.post(
            when (plan.reason) {
                TrackStartBlock.UNAVAILABLE -> R.string.player_unavailable
                TrackStartBlock.NOT_DOWNLOADED -> R.string.playback_error_not_available_offline
            },
        )
        is TrackStartPlan.Play -> {
            TrackStarts.lastSent = setOfNotNull(plan.albumUri, plan.trackUri)
            if (plan.albumUri != null) {
                player.playContext(plan.albumUri, startUri = plan.trackUri)
            } else {
                player.playTracks(listOf(plan.trackUri))
            }
        }
        TrackStartPlan.Superseded -> Unit
    }
}

/** Plays [track] within its album, else on its own; see [launchTrackStart]. */
internal fun AppGraph.playTrackInAlbum(track: Track) = launchTrackStart(track.uri, track)
