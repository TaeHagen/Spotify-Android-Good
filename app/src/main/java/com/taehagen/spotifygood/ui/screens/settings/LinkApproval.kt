package com.taehagen.spotifygood.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * open.spotify.com links (docs/ARCHITECTURE.md §9.3, §9.9). This app can't verify Spotify's
 * domain, and from Android 12 a web link opens in the browser unless the user approved the
 * domain for an app ("Open by default" › Add links); nothing else asks them. Settings offers a
 * row for that while it is still needed; below Android 12 the chooser offers the app anyway.
 */
internal object SpotifyLinkApproval {
    private const val TAG = "SpotifyLinkApproval"

    /** The https hosts LinkActivity's intent filter declares. */
    val HOSTS = listOf("open.spotify.com")

    /** Whether the user still has to approve the links (never below Android 12). Blocking: a binder call. */
    fun needed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val state = try {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Reading the link approval failed", e)
            null
        } ?: return false
        return linksNeedApproval(state.isLinkHandlingAllowed, state.hostToStateMap, HOSTS)
    }

    /** Opens the app's "Open by default" settings, else its app info page. */
    fun openSettings(context: Context) {
        val app = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        }
        for (intent in candidates) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No ${intent.action}")
            }
        }
    }
}

/**
 * The approval is needed while the user turned link handling off for the app, or a declared
 * [hosts] entry is neither selected by the user nor verified ([hostStates] as
 * `DomainVerificationUserState.hostToStateMap`; a host the system doesn't list isn't ours to ask).
 */
internal fun linksNeedApproval(linkHandlingAllowed: Boolean, hostStates: Map<String, Int>, hosts: Collection<String>): Boolean =
    !linkHandlingAllowed || hosts.any { hostStates[it] == DomainVerificationUserState.DOMAIN_STATE_NONE }
