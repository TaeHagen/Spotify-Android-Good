package com.taehagen.spotifygood.playback

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaButtonReceiver
import com.taehagen.spotifygood.App

/**
 * Media3's media button receiver (Bluetooth / headset play while the app is not running), except
 * that nothing is started while logged out: playback resumption could only fail, and the
 * foreground service start would flash a notification and wake the engine for nothing.
 */
class PlaybackButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext as? App ?: return true
        // Cached value or a file-exists check (no Keystore, no lock): fine on the main thread.
        return app.graph.credentialStore.hasCredentials()
    }
}
