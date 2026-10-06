package com.taehagen.spotifygood.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFilesTest {
    private val dir = "/data/user/0/com.taehagen.spotifygood/no_backup/offline/audio"
    private val shared = "aa".repeat(20)
    private val other = "bb".repeat(20)

    // ---- removal (shared files) -------------------------------------------------------------------------

    @Test
    fun removingOneOfTwoDownloadsOfTheSameFileKeepsIt() {
        // X (relinked to A) and A both completed into the same file; X is removed.
        val deleted = DownloadRules.audioToDelete(
            removedPaths = listOf("$dir/$shared"),
            remainingPaths = listOf("$dir/$shared"),
            remainingFileIds = listOf(shared),
        )
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun removingTheLastDownloadOfAFileDeletesIt() {
        val deleted = DownloadRules.audioToDelete(
            removedPaths = listOf("$dir/$shared", "$dir/$shared", null, "$dir/$other"),
            remainingPaths = listOf("$dir/$other"),
            remainingFileIds = listOf(other),
        )
        assertEquals(listOf("$dir/$shared"), deleted)
    }

    @Test
    fun aFileAnUnfinishedDownloadNamesIsKeptWhateverThePathSpelling() {
        // The remaining row has not completed yet (no path) but records the file it downloads; the
        // removed row's path uses the /data/data alias and upper case.
        val deleted = DownloadRules.audioToDelete(
            removedPaths = listOf("/data/data/com.taehagen.spotifygood/no_backup/offline/audio/${shared.uppercase()}"),
            remainingPaths = emptyList(),
            remainingFileIds = listOf(shared),
        )
        assertEquals(emptyList<String>(), deleted)
    }

    // ---- garbage collection --------------------------------------------------------------------------------

    @Test
    fun partialFilesOfFailedOrCancelledDownloadsSurviveGarbageCollection() {
        val failed = "cc".repeat(20)
        val names = listOf("$failed.part", "$other.part", shared, other)
        val garbage = DownloadRules.audioGarbage(
            names,
            paths = listOf("$dir/$shared"),
            fileIds = listOf(shared, failed),
            unfinishedFileIds = listOf(failed),
        )
        // The failed row resumes its .part; nothing names `other` any more.
        assertEquals(listOf("$other.part", other), garbage)
    }

    @Test
    fun aStalePartNextToACompletedFileIsCollected() {
        val garbage = DownloadRules.audioGarbage(
            names = listOf("$shared.part", shared),
            paths = listOf("$dir/$shared"),
            fileIds = listOf(shared),
            unfinishedFileIds = emptyList(),
        )
        assertEquals(listOf("$shared.part"), garbage)
    }

    @Test
    fun aCompletedFileAFailedRowStillNamesIsKept() {
        // download.track finished the file but the commit failed: the retry reuses it.
        val garbage = DownloadRules.audioGarbage(
            names = listOf(shared.uppercase()),
            paths = emptyList(),
            fileIds = listOf(shared),
            unfinishedFileIds = listOf(shared),
        )
        assertEquals(emptyList<String>(), garbage)
    }
}
