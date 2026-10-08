package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lists built from downloads show the downloaded covers, offline too. */
class OfflineCoversTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun img(id: String, width: Int) = Image("https://i.scdn.co/image/$id", width, width)

    private val albumImages = listOf(img("a64", 64), img("a300", 300), img("a640", 640))
    private val track = Track(uri = "spotify:track:t", name = "Song", album = AlbumRef(uri = "spotify:album:a", name = "Album", images = albumImages))
    private val cover = "/data/user/0/app/no_backup/offline/images/a640.jpg"

    @Test
    fun everySizeOfACompletedTracksAlbumArtShowsItsCover() {
        val urls = DownloadRules.coverUrls(json, json.encodeToString(Track.serializer(), track), null)
        assertEquals(albumImages.map { it.url }, urls)
        val maps = DownloadRules.offlineCoverMaps(listOf(DownloadRules.CoverSource(track.uri, cover, urls)), emptyList())
        // What a track row loads (TrackRow: best(120), the 300 px variant).
        assertEquals(cover, maps.exact[track.album!!.images.best(120)!!])
        assertEquals(cover, maps.exact[track.album!!.images.best()!!])
    }

    @Test
    fun anEpisodeUsesItsOwnImagesElseItsShows() {
        val show = ShowRef(uri = "spotify:show:s", name = "Show", images = listOf(img("s300", 300)))
        val own = Episode(uri = "spotify:episode:e", name = "Ep", show = show, images = listOf(img("e640", 640)))
        assertEquals(listOf("https://i.scdn.co/image/e640"), DownloadRules.coverUrls(json, json.encodeToString(Episode.serializer(), own), null))
        val bare = own.copy(images = emptyList())
        assertEquals(listOf("https://i.scdn.co/image/s300"), DownloadRules.coverUrls(json, json.encodeToString(Episode.serializer(), bare), null))
    }

    @Test
    fun theRecordStandsInForMissingMetadata() {
        val record = """{"uri":"spotify:track:t","track":${json.encodeToString(Track.serializer(), track)}}"""
        assertEquals(albumImages.map { it.url }, DownloadRules.coverUrls(json, null, record))
        assertEquals(emptyList<String>(), DownloadRules.coverUrls(json, "not json", null))
    }

    @Test
    fun aCollectionImageFallsBackToItsFirstDownloadedMember() {
        val first = DownloadRules.CoverSource("spotify:track:1", "/covers/1.jpg", listOf("https://i.scdn.co/image/one"))
        val noCover = DownloadRules.CoverSource("spotify:track:0", null, listOf("https://i.scdn.co/image/zero"))
        val maps = DownloadRules.offlineCoverMaps(
            listOf(noCover, first),
            listOf(
                "https://mosaic.scdn.co/playlist" to listOf("spotify:track:0", "spotify:track:1"),
                // An album's image is one of its tracks' URLs: served exactly, no fallback needed.
                "https://i.scdn.co/image/one" to listOf("spotify:track:1"),
                null to listOf("spotify:track:1"),
            ),
        )
        assertEquals("/covers/1.jpg", maps.fallback["https://mosaic.scdn.co/playlist"])
        assertEquals("/covers/1.jpg", maps.exact["https://i.scdn.co/image/one"])
        assertNull(maps.fallback["https://i.scdn.co/image/one"])
        assertNull("no cover file", maps.exact["https://i.scdn.co/image/zero"])
        assertEquals(1, maps.fallback.size)
    }

    @Test
    fun theCoversServeWhatTheMapsSay() {
        val covers = OfflineCovers()
        assertNull(covers.exact("https://i.scdn.co/image/a300"))
        covers.update(DownloadRules.CoverMaps(mapOf("https://i.scdn.co/image/a300" to cover), mapOf("https://mosaic" to cover)))
        assertEquals(cover, covers.exact("https://i.scdn.co/image/a300"))
        assertEquals(cover, covers.fallback("https://mosaic"))
        assertNull(covers.exact("https://mosaic"))
    }
}
