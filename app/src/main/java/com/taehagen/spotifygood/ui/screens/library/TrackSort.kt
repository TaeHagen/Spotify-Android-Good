package com.taehagen.spotifygood.ui.screens.library

import android.content.Context
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.components.isPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

/**
 * The last sorted list played in this session: its list key ([ListSortStore.LIKED_SONGS],
 * [ListSortStore.playlist]) and the URIs sent. Kept outside the pages, so a page opened again
 * still knows its list is playing (its Play button resumes it instead of starting it over).
 */
internal object SortedPlays {
    internal data class Entry(val list: String, val uris: Set<String>, val generation: Int)

    private val entry = MutableStateFlow<Entry?>(null)

    /** The last sorted play of this session (a logout ends it), or null. */
    val last: Flow<Entry?> = entry.map { it?.takeIf { e -> e.generation == BackgroundMessages.currentGeneration() } }

    fun record(list: String, request: PlayRequest) {
        entry.value = Entry(list, request.trackUris.orEmpty().toSet(), BackgroundMessages.currentGeneration())
    }
}

/**
 * The URIs of list [list] whose playback counts as this sorted list playing: what was sent for it
 * when the last sorted play ([last]) was this list's; nothing when it was another list's. With no
 * sorted play recorded (a new process, e.g. a resumed session), the list as [shown]: a context-less
 * play of one of its songs is taken for it.
 */
internal fun sortedListUris(list: String, last: SortedPlays.Entry?, shown: () -> List<String>): Set<String> = when {
    last == null -> shown().toHashSet()
    last.list == list -> last.uris
    else -> emptySet()
}

/**
 * Whether what plays is a sorted list this page sent ([sent]): its track, and no catalog context
 * (a track-list load has none; the same track played from an album or playlist doesn't count).
 */
fun isSortedPlayback(trackUri: String?, contextUri: String?, sent: Set<String>): Boolean {
    if (trackUri == null || trackUri !in sent) return false
    val context = contextUri?.takeIf { it.isNotBlank() } ?: return true
    return CATALOG_CONTEXTS.none { context.startsWith(it) } && !context.endsWith(":collection")
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
