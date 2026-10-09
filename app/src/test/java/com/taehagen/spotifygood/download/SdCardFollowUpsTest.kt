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

    // ---- a damaged file on the old card ------------------------------------------------------------------

    private fun unreadable(name: String) = SourceUnreadableException(File(name), IOException("EIO"))

    @Test
    fun aDamagedOriginalIsSkippedAndTheMoveGoesOn() = runTest {
        val switched = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        copyThenSwitch(
            listOf("a", "bad", "c", "d"),
            copy = { item -> if (item == "bad") throw unreadable(item) },
            onDone = {},
            switch = { switched += it },
            skip = { skipped += it },
            onFailed = { failed += it },
        )
        assertEquals("the files after it are still moved", listOf("a", "c", "d"), switched)
        assertEquals("downloaded again", listOf("bad"), skipped)
        assertEquals(listOf("bad"), failed)
    }

    @Test
    fun anErrorAboutTheTargetStillStopsTheBatch() = runTest {
        val switched = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()
        val stop = runCatching {
            copyThenSwitch(
                listOf("a", "b", "c"),
                copy = { item -> if (item == "b") throw IOException("target write failed") },
                onDone = {},
                switch = { switched += it },
                skip = { skipped += it },
                onFailed = { failed += it },
            )
        }.exceptionOrNull()
        assertEquals("target write failed", stop?.message)
        assertEquals(listOf("a"), switched)
        assertEquals(emptyList<String>(), skipped)
        assertEquals("moved last at the next pass", listOf("b"), failed)
    }

    /** One move pass over [plan] as relocatePass runs it: the card is judged by [SourceHealth]. */
    private suspend fun pass(plan: List<String>, damaged: (String) -> Boolean): Triple<List<String>, List<String>, Throwable?> {
        val switched = ArrayList<String>()
        val skipped = ArrayList<String>()
        val health = SourceHealth()
        val stop = runCatching {
            for (batch in plan.chunked(50)) {
                copyThenSwitch(
                    batch,
                    copy = { item ->
                        if (damaged(item)) {
                            health.unreadable()
                            throw unreadable(item)
                        }
                        health.copied()
                    },
                    onDone = {},
                    switch = { switched += it },
                    skip = { skipped += it },
                    cardFailing = { health.failing },
                )
            }
        }.exceptionOrNull()
        return Triple(switched, skipped, stop)
    }

    @Test
    fun adjacentDamagedFilesAreAllDownloadedAgainAndTheMoveGoesOn() = runTest {
        // An album in one bad region of the card: three damaged files in a row.
        val plan = listOf("a", "x1", "x2", "x3", "b", "c")
        val (switched, skipped, stop) = pass(plan) { it.startsWith("x") }
        assertNull(stop)
        assertEquals(listOf("a", "b", "c"), switched)
        assertEquals(listOf("x1", "x2", "x3"), skipped)

        // A fresh process (nothing failed before) with the same files still moves b and c.
        val fresh = DownloadRules.orderPlan(plan.map { DownloadRules.FileMove(it, "/internal/$it", image = false) }, emptySet()).map { it.from }
        val (again, againSkipped, againStop) = pass(fresh) { it.startsWith("x") }
        assertNull(againStop)
        assertEquals(listOf("a", "b", "c"), again)
        assertEquals(listOf("x1", "x2", "x3"), againSkipped)
    }

    @Test
    fun aCardThatCannotBeReadAtAllStopsTheMove() = runTest {
        // Everything after x1 is unreadable: the card itself is failing.
        val plan = listOf("a") + (1..40).map { "x$it" }
        val (switched, skipped, stop) = pass(plan) { it.startsWith("x") }
        assertTrue(stop is CardFailingException)
        assertEquals(listOf("a"), switched)
        // What was found unreadable is still downloaded again; the rest waits for a later pass.
        assertEquals((1..19).map { "x$it" }, skipped)
    }

    @Test
    fun aFewDamagedFilesNeverMakeTheCardFailing() {
        val health = SourceHealth(sample = 20, ratio = 0.8)
        repeat(5) { health.unreadable() }
        assertFalse("too few tried to judge the card", health.failing)
        repeat(30) { health.copied() }
        repeat(5) { health.unreadable() }
        assertFalse(health.failing)
        val bad = SourceHealth(sample = 20, ratio = 0.8)
        repeat(3) { bad.copied() }
        repeat(17) { bad.unreadable() }
        assertTrue(bad.failing)
    }

    @Test
    fun readErrorsOfTheOriginalAreToldApartFromTargetErrors() {
        val src = File(base, "card/audio/${"cd".repeat(20)}").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(100_000) { it.toByte() })
        }
        val dst = File(base, "internal/audio/${src.name}")
        val failing: (File) -> java.io.InputStream = { file ->
            object : java.io.FilterInputStream(java.io.FileInputStream(file)) {
                private var reads = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (++reads > 1) throw IOException("EIO")
                    return super.read(b, off, minOf(len, 1_000))
                }
            }
        }
        val bad = runCatching { copyVerified(src, dst, { Long.MAX_VALUE }, 0, failing) }.exceptionOrNull()
        assertTrue(bad is SourceUnreadableException)
        assertFalse(dst.exists())
        assertFalse(File(dst.path + TMP_SUFFIX).exists())

        // A short read (the original ends early) is the original's fault too.
        val short: (File) -> java.io.InputStream = { file -> java.io.ByteArrayInputStream(file.readBytes().copyOf(10)) }
        assertTrue(runCatching { copyVerified(src, dst, { Long.MAX_VALUE }, 0, short) }.exceptionOrNull() is SourceUnreadableException)

        // The target: its folder cannot be made.
        File(base, "blocked").writeText("a file, not a folder")
        val targetError = runCatching { copyVerified(src, File(base, "blocked/audio/${src.name}"), { Long.MAX_VALUE }, 0) }.exceptionOrNull()
        assertTrue(targetError is IOException && targetError !is SourceUnreadableException)
    }

    @Test
    fun filesThatFailedBeforeAreMovedLast() {
        val plan = listOf("a", "b", "c", "d").map { DownloadRules.FileMove("/card/audio/$it", "/internal/audio/$it", image = false) }
        val ordered = DownloadRules.orderPlan(plan, setOf("/card/audio/b"))
        assertEquals(listOf("a", "c", "d", "b"), ordered.map { File(it.from).name })
        assertEquals(plan, DownloadRules.orderPlan(plan, emptySet()))
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

    // ---- the engine-start index snapshot against a running move -------------------------------------------

    @Test
    fun aSnapshotNeverFailsADownloadAMoveSwitchedToItsCopy() {
        val internal = "/data/user/0/pkg/no_backup/offline/audio"
        val card = "/storage/1234-ABCD/Android/data/pkg/files/offline/audio"
        // What the snapshot read and checked (outside the lock): every file looked gone, because the
        // move deleted the originals right after switching the rows to the copies.
        val checked = listOf("a", "b", "c", "d", "e").mapIndexed { i, name ->
            DownloadRules.CheckedFile("t-$name", "$internal/$name", "$internal/$name", i + 1L)
        }
        val onDisk = mutableSetOf("$card/a", "$internal/c")
        // The rows under the lock, after the move's switch.
        val current = mapOf(
            "t-a" to ("$card/a" to 1L), // switched to its copy on the card
            "t-b" to ("$internal/b" to 2L), // unchanged, and really gone
            "t-c" to ("$internal/c" to 3L), // its file is back
            "t-e" to ("$internal/e" to 99L), // removed and downloaded again since
        ) // t-d: removed (no longer a completed row)
        val failed = DownloadRules.stillMissing(checked, current, exists = { it in onDisk }, relocating = false)
        assertEquals(listOf("t-b"), failed.map { it.uri })
        assertEquals("$internal/b", failed.single().rowPath)

        // While a move runs nothing is failed: the rows may be switched under it at any time.
        assertTrue(DownloadRules.stillMissing(checked, current, exists = { false }, relocating = true).isEmpty())
        // A row whose path is null is still matched as null (the record's path was checked).
        val legacy = DownloadRules.CheckedFile("t-l", null, "$internal/l", 7L)
        assertEquals(listOf(legacy), DownloadRules.stillMissing(listOf(legacy), mapOf("t-l" to (null to 7L)), { false }, false))
        assertTrue(DownloadRules.stillMissing(listOf(legacy), mapOf("t-l" to ("$card/l" to 7L)), { false }, false).isEmpty())
    }
}
