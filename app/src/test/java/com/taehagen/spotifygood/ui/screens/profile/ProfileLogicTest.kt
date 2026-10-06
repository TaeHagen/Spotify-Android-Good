package com.taehagen.spotifygood.ui.screens.profile

import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.RootlistEntryType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLogicTest {
    @Test
    fun ownedPlaylistsComeFromTheWholeRootlist() {
        val rootlist = Rootlist(
            listOf(
                RootlistEntry(RootlistEntryType.PLAYLIST, "spotify:playlist:1", "Mine", owner = PlaylistOwner("me", "Me")),
                RootlistEntry(RootlistEntryType.PLAYLIST, "spotify:playlist:2", "Followed", owner = PlaylistOwner("other")),
                RootlistEntry(
                    RootlistEntryType.FOLDER,
                    name = "Folder",
                    children = listOf(
                        RootlistEntry(RootlistEntryType.PLAYLIST, "spotify:playlist:3", "Nested", owner = PlaylistOwner("me")),
                        RootlistEntry(RootlistEntryType.PLAYLIST, null, "Broken", owner = PlaylistOwner("me")),
                    ),
                ),
            ),
        )
        val owned = rootlist.ownedBy("me")
        assertEquals(listOf("Mine", "Nested"), owned.map { it.name })
        assertEquals(MediaType.PLAYLIST, owned.first().type)
        assertEquals("Me", owned.first().subtitle)
    }
}
