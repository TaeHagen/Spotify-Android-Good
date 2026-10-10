package com.taehagen.spotifygood.ui.screens.playlist

import com.taehagen.spotifygood.data.monotonicMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A burst of pushes (one per song added elsewhere) refreshes an open playlist once, after this. */
internal const val PLAYLIST_PUSH_SETTLE_MS = 1_000L

/**
 * The page start's revision check is skipped this soon after the server last told the revision
 * shown (a rotation, a quick look at another page): pushes cover that time.
 */
internal const val PLAYLIST_START_CHECK_INTERVAL_MS = 30_000L

/**
 * Keeps an open playlist page current with changes made elsewhere (docs/ARCHITECTURE.md §9.9),
 * only while the page is on screen ([onStart] .. [onStop]) and the session is [online]:
 * * A push names the playlist ([onPushed]) at a revision other than the one shown: the rows are
 *   refreshed in place ([refresh]), [settleMs] after the first push of a burst. Pushed while the
 *   page is off screen, it refreshes when the page is back.
 * * Pushes are lost while the app is in the background and while the session reconnects, so the
 *   revision is checked ([fetchRevision], one small request) when the page starts (not within
 *   [checkIntervalMs] of the server telling it), when it shows a cached copy while on screen, and
 *   each time the session comes online ([onOnline]); the rows refresh only if it changed.
 *
 * Nothing runs on a timer or while the page is off screen; a failed check or refresh isn't retried
 * by itself (the next push, start or reconnect does). [shown] is the revision on screen, or null
 * while the page shows nothing that can be refreshed in place (loading, failed, the download, edits
 * pending). The page reports what it shows with [onPage]. Main thread only.
 */
internal class PlaylistFreshness(
    private val scope: CoroutineScope,
    private val shown: () -> String?,
    private val online: () -> Boolean,
    private val fetchRevision: suspend () -> String?,
    /** Reloads the rows in place; false when that failed (tried again on the next start). */
    private val refresh: suspend () -> Boolean,
    private val clock: () -> Long = ::monotonicMs,
    private val settleMs: Long = PLAYLIST_PUSH_SETTLE_MS,
    private val checkIntervalMs: Long = PLAYLIST_START_CHECK_INTERVAL_MS,
) {
    private enum class Work { CHECK, REFRESH }

    private var started = false
    /** A change was pushed that the page doesn't show yet ([pushedRevision]: its revision, if told). */
    private var pushed = false
    private var pushedRevision: String? = null
    /** When the server last told the revision shown; null: check at the next start. */
    private var confirmedAt: Long? = null
    private var job: Job? = null
    private var jobWork: Work? = null
    /** The scheduled work is past its settle delay (a push now isn't covered by it). */
    private var running = false
    /** Pushed while [running]: refresh again once it is done. */
    private var again = false

    /** Spotify pushed a change of the playlist, now at [revision] (null: not told). */
    fun onPushed(revision: String?) {
        if (revision != null && revision.equals(shown(), ignoreCase = true)) return
        pushed = true
        pushedRevision = revision
        if (!started) return
        if (running) again = true else schedule(Work.REFRESH, settleMs)
    }

    /** The page is on screen (ON_START). */
    fun onStart() {
        started = true
        when {
            pushed -> schedule(Work.REFRESH, 0)
            checkDue() -> schedule(Work.CHECK, 0)
        }
    }

    /** The page left the screen, or the app went to the background (ON_STOP): nothing runs. */
    fun onStop() {
        started = false
        cancel()
    }

    /** The session came online: pushes may have been lost while it was away. */
    fun onOnline() {
        if (!started) {
            confirmedAt = null
            return
        }
        schedule(if (pushed) Work.REFRESH else Work.CHECK, 0)
    }

    /** The page shows [revision], just fetched from the server ([fromServer]) or a cached copy. */
    fun onPage(revision: String?, fromServer: Boolean) {
        if (fromServer) confirmedAt = clock()
        if (pushed && pushedRevision != null && pushedRevision.equals(revision, ignoreCase = true)) {
            pushed = false
            pushedRevision = null
        }
        if (!started || job?.isActive == true) return
        when {
            // A push that came while the page couldn't be refreshed.
            pushed -> schedule(Work.REFRESH, 0)
            // Opened from the cache while on screen: is it still the playlist's revision?
            !fromServer && checkDue() -> schedule(Work.CHECK, 0)
        }
    }

    private fun checkDue(): Boolean = confirmedAt?.let { clock() - it >= checkIntervalMs } ?: true

    private fun cancel() {
        job?.cancel()
        job = null
        jobWork = null
        running = false
        again = false
    }

    /** A refresh replaces a check; otherwise the one scheduled stays (a burst refreshes once). */
    private fun schedule(work: Work, delayMs: Long) {
        if (job?.isActive == true && (jobWork == Work.REFRESH || work == Work.CHECK)) return
        cancel()
        jobWork = work
        // Started once it is [job]: on the main dispatcher it may run at once.
        val next = scope.launch(start = CoroutineStart.LAZY) { perform(work, delayMs) }
        job = next
        next.start()
    }

    private suspend fun perform(work: Work, delayMs: Long) {
        if (delayMs > 0) delay(delayMs)
        running = true
        when (work) {
            Work.REFRESH -> refreshNow()
            Work.CHECK -> checkNow()
        }
        if (job !== currentCoroutineContext()[Job]) return
        running = false
        jobWork = null
        job = null
        // Pushed again while this ran: once more (a failed refresh alone waits for the next push,
        // start or reconnect).
        val more = again && started && pushed
        again = false
        if (more) schedule(Work.REFRESH, settleMs)
    }

    private suspend fun refreshNow() {
        if (!started || !online()) return
        val current = shown() ?: return // onPage brings it back once the page can be refreshed
        if (pushedRevision != null && pushedRevision.equals(current, ignoreCase = true)) {
            pushed = false
            pushedRevision = null
            return
        }
        pushed = false
        pushedRevision = null
        val ok = try {
            refresh()
        } catch (e: CancellationException) {
            pushed = true
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) pushed = true
    }

    private suspend fun checkNow() {
        if (!started || !online() || pushed) return
        val current = shown() ?: return
        val server = try {
            fetchRevision()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        confirmedAt = clock()
        if (server == null || server.equals(current, ignoreCase = true) || !started) return
        pushed = true
        pushedRevision = server
        refreshNow()
    }
}
