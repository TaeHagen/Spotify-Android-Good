package com.taehagen.spotifygood.data

import android.util.Log
import com.taehagen.spotifygood.model.CollectionChangeItem
import com.taehagen.spotifygood.model.CollectionChangedPush
import com.taehagen.spotifygood.model.LibraryPush
import com.taehagen.spotifygood.model.PlaylistChangedPush
import com.taehagen.spotifygood.model.RootlistChangedPush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Library changes made elsewhere (docs/ARCHITECTURE.md §5 `playlistChanged`, `rootlistChanged`,
// `collectionChanged`; §9.8): Spotify pushes every change of the user's playlists, rootlist and
// library sets to every connection of the account, this phone's own edits included.

/** Milliseconds of a monotonic clock (not the wall time a user can change). */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * The library writes this app makes ([PlaylistEditor], [LibraryRepository]), so that their echoes
 * among Spotify's pushes are told apart from changes made elsewhere: the revisions its playlist
 * edits produced and the edits still in flight, and when it last wrote the rootlist or a saved
 * state. A page already shows what an edit made here did (it refetches after it), so its echo
 * needs nothing. Thread safe.
 */
class OwnLibraryEdits(private val clock: () -> Long = ::monotonicMs) {
    private val lock = Any()
    private val playlistsInFlight = HashMap<String, Int>()
    /** The last revisions each playlist's edits produced here (least recently edited first). */
    private val revisions = object : LinkedHashMap<String, ArrayDeque<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<String>>?) = size > MAX_PLAYLISTS
    }
    private var rootlistInFlight = 0
    private var rootlistAt: Long? = null
    /** Saved states written here: uri → (value, when). */
    private val saved = object : LinkedHashMap<String, Pair<Boolean, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, Long>>?) = size > MAX_SAVED
    }
    private val setWrittenAt = HashMap<String, Long>()

    /** An edit of playlist [uri] starts (before its request goes out). */
    fun beginPlaylist(uri: String) {
        val key = SpotifyUris.playlistKey(uri)
        synchronized(lock) { playlistsInFlight[key] = (playlistsInFlight[key] ?: 0) + 1 }
    }

    /** The edit of [uri] begun last ended; [revision]: the one it produced (recorded first). */
    fun endPlaylist(uri: String, revision: String?) {
        val key = SpotifyUris.playlistKey(uri)
        synchronized(lock) {
            if (revision != null) notePlaylistRevision(key, revision)
            val left = (playlistsInFlight[key] ?: 0) - 1
            if (left > 0) {
                playlistsInFlight[key] = left
            } else {
                playlistsInFlight.remove(key)
            }
        }
    }

    /** A revision an edit of [uri] made here produced (an add in several requests makes several). */
    fun notePlaylistRevision(uri: String, revision: String) {
        val key = SpotifyUris.playlistKey(uri)
        val value = revision.lowercase()
        synchronized(lock) {
            val list = revisions.getOrPut(key) { ArrayDeque() }
            if (value !in list) list.addLast(value)
            while (list.size > MAX_REVISIONS) list.removeFirst()
        }
    }

    /** A push of [uri] at [revision] is this app's own edit: one in flight, or a revision one produced. */
    fun isPlaylistEcho(uri: String, revision: String?): Boolean = synchronized(lock) {
        val key = SpotifyUris.playlistKey(uri)
        (playlistsInFlight[key] ?: 0) > 0 || (revision != null && revisions[key]?.contains(revision.lowercase()) == true)
    }

    /** A rootlist write starts (create, delete, follow, unfollow, public). */
    fun beginRootlist() {
        synchronized(lock) { rootlistInFlight++ }
    }

    /** The rootlist write begun last ended. */
    fun endRootlist() {
        synchronized(lock) {
            rootlistInFlight = (rootlistInFlight - 1).coerceAtLeast(0)
            rootlistAt = clock()
        }
    }

    /** A rootlist push now is the echo of a rootlist write made here (in flight, or just done). */
    fun isRootlistEcho(): Boolean = synchronized(lock) {
        rootlistInFlight > 0 || rootlistAt?.let { clock() - it < ECHO_WINDOW_MS } == true
    }

    /** Saved states written here (before the request goes out, and again once it went through). */
    fun noteSaved(uris: Collection<String>, value: Boolean) {
        synchronized(lock) {
            val now = clock()
            for (uri in uris) {
                saved[uri] = value to now
                val set = librarySetOf(uri) ?: continue
                setWrittenAt[set] = now
            }
        }
    }

    /** The items of a push that aren't echoes of saved states written here just before. */
    fun foreignItems(items: List<CollectionChangeItem>): List<CollectionChangeItem> = synchronized(lock) {
        val now = clock()
        items.filterNot { item -> saved[item.uri]?.let { (value, at) -> value == !item.removed && now - at < ECHO_WINDOW_MS } == true }
    }

    /** A push of [set] that names no items, just after a write to it here, is taken for its echo. */
    fun isSetEcho(set: String): Boolean = synchronized(lock) { setWrittenAt[set]?.let { clock() - it < ECHO_WINDOW_MS } == true }

    /** Logout, another account. */
    fun clear() {
        synchronized(lock) {
            playlistsInFlight.clear()
            revisions.clear()
            rootlistInFlight = 0
            rootlistAt = null
            saved.clear()
            setWrittenAt.clear()
        }
    }

    internal companion object {
        /** How long after a write here a push without a revision is taken for its echo. */
        const val ECHO_WINDOW_MS = 10_000L
        private const val MAX_PLAYLISTS = 64
        private const val MAX_REVISIONS = 16
        private const val MAX_SAVED = 500

        /** The library set Spotify keeps [uri] in (`collection/v2`), or null. */
        fun librarySetOf(uri: String): String? = when (SpotifyUris.typeOf(uri)) {
            "track", "album" -> "collection"
            "artist" -> "artist"
            "show" -> "show"
            "episode" -> "listenlater"
            else -> null
        }
    }
}

/** A change made elsewhere, for the pages that show it ([LibraryPushes.changes]). */
sealed interface LibraryChange {
    /** Playlist [uri] (canonical form) changed; [revision]: its revision since, when told. */
    data class Playlist(val uri: String, val revision: String?) : LibraryChange

    /** Library set [set] changed; [items]: what changed, null when that isn't known (reload it). */
    data class Collection(val set: String, val items: List<CollectionChangeItem>?) : LibraryChange
}

/** What a change made elsewhere does outside the pages that show it ([LibraryPushes]). */
interface LibraryPushEffects {
    /** The cached pages of playlist [uri] go stale: kept, revalidated when shown next. No request. */
    suspend fun playlistStale(uri: String)

    /** The cached playlist list goes stale; [reload]: lists on screen reload it now. */
    suspend fun rootlistStale(reload: Boolean)

    /** The cached lists of library [set] go stale; [reload]: lists on screen reload now. */
    suspend fun setStale(set: String, reload: Boolean)

    /** Saved states the push tells (hearts, Follow): memory only. */
    fun savedChanged(items: List<CollectionChangeItem>)

    /** Playlists at new revisions: their mosaics are learned again where they show. */
    fun playlistRevisions(revisions: Map<String, String>)

    /** A downloaded playlist follows its change ([revision]: the one it is at now, when told). */
    suspend fun syncPlaylistDownload(uri: String, revision: String?)

    /** A downloaded Liked Songs follows a change of it. */
    suspend fun syncLikedSongsDownload()
}

/**
 * Applies the library changes Spotify pushes (the engine's `playlistChanged`, `rootlistChanged`,
 * `collectionChanged`): an echo of an edit made here is dropped ([OwnLibraryEdits]); otherwise
 * what costs no request happens at once (cached rows marked stale, saved states), and the pages
 * that show the change hear of it ([changes]; they refresh only while on screen). What may cost
 * requests (the playlist list on screen reloading, mosaics learned again, a downloaded playlist
 * syncing) runs [settleMs] after a burst of pushes and only while the app is in the [foreground]:
 * pushed while it is in the background, it is held until the app comes back. Nothing runs on a
 * timer of its own.
 */
class LibraryPushes(
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
    private val own: OwnLibraryEdits,
    private val effects: LibraryPushEffects,
    private val settleMs: Long = SETTLE_MS,
) {
    /** Changes of one burst of pushes, or held for the foreground. */
    private class Batch {
        val playlists = LinkedHashMap<String, String?>()
        var rootlist = false
        /** Set → its items (null: unknown). */
        val sets = LinkedHashMap<String, MutableList<CollectionChangeItem>?>()

        val isEmpty: Boolean get() = playlists.isEmpty() && !rootlist && sets.isEmpty()

        fun addAll(other: Batch) {
            other.playlists.forEach { (uri, revision) -> addPlaylist(uri, revision) }
            rootlist = rootlist || other.rootlist
            other.sets.forEach { (set, items) -> addSet(set, items) }
        }

        fun addPlaylist(uri: String, revision: String?) {
            // The newest revision told counts; an unknown one doesn't replace a known one.
            if (revision != null || uri !in playlists) playlists[uri] = revision
        }

        /**
         * Pushes of a set in one burst: their items together. One that names none next to one that
         * does is taken for the same change in its other form (Spotify sends a protobuf and a JSON
         * message for one change), not for an unknown one that reloads the set.
         */
        fun addSet(set: String, items: List<CollectionChangeItem>?) {
            if (set !in sets) {
                sets[set] = items?.toMutableList()
                return
            }
            if (items == null) return
            val known = sets[set]
            if (known == null) sets[set] = items.toMutableList() else known += items
        }
    }

    private val lock = Any()
    private var batch = Batch()
    private var held = Batch()
    private var flush: Job? = null

    private val _changes = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Changes made elsewhere, for the pages that show them (an echo of an edit made here is not one). */
    val changes: SharedFlow<LibraryChange> = _changes.asSharedFlow()

    /** The revisions playlist [uri] was pushed at (null: unknown), as they come. */
    fun playlistChanges(uri: String): Flow<String?> {
        val key = SpotifyUris.playlistKey(uri)
        return changes.filterIsInstance<LibraryChange.Playlist>().filter { it.uri == key }.map { it.revision }
    }

    /** Changes of library set [set] (`collection`: Liked Songs), one per burst of pushes. */
    fun collectionChanges(set: String): Flow<LibraryChange.Collection> =
        changes.filterIsInstance<LibraryChange.Collection>().filter { it.set == set }

    /** Follows [pushes] (and the foreground, for what was held) until [scope] ends. */
    fun start(pushes: Flow<LibraryPush>) {
        scope.launch { pushes.collect { attempt { onPush(it) } } }
        scope.launch {
            foreground.collect { visible ->
                if (visible) attempt { applyHeld() }
            }
        }
    }

    /** Logout or another account: nothing pushed for the previous one is applied any more. */
    fun clear() {
        synchronized(lock) {
            batch = Batch()
            held = Batch()
            flush?.cancel()
            flush = null
        }
        own.clear()
    }

    internal suspend fun onPush(push: LibraryPush) {
        when (push) {
            is PlaylistChangedPush -> {
                val uri = SpotifyUris.playlistKey(push.uri)
                val revision = push.revision?.lowercase()
                if (own.isPlaylistEcho(uri, revision)) return
                effects.playlistStale(uri)
                _changes.emit(LibraryChange.Playlist(uri, revision))
                synchronized(lock) { batch.addPlaylist(uri, revision) }
            }
            is RootlistChangedPush -> {
                if (own.isRootlistEcho()) return
                synchronized(lock) { batch.rootlist = true }
            }
            is CollectionChangedPush -> {
                val items = if (push.items.isEmpty()) null else own.foreignItems(push.items)
                when {
                    items == null && own.isSetEcho(push.set) -> return
                    items != null && items.isEmpty() -> return
                }
                items?.let(effects::savedChanged)
                synchronized(lock) { batch.addSet(push.set, items) }
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flush?.isActive == true) return
            flush = scope.launch {
                delay(settleMs)
                // A push from now on starts the next burst.
                synchronized(lock) { flush = null }
                attempt { flushBatch() }
            }
        }
    }

    /** The end of a burst: what costs nothing now; the rest now in the foreground, else held. */
    internal suspend fun flushBatch() {
        val done = synchronized(lock) {
            batch.also { batch = Batch() }
        }
        if (done.isEmpty) return
        if (done.playlists.isNotEmpty() || done.rootlist) effects.rootlistStale(reload = false)
        for ((set, items) in done.sets) {
            effects.setStale(set, reload = false)
            _changes.emit(LibraryChange.Collection(set, items))
        }
        synchronized(lock) { held.addAll(done) }
        if (foreground.value) applyHeld()
    }

    /** In the foreground: what was held (pushed while the app was in the background). */
    private suspend fun applyHeld() {
        val due = synchronized(lock) {
            held.also { held = Batch() }
        }
        if (due.isEmpty) return
        if (due.playlists.isNotEmpty() || due.rootlist) effects.rootlistStale(reload = true)
        val revisions = due.playlists.mapNotNull { (uri, revision) -> revision?.let { uri to it } }.toMap()
        if (revisions.isNotEmpty()) effects.playlistRevisions(revisions)
        due.playlists.forEach { (uri, revision) -> effects.syncPlaylistDownload(uri, revision) }
        for (set in due.sets.keys) effects.setStale(set, reload = true)
        if (COLLECTION_SET in due.sets) effects.syncLikedSongsDownload()
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "A library push was not applied", e)
        }
    }

    internal companion object {
        private const val TAG = "LibraryPushes"
        /** A burst of pushes (one per song added, the two forms of one like) is applied once. */
        const val SETTLE_MS = 1_500L
        /** Liked Songs and saved albums. */
        const val COLLECTION_SET = "collection"
    }
}
