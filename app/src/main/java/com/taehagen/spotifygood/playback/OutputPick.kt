package com.taehagen.spotifygood.playback

/**
 * The user's explicit output pick ([OutputRouteManager.select]), temporary like the system output
 * switcher's: it lasts until that device goes away, a new external output connects (headphones
 * put on, a car kit, a wired or USB headset: it takes over), the engine stops (or logs out), or
 * the user picks "Automatic". Otherwise a picked phone speaker would stay forced and every
 * output connected later would get no audio.
 *
 * The outputs present at the pick are remembered: the device callback's initial "added" event
 * (each engine start) lists every current device, which must not end a valid pick. A Bluetooth
 * device that reconnects gets a new id, so it counts as new. Thread-safe.
 */
internal class OutputPick {
    /** The picked output's device id; null: follow the system route. */
    @get:Synchronized
    var preferredId: Int? = null
        private set
    private var presentAtPick: Set<Int> = emptySet()

    /** Picks [id] (null: "Automatic") while the outputs [present] are connected. */
    @Synchronized fun pick(id: Int?, present: Set<Int>) {
        preferredId = id
        presentAtPick = if (id != null) present else emptySet()
    }

    /** Outputs were [added] (id and kind); true when that ended the pick. */
    @Synchronized fun onAdded(added: List<Pair<Int, OutputKind?>>): Boolean {
        val id = preferredId ?: return false
        val takesOver = added.any { (device, kind) -> device != id && device !in presentAtPick && kind in EXTERNAL }
        if (takesOver) clearLocked()
        return takesOver
    }

    /** Outputs were removed; true when the picked one was among them (the pick ended). */
    @Synchronized fun onRemoved(removed: Set<Int>): Boolean {
        val id = preferredId ?: return false
        if (id !in removed) return false
        clearLocked()
        return true
    }

    /** Ends the pick (engine stop, logout); true when there was one. */
    @Synchronized fun clear(): Boolean {
        if (preferredId == null) return false
        clearLocked()
        return true
    }

    private fun clearLocked() {
        preferredId = null
        presentAtPick = emptySet()
    }

    private companion object {
        /** Outputs that take over from a pick when they connect (not the speaker, not a dock). */
        val EXTERNAL = setOf(
            OutputKind.BLUETOOTH, OutputKind.WIRED, OutputKind.USB, OutputKind.HEARING_AID, OutputKind.CAR, OutputKind.HDMI,
        )
    }
}
