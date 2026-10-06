package com.taehagen.spotifygood.playback

import android.content.Context
import androidx.lifecycle.LifecycleOwner

/**
 * Keeps the [PlaybackService] (MediaLibraryService) bound while a UI lifecycle is STARTED, using a
 * Media3 MediaController, and releases it when STOPPED so the service can stop when idle.
 * Also ensures the service is started when local playback begins while no controller is bound.
 */
class PlaybackServiceConnector(context: Context) {
    fun attach(owner: LifecycleOwner): Unit = TODO()
}
