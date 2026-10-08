package com.taehagen.spotifygood

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log

/**
 * Entry point for requests from outside the launcher: the `spotifygood://auth` login redirect,
 * `spotify:` and open.spotify.com links, shared text and voice search. It forwards them to the
 * one [MainActivity] in the app's own task and finishes (no UI, no history).
 *
 * [MainActivity] is only `singleTop`, so a launcher tap brings the task to the front as it is,
 * with a login Custom Tab still on top of it; a `singleTask` relaunch used to finish the tab and
 * lose the half-done login. The forward is a launcher intent carrying the request
 * ([MainActivity.EXTRA_REQUEST]): a task a link created still has the launcher's root intent, so
 * a later launcher tap only brings it to the front too. It clears what is above [MainActivity]
 * (the login Custom Tab once its redirect arrives) and reaches the existing instance
 * (`onNewIntent`) instead of creating a second one in the caller's task.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) forward(intent)
        finish()
    }

    private fun forward(request: Intent?) {
        val main = Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        request?.let { copyRequest(it) }?.let { main.putExtra(MainActivity.EXTRA_REQUEST, it) }
        startActivity(main)
    }

    /**
     * Only what [MainActivity] reads (action, data, the shared text and the voice search
     * fields): a fresh intent that is never started, and no other app's parcelables to unmarshal.
     */
    private fun copyRequest(request: Intent): Intent? = try {
        Intent(request.action).apply {
            setDataAndType(request.data, request.type)
            for (key in FORWARDED_EXTRAS) request.getStringExtra(key)?.let { putExtra(key, it) }
        }
    } catch (e: RuntimeException) {
        // A malformed extras bundle from another app: nothing to forward.
        Log.w(TAG, "Unreadable request", e)
        null
    }

    private companion object {
        const val TAG = "LinkActivity"
        val FORWARDED_EXTRAS = listOf(
            Intent.EXTRA_TEXT,
            SearchManager.QUERY,
            MediaStore.EXTRA_MEDIA_FOCUS,
            MediaStore.EXTRA_MEDIA_ARTIST,
            MediaStore.EXTRA_MEDIA_ALBUM,
            MediaStore.EXTRA_MEDIA_TITLE,
            MainActivity.EXTRA_MEDIA_PLAYLIST,
        )
    }
}
