package com.taehagen.spotifygood.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (navigation-compose 2.10). */
@Serializable
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Search : Route
    @Serializable data object Library : Route
    @Serializable data object LikedSongs : Route
    @Serializable data object Downloads : Route
    @Serializable data object Settings : Route
    @Serializable data class Album(val uri: String) : Route
    @Serializable data class Artist(val uri: String) : Route
    /** All releases of an artist; [group] = "albums" | "singles" | "compilations" | "appears_on". */
    @Serializable data class ArtistDiscography(val uri: String, val group: String) : Route
    @Serializable data class Playlist(val uri: String) : Route
    @Serializable data class Show(val uri: String) : Route
    @Serializable data class Episode(val uri: String) : Route
    @Serializable data class Profile(val username: String? = null) : Route
    /** Full result list for one search type ("track", "artist", ...). */
    @Serializable data class SearchResults(val query: String, val type: String) : Route
}

/** Something the action sheet can act on. */
sealed interface MediaActionTarget {
    data class TrackTarget(
        val track: Track,
        /** Context the row was shown in (to "play from here"). */
        val contextUri: String? = null,
        /** Set when the row belongs to an editable playlist (enables "Remove from playlist"). */
        val playlistUri: String? = null,
        val playlistIndex: Int? = null,
        val playlistRevision: String? = null,
        /** Set when the row is a queue entry (enables "Remove from queue"). */
        val queueUid: String? = null,
    ) : MediaActionTarget
    data class EpisodeTarget(val episode: Episode) : MediaActionTarget
    data class AlbumTarget(val album: AlbumRef) : MediaActionTarget
    data class ArtistTarget(val artist: ArtistRef) : MediaActionTarget
    data class PlaylistTarget(val playlist: PlaylistRef, val isOwned: Boolean) : MediaActionTarget
    data class ShowTarget(val show: ShowRef) : MediaActionTarget
}

/** App-wide navigation and overlay control, provided by the main scaffold. */
interface AppNavigator {
    fun navigate(route: Route)
    fun back()
    /** Opens the page for a media reference (album/artist/playlist/show/episode/collection). Tracks play. */
    fun open(ref: MediaRef)
    /** Opens a `spotify:` URI or open.spotify.com link. Returns false if unsupported. */
    fun openUri(uri: String): Boolean
    fun openNowPlaying()
    fun closeNowPlaying()
    fun openQueue()
    fun openLyrics()
    fun openDevices()
    fun showActions(target: MediaActionTarget)
    fun addToPlaylist(uris: List<String>)
    fun showMessage(message: String)
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { error("No AppNavigator provided") }
