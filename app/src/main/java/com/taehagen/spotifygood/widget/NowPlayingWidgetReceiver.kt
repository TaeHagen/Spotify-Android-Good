package com.taehagen.spotifygood.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen now-playing widget's provider (docs/ARCHITECTURE.md §9.4) and the target of its
 * buttons. Not exported: the system's widget broadcasts and the widget's own PendingIntents still
 * reach it.
 *
 * Playback keys go to [PlaybackService] as media-button intents, the path a headset's take
 * through [com.taehagen.spotifygood.playback.PlaybackButtonReceiver]. Play works from a dead
 * process: a foreground start (a widget tap allows one) whose Play Media3 turns into the playback
 * resumption of the stored session, which takes the media foreground at once (the service meets
 * the start's contract itself when there is nothing to resume). The other buttons need the
 * session the widget showed: without the service it is gone, nothing is started, and the widget
 * is drawn again with the stored session instead.
 */
class NowPlayingWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRANSPORT -> transport(context, intent.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN))
            ACTION_LIKE -> like(context, intent.getStringExtra(EXTRA_URI), intent.getBooleanExtra(EXTRA_LIKED, false))
            ACTION_SHUFFLE -> shuffle(context)
            else -> super.onReceive(context, intent)
        }
    }

    // ---- the system's widget broadcasts ----------------------------------------------------------

    /**
     * Placed, after a reboot or an app update (never on a schedule: updatePeriodMillis is 0).
     * Every widget is drawn: a larger one may need a larger artwork, which they all share.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        redraw(context, null, idsChanged = true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return redraw(context, intArrayOf(appWidgetId))
        // From Android 12 the launcher picks the layout for a new size itself (sized RemoteViews);
        // only a new artwork size draws again.
        val pending = goAsync()
        (context.applicationContext as App).graph.appScope.launch {
            try {
                withTimeoutOrNull(REDRAW_TIMEOUT_MS) { NowPlayingWidgets.resized(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Widget redraw after a resize failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = idsChanged(context)

    override fun onEnabled(context: Context) = idsChanged(context)

    override fun onDisabled(context: Context) = idsChanged(context)

    /**
     * Nothing here: the same broadcast calls [onUpdate] right after, which takes the broadcast's
     * one goAsync() and refreshes the ids itself (a second goAsync() would return null).
     */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) = Unit

    private fun idsChanged(context: Context) {
        val pending = goAsync()
        (context.applicationContext as App).graph.appScope.launch(Dispatchers.IO) {
            try {
                NowPlayingWidgets.ids(context).refresh()
            } finally {
                pending.finish()
            }
        }
    }

    /** Draws [ids] (null: every placed widget), bounded, inside the broadcast. */
    private fun redraw(context: Context, ids: IntArray?, idsChanged: Boolean = false) {
        val pending = goAsync()
        (context.applicationContext as App).graph.appScope.launch {
            try {
                withTimeoutOrNull(REDRAW_TIMEOUT_MS) {
                    val widgets = NowPlayingWidgets.ids(context)
                    if (idsChanged) widgets.refresh()
                    NowPlayingWidgets.render(context, ids ?: widgets.current())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Widget redraw failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    // ---- the widget's buttons ----------------------------------------------------------------------

    private fun transport(context: Context, keyCode: Int) {
        if (keyCode !in KEYS) return
        val graph = (context.applicationContext as App).graph
        // Logged out meanwhile (a cached value or a file check): nothing to control, the widget
        // shows "Sign in" again.
        if (!graph.credentialStore.hasCredentials()) return redraw(context, null)
        val play = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        if (!play && !PlaybackService.isRunning) return redraw(context, null)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        try {
            when {
                // Like a headset Play: a dead process resumes the stored session.
                play -> ContextCompat.startForegroundService(context, intent)
                PlaybackService.isMediaForeground || PlaybackService.isPresenceForeground -> context.startService(intent)
                else -> startInBackground(context, intent)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not for a widget tap, but never crash on it.
            Log.w(TAG, "Cannot start the playback service for a widget button", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the playback service for a widget button", e)
        }
    }

    /**
     * The service runs without a foreground: a plain start where the app may make one (visible,
     * or not idle yet), else a foreground start, whose contract the service meets itself when the
     * key leaves nothing playing.
     */
    private fun startInBackground(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Writes the opposite of what the widget showed ([liked]) for [uri]. */
    private fun like(context: Context, uri: String?, liked: Boolean) {
        if (uri == null || !PlaybackService.isRunning) return redraw(context, null)
        val graph = (context.applicationContext as App).graph
        // The write completes on its own (the library runs it detached); nothing is held for it.
        graph.appScope.launch {
            try {
                graph.library.toggleSaved(uri, liked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Like from the widget failed", e)
            }
        }
    }

    private fun shuffle(context: Context) {
        if (!PlaybackService.isRunning) return redraw(context, null)
        (context.applicationContext as App).graph.player.cycleShuffle()
    }

    internal companion object {
        private const val TAG = "NowPlayingWidget"
        private const val REDRAW_TIMEOUT_MS = 8_000L

        /** A playback key ([EXTRA_KEY_CODE]) for [PlaybackService]. */
        const val ACTION_TRANSPORT = "com.taehagen.spotifygood.widget.action.TRANSPORT"

        /** Like / unlike the current track ([EXTRA_URI], [EXTRA_LIKED]: what the widget showed). */
        const val ACTION_LIKE = "com.taehagen.spotifygood.widget.action.LIKE"

        /** The shuffle button: off → shuffle → smart shuffle → off. */
        const val ACTION_SHUFFLE = "com.taehagen.spotifygood.widget.action.SHUFFLE"

        const val EXTRA_KEY_CODE = "com.taehagen.spotifygood.widget.extra.KEY_CODE"
        const val EXTRA_URI = "com.taehagen.spotifygood.widget.extra.URI"
        const val EXTRA_LIKED = "com.taehagen.spotifygood.widget.extra.LIKED"

        /** The keys the widget sends. */
        private val KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )
    }
}
