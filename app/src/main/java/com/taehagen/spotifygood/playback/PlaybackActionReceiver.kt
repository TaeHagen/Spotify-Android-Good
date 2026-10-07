package com.taehagen.spotifygood.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taehagen.spotifygood.App
import kotlinx.coroutines.launch

/**
 * Target of the app's own notification actions ("Tap to resume", the presence "Stop"). It is not
 * exported, so only our PendingIntents reach it; it forwards to [PlaybackService] with the
 * in-process token ([PlaybackService.internalIntent]), because the exported service cannot tell
 * who started it. The notification tap's temporary allowlist covers the service start.
 */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESUME -> try {
                context.startService(PlaybackService.internalIntent(context, PlaybackService.ACTION_RESUME))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot start the playback service to resume", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot start the playback service to resume", e)
            }
            ACTION_STOP_PRESENCE -> {
                // The service follows the setting (it drops the presence foreground right away).
                val graph = (context.applicationContext as App).graph
                val pending = goAsync()
                graph.appScope.launch {
                    try {
                        graph.settings.update { it.copy(connectPresence = false) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot turn off Connect presence", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackActions"
        /** "Tap to resume" ([ResumeAlert]). */
        const val ACTION_RESUME = "com.taehagen.spotifygood.playback.action.RESUME"
        /** "Stop" of the Connect presence notification: turns the setting off. */
        const val ACTION_STOP_PRESENCE = "com.taehagen.spotifygood.playback.action.STOP_PRESENCE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, PlaybackActionReceiver::class.java).setAction(action)
    }
}
