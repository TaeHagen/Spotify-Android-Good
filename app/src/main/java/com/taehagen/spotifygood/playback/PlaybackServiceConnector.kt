package com.taehagen.spotifygood.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the [PlaybackService] (MediaLibraryService) bound while a UI lifecycle is STARTED, using a
 * Media3 MediaController, and releases it when STOPPED so the service can stop when idle.
 * Also ensures the service is started when local playback begins while no controller is bound.
 */
class PlaybackServiceConnector(context: Context) {
    private val appContext = context.applicationContext
    private val _controller = MutableStateFlow<MediaController?>(null)

    /** The connected in-app controller while an attached lifecycle is started (main thread). */
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private var bindCount = 0
    private var future: ListenableFuture<MediaController>? = null

    init {
        // The coordinator wires audio focus, volume and service starts (idempotent).
        PlaybackCoordinator.install(appContext)
    }

    /**
     * Binds the service while [owner] is STARTED. Safe to call for several owners; the binding is
     * shared and released when the last started owner stops. Main thread.
     */
    fun attach(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var started = false

            override fun onStart(owner: LifecycleOwner) {
                if (started) return
                started = true
                acquire()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!started) return
                started = false
                release()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (started) {
                    started = false
                    release()
                }
                owner.lifecycle.removeObserver(this)
            }
        })
    }

    /**
     * Starts the playback service if it is not running (only possible while the app is visible;
     * Media3 promotes it to the foreground once playback is ongoing). Any thread.
     */
    fun ensureServiceStarted() {
        PlaybackCoordinator.install(appContext).ensureServiceStarted()
    }

    private fun acquire() {
        if (bindCount++ > 0) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).buildAsync()
        future = f
        f.addListener(
            {
                if (future !== f) return@addListener
                _controller.value = runCatching { f.get() }
                    .onFailure { Log.w(TAG, "Cannot connect to the playback service", it) }
                    .getOrNull()
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun release() {
        if (bindCount == 0 || --bindCount > 0) return
        future?.let { MediaController.releaseFuture(it) }
        future = null
        _controller.value = null
    }

    private companion object {
        const val TAG = "PlaybackConnector"
    }
}
