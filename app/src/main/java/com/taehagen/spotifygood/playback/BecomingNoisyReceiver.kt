package com.taehagen.spotifygood.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged, Bluetooth disconnected) → pause, before the
 * audio is re-routed to the speaker. Registered only while audio plays locally
 * (docs/ARCHITECTURE.md §9.4); [register]/[unregister] are idempotent and main-thread only.
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
