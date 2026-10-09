package com.taehagen.spotifygood.ui.screens.library

import android.content.Context
import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.isSameContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.CollationKey
import java.text.Collator

// Sorting Liked Songs and playlists the way Spotify offers it (docs/ARCHITECTURE.md §9.8).

/** Sort of a track list. The list's default is the server's order (no sorting). */
enum class TrackSort {
    /** A playlist's own order (its default). */
    CUSTOM,

    /** Newest added first (Liked Songs' own order, its default). */
    RECENTLY_ADDED,
    TITLE,
    ARTIST,
    ALBUM,
    ;

    companion object {
        val LIKED_SONGS: List<TrackSort> = listOf(RECENTLY_ADDED, TITLE, ARTIST, ALBUM)
        val PLAYLIST: List<TrackSort> = listOf(CUSTOM, TITLE, ARTIST, ALBUM, RECENTLY_ADDED)
    }
}

/** What a row sorts by; [startable] false (placeholder, unplayable) sorts last. */
class TrackSortKey(
    val title: String,
    val artist: String,
    val album: String,
    val addedAt: Long?,
    val disc: Int,
    val number: Int,
    val startable: Boolean,
)

fun Track.sortKey(addedAt: Long? = null): TrackSortKey = TrackSortKey(
    title = name,
    artist = artists.firstOrNull()?.name.orEmpty(),
    album = album?.name.orEmpty(),
    addedAt = addedAt,
    disc = discNumber ?: 0,
    number = trackNumber ?: 0,
    startable = playable && !isPlaceholder,
)

/** Episodes sort by their name, their show's publisher as "artist" and the show as "album". */
fun PlaylistItem.sortKey(): TrackSortKey {
    track?.let { return it.sortKey(addedAt) }
    val episode = episode ?: return TrackSortKey("", "", "", addedAt, 0, 0, startable = false)
    return TrackSortKey(
        title = episode.name,
        artist = episode.show?.publisher ?: episode.show?.name.orEmpty(),
        album = episode.show?.name.orEmpty(),
        addedAt = addedAt,
        disc = 0,
        number = 0,
        startable = episode.playable && !episode.isPlaceholder,
    )
}

/**
 * The display order of [keys] (indices into it) for [sort]. The list's [default] sort is its
 * server order and leaves it as is. Otherwise rows that can't start go last, names compare with
 * [collator] (case and accents ignored, unknown names after known ones), and ties keep the list
 * order (a stable sort). Title: title, artist, album. Artist: artist, album, disc / track number,
 * title. Album: album, disc / track number, title. Recently added: newest first, unknown last.
 */
fun sortOrder(keys: List<TrackSortKey>, sort: TrackSort, default: TrackSort, collator: Collator = defaultCollator()): List<Int> {
    val indices = keys.indices.toList()
    if (sort == default || sort == TrackSort.CUSTOM) return indices
    val byStartable = compareBy<Int> { if (keys[it].startable) 0 else 1 }
    if (sort == TrackSort.RECENTLY_ADDED) {
        return indices.sortedWith(byStartable.thenBy { keys[it].addedAt == null }.thenByDescending { keys[it].addedAt ?: 0L })
    }
    // Collation keys once per row: comparing them is cheap for lists of thousands.
    fun keysOf(text: (TrackSortKey) -> String): Array<CollationKey?> =
        Array(keys.size) { i -> text(keys[i]).takeIf { it.isNotBlank() }?.let(collator::getCollationKey) }
    val titles = keysOf { it.title }
    val artists = keysOf { it.artist }
    val albums = keysOf { it.album }
    fun by(field: Array<CollationKey?>) = Comparator<Int> { a, b ->
        val x = field[a]
        val y = field[b]
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> x.compareTo(y)
        }
    }
    val byNumber = compareBy<Int>({ keys[it].disc }, { keys[it].number })
    val comparator = when (sort) {
        TrackSort.TITLE -> byStartable.then(by(titles)).then(by(artists)).then(by(albums))
        TrackSort.ARTIST -> byStartable.then(by(artists)).then(by(albums)).then(byNumber).then(by(titles))
        TrackSort.ALBUM -> byStartable.then(by(albums)).then(byNumber).then(by(titles))
        TrackSort.CUSTOM, TrackSort.RECENTLY_ADDED -> byStartable
    }
    return indices.sortedWith(comparator)
}

/** [tracks] in [sort] order (Liked Songs: its default is the server's, newest first). */
fun List<Track>.sortedFor(sort: TrackSort, default: TrackSort, collator: Collator = defaultCollator()): List<Track> {
    if (sort == default) return this
    val order = sortOrder(map { it.sortKey() }, sort, default, collator)
    return order.map(::get)
}

/** Most tracks a sorted list is played with; see [sortedPlayRequest]. */
const val MAX_SORTED_PLAY = 500

/** Of those, at most this many before the start item (so "previous" stays in the list). */
private const val SORTED_PLAY_BEFORE = 50

/**
 * Plays a sorted list ([uris], in the shown order) from [startIndex]. A context load would play the
 * server's order, so the list goes as a track list (Connect then shows no playlist, and the list is
 * a snapshot of what was loaded): at most [MAX_SORTED_PLAY] tracks around the start, shuffle off so
 * the shown order plays. Null when there is nothing to play.
 */
fun sortedPlayRequest(uris: List<String>, startIndex: Int = 0): PlayRequest? {
    if (uris.isEmpty()) return null
    val start = startIndex.coerceIn(0, uris.lastIndex)
    val from = if (uris.size <= MAX_SORTED_PLAY) 0 else (start - SORTED_PLAY_BEFORE).coerceIn(0, uris.size - MAX_SORTED_PLAY)
    val window = uris.subList(from, minOf(uris.size, from + MAX_SORTED_PLAY)).toList()
    return PlayRequest(trackUris = window, startIndex = start - from, shuffle = false, smartShuffle = false)
}

/** How a sorted list starts ([planSortedPlay]). */
sealed interface SortedStart {
    data class Load(val request: PlayRequest) : SortedStart

    /** The tapped song isn't downloaded and the engine is offline (or Play with nothing downloaded). */
    data object NotDownloaded : SortedStart

    /** Nothing to play. */
    data object Nothing : SortedStart
}

/**
 * Starts the sorted list [uris] (its playable songs, in the shown order) at [startUri] (a tapped
 * row) or from the top (Play: null), by the engine's [reach] like any plain list ([planListPlay]):
 * ONLINE the window around the start ([sortedPlayRequest]). Otherwise a downloaded start plays the
 * list's downloads ([downloaded]) from exactly there; a tapped song that isn't downloaded is sent
 * alone while CONNECTING (it plays once the session is back, else "not available offline") and
 * doesn't start OFFLINE: a track list loaded then goes to the offline queue, which would start the
 * next download instead. Play while not ONLINE plays the list's downloads from the first; with none
 * the whole window while CONNECTING (it waits for the session), nothing OFFLINE.
 */
fun planSortedPlay(uris: List<String>, startUri: String?, reach: EngineReach, downloaded: Set<String>): SortedStart {
    if (startUri == null) {
        if (uris.isEmpty()) return SortedStart.Nothing
        if (reach == EngineReach.ONLINE) return sortedPlayRequest(uris, 0)?.let(SortedStart::Load) ?: SortedStart.Nothing
        val own = uris.filter { it in downloaded }
        return when {
            own.isNotEmpty() -> sortedPlayRequest(own, 0)?.let(SortedStart::Load) ?: SortedStart.Nothing
            reach == EngineReach.CONNECTING -> sortedPlayRequest(uris, 0)?.let(SortedStart::Load) ?: SortedStart.Nothing
            else -> SortedStart.NotDownloaded
        }
    }
    val list = if (startUri in uris) uris else listOf(startUri)
    return when (val plan = planListPlay(list, list.indexOf(startUri), reach, downloaded)) {
        is ListPlay.Tracks -> sortedPlayRequest(plan.uris, plan.index)?.let(SortedStart::Load) ?: SortedStart.Nothing
        ListPlay.NotDownloaded -> SortedStart.NotDownloaded
    }
}

/** What plays, as far as "is this list playing" goes ([isListPlaying]). */
@Immutable
data class ListPlayback(
    val trackUri: String? = null,
    val contextUri: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    /** The context's next / previous track (not the user queue, autoplay or suggestions). */
    val next: String? = null,
    val previous: String? = null,
)

fun PlaybackSnapshot.toListPlayback(): ListPlayback =
    if (!isActive) {
        ListPlayback()
    } else {
        ListPlayback(
            trackUri = track?.uri,
            contextUri = context?.uri,
            isPlaying = isPlaying,
            shuffle = shuffle || smartShuffle,
            next = nextTracks.firstOrNull { it.provider == TrackProvider.CONTEXT }?.uri,
            previous = prevTracks.lastOrNull { it.provider == TrackProvider.CONTEXT }?.uri,
        )
    }

internal fun AppGraph.listPlaybackFlow(): Flow<ListPlayback> =
    playback.snapshot.map { it.toListPlayback() }.distinctUntilChanged()

/**
 * The last track list this app started for a list (Liked Songs, a playlist: a sorted order, or
 * Liked Songs' downloads offline), for the session: its list key ([ListSortStore.LIKED_SONGS],
 * [ListSortStore.playlist]) and the tracks sent, in order. Kept outside the pages, so a page
 * opened again still knows its list is playing; dropped as soon as playback moves to anything
 * else ([Entry.after]).
 */
internal object SortedPlays {
    internal data class Entry(
        val list: String,
        val order: List<String>,
        val generation: Int,
        /** One of its tracks has played since it was sent (until then the previous playback shows). */
        val landed: Boolean = false,
    ) {
        private val positions: Map<String, Int> by lazy {
            HashMap<String, Int>(order.size * 2).also { map -> order.forEachIndexed { i, uri -> map.putIfAbsent(uri, i) } }
        }

        operator fun contains(uri: String): Boolean = uri in positions

        /**
         * [playback] is this load: its track is in the list and, unless shuffled, the context's next
         * (else previous) track is its neighbour in the order sent (the wrap of repeat-all too).
         * Another list holding the same song has other neighbours.
         */
        fun matches(playback: ListPlayback): Boolean {
            val track = playback.trackUri ?: return false
            val i = positions[track] ?: return false
            if (playback.shuffle) return playback.next == null || playback.next in this
            playback.next?.let { return it == (order.getOrNull(i + 1) ?: order.first()) }
            playback.previous?.let { return it == (order.getOrNull(i - 1) ?: order.last()) }
            return true
        }
    }

    private val entry = MutableStateFlow<Entry?>(null)
    private var watcher: Job? = null

    /** The last such play of this session (a logout ends it), or null. */
    val last: Flow<Entry?> = entry.map { it?.takeIf { e -> e.generation == BackgroundMessages.currentGeneration() } }

    /** [request] (a track list for [list]) was just sent. */
    @Synchronized
    fun record(graph: AppGraph, list: String, request: PlayRequest) {
        val order = request.trackUris.orEmpty()
        entry.value = if (order.isEmpty()) null else Entry(list, order, BackgroundMessages.currentGeneration())
        if (watcher?.isActive != true) {
            watcher = graph.appScope.launch {
                graph.playback.snapshot.collect { snapshot -> entry.update { it?.after(snapshot.toListPlayback()) } }
            }
        }
    }
}

/**
 * The record once playback is [now]: landed when one of its tracks plays as this load; dropped
 * when, after that, playback moved to anything else (another list, a context, a single track).
 * Nothing playing (stopped, a load on its way) keeps it.
 */
internal fun SortedPlays.Entry.after(now: ListPlayback): SortedPlays.Entry? {
    if (now.trackUri == null) return this
    val ours = !isCatalogContext(now.contextUri) && matches(now)
    return when {
        ours -> if (landed) this else copy(landed = true)
        !landed -> this
        else -> null
    }
}

/**
 * Whether [playback] is the list [listKey] (catalog context [listContextUri]: the playlist, Liked
 * Songs): its own context, in any order (Shuffle, started before a sort, from Auto or another
 * device), or the track list this app last started for it ([last]) while playback is still that
 * load ([SortedPlays.Entry.matches]). A context-less play of one of its songs from elsewhere
 * (Downloads, a single track, another client) is not it. Used for the Play/Pause button of
 * Liked Songs and playlists: toggle when true, start the list otherwise.
 */
internal fun isListPlaying(listKey: String, listContextUri: String?, playback: ListPlayback, last: SortedPlays.Entry?): Boolean {
    if (playback.trackUri == null) return false
    val context = playback.contextUri?.takeIf { it.isNotBlank() }
    if (context != null && listContextUri != null && isSameContext(context, listContextUri)) return true
    if (last == null || last.list != listKey || isCatalogContext(context)) return false
    return last.matches(playback)
}

/** A context of the catalog (a track list's context is none, or a placeholder such as spotify:web-api). */
private fun isCatalogContext(context: String?): Boolean {
    val uri = context?.takeIf { it.isNotBlank() } ?: return false
    return CATALOG_CONTEXTS.any { uri.startsWith(it) } || uri.endsWith(":collection")
}

private val CATALOG_CONTEXTS = listOf("spotify:playlist:", "spotify:album:", "spotify:artist:", "spotify:show:", "spotify:station:")

/**
 * The sort chosen per list (Liked Songs, each playlist), kept across app starts. Only non-default
 * choices are stored, with the time they were made: past [MAX_LISTS] lists the oldest are
 * forgotten.
 */
internal class ListSortStore(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    suspend fun get(list: String): TrackSort? = withContext(Dispatchers.IO) {
        prefs.getString(list, null)?.let(::parseStoredSort)
    }

    suspend fun set(list: String, sort: TrackSort, default: TrackSort) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        if (sort == default) {
            editor.remove(list)
        } else {
            val value = "${sort.name}|${System.currentTimeMillis()}"
            editor.putString(list, value)
            val stored = prefs.all.mapNotNull { (key, stored) -> (stored as? String)?.let { key to it } }.toMap() + (list to value)
            sortsToForget(stored, MAX_LISTS).forEach(editor::remove)
        }
        editor.apply()
    }

    companion object {
        private const val FILE = "list_sorts"
        const val MAX_LISTS = 300
        const val LIKED_SONGS = "liked_songs"

        fun playlist(uri: String): String = "playlist:$uri"
    }
}

/** A stored "SORT|millis" value's sort; null for anything else (an older or broken value). */
internal fun parseStoredSort(value: String): TrackSort? =
    TrackSort.entries.firstOrNull { it.name == value.substringBefore('|') }

/** Keys of [stored] ("SORT|millis" values) past the [max] most recent ones. */
internal fun sortsToForget(stored: Map<String, String>, max: Int): List<String> {
    if (stored.size <= max) return emptyList()
    return stored.entries
        .sortedByDescending { it.value.substringAfter('|', "0").toLongOrNull() ?: 0L }
        .drop(max)
        .map { it.key }
}
