package com.taehagen.spotifygood.playback

/** How a playback command would be routed natively right now (docs/ARCHITECTURE.md §4.6, §6). */
enum class EngineReach {
    /** The session is Online: Spirc / the active remote device. */
    ONLINE,

    /**
     * Not Online, but a session is (about to be) connecting with a usable network: native
     * commands wait briefly for Online, then fall back to the offline routing.
     */
    CONNECTING,

    /** Offline mode, no network, or the session reported offline: the native offline queue. */
    OFFLINE,
}

/**
 * What [PlayerController] needs to know about the engine and the downloads, so every caller (UI,
 * the media session, Android Auto) gets the same cold-start and offline behaviour. Installed by
 * [PlaybackCoordinator] (backed by the app graph); without one, commands go straight to the engine.
 */
interface PlaybackEnvironment {
    /** Current routing of a playback command. Cheap, any thread. */
    fun reach(): EngineReach

    /**
     * Suspends (bounded) while the session is starting with a usable network — e.g. a Bluetooth
     * play or a resumption right after process start, before `session.start` even reached the
     * engine — and returns as soon as it is Online, offline, or cannot come online. Returns at once
     * when nothing is starting.
     */
    suspend fun awaitSessionStart()

    /**
     * Downloads of the context [contextUri] (playlist, Liked Songs, album or show) in context
     * order ([startUri]: the requested start item, see [OfflineLoads.members]); null for other
     * context kinds (left to the engine).
     */
    suspend fun downloadedMembers(contextUri: String, startUri: String?): OfflineMembers?
}
