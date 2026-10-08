package com.taehagen.spotifygood.playback

/**
 * Paging of the media browser's lists (Android Auto / AAOS, docs/ARCHITECTURE.md §9.4; pure,
 * JVM-testable). A list is read a window at a time, as Media3 asks (page and page size). One
 * answer holds at most [WINDOW] rows: Media3 cuts a legacy browser's result at 256 KB (about 1 KB
 * a row), and Android Auto does not page. When rows lie past an answer that the browser will not
 * page to (an unpaged request, or a page larger than a window), the answer ends with a "More"
 * row ([moreId]) that opens the rest, a window at a time, so every row stays reachable.
 */
internal object BrowsePaging {
    /** Most rows of one answer. */
    const val WINDOW = 200

    /**
     * The rows one request reads: [count] from [from]. [continued]: rows past them are reached
     * through a "More" row, not by the browser's next page.
     */
    data class Request(val from: Int, val count: Int, val continued: Boolean)

    /**
     * The rows page [page] of [pageSize] reads of the list continued at [offset] (0: its start;
     * a "More" row's offset otherwise). Null: nothing (an unpaged request asks for page 0 only).
     */
    fun request(offset: Int, page: Int, pageSize: Int, window: Int = WINDOW): Request? {
        val start = offset.coerceAtLeast(0)
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return if (page <= 0) Request(start, window, continued = true) else null
        val from = start.toLong() + page.coerceAtLeast(0).toLong() * pageSize
        if (from >= Int.MAX_VALUE) return null
        return Request(from.toInt(), minOf(pageSize, window), continued = pageSize > window)
    }

    /** Where the "More" row of [request] continues a list of [total] rows; null: no row. */
    fun moreAt(request: Request, total: Int): Int? = (request.from + request.count).takeIf { request.continued && it < total }

    /** [list]'s rows [from] until `from + count` (a list already in memory). */
    fun <T> slice(list: List<T>, from: Int, count: Int): List<T> {
        val start = from.coerceIn(0, list.size)
        return list.subList(start, (start.toLong() + count.coerceAtLeast(0)).coerceAtMost(list.size.toLong()).toInt())
    }

    /** A catalog call's answer: [items] from the offset asked for, and the list's [total]. */
    data class Fetched<T>(val items: List<T>, val total: Int)

    /**
     * Rows [from] until `from + count` of a catalog list of [total] rows: from its [first] page
     * (offset 0) as far as it goes, then fetched, [pageLimit] a call ([fetch]).
     */
    suspend fun <T> window(
        first: List<T>,
        total: Int,
        from: Int,
        count: Int,
        pageLimit: Int,
        fetch: suspend (offset: Int, limit: Int) -> List<T>,
    ): List<T> {
        val end = minOf(total.toLong(), from.toLong() + count).toInt()
        if (from >= end) return emptyList()
        val rows = ArrayList<T>(end - from)
        rows += slice(first, from, end - from)
        var offset = from + rows.size
        while (offset < end) {
            val page = fetch(offset, minOf(pageLimit, end - offset))
            if (page.isEmpty()) break
            rows += page
            offset += page.size
        }
        return rows
    }

    /**
     * Rows [from] until `from + count` of a list whose size only its pages tell ([fetch], [pageLimit]
     * a call), and that size.
     */
    suspend fun <T> fetchWindow(from: Int, count: Int, pageLimit: Int, fetch: suspend (offset: Int, limit: Int) -> Fetched<T>): Fetched<T> {
        val rows = ArrayList<T>()
        var offset = from
        var total = from
        val end = from.toLong() + count
        while (offset < end) {
            val page = fetch(offset, minOf(pageLimit.toLong(), end - offset).toInt())
            total = page.total
            if (page.items.isEmpty()) break
            rows += page.items
            offset += page.items.size
            if (offset >= total) break
        }
        return Fetched(rows, total)
    }

    private const val MORE = "more"
    private const val SEP = '|'

    /** Media id of the "More" row continuing [parentId] at [offset]. */
    fun moreId(parentId: String, offset: Int): String = "$MORE$SEP$offset$SEP$parentId"

    data class More(val parentId: String, val offset: Int)

    /** The list and offset a "More" row's [mediaId] continues; null for any other id. */
    fun parseMore(mediaId: String): More? {
        val parts = mediaId.split(SEP, limit = 3)
        if (parts.size != 3 || parts[0] != MORE || parts[2].isEmpty()) return null
        val offset = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return More(parts[2], offset)
    }

    /** The list [mediaId] shows: itself, or the one its "More" row continues. */
    fun listIdOf(mediaId: String): String = parseMore(mediaId)?.parentId ?: mediaId
}
