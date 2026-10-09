package com.taehagen.spotifygood.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Copies [src] to [dst] for moving downloads between locations (docs §9.7): through `<dst>.tmp`,
 * synced, read back and compared (SHA-256) before it is renamed to [dst], so an interrupted or bad
 * copy never stands in for the file. A [dst] that is already identical (a move interrupted after
 * its copy) is kept. [freeBytes] of the target directory must leave [reserveBytes] after the copy.
 * Blocking. Throws [FileNotFoundException] (the original is gone), [SourceUnreadableException]
 * (the original cannot be read: a bad sector, a short read) or another [IOException] about the
 * target (no space, an I/O error, the copy does not read back identical).
 */
internal fun copyVerified(
    src: File,
    dst: File,
    freeBytes: (File) -> Long,
    reserveBytes: Long,
    open: (File) -> InputStream = ::FileInputStream,
) {
    if (!src.isFile) throw FileNotFoundException("${src.path} is gone")
    val size = src.length()
    val dir = dst.parentFile ?: throw IOException("No directory for ${dst.path}")
    if (dst.isFile && dst.length() == size && sha256(dst).contentEquals(readingSource(src) { open(src).use { sha256(it) } })) return
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
    if (freeBytes(dir) - size < reserveBytes) throw NoSpaceException(dir)
    val tmp = File(dst.path + TMP_SUFFIX)
    try {
        val digest = MessageDigest.getInstance(SHA_256)
        open(src).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var read = 0L
                while (true) {
                    val n = readingSource(src) { input.read(buffer) }
                    if (n < 0) break
                    read += n
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
                if (read != size) throw SourceUnreadableException(src, IOException("Read $read of $size bytes"))
                output.fd.sync()
            }
        }
        if (tmp.length() != size || !sha256(tmp).contentEquals(digest.digest())) throw IOException("The copy of ${src.name} does not read back identical")
        if (!tmp.renameTo(dst)) throw IOException("Could not rename ${tmp.path}")
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
}

/**
 * Copies [batch] with [copy], one item at a time, and always hands the items copied so far to
 * [switch]: also when a copy stops the batch (no space, an error about the target, the card gone)
 * or the pass is cancelled, so a verified copy is never thrown away and redone at the next pass.
 * An item whose original is gone ([FileNotFoundException]) is skipped. An item whose original
 * cannot be read ([SourceUnreadableException]: a damaged file) is always skipped and handed to
 * [skip] (downloaded again), and the batch goes on, however many damaged files lie next to each
 * other (an album in one bad region of the card); it stops only when [cardFailing] says the card
 * itself is failing ([SourceHealth]), with a [CardFailingException]. [onFailed] sees every item
 * that was skipped as unreadable or stopped the batch (not a cancellation). [onDone] runs after
 * each item. What stopped the batch is rethrown after the switch; a failing switch does not hide it.
 */
internal suspend fun <T> copyThenSwitch(
    batch: List<T>,
    copy: suspend (T) -> Unit,
    onDone: () -> Unit,
    switch: suspend (List<T>) -> Unit,
    skip: suspend (List<T>) -> Unit = {},
    onFailed: (T) -> Unit = {},
    cardFailing: () -> Boolean = { false },
) {
    val copied = ArrayList<T>(batch.size)
    val skipped = ArrayList<T>()
    var stop: Throwable? = null
    try {
        for (item in batch) {
            currentCoroutineContext().ensureActive()
            try {
                copy(item)
                copied += item
            } catch (e: FileNotFoundException) {
                // Removed meanwhile: nothing to move.
            } catch (e: SourceUnreadableException) {
                onFailed(item)
                skipped += item
                if (cardFailing()) throw CardFailingException(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CardFailingException) {
                throw e
            } catch (e: Throwable) {
                onFailed(item)
                throw e
            }
            onDone()
        }
    } catch (e: Throwable) {
        stop = e
    }
    try {
        withContext(NonCancellable) {
            switch(copied)
            skip(skipped)
        }
    } catch (e: Exception) {
        if (stop == null) throw e
        stop.addSuppressed(e)
    }
    stop?.let { throw it }
}

/**
 * Whether the card the originals are on is failing as a whole, judged from what a move met (the
 * card being gone or its folder unreadable is checked directly): at least [sample] originals tried
 * and at least [ratio] of them unreadable. A cluster of damaged files among readable ones (an album
 * in a bad region) never counts as that. Used by one move pass at a time.
 */
internal class SourceHealth(private val sample: Int = CARD_FAILING_SAMPLE, private val ratio: Double = CARD_FAILING_RATIO) {
    private var tried = 0
    private var unreadable = 0

    fun copied() {
        tried++
    }

    fun unreadable() {
        tried++
        unreadable++
    }

    val failing: Boolean get() = tried >= sample && unreadable >= tried * ratio
}

/** The card itself is failing ([SourceHealth]): the move stops and resumes later. */
internal class CardFailingException(cause: IOException) : IOException("The card is failing", cause)

/** The originals' card went (unmounted, removed) or its folder cannot be read: the move waits for it. */
internal class CardGoneException(message: String, cause: IOException? = null) : IOException(message, cause)

/** Originals a move must try before it may judge the card as failing ([SourceHealth]). */
internal const val CARD_FAILING_SAMPLE = 20

/** Share of unreadable originals from which a move judges the card as failing ([SourceHealth]). */
internal const val CARD_FAILING_RATIO = 0.8

/** The original of a move cannot be read (a bad sector, a short read): a damaged download. */
internal class SourceUnreadableException(val src: File, cause: IOException) : IOException("Could not read ${src.path}", cause)

/** [block] reading [src]: its I/O errors (other than the file being gone) are [SourceUnreadableException]s. */
private inline fun <T> readingSource(src: File, block: () -> T): T =
    try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: SourceUnreadableException) {
        throw e
    } catch (e: IOException) {
        throw SourceUnreadableException(src, e)
    }



/** Not enough space at [dir] for a copy. */
internal class NoSpaceException(val dir: File) : IOException("Not enough space in ${dir.path}")

private fun sha256(file: File): ByteArray = FileInputStream(file).use { sha256(it) }

private fun sha256(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance(SHA_256)
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest()
}

/** Suffix of a copy in progress (garbage collection removes leftovers of an interrupted one). */
internal const val TMP_SUFFIX = ".tmp"
private const val SHA_256 = "SHA-256"
private const val BUFFER_BYTES = 256 * 1024
