package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Page
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Small helpers shared by the data and download layers. Everything here is free of Android APIs so the
// pure parts can be unit tested on the JVM.

/**
 * [NativeRpc.call] whose result is decoded on [Dispatchers.Default]: `NativeRpc` resumes on the
 * caller's dispatcher, and decoding large catalog pages on the main thread would drop frames.
 * [com.taehagen.spotifygood.nativebridge.NativeException]s propagate unchanged.
 */
internal suspend inline fun <reified T> NativeRpc.callOffMain(method: String, args: JsonObject = NativeRpc.EMPTY): T =
    withContext(Dispatchers.Default) { call<T>(method, args) }

/** Like [callOffMain] for a runtime [serializer] (generic element types). */
internal suspend fun <T> NativeRpc.callWith(serializer: KSerializer<T>, method: String, args: JsonObject = NativeRpc.EMPTY): T =
    withContext(Dispatchers.Default) { json.decodeFromJsonElement(serializer, callRaw(method, args)) }

internal suspend fun NativeRpc.callUnitOffMain(method: String, args: JsonObject = NativeRpc.EMPTY) =
    withContext(Dispatchers.Default) { callUnit(method, args) }

/** Builds an RPC argument object (docs/ARCHITECTURE.md §6). */
internal inline fun rpcArgs(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

internal fun JsonObjectBuilder.putStrings(key: String, values: Collection<String>) {
    put(key, JsonArray(values.map(::JsonPrimitive)))
}

/** Every item of a paged list, and whether any page was `partial` (docs §6.3). */
internal data class PagedItems<T>(val items: List<T>, val partial: Boolean)

/** [pageAllChecked] without the partial flag. */
internal suspend fun <T> pageAll(
    pageSize: Int,
    maxItems: Int = MAX_PAGED_ITEMS,
    fetch: suspend (offset: Int, limit: Int) -> Page<T>,
): List<T> = pageAllChecked(pageSize, maxItems, fetch).items

/**
 * Collects every item of an offset/limit paged `library.*` endpoint until `total` or [maxItems].
 * Pages are whole windows (one item per slot, docs §6.3), so the offset advances by the window size.
 * An empty page before `total` means the list changed or broke mid-way: that fails instead of
 * returning a silently truncated list.
 */
internal suspend fun <T> pageAllChecked(
    pageSize: Int,
    maxItems: Int = MAX_PAGED_ITEMS,
    fetch: suspend (offset: Int, limit: Int) -> Page<T>,
): PagedItems<T> {
    val out = ArrayList<T>()
    var partial = false
    var offset = 0
    while (out.size < maxItems) {
        val page = fetch(offset, pageSize)
        partial = partial || page.partial
        if (page.items.isEmpty()) {
            check(offset >= page.total) { "Empty page at offset $offset of ${page.total}" }
            break
        }
        out += page.items
        offset += maxOf(page.items.size, pageSize)
        if (offset >= page.total) break
    }
    return PagedItems(if (out.size > maxItems) out.subList(0, maxItems).toList() else out, partial)
}

/** Upper bound for internally paged lists (Spotify caps playlists at 10k items). */
internal const val MAX_PAGED_ITEMS = 20_000

/** Spotify URI helpers. */
internal object SpotifyUris {
    /** `track`, `album`, `artist`, `playlist`, `show`, `episode`, `collection`, … or null. */
    fun typeOf(uri: String): String? {
        val parts = uri.split(':')
        if (parts.size < 3 || parts[0] != "spotify") return null
        // Legacy `spotify:user:<name>:playlist:<id>` and `spotify:user:<name>:collection`.
        if (parts[1] == "user" && parts.size >= 4) return parts[3]
        return parts[1]
    }

    fun isPlaylist(uri: String) = typeOf(uri) == "playlist"

    /** Types `library.contains` / `library.save` understand (docs §6.3). */
    fun isLibraryItem(uri: String): Boolean = typeOf(uri) in LIBRARY_TYPES

    private val LIBRARY_TYPES = setOf("track", "album", "artist", "show", "episode")

    /** URIs librespot can download / play (local files cannot). */
    fun isPlayableItem(uri: String): Boolean = typeOf(uri).let { it == "track" || it == "episode" }
}
