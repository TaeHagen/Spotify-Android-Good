package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Rootlist
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `isPublic` on rootlist entries and playlist pages (docs §6.5): what the privacy toggles read. */
class PlaylistPrivacyModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rootlistEntriesCarryTheirPublicState() {
        val rootlist = json.decodeFromString(
            Rootlist.serializer(),
            """{"items":[
                {"type":"playlist","uri":"spotify:playlist:a","name":"Road trip","isPublic":true},
                {"type":"folder","uri":"spotify:user:u:folder:f","name":"Folder","children":[
                    {"type":"playlist","uri":"spotify:playlist:b","name":"Mine","collaborative":true,"isPublic":false}
                ]}
            ]}""",
        )
        val (a, b) = rootlist.flatPlaylists()
        assertEquals(true, a.isPublic)
        assertEquals(false, b.isPublic)
        assertEquals(true, b.collaborative)
        assertNull("folders have none", rootlist.items[1].isPublic)
    }

    @Test
    fun aPlaylistPageKnowsItOnlyWhenItIsInTheLibrary() {
        val page = json.decodeFromString(Playlist.serializer(), """{"uri":"spotify:playlist:a","name":"A","isOwnedByMe":true,"isPublic":true}""")
        assertEquals(true, page.isPublic)
        val unknown = json.decodeFromString(Playlist.serializer(), """{"uri":"spotify:playlist:a","name":"A"}""")
        assertNull(unknown.isPublic)
    }
}
