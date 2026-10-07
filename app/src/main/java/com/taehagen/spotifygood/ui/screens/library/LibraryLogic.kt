package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.RootlistEntryType
import com.taehagen.spotifygood.model.SavedAlbum
import com.taehagen.spotifygood.model.SavedArtist
import com.taehagen.spotifygood.model.SavedShow
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Collator
import java.util.Locale

// Pure Your Library shaping: items, folders, filters and sort orders (JVM-testable).

/**
 * Which loaded pages of a [PagedLoader] list came back `partial` (some items are uri-only
 * placeholders, docs §6.3), by offset. A page fetched at offset 0 starts the list over. Thread-safe.
 */
class PartialPages {
    private val offsets = HashSet<Int>()
    private val _partial = MutableStateFlow(false)

    /** True while any loaded page is partial: the list offers a retry. */
    val partial: StateFlow<Boolean> = _partial.asStateFlow()

    @Synchronized
    fun record(offset: Int, partial: Boolean) {
        if (offset == 0) offsets.clear()
        if (partial) offsets += offset else offsets -= offset
        _partial.value = offsets.isNotEmpty()
    }
}

enum class LibraryFilter { PLAYLISTS, ALBUMS, ARTISTS, PODCASTS, DOWNLOADED }

enum class LibraryItemKind { PLAYLIST, FOLDER, ALBUM, ARTIST, SHOW }

@Immutable
data class LibraryItem(
    val kind: LibraryItemKind,
    /** URI, or a synthetic id for folders without one. */
    val id: String,
    val name: String,
    val images: List<Image> = emptyList(),
    /** Playlist owner, album artists or podcast publisher. */
    val creator: String? = null,
    val ownerUsername: String? = null,
    val addedAt: Long? = null,
    /** Position in its source list (rootlist order for playlists). */
    val sourceIndex: Int = 0,
    val children: List<LibraryItem> = emptyList(),
) {
    val key: String get() = "${kind.name}:$id"
    val imageUrl: String? get() = images.best(160)

    fun toMediaRef(): MediaRef = MediaRef(
        type = when (kind) {
            LibraryItemKind.PLAYLIST, LibraryItemKind.FOLDER -> MediaType.PLAYLIST
            LibraryItemKind.ALBUM -> MediaType.ALBUM
            LibraryItemKind.ARTIST -> MediaType.ARTIST
            LibraryItemKind.SHOW -> MediaType.SHOW
        },
        uri = id,
        name = name,
        subtitle = creator,
        images = images,
    )

    /** Playlists inside this folder, recursively. */
    fun playlistCount(): Int = children.sumOf { if (it.kind == LibraryItemKind.FOLDER) it.playlistCount() else 1 }

    fun actionTarget(myUsername: String?): MediaActionTarget? = when (kind) {
        LibraryItemKind.FOLDER -> null
        LibraryItemKind.PLAYLIST -> MediaActionTarget.PlaylistTarget(
            PlaylistRef(
                uri = id,
                name = name,
                images = images,
                owner = ownerUsername?.let { PlaylistOwner(it, creator) },
            ),
            isOwned = myUsername != null && ownerUsername == myUsername,
        )
        LibraryItemKind.ALBUM -> MediaActionTarget.AlbumTarget(AlbumRef(uri = id, name = name, images = images))
        LibraryItemKind.ARTIST -> MediaActionTarget.ArtistTarget(ArtistRef(uri = id, name = name, images = images))
        LibraryItemKind.SHOW -> MediaActionTarget.ShowTarget(ShowRef(uri = id, name = name, publisher = creator, images = images))
    }
}

/** Converts the rootlist into library items, keeping folders as items with children. */
fun Rootlist.toLibraryItems(): List<LibraryItem> {
    fun convert(entries: List<RootlistEntry>, path: String): List<LibraryItem> = entries.mapIndexedNotNull { index, entry ->
        when (entry.type) {
            RootlistEntryType.FOLDER -> {
                val id = entry.uri ?: "folder:$path$index"
                LibraryItem(
                    kind = LibraryItemKind.FOLDER,
                    id = id,
                    name = entry.name,
                    images = entry.images,
                    sourceIndex = index,
                    children = convert(entry.children, "$path$index/"),
                )
            }
            RootlistEntryType.PLAYLIST -> entry.uri?.let { uri ->
                LibraryItem(
                    kind = LibraryItemKind.PLAYLIST,
                    id = uri,
                    name = entry.name,
                    images = entry.images,
                    creator = entry.owner?.displayName ?: entry.owner?.username,
                    ownerUsername = entry.owner?.username,
                    sourceIndex = index,
                )
            }
        }
    }
    return convert(items, "")
}

fun List<SavedAlbum>.albumItems(): List<LibraryItem> = mapIndexed { index, saved ->
    LibraryItem(
        kind = LibraryItemKind.ALBUM,
        id = saved.album.uri,
        name = saved.album.name,
        images = saved.album.images,
        creator = saved.album.artists.joinToString { it.name }.ifEmpty { null },
        addedAt = saved.addedAt,
        sourceIndex = index,
    )
}

fun List<SavedArtist>.artistItems(): List<LibraryItem> = mapIndexed { index, saved ->
    LibraryItem(
        kind = LibraryItemKind.ARTIST,
        id = saved.artist.uri,
        name = saved.artist.name,
        images = saved.artist.images,
        addedAt = saved.addedAt,
        sourceIndex = index,
    )
}

fun List<SavedShow>.showItems(): List<LibraryItem> = mapIndexed { index, saved ->
    LibraryItem(
        kind = LibraryItemKind.SHOW,
        id = saved.show.uri,
        name = saved.show.name,
        images = saved.show.images,
        creator = saved.show.publisher,
        addedAt = saved.addedAt,
        sourceIndex = index,
    )
}

/** All playlists, folders flattened (depth first). */
fun List<LibraryItem>.flattenFolders(): List<LibraryItem> = buildList {
    fun walk(items: List<LibraryItem>) {
        items.forEach { if (it.kind == LibraryItemKind.FOLDER) walk(it.children) else add(it) }
    }
    walk(this@flattenFolders)
}

/** Folder chain for [path] (folder ids from the top level); stops at the first unknown id. */
fun resolveFolderPath(playlists: List<LibraryItem>, path: List<String>): List<LibraryItem> {
    val chain = ArrayList<LibraryItem>(path.size)
    var level = playlists
    for (id in path) {
        val folder = level.firstOrNull { it.kind == LibraryItemKind.FOLDER && it.id == id } ?: break
        chain += folder
        level = folder.children
    }
    return chain
}

/** Comparator for [sort]; [recentRank] maps URIs to recency (0 = most recent). */
fun libraryComparator(
    sort: LibrarySort,
    recentRank: Map<String, Int> = emptyMap(),
    collator: Collator = defaultCollator(),
): Comparator<LibraryItem> {
    val byName = Comparator<LibraryItem> { a, b -> collator.compare(a.name, b.name) }
    // Playlists/folders (no timestamp) keep rootlist order, newest-first by convention, ahead of
    // timestamped saves; timestamped items newest first; ties by source position.
    val byAdded = compareBy<LibraryItem> { if (it.addedAt == null) 0 else 1 }
        .thenByDescending { it.addedAt ?: Long.MAX_VALUE }
        .thenBy { it.sourceIndex }
    return when (sort) {
        LibrarySort.RECENT -> compareBy<LibraryItem> { recentRank[it.id] ?: Int.MAX_VALUE }.then(byAdded)
        LibrarySort.RECENTLY_ADDED -> byAdded
        LibrarySort.ALPHABETICAL -> byName.thenBy { it.kind.ordinal }
        LibrarySort.CREATOR -> compareBy<LibraryItem> { it.creator == null }
            .then { a, b -> collator.compare(a.creator.orEmpty(), b.creator.orEmpty()) }
            .then(byName)
    }
}

fun defaultCollator(locale: Locale = Locale.getDefault()): Collator =
    Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

fun LibraryItem.matchesQuery(query: String): Boolean =
    query.isBlank() || name.contains(query, ignoreCase = true) || creator?.contains(query, ignoreCase = true) == true

/** Inputs of the visible library list. */
data class LibraryQuery(
    val filter: LibraryFilter? = null,
    val folderPath: List<String> = emptyList(),
    val text: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val offline: Boolean = false,
)

@Immutable
data class LibraryListing(
    val items: List<LibraryItem>,
    /** Open folders, outermost first (empty at the top level). */
    val folders: List<LibraryItem>,
)

/**
 * A downloaded collection as a library row (downloads need not be saved in the library, and the
 * saved lists may be gone from the cache offline). Liked Songs has its own pinned row: null.
 */
fun DownloadedCollection.toLibraryItem(): LibraryItem? {
    val kind = when (type) {
        CollectionType.ALBUM -> LibraryItemKind.ALBUM
        CollectionType.PLAYLIST -> LibraryItemKind.PLAYLIST
        CollectionType.SHOW -> LibraryItemKind.SHOW
        CollectionType.LIKED_SONGS -> return null
    }
    return LibraryItem(
        kind = kind,
        id = uri,
        name = name,
        images = imageUrl?.takeIf { it.isNotBlank() }?.let { listOf(Image(it)) }.orEmpty(),
        addedAt = addedAt,
    )
}

/**
 * The items to show: the open folder's children, or the top level for the filter. Text search
 * and the Downloaded filter / offline mode look through folders; downloaded-only views contain
 * only items in [downloaded], and also list [downloadedItems] (downloads that are not, or no
 * longer, in the saved lists; a saved item wins over its download row).
 */
fun buildLibraryListing(
    playlists: List<LibraryItem>,
    albums: List<LibraryItem>,
    artists: List<LibraryItem>,
    shows: List<LibraryItem>,
    query: LibraryQuery,
    downloaded: Set<String>,
    downloadedItems: List<LibraryItem> = emptyList(),
    recentRank: Map<String, Int> = emptyMap(),
    collator: Collator = defaultCollator(),
): LibraryListing {
    val downloadedOnly = query.offline || query.filter == LibraryFilter.DOWNLOADED
    val searching = query.text.isNotBlank()
    val folders = if (downloadedOnly || searching) emptyList() else resolveFolderPath(playlists, query.folderPath)
    val flatten = downloadedOnly || searching

    val base: List<LibraryItem> = when {
        folders.isNotEmpty() -> folders.last().children
        else -> {
            val playlistItems = if (flatten) playlists.flattenFolders() else playlists
            val saved = when (query.filter) {
                null, LibraryFilter.DOWNLOADED -> playlistItems + albums + artists + shows
                LibraryFilter.PLAYLISTS -> playlistItems
                LibraryFilter.ALBUMS -> albums
                LibraryFilter.ARTISTS -> artists
                LibraryFilter.PODCASTS -> shows
            }
            // Saved items first: distinctBy below keeps their richer rows (creator, owner).
            if (downloadedOnly) saved + downloadedItems.filter { it.kind in query.filter.kinds() } else saved
        }
    }
    val filtered = base.asSequence()
        .filter { !downloadedOnly || it.id in downloaded }
        .filter { it.matchesQuery(query.text) }
        .distinctBy { it.key }
        .sortedWith(libraryComparator(query.sort, recentRank, collator))
        .toList()
    return LibraryListing(filtered, folders)
}

/** Item kinds a filter shows (no filter / Downloaded: all). */
private fun LibraryFilter?.kinds(): Set<LibraryItemKind> = when (this) {
    null, LibraryFilter.DOWNLOADED -> LibraryItemKind.entries.toSet()
    LibraryFilter.PLAYLISTS -> setOf(LibraryItemKind.PLAYLIST, LibraryItemKind.FOLDER)
    LibraryFilter.ALBUMS -> setOf(LibraryItemKind.ALBUM)
    LibraryFilter.ARTISTS -> setOf(LibraryItemKind.ARTIST)
    LibraryFilter.PODCASTS -> setOf(LibraryItemKind.SHOW)
}

/** Recency ranks from recently played references (first occurrence wins). */
fun recentRanks(recent: List<MediaRef>): Map<String, Int> {
    val ranks = HashMap<String, Int>(recent.size)
    recent.forEachIndexed { index, ref -> ranks.putIfAbsent(ref.uri, index) }
    return ranks
}

/** Pinned rows above the library list. */
@Immutable
sealed interface PinnedEntry {
    val key: String

    data class LikedSongs(val count: Int?, val downloaded: Boolean) : PinnedEntry {
        override val key: String get() = "pinned:liked"
    }

    data object YourEpisodes : PinnedEntry {
        override val key: String = "pinned:episodes"
    }

    data class Downloads(val count: Int) : PinnedEntry {
        override val key: String get() = "pinned:downloads"
    }
}

fun pinnedEntries(
    query: LibraryQuery,
    likedCount: Int?,
    likedDownloaded: Boolean,
    downloadCount: Int,
): List<PinnedEntry> {
    if (query.text.isNotBlank() || query.folderPath.isNotEmpty()) return emptyList()
    val liked = PinnedEntry.LikedSongs(likedCount, likedDownloaded)
    val downloads = PinnedEntry.Downloads(downloadCount)
    if (query.offline) {
        return when (query.filter) {
            null, LibraryFilter.DOWNLOADED, LibraryFilter.PLAYLISTS -> listOfNotNull(liked.takeIf { likedDownloaded }, downloads)
            else -> emptyList()
        }
    }
    return when (query.filter) {
        null -> listOf(liked, downloads)
        LibraryFilter.PLAYLISTS -> listOf(liked)
        LibraryFilter.PODCASTS -> listOf(PinnedEntry.YourEpisodes)
        LibraryFilter.DOWNLOADED -> listOfNotNull(liked.takeIf { likedDownloaded }, downloads)
        LibraryFilter.ALBUMS, LibraryFilter.ARTISTS -> emptyList()
    }
}
