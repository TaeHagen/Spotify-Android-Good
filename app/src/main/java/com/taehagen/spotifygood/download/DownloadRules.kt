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

    /** First queue pause after a rate limit without a server delay (doubles per consecutive limit). */
    const val RATE_LIMIT_PAUSE_MS = 60_000L

    /** Consecutive connectivity failures while online after which the whole queue pauses. */
    const val CONNECTIVITY_TRIP = 3

    /** First queue pause after [CONNECTIVITY_TRIP] connectivity failures (×4 per further trip). */
    const val CONNECTIVITY_PAUSE_MS = 60_000L

    /** Longest queue pause. */
    const val MAX_QUEUE_PAUSE_MS = 30 * 60_000L

    /** Stop downloading when less than this is free on the data partition. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    /** Completed downloads are re-validated against the catalog after this long. */
    const val REVALIDATE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    /** A downloaded collection is synced again (when the session comes online) after this long. */
    const val SYNC_STALE_MS = 12L * 60 * 60 * 1000

    /** Retry of a collection whose sync failed or was incomplete: 1 h, doubling per failure … */
    const val SYNC_RETRY_BASE_MS = 60L * 60 * 1000

    /** … up to once a day. */
    const val SYNC_RETRY_MAX_MS = 24L * 60 * 60 * 1000

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

        /**
         * The service is rate limiting: back to the queue without counting an attempt; the whole
         * queue pauses ([QueueBreaker]) rather than every item trying again.
         */
        data object Throttled : FailureAction
    }

    /** Decides what to do with an item that failed with [code] after [previousAttempts] earlier failures. */
    fun onFailure(code: String, previousAttempts: Int, online: Boolean, retryAfterMs: Long? = null): FailureAction {
        val attempts = previousAttempts + 1
        return when {
            code in STOP_RUN -> FailureAction.StopRun
            code == NativeErrorCode.RATE_LIMITED -> FailureAction.Throttled
            code in CONNECTIVITY && !online -> FailureAction.WaitForNetwork
            code in PERMANENT -> FailureAction.Fail(attempts)
            attempts >= MAX_ATTEMPTS -> FailureAction.Fail(attempts)
            else -> FailureAction.Retry(attempts, backoffMs(attempts, retryAfterMs))
        }
    }

    /**
     * Queue pause after the [consecutive]-th rate limit in a row (1-based): the server's
     * [retryAfterMs] when it gave one (at least [BASE_BACKOFF_MS]), else [RATE_LIMIT_PAUSE_MS]
     * doubling per consecutive limit; at most [MAX_QUEUE_PAUSE_MS].
     */
    fun rateLimitPauseMs(retryAfterMs: Long?, consecutive: Int): Long {
        val pause = retryAfterMs?.coerceAtLeast(BASE_BACKOFF_MS) ?: grow(RATE_LIMIT_PAUSE_MS, 2, consecutive)
        return pause.coerceAtMost(MAX_QUEUE_PAUSE_MS)
    }

    /** Queue pause after the [trips]-th connectivity trip in a row (1-based): 1, 4, 16 min …, ≤ 30 min. */
    fun connectivityPauseMs(trips: Int): Long = grow(CONNECTIVITY_PAUSE_MS, 4, trips).coerceAtMost(MAX_QUEUE_PAUSE_MS)

    private fun grow(base: Long, factor: Long, n: Int): Long {
        var value = base
        repeat((n - 1).coerceIn(0, 10)) { value = (value * factor).coerceAtMost(MAX_QUEUE_PAUSE_MS) }
        return value
    }

    /** Codes that say something about connectivity (for [QueueBreaker]). */
    fun isConnectivity(code: String) = code in CONNECTIVITY

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

    /**
     * Whether a listing of [listed] items is the whole collection: not empty (a failed lookup and an
     * emptied collection look the same), nothing failed ([partial]: some item metadata failed and
     * those items are placeholders, or a page came back short) and, for sources that list every slot,
     * all [total] slots.
     */
    fun listingComplete(listed: Int, partial: Boolean, total: Int? = null): Boolean =
        listed > 0 && !partial && (total == null || listed >= total)

    /** New membership of a collection: its [items] and what joined / left it. */
    data class MembershipUpdate(val items: List<String>, val added: List<String>, val dropped: List<String>)

    /**
     * Membership after a resolution listing [resolved]. Only a [complete] resolution replaces [old]
     * (and so may drop items, which deletes their downloads); an incomplete one (failed or short
     * lookup) only adds: items it does not list are unknown and kept.
     */
    fun updateMembership(old: List<String>, resolved: List<String>, complete: Boolean): MembershipUpdate {
        val items = if (complete) resolved.distinct() else (resolved + old).distinct()
        val diff = diff(old, items)
        return MembershipUpdate(items, diff.added, diff.dropped)
    }

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
     * download state of every known item. Items without a row count towards the total only; items
     * not playable here ([unavailable]) do not count unless they were downloaded.
     */
    fun collectionStatus(
        items: List<String>?,
        states: Map<String, DownloadState>,
        unavailable: Set<String> = emptySet(),
    ): CollectionDownloadStatus {
        if (items == null) return CollectionDownloadStatus.None
        val distinct = items.distinct().filter { it !in unavailable || states[it] == DownloadState.COMPLETED }
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

    /** Members not playable here after a resolution, and those that became playable again. */
    data class Availability(val unavailable: Set<String>, val revived: Set<String>)

    /**
     * Updates the [old] set of unavailable members with a resolution listing [listed] items, of which
     * [listedUnavailable] are not playable here. A [complete] resolution decides for every member; an
     * incomplete one only for the members it lists. [Availability.revived] members were unavailable
     * and are playable now: they are queued like new members.
     */
    fun updateAvailability(old: Set<String>, listed: Set<String>, listedUnavailable: Set<String>, complete: Boolean): Availability {
        val unavailable = if (complete) listedUnavailable else (old - listed) + listedUnavailable
        return Availability(unavailable, old.filterTo(HashSet()) { it in listed && it !in listedUnavailable })
    }

    /**
     * Earliest time a downloaded collection is synced again when the session comes online:
     * [SYNC_STALE_MS] after its last complete sync, and after [failures] consecutive failed or
     * incomplete attempts no sooner than [SYNC_RETRY_BASE_MS] (doubling, ≤ [SYNC_RETRY_MAX_MS]) after
     * the last one, so a collection that keeps failing (deleted playlist) is not re-fetched at every
     * reconnect.
     */
    fun nextSyncAt(lastSyncedAt: Long?, lastAttemptAt: Long?, failures: Int): Long {
        val afterSync = (lastSyncedAt ?: 0L) + SYNC_STALE_MS
        if (failures <= 0 || lastAttemptAt == null) return afterSync
        var backoff = SYNC_RETRY_BASE_MS
        repeat((failures - 1).coerceIn(0, 10)) { backoff = (backoff * 2).coerceAtMost(SYNC_RETRY_MAX_MS) }
        return maxOf(afterSync, lastAttemptAt + backoff)
    }

    // ---- scheduling ----------------------------------------------------------------------------------

    /**
     * Whether a pending user-initiated job is left to drain the queue: unless the network policy
     * changed ([replace]), or it is not executing and either has the wrong network constraint
     * ([stale]) or a user action wants it to start now instead of after its backoff ([kick]).
     */
    fun keepPendingJob(replace: Boolean, kick: Boolean, executing: Boolean, stale: Boolean): Boolean =
        !replace && (executing || (!stale && !kick))

    /**
     * Whether unique download work is re-created rather than kept: for a policy change ([replace]);
     * otherwise only work that is still enqueued (never running work), when its network constraint
     * does not match the setting ([stale]) or a user action finds it waiting out a retry backoff
     * ([kick] after [runAttempts] > 0).
     */
    fun replaceWork(replace: Boolean, kick: Boolean, enqueued: Boolean, stale: Boolean, runAttempts: Int): Boolean =
        replace || (enqueued && (stale || (kick && runAttempts > 0)))

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

/**
 * Run-wide view of failures (docs/ARCHITECTURE.md §9.7), so that a condition of the whole service
 * pauses the whole queue instead of every item spending its attempts on it:
 * * a rate limit pauses at once ([DownloadRules.rateLimitPauseMs]);
 * * connectivity failures while the session is online (CDN unreachable while the AP works) pause
 *   after [DownloadRules.CONNECTIVITY_TRIP] consecutive items ([DownloadRules.connectivityPauseMs]);
 *   each failure still counts as an attempt of its item.
 *
 * A completed download resets it; another kind of failure resets the connectivity count (the service
 * answered). Pauses grow while the condition persists. Not thread-safe: used by one run at a time.
 */
internal class QueueBreaker {
    private var rateLimits = 0
    private var connectivityFailures = 0
    private var connectivityTrips = 0

    fun onSuccess() {
        rateLimits = 0
        connectivityFailures = 0
        connectivityTrips = 0
    }

    /** How long to pause the whole queue after an item failed with [code]; null = go on. */
    fun onFailure(code: String, online: Boolean, retryAfterMs: Long?): Long? {
        if (code == NativeErrorCode.RATE_LIMITED) {
            connectivityFailures = 0
            return DownloadRules.rateLimitPauseMs(retryAfterMs, ++rateLimits)
        }
        if (!DownloadRules.isConnectivity(code)) {
            connectivityFailures = 0
            return null
        }
        if (!online) return null // the device is offline: the run waits for the network instead
        if (++connectivityFailures < DownloadRules.CONNECTIVITY_TRIP) return null
        connectivityFailures = 0
        return DownloadRules.connectivityPauseMs(++connectivityTrips)
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
