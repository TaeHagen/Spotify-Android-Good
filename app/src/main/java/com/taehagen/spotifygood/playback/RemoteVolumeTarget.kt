package com.taehagen.spotifygood.playback

/**
 * The volume (percent) last sent to a remote Connect device, so relative volume-key steps add up
 * (docs/ARCHITECTURE.md §9.6). The snapshot only reflects a change after the debounced PUT and the
 * cluster round trip; computing every step from it would send the same target again and again.
 *
 * A target stays authoritative for [validMs] after the last change (the echoes of our own PUTs
 * arrive meanwhile and are not "external" changes), for the same active device only. Once the
 * snapshot reports it, or it expires, the snapshot is the truth again. Main thread only.
 */
internal class RemoteVolumeTarget(
    private val now: () -> Long,
    private val validMs: Long = DEFAULT_VALID_MS,
) {
    private var percent: Int? = null
    private var deviceId: String? = null
    private var atMs = 0L

    /** Records [target] (0..100) just sent to [device]. */
    fun set(target: Int, device: String?) {
        percent = target.coerceIn(0, 100)
        deviceId = device
        atMs = now()
    }

    /** The pending target for [device], or null (none, expired, other device). */
    fun pending(device: String?): Int? {
        val p = percent ?: return null
        if (device != deviceId || now() - atMs >= validMs) {
            clear()
            return null
        }
        return p
    }

    /** Base of a relative step: the pending target, else the device's reported [snapshotPercent]. */
    fun base(device: String?, snapshotPercent: Int): Int = pending(device) ?: snapshotPercent

    /**
     * Percent to publish for [device] given the reported [snapshotPercent]; clears the target once
     * the device reports it.
     */
    fun reported(device: String?, snapshotPercent: Int): Int {
        val p = pending(device) ?: return snapshotPercent
        if (p == snapshotPercent) {
            clear()
            return snapshotPercent
        }
        return p
    }

    /** Milliseconds until the pending target expires (null without one). */
    fun remainingMs(): Long? = percent?.let { (validMs - (now() - atMs)).coerceAtLeast(0) }

    fun clear() {
        percent = null
        deviceId = null
    }

    companion object {
        /** Like [PlayerController]'s optimistic tri-state values and [SpotifyPlayer]'s settle time. */
        const val DEFAULT_VALID_MS = 2_000L
    }
}
