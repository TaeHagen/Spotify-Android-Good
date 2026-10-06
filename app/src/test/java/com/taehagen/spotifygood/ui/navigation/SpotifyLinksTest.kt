package com.taehagen.spotifygood.ui.navigation

import com.taehagen.spotifygood.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyLinksTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"

    @Test
    fun parsesSpotifyUrisForEveryEntityType() {
        mapOf(
            "track" to MediaType.TRACK,
            "album" to MediaType.ALBUM,
            "artist" to MediaType.ARTIST,
            "playlist" to MediaType.PLAYLIST,
            "show" to MediaType.SHOW,
            "episode" to MediaType.EPISODE,
        ).forEach { (wire, type) ->
            assertEquals(SpotifyLink.Media(type, id), SpotifyLinks.parse("spotify:$wire:$id"))
        }
    }

    @Test
    fun canonicalUriRoundTrips() {
        assertEquals("spotify:album:$id", SpotifyLinks.canonicalUri("spotify:album:$id"))
        assertEquals("spotify:track:$id", (SpotifyLinks.parse(" spotify:track:$id ") as SpotifyLink.Media).uri)
    }

    @Test
    fun legacyUserPlaylistUriIsNormalised() {
        val link = SpotifyLinks.parse("spotify:user:someone:playlist:$id")
        assertEquals(SpotifyLink.Media(MediaType.PLAYLIST, id), link)
        assertEquals("spotify:playlist:$id", SpotifyLinks.canonicalUri("spotify:user:someone:playlist:$id"))
    }

    @Test
    fun likedSongsUris() {
        assertEquals(SpotifyLink.LikedSongs("me"), SpotifyLinks.parse("spotify:user:me:collection"))
        assertEquals(SpotifyLink.LikedSongs(null), SpotifyLinks.parse("spotify:collection:tracks"))
        assertEquals(SpotifyLink.LikedSongs(null), SpotifyLinks.parse("https://open.spotify.com/collection/tracks"))
        assertEquals(SpotifyLink.Library, SpotifyLinks.parse("https://open.spotify.com/collection/albums"))
        assertEquals(Route.LikedSongs, SpotifyLinks.routeFor(SpotifyLinks.parse("spotify:user:me:collection")!!))
    }

    @Test
    fun profileAndSearchUris() {
        assertEquals(SpotifyLink.Profile("someone"), SpotifyLinks.parse("spotify:user:someone"))
        assertEquals(SpotifyLink.Search("daft punk"), SpotifyLinks.parse("spotify:search:daft+punk"))
        assertEquals(SpotifyLink.Search("a:b"), SpotifyLinks.parse("spotify:search:a:b"))
        assertEquals(
            Route.SearchResults("daft punk", "track"),
            SpotifyLinks.routeFor(SpotifyLink.Search("daft punk")),
        )
    }

    @Test
    fun slashStyleSpotifyUri() {
        assertEquals(SpotifyLink.Media(MediaType.ALBUM, id), SpotifyLinks.parse("spotify://album/$id"))
    }

    @Test
    fun webLinksStripQueryAndLocalePrefix() {
        val expected = SpotifyLink.Media(MediaType.TRACK, id)
        assertEquals(expected, SpotifyLinks.parse("https://open.spotify.com/track/$id"))
        assertEquals(expected, SpotifyLinks.parse("https://open.spotify.com/track/$id?si=abc123&utm_source=copy-link"))
        assertEquals(expected, SpotifyLinks.parse("https://open.spotify.com/intl-de/track/$id?si=x"))
        assertEquals(expected, SpotifyLinks.parse("http://open.spotify.com/track/$id#frag"))
        assertEquals(expected, SpotifyLinks.parse("https://OPEN.SPOTIFY.COM/TRACK/$id"))
        assertEquals(expected, SpotifyLinks.parse("https://play.spotify.com/track/$id"))
        assertEquals(SpotifyLink.Media(MediaType.EPISODE, id), SpotifyLinks.parse("https://open.spotify.com/embed/episode/$id"))
        assertEquals(SpotifyLink.Media(MediaType.SHOW, id), SpotifyLinks.parse("https://open.spotify.com/show/$id/"))
    }

    @Test
    fun webUserLinks() {
        assertEquals(
            SpotifyLink.Media(MediaType.PLAYLIST, id),
            SpotifyLinks.parse("https://open.spotify.com/user/someone/playlist/$id?si=1"),
        )
        assertEquals(SpotifyLink.Profile("some one"), SpotifyLinks.parse("https://open.spotify.com/user/some%20one"))
        assertEquals(SpotifyLink.Search("c++ rock"), SpotifyLinks.parse("https://open.spotify.com/search/c++%20rock"))
    }

    @Test
    fun rejectsUnsupportedInput() {
        listOf(
            null,
            "",
            "hello",
            "spotify:",
            "spotify:track:",
            "spotify:track:not-an-id!",
            "spotify:local:artist:album:title:180",
            "spotify:genre:pop",
            "https://example.com/track/$id",
            "https://open.spotify.com/",
            "https://open.spotify.com/genre/pop-page",
            "https://spotify.link/abcdef",
            "ftp://open.spotify.com/track/$id",
        ).forEach { assertNull("expected null for $it", SpotifyLinks.parse(it)) }
    }

    @Test
    fun findsLinkInSharedText() {
        assertEquals(
            "https://open.spotify.com/album/$id?si=Zz",
            SpotifyLinks.findLink("Check this out: https://open.spotify.com/album/$id?si=Zz."),
        )
        assertEquals("spotify:artist:$id", SpotifyLinks.findLink("(spotify:artist:$id)"))
        assertEquals(
            "https://open.spotify.com/track/$id",
            SpotifyLinks.findLink("first https://example.com then https://open.spotify.com/track/$id"),
        )
        assertNull(SpotifyLinks.findLink("no links here"))
        assertNull(SpotifyLinks.findLink(null))
    }

    @Test
    fun webUrlsForSharing() {
        assertEquals("https://open.spotify.com/track/$id", SpotifyLinks.webUrl("spotify:track:$id"))
        assertEquals("https://open.spotify.com/playlist/$id", SpotifyLinks.webUrl("spotify:user:x:playlist:$id"))
        assertEquals("https://open.spotify.com/collection/tracks", SpotifyLinks.webUrl("spotify:user:x:collection"))
        assertEquals("https://open.spotify.com/user/a%20b", SpotifyLinks.webUrl("spotify:user:a+b"))
        assertNull(SpotifyLinks.webUrl("not a uri"))
    }

    @Test
    fun routesForEntities() {
        assertEquals(Route.Album("spotify:album:$id"), SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.ALBUM, id)))
        assertEquals(Route.Artist("spotify:artist:$id"), SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.ARTIST, id)))
        assertEquals(Route.Playlist("spotify:playlist:$id"), SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.PLAYLIST, id)))
        assertEquals(Route.Show("spotify:show:$id"), SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.SHOW, id)))
        assertEquals(Route.Episode("spotify:episode:$id"), SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.EPISODE, id)))
        assertEquals(Route.Profile("u"), SpotifyLinks.routeFor(SpotifyLink.Profile("u")))
        assertEquals(Route.Library, SpotifyLinks.routeFor(SpotifyLink.Library))
        // Tracks are played, not opened.
        assertNull(SpotifyLinks.routeFor(SpotifyLink.Media(MediaType.TRACK, id)))
    }

    @Test
    fun typeAndIdHelpers() {
        assertEquals(MediaType.ALBUM, SpotifyLinks.typeOf("https://open.spotify.com/album/$id"))
        assertEquals(MediaType.COLLECTION, SpotifyLinks.typeOf("spotify:user:me:collection"))
        assertEquals(id, SpotifyLinks.idOf("spotify:episode:$id"))
        assertNull(SpotifyLinks.idOf("spotify:user:me"))
    }
}
