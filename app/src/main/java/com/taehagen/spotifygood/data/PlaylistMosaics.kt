package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.best
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

// The art of a playlist without an image of its own (docs §9.8), as Spotify draws it: a 2x2
// mosaic of the covers of its first songs, edge to edge.

/** A playlist's uri (not Liked Songs, which keeps its own art, nor a folder). */
fun isPlaylistUri(uri: String): Boolean =
    uri.startsWith("spotify:playlist:") || (uri.startsWith("spotify:user:") && ":playlist:" in uri)

/** Whether [uri]'s art is a mosaic: a playlist without an image of its own ([imageUrl]); nothing is learned for others. */
fun needsMosaic(uri: String, imageUrl: String?): Boolean = imageUrl.isNullOrBlank() && isPlaylistUri(uri)

/** How many first items a mosaic looks at (one small first page). */
const val MOSAIC_SCAN = 20

/** One tile of a playlist mosaic: an album's (or an episode's / show's) image variants. */
@Serializable
data class MosaicCover(val images: List<Image>) {
    /** The tile's image for a tile of [minPx] (see [best]). */
    fun url(minPx: Int): String? = images.best(minPx)

    /** The image id of the cover (the CDN URL's last path segment): what a composed bitmap is keyed by. */
    val id: String get() = (images.best(640) ?: "").substringAfterLast('/')
}

/**
 * What shows for a playlist without an image of its own, learned at the playlist's [revision]
 * ([listed]: the revision the library listed it at then): 4 [covers] make the 2x2 mosaic, 1 is the
 * first song's cover full size, none the placeholder.
 */
@Serializable
data class PlaylistMosaic(
    val revision: String? = null,
    val covers: List<MosaicCover> = emptyList(),
    val listed: String? = null,
) {
    val isMosaic: Boolean get() = covers.size == MOSAIC_TILES
}

/** Tiles of a mosaic. */
const val MOSAIC_TILES = 4

/**
 * Spotify's rule for the covers of a playlist without an image: the first [MOSAIC_TILES] distinct
 * album covers among its first [items] (local files, rows without art — placeholders, episodes
 * without images — and unavailable rows skipped); fewer than that: the first song's cover alone;
 * none: nothing (the placeholder).
 */
fun mosaicCovers(items: List<PlaylistItem>): List<MosaicCover> {
    val distinct = LinkedHashMap<String, MosaicCover>()
    for (item in items) {
        val images = coverOf(item) ?: continue
        val key = images.best(640) ?: continue
        if (key !in distinct) distinct[key] = MosaicCover(images)
        if (distinct.size == MOSAIC_TILES) break
    }
    return when {
        distinct.size >= MOSAIC_TILES -> distinct.values.toList()
        distinct.isEmpty() -> emptyList()
        else -> listOf(distinct.values.first())
    }
}

/**
 * The cover art of a playlist row, or null when it has none to give: a local file, a placeholder
 * (its metadata failed: no art), an episode without images, or a row unavailable here (one marked
 * unplayable only because of Hide explicit content still counts: the filter doesn't change a
 * playlist's art).
 */
private fun coverOf(item: PlaylistItem): List<Image>? {
    item.track?.let { t ->
        if (t.uri.startsWith("spotify:local:") || t.name.isBlank() || (!t.playable && !t.explicit)) return null
        return t.album?.images?.takeIf { it.isNotEmpty() }
    }
    item.episode?.let { e ->
        if (e.name.isBlank() || (!e.playable && !e.explicit)) return null
        return e.images.ifEmpty { e.show?.images.orEmpty() }.takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * The mosaics of playlists without an image of their own, learned from their first items (one
 * small first page each, [MOSAIC_SCAN] items) only when such a playlist is shown, at most
 * [concurrency] at a time and once per playlist at a time; a learn still waiting for its turn when
 * no row awaits it any more (they left the screen) is dropped, so the rows on screen don't queue
 * behind it. Kept per playlist revision in memory and in the response cache, so a long library
 * scrolled through again fetches nothing: a mosaic is learned again when the library lists the
 * playlist at another revision ([noteRevisions]; the rows showing it ask again), after an edit
 * made here ([invalidate]; the cache row goes stale with the playlist's), or after [TTL_MS] for a
 * playlist whose revision isn't known (Home, Search). Offline, the downloaded rows; one not
 * downloaded is asked for again by its rows once the session is online ([onOnline]).
 */
class PlaylistMosaicStore internal constructor(
    private val scope: CoroutineScope,
    private val cache: ResponseCache?,
    /** The playlist's first page, [MOSAIC_SCAN] items (online). */
    private val firstPage: suspend (uri: String) -> Playlist,
    /** The downloaded playlist's first items, if it is downloaded (offline). */
    private val downloadedItems: suspend (uri: String) -> List<PlaylistItem>?,
    private val online: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    concurrency: Int = MAX_CONCURRENT_FETCHES,
) {
    private class Entry(val mosaic: PlaylistMosaic, val at: Long)

    /**
     * A learn in flight, shared by its [askers]. The last of them gone before it got a permit, it is
     * dropped; once [fetching], it finishes and is kept whoever still awaits it.
     */
    private class Learn {
        lateinit var job: Deferred<PlaylistMosaic?>
        var askers = 0
        var fetching = false
    }

    private val lock = Any()
    /** Least recently used first. */
    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_MEMORY
    }
    /** The revisions the library lists its playlists at (the rootlist's). */
    private val listed = ConcurrentHashMap<String, String>()
    /** When a learn of a playlist last failed: not tried again before [RETRY_MS]. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = HashMap<String, Learn>()
    private val permits = Semaphore(concurrency)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * Counts the times many rows may have to ask again at once: the session came online
     * ([onOnline]), the library listed playlists at other revisions ([noteRevisions]). Each row
     * checks it against its own playlist ([asksAgain]): one conflated value, so none is lost however
     * many playlists that concerns (per-playlist [_changes] would drop some past its buffer).
     */
    private val recheck = MutableStateFlow(0)
    /** Playlists not learned because the session was offline (and not downloaded), with the [recheck] count then. */
    private val waitingOnline = ConcurrentHashMap<String, Int>()

    /**
     * When a row of [uri] asks [mosaic] again: its mosaic changed or went stale (learned, edited
     * here, recorded from its open page, listed at another revision), or the session came online to
     * learn one skipped for want of it.
     */
    fun changesOf(uri: String): Flow<Unit> =
        merge(_changes.filter { it == uri }.map { }, recheck.filter { asksAgain(uri, it) }.map { })

    init {
        cache?.addClearListener { clearMemory() }
    }

    /** What memory holds for [uri] (no I/O, maybe stale): the first frame of its artwork. */
    fun peek(uri: String): PlaylistMosaic? = synchronized(lock) { memory[uri]?.mosaic }

    /**
     * The mosaic of [uri]: a valid one kept, else learned (bounded, see the class); when that
     * fails, what was kept (maybe stale), else null.
     */
    suspend fun mosaic(uri: String): PlaylistMosaic? {
        val kept = synchronized(lock) { memory[uri] } ?: readDisk(uri)
        if (kept != null && valid(uri, kept)) return kept.mosaic
        val failed = failedAt[uri]
        if (failed != null && clock() - failed < RETRY_MS) return kept?.mosaic
        return learn(uri) ?: kept?.mosaic
    }

    /** Learns [uri]'s mosaic in the background (Android Auto lists show it on their next load). */
    fun prefetch(uri: String) {
        scope.launch { mosaic(uri) }
    }

    /** The playlist page's first [items] at [revision]: what the lists show too, nothing fetched. */
    fun record(uri: String, revision: String?, items: List<PlaylistItem>) {
        val mosaic = PlaylistMosaic(revision, mosaicCovers(items.take(MOSAIC_SCAN)), listed[uri])
        val before = synchronized(lock) { memory[uri]?.mosaic }
        store(uri, mosaic)
        if (before?.covers != mosaic.covers) _changes.tryEmit(uri)
    }

    /**
     * The library lists these playlists at these revisions (the rootlist's; not [fetched]: the
     * cached one, which only fills in what no fetch has told yet): a mosaic learned while it listed
     * another goes stale, learned again when shown, and the rows showing it now ask again.
     */
    fun noteRevisions(current: Map<String, String>, fetched: Boolean = true) {
        var changed = false
        for ((uri, revision) in current) {
            if (fetched) {
                if (listed.put(uri, revision) != revision) changed = true
            } else if (listed.putIfAbsent(uri, revision) == null) {
                changed = true
            }
        }
        if (changed) recheck.update { it + 1 }
    }

    /** The session is online: the rows of playlists skipped for want of it ask again. */
    fun onOnline() {
        recheck.update { it + 1 }
    }

    /** [uri] was edited here: its mosaic is learned again when shown. */
    fun invalidate(uri: String) {
        synchronized(lock) { memory.remove(uri) }
        failedAt.remove(uri)
        _changes.tryEmit(uri)
    }

    private fun clearMemory() {
        synchronized(lock) { memory.clear() }
        listed.clear()
        failedAt.clear()
        waitingOnline.clear()
    }

    /**
     * Whether a row of [uri] asks again at the [recheck] [count]: skipped offline before it, or what
     * memory keeps went stale (not while a failed learn waits [RETRY_MS]).
     */
    private fun asksAgain(uri: String, count: Int): Boolean {
        waitingOnline[uri]?.let { return it < count }
        val kept = synchronized(lock) { memory[uri] } ?: return false
        if (valid(uri, kept)) return false
        val failed = failedAt[uri]
        return failed == null || clock() - failed >= RETRY_MS
    }

    private fun valid(uri: String, entry: Entry): Boolean {
        if (entry.at <= 0) return false // invalidated (an edit made here)
        val current = listed[uri]
        return if (current != null) current == entry.mosaic.listed else clock() - entry.at < TTL_MS
    }

    private suspend fun learn(uri: String): PlaylistMosaic? {
        val learn = synchronized(lock) {
            val shared = inFlight[uri] ?: Learn().also { started ->
                started.job = scope.async {
                    permits.withPermit {
                        synchronized(lock) { started.fetching = true }
                        fetch(uri)
                    }
                }
                inFlight[uri] = started
                started.job.invokeOnCompletion { synchronized(lock) { if (inFlight[uri] === started) inFlight.remove(uri) } }
            }
            shared.also { it.askers++ }
        }
        try {
            return learn.job.await()
        } finally {
            synchronized(lock) {
                learn.askers--
                // No row awaits it any more (they left the screen) and it hasn't started: it gives way.
                if (learn.askers == 0 && !learn.fetching && inFlight[uri] === learn) {
                    inFlight.remove(uri)
                    learn.job.cancel()
                }
            }
        }
    }

    private suspend fun fetch(uri: String): PlaylistMosaic? {
        val listedNow = listed[uri]
        // Read before the session's state: coming online after this asks it again.
        val count = recheck.value
        val tried = online()
        val mosaic = (if (tried) fetchOnline(uri, listedNow) else null) ?: fetchDownloaded(uri, listedNow)
        if (mosaic == null) {
            if (tried) {
                failedAt[uri] = clock()
                waitingOnline.remove(uri)
            } else {
                // Offline without a download: its rows ask again once the session is online.
                waitingOnline[uri] = count
            }
            return null
        }
        failedAt.remove(uri)
        val before = synchronized(lock) { memory[uri]?.mosaic }
        store(uri, mosaic)
        if (before?.covers != mosaic.covers) _changes.tryEmit(uri)
        return mosaic
    }

    private suspend fun fetchOnline(uri: String, listedNow: String?): PlaylistMosaic? = try {
        val page = firstPage(uri)
        // A row listed without an image while the playlist has one now: that one, alone.
        val own = page.images.takeIf { it.isNotEmpty() }?.let { listOf(MosaicCover(it)) }
        PlaylistMosaic(page.revision, own ?: mosaicCovers(page.items.take(MOSAIC_SCAN)), listedNow)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchDownloaded(uri: String, listedNow: String?): PlaylistMosaic? = try {
        downloadedItems(uri)?.let { PlaylistMosaic(revision = null, covers = mosaicCovers(it.take(MOSAIC_SCAN)), listed = listedNow) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun store(uri: String, mosaic: PlaylistMosaic) {
        synchronized(lock) { memory[uri] = Entry(mosaic, clock()) }
        waitingOnline.remove(uri)
        val target = cache ?: return
        scope.launch {
            try {
                target.put(CacheKeys.playlistMosaic(uri), PlaylistMosaic.serializer(), mosaic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Memory still has it.
            }
        }
    }

    private suspend fun readDisk(uri: String): Entry? {
        val target = cache ?: return null
        val (mosaic, at) = try {
            target.get(CacheKeys.playlistMosaic(uri), PlaylistMosaic.serializer())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return Entry(mosaic, at).also { entry -> synchronized(lock) { if (uri !in memory) memory[uri] = entry } }
    }

    internal companion object {
        /** Playlists learned at the same time. */
        const val MAX_CONCURRENT_FETCHES = 3
        /** A mosaic of a playlist whose revision isn't known is learned again after this. */
        const val TTL_MS = 24 * 60 * 60 * 1000L
        /** A failed learn isn't tried again before this. */
        const val RETRY_MS = 5 * 60 * 1000L
        private const val MAX_MEMORY = 1_000
    }
}

/** A tile of a mosaic in pixels: [left] and [top] inclusive, [right] and [bottom] exclusive. */
data class MosaicTile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The [MOSAIC_TILES] tiles of a [width] x [height] mosaic in reading order, edge to edge: they
 * cover it exactly, without a gap or an overlap (an odd size gives the right column and the bottom
 * row the extra pixel). The UI's grid and the composed bitmap both lay it out so.
 */
fun mosaicTiles(width: Int, height: Int): List<MosaicTile> {
    val x = width / 2
    val y = height / 2
    return listOf(
        MosaicTile(0, 0, x, y),
        MosaicTile(x, 0, width, y),
        MosaicTile(0, y, x, height),
        MosaicTile(x, y, width, height),
    )
}

/** The centre square of a [width] x [height] cover: a tile is square, a cover that isn't is cropped, not squeezed. */
fun centerSquare(width: Int, height: Int): MosaicTile {
    val side = minOf(width, height)
    val left = (width - side) / 2
    val top = (height - side) / 2
    return MosaicTile(left, top, left + side, top + side)
}
