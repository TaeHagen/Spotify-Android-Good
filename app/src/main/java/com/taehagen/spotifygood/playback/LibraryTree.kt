package com.taehagen.spotifygood.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.data.db.DownloadEntity
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection
import com.taehagen.spotifygood.engine.accountExplicitFilter
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Browse tree for Android Auto / AAOS and other media browsers (docs/ARCHITECTURE.md §9.4):
 * at most four tabs — Home (recently played + home feed), Library (Liked Songs, playlists, albums,
 * artists, podcasts), Downloads (playable offline; the only tab for offline requests) and Browse
 * (recent searches, followed artists). Spotify contexts are browsable by their uri; playable
 * children carry `ctx|<context>|<track>` ids so Spotify context semantics are kept. The downloads
 * ([OfflineTree]) are listed from the download database, a page at a time ([pagedChildren]).
 *
 * All data comes from the repositories (cached, stale-while-revalidate); every lookup is bounded
 * by a timeout so a browser never waits forever.
 */
internal class LibraryTree(context: Context, private val graph: AppGraph) {
    private val context = context.applicationContext

    // ---- root -----------------------------------------------------------------------------

    fun root(params: LibraryParams?): MediaItem = when {
        params?.isOffline == true -> folder(ROOT_OFFLINE, R.string.app_name)
        params?.isRecent == true -> folder(ROOT_RECENT, R.string.app_name)
        else -> folder(ROOT, R.string.app_name)
    }

    /** Content-style hints returned with the root (Auto reads them from the root extras). */
    fun rootParams(params: LibraryParams?): LibraryParams = LibraryParams.Builder()
        .setOffline(params?.isOffline == true)
        .setRecent(params?.isRecent == true)
        .setExtras(
            Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            },
        )
        .build()

    private fun tabs(params: LibraryParams?): List<MediaItem> {
        val limit = params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, MAX_TABS)
            ?.takeIf { it > 0 } ?: MAX_TABS
        val settings = graph.settings.settings.value
        // Holds no engine holder: the network as of now, also while the engine is stopped.
        val offline = settings.offlineMode || !graph.engine.currentNetworkAvailable()
        val home = folder(HOME, R.string.playback_tab_home, R.drawable.pb_ic_auto_home)
        val library = folder(LIBRARY, R.string.playback_tab_library, R.drawable.pb_ic_auto_library, listStyle = true)
        val downloads = folder(DOWNLOADS, R.string.playback_tab_downloads, R.drawable.pb_ic_auto_downloads)
        val browse = folder(BROWSE, R.string.playback_tab_browse, R.drawable.pb_ic_auto_browse)
        val ordered = if (offline) listOf(downloads, library, home, browse) else listOf(home, library, downloads, browse)
        return ordered.take(limit.coerceAtMost(MAX_TABS))
    }

    // ---- children ---------------------------------------------------------------------------

    /**
     * Children of [parentId] as page [page] of [pageSize] ([BrowsePaging]). The lists (Library's,
     * Liked Songs, a playlist's, album's or show's rows, the downloads) are read a window at a
     * time, as Media3 asks, and end with a "More" row when the browser would not reach the rest
     * (it does not page, or asked for more than a window). The composite parents (the tabs, Home,
     * Browse, an artist) are [children].
     */
    suspend fun pagedChildren(parentId: String, page: Int, pageSize: Int, params: LibraryParams?): List<MediaItem>? {
        val more = BrowsePaging.parseMore(parentId)
        val listId = more?.parentId ?: parentId
        val source = try {
            sourceOf(listId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "List $listId failed", e)
            return null
        }
        if (source == null) {
            if (more != null) return null
            val request = BrowsePaging.request(0, page, pageSize, window = Int.MAX_VALUE) ?: return emptyList()
            return children(parentId, params)?.let { BrowsePaging.slice(it, request.from, request.count) }
        }
        val request = BrowsePaging.request(more?.offset ?: 0, page, pageSize) ?: return emptyList()
        val rows = try {
            source.load(request.from, request.count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Rows ${request.from}+${request.count} of $listId failed", e)
            return null
        }
        val moreAt = BrowsePaging.moreAt(request, rows.total)
        return if (moreAt == null) rows.items else rows.items + moreItem(listId, moreAt)
    }

    /** Children of a composite parent (bounded: the tabs, Home, Browse, an artist's page). */
    private suspend fun children(parentId: String, params: LibraryParams?): List<MediaItem>? = try {
        when (parentId) {
            ROOT -> tabs(params)
            ROOT_OFFLINE -> listOf(folder(DOWNLOADS, R.string.playback_tab_downloads, R.drawable.pb_ic_auto_downloads))
            ROOT_RECENT -> recentItem()?.let(::listOf).orEmpty()
            HOME -> home()
            LIBRARY -> listOf(
                likedFolder(),
                folder(PLAYLISTS, R.string.playback_playlists, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(ALBUMS, R.string.playback_albums, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(ARTISTS, R.string.playback_artists, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(PODCASTS, R.string.playback_podcasts, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            )
            BROWSE -> browse()
            else -> artistChildren(parentId)
        }?.take(MAX_ITEMS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "children($parentId) failed", e)
        null
    }

    private suspend fun home(): List<MediaItem> {
        val recentTitle = context.getString(R.string.playback_recently_played)
        val recent = runCatching { graph.catalog.recentlyPlayed(RECENT_LIMIT) }.getOrDefault(emptyList())
            .take(RECENT_LIMIT)
            .mapNotNull { mediaRefItem(it, recentTitle) }
        val sections = graph.home.home().settle()?.sections.orEmpty()
            .take(HOME_SECTIONS)
            .flatMap { section -> section.items.take(HOME_SECTION_ITEMS).mapNotNull { mediaRefItem(it, section.title) } }
        return (recent + sections).distinctBy { it.mediaId }
    }

    private suspend fun browse(): List<MediaItem> {
        val recentTitle = context.getString(R.string.playback_recent_searches)
        val recent = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { graph.search.recent.first() }.orEmpty()
            .filterIsInstance<RecentSearch.Item>()
            .mapNotNull { mediaRefItem(it.ref, recentTitle) }
        val artistsTitle = context.getString(R.string.playback_artists)
        val artists = graph.library.artists().settle().orEmpty().take(BROWSE_ARTISTS).mapNotNull { artistItem(it.artist, artistsTitle) }
        return (recent + artists).distinctBy { it.mediaId }
    }

    /** An artist's page: popular tracks and releases (bounded by the catalog). */
    private suspend fun artistChildren(uri: String): List<MediaItem>? = when {
        uri.startsWith("spotify:artist:") -> graph.catalog.artist(uri).settle()?.let { artist ->
            val popular = context.getString(R.string.playback_popular)
            val albums = context.getString(R.string.playback_albums)
            artist.topTracks.mapNotNull { trackItem(it, uri, group = popular) } +
                (artist.albums + artist.singles).mapNotNull { albumItem(it, albums) }
        }
        else -> null
    }

    // ---- lists, a window at a time ------------------------------------------------------------

    /** Rows of a list: [items] from the start asked for (fewer at its end), and its size. */
    private class Rows(val items: List<MediaItem>, val total: Int)

    /** A list read a window at a time ([BrowsePaging]). */
    private fun interface Source {
        /** The rows [from] until `from + count`. */
        suspend fun load(from: Int, count: Int): Rows
    }

    /** The list behind [listId]; null for a composite parent ([children]). */
    private suspend fun sourceOf(listId: String): Source? = downloadsSource(listId) ?: catalogSource(listId)

    /** A list in memory (a saved list, an album), its rows built a window at a time. */
    private fun <T> listSource(list: List<T>, row: (T) -> MediaItem?): Source =
        Source { from, count -> Rows(BrowsePaging.slice(list, from, count).mapNotNull(row), list.size) }

    /** Library's lists, Liked Songs, a playlist's, album's or show's rows from the catalog; null for any other. */
    private suspend fun catalogSource(listId: String): Source? = when {
        listId == PLAYLISTS -> listSource(graph.library.playlists().settle()?.flatPlaylists().orEmpty()) { entry ->
            entry.uri?.let { contextItem(it, entry.name, entry.owner?.displayName ?: entry.owner?.username, entry.images, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
        }
        listId == ALBUMS -> listSource(graph.library.albums().settle().orEmpty()) { albumItem(it.album) }
        listId == ARTISTS -> listSource(graph.library.artists().settle().orEmpty()) { artistItem(it.artist) }
        listId == PODCASTS -> listSource(graph.library.shows().settle().orEmpty()) { showItem(it.show) }
        listId == LIKED || OfflineLoads.kindOf(listId) == OfflineLoads.ContextKind.LIKED_SONGS -> likedSource()
        listId.startsWith("spotify:playlist:") -> playlistSource(listId)
        listId.startsWith("spotify:album:") -> graph.catalog.album(listId).settle()?.let { album ->
            listSource(album.tracks) { trackItem(it, listId, fallbackImages = album.images) }
        }
        listId.startsWith("spotify:show:") -> showSource(listId)
        else -> null
    }

    /** Liked Songs (`library.tracks`, newest first), [LIBRARY_PAGE] a call. */
    private fun likedSource(): Source = Source { from, count ->
        val likedUri = likedContextUri()
        val fetched = BrowsePaging.fetchWindow(from, count, LIBRARY_PAGE) { offset, limit ->
            graph.library.likedTracks(offset, limit).let { BrowsePaging.Fetched(it.items, it.total) }
        }
        Rows(fetched.items.mapNotNull { trackItem(it.track, likedUri) }, fetched.total)
    }

    /** A playlist's rows: the first page as cached (the app shows the same), the rest fetched. */
    private suspend fun playlistSource(uri: String): Source? {
        val first = graph.catalog.playlist(uri, MAX_ITEMS).settle() ?: return null
        val total = maxOf(first.total, first.items.size)
        return Source { from, count ->
            val items = BrowsePaging.window(first.items, total, from, count, CATALOG_PAGE) { offset, limit ->
                graph.catalog.playlistPage(uri, offset, limit).items
            }
            Rows(items.mapNotNull { item -> item.track?.let { trackItem(it, uri) } ?: item.episode?.let { episodeItem(it, uri) } }, total)
        }
    }

    /** A show's episodes (newest first): the first page as cached, the rest fetched. */
    private suspend fun showSource(uri: String): Source? {
        val first = graph.catalog.show(uri).settle() ?: return null
        val total = maxOf(first.total, first.episodes.size)
        return Source { from, count ->
            val episodes = BrowsePaging.window(first.episodes, total, from, count, CATALOG_PAGE) { offset, limit ->
                graph.catalog.showPage(uri, offset, limit).episodes
            }
            Rows(episodes.mapNotNull { episodeItem(it, uri, fallbackImages = first.images) }, total)
        }
    }

    /** The "More" row of [listId], from [offset] on ([BrowsePaging]). */
    private fun moreItem(listId: String, offset: Int): MediaItem = MediaItem.Builder()
        .setMediaId(BrowsePaging.moreId(listId, offset))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(context.getString(R.string.playback_more))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    // ---- offline tree: the downloads ------------------------------------------------------------

    /**
     * Whether [parentId] is browsed from the downloads alone now: offline, a downloaded
     * collection (the Downloads tab always is, [needsSession]). No session to wait for or start.
     */
    suspend fun browsesDownloads(parentId: String): Boolean {
        val listId = BrowsePaging.listIdOf(parentId)
        return isOffline() && OfflineTree.mayBeCollection(listId) && OfflineTree.collectionOf(listId, downloadedCollections()) != null
    }

    /**
     * The download-backed list of [listId]: the Downloads tab, or a downloaded collection. Offline
     * that is its downloads; online the catalog's copy (all of it, a window at a time, in its current
     * order), and its downloads when the catalog has nothing for it (no session, a failed or empty
     * answer, a cleared cache). Null when it is neither.
     */
    private suspend fun downloadsSource(listId: String): Source? {
        if (listId == DOWNLOADS) return downloadsTab()
        if (!OfflineTree.mayBeCollection(listId)) return null
        val collection = OfflineTree.collectionOf(listId, downloadedCollections()) ?: return null
        val downloads = collectionSource(collection)
        if (isOffline()) return downloads
        val catalog = try {
            catalogSource(listId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Catalog copy of $listId failed", e)
            null
        } ?: return downloads
        return Source { from, count ->
            val rows = try {
                catalog.load(from, count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Catalog rows of $listId failed, listing the downloads", e)
                null
            }
            rows?.takeIf { it.total > 0 } ?: downloads.load(from, count)
        }
    }

    /** The Downloads tab, grouped as the app's Downloads screen ([OfflineTree.tab]). */
    private suspend fun downloadsTab(): Source {
        val rows = OfflineTree.tab(downloadedCollections(), downloadedUris())
        return Source { from, count ->
            val page = BrowsePaging.slice(rows, from, count)
            val stored = storedDownloads(page.mapNotNull { (it as? OfflineTree.Row.Item)?.uri })
            val filter = explicitFilter()
            val items = page.mapNotNull { row ->
                val group = context.getString(sectionTitle(row.section))
                when (row) {
                    is OfflineTree.Row.Collection -> downloadedCollectionItem(row.collection, group)
                    is OfflineTree.Row.Item -> stored[row.uri]?.let { downloadedItem(it, contextUri = null, group = group, filter = filter) }
                }
            }
            Rows(items, rows.size)
        }
    }

    /** The downloads of [collection] in collection order, as `ctx|` items (an offline load plays them in it). */
    private suspend fun collectionSource(collection: DownloadedCollection): Source {
        val uris = OfflineTree.playableItems(collection, downloadedUris())
        val ref = collection.ref
        val contextUri = if (ref.type == CollectionType.LIKED_SONGS) likedContextUri() ?: ref.uri else ref.uri
        return Source { from, count ->
            val page = BrowsePaging.slice(uris, from, count)
            val stored = storedDownloads(page)
            val filter = explicitFilter()
            Rows(page.mapNotNull { uri -> stored[uri]?.let { downloadedItem(it, contextUri, group = null, filter = filter) } }, uris.size)
        }
    }

    private suspend fun downloadedCollections(): List<DownloadedCollection> =
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { graph.downloads.collections.first() }.orEmpty()

    /**
     * The completed downloads that play, newest first ([com.taehagen.spotifygood.download.DownloadManager.downloadedUris]).
     * Right after a cold start (Auto binds the service first) that set may not be read yet: when
     * the database has completed downloads, wait for it.
     */
    private suspend fun downloadedUris(): Set<String> {
        val hot = graph.downloads.downloadedUris
        if (hot.value.isNotEmpty()) return hot.value
        if (graph.database.downloads().observeCompletedUris().first().isEmpty()) return emptySet()
        return withTimeoutOrNull(FIRST_READ_TIMEOUT_MS) { hot.first { it.isNotEmpty() } } ?: hot.value
    }

    /** The download rows of [uris] (metadata and cover stored at download time). */
    private suspend fun storedDownloads(uris: List<String>): Map<String, DownloadEntity> {
        if (uris.isEmpty()) return emptyMap()
        val dao = graph.database.downloads()
        return uris.chunked(SQL_CHUNK).flatMap { dao.getAll(it) }.associateBy { it.uri }
    }

    /**
     * A downloaded song / episode from its stored metadata (always marked playable: [filter]
     * (Hide explicit content) makes an explicit one unplayable, as the player refuses it), with its
     * downloaded cover and the downloaded status; `ctx|` in [contextUri], otherwise `dl|`.
     */
    private fun downloadedItem(row: DownloadEntity, contextUri: String?, group: String?, filter: Boolean): MediaItem? {
        val json = row.metadataJson ?: return null
        val item = if (row.uri.startsWith("spotify:episode:")) {
            val episode = runCatching { graph.json.decodeFromString<Episode>(json) }.getOrNull() ?: return null
            episodeItem(episode.copy(playable = !(filter && episode.explicit)), contextUri, group)
        } else {
            val track = runCatching { graph.json.decodeFromString<Track>(json) }.getOrNull() ?: return null
            trackItem(track.copy(playable = !(filter && track.explicit)), contextUri, group)
        } ?: return null
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        }
        val metadata = item.mediaMetadata.buildUpon().setExtras(extras)
        artworkUri(context, row.imagePath)?.let(metadata::setArtworkUri)
        return item.buildUpon()
            .setMediaId(contextUri?.let { MediaIds.inContext(it, row.uri) } ?: MediaIds.downloaded(row.uri))
            .setMediaMetadata(metadata.build())
            .build()
    }

    /** A downloaded collection of the Downloads tab: browsable (its downloads) and playable (its context). */
    private fun downloadedCollectionItem(collection: DownloadedCollection, group: String?): MediaItem? {
        val ref = collection.ref
        val liked = ref.type == CollectionType.LIKED_SONGS
        val title = if (liked) context.getString(R.string.playback_liked_songs) else ref.name
        if (isPlaceholder(title)) return null
        val extras = (group?.let(::groupExtras) ?: Bundle()).apply {
            putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        }
        return MediaItem.Builder()
            .setMediaId(if (liked) likedContextUri() ?: ref.uri else ref.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(if (liked) resourceUri(R.drawable.pb_ic_auto_liked) else artworkUri(context, ref.imageUrl))
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(
                        when (ref.type) {
                            CollectionType.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                            CollectionType.SHOW -> MediaMetadata.MEDIA_TYPE_PODCAST
                            CollectionType.PLAYLIST, CollectionType.LIKED_SONGS -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                        },
                    )
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    @StringRes
    private fun sectionTitle(section: OfflineTree.Section): Int = when (section) {
        OfflineTree.Section.PLAYLISTS -> R.string.playback_playlists
        OfflineTree.Section.ALBUMS -> R.string.playback_albums
        OfflineTree.Section.PODCASTS -> R.string.playback_podcasts
        OfflineTree.Section.SONGS -> R.string.playback_songs
        OfflineTree.Section.EPISODES -> R.string.playback_episodes
    }

    /**
     * No network, or offline mode: only the downloads play (and can be browsed or found). The
     * network as of now, also while the engine is stopped (no holder is taken here).
     */
    fun isOffline(): Boolean = graph.settings.settings.value.offlineMode || !graph.engine.currentNetworkAvailable()

    private fun explicitFilter(): Boolean {
        val settings = graph.settings.settings.value
        return settings.hideExplicit || accountExplicitFilter(graph.engine.user.value, settings.accountExplicitFilter)
    }

    // ---- items --------------------------------------------------------------------------------

    /** Single item lookup (`onGetItem`). */
    suspend fun item(mediaId: String): MediaItem? = try {
        when (mediaId) {
            ROOT, ROOT_OFFLINE, ROOT_RECENT -> folder(mediaId, R.string.app_name)
            HOME -> folder(HOME, R.string.playback_tab_home, R.drawable.pb_ic_auto_home)
            LIBRARY -> folder(LIBRARY, R.string.playback_tab_library, R.drawable.pb_ic_auto_library, listStyle = true)
            DOWNLOADS -> folder(DOWNLOADS, R.string.playback_tab_downloads, R.drawable.pb_ic_auto_downloads)
            BROWSE -> folder(BROWSE, R.string.playback_tab_browse, R.drawable.pb_ic_auto_browse)
            LIKED -> likedFolder()
            PLAYLISTS -> folder(PLAYLISTS, R.string.playback_playlists, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            ALBUMS -> folder(ALBUMS, R.string.playback_albums, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
            ARTISTS -> folder(ARTISTS, R.string.playback_artists, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
            PODCASTS -> folder(PODCASTS, R.string.playback_podcasts, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
            else -> BrowsePaging.parseMore(mediaId)?.let { moreItem(it.parentId, it.offset) } ?: lookup(mediaId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "item($mediaId) failed", e)
        null
    }

    private suspend fun lookup(mediaId: String): MediaItem? {
        val parsed = MediaIds.parse(mediaId)
        val contextUri = (parsed as? MediaIds.Parsed.InContext)?.contextUri
        val uri = when (parsed) {
            is MediaIds.Parsed.InContext -> parsed.trackUri
            is MediaIds.Parsed.Downloaded -> parsed.trackUri
            is MediaIds.Parsed.Plain -> parsed.uri
            MediaIds.Parsed.Invalid -> return null
        }
        return when {
            uri.startsWith("spotify:track:") ->
                graph.catalog.tracks(listOf(uri)).firstOrNull()?.let { trackItem(it, contextUri) }?.withId(mediaId)
            uri.startsWith("spotify:episode:") ->
                graph.catalog.episode(uri)?.let { episodeItem(it, contextUri) }?.withId(mediaId)
            uri.startsWith("spotify:album:") -> graph.catalog.album(uri).settle()?.let { albumItem(it.toRef()) }
            uri.startsWith("spotify:playlist:") -> graph.catalog.playlist(uri, 1).settle()?.let { p ->
                contextItem(uri, p.name, p.owner?.displayName ?: p.owner?.username, p.images, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
            uri.startsWith("spotify:artist:") -> graph.catalog.artist(uri).settle()?.let { a -> artistItem(ArtistRef(a.uri, a.name, a.images)) }
            uri.startsWith("spotify:show:") -> graph.catalog.show(uri).settle()?.let { showItem(it.toRef()) }
            uri.endsWith(":collection") -> likedFolder()
            else -> null
        }
    }

    /** The last locally played item (for "recent" roots of non-SysUI browsers). */
    private suspend fun recentItem(): MediaItem? = graph.resumeStore.read()?.let(::resumeItem)

    /** [downloadedImage]: path of the downloaded cover of the track, preferred (works offline). */
    fun resumeItem(state: ResumeState, downloadedImage: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(state.mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(state.title)
                .setArtist(state.artist)
                .setAlbumTitle(state.album)
                .setArtworkUri(artworkUri(context, downloadedImage) ?: artworkUri(context, state.artworkUrl))
                .setDurationMs(state.durationMs)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(if (state.isEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        // Shuffle / repeat of the session, for the load that resumes it (a load without them resets both).
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(ResumeModes.extras(state)).build())
        .build()

    // ---- search -------------------------------------------------------------------------------

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        // Offline the catalog cannot search (no session): what is downloaded can be found.
        if (isOffline()) return offlineSearch(query)
        val results = graph.search.search(query, limit = SEARCH_LIMIT)
        return searchItems(results)
    }

    private fun searchItems(r: SearchResults): List<MediaItem> {
        val songs = context.getString(R.string.playback_songs)
        val artists = context.getString(R.string.playback_artists)
        val albums = context.getString(R.string.playback_albums)
        val playlists = context.getString(R.string.playback_playlists)
        val podcasts = context.getString(R.string.playback_podcasts)
        val top = r.topResult?.let { mediaRefItem(it, context.getString(R.string.playback_top_result)) }
        return (
            listOfNotNull(top) +
                r.tracks.mapNotNull { trackItem(it, it.album?.uri, group = songs) } +
                r.artists.mapNotNull { artistItem(it, artists) } +
                r.albums.mapNotNull { albumItem(it, albums) } +
                r.playlists.mapNotNull { playlistItem(it, playlists) } +
                r.shows.mapNotNull { showItem(it, podcasts) } +
                r.episodes.mapNotNull { episodeItem(it, null, group = podcasts) }
            ).distinctBy { it.mediaId }
    }

    /**
     * Resolves a voice request ("play X on SpotifyGood") into the items to play; empty when
     * nothing matches. The user's own collections come first, by name ([VoiceMatch]), honouring
     * the `MediaStore.EXTRA_MEDIA_FOCUS` hint and the names in the `EXTRA_MEDIA_*` extras: the
     * downloaded ones and Liked Songs, then (online) Library's playlists, albums, artists and
     * podcasts. The same or loosely the same name wins over the catalog's search (the user's own,
     * maybe private, "Road Trip" over a stranger's), a name that only starts so when the search
     * has nothing. Offline only the downloads count: their collections, then the downloaded songs
     * and episodes by title, artist, album or show.
     */
    suspend fun resolveVoiceQuery(query: String, extras: Bundle?): List<MediaItem> {
        val offline = isOffline()
        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val kinds = VoiceMatch.kindsFor(focus)
        val named = voiceNames(extras)
        val local = if (kinds.any { it != VoiceMatch.Kind.SONG }) ownCollection(query, kinds, named, offline) else null
        if (local != null && local.strength >= VoiceMatch.Strength.LOOSE) local.candidate.value()?.let { return listOf(it) }
        if (!offline) {
            val found = try {
                catalogVoiceQuery(query, focus)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Voice search failed", e)
                null
            }
            if (found != null) return listOf(found)
        }
        local?.candidate?.value()?.let { return listOf(it) }
        // Offline, or the search had nothing (or failed): the downloaded songs and episodes.
        return downloadedVoiceMatches(query, kinds, named)
    }

    /** The names a voice request's extras give per kind (`EXTRA_MEDIA_PLAYLIST`, `_ALBUM`, `_ARTIST`, `_TITLE`). */
    private fun voiceNames(extras: Bundle?): Map<VoiceMatch.Kind, String> = buildMap {
        fun put(kind: VoiceMatch.Kind, key: String) {
            extras?.getString(key)?.takeIf { it.isNotBlank() }?.let { put(kind, it) }
        }
        put(VoiceMatch.Kind.PLAYLIST, MediaStore.EXTRA_MEDIA_PLAYLIST)
        put(VoiceMatch.Kind.ALBUM, MediaStore.EXTRA_MEDIA_ALBUM)
        put(VoiceMatch.Kind.ARTIST, MediaStore.EXTRA_MEDIA_ARTIST)
        put(VoiceMatch.Kind.SONG, MediaStore.EXTRA_MEDIA_TITLE)
    }

    /**
     * The best of the user's own collections for a voice request: Liked Songs, the downloaded
     * collections, and online Library's playlists, albums, artists and podcasts (cached lists).
     */
    private suspend fun ownCollection(
        query: String,
        kinds: Set<VoiceMatch.Kind>,
        named: Map<VoiceMatch.Kind, String>,
        offline: Boolean,
    ): VoiceMatch.Match<() -> MediaItem?>? {
        val candidates = ownCollections(offline)
        return VoiceMatch.best(query, candidates, kinds, named)
    }

    /** The user's collections by name (downloaded ones first; offline only those). */
    private suspend fun ownCollections(offline: Boolean): List<VoiceMatch.Candidate<() -> MediaItem?>> {
        val list = ArrayList<VoiceMatch.Candidate<() -> MediaItem?>>()
        val downloaded = downloadedCollections()
        val done = if (offline) downloadedUris() else emptySet()
        val liked = downloaded.firstOrNull { it.ref.type == CollectionType.LIKED_SONGS }
        val likedName = context.getString(R.string.playback_liked_songs)
        when {
            !offline && likedContextUri() != null -> list += VoiceMatch.Candidate(likedName, VoiceMatch.Kind.PLAYLIST) { likedFolder() }
            liked != null -> list += VoiceMatch.Candidate(likedName, VoiceMatch.Kind.PLAYLIST) { downloadedCollectionItem(liked, group = null) }
        }
        for (c in downloaded) {
            val kind = when (c.ref.type) {
                CollectionType.PLAYLIST -> VoiceMatch.Kind.PLAYLIST
                CollectionType.ALBUM -> VoiceMatch.Kind.ALBUM
                CollectionType.SHOW -> VoiceMatch.Kind.SHOW
                CollectionType.LIKED_SONGS -> continue
            }
            // Offline only what plays: something of it is downloaded.
            if (offline && c.itemUris.none(done::contains)) continue
            list += VoiceMatch.Candidate(c.ref.name, kind) { downloadedCollectionItem(c, group = null) }
        }
        if (offline) return list
        graph.library.playlists().cachedOrSettled()?.flatPlaylists().orEmpty().forEach { entry ->
            val uri = entry.uri ?: return@forEach
            list += VoiceMatch.Candidate(entry.name, VoiceMatch.Kind.PLAYLIST) {
                contextItem(uri, entry.name, entry.owner?.displayName ?: entry.owner?.username, entry.images, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            }
        }
        graph.library.albums().cachedOrSettled().orEmpty().forEach { saved ->
            list += VoiceMatch.Candidate(saved.album.name, VoiceMatch.Kind.ALBUM) { albumItem(saved.album) }
        }
        graph.library.artists().cachedOrSettled().orEmpty().forEach { saved ->
            list += VoiceMatch.Candidate(saved.artist.name, VoiceMatch.Kind.ARTIST) { artistItem(saved.artist) }
        }
        graph.library.shows().cachedOrSettled().orEmpty().forEach { saved ->
            list += VoiceMatch.Candidate(saved.show.name, VoiceMatch.Kind.SHOW) { showItem(saved.show) }
        }
        return list
    }

    /** A downloaded song or episode with its stored metadata, for matching by name. */
    private class StoredItem(val uri: String, val track: Track?, val episode: Episode?)

    /** The completed downloads with their stored metadata, newest first. */
    private suspend fun storedItems(): List<StoredItem> {
        val uris = downloadedUris().toList()
        val rows = storedDownloads(uris)
        return uris.mapNotNull { uri ->
            val json = rows[uri]?.metadataJson ?: return@mapNotNull null
            if (uri.startsWith("spotify:episode:")) {
                runCatching { graph.json.decodeFromString<Episode>(json) }.getOrNull()?.let { StoredItem(uri, null, it) }
            } else {
                runCatching { graph.json.decodeFromString<Track>(json) }.getOrNull()?.let { StoredItem(uri, it, null) }
            }
        }
    }

    /**
     * The downloaded songs and episodes a voice request names: by title (the best matches), else
     * by artist, album or show (all of theirs), newest download first; played as a list.
     */
    private suspend fun downloadedVoiceMatches(query: String, kinds: Set<VoiceMatch.Kind>, named: Map<VoiceMatch.Kind, String>): List<MediaItem> {
        val items = storedItems()
        if (items.isEmpty()) return emptyList()
        val filter = explicitFilter()
        fun row(item: StoredItem): MediaItem? = item.track?.let { trackItem(it.copy(playable = !(filter && it.explicit)), null) }
            ?: item.episode?.let { episodeItem(it.copy(playable = !(filter && it.explicit)), null) }
        fun bestBy(text: String, nameOf: (StoredItem) -> List<String>): List<StoredItem> {
            val scored = items.mapNotNull { item -> nameOf(item).mapNotNull { VoiceMatch.strength(text, it) }.maxOrNull()?.let { item to it } }
            val top = scored.maxOfOrNull { it.second } ?: return emptyList()
            return scored.filter { it.second == top }.map { it.first }
        }
        val artist = named[VoiceMatch.Kind.ARTIST]
        if (VoiceMatch.Kind.SONG in kinds) {
            var titled = bestBy(named[VoiceMatch.Kind.SONG] ?: query) { listOfNotNull(it.track?.name ?: it.episode?.name) }
            if (artist != null) {
                titled = titled.filter { item -> item.track?.artists.orEmpty().any { VoiceMatch.strength(artist, it.name) != null } }
            }
            if (titled.isNotEmpty()) return titled.take(VOICE_MAX_ITEMS).mapNotNull(::row)
        }
        val byKind = listOf(
            VoiceMatch.Kind.ARTIST to { item: StoredItem -> item.track?.artists.orEmpty().map { it.name } },
            VoiceMatch.Kind.ALBUM to { item: StoredItem -> listOfNotNull(item.track?.album?.name) },
            VoiceMatch.Kind.SHOW to { item: StoredItem -> listOfNotNull(item.episode?.show?.name) },
        )
        for ((kind, nameOf) in byKind) {
            if (kind !in kinds) continue
            val found = bestBy(named[kind] ?: query, nameOf)
            if (found.isNotEmpty()) return found.take(VOICE_MAX_ITEMS).mapNotNull(::row)
        }
        return emptyList()
    }

    /**
     * Search offline: the downloaded collections and the downloaded songs and episodes whose
     * names have the query's words (by title, artist, album or show).
     */
    private suspend fun offlineSearch(query: String): List<MediaItem> {
        val collections = ownCollections(offline = true)
            .filter { VoiceMatch.mentions(query, it.name) }
            .mapNotNull { it.value() }
        val songs = context.getString(R.string.playback_songs)
        val episodes = context.getString(R.string.playback_episodes)
        val filter = explicitFilter()
        val items = storedItems().asSequence().filter { item ->
            val names = item.track?.let { t -> listOf(t.name) + t.artists.map { it.name } + listOfNotNull(t.album?.name) }
                ?: item.episode?.let { e -> listOfNotNull(e.name, e.show?.name) }.orEmpty()
            names.any { VoiceMatch.mentions(query, it) }
        }.take(OFFLINE_SEARCH_ITEMS).mapNotNull { item ->
            item.track?.let { trackItem(it.copy(playable = !(filter && it.explicit)), null, group = songs) }
                ?: item.episode?.let { episodeItem(it.copy(playable = !(filter && it.explicit)), null, group = episodes) }
        }.toList()
        return (collections + items).distinctBy { it.mediaId }
    }

    /** The catalog's search for a voice request, honouring the [focus] hint; null when it has nothing. */
    private suspend fun catalogVoiceQuery(query: String, focus: String?): MediaItem? {
        val r = graph.search.search(query, limit = VOICE_LIMIT)
        return when (focus) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> r.artists.firstOrNull()?.let { artistItem(it) }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> r.albums.firstOrNull()?.let { albumItem(it) }
            VoiceMatch.PLAYLIST_FOCUS -> r.playlists.firstOrNull()?.let { playlistItem(it) }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> r.tracks.firstOrNull { it.playable }?.let { trackItem(it, it.album?.uri) }
            else -> null
        } ?: r.topResult?.takeIf { top -> isPlayableResult(r, top) }?.let { mediaRefItem(it, null) }
            ?: r.tracks.firstOrNull { it.playable }?.let { trackItem(it, it.album?.uri) }
            ?: r.playlists.firstOrNull()?.let { playlistItem(it) }
            ?: r.albums.firstOrNull()?.let { albumItem(it) }
            ?: r.artists.firstOrNull()?.let { artistItem(it) }
            ?: r.episodes.firstOrNull { it.playable }?.let { episodeItem(it, null) }
    }

    /** A top track/episode the results mark unplayable (e.g. explicit with the filter on) is skipped. */
    private fun isPlayableResult(r: SearchResults, top: MediaRef): Boolean = when (top.type) {
        MediaType.TRACK -> r.tracks.firstOrNull { it.uri == top.uri }?.playable != false
        MediaType.EPISODE -> r.episodes.firstOrNull { it.uri == top.uri }?.playable != false
        else -> true
    }

    // ---- builders -------------------------------------------------------------------------------

    private fun likedFolder(): MediaItem {
        val uri = likedContextUri()
        return MediaItem.Builder()
            // Playable ("Play" on the folder) only works with the context uri as the media id:
            // MediaIds/SpotifyPlayer cannot load "library:liked". Browsing/lookup of the context
            // uri lands on the Liked Songs branches as well.
            .setMediaId(likedMediaId(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.playback_liked_songs))
                    .setArtworkUri(resourceUri(R.drawable.pb_ic_auto_liked))
                    .setIsBrowsable(true)
                    .setIsPlayable(uri != null)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build(),
            )
            .build()
    }

    /** `spotify:user:<username>:collection`, the Liked Songs context (null while the user is unknown). */
    private fun likedContextUri(): String? = graph.engine.user.value?.username?.let { "spotify:user:$it:collection" }

    private fun folder(
        id: String,
        @StringRes title: Int,
        @DrawableRes icon: Int? = null,
        listStyle: Boolean = false,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    ): MediaItem {
        val extras = if (listStyle) {
            Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
        } else {
            null
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(title))
                    .setArtworkUri(icon?.let(::resourceUri))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun mediaRefItem(ref: MediaRef, group: String?): MediaItem? = when (ref.type) {
        MediaType.TRACK -> playable(ref.uri, ref.name, ref.subtitle, artwork(ref.images), MediaMetadata.MEDIA_TYPE_MUSIC, group = group)
        MediaType.EPISODE -> playable(ref.uri, ref.name, ref.subtitle, artwork(ref.images), MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, group = group)
        MediaType.ALBUM -> contextItem(ref.uri, ref.name, ref.subtitle, ref.images, MediaMetadata.MEDIA_TYPE_ALBUM, group)
        MediaType.PLAYLIST -> contextItem(ref.uri, ref.name, ref.subtitle, ref.images, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)
        MediaType.ARTIST -> contextItem(ref.uri, ref.name, ref.subtitle, ref.images, MediaMetadata.MEDIA_TYPE_ARTIST, group)
        MediaType.SHOW -> contextItem(ref.uri, ref.name, ref.subtitle, ref.images, MediaMetadata.MEDIA_TYPE_PODCAST, group)
        MediaType.COLLECTION -> contextItem(ref.uri, ref.name, ref.subtitle, ref.images, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)
    }

    private fun albumItem(album: AlbumRef, group: String? = null) = contextItem(
        album.uri, album.name, album.artists.joinToString { it.name }.ifEmpty { null }, album.images, MediaMetadata.MEDIA_TYPE_ALBUM, group,
    )

    private fun artistItem(artist: ArtistRef, group: String? = null) =
        contextItem(artist.uri, artist.name, null, artist.images, MediaMetadata.MEDIA_TYPE_ARTIST, group)

    private fun playlistItem(playlist: PlaylistRef, group: String? = null) = contextItem(
        playlist.uri, playlist.name, playlist.owner?.displayName ?: playlist.owner?.username, playlist.images,
        MediaMetadata.MEDIA_TYPE_PLAYLIST, group,
    )

    private fun showItem(show: ShowRef, group: String? = null) =
        contextItem(show.uri, show.name, show.publisher, show.images, MediaMetadata.MEDIA_TYPE_PODCAST, group)

    /**
     * A Spotify context: browsable (its tracks) and playable ("play all"). Null for a uri-only
     * placeholder (its metadata could not be loaded: no name), which would be an empty row.
     */
    private fun contextItem(
        uri: String,
        title: String,
        subtitle: String?,
        images: List<Image>,
        mediaType: Int,
        group: String? = null,
    ): MediaItem? = if (isPlaceholder(title)) null else MediaItem.Builder()
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(artwork(images))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(mediaType)
                .setExtras(group?.let { groupExtras(it) })
                .build(),
        )
        .build()

    /** Null for a placeholder (see [playable]); an unplayable track is listed but not playable. */
    private fun trackItem(track: Track, contextUri: String?, group: String? = null, fallbackImages: List<Image> = emptyList()): MediaItem? =
        playable(
            mediaId = contextUri?.let { MediaIds.inContext(it, track.uri) } ?: track.uri,
            title = track.name,
            subtitle = track.artists.joinToString { it.name },
            artwork = artwork(track.album?.images.orEmpty().ifEmpty { fallbackImages }),
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            explicit = track.explicit,
            group = group,
            album = track.album?.name,
            durationMs = track.durationMs.takeIf { it > 0 },
            isPlayable = track.playable,
        )

    /**
     * An episode row, with its resume point as the car's completion status (docs §6.5). Playing it
     * resumes there: the catalog / search answer that brought the episode already taught the store
     * Spotify's point, and every play of an episode starts at the store's point.
     */
    private fun episodeItem(episode: Episode, contextUri: String?, group: String? = null, fallbackImages: List<Image> = emptyList()): MediaItem? {
        val shown = graph.episodeProgress.overlay(episode)
        return playable(
            mediaId = contextUri?.let { MediaIds.inContext(it, shown.uri) } ?: shown.uri,
            title = shown.name,
            subtitle = shown.show?.name,
            artwork = artwork(shown.images.ifEmpty { shown.show?.images.orEmpty() }.ifEmpty { fallbackImages }),
            mediaType = MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE,
            explicit = shown.explicit,
            group = group,
            album = shown.show?.name,
            durationMs = shown.durationMs.takeIf { it > 0 },
            extras = completionExtras(shown),
            isPlayable = shown.playable,
        )
    }

    private fun completionExtras(episode: Episode): Bundle = Bundle().apply {
        val position = episode.resumePositionMs ?: 0
        when {
            episode.fullyPlayed == true ->
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            position > 0 && episode.durationMs > 0 -> {
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, (position.toDouble() / episode.durationMs).coerceIn(0.0, 1.0))
            }
            else -> putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
        }
    }

    /**
     * A track / episode row. Null for a uri-only placeholder (partial catalog page: its metadata
     * could not be loaded, so it has no name) — the phone shows those as "Unavailable"; a row with
     * no title in the car would only be confusing.
     */
    private fun playable(
        mediaId: String,
        title: String?,
        subtitle: String?,
        artwork: Uri?,
        mediaType: Int,
        explicit: Boolean = false,
        group: String? = null,
        album: String? = null,
        durationMs: Long? = null,
        extras: Bundle? = null,
        isPlayable: Boolean = true,
    ): MediaItem? {
        if (isPlaceholder(title)) return null
        val bundle = (extras ?: Bundle()).apply {
            if (explicit) putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .setAlbumTitle(album)
                    .setArtworkUri(artwork)
                    .setDurationMs(durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(isPlayable)
                    .setMediaType(mediaType)
                    .setExtras(bundle.takeUnless { it.isEmpty })
                    .build(),
            )
            .build()
    }

    private fun MediaItem.withId(id: String): MediaItem = buildUpon().setMediaId(id).build()

    private fun groupExtras(group: String) = Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group) }

    private fun artwork(images: List<Image>): Uri? = artworkUri(context, images.best(ARTWORK_PX))

    private fun resourceUri(@DrawableRes res: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(res.toString())
        .build()

    /**
     * The cached value of a stale-while-revalidate flow at once, else its first answer (bounded):
     * a voice request does not wait for a revalidation.
     */
    private suspend fun <T> Flow<Resource<T>>.cachedOrSettled(): T? =
        withTimeoutOrNull(VOICE_LOOKUP_TIMEOUT_MS) { first { it.dataOrNull != null || it !is Resource.Loading } }?.dataOrNull

    /** First non-loading value of a stale-while-revalidate flow (or the cached one on timeout). */
    private suspend fun <T> Flow<Resource<T>>.settle(): T? {
        var cached: T? = null
        val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            onEach { r -> r.dataOrNull?.let { cached = it } }.first { it !is Resource.Loading }
        }
        return result?.dataOrNull ?: cached
    }

    companion object {
        private const val TAG = "LibraryTree"

        const val ROOT = "root"
        const val ROOT_OFFLINE = "root:offline"
        const val ROOT_RECENT = "root:recent"
        const val HOME = "tab:home"
        const val LIBRARY = "tab:library"
        const val DOWNLOADS = "tab:downloads"
        const val BROWSE = "tab:browse"
        const val LIKED = "library:liked"
        const val PLAYLISTS = "library:playlists"
        const val ALBUMS = "library:albums"
        const val ARTISTS = "library:artists"
        const val PODCASTS = "library:podcasts"

        /**
         * Media id of the Liked Songs folder: its context uri when the user is known (so the folder
         * is playable), otherwise [LIKED] (browsable only). [LIKED] keeps working for browsers that
         * cached it.
         */
        fun likedMediaId(likedContextUri: String?): String = likedContextUri ?: LIKED

        /** A catalog item whose metadata failed to load is a uri-only placeholder without a name. */
        fun isPlaceholder(name: String?): Boolean = name.isNullOrBlank()

        /** Whether the children of [parentId] come from the catalog (worth waiting for a starting session). */
        fun needsSession(parentId: String): Boolean = BrowsePaging.listIdOf(parentId) !in LOCAL_PARENTS

        private val LOCAL_PARENTS = setOf(ROOT, ROOT_OFFLINE, ROOT_RECENT, LIBRARY, DOWNLOADS)

        /** Whether [item] of [mediaId] looks it up in the catalog (the plain folders do not). */
        fun itemNeedsSession(mediaId: String): Boolean = mediaId !in FOLDER_ITEMS && BrowsePaging.parseMore(mediaId) == null

        private val FOLDER_ITEMS = setOf(
            ROOT, ROOT_OFFLINE, ROOT_RECENT, HOME, LIBRARY, DOWNLOADS, BROWSE, PLAYLISTS, ALBUMS, ARTISTS, PODCASTS,
        )

        private const val MAX_TABS = 4
        private const val MAX_ITEMS = 100
        private const val RECENT_LIMIT = 12
        private const val HOME_SECTIONS = 6
        private const val HOME_SECTION_ITEMS = 10
        private const val BROWSE_ARTISTS = 20
        private const val SEARCH_LIMIT = 10
        private const val VOICE_LIMIT = 5
        private const val ARTWORK_PX = 300
        private const val LOOKUP_TIMEOUT_MS = 8_000L
        /** Most rows of one `library.tracks` call (its limit). */
        private const val LIBRARY_PAGE = 500
        /** Rows of one further playlist / show page call. */
        private const val CATALOG_PAGE = 100
        /** Longest wait for the first read of the completed downloads (all on a card not mounted: none). */
        private const val FIRST_READ_TIMEOUT_MS = 2_000L
        /** Uris per download-row query (SQLite's variable limit is 999 on older devices). */
        private const val SQL_CHUNK = 400
        /** Longest wait for one of Library's lists in a voice request (they are cached). */
        private const val VOICE_LOOKUP_TIMEOUT_MS = 3_000L
        /** Most downloads one voice request plays as a list (an artist's, a show's). */
        private const val VOICE_MAX_ITEMS = 500
        /** Most downloaded songs and episodes an offline search lists. */
        private const val OFFLINE_SEARCH_ITEMS = 50
    }
}
