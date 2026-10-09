package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Playlist

// "Add to playlist" (docs §9.8): what an add of songs, an album, a playlist or an episode does to
// the playlist picked — Spotify's "Already added" question, and its item limit.

/** Spotify's playlist item limit: an add never goes past it. */
const val PLAYLIST_MAX_ITEMS = 10_000

/** Items per page when paging through a playlist for an add (the engine's largest page). */
internal const val ADD_PAGE_SIZE = 500

/**
 * Most page requests one listing for an add makes: [PLAYLIST_MAX_ITEMS] at full pages, with room
 * for a server that answers shorter ones.
 */
internal const val ADD_MAX_PAGES = 2 * (PLAYLIST_MAX_ITEMS / ADD_PAGE_SIZE) + 1

/** A playlist before an add: its item count ([total]) and the items it lists ([uris]). */
data class PlaylistContents(val total: Int, val uris: Set<String>)

/** Which of the items asked for an add sends ([PlaylistAddPlan.itemsFor]). */
enum class PlaylistAddChoice {
    /** All of them, also those already in the playlist ("Add anyway"). */
    ALL,

    /** Only those not in it yet ("Add new ones"). */
    NEW_ONES,
}

/**
 * How adding [requested] (in order) to a playlist goes: [fresh] are those it doesn't list yet
 * (each once), [duplicates] how many of [requested] it already lists, [room] how many more items
 * fit (null: unknown, the server decides).
 */
data class PlaylistAddPlan(
    val requested: List<String>,
    val fresh: List<String>,
    val duplicates: Int,
    val room: Int?,
) {
    /** Some are in the playlist already: the user is asked ("Already added"). */
    val asks: Boolean get() = duplicates > 0

    /** Nothing fits any more. */
    val full: Boolean get() = room != null && room <= 0

    /** What [choice] sends, cut to the [room] left. */
    fun itemsFor(choice: PlaylistAddChoice): List<String> {
        val items = when (choice) {
            PlaylistAddChoice.ALL -> requested
            PlaylistAddChoice.NEW_ONES -> fresh
        }
        return if (room == null) items else items.take(room.coerceAtLeast(0))
    }
}

/**
 * The plan for adding [requested] to a playlist holding [contents] (null when they couldn't be
 * listed: everything is added, as before the check, and the server keeps its own limit).
 */
fun planPlaylistAdd(requested: List<String>, contents: PlaylistContents?): PlaylistAddPlan {
    if (contents == null) return PlaylistAddPlan(requested, requested.distinct(), duplicates = 0, room = null)
    val fresh = requested.filter { it !in contents.uris }.distinct()
    return PlaylistAddPlan(
        requested = requested,
        fresh = fresh,
        duplicates = requested.count { it in contents.uris },
        room = (PLAYLIST_MAX_ITEMS - contents.total).coerceAtLeast(0),
    )
}

/** A page-through of a playlist: its [items] (in order) and its item count ([total]). */
internal class PagedPlaylist(val items: List<String>, val total: Int)

/**
 * The item URIs of the playlist [uri] in order, paged through [fetchPage] ([pageSize] per
 * request): at most [maxItems] items and [maxRequests] requests, so a huge playlist costs a
 * bounded number of requests (what is beyond is left out).
 */
internal suspend fun pagePlaylist(
    uri: String,
    fetchPage: suspend (uri: String, offset: Int, limit: Int) -> Playlist,
    pageSize: Int = ADD_PAGE_SIZE,
    maxItems: Int = PLAYLIST_MAX_ITEMS,
    maxRequests: Int = ADD_MAX_PAGES,
): PagedPlaylist {
    val items = ArrayList<String>()
    var total = 0
    var offset = 0
    var requests = 0
    while (items.size < maxItems && requests < maxRequests) {
        val page = fetchPage(uri, offset, pageSize.coerceAtMost(maxItems - items.size))
        requests++
        total = maxOf(page.total, offset + page.items.size)
        if (page.items.isEmpty()) break
        page.items.mapNotNullTo(items) { it.uri }
        offset += page.items.size
        if (offset >= page.total) break
    }
    return PagedPlaylist(items.take(maxItems), total)
}
