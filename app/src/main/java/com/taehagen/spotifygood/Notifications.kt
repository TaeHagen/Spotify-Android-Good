package com.taehagen.spotifygood

/** Notification channel and id constants shared by all components (channels created at startup). */
object Notifications {
    /** Media playback (created by the Media3 notification provider). */
    const val CHANNEL_PLAYBACK = "playback"
    /** Download progress (low importance). */
    const val CHANNEL_DOWNLOADS = "downloads"
    /** "Available on Spotify Connect" presence (min importance). */
    const val CHANNEL_CONNECT_PRESENCE = "connect_presence"
    /** Actionable alerts, e.g. "Tap to resume" when playback could not start in the background. */
    const val CHANNEL_ALERTS = "alerts"

    const val ID_PLAYBACK = 1001
    const val ID_PRESENCE = 1002
    const val ID_DOWNLOADS = 1003
    const val ID_DOWNLOADS_DONE = 1004
    const val ID_RESUME_ALERT = 1005
    /** Connect presence could not be restored after a reboot / update: open the app. */
    const val ID_PRESENCE_RESTORE = 1006
}
