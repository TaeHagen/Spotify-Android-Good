package com.taehagen.spotifygood.playback

import android.content.Context
import com.taehagen.spotifygood.R

/**
 * The "Playing on <device>" line of remote playback (docs/ARCHITECTURE.md §8) combined with the
 * artist line, for the places that show only title + artist (SysUI media controls, the media
 * notification).
 */
internal object DeviceLine {
    /** First release whose SysUI media controls take title/artist from the session (Android 11). */
    const val IN_ARTIST_SDK = 30

    /**
     * "<artist> • <deviceLine>"; just [deviceLine] without an artist; [artist] unchanged when there
     * is no device line or it already contains it (never added twice).
     */
    fun join(artist: CharSequence?, deviceLine: CharSequence?, format: (CharSequence, CharSequence) -> CharSequence): CharSequence? =
        when {
            deviceLine.isNullOrBlank() -> artist
            artist.isNullOrBlank() -> deviceLine
            artist.contains(deviceLine) -> artist
            else -> format(artist, deviceLine)
        }

    fun join(context: Context, artist: CharSequence?, deviceLine: CharSequence?): CharSequence? =
        join(artist, deviceLine) { a, d -> context.getString(R.string.playback_artist_on_device, a, d) }
}
