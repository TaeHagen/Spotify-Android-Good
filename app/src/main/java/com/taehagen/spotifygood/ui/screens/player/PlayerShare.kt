package com.taehagen.spotifygood.ui.screens.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Opens the system share sheet with the open.spotify.com link of [uri]; false if not possible. */
internal fun shareSpotifyLink(context: Context, uri: String, title: String?, chooserTitle: String): Boolean {
    val url = spotifyShareUrl(uri) ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, title)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
