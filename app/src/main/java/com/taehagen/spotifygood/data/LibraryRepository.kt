package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Page
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.SavedAlbum
import com.taehagen.spotifygood.model.SavedArtist
import com.taehagen.spotifygood.model.SavedEpisode
import com.taehagen.spotifygood.model.SavedShow
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put

/** A library change the app itself made (downloads re-sync the downloaded collection it affects). */
sealed interface LibraryEdit {
    /** Tracks liked ([saved]) or unliked: Liked Songs changed. */
    data class LikedTracks(val uris: List<String>, val saved: Boolean) : LibraryEdit

    /** Items of playlist [uri] were added, removed or moved (or it was renamed). */
    data class PlaylistEdited(val uri: String) : LibraryEdit

    /** Pull-to-refresh: the library may have changed elsewhere (another device). */
    data object Refreshed : LibraryEdit
}

/**
 * The user's library (native `library.*`): playlists (rootlist), Liked Songs, saved albums,
 * followed artists, saved podcasts and episodes. Saved state is optimistic and cached.
 *
 * * Lists are stale-while-revalidate ([ResponseCache], 5 min TTL) and reload by themselves after a
 *   mutation that affects them or a [refresh] (they never complete). Paged lists (Liked Songs,
 *   episodes) are not cached: refetch them when [changes] emits.
 * * Saved state lives in an LRU memory map fed by fetched lists and by `library.contains` lookups
 *   that are coalesced (50 ms window, ≤ 50 URIs per call). Playlists are "saved" when they are in
 *   the rootlist; following/unfollowing them uses `playlist.follow` / `playlist.unfollow`.
 * * A saved state is unknown (null) until looked up; a failed lookup stays unknown and runs again
 *   once the session is [online] (`SpotifyEngine.isOnline`). Toggles act on the state the control
 *   showed ([toggleSaved]).
 * * Mutations run in the repository scope (a like is not lost when the screen closes), flip the
 *   local state immediately and roll back the URIs that failed.
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val rpc: NativeRpc,
    private val cache: ResponseCache,
    private val online: StateFlow<Boolean>,
    /** Learns Spotify's podcast played state carried by Your Episodes pages (docs §6.5). */
    private val progress: EpisodeProgressStore? = null,
) {
    private val saved = SavedStateStore()

    /** Every rootlist fetched (the playlists' current revisions, for their mosaics). Installed by the app graph. */
    @Volatile var onRootlist: ((Rootlist) -> Unit)? = null
    private val lookups = CoalescingBatcher(scope, LOOKUP_WINDOW_MS, LOOKUP_BATCH, ::resolveSaved)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _edits = MutableSharedFlow<LibraryEdit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Logout wipes the response cache; drop the previous account's saved state with it.
        cache.addClearListener { saved.clear() }
    }

    fun playlists(): Flow<Resource<Rootlist>> =
        cache.liveOf(CacheKeys.LIBRARY_PLAYLISTS, Rootlist.serializer(), CacheKeys.TTL_LIBRARY) {
            fetchRootlist().let { CacheFill(it, it.partial) }
        }

    suspend fun likedTracks(offset: Int, limit: Int = 100): Page<SavedTrack> {
        val seq = saved.currentSeq()
        val page = rpc.callOffMain<Page<SavedTrack>>("library.tracks", pageArgs(offset, limit))
        saved.applyLookup(page.items.associate { it.track.uri to true }, seq)
        return page
    }

    /**
     * All Liked Songs URIs (newest first) for play/shuffle/download. Uses `urisOnly` (docs §6.3): no
     * metadata is fetched, so a metadata failure cannot drop songs from the list.
     */
    suspend fun allLikedTrackUris(): List<String> {
        val seq = saved.currentSeq()
        val uris = pageAll(LIKED_URI_PAGE_SIZE) { offset, limit ->
            val page = rpc.callOffMain<UriPage>("library.tracks", rpcArgs {
                put("offset", offset)
                put("limit", limit)
                put("urisOnly", true)
            })
            Page(page.total, page.uris)
        }
        saved.applyLookup(uris.associateWith { true }, seq)
        return uris
    }

    fun albums(): Flow<Resource<List<SavedAlbum>>> =
        savedList(CacheKeys.LIBRARY_ALBUMS, "library.albums", SavedAlbum.serializer(), { it.album.uri }, { it.album.name })

    fun artists(): Flow<Resource<List<SavedArtist>>> =
        savedList(CacheKeys.LIBRARY_ARTISTS, "library.artists", SavedArtist.serializer(), { it.artist.uri }, { it.artist.name })

    fun shows(): Flow<Resource<List<SavedShow>>> =
        savedList(CacheKeys.LIBRARY_SHOWS, "library.shows", SavedShow.serializer(), { it.show.uri }, { it.show.name })

    suspend fun episodes(offset: Int, limit: Int = 50): Page<SavedEpisode> {
        val seq = saved.currentSeq()
        val requestedAt = System.currentTimeMillis()
        val page = rpc.callOffMain<Page<SavedEpisode>>("library.episodes", pageArgs(offset, limit))
        progress?.observe(page.items.map { it.episode }, requestedAt)
        saved.applyLookup(page.items.associate { it.episode.uri to true }, seq)
        return page
    }

    /**
     * Live saved/followed state of [uri] (track/album/artist/show/episode/playlist); null while it
     * is unknown. Emits at once: the known state, or null until the (batched) lookup answers. A
     * failed lookup (offline, the session still connecting, a network error) keeps it null, never a
     * definitive `false`, and is retried when the session comes online ([SavedStateStore.observe]).
     * Show null as a neutral, disabled control; a toggle passes what was shown ([toggleSaved]).
     */
    fun isSaved(uri: String): Flow<Boolean?> = saved.observe(uri, lookups::get, online)

    suspend fun setSaved(uris: List<String>, saved: Boolean) {
        val targets = uris.distinct()
        if (targets.isEmpty()) return
        // Run detached from the caller: a mutation must complete (or roll back) even if the screen
        // that started it goes away.
        scope.async { mutate(targets, saved) }.await()
    }

    /**
     * Toggle of a control that showed [displayed] for [uri]: writes the opposite of what the user
     * saw, never of the server's current state (which may differ, e.g. after a failed lookup showed
     * the wrong state). Unknown (null) writes nothing and only looks the state up again.
     */
    suspend fun toggleSaved(uri: String, displayed: Boolean?) {
        val target = toggleTarget(displayed)
        if (target == null) {
            lookups.request(listOf(uri))
            return
        }
        setSaved(listOf(uri), target)
    }

    /** Emits after any library mutation (lists can refresh). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** What the app changed, with the URIs (likes, playlist edits); [LibraryEdit.Refreshed] for pull-to-refresh. */
    val edits: SharedFlow<LibraryEdit> = _edits.asSharedFlow()

    /**
     * Pull-to-refresh: invalidates every cached library list (the engine's own caches first, so the
     * refetch reaches the server instead of the engine's 30–60 s copies), re-checks recently used
     * saved states and emits [changes]. Liked Songs and the episode pager reload on [changes].
     */
    suspend fun refresh() {
        invalidateNativeLists()
        cache.invalidatePrefix(CacheKeys.LIBRARY_PREFIX)
        lookups.request(saved.recentKeys(MAX_REFRESH_LOOKUPS))
        _changes.emit(Unit)
        // Downloaded Liked Songs and playlists follow what changed elsewhere.
        _edits.tryEmit(LibraryEdit.Refreshed)
    }

    // ---- hooks for PlaylistEditor ------------------------------------------------------------------

    internal suspend fun onPlaylistCreated(uri: String) {
        saved.mutate(listOf(uri), true)
        cache.invalidate(CacheKeys.LIBRARY_PLAYLISTS)
        _changes.emit(Unit)
    }

    internal suspend fun onPlaylistEdited(uri: String) {
        cache.invalidatePrefix(CacheKeys.playlistPrefix(uri))
        // The rootlist carries names and images (the mosaic image changes with the first items).
        cache.invalidate(CacheKeys.LIBRARY_PLAYLISTS)
        _changes.emit(Unit)
        _edits.tryEmit(LibraryEdit.PlaylistEdited(uri))
    }

    /** Public / collaborative changed: the page and the rootlist carry them; the items didn't change. */
    internal suspend fun onPlaylistAttributesChanged(uri: String) {
        cache.invalidatePrefix(CacheKeys.playlistPrefix(uri))
        cache.invalidate(CacheKeys.LIBRARY_PLAYLISTS)
        _changes.emit(Unit)
    }

    internal suspend fun onPlaylistDeleted(uri: String) {
        saved.mutate(listOf(uri), false)
        cache.invalidatePrefix(CacheKeys.playlistPrefix(uri))
        cache.invalidate(CacheKeys.LIBRARY_PLAYLISTS)
        _changes.emit(Unit)
    }

    // ---- internals ----------------------------------------------------------------------------------

    /** `library.invalidate` (docs §6.3); best effort: an error must not keep the app's own lists stale. */
    private suspend fun invalidateNativeLists() {
        try {
            rpc.callUnitOffMain("library.invalidate")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Older engine or no session: the app-side invalidation still runs.
        }
    }

    private suspend fun mutate(uris: List<String>, value: Boolean) {
        val mutation = saved.mutate(uris, value)
        val done = ArrayList<String>(uris.size)
        try {
            val (playlists, others) = uris.partition(SpotifyUris::isPlaylist)
            for (chunk in others.chunked(MUTATION_BATCH)) {
                rpc.callUnitOffMain(if (value) "library.save" else "library.remove", rpcArgs { putStrings("uris", chunk) })
                done += chunk
            }
            for (playlist in playlists) {
                rpc.callUnitOffMain(if (value) "playlist.follow" else "playlist.unfollow", rpcArgs { put("uri", playlist) })
                done += playlist
            }
        } catch (e: Throwable) {
            val doneSet = done.toHashSet()
            saved.rollback(mutation, uris.filterNot(doneSet::contains))
            throw e
        } finally {
            if (done.isNotEmpty()) {
                invalidateListsFor(done)
                _changes.tryEmit(Unit)
                val tracks = done.filter { SpotifyUris.typeOf(it) == "track" }
                if (tracks.isNotEmpty()) _edits.tryEmit(LibraryEdit.LikedTracks(tracks, value))
            }
        }
    }

    private suspend fun invalidateListsFor(uris: Collection<String>) {
        val keys = LinkedHashSet<String>()
        val prefixes = LinkedHashSet<String>()
        uris.forEach { uri ->
            when (SpotifyUris.typeOf(uri)) {
                "album" -> keys += CacheKeys.LIBRARY_ALBUMS
                "artist" -> {
                    keys += CacheKeys.LIBRARY_ARTISTS
                    keys += CacheKeys.artist(uri) // carries `following`
                }
                "show" -> {
                    keys += CacheKeys.LIBRARY_SHOWS
                    keys += CacheKeys.show(uri)
                }
                "playlist" -> {
                    keys += CacheKeys.LIBRARY_PLAYLISTS
                    prefixes += CacheKeys.playlistPrefix(uri)
                }
            }
        }
        keys.forEach { cache.invalidate(it) }
        prefixes.forEach { cache.invalidatePrefix(it) }
    }

    /** Batched lookup used by [lookups]: `library.contains` for items, the rootlist for playlists. */
    private suspend fun resolveSaved(uris: List<String>): Map<String, Boolean> {
        val seq = saved.currentSeq()
        val (playlists, rest) = uris.partition(SpotifyUris::isPlaylist)
        val (others, unsupported) = rest.partition(SpotifyUris::isLibraryItem)
        val results = HashMap<String, Boolean>(uris.size)
        // Contexts such as `spotify:user:<u>:collection` cannot be saved; answer locally instead of
        // letting one odd URI fail the whole batch.
        unsupported.forEach { results[it] = false }
        if (others.isNotEmpty()) {
            val contains = rpc.callOffMain<ContainsResult>("library.contains", rpcArgs { putStrings("uris", others) }).contains
            others.forEachIndexed { i, uri -> results[uri] = contains.getOrElse(i) { false } }
        }
        if (playlists.isNotEmpty()) {
            val followed = followedPlaylists()
            playlists.forEach { results[it] = it in followed }
        }
        saved.applyLookup(results, seq)
        // A concurrent optimistic mutation wins over the (older) server answer.
        return results.mapValues { (uri, value) -> saved.get(uri) ?: value }
    }

    private suspend fun followedPlaylists(): Set<String> {
        val cached = cache.get(CacheKeys.LIBRARY_PLAYLISTS, Rootlist.serializer())
        val rootlist = if (cached != null && ResponseCache.isFresh(cached.second, CacheKeys.TTL_LIBRARY, System.currentTimeMillis())) {
            cached.first
        } else {
            fetchRootlist().also { if (!it.partial) runCatching { cache.put(CacheKeys.LIBRARY_PLAYLISTS, Rootlist.serializer(), it) } }
        }
        return rootlist.flatPlaylists().mapNotNullTo(HashSet()) { it.uri }
    }

    private suspend fun fetchRootlist(): Rootlist {
        val seq = saved.currentSeq()
        val rootlist = rpc.callOffMain<Rootlist>("library.playlists")
        saved.applyLookup(rootlist.flatPlaylists().mapNotNull { it.uri }.associateWith { true }, seq)
        onRootlist?.invoke(rootlist)
        return rootlist
    }

    /**
     * A whole saved list. Every entry counts for the saved state; entries without metadata (nameless
     * placeholders, docs §6.3) are not listed. A list with `partial` pages is not cached as fresh.
     */
    private fun <T> savedList(
        key: String,
        method: String,
        serializer: KSerializer<T>,
        uriOf: (T) -> String,
        nameOf: (T) -> String,
    ): Flow<Resource<List<T>>> =
        cache.liveOf(key, ListSerializer(serializer), CacheKeys.TTL_LIBRARY) {
            val seq = saved.currentSeq()
            val pageSerializer = Page.serializer(serializer)
            val paged = pageAllChecked(LIST_PAGE_SIZE) { offset, limit -> rpc.callWith(pageSerializer, method, pageArgs(offset, limit)) }
            saved.applyLookup(paged.items.associate { uriOf(it) to true }, seq)
            CacheFill(paged.items.filter { nameOf(it).isNotEmpty() }, paged.partial)
        }

    private fun pageArgs(offset: Int, limit: Int) = rpcArgs {
        put("offset", offset)
        put("limit", limit)
    }

    private companion object {
        const val LOOKUP_WINDOW_MS = 50L
        const val LOOKUP_BATCH = 50
        const val MUTATION_BATCH = 50
        /** URI-only pages are cheap; `library.*` accepts up to 500 (docs §6.3). */
        const val LIKED_URI_PAGE_SIZE = 500
        const val LIST_PAGE_SIZE = 100
        const val MAX_REFRESH_LOOKUPS = 200
    }
}

@Serializable
internal data class ContainsResult(val contains: List<Boolean> = emptyList())

/** `library.tracks {urisOnly:true}` result (docs §6.3). */
@Serializable
internal data class UriPage(val total: Int = 0, val uris: List<String> = emptyList())
