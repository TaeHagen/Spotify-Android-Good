package com.taehagen.spotifygood.download

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
 * Blocking. Throws [IOException] (no space, I/O error, the copy does not read back identical).
 */
internal fun copyVerified(src: File, dst: File, freeBytes: (File) -> Long, reserveBytes: Long) {
    if (!src.isFile) throw FileNotFoundException("${src.path} is gone")
    val size = src.length()
    val dir = dst.parentFile ?: throw IOException("No directory for ${dst.path}")
    if (dst.isFile && dst.length() == size && sha256(dst).contentEquals(sha256(src))) return
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
    if (freeBytes(dir) - size < reserveBytes) throw NoSpaceException(dir)
    val tmp = File(dst.path + TMP_SUFFIX)
    try {
        val digest = MessageDigest.getInstance(SHA_256)
        FileInputStream(src).use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
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
