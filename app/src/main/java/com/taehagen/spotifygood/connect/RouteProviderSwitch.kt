package com.taehagen.spotifygood.connect

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Enables [ConnectRouteProviderService] only while the playback service runs (docs/ARCHITECTURE.md
 * §8, "System output switcher"). Android binds every enabled route provider by its own rules (on
 * Android 11 always, on 12–14 while any app's media notification is up with the screen on, from 15
 * while a foreground app or the output switcher asks for routes) and the binding starts the
 * process or keeps it at foreground-service importance; the manifest declares the provider
 * disabled. The setting is applied off the main thread, the latest wish winning; created at every
 * process start, it disables a provider left enabled by a process that died (its first wish is off).
 */
internal class RouteProviderSwitch(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val wanted = MutableStateFlow(false)

    init {
        if (Build.VERSION.SDK_INT >= SystemRoutes.MIN_SDK) {
            scope.launch(Dispatchers.IO) { wanted.collect(::apply) }
        }
    }

    /** The provider should be enabled ([enabled]: the playback service runs) or not. Any thread. */
    fun set(enabled: Boolean) {
        wanted.value = enabled
    }

    private fun apply(enabled: Boolean) {
        val component = ComponentName(appContext, ConnectRouteProviderService::class.java)
        // DEFAULT is the manifest's: disabled.
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        try {
            val pm = appContext.packageManager
            if (pm.getComponentEnabledSetting(component) == state) return
            // The system rescans its route providers on the PACKAGE_CHANGED this sends.
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot ${if (enabled) "enable" else "disable"} the route provider", e)
        }
    }

    private companion object {
        const val TAG = "RouteProviderSwitch"
    }
}
