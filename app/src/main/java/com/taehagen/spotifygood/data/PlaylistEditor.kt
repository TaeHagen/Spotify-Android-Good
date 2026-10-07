package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * Playlist mutations (native `playlist.*`). Throws NativeException on failure.
 *
 * Item mutations return the playlist revision they produced; callers keep it for their next
 * mutation so consecutive edits do not run into revision conflicts. A conflict that still happens
 * (stale revision, docs/ARCHITECTURE.md §6.3) is retried once with the current revision, as long
 * as the edited items are still where the caller saw them.
 *
 * After every successful mutation the affected caches (playlist pages, rootlist) are invalidated and
 * [LibraryRepository.changes] emits, so open screens refetch.
 *
 * Mutations run detached in [scope] (the app scope): if the caller is cancelled (its screen closed),
 * the write and its cache/revision bookkeeping still complete; the caller just stops waiting.
 */
class PlaylistEditor(
    private val scope: CoroutineScope,
    private val rpc: NativeRpc,
    private val library: LibraryRepository,
    private val catalog: CatalogRepository,
) {
    /** Creates a playlist (added to the library) and returns its URI. */
    suspend fun create(name: String, description: String? = null, public: Boolean = false, initialUris: List<String> = emptyList()): String =
        detached { createNow(name, description, public, initialUris) }

    private suspend fun createNow(name: String, description: String?, public: Boolean, initialUris: List<String>): String {
        val uri = rpc.callOffMain<CreatedPlaylist>(
            "playlist.create",
            rpcArgs {
                put("name", name)
                if (!description.isNullOrBlank()) put("description", description)
                put("public", public)
            },
        ).uri
        try {
            if (initialUris.isNotEmpty()) appendItems(uri, initialUris)
        } finally {
            // The playlist exists even if adding the initial items failed.
            library.onPlaylistCreated(uri)
        }
        return uri
    }

    /** Appends [uris]; returns the new revision (null if the engine reported none). */
    suspend fun addItems(playlistUri: String, uris: List<String>): String? {
        if (uris.isEmpty()) return null
        return detached {
            try {
                appendItems(playlistUri, uris)
            } finally {
                library.onPlaylistEdited(playlistUri)
            }
        }
    }

    /** [items]: (uri, index) pairs from the latest fetched [revision]. Returns the new revision. */
    suspend fun removeItems(playlistUri: String, items: List<Pair<String, Int>>, revision: String?): String? {
        if (items.isEmpty()) return revision
        return detached { removeItemsNow(playlistUri, items, revision) }
    }

    private suspend fun removeItemsNow(playlistUri: String, items: List<Pair<String, Int>>, revision: String?): String? {
        val result = retryOnStaleRevision(revision, { currentRevision(playlistUri, items) }) { rev ->
            rpc.callOffMain<RevisionResult>(
                "playlist.removeItems",
                rpcArgs {
                    put("uri", playlistUri)
                    put(
                        "items",
                        JsonArray(
                            items.map { (uri, index) ->
                                rpcArgs {
                                    put("uri", uri)
                                    put("index", index)
                                }
                            },
                        ),
                    )
                    put("revision", rev)
                },
            )
        }
        library.onPlaylistEdited(playlistUri)
        return result.revision
    }

    /**
     * Moves one item. [toIndex] is the playlist4 MOV insert-before position in the list *before*
     * the move (e.g. moving index 2 to the end of a 5-item list uses toIndex = 5); it is sent
     * unchanged. [itemUri] (the moved item) lets a conflict retry check that the item is still at
     * [fromIndex]. Returns the new revision.
     */
    suspend fun moveItem(playlistUri: String, fromIndex: Int, toIndex: Int, revision: String?, itemUri: String? = null): String? {
        if (fromIndex == toIndex) return revision
        return detached { moveItemNow(playlistUri, fromIndex, toIndex, revision, itemUri) }
    }

    private suspend fun moveItemNow(playlistUri: String, fromIndex: Int, toIndex: Int, revision: String?, itemUri: String?): String? {
        val expected = listOfNotNull(itemUri?.let { it to fromIndex })
        val result = retryOnStaleRevision(revision, { currentRevision(playlistUri, expected) }) { rev ->
            rpc.callOffMain<RevisionResult>(
                "playlist.moveItems",
                rpcArgs {
                    put("uri", playlistUri)
                    put("fromIndex", fromIndex)
                    put("length", 1)
                    put("toIndex", toIndex)
                    put("revision", rev)
                },
            )
        }
        library.onPlaylistEdited(playlistUri)
        return result.revision
    }

    suspend fun updateDetails(playlistUri: String, name: String? = null, description: String? = null) {
        if (name == null && description == null) return
        detached {
            rpc.callUnitOffMain(
                "playlist.updateDetails",
                rpcArgs {
                    put("uri", playlistUri)
                    if (name != null) put("name", name)
                    if (description != null) put("description", description)
                },
            )
            library.onPlaylistEdited(playlistUri)
        }
    }

    suspend fun delete(playlistUri: String) = detached {
        rpc.callUnitOffMain("playlist.delete", rpcArgs { put("uri", playlistUri) })
        library.onPlaylistDeleted(playlistUri)
    }

    /** Follows with an optimistic library state (rolled back on failure; already detached). */
    suspend fun follow(playlistUri: String) = library.setSaved(listOf(playlistUri), true)

    suspend fun unfollow(playlistUri: String) = library.setSaved(listOf(playlistUri), false)

    /** Runs [block] in [scope]; cancelling the caller only stops it from waiting for the result. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    /** Returns the revision after the last batch. */
    private suspend fun appendItems(playlistUri: String, uris: List<String>): String? {
        var revision: String? = null
        for (chunk in uris.chunked(ADD_BATCH)) {
            revision = rpc.callOffMain<RevisionResult>(
                "playlist.addItems",
                rpcArgs {
                    put("uri", playlistUri)
                    putStrings("uris", chunk)
                    put("position", JsonNull) // append
                },
            ).revision ?: revision
        }
        return revision
    }

    /**
     * The playlist's current revision, or null when an item of [expected] ((uri, index) pairs) is
     * no longer at its index: retrying an index-based edit would then hit the wrong item.
     */
    private suspend fun currentRevision(playlistUri: String, expected: List<Pair<String, Int>>): String? {
        if (expected.isEmpty()) return catalog.playlistPage(playlistUri, 0, 1).revision
        val first = expected.minOf { it.second }
        val span = expected.maxOf { it.second } - first + 1
        if (first < 0 || span > VERIFY_MAX_SPAN) return null
        val page = catalog.playlistPage(playlistUri, first, span)
        val unchanged = expected.all { (uri, index) -> page.items.getOrNull(index - first)?.uri == uri }
        return page.revision.takeIf { unchanged }
    }

    private companion object {
        /** spclient playlist changes accept ~100 items per delta comfortably. */
        const val ADD_BATCH = 100
        /** Largest index range fetched to verify the items of a retried edit. */
        const val VERIFY_MAX_SPAN = 100
    }
}

/** A stale `revision` (docs/ARCHITECTURE.md §6.3): INVALID_ARGUMENT whose message mentions "revision". */
internal val NativeException.isRevisionConflict: Boolean
    get() = code == NativeErrorCode.INVALID_ARGUMENT && info.message.contains("revision", ignoreCase = true)

/**
 * Runs [call] with [revision]; on a revision conflict fetches the current revision and retries
 * once. The original error is rethrown when the refetch fails or yields nothing new (null, or the
 * same revision that was just rejected).
 */
internal suspend fun <T> retryOnStaleRevision(
    revision: String?,
    fetchRevision: suspend () -> String?,
    call: suspend (revision: String?) -> T,
): T = try {
    call(revision)
} catch (e: NativeException) {
    if (!e.isRevisionConflict) throw e
    val current = try {
        fetchRevision()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }
    if (current == null || current == revision) throw e
    call(current)
}

@Serializable
internal data class CreatedPlaylist(val uri: String)

@Serializable
internal data class RevisionResult(val revision: String? = null)
