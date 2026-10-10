package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.CollectionChangeItem
import com.taehagen.spotifygood.model.CollectionChangedPush
import com.taehagen.spotifygood.model.LibraryPush
import com.taehagen.spotifygood.model.PlaylistChangedPush
import com.taehagen.spotifygood.model.RootlistChangedPush
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library changes Spotify pushes (docs §9.8): echoes of edits made here are dropped, what costs no
 * request happens at once, the rest once a burst settled and only while the app is in the
 * foreground (held until then).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPushesTest {
    private val playlist = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"

    private class Effects : LibraryPushEffects {
        val log = mutableListOf<String>()

        override suspend fun playlistStale(uri: String) {
            log += "stale $uri"
        }

        override suspend fun rootlistStale(reload: Boolean) {
            log += if (reload) "reload rootlist" else "stale rootlist"
        }

        override suspend fun setStale(set: String, reload: Boolean) {
            log += if (reload) "reload $set" else "stale $set"
        }

        override fun savedChanged(items: List<CollectionChangeItem>) {
            log += "saved " + items.joinToString { (if (it.removed) "-" else "+") + it.uri }
        }

        override fun playlistRevisions(revisions: Map<String, String>) {
            log += "mosaics $revisions"
        }

        override suspend fun syncPlaylistDownload(uri: String, revision: String?) {
            log += "sync $uri $revision"
        }

        override suspend fun syncLikedSongsDownload() {
            log += "sync liked"
        }
    }

    private class Fixture(scope: TestScope, foreground: Boolean = true) {
        val effects = Effects()
        val visible = MutableStateFlow(foreground)
        val own = OwnLibraryEdits(clock = { scope.currentTime })
        val pushes = MutableSharedFlow<LibraryPush>(extraBufferCapacity = 64)
        val coordinator = LibraryPushes(scope.backgroundScope, visible, own, effects)
        val changes = mutableListOf<LibraryChange>()

        init {
            coordinator.start(pushes)
            scope.backgroundScope.launch { coordinator.changes.collect { changes += it } }
            scope.runCurrent()
        }

        fun push(push: LibraryPush) {
            check(pushes.tryEmit(push))
        }
    }

    /**
     * Runs what is due within a few seconds (longer than a settle delay, shorter than the check
     * interval and the echo window). Not advanceUntilIdle: it leaves the background scope's work.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_STEP_MS)
        runCurrent()
    }

    @Test
    fun aPlaylistChangedElsewhereIsStaleAtOnceAndTheRestFollowsTheBurst() = runTest {
        val f = Fixture(this)
        f.push(PlaylistChangedPush(playlist, "00AB"))
        f.push(PlaylistChangedPush(playlist, "00ac"))
        runCurrent()
        assertEquals(listOf("stale $playlist", "stale $playlist"), f.effects.log)
        assertEquals(
            "the open page hears of each",
            listOf(LibraryChange.Playlist(playlist, "00ab"), LibraryChange.Playlist(playlist, "00ac")),
            f.changes,
        )
        f.effects.log.clear()
        advanceTimeBy(LibraryPushes.SETTLE_MS + 1)
        assertEquals(
            listOf("stale rootlist", "reload rootlist", "mosaics {$playlist=00ac}", "sync $playlist 00ac"),
            f.effects.log,
        )
    }

    @Test
    fun theUserScopedFormNamesTheSamePlaylist() = runTest {
        val f = Fixture(this)
        val revisions = mutableListOf<String?>()
        backgroundScope.launch { f.coordinator.playlistChanges("spotify:user:alice:playlist:37i9dQZF1DXcBWIGoYBM5M").collect { revisions += it } }
        runCurrent()
        f.push(PlaylistChangedPush(playlist, "01"))
        f.push(PlaylistChangedPush("spotify:playlist:0000000000000000000000", "02"))
        runCurrent()
        assertEquals(listOf<String?>("01"), revisions)
    }

    @Test
    fun theEchoOfAnEditMadeHereIsDropped() = runTest {
        val f = Fixture(this)
        // In flight: the push can come before the edit's answer.
        f.own.beginPlaylist(playlist)
        f.push(PlaylistChangedPush(playlist, "0002"))
        runCurrent()
        f.own.endPlaylist(playlist, "0002")
        // After it, with the revision it produced (or an earlier batch of a long add).
        f.own.notePlaylistRevision(playlist, "0001")
        f.push(PlaylistChangedPush(playlist, "0002"))
        f.push(PlaylistChangedPush("spotify:user:me:playlist:37i9dQZF1DXcBWIGoYBM5M", "0001"))
        settle()
        assertTrue(f.effects.log.isEmpty())
        assertTrue(f.changes.isEmpty())
        // Another device's change of the same playlist is not one.
        f.push(PlaylistChangedPush(playlist, "0003"))
        settle()
        assertEquals(listOf(LibraryChange.Playlist(playlist, "0003")), f.changes)
    }

    @Test
    fun inTheBackgroundWhatCostsRequestsWaitsForTheForeground() = runTest {
        val f = Fixture(this, foreground = false)
        f.push(PlaylistChangedPush(playlist, "0a"))
        f.push(RootlistChangedPush())
        settle()
        assertEquals("no request in the background", listOf("stale $playlist", "stale rootlist"), f.effects.log)
        f.effects.log.clear()
        f.visible.value = true
        runCurrent()
        assertEquals(listOf("reload rootlist", "mosaics {$playlist=0a}", "sync $playlist 0a"), f.effects.log)
        // Applied once.
        f.visible.value = false
        runCurrent()
        f.visible.value = true
        settle()
        assertEquals(3, f.effects.log.size)
    }

    @Test
    fun aRootlistWriteMadeHereIsNotAChangeMadeElsewhere() = runTest {
        val f = Fixture(this)
        f.own.beginRootlist()
        f.push(RootlistChangedPush())
        f.own.endRootlist()
        advanceTimeBy(OwnLibraryEdits.ECHO_WINDOW_MS / 2)
        f.push(RootlistChangedPush("0001"))
        settle()
        assertTrue(f.effects.log.isEmpty())
        advanceTimeBy(OwnLibraryEdits.ECHO_WINDOW_MS)
        f.push(RootlistChangedPush())
        settle()
        assertEquals(listOf("stale rootlist", "reload rootlist"), f.effects.log)
    }

    @Test
    fun theTwoFormsOfALikeAreOneChangeAndItsItemsAreKept() = runTest {
        val f = Fixture(this)
        val song = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        // The protobuf form (no items) and the JSON form of one like.
        f.push(CollectionChangedPush("collection"))
        f.push(CollectionChangedPush("collection", listOf(CollectionChangeItem(song))))
        runCurrent()
        assertEquals(listOf("saved +$song"), f.effects.log)
        settle()
        assertEquals(listOf(LibraryChange.Collection("collection", listOf(CollectionChangeItem(song)))), f.changes)
        assertEquals(listOf("saved +$song", "stale collection", "reload collection", "sync liked"), f.effects.log)
    }

    @Test
    fun aLikeMadeHereIsDroppedButAnotherDevicesChangeOfTheSameSetIsNot() = runTest {
        val f = Fixture(this)
        val mine = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        val theirs = "spotify:track:1kuPgsWuwfNVHTPDBGMMj4"
        f.own.noteSaved(listOf(mine), true)
        f.push(CollectionChangedPush("collection"))
        f.push(CollectionChangedPush("collection", listOf(CollectionChangeItem(mine))))
        settle()
        assertTrue("both forms of its echo", f.effects.log.isEmpty())
        // Another device unlikes it, and likes another song: those count.
        f.push(CollectionChangedPush("collection", listOf(CollectionChangeItem(mine, removed = true), CollectionChangeItem(theirs))))
        settle()
        assertEquals(
            listOf(LibraryChange.Collection("collection", listOf(CollectionChangeItem(mine, removed = true), CollectionChangeItem(theirs)))),
            f.changes,
        )
        // Long after the write, a push that names nothing is another device's.
        advanceTimeBy(OwnLibraryEdits.ECHO_WINDOW_MS)
        f.changes.clear()
        f.push(CollectionChangedPush("collection"))
        settle()
        assertEquals(listOf(LibraryChange.Collection("collection", null)), f.changes)
    }

    @Test
    fun logoutDropsWhatWasHeld() = runTest {
        val f = Fixture(this, foreground = false)
        f.own.noteSaved(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"), true)
        f.push(PlaylistChangedPush(playlist, "0a"))
        settle()
        f.coordinator.clear()
        f.effects.log.clear()
        f.visible.value = true
        settle()
        assertTrue(f.effects.log.isEmpty())
        assertFalse(f.own.isSetEcho("collection"))
    }

    private companion object {
        const val SETTLE_STEP_MS = 5_000L
    }
}
