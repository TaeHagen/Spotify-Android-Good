package com.taehagen.spotifygood.ui.screens.profile

import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.RootlistEntryType
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val row = owned.first().toMediaRef()
        assertEquals(MediaType.PLAYLIST, row.type)
        assertEquals("Me", row.subtitle)
    }

    @Test
    fun anotherUsersPublicPlaylistsAreListedAndAttributedToThem() {
        val refs = (1..60).map { PlaylistRef(uri = "spotify:playlist:$it", name = "P$it") } +
            PlaylistRef(uri = "spotify:playlist:1", name = "Duplicate") +
            PlaylistRef(uri = "", name = "No uri")
        val user = User(username = "anna", displayName = "Anna", publicPlaylists = refs)
        val listed = publicPlaylistsOf(user)
        assertEquals(MAX_PUBLIC_PLAYLISTS, listed.size)
        assertEquals("P1", listed.first().name)
        assertTrue(listed.all { it.owner == PlaylistOwner("anna", "Anna") })
        // A playlist with its own owner keeps it.
        val collaborative = PlaylistRef(uri = "spotify:playlist:c", name = "C", owner = PlaylistOwner("bob"))
        assertEquals("bob", publicPlaylistsOf(user.copy(publicPlaylists = listOf(collaborative))).single().owner?.username)
    }

    @Test
    fun onlyMyOwnPlaylistsOfferEditAndDelete() {
        val theirs = PlaylistRef(uri = "spotify:playlist:t", name = "Theirs", owner = PlaylistOwner("anna"))
        val mine = PlaylistRef(uri = "spotify:playlist:m", name = "Mine", owner = PlaylistOwner("me"))
        assertFalse(profilePlaylistTarget(theirs, myUsername = "me").isOwned)
        assertTrue(profilePlaylistTarget(mine, myUsername = "me").isOwned)
        // Unknown owner or unknown me: never claim ownership.
        assertFalse(profilePlaylistTarget(theirs.copy(owner = null), myUsername = "me").isOwned)
        assertFalse(profilePlaylistTarget(mine, myUsername = null).isOwned)
    }
}
