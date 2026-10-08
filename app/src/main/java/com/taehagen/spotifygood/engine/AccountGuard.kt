package com.taehagen.spotifygood.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Keeps this device's data (downloads, caches, recent searches, the resume state) with the
 * account it belongs to (docs/ARCHITECTURE.md §9.3). Rejected credentials only forget the
 * credentials, so the same user logs back in with everything in place; when another account
 * logs in, the previous account's data goes first.
 *
 * [readOwner] / [writeOwner] are blocking (run on IO); [wipe] removes the previous account's data
 * and throws if any of it may be left.
 */
internal class AccountGuard(
    private val readOwner: () -> String?,
    private val writeOwner: (String) -> Unit,
    private val onOwnerNotRecorded: (Throwable) -> Unit = {},
    private val wipe: suspend () -> Unit,
) {
    /**
     * [username] is about to be logged in. If the data belongs to another account it is wiped
     * first, and the new owner is recorded only once that succeeded: a failed or interrupted wipe
     * throws (the login fails) and is redone by the next login. Without an owner yet, a failed
     * write only reaches [onOwnerNotRecorded] (no other account's data is at stake; the owner is
     * recorded again at the next login or process start). Returns whether it wiped. Not
     * cancellable once started.
     */
    suspend fun adopt(username: String): Boolean = withContext(NonCancellable) {
        val name = username.trim()
        if (name.isEmpty()) return@withContext false
        val owner = withContext(Dispatchers.IO) { readOwner() }
        if (isAnotherAccount(owner, name)) {
            wipe()
            withContext(Dispatchers.IO) { writeOwner(name) }
            return@withContext true
        }
        if (owner != name) {
            try {
                withContext(Dispatchers.IO) { writeOwner(name) }
            } catch (e: Exception) {
                onOwnerNotRecorded(e)
            }
        }
        false
    }
}

/**
 * Whether [username] is another account than the [owner] of the data. Nobody owns it (first
 * login, after a complete logout) is no other account; Spotify usernames don't differ by case.
 */
internal fun isAnotherAccount(owner: String?, username: String): Boolean =
    owner != null && !owner.equals(username.trim(), ignoreCase = true)

/** A named step of a wipe (logout, a login as another account). */
internal class WipeStep(val name: String, val run: suspend () -> Unit)

/**
 * Runs every step, also after a failed one, and rethrows the first failure at the end (after
 * [onFailure] saw each one): a wipe never stops halfway because one part failed.
 */
internal suspend fun runWipeSteps(steps: List<WipeStep>, onFailure: (WipeStep, Throwable) -> Unit = { _, _ -> }) {
    var failure: Throwable? = null
    for (step in steps) {
        try {
            step.run()
        } catch (t: Throwable) {
            onFailure(step, t)
            if (failure == null) failure = t
        }
    }
    failure?.let { throw it }
}
