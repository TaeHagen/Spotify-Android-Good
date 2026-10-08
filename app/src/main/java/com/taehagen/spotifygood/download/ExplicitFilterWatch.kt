package com.taehagen.spotifygood.download

/**
 * What the downloads bookkeeping knows about the explicit filter behind a catalog answer.
 *
 * While the effective filter is on, the catalog returns every explicit track and episode with
 * `playable:false`, whatever its availability. The filter has two sources that downloads treat
 * differently ([DownloadRules.memberVerdict], [DownloadRules.rowVerdict]):
 * * "Hide explicit content" (the app setting): downloads ignore it (display and playback apply
 *   it), so such an answer says nothing about an explicit item.
 * * The account's own filter (Spotify's parental setting): explicit items are not downloaded for
 *   such an account (`download.track` refuses them), so new ones are not queued; existing
 *   downloads stay (the Player refuses them, offline too).
 *
 * The engine does not report which filter an answer was computed with, so a lookup takes a [Mark]
 * before it asks ([begin]) and then asks [filterAt]. A source counts as off only if it was known
 * off (the app setting applied by the engine, the account's value reported by an online session)
 * from before the lookup until after it; any change starts a new generation. Until the first values
 * are known, both may apply.
 */
internal class ExplicitFilterWatch {
    /** What an explicit item's `playable:false` may stem from. */
    enum class Filter {
        /** No filter applied: the item is not playable here. */
        OFF,

        /** Only "Hide explicit content" may have applied: no verdict (downloads ignore it). */
        APP,

        /** The account's own filter is or may be on: explicit items are not downloaded for it. */
        ACCOUNT,
    }

    /** The watch when a lookup started. */
    class Mark internal constructor(
        internal val appGeneration: Long,
        internal val accountGeneration: Long,
        internal val appKnownOff: Boolean,
        internal val accountKnownOff: Boolean,
    )

    private data class State(
        val appGeneration: Long = 0,
        val app: Boolean = true,
        val appSettled: Boolean = false,
        val accountGeneration: Long = 0,
        /** As an online session reported it; null while not reported. */
        val account: Boolean? = null,
    )

    private val lock = Any()
    @Volatile private var state = State()

    /** Taken before a catalog lookup whose `playable` flags are used. */
    fun begin(): Mark = state.let { Mark(it.appGeneration, it.accountGeneration, !it.app && it.appSettled, it.account == false) }

    /** Which filter may have applied to an answer requested after [mark]. */
    fun filterAt(mark: Mark): Filter {
        val now = state
        if (!mark.accountKnownOff || now.accountGeneration != mark.accountGeneration) return Filter.ACCOUNT
        return if (mark.appKnownOff && now.appGeneration == mark.appGeneration) Filter.OFF else Filter.APP
    }

    /**
     * "Hide explicit content" is now [hide] (also when unchanged: what was asked before cannot count
     * on the engine's value any more); not applied by the engine yet. Returns the generation to
     * [settleApp].
     */
    fun updateApp(hide: Boolean): Long = synchronized(lock) {
        state.copy(appGeneration = state.appGeneration + 1, app = hide, appSettled = false).also { state = it }.appGeneration
    }

    /** The engine runs with the app setting of [generation] (ignored once a newer one was observed). */
    fun settleApp(generation: Long) {
        synchronized(lock) {
            if (state.appGeneration == generation) state = state.copy(appSettled = true)
        }
    }

    /** The account's own filter as an online session reported it; null while none did. */
    fun updateAccount(account: Boolean?) {
        synchronized(lock) {
            if (state.account != account) state = state.copy(accountGeneration = state.accountGeneration + 1, account = account)
        }
    }
}
