package com.taehagen.spotifygood.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/** Follow-ups of the SD-card download location (docs §9.4, §9.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class SdCardFollowUpsTest {
    private val base: File = Files.createTempDirectory("sd-card").toFile()

    @After
    fun cleanUp() {
        base.deleteRecursively()
    }

    // ---- artwork of covers on the card ---------------------------------------------------------------

    private fun dirs(): List<File> {
        val noBackup = File(base, "data/no_backup")
        val files = File(base, "data/files")
        val card = File(base, "storage/1234-ABCD/Android/data/pkg/files")
        val roots = DownloadLocations.candidateRoots(noBackup, files, listOf(File(base, "storage/emulated/0/Android/data/pkg/files"), null, card))
        return DownloadLocations.imageDirsOf(roots)
    }

    private fun cover(dir: String): File = File(base, "$dir/${"ab".repeat(20)}.jpg").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun theArtworkProviderServesCoversOnTheCard() {
        val dirs = dirs()
        assertEquals(4, dirs.size) // internal, legacy, the emulated dir, the card (an unmounted volume is null)
        val onCard = cover("storage/1234-ABCD/Android/data/pkg/files/offline/images")
        val internal = cover("data/no_backup/offline/images")
        // sourceOf's boundary (canonical containment) and artworkUri's check agree.
        assertTrue(DownloadLocations.isInside(onCard, dirs))
        assertTrue(DownloadLocations.namesFileIn(onCard.path, dirs))
        assertTrue(DownloadLocations.isInside(internal, dirs))
        assertTrue(DownloadLocations.namesFileIn(internal.path, dirs))
    }

    @Test
    fun anythingElseStaysRefused() {
        val dirs = dirs()
        val elsewhere = cover("storage/1234-ABCD/DCIM")
        assertFalse(DownloadLocations.isInside(elsewhere, dirs))
        assertFalse(DownloadLocations.namesFileIn(elsewhere.path, dirs))
        // Traversal out of an allowed directory.
        val sneaky = File(base, "data/no_backup/offline/images/../../../../storage/1234-ABCD/DCIM/${elsewhere.name}")
        assertFalse(DownloadLocations.isInside(sneaky, dirs))
        assertFalse(DownloadLocations.namesFileIn(sneaky.path, dirs))
        // The card's other app data, and a relative path.
        assertFalse(DownloadLocations.isInside(cover("storage/1234-ABCD/Android/data/pkg/files/other"), dirs))
        assertFalse(DownloadLocations.namesFileIn("offline/images/x.jpg", dirs))
    }

    // ---- cover maps follow a move ----------------------------------------------------------------------

    @Test
    fun theCoverMapsFollowAMovedCover() {
        val url = "https://i.scdn.co/image/a300"
        val known = HashMap<String, DownloadRules.CoverSource>()
        val before = mapOf("spotify:track:1" to "/internal/offline/images/a.jpg", "spotify:track:2" to null)
        assertEquals(listOf("spotify:track:1", "spotify:track:2"), DownloadRules.refreshCoverSources(known, before, emptySet()).sorted())
        known["spotify:track:1"] = DownloadRules.CoverSource("spotify:track:1", "/internal/offline/images/a.jpg", listOf(url))
        known["spotify:track:2"] = DownloadRules.CoverSource("spotify:track:2", null, emptyList())

        // Moved to the card: the same URLs, the new file, nothing to read again.
        val after = mapOf("spotify:track:1" to "/card/offline/images/a.jpg", "spotify:track:2" to null)
        assertEquals(emptyList<String>(), DownloadRules.refreshCoverSources(known, after, emptySet()))
        val covers = OfflineCovers()
        covers.update(DownloadRules.offlineCoverMaps(known.values, emptyList()))
        assertEquals("/card/offline/images/a.jpg", covers.exact(url))
        // The session artwork follows too, and leaves out a card that went.
        assertEquals(mapOf("spotify:track:1" to "/card/offline/images/a.jpg"), DownloadRules.downloadedImagesOf(after, emptySet()))
        assertEquals(emptyMap<String, String>(), DownloadRules.downloadedImagesOf(after, setOf("spotify:track:1")))
        // Gone from the completed set (or on a card that went): dropped.
        DownloadRules.refreshCoverSources(known, after, setOf("spotify:track:1"))
        assertNull(known["spotify:track:1"])
    }

    // ---- a move that runs out of space -----------------------------------------------------------------

    @Test
    fun copiesMadeBeforeRunningOutOfSpaceAreSwitched() = runTest {
        val switched = ArrayList<String>()
        var done = 0
        val error = runCatching {
            copyThenSwitch(
                listOf("a", "gone", "b", "c", "d"),
                copy = { item ->
                    if (item == "gone") throw FileNotFoundException(item)
                    if (item == "c") throw NoSpaceException(File("/card"))
                },
                onDone = { done++ },
                switch = { switched += it },
            )
        }.exceptionOrNull()
        assertTrue(error is NoSpaceException)
        assertEquals("the verified copies are kept", listOf("a", "b"), switched)
        assertEquals(3, done)
    }

    @Test
    fun aCancelledMoveSwitchesItsCopiesAndAFailingSwitchDoesNotHideTheStop() = runTest {
        val switched = ArrayList<String>()
        val move = async {
            copyThenSwitch(
                listOf("a", "b"),
                copy = { item -> if (item == "b") awaitCancellation() },
                onDone = {},
                switch = { switched += it },
            )
        }
        runCurrent()
        move.cancel()
        assertTrue(runCatching { move.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("a"), switched)

        val stop = runCatching {
            copyThenSwitch(listOf("a"), copy = { throw IOException("card gone") }, onDone = {}, switch = { throw IllegalStateException("db") })
        }.exceptionOrNull()
        assertEquals("card gone", stop?.message)
        assertTrue(stop!!.suppressed.single() is IllegalStateException)
    }

    // ---- runs with a missing or full card ---------------------------------------------------------------

    @Test
    fun aMissingOrFullCardStopsTheRunBeforeASessionIsNeeded() {
        val internal = "/data/user/0/pkg/no_backup/offline"
        val card = "/storage/1234-ABCD/Android/data/pkg/files/offline"
        val low = DownloadRules.MIN_FREE_BYTES - 1
        var asked = false
        assertEquals(DownloadRules.StorageCheck.LOCATION_MISSING, DownloadRules.storageCheck(null, internal) { asked = true; 0L })
        assertFalse("nothing to measure without the card", asked)
        assertEquals(DownloadRules.StorageCheck.CARD_FULL, DownloadRules.storageCheck(card, internal) { low })
        assertEquals(DownloadRules.StorageCheck.INTERNAL_FULL, DownloadRules.storageCheck("/data/data/pkg/no_backup/offline", internal) { low })
        assertEquals(DownloadRules.StorageCheck.OK, DownloadRules.storageCheck(card, internal) { DownloadRules.MIN_FREE_BYTES })

        assertEquals(RunOutcome.STOPPED, DownloadRules.storageOutcome(DownloadRules.StorageCheck.LOCATION_MISSING))
        assertEquals(RunOutcome.STOPPED, DownloadRules.storageOutcome(DownloadRules.StorageCheck.CARD_FULL))
        // The hosts' storage-not-low constraint gates this retry.
        assertEquals(RunOutcome.RESCHEDULE, DownloadRules.storageOutcome(DownloadRules.StorageCheck.INTERNAL_FULL))
        assertNull(DownloadRules.storageOutcome(DownloadRules.StorageCheck.OK))
    }
}
