package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * Playlist mutations (native `playlist.*`). Throws NativeException on failure.
 *
 * After every successful mutation the affected caches (playlist pages, rootlist) are invalidated and
 * [LibraryRepository.changes] emits, so open screens refetch.
 */
class PlaylistEditor(private val rpc: NativeRpc, private val library: LibraryRepository) {
    /** Creates a playlist (added to the library) and returns its URI. */
    suspend fun create(name: String, description: String? = null, public: Boolean = false, initialUris: List<String> = emptyList()): String {
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

    suspend fun addItems(playlistUri: String, uris: List<String>) {
        if (uris.isEmpty()) return
        try {
            appendItems(playlistUri, uris)
        } finally {
            library.onPlaylistEdited(playlistUri)
        }
    }

    /** [items]: (uri, index) pairs from the latest fetched [revision]. */
    suspend fun removeItems(playlistUri: String, items: List<Pair<String, Int>>, revision: String?) {
        if (items.isEmpty()) return
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
                put("revision", revision)
            },
        )
        library.onPlaylistEdited(playlistUri)
    }

    /**
     * Moves one item. [toIndex] is the playlist4 MOV insert-before position in the list *before*
     * the move (e.g. moving index 2 to the end of a 5-item list uses toIndex = 5).
     */
    suspend fun moveItem(playlistUri: String, fromIndex: Int, toIndex: Int, revision: String?) {
        if (fromIndex == toIndex) return
        rpc.callOffMain<RevisionResult>(
            "playlist.moveItems",
            rpcArgs {
                put("uri", playlistUri)
                put("fromIndex", fromIndex)
                put("length", 1)
                put("toIndex", toIndex)
                put("revision", revision)
            },
        )
        library.onPlaylistEdited(playlistUri)
    }

    suspend fun updateDetails(playlistUri: String, name: String? = null, description: String? = null) {
        if (name == null && description == null) return
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

    suspend fun delete(playlistUri: String) {
        rpc.callUnitOffMain("playlist.delete", rpcArgs { put("uri", playlistUri) })
        library.onPlaylistDeleted(playlistUri)
    }

    /** Follows with an optimistic library state (rolled back on failure). */
    suspend fun follow(playlistUri: String) = library.setSaved(listOf(playlistUri), true)

    suspend fun unfollow(playlistUri: String) = library.setSaved(listOf(playlistUri), false)

    private suspend fun appendItems(playlistUri: String, uris: List<String>) {
        for (chunk in uris.chunked(ADD_BATCH)) {
            rpc.callOffMain<RevisionResult>(
                "playlist.addItems",
                rpcArgs {
                    put("uri", playlistUri)
                    putStrings("uris", chunk)
                    put("position", JsonNull) // append
                },
            )
        }
    }

    private companion object {
        /** spclient playlist changes accept ~100 items per delta comfortably. */
        const val ADD_BATCH = 100
    }
}

@Serializable
internal data class CreatedPlaylist(val uri: String)

@Serializable
internal data class RevisionResult(val revision: String? = null)
