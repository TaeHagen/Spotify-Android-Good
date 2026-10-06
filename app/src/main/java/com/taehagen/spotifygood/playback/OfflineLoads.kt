package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection

/**
 * The downloads of a context: [order] lists the context's items in context order (all of them
 * when the context was downloaded as a collection, otherwise only what is known), [downloaded]
 * says which items are complete downloads.
 */
data class OfflineMembers(val order: List<String>, val downloaded: Set<String>)

/** A completed download, as far as context membership goes (from its stored metadata). */
internal data class DownloadedEntry(
    val uri: String,
    val albumUri: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val showUri: String? = null,
    val releaseDate: String? = null,
)

/**
 * Offline context loads (docs/ARCHITECTURE.md §4.6). The native offline queue cannot know which
 * downloads belong to a playlist or Liked Songs (it would queue every download), so while the
 * session is not Online a `player.load` of a playlist / Liked Songs / album / show context without
 * `trackUris` becomes a `trackUris` load of that context's downloads, in context order.
 *
 * The context uri stays in the request: should the session be Online by the time the engine
 * routes it, Spirc loads the context itself (it ignores `trackUris` for a resolvable context) and
 * starts at `startUid` / `startUri`, which are kept for that reason.
 */
internal object OfflineLoads {
    enum class ContextKind { PLAYLIST, LIKED_SONGS, ALBUM, SHOW }

    sealed interface Plan {
        /** Send this request instead. */
        data class Load(val request: PlayRequest) : Plan

        /** Send the request as it is (online, not a context load, or left to the engine). */
        data object Unchanged : Plan

        /** Offline and nothing of the context is downloaded. */
        data object NotDownloaded : Plan
    }

    /** Uids of the native offline queue ("o<n>"), which the engine reads as an index. */
    private val OFFLINE_UID = Regex("o\\d+")

    fun kindOf(contextUri: String): ContextKind? {
        val parts = contextUri.split(':')
        if (parts.size < 2 || parts[0] != "spotify") return null
        return when {
            parts.size == 3 && parts[1] == "playlist" -> ContextKind.PLAYLIST
            parts.size == 3 && parts[1] == "album" -> ContextKind.ALBUM
            parts.size == 3 && parts[1] == "show" -> ContextKind.SHOW
            // Liked Songs: spotify:user:<name>:collection (also spotify:collection[:tracks]).
            parts[1] == "user" && parts.size == 4 && parts[3] == "collection" -> ContextKind.LIKED_SONGS
            parts[1] == "collection" && (parts.size == 2 || (parts.size == 3 && parts[2] == "tracks")) -> ContextKind.LIKED_SONGS
            // Legacy spotify:user:<name>:playlist:<id>.
            parts[1] == "user" && parts.size == 5 && parts[3] == "playlist" -> ContextKind.PLAYLIST
            else -> null
        }
    }

    /**
     * The downloads of [contextUri]: the downloaded collection's membership when the context was
     * downloaded as a whole; otherwise albums and shows by the downloads' metadata ([entries], only
     * evaluated then), and playlists / Liked Songs only [startUri] itself (their membership is not
     * known offline). Null for context kinds this does not handle.
     */
    fun members(
        contextUri: String,
        startUri: String?,
        collections: List<DownloadedCollection>,
        completed: Set<String>,
        entries: () -> List<DownloadedEntry>,
    ): OfflineMembers? {
        val kind = kindOf(contextUri) ?: return null
        val collection = collections.firstOrNull { it.ref.uri == contextUri }
            ?: collections.firstOrNull { kind == ContextKind.LIKED_SONGS && it.ref.type == CollectionType.LIKED_SONGS }
        if (collection != null) return OfflineMembers(collection.itemUris, completed)
        val order = when (kind) {
            ContextKind.ALBUM -> entries()
                .filter { it.albumUri == contextUri }
                .sortedWith(compareBy<DownloadedEntry>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }))
                .map { it.uri }
            // Shows list the newest episode first (ISO dates sort lexicographically).
            ContextKind.SHOW -> entries()
                .filter { it.showUri == contextUri }
                .sortedByDescending { it.releaseDate.orEmpty() }
                .map { it.uri }
            ContextKind.PLAYLIST, ContextKind.LIKED_SONGS -> listOfNotNull(startUri)
        }
        return OfflineMembers(order, completed)
    }

    /**
     * Rewrites [request] for the offline queue when [reach] is not Online. The start is the
     * requested item, or the first download after it when it is not downloaded (like the engine's
     * own selection), else the first download; the position is kept only for the requested item.
     */
    fun plan(request: PlayRequest, members: OfflineMembers?, reach: EngineReach): Plan {
        if (reach == EngineReach.ONLINE) return Plan.Unchanged
        if (request.contextUri == null || !request.trackUris.isNullOrEmpty() || members == null) return Plan.Unchanged
        val order = members.order
        val wanted = request.startUri?.let { uri -> order.indexOf(uri).takeIf { it >= 0 } } ?: request.startIndex
        val selected = ArrayList<String>()
        val seen = HashSet<String>()
        var start: Int? = null
        var startOrderIndex = -1
        order.forEachIndexed { i, uri ->
            if (uri !in members.downloaded || !seen.add(uri)) return@forEachIndexed
            if (start == null && wanted != null && i >= wanted) {
                start = selected.size
                startOrderIndex = i
            }
            selected += uri
        }
        if (selected.isEmpty()) return if (reach == EngineReach.OFFLINE) Plan.NotDownloaded else Plan.Unchanged
        val hasStart = request.startUri != null || request.startIndex != null || request.startUid != null
        val startAt = start ?: 0
        val exactStart = wanted == null || startOrderIndex == wanted
        return Plan.Load(
            request.copy(
                trackUris = selected,
                startIndex = if (hasStart) startAt else null,
                // Name the start item for Spirc when only an index was given (indices differ).
                startUri = request.startUri ?: if (hasStart) selected[startAt] else null,
                startUid = request.startUid?.takeUnless { OFFLINE_UID.matches(it) },
                positionMs = if (exactStart) request.positionMs else 0,
            ),
        )
    }
}
