package com.taehagen.spotifygood.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus

/**
 * When the becoming-noisy receiver is needed (docs/ARCHITECTURE.md §9.4): while local playback
 * plays, loads or awaits a focus resume, also while the sink is stopped meanwhile (a pause for a
 * transient focus loss, the stall watchdog), since playback is still meant to go on and would
 * otherwise come back on the speaker after a headphone or car disconnect. Never while another
 * device plays.
 */
internal object NoisyRules {
    fun wanted(sinkActive: Boolean, snapshot: PlaybackSnapshot, waitingForGain: Boolean): Boolean {
        if (snapshot.source == PlaybackSource.REMOTE) return false
        val active = snapshot.source == PlaybackSource.LOCAL && snapshot.track != null &&
            (snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.LOADING)
        return sinkActive || active || waitingForGain
    }
}

/**
 * `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged, Bluetooth disconnected) → pause, before the
 * audio is re-routed to the speaker. Registered while [NoisyRules.wanted] (docs/ARCHITECTURE.md
 * §9.4); [register]/[unregister] are idempotent and main-thread only.
 */
internal class BecomingNoisyReceiver(context: Context, private val onNoisy: () -> Unit) : BroadcastReceiver() {
    private val appContext = context.applicationContext
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            appContext,
            this,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(this) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
    }
}
