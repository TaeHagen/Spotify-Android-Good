package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack

/** One entry of the media session playlist window; [uid] is unique within the window. */
internal data class WindowEntry(val uid: String, val track: PlaybackTrack)

/**
 * The part of the play queue exposed to Media3 controllers (docs/ARCHITECTURE.md §9.4): the last
 * [PREVIOUS] played tracks, the current track and the next [NEXT] tracks, in actual play order
 * (shuffle already applied by Spirc). Delimiters/hidden entries are dropped and uids are made
 * unique, because Media3 rejects duplicate uids and the same track can be queued twice.
 */
internal data class QueueWindow(val entries: List<WindowEntry>, val currentIndex: Int) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val current: WindowEntry? get() = entries.getOrNull(currentIndex)

    companion object {
        const val PREVIOUS = 10
        const val NEXT = 50

        val EMPTY = QueueWindow(emptyList(), -1)

        fun build(snapshot: PlaybackSnapshot, previous: Int = PREVIOUS, next: Int = NEXT): QueueWindow {
            val current = snapshot.track?.takeIf { isVisible(it) } ?: return EMPTY
            val prev = snapshot.prevTracks.filter(::isVisible).takeLast(previous)
            val upcoming = snapshot.nextTracks.filter(::isVisible).take(next)

            val used = HashSet<String>(prev.size + upcoming.size + 1)
            val occurrences = HashMap<String, Int>()
            fun uidFor(track: PlaybackTrack): String {
                val base = track.uid.ifEmpty {
                    // No Connect uid: derive one from the uri and its occurrence in the window.
                    val n = (occurrences[track.uri] ?: 0) + 1
                    occurrences[track.uri] = n
                    if (n == 1) track.uri else "${track.uri}#$n"
                }
                var candidate = base
                var suffix = 1
                while (!used.add(candidate)) candidate = "$base~${suffix++}"
                return candidate
            }

            // The current entry claims its uid first so it stays stable while the window shifts.
            val currentEntry = WindowEntry(uidFor(current), current)
            val nextEntries = upcoming.map { WindowEntry(uidFor(it), it) }
            val prevEntries = prev.map { WindowEntry(uidFor(it), it) }
            return QueueWindow(prevEntries + currentEntry + nextEntries, prevEntries.size)
        }

        /** Hidden queue delimiters / meta pages are never shown to controllers. */
        fun isVisible(track: PlaybackTrack): Boolean =
            track.uri.isNotEmpty() &&
                !track.uri.startsWith("spotify:delimiter") &&
                !track.uri.startsWith("spotify:meta:")
    }
}

/** What `player.load` should be called with for a set of Media3 media ids. */
internal data class LoadPlan(
    val contextUri: String? = null,
    val trackUris: List<String>? = null,
    val startUri: String? = null,
    val startIndex: Int? = null,
)

/**
 * Media ids used by the browse tree, playback resumption and the session player:
 * * `ctx|<contextUri>|<trackUri>` — a track played inside its context (playlist, album, ...),
 *   keeping Spotify context semantics (autoplay, smart shuffle) without flattening the context;
 * * `dl|<trackUri>` — a downloaded track, played with all downloads as the queue;
 * * a plain Spotify uri — a track/episode on its own or a whole context.
 */
internal object MediaIds {
    private const val SEP = '|'
    private const val CONTEXT = "ctx"
    private const val DOWNLOADED = "dl"

    sealed interface Parsed {
        data class InContext(val contextUri: String, val trackUri: String) : Parsed
        data class Downloaded(val trackUri: String) : Parsed
        data class Plain(val uri: String) : Parsed
        data object Invalid : Parsed
    }

    fun inContext(contextUri: String, trackUri: String): String = "$CONTEXT$SEP$contextUri$SEP$trackUri"

    fun downloaded(trackUri: String): String = "$DOWNLOADED$SEP$trackUri"

    fun parse(mediaId: String): Parsed {
        if (mediaId.isBlank()) return Parsed.Invalid
        val parts = mediaId.split(SEP)
        return when {
            parts.size == 3 && parts[0] == CONTEXT && parts[1].isNotEmpty() && parts[2].isNotEmpty() ->
                Parsed.InContext(parts[1], parts[2])
            parts.size == 2 && parts[0] == DOWNLOADED && parts[1].isNotEmpty() -> Parsed.Downloaded(parts[1])
            parts.size == 1 && mediaId.startsWith("spotify:") -> Parsed.Plain(mediaId)
            else -> Parsed.Invalid
        }
    }

    /** Track or episode (something that is a single queue entry). */
    fun isItemUri(uri: String): Boolean =
        uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:") || uri.startsWith("spotify:local:")

    /**
     * A context Spirc can load by uri (playlist, album, artist, show, Liked Songs, station, ...): the
     * same rule as the engine's `connect/uri.rs` `is_resolvable_context`. Plain track lists report
     * `spotify:web-api…` as their context, which cannot be loaded again.
     */
    fun isResolvableContext(uri: String?): Boolean =
        uri != null && uri.startsWith("spotify:") && !uri.startsWith(WEB_API_CONTEXT) && !isItemUri(uri)

    /** Context uri of plain track lists (Liked Songs as tracks, downloads, search results). */
    const val WEB_API_CONTEXT = "spotify:web-api"

    /** The single playable item behind [mediaId], if any. */
    fun itemUriOf(mediaId: String): String? = when (val p = parse(mediaId)) {
        is Parsed.InContext -> p.trackUri
        is Parsed.Downloaded -> p.trackUri
        is Parsed.Plain -> p.uri.takeIf(::isItemUri)
        Parsed.Invalid -> null
    }

    /**
     * Resolves controller media items (Auto, Assistant, resumption) into a `player.load` request.
     * [downloaded] is only invoked for `dl|` ids.
     */
    fun plan(mediaIds: List<String>, startIndex: Int, downloaded: () -> List<String>): LoadPlan? {
        if (mediaIds.isEmpty()) return null
        val index = startIndex.coerceIn(0, mediaIds.lastIndex)
        return when (val start = parse(mediaIds[index])) {
            // Ids cached by a browser may name a context that cannot be loaded: play the item alone.
            is Parsed.InContext -> if (isResolvableContext(start.contextUri)) {
                LoadPlan(contextUri = start.contextUri, startUri = start.trackUri)
            } else {
                LoadPlan(trackUris = listOf(start.trackUri), startIndex = 0)
            }
            is Parsed.Downloaded -> {
                val all = downloaded()
                val queue = if (start.trackUri in all) all else listOf(start.trackUri) + all
                LoadPlan(trackUris = queue, startIndex = queue.indexOf(start.trackUri))
            }
            is Parsed.Plain -> if (isItemUri(start.uri)) {
                val tracks = ArrayList<String>(mediaIds.size)
                var startAt = 0
                mediaIds.forEachIndexed { i, id ->
                    val uri = itemUriOf(id) ?: return@forEachIndexed
                    if (i == index) startAt = tracks.size
                    tracks += uri
                }
                LoadPlan(trackUris = tracks, startIndex = startAt)
            } else {
                LoadPlan(contextUri = start.uri)
            }
            Parsed.Invalid -> null
        }
    }
}
