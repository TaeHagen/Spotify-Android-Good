package com.taehagen.spotifygood.connect

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * When [ConnectRouteProviderService] is enabled (docs/ARCHITECTURE.md §8, "System output switcher";
 * pure, JVM-testable).
 *
 * Every change of a component's enabled state makes PackageManager send a package-wide
 * `PACKAGE_CHANGED`: AppWidgetService then resets every placed now-playing widget to its initial
 * layout and sends it `APPWIDGET_UPDATE`, launchers reload the package, and the system rescans its
 * route providers. So the state follows the login alone (it changes at login and logout), never the
 * playback service or the app's visibility.
 */
internal object RouteProviderRule {
    /**
     * The provider is offered from Android 12. Android 11 binds every enabled route provider for
     * good (from the first router at boot, again after its process dies and after a force stop:
     * `MediaRoute2ProviderServiceProxy.shouldBind()` is `mRunning`, `onBindingDied` binds again, the
     * watcher rescans on `PACKAGE_RESTARTED`), as a foreground service binding: the process would stay
     * resident at visible importance all day, also with nothing playing and the app never opened.
     */
    const val MIN_SDK = 31

    /**
     * Whether the provider is enabled: from [MIN_SDK], while an account is logged in ([loggedIn]:
     * the engine says so; [storedCredentials]: credentials are stored, the engine has not read them
     * yet or could not right now). Logged out it has nothing to list.
     */
    fun wanted(sdk: Int, loggedIn: Boolean, storedCredentials: Boolean): Boolean =
        sdk >= MIN_SDK && (loggedIn || storedCredentials)

    /**
     * The enabled setting to write for [wanted] given the [current] one (a
     * `PackageManager.COMPONENT_ENABLED_STATE_*`), or null when it already matches: nothing is
     * written, so no `PACKAGE_CHANGED`. Off is the manifest's default (disabled); any disabled state
     * counts as off.
     */
    fun change(current: Int, wanted: Boolean): Int? = when {
        wanted && current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        !wanted && current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        else -> null
    }
}

/** The provider component's enabled setting (PackageManager in the app; a fake in tests). */
internal interface ComponentSetting {
    fun get(): Int

    fun set(state: Int)
}

/**
 * Applies [RouteProviderRule] to [ConnectRouteProviderService] (the manifest declares it disabled).
 * Its inputs: the stored credentials at every process start ([reconcile], so a process that died
 * between a login and the write still gets there), then the engine's login state once it read the
 * credentials ([update]). A wish is applied [settleMs] after the last one (latest wins), off the
 * main thread, and written only when it differs from the component's setting: an ordinary process
 * start, a playback service start or stop and an app switch write nothing.
 */
internal class RouteProviderSwitch(
    scope: CoroutineScope,
    private val setting: ComponentSetting,
    private val storedCredentials: () -> Boolean,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val settleMs: Long = SETTLE_MS,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = { message, error -> if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error) },
) {
    /** The engine's login state; null: not known yet (the stored credentials decide). */
    private val loggedIn = MutableStateFlow<Boolean?>(null)
    private val requests = MutableStateFlow<Request?>(null)

    /** A new object per wish, so an equal one still counts as the latest. */
    private class Request(val loggedIn: Boolean)

    init {
        scope.launch {
            requests.filterNotNull().collectLatest { request ->
                delay(settleMs)
                withContext(io) { apply(request.loggedIn) }
            }
        }
    }

    /** At process start: the stored credentials decide until the engine has read them. */
    fun reconcile() {
        requests.value = Request(loggedIn.value ?: false)
    }

    /** The engine's login state (after it read the stored credentials). Any thread. */
    fun update(loggedIn: Boolean) {
        this.loggedIn.value = loggedIn
        requests.value = Request(loggedIn)
    }

    private fun apply(loggedIn: Boolean) {
        // The credentials are read now: at a login the engine may say so before they are written.
        val wanted = RouteProviderRule.wanted(sdk, loggedIn, storedCredentials())
        try {
            val target = RouteProviderRule.change(setting.get(), wanted) ?: return
            setting.set(target)
            log("Route provider ${if (wanted) "enabled" else "disabled"}", null)
        } catch (e: RuntimeException) {
            log("Cannot ${if (wanted) "enable" else "disable"} the route provider", e)
        }
    }

    /** [ComponentSetting] of [ConnectRouteProviderService] through PackageManager. */
    class Provider(context: Context) : ComponentSetting {
        private val pm = context.applicationContext.packageManager
        // By name: a class literal would load ConnectRouteProviderService, whose superclass is
        // API 30, and crash the process start below Android 11 (this runs from App.onCreate).
        private val component = ComponentName(context.applicationContext.packageName, PROVIDER_CLASS)

        override fun get(): Int = pm.getComponentEnabledSetting(component)

        // The system rescans its route providers on the PACKAGE_CHANGED this sends.
        override fun set(state: Int) = pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    internal companion object {
        private const val TAG = "RouteProviderSwitch"

        /** A login and a logout right after it write nothing. */
        const val SETTLE_MS = 2_000L

        /** [ConnectRouteProviderService]'s name (a manifest component, so R8 keeps it). */
        const val PROVIDER_CLASS = "com.taehagen.spotifygood.connect.ConnectRouteProviderService"
    }
}
