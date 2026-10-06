package com.taehagen.spotifygood.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens login pages in a browser chosen explicitly by package, so no other app (notably the
 * official Spotify app, which claims accounts.spotify.com / spotify.com links) can intercept them.
 */
internal object BrowserLauncher {
    private const val TAG = "BrowserLauncher"

    /** Custom Tab of the default Custom-Tabs browser, else a plain browser intent. False if none exists. */
    fun open(activity: Activity, url: String): Boolean {
        val uri = Uri.parse(url)
        val customTabsPackage = try {
            CustomTabsClient.getPackageName(activity, null)
        } catch (e: RuntimeException) {
            null
        }
        if (customTabsPackage != null) {
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
            tab.intent.setPackage(customTabsPackage)
            try {
                tab.launchUrl(activity, uri)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Custom Tab browser $customTabsPackage vanished")
            }
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        browserPackage(activity)?.let(view::setPackage)
        return try {
            activity.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** The default browser's package, else any installed browser's. */
    @Suppress("DEPRECATION")
    private fun browserPackage(context: Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" is the system chooser (no default set): pick a concrete browser instead.
        if (default != null && default != "android") return default
        return pm.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo?.packageName
    }
}
