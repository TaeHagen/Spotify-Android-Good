package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import java.io.File

/**
 * Pure download policy (no Android, no I/O) so it can be unit tested: shared membership, removal,
 * shared files, collection status, retry/backoff and sizing rules.
 */
internal object DownloadRules {
    /** An item is marked FAILED after this many failed attempts. */
    const val MAX_ATTEMPTS = 3
    const val BASE_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /** Stop downloading when less than this is free on the data partition. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /** Completed downloads are re-validated against the catalog after this long. */
    const val REVALIDATE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    private val PENDING = setOf(DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING)

    /** Codes after which no further download can succeed in this run (account-wide conditions). */
    private val STOP_RUN = setOf(
        NativeErrorCode.PREMIUM_REQUIRED,
        NativeErrorCode.PLAYBACK_REFUSED,
        NativeErrorCode.BAD_CREDENTIALS,
        NativeErrorCode.NOT_LOGGED_IN,
    )

    /** Codes that will not change by retrying the same item. */
    private val PERMANENT = setOf(NativeErrorCode.NOT_FOUND, NativeErrorCode.UNAVAILABLE, NativeErrorCode.INVALID_ARGUMENT)

    /** Codes caused by connectivity (or the session going away underneath the call). */
    private val CONNECTIVITY = setOf(NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED, NativeErrorCode.CANCELLED)

    fun isPending(state: DownloadState?) = state in PENDING

    /**
     * Exponential backoff before the next attempt after [failedAttempts] failures (1-based):
     * 5 s, 20 s, 80 s … capped at [MAX_BACKOFF_MS]; a longer server `retryAfterMs` wins.
     */
    fun backoffMs(failedAttempts: Int, retryAfterMs: Long? = null): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 10)
        var delay = BASE_BACKOFF_MS
        repeat(exponent) { delay = (delay * 4).coerceAtMost(MAX_BACKOFF_MS) }
        return maxOf(delay, retryAfterMs ?: 0L).coerceAtMost(MAX_BACKOFF_MS)
    }

    sealed interface FailureAction {
        /** Account-wide failure: fail what is pending and stop the run. */
        data object StopRun : FailureAction

        /** The device went offline: requeue without counting an attempt and wait for the network. */
        data object WaitForNetwork : FailureAction

        data class Retry(val attempts: Int, val delayMs: Long) : FailureAction

        data class Fail(val attempts: Int) : FailureAction
    }

    /** Decides what to do with an item that failed with [code] after [previousAttempts] earlier failures. */
    fun onFailure(code: String, previousAttempts: Int, online: Boolean, retryAfterMs: Long? = null): FailureAction {
        val attempts = previousAttempts + 1
        return when {
            code in STOP_RUN -> FailureAction.StopRun
            code in CONNECTIVITY && !online -> FailureAction.WaitForNetwork
            code in PERMANENT -> FailureAction.Fail(attempts)
            attempts >= MAX_ATTEMPTS -> FailureAction.Fail(attempts)
            else -> FailureAction.Retry(attempts, backoffMs(attempts, retryAfterMs))
        }
    }

    /**
     * Which of [candidates] may be deleted: those not part of any of [keptCollections] (item lists of
     * the downloaded collections that stay) and not downloaded on their own ([individual]).
     */
    fun itemsToDelete(
        candidates: Collection<String>,
        keptCollections: Collection<Collection<String>>,
        individual: Set<String>,
    ): List<String> {
        val referenced = HashSet<String>()
        keptCollections.forEach { referenced.addAll(it) }
        return candidates.distinct().filter { it !in referenced && it !in individual }
    }

    data class MembershipDiff(val added: List<String>, val dropped: List<String>)

    /** Items that joined / left a collection between two resolutions (order of [new] / [old] kept). */
    fun diff(old: List<String>, new: List<String>): MembershipDiff {
        val oldSet = old.toHashSet()
        val newSet = new.toHashSet()
        return MembershipDiff(
            added = new.distinct().filter { it !in oldSet },
            dropped = old.distinct().filter { it !in newSet },
        )
    }

    /**
     * Status of a downloaded collection whose items are [items] (null = not downloaded) given the
     * download state of every known item. Items without a row count towards the total only.
     */
    fun collectionStatus(items: List<String>?, states: Map<String, DownloadState>): CollectionDownloadStatus {
        if (items == null) return CollectionDownloadStatus.None
        val distinct = items.distinct()
        if (distinct.isEmpty()) return CollectionDownloadStatus.Complete
        var done = 0
        var active = false
        distinct.forEach { uri ->
            when (val state = states[uri]) {
                DownloadState.COMPLETED -> done++
                else -> if (isPending(state)) active = true
            }
        }
        return if (done == distinct.size) {
            CollectionDownloadStatus.Complete
        } else {
            CollectionDownloadStatus.InProgress(done, distinct.size, active)
        }
    }

    /** Rough size of [count] items at [kbps] (≈ 4 min each) for the job's network estimate. */
    fun estimateBytes(count: Int, kbps: Int): Long = count.toLong() * kbps * 1000 / 8 * AVERAGE_DURATION_S

    private const val AVERAGE_DURATION_S = 240L

    // ---- files ---------------------------------------------------------------------------------------
    //
    // Audio lives in `<audioDir>/<fileId>` (+ `.part` while unfinished). Several rows can use one file
    // (relinking, the same recording in two releases): the native downloader reuses a verified file,
    // so a file belongs to every row whose path or fileId names it. Matching is by lower-case file
    // name, so path aliases (`/data/user/0` vs `/data/data`) do not matter.

    private const val PART_SUFFIX = ".part"

    private fun fileName(path: String) = File(path).name.lowercase()

    /**
     * Of the completed files of removed rows ([removedPaths]), those no remaining row uses: none has
     * them as its path ([remainingPaths]) or file ([remainingFileIds], also rows still downloading it).
     */
    fun audioToDelete(
        removedPaths: Collection<String?>,
        remainingPaths: Collection<String>,
        remainingFileIds: Collection<String>,
    ): List<String> {
        val used = HashSet<String>()
        remainingPaths.mapTo(used, ::fileName)
        remainingFileIds.mapTo(used) { it.lowercase() }
        return removedPaths.filterNotNull().filter { it.isNotEmpty() && fileName(it) !in used }.distinct()
    }

    /**
     * Names in the audio directory garbage collection deletes: completed files no row uses ([paths],
     * [fileIds]) and `.part` files no unfinished row is downloading ([unfinishedFileIds], any state:
     * failed and cancelled rows resume them when retried).
     */
    fun audioGarbage(
        names: Collection<String>,
        paths: Collection<String>,
        fileIds: Collection<String>,
        unfinishedFileIds: Collection<String>,
    ): List<String> {
        val files = HashSet<String>()
        paths.mapTo(files, ::fileName)
        fileIds.mapTo(files) { it.lowercase() }
        val parts = unfinishedFileIds.mapTo(HashSet()) { it.lowercase() }
        return names.filter { name ->
            val lower = name.lowercase()
            if (lower.endsWith(PART_SUFFIX)) lower.removeSuffix(PART_SUFFIX) !in parts else lower !in files
        }
    }
}

/** Lower-case hex codec for audio keys. */
internal object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
