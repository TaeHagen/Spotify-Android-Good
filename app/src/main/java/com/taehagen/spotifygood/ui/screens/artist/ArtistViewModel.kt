package com.taehagen.spotifygood.ui.screens.artist

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.DetailViewModel
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.PlaybackInfo
import com.taehagen.spotifygood.ui.screens.album.RichText
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.parseHtml
import com.taehagen.spotifygood.ui.screens.album.savedFlow
import com.taehagen.spotifygood.ui.screens.album.statesFor
import com.taehagen.spotifygood.ui.screens.album.toLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Release groups of an artist; [key] is the `Route.ArtistDiscography.group` value. */
internal enum class DiscographyGroup(val key: String, @StringRes val title: Int) {
    ALBUMS("albums", R.string.detail_albums),
    SINGLES("singles", R.string.detail_singles_eps),
    COMPILATIONS("compilations", R.string.detail_compilations),
    APPEARS_ON("appears_on", R.string.detail_appears_on),
    ;

    fun releases(artist: Artist): List<AlbumRef> = when (this) {
        ALBUMS -> artist.albums
        SINGLES -> artist.singles
        COMPILATIONS -> artist.compilations
        APPEARS_ON -> artist.appearsOn
    }

    companion object {
        fun fromKey(key: String): DiscographyGroup = entries.firstOrNull { it.key == key } ?: ALBUMS
    }
}

@Immutable
internal data class ReleaseGroup(val group: DiscographyGroup, val releases: List<AlbumRef>)

@Immutable
internal data class ArtistContent(
    val artist: Artist,
    val heroImageUrl: String?,
    val aboutImageUrl: String?,
    val topTracks: List<Track>,
    val groups: List<ReleaseGroup>,
    val related: List<MediaRef>,
    val biography: RichText,
)

@Immutable
internal data class ArtistUiState(
    val load: LoadState<ArtistContent> = LoadState.Loading,
    val playback: PlaybackInfo = PlaybackInfo(),
    /** Null until known. */
    val following: Boolean? = null,
    val rowDownloads: Map<String, DownloadState> = emptyMap(),
    val offline: Boolean = false,
    /** The session is ONLINE: rows that aren't downloaded can start ([canStartNow]). */
    val online: Boolean = true,
    val filterExplicit: Boolean = false,
)

internal class ArtistViewModel(graph: AppGraph, private val uri: String) : DetailViewModel(graph, uri) {

    private val content: StateFlow<LoadState<ArtistContent>> = retryTrigger
        .flatMapLatest { loadOnceConnected { graph.catalog.artist(uri) }.catch { emit(Resource.Error(it)) } }
        .map { resource -> resource.toLoadState(::toContent) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    private val topTrackUris: Flow<Set<String>> = content
        .map { load -> load.dataOrNull()?.topTracks?.mapTo(HashSet()) { it.uri } ?: emptySet() }
        .distinctUntilChanged()

    val state: StateFlow<ArtistUiState> = combine(
        content,
        playbackInfo,
        graph.savedFlow(uri),
        graph.downloads.statesFor(topTrackUris),
        connectivity,
    ) { load, playback, following, rows, connectivity ->
        ArtistUiState(load, playback, following, rows, connectivity.offline, connectivity.online, connectivity.filterExplicit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistUiState())

    init {
        // Opened while the session connected and it failed, or a stale copy: load again once ONLINE.
        reloadWhenOnline(content)
    }

    /** Plays the artist context starting at [track] (top tracks come first in an artist context). */
    fun playTrack(track: Track) {
        if (track.isPlaceholder || !track.playable) return
        graph.player.play(PlayRequest(contextUri = uri, startUri = track.uri))
    }

    fun toggleFollow() {
        toggleSaved(state.value.following)
    }

    private fun toContent(artist: Artist): ArtistContent = ArtistContent(
        artist = artist,
        heroImageUrl = artist.headerImages.best(1000) ?: artist.images.best(1000),
        aboutImageUrl = artist.images.best(640),
        topTracks = artist.topTracks.take(MAX_TOP_TRACKS),
        groups = DiscographyGroup.entries.mapNotNull { group ->
            group.releases(artist).distinctBy { it.uri }.takeIf { it.isNotEmpty() }?.let { ReleaseGroup(group, it.take(CAROUSEL_LIMIT)) }
        },
        related = artist.related.distinctBy { it.uri }.map { MediaRef(type = MediaType.ARTIST, uri = it.uri, name = it.name, images = it.images) },
        biography = artist.biography?.takeIf { it.isNotBlank() }?.let(::parseHtml) ?: RichText.EMPTY,
    )

    internal companion object {
        const val MAX_TOP_TRACKS = 10
        const val COLLAPSED_TOP_TRACKS = 5
        const val CAROUSEL_LIMIT = 20
    }
}

@Immutable
internal data class DiscographyContent(
    val artistName: String,
    /** Groups that have releases (in display order). */
    val available: List<DiscographyGroup>,
    val selected: DiscographyGroup,
    /** Releases of [selected], newest first. */
    val releases: List<AlbumRef>,
)

@Immutable
internal data class DiscographyUiState(
    val load: LoadState<DiscographyContent> = LoadState.Loading,
    val offline: Boolean = false,
)

internal class ArtistDiscographyViewModel(
    graph: AppGraph,
    uri: String,
    initialGroup: DiscographyGroup,
) : DetailViewModel(graph, uri) {

    private val selected = MutableStateFlow(initialGroup)

    private val artist: Flow<LoadState<Artist>> = retryTrigger
        .flatMapLatest { loadOnceConnected { graph.catalog.artist(uri) }.catch { emit(Resource.Error(it)) } }
        .map { resource -> resource.toLoadState { it } }

    val state: StateFlow<DiscographyUiState> = combine(artist, selected, offline) { load, group, offline ->
        val mapped: LoadState<DiscographyContent> = when (load) {
            is LoadState.Ready -> LoadState.Ready(toContent(load.data, group), load.refreshing, load.stale)
            is LoadState.Failed -> load
            LoadState.Loading -> LoadState.Loading
        }
        DiscographyUiState(mapped, offline)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscographyUiState())

    init {
        // Opened while the session connected and it failed, or a stale copy: load again once ONLINE.
        reloadWhenOnline(state.map { it.load })
    }

    fun select(group: DiscographyGroup) {
        selected.value = group
    }

    private fun toContent(artist: Artist, group: DiscographyGroup): DiscographyContent {
        val available = DiscographyGroup.entries.filter { it.releases(artist).isNotEmpty() }
        return DiscographyContent(
            artistName = artist.name,
            available = available,
            selected = group,
            releases = group.releases(artist).distinctBy { it.uri }.sortedByDescending { it.releaseDate.orEmpty() },
        )
    }
}
