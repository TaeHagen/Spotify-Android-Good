package com.taehagen.spotifygood.ui.screens.search

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.SpotifyLinks
import com.taehagen.spotifygood.ui.screens.album.isSameContext
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.library.NowPlaying
import com.taehagen.spotifygood.ui.screens.library.toMediaRef

// Pure search shaping (JVM-testable).

enum class SearchFilter(val type: SearchType?) {
    TOP(null),
    SONGS(SearchType.TRACK),
    ARTISTS(SearchType.ARTIST),
    ALBUMS(SearchType.ALBUM),
    PLAYLISTS(SearchType.PLAYLIST),
    PODCASTS(SearchType.SHOW),
    EPISODES(SearchType.EPISODE),
}

fun searchTypeOf(wire: String): SearchType? = SearchType.entries.firstOrNull { it.wire == wire }

/** One row of a single-type result list. */
@Immutable
sealed interface SearchItem {
    val key: String
    val ref: MediaRef

    data class Song(val track: Track) : SearchItem {
        override val key: String get() = "track:${track.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { track.toMediaRef() }
    }

    data class ArtistItem(val artist: ArtistRef) : SearchItem {
        override val key: String get() = "artist:${artist.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { artist.toMediaRef() }
    }

    data class AlbumItem(val album: AlbumRef) : SearchItem {
        override val key: String get() = "album:${album.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { album.toMediaRef() }
    }

    data class PlaylistItem(val playlist: PlaylistRef) : SearchItem {
        override val key: String get() = "playlist:${playlist.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { playlist.toMediaRef() }
    }

    data class ShowItem(val show: ShowRef) : SearchItem {
        override val key: String get() = "show:${show.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { show.toMediaRef() }
    }

    data class EpisodeItem(val episode: Episode) : SearchItem {
        override val key: String get() = "episode:${episode.uri}"
        override val ref: MediaRef by lazy(LazyThreadSafetyMode.NONE) { episode.toMediaRef() }
    }
}

fun SearchResults.itemsOf(type: SearchType): List<SearchItem> = when (type) {
    SearchType.TRACK -> tracks.map(SearchItem::Song)
    SearchType.ARTIST -> artists.map(SearchItem::ArtistItem)
    SearchType.ALBUM -> albums.map(SearchItem::AlbumItem)
    SearchType.PLAYLIST -> playlists.map(SearchItem::PlaylistItem)
    SearchType.SHOW -> shows.map(SearchItem::ShowItem)
    SearchType.EPISODE -> episodes.map(SearchItem::EpisodeItem)
}

/**
 * The server's top result, else the most likely intent (artist, then song, album, ...). A song or
 * episode the results mark unplayable (explicit with the filter on, not available here) is never
 * the top result: its card would start a different track.
 */
/** Something to show (the Top tab is "No results" otherwise); only such results are cached. */
fun SearchResults.hasAnyResult(): Boolean = !isEmpty || topResult != null

/**
 * Whether these results may stand for their query for the rest of the session: not empty and not
 * `partial` (a failed source, docs §6.3). Both can be transient, so they are asked for again.
 */
fun SearchResults.cacheable(): Boolean = hasAnyResult() && !partial

fun SearchResults.topResultOrBest(): MediaRef? = topResult?.takeIf { isPlayableRef(it) }
    ?: artists.firstOrNull()?.toMediaRef()
    ?: tracks.firstOrNull { it.playable }?.toMediaRef()
    ?: albums.firstOrNull()?.toMediaRef()
    ?: playlists.firstOrNull()?.toMediaRef()
    ?: shows.firstOrNull()?.toMediaRef()
    ?: episodes.firstOrNull { it.playable }?.toMediaRef()

/** False for a track / episode that these results list as unplayable. */
fun SearchResults.isPlayableRef(ref: MediaRef): Boolean = when (ref.type) {
    MediaType.TRACK -> tracks.firstOrNull { it.uri == ref.uri }?.playable != false
    MediaType.EPISODE -> episodes.firstOrNull { it.uri == ref.uri }?.playable != false
    else -> true
}

/** False for a song / episode result that is not playable (anything else can be opened). */
val SearchItem?.isPlayable: Boolean
    get() = when (this) {
        is SearchItem.Song -> track.playable
        is SearchItem.EpisodeItem -> episode.playable
        else -> true
    }

/**
 * What a recent search's row adds to the queue when swiped start→end (docs §9.9): a song's or an
 * episode's URI (canonical, as a tap opens it); null for a query or anything else (a plain row).
 */
fun RecentSearch.queueUri(): String? {
    val ref = (this as? RecentSearch.Item)?.ref ?: return null
    if (ref.type != MediaType.TRACK && ref.type != MediaType.EPISODE) return null
    return SpotifyLinks.canonicalUri(ref.uri) ?: ref.uri
}

/**
 * Whether the top result [ref] is what plays (or is paused): its track / episode, or its context
 * (playlist URI forms compared canonically). The card's Pause icon and its toggle both read it.
 */
fun NowPlaying.isTop(ref: MediaRef): Boolean = when (ref.type) {
    MediaType.TRACK, MediaType.EPISODE -> trackUri == ref.uri
    else -> isSameContext(contextUri, ref.uri)
}

/** What the top result card's Play / Pause does ([SearchViewModel.playTop]). */
sealed interface TopPlay {
    /** It is what plays (or is paused): pause / resume it. */
    data object Toggle : TopPlay

    /** Start it (the result is remembered as a recent search). */
    sealed interface Start : TopPlay
    data class SongInAlbum(val track: Track) : Start
    data class SongByUri(val uri: String) : Start
    data class EpisodeByUri(val uri: String) : Start
    data class ContextByUri(val uri: String) : Start
}

/** [TopPlay] for [ref] (its result [item]) while [nowPlaying] plays; null: it can't start (unplayable). */
fun topPlay(ref: MediaRef, item: SearchItem?, nowPlaying: NowPlaying): TopPlay? = when {
    !item.isPlayable -> null
    nowPlaying.isTop(ref) -> TopPlay.Toggle
    ref.type == MediaType.TRACK -> (item as? SearchItem.Song)?.let { TopPlay.SongInAlbum(it.track) } ?: TopPlay.SongByUri(ref.uri)
    ref.type == MediaType.EPISODE -> TopPlay.EpisodeByUri(ref.uri)
    else -> TopPlay.ContextByUri(ref.uri)
}

/** De-duplicates every list of a results page by URI (the server sometimes repeats items). */
fun SearchResults.distinct(): SearchResults = copy(
    tracks = tracks.distinctBy { it.uri },
    artists = artists.distinctBy { it.uri },
    albums = albums.distinctBy { it.uri },
    playlists = playlists.distinctBy { it.uri },
    shows = shows.distinctBy { it.uri },
    episodes = episodes.distinctBy { it.uri },
)

/** Action sheet target for a result row. */
fun SearchItem.actionTarget(myUsername: String?): MediaActionTarget = when (this) {
    is SearchItem.Song -> MediaActionTarget.TrackTarget(track, contextUri = track.album?.uri)
    is SearchItem.ArtistItem -> MediaActionTarget.ArtistTarget(artist)
    is SearchItem.AlbumItem -> MediaActionTarget.AlbumTarget(album)
    is SearchItem.PlaylistItem -> MediaActionTarget.PlaylistTarget(
        playlist,
        isOwned = myUsername != null && playlist.owner?.username == myUsername,
    )
    is SearchItem.ShowItem -> MediaActionTarget.ShowTarget(show)
    is SearchItem.EpisodeItem -> MediaActionTarget.EpisodeTarget(episode)
}

/** The "Top" filter page, precomputed for the UI. */
@Immutable
data class TopSections(
    val top: MediaRef?,
    val songs: List<Track>,
    val artists: List<MediaRef>,
    val albums: List<MediaRef>,
    val playlists: List<MediaRef>,
    val shows: List<MediaRef>,
    val episodes: List<Episode>,
    /** Every result by URI (full models for actions and playback). */
    val byUri: Map<String, SearchItem>,
)

fun SearchResults.toTopSections(songCount: Int = 5, episodeCount: Int = 3): TopSections {
    val items = SearchType.entries.flatMap { itemsOf(it) }
    return TopSections(
        top = topResultOrBest(),
        songs = tracks.take(songCount),
        artists = artists.map { it.toMediaRef() },
        albums = albums.map { it.toMediaRef() },
        playlists = playlists.map { it.toMediaRef() },
        shows = shows.map { it.toMediaRef() },
        episodes = episodes.take(episodeCount),
        byUri = items.associateBy { it.ref.uri },
    )
}

/** "All results" (Top filter) state. */
@Immutable
sealed interface TopResultsState {
    data object Idle : TopResultsState
    data object Loading : TopResultsState
    data class Ready(
        val query: String,
        val sections: TopSections,
        /** A newer query is loading; [sections] are from the previous one. */
        val isRefreshing: Boolean = false,
    ) : TopResultsState
    data class Empty(val query: String) : TopResultsState
    data class Failed(val query: String, val error: BrowseError) : TopResultsState
}

/** Small LRU of recent result pages so switching filters or going back does not refetch. */
class SearchCache<V>(private val capacity: Int = 12) {
    private val map = object : LinkedHashMap<String, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > capacity
    }

    @Synchronized operator fun get(key: String): V? = map[key]

    @Synchronized operator fun set(key: String, value: V) {
        map[key] = value
    }

    @Synchronized fun clear() = map.clear()
}
