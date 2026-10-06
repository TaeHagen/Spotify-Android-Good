package com.taehagen.spotifygood.ui.navigation

import com.taehagen.spotifygood.model.MediaType
import java.net.URLDecoder
import java.net.URLEncoder

/** A parsed `spotify:` URI or open.spotify.com link. Pure Kotlin (JVM-testable). */
sealed interface SpotifyLink {
    /** A catalog entity; [uri] is canonical (`spotify:<type>:<id>`, user playlists normalised). */
    data class Media(val type: MediaType, val id: String) : SpotifyLink {
        val uri: String get() = "spotify:${type.wire}:$id"
    }

    /** Liked Songs ([username] from `spotify:user:<u>:collection`, null when not given). */
    data class LikedSongs(val username: String? = null) : SpotifyLink

    /** Another part of the library (`/collection/albums`, `spotify:collection:your-episodes`, ...). */
    data object Library : SpotifyLink

    data class Profile(val username: String) : SpotifyLink

    data class Search(val query: String) : SpotifyLink
}

/** Wire name of a media type as used in Spotify URIs and links. */
val MediaType.wire: String
    get() = when (this) {
        MediaType.TRACK -> "track"
        MediaType.ALBUM -> "album"
        MediaType.ARTIST -> "artist"
        MediaType.PLAYLIST -> "playlist"
        MediaType.SHOW -> "show"
        MediaType.EPISODE -> "episode"
        MediaType.COLLECTION -> "collection"
    }

object SpotifyLinks {
    private val ENTITY_TYPES = mapOf(
        "track" to MediaType.TRACK,
        "album" to MediaType.ALBUM,
        "artist" to MediaType.ARTIST,
        "playlist" to MediaType.PLAYLIST,
        "show" to MediaType.SHOW,
        "episode" to MediaType.EPISODE,
    )
    private val WEB_HOSTS = setOf("open.spotify.com", "play.spotify.com")
    private val ID = Regex("^[A-Za-z0-9]{1,64}$")
    private val LINK_IN_TEXT = Regex(
        """(https?://(?:open|play)\.spotify\.com/[^\s<>"']+|spotify:[A-Za-z0-9:%._+~-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses `spotify:<type>:<id>`, `spotify:user:<u>:playlist:<id>`, `spotify:user:<u>:collection`,
     * `spotify:user:<u>`, `spotify:search:<q>`, `spotify://<type>/<id>` and
     * `https://open.spotify.com/[intl-xx/][embed/]<type>/<id>?si=…` (query and fragment ignored).
     * Returns null for anything else (e.g. short links, local files, genres).
     */
    fun parse(input: String?): SpotifyLink? {
        val raw = input?.trim().orEmpty()
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        return when (raw.substring(0, colon).lowercase()) {
            "spotify" -> parseSpotifyUri(raw.substring(colon + 1))
            "http", "https" -> parseWebLink(raw)
            else -> null
        }
    }

    /** First supported Spotify link inside free text (e.g. a shared message), or null. */
    fun findLink(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return LINK_IN_TEXT.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '!', ';', ':') }
            .firstOrNull { parse(it) != null }
    }

    /** Canonical `spotify:` URI for any supported link, or null. */
    fun canonicalUri(input: String?): String? = when (val link = parse(input)) {
        is SpotifyLink.Media -> link.uri
        else -> null
    }

    /** Public web link for sharing (`https://open.spotify.com/<type>/<id>`), or null. */
    fun webUrl(uri: String?): String? = when (val link = parse(uri)) {
        is SpotifyLink.Media -> "https://open.spotify.com/${link.type.wire}/${link.id}"
        is SpotifyLink.LikedSongs -> "https://open.spotify.com/collection/tracks"
        is SpotifyLink.Profile -> "https://open.spotify.com/user/${encode(link.username)}"
        is SpotifyLink.Search -> "https://open.spotify.com/search/${encode(link.query)}"
        SpotifyLink.Library, null -> null
    }

    /** Media type of a URI/link (Liked Songs = [MediaType.COLLECTION]), or null. */
    fun typeOf(uri: String?): MediaType? = when (val link = parse(uri)) {
        is SpotifyLink.Media -> link.type
        is SpotifyLink.LikedSongs -> MediaType.COLLECTION
        else -> null
    }

    /** Base62 id of an entity URI/link, or null. */
    fun idOf(uri: String?): String? = (parse(uri) as? SpotifyLink.Media)?.id

    /**
     * Navigation destination for a link. Tracks have no page (they are played) and return null,
     * as do unsupported links.
     */
    fun routeFor(link: SpotifyLink): Route? = when (link) {
        is SpotifyLink.Media -> when (link.type) {
            MediaType.ALBUM -> Route.Album(link.uri)
            MediaType.ARTIST -> Route.Artist(link.uri)
            MediaType.PLAYLIST -> Route.Playlist(link.uri)
            MediaType.SHOW -> Route.Show(link.uri)
            MediaType.EPISODE -> Route.Episode(link.uri)
            MediaType.COLLECTION -> Route.LikedSongs
            MediaType.TRACK -> null
        }
        is SpotifyLink.LikedSongs -> Route.LikedSongs
        SpotifyLink.Library -> Route.Library
        is SpotifyLink.Profile -> Route.Profile(link.username)
        is SpotifyLink.Search -> Route.SearchResults(link.query, MediaType.TRACK.wire)
    }

    // ---------------------------------------------------------------------------------------

    private fun parseSpotifyUri(body: String): SpotifyLink? {
        var b = body.substringBefore('?').substringBefore('#')
        if (b.startsWith("//")) b = b.removePrefix("//").replace('/', ':')
        val segments = b.split(':').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = true) }
        return fromSegments(segments)
    }

    private fun parseWebLink(url: String): SpotifyLink? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        if (host !in WEB_HOSTS) return null
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        var segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = false) }
        if (segments.firstOrNull()?.lowercase()?.startsWith("intl-") == true) segments = segments.drop(1)
        if (segments.firstOrNull()?.lowercase() in setOf("embed", "embed-podcast")) segments = segments.drop(1)
        return fromSegments(segments)
    }

    private fun fromSegments(segments: List<String>): SpotifyLink? {
        if (segments.isEmpty()) return null
        val head = segments[0].lowercase()
        ENTITY_TYPES[head]?.let { type ->
            val id = segments.getOrNull(1) ?: return null
            return if (ID.matches(id)) SpotifyLink.Media(type, id) else null
        }
        return when (head) {
            "user" -> {
                val user = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                when (segments.getOrNull(2)?.lowercase()) {
                    null -> SpotifyLink.Profile(user)
                    "playlist" -> segments.getOrNull(3)?.takeIf { ID.matches(it) }
                        ?.let { SpotifyLink.Media(MediaType.PLAYLIST, it) }
                    "collection" -> when (segments.getOrNull(3)?.lowercase()) {
                        null, "tracks" -> SpotifyLink.LikedSongs(user)
                        else -> SpotifyLink.Library
                    }
                    else -> null
                }
            }
            "collection" -> when (segments.getOrNull(1)?.lowercase()) {
                null, "tracks" -> SpotifyLink.LikedSongs(null)
                else -> SpotifyLink.Library
            }
            "search" -> segments.drop(1).joinToString(":").trim().takeIf { it.isNotEmpty() }
                ?.let { SpotifyLink.Search(it) }
            else -> null
        }
    }

    private fun decode(segment: String, plusIsSpace: Boolean): String {
        val prepared = if (plusIsSpace) segment else segment.replace("+", "%2B")
        return try {
            URLDecoder.decode(prepared, "UTF-8")
        } catch (_: IllegalArgumentException) {
            segment
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
