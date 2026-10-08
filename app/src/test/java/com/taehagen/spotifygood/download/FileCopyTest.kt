package com.taehagen.spotifygood.download

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.random.Random

/** Moving a download between locations: copied, verified, never a half copy in its place. */
class FileCopyTest {
    private val dir: File = Files.createTempDirectory("file-copy").toFile()
    private val plenty: (File) -> Long = { Long.MAX_VALUE }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun source(bytes: Int = 600_000): File = File(dir, "internal/audio/${"aa".repeat(20)}").apply {
        parentFile!!.mkdirs()
        writeBytes(Random(7).nextBytes(bytes))
    }

    @Test
    fun aCopyIsIdenticalAndLeavesNoTemporaryFile() {
        val src = source()
        val dst = File(dir, "card/audio/${src.name}")
        copyVerified(src, dst, plenty, reserveBytes = 0)
        assertArrayEquals(src.readBytes(), dst.readBytes())
        assertFalse(File(dst.path + TMP_SUFFIX).exists())
        assertTrue("the original stays until the rows point at the copy", src.isFile)
    }

    @Test
    fun anIdenticalCopyFromAnInterruptedMoveIsKept() {
        val src = source()
        val dst = File(dir, "card/audio/${src.name}").apply {
            parentFile!!.mkdirs()
            writeBytes(src.readBytes())
        }
        val modified = dst.lastModified()
        copyVerified(src, dst, { 0L }, reserveBytes = 1) // no space needed: nothing is written
        assertEquals(modified, dst.lastModified())
    }

    @Test
    fun aDifferentFileAtTheTargetIsReplaced() {
        val src = source()
        val dst = File(dir, "card/audio/${src.name}").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        copyVerified(src, dst, plenty, reserveBytes = 0)
        assertArrayEquals(src.readBytes(), dst.readBytes())
    }

    @Test
    fun notEnoughSpaceWritesNothing() {
        val src = source()
        val dst = File(dir, "card/audio/${src.name}")
        assertThrows(NoSpaceException::class.java) { copyVerified(src, dst, { 1_000L }, reserveBytes = 0) }
        assertFalse(dst.exists())
        assertFalse(File(dst.path + TMP_SUFFIX).exists())
    }

    @Test
    fun aRemovedOriginalIsReported() {
        val dst = File(dir, "card/audio/x")
        assertThrows(FileNotFoundException::class.java) { copyVerified(File(dir, "gone"), dst, plenty, reserveBytes = 0) }
    }
}
