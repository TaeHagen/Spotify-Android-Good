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
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.DownloadState
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
 * children carry `ctx|<context>|<track>` ids so Spotify context semantics are kept.
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
        val offline = settings.offlineMode || !graph.engine.isNetworkAvailable.value
        val home = folder(HOME, R.string.playback_tab_home, R.drawable.pb_ic_auto_home)
        val library = folder(LIBRARY, R.string.playback_tab_library, R.drawable.pb_ic_auto_library, listStyle = true)
        val downloads = folder(DOWNLOADS, R.string.playback_tab_downloads, R.drawable.pb_ic_auto_downloads)
        val browse = folder(BROWSE, R.string.playback_tab_browse, R.drawable.pb_ic_auto_browse)
        val ordered = if (offline) listOf(downloads, library, home, browse) else listOf(home, library, downloads, browse)
        return ordered.take(limit.coerceAtMost(MAX_TABS))
    }

    // ---- children ---------------------------------------------------------------------------

    suspend fun children(parentId: String, params: LibraryParams?): List<MediaItem>? = try {
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
            LIKED -> likedSongs()
            PLAYLISTS -> graph.library.playlists().settle()?.flatPlaylists().orEmpty()
                .mapNotNull { entry ->
                    entry.uri?.let { contextItem(it, entry.name, entry.owner?.displayName ?: entry.owner?.username, entry.images, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                }
            ALBUMS -> graph.library.albums().settle().orEmpty().map { albumItem(it.album) }
            ARTISTS -> graph.library.artists().settle().orEmpty().map { artistItem(it.artist) }
            PODCASTS -> graph.library.shows().settle().orEmpty().map { showItem(it.show) }
            DOWNLOADS -> downloads()
            BROWSE -> browse()
            else -> contextChildren(parentId)
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

    private suspend fun likedSongs(): List<MediaItem> {
        val likedUri = likedContextUri()
        return graph.library.likedTracks(0, MAX_ITEMS).items.map { saved -> trackItem(saved.track, likedUri) }
    }

    /** Newest download first, the order [com.taehagen.spotifygood.download.DownloadManager.downloadedUris] plays them in. */
    private suspend fun downloads(): List<MediaItem> {
        val items = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { graph.downloads.items.first() }.orEmpty()
        return items.asReversed().filter { it.state == DownloadState.COMPLETED }.mapNotNull { item ->
            val json = item.metadataJson ?: return@mapNotNull null
            val image = item.imagePath?.let { artworkUri(context, it) }
            val extras = Bundle().apply { putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED) }
            if (item.uri.startsWith("spotify:episode:")) {
                val episode = runCatching { graph.json.decodeFromString<Episode>(json) }.getOrNull() ?: return@mapNotNull null
                playable(
                    MediaIds.downloaded(item.uri), episode.name, episode.show?.name,
                    image ?: artwork(episode.images.ifEmpty { episode.show?.images.orEmpty() }),
                    MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, episode.explicit, extras = extras,
                )
            } else {
                val track = runCatching { graph.json.decodeFromString<Track>(json) }.getOrNull() ?: return@mapNotNull null
                playable(
                    MediaIds.downloaded(item.uri), track.name, track.artists.joinToString { it.name },
                    image ?: artwork(track.album?.images.orEmpty()),
                    MediaMetadata.MEDIA_TYPE_MUSIC, track.explicit, extras = extras,
                )
            }
        }
    }

    private suspend fun browse(): List<MediaItem> {
        val recentTitle = context.getString(R.string.playback_recent_searches)
        val recent = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { graph.search.recent.first() }.orEmpty()
            .filterIsInstance<RecentSearch.Item>()
            .mapNotNull { mediaRefItem(it.ref, recentTitle) }
        val artistsTitle = context.getString(R.string.playback_artists)
        val artists = graph.library.artists().settle().orEmpty().take(BROWSE_ARTISTS).map { artistItem(it.artist, artistsTitle) }
        return (recent + artists).distinctBy { it.mediaId }
    }

    /** Children of a Spotify context browsed by uri. */
    private suspend fun contextChildren(uri: String): List<MediaItem>? = when {
        uri.startsWith("spotify:playlist:") -> graph.catalog.playlist(uri, MAX_ITEMS).settle()?.items?.mapNotNull { item ->
            item.track?.let { trackItem(it, uri) } ?: item.episode?.let { episodeItem(it, uri) }
        }
        uri.startsWith("spotify:album:") -> graph.catalog.album(uri).settle()?.let { album ->
            album.tracks.map { trackItem(it, uri, fallbackImages = album.images) }
        }
        uri.startsWith("spotify:artist:") -> graph.catalog.artist(uri).settle()?.let { artist ->
            val popular = context.getString(R.string.playback_popular)
            val albums = context.getString(R.string.playback_albums)
            artist.topTracks.map { trackItem(it, uri, group = popular) } +
                (artist.albums + artist.singles).map { albumItem(it, albums) }
        }
        uri.startsWith("spotify:show:") -> graph.catalog.show(uri).settle()?.let { show ->
            show.episodes.map { episodeItem(it, uri, fallbackImages = show.images) }
        }
        uri.endsWith(":collection") -> likedSongs()
        else -> null
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
            else -> lookup(mediaId)
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
        .build()

    // ---- search -------------------------------------------------------------------------------

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
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
                r.tracks.map { trackItem(it, it.album?.uri, group = songs) } +
                r.artists.map { artistItem(it, artists) } +
                r.albums.map { albumItem(it, albums) } +
                r.playlists.map { playlistItem(it, playlists) } +
                r.shows.map { showItem(it, podcasts) } +
                r.episodes.map { episodeItem(it, null, group = podcasts) }
            ).distinctBy { it.mediaId }
    }

    /**
     * Resolves a voice request ("play X on SpotifyGood") into a playable item, honouring the
     * `MediaStore.EXTRA_MEDIA_FOCUS` hint. Returns null when nothing matches.
     */
    suspend fun resolveVoiceQuery(query: String, extras: Bundle?): MediaItem? {
        val r = graph.search.search(query, limit = VOICE_LIMIT)
        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        return when (focus) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> r.artists.firstOrNull()?.let { artistItem(it) }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> r.albums.firstOrNull()?.let { albumItem(it) }
            PLAYLIST_FOCUS -> r.playlists.firstOrNull()?.let { playlistItem(it) }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> r.tracks.firstOrNull()?.let { trackItem(it, it.album?.uri) }
            else -> null
        } ?: r.topResult?.let { mediaRefItem(it, null) }
            ?: r.tracks.firstOrNull()?.let { trackItem(it, it.album?.uri) }
            ?: r.playlists.firstOrNull()?.let { playlistItem(it) }
            ?: r.albums.firstOrNull()?.let { albumItem(it) }
            ?: r.artists.firstOrNull()?.let { artistItem(it) }
            ?: r.episodes.firstOrNull()?.let { episodeItem(it, null) }
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

    /** A Spotify context: browsable (its tracks) and playable ("play all"). */
    private fun contextItem(
        uri: String,
        title: String,
        subtitle: String?,
        images: List<Image>,
        mediaType: Int,
        group: String? = null,
    ): MediaItem = MediaItem.Builder()
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

    private fun trackItem(track: Track, contextUri: String?, group: String? = null, fallbackImages: List<Image> = emptyList()): MediaItem =
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
        )

    private fun episodeItem(episode: Episode, contextUri: String?, group: String? = null, fallbackImages: List<Image> = emptyList()): MediaItem =
        playable(
            mediaId = contextUri?.let { MediaIds.inContext(it, episode.uri) } ?: episode.uri,
            title = episode.name,
            subtitle = episode.show?.name,
            artwork = artwork(episode.images.ifEmpty { episode.show?.images.orEmpty() }.ifEmpty { fallbackImages }),
            mediaType = MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE,
            explicit = episode.explicit,
            group = group,
            album = episode.show?.name,
            durationMs = episode.durationMs.takeIf { it > 0 },
        )

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
    ): MediaItem {
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
                    .setIsPlayable(true)
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

        /** Whether the children of [parentId] come from the catalog (worth waiting for a starting session). */
        fun needsSession(parentId: String): Boolean = parentId !in LOCAL_PARENTS

        private val LOCAL_PARENTS = setOf(ROOT, ROOT_OFFLINE, ROOT_RECENT, LIBRARY, DOWNLOADS)

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
        /** `MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE` (the constant is deprecated). */
        private const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
    }
}
