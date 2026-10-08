package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection

/**
 * The downloads in the media browser (Android Auto / AAOS, docs/ARCHITECTURE.md §9.4), pure rules
 * (JVM-testable) for [LibraryTree]: the Downloads tab grouped as the app's Downloads screen is
 * (Liked Songs and playlists, albums, podcasts, then the songs and the episodes downloaded on their
 * own), and downloaded collections listed from the download database (paged by [BrowsePaging]).
 */
internal object OfflineTree {
    /** A section of the Downloads tab, in tab order. */
    enum class Section { PLAYLISTS, ALBUMS, PODCASTS, SONGS, EPISODES }

    sealed interface Row {
        val section: Section

        /** A downloaded collection: browsable (its downloads) and playable (as its context). */
        data class Collection(val collection: DownloadedCollection, override val section: Section) : Row

        /** A song or an episode downloaded on its own. */
        data class Item(val uri: String, override val section: Section) : Row
    }

    /** Completed downloads ([downloaded] newest first) that are no part of a downloaded collection. */
    data class Singles(val songs: List<String>, val episodes: List<String>) {
        /** The section a `dl|` row of [uri] is listed (and played) in. */
        fun sectionOf(uri: String): List<String> = if (isEpisode(uri)) episodes else songs
    }

    fun singles(downloaded: Collection<String>, collections: List<DownloadedCollection>): Singles {
        val members = HashSet<String>()
        collections.forEach { members.addAll(it.itemUris) }
        val songs = ArrayList<String>()
        val episodes = ArrayList<String>()
        for (uri in downloaded) {
            if (uri in members) continue
            if (isEpisode(uri)) episodes += uri else songs += uri
        }
        return Singles(songs, episodes)
    }

    /**
     * The rows of the Downloads tab: the collections ([collections] newest first) with something
     * that plays offline ([downloaded]), Liked Songs first, then the [singles] newest first.
     */
    fun tab(collections: List<DownloadedCollection>, downloaded: Set<String>, singles: Singles = singles(downloaded, collections)): List<Row> {
        val playable = collections.filter { c -> c.itemUris.any(downloaded::contains) }
        val playlists = playable
            .filter { it.ref.type == CollectionType.LIKED_SONGS || it.ref.type == CollectionType.PLAYLIST }
            .sortedBy { if (it.ref.type == CollectionType.LIKED_SONGS) 0 else 1 }
        return buildList {
            playlists.mapTo(this) { Row.Collection(it, Section.PLAYLISTS) }
            playable.filter { it.ref.type == CollectionType.ALBUM }.mapTo(this) { Row.Collection(it, Section.ALBUMS) }
            playable.filter { it.ref.type == CollectionType.SHOW }.mapTo(this) { Row.Collection(it, Section.PODCASTS) }
            singles.songs.mapTo(this) { Row.Item(it, Section.SONGS) }
            singles.episodes.mapTo(this) { Row.Item(it, Section.EPISODES) }
        }
    }

    /**
     * The downloaded collection browsed as [parentId]: Liked Songs by its kind (any of its uris, or
     * [likedFolder] for the Library folder), any other by its uri. Null when it is not downloaded.
     */
    fun collectionOf(parentId: String, collections: List<DownloadedCollection>, likedFolder: String = LibraryTree.LIKED): DownloadedCollection? {
        val liked = parentId == likedFolder || OfflineLoads.kindOf(parentId) == OfflineLoads.ContextKind.LIKED_SONGS
        return if (liked) {
            collections.firstOrNull { it.ref.type == CollectionType.LIKED_SONGS }
        } else {
            collections.firstOrNull { it.ref.uri == parentId }
        }
    }

    /** Whether [parentId] may be a downloaded collection ([collectionOf]). */
    fun mayBeCollection(parentId: String, likedFolder: String = LibraryTree.LIKED): Boolean =
        parentId == likedFolder || OfflineLoads.kindOf(parentId) != null

    /** The items of [collection] that play offline, in collection order (what an offline load of it plays). */
    fun playableItems(collection: DownloadedCollection, downloaded: Set<String>): List<String> =
        collection.itemUris.filter(downloaded::contains)

    private fun isEpisode(uri: String) = uri.startsWith("spotify:episode:")
}
