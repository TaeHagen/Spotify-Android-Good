package com.taehagen.spotifygood.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.RouteListingPreference
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.LinkActivity
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.connect.AdoptAttempt
import com.taehagen.spotifygood.connect.ConnectRouteProviderService
import com.taehagen.spotifygood.connect.ListedRoute
import com.taehagen.spotifygood.connect.ListingBehavior
import com.taehagen.spotifygood.connect.RoutingStep
import com.taehagen.spotifygood.connect.SystemRoutes
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.screens.player.DevicePicks
import com.taehagen.spotifygood.ui.screens.player.message
import com.taehagen.spotifygood.ui.screens.player.transferFailureEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's side of Android's system output switcher (docs/ARCHITECTURE.md §8, "System output
 * switcher"), owned by the [PlaybackService] from its creation to its destruction (Android 12+,
 * [SystemRoutes.MIN_SDK]): the app's `MediaRouter2`, the router the system lists routes for and asks
 * for sessions through. It changes no component state (the provider is enabled while logged in,
 * [com.taehagen.spotifygood.connect.RouteProviderSwitch]); the provider follows [running].
 *
 * * While logged in it asks for the Connect routes ([ConnectRouteProviderService]) and the system's
 *   own (this phone, Bluetooth, wired: [SystemRoutes.FEATURE_LOCAL_PLAYBACK]), passively: no scan.
 *   Without a router asking for them the switcher lists no Connect device, and a pick there could
 *   not reach the app.
 * * The media session names its routing session ([controllerId], the `DeviceInfo` routing
 *   controller id, which Media3 passes on as the volume control id): SystemUI matches the two,
 *   names the chip after the session and opens the switcher on it. Another device playing without
 *   a session gets one ([SystemRoutes.next]: a session request for its route, which the provider
 *   answers at once), whenever the router knows the route (the system binds the provider when the
 *   app is in the foreground, the switcher opens, and on Android 12–14 also while the screen is on
 *   with a media control in quick settings). Released at once when playback came back to this
 *   phone from its device, else once no device plays for a grace.
 * * "This phone" or a Bluetooth / wired output picked in the switcher while the session is on
 *   another device (the system transfers the session to its own): playback comes back here, as
 *   the devices sheet's "Tap to play here" does, and the switcher's output wins over a pick in the
 *   app.
 * * From Android 14 a route listing preference keeps the switcher in the devices sheet's order,
 *   shows devices that can't play as such, and lists "Other devices on your network", which opens
 *   the sheet.
 *
 * Main thread.
 */
@RequiresApi(31)
internal class SystemRouting(
    context: Context,
    private val graph: AppGraph,
    /** [controllerId] changed: the session player and the volume keys publish it. */
    private val onControllerChanged: () -> Unit,
    /** Sets the playing device's volume, in percent, through the session player. */
    val setVolume: (Int) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = context.mainExecutor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val router: MediaRouter2? = try {
        MediaRouter2.getInstance(this.context)
    } catch (e: RuntimeException) {
        Log.w(TAG, "No media router", e)
        null
    }
    private var registered = false

    /** The later of our session's creation and the last time a device played ([SystemRoutes.next]). */
    private var heldSince = 0L
    /** A device played while our current session existed ([SystemRoutes.handedBack]). */
    private var sawDevicePlay = false
    private var attempt: AdoptAttempt? = null
    private val recheck = Runnable { reconcile() }

    /**
     * The app's routing session for the device that plays (the routing controller id), or null:
     * the media session's `DeviceInfo` and [RemoteVolumeKeys] carry it.
     */
    var controllerId: String? = null
        private set

    fun start() {
        active.value = this
        scope.launch {
            graph.engine.isLoggedIn.collect { loggedIn -> if (loggedIn) register() else unregister() }
        }
        scope.launch {
            // What the session shows playing elsewhere; the provider keeps a session's device itself.
            merge(
                graph.playback.snapshot.map { },
                graph.devices.devices.map { },
            ).collect { reconcile() }
        }
    }

    fun stop() {
        scope.cancel()
        main.removeCallbacks(recheck)
        unregister()
        // The provider lists nothing without it (and leaves the engine alone while bound).
        active.compareAndSet(this, null)
    }

    private fun register() {
        val router = router ?: return
        if (registered) return
        try {
            router.registerTransferCallback(executor, transferCallback)
            router.registerRouteCallback(
                executor,
                routeCallback,
                RouteDiscoveryPreference.Builder(listOf(SystemRoutes.FEATURE_CONNECT, SystemRoutes.FEATURE_LOCAL_PLAYBACK), false).build(),
            )
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot register with the media router", e)
            runCatching { router.unregisterTransferCallback(transferCallback) }
            return
        }
        reconcile()
    }

    private fun unregister() {
        val router = router ?: return
        if (!registered) return
        registered = false
        main.removeCallbacks(recheck)
        ours()?.let { release(it) }
        attempt = null
        sawDevicePlay = false
        if (Build.VERSION.SDK_INT >= SystemRoutes.LISTING_SDK) Api34.setListing(router, null)
        runCatching { router.unregisterRouteCallback(routeCallback) }
        runCatching { router.unregisterTransferCallback(transferCallback) }
    }

    /** Our routing controller (any session not the system's is the provider's). */
    private fun ours(): MediaRouter2.RoutingController? {
        val router = router ?: return null
        val system = router.systemController
        return router.controllers.lastOrNull { it !== system && !it.isReleased }
    }

    private fun release(controller: MediaRouter2.RoutingController) {
        runCatching { controller.release() }.onFailure { Log.w(TAG, "Cannot release the routing session", it) }
        if (controller.id == controllerId) {
            setControllerId(null)
            sawDevicePlay = false
        }
    }

    private fun setControllerId(id: String?) {
        if (id == controllerId) return
        controllerId = id
        onControllerChanged()
    }

    /** The router's route for the Connect device [deviceId], if it knows it. */
    private fun routeOf(deviceId: String): MediaRoute2Info? = router?.routes?.firstOrNull {
        SystemRoutes.FEATURE_CONNECT in it.features && it.extras?.getString(SystemRoutes.EXTRA_DEVICE_ID) == deviceId
    }

    /** Applies [SystemRoutes.next] to the current state. */
    private fun reconcile() {
        val router = router ?: return
        if (!registered) return
        main.removeCallbacks(recheck)
        val now = clock()
        val snapshot = graph.playback.snapshot.value
        val target = SystemRoutes.target(graph.devices.devices.value, snapshot)?.id
        if (target != null) heldSince = now
        val ours = ours()
        if (ours?.id != controllerId) sawDevicePlay = false
        setControllerId(ours?.id)
        if (target != null && ours != null) sawDevicePlay = true
        val route = target?.let(::routeOf)
        val backHere = SystemRoutes.handedBack(snapshot, sawDevicePlay)
        when (val step = SystemRoutes.next(target, ours != null, heldSince, route != null, attempt, now, backHere)) {
            is RoutingStep.Adopt -> {
                attempt = AdoptAttempt(step.deviceId, now)
                // The provider answers at once for the device that plays (no transfer).
                if (route != null) runCatching { router.transferTo(route) }.onFailure { Log.w(TAG, "Session request failed", it) }
            }
            RoutingStep.Release -> ours?.let(::release)
            is RoutingStep.RecheckAt -> main.postDelayed(recheck, (step.atMs - now).coerceAtLeast(0))
            RoutingStep.None -> Unit
        }
    }

    /**
     * Playback comes back to this phone after a pick of one of its outputs in the switcher, as the
     * devices sheet's "Tap to play here" (the same transfer and messages).
     */
    private fun playHere() {
        // A session kept through its grace while nothing plays elsewhere: nothing to bring back.
        val target = SystemRoutes.target(graph.devices.devices.value, graph.playback.snapshot.value)?.id ?: return
        val thisId = graph.devices.devices.value.let { list -> list.thisDeviceId ?: list.devices.firstOrNull { it.isThisDevice }?.id } ?: return
        // No new session for the device while playback leaves it (the snapshot follows shortly).
        attempt = AdoptAttempt(target, clock())
        // The system routes the output picked there; a pick in the app's sheet would override it.
        if (graph.outputs.outputs.value.any { it.isPreferred }) graph.outputs.select(null)
        DevicePicks.mark()
        val name = context.getString(R.string.player_devices_this_phone)
        graph.appScope.launch {
            try {
                graph.devices.transferTo(thisId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playing here after a pick in the output switcher failed", e)
                val text = transferFailureEvent(sheet = "", deviceName = name, isThisDevice = true, error = e).message()
                    ?.let { context.getString(it.res, *it.args.toTypedArray()) }
                withContext(Dispatchers.Main) {
                    text?.let { BackgroundMessages.post(it) }
                    // It plays on there: its session again.
                    if (attempt?.deviceId == target) attempt = null
                    reconcile()
                }
            }
        }
    }

    private val transferCallback = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) {
            val router = router ?: return
            val system = router.systemController
            if (newController !== system) {
                // A session of ours: a pick in the switcher, or the request for the device that plays.
                heldSince = clock()
                attempt = null
                // Its device may not show in the snapshot yet: the release grace applies.
                sawDevicePlay = false
                setControllerId(newController.id)
                // One session at a time (a pick and the request for the playing device may cross).
                router.controllers.filter { it !== system && it !== newController && !it.isReleased }.forEach { release(it) }
                if (oldController !== system && !oldController.isReleased) release(oldController)
            } else if (oldController !== system) {
                // One of this phone's outputs picked while the session was on another device.
                release(oldController)
                playHere()
            }
            reconcile()
        }

        override fun onTransferFailure(requestedRoute: MediaRoute2Info) {
            // A failed request for the playing device waits for ADOPT_RETRY_MS (attempt).
            reconcile()
        }

        override fun onStop(controller: MediaRouter2.RoutingController) {
            if (controller.id == controllerId) setControllerId(null)
            reconcile()
        }
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        // Below Android 14 only these three are called; from 14 onRoutesUpdated after each of them.
        @Deprecated("Android 14: onRoutesUpdated")
        override fun onRoutesAdded(routes: List<MediaRoute2Info>) = onRoutes(updated = false)

        @Deprecated("Android 14: onRoutesUpdated")
        override fun onRoutesRemoved(routes: List<MediaRoute2Info>) = onRoutes(updated = false)

        @Deprecated("Android 14: onRoutesUpdated")
        override fun onRoutesChanged(routes: List<MediaRoute2Info>) = onRoutes(updated = false)

        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = onRoutes(updated = true)
    }

    private fun onRoutes(updated: Boolean) {
        if (updated != (Build.VERSION.SDK_INT >= SystemRoutes.LISTING_SDK)) return
        val router = router ?: return
        if (!registered) return
        if (Build.VERSION.SDK_INT >= SystemRoutes.LISTING_SDK) Api34.updateListing(context, router)
        reconcile()
    }

    @RequiresApi(34)
    private object Api34 {
        /**
         * The listing for the switcher: our routes in the provider's order, the ones that can't
         * play not selectable ("Can't play here"), "More devices" opening the app. Set on every
         * route update: routes a listing doesn't name are not shown.
         */
        fun updateListing(context: Context, router: MediaRouter2) {
            val ours = router.routes.filter { SystemRoutes.FEATURE_CONNECT in it.features }.map { route ->
                val extras = route.extras
                ListedRoute(
                    routerId = route.id,
                    order = extras?.getInt(SystemRoutes.EXTRA_ORDER, Int.MAX_VALUE) ?: Int.MAX_VALUE,
                    playable = extras?.getBoolean(SystemRoutes.EXTRA_PLAYABLE, true) ?: true,
                    opensApp = extras?.getBoolean(SystemRoutes.EXTRA_OPENS_APP, false) ?: false,
                )
            }
            if (ours.isEmpty()) {
                setListing(router, null)
                return
            }
            val cannotPlay = context.getString(R.string.player_devices_cannot_play)
            val items = SystemRoutes.listing(ours).map { (id, behavior) ->
                val item = RouteListingPreference.Item.Builder(id)
                when (behavior) {
                    ListingBehavior.TRANSFER -> item.setSelectionBehavior(RouteListingPreference.Item.SELECTION_BEHAVIOR_TRANSFER)
                    ListingBehavior.NOT_SELECTABLE -> item.setSelectionBehavior(RouteListingPreference.Item.SELECTION_BEHAVIOR_NONE)
                        .setSubText(RouteListingPreference.Item.SUBTEXT_CUSTOM)
                        .setCustomSubtextMessage(cannotPlay)
                    ListingBehavior.OPENS_APP -> item.setSelectionBehavior(RouteListingPreference.Item.SELECTION_BEHAVIOR_GO_TO_APP)
                }
                item.build()
            }
            setListing(
                router,
                RouteListingPreference.Builder()
                    .setItems(items)
                    .setUseSystemOrdering(false)
                    // "More devices": LinkActivity takes ACTION_TRANSFER_MEDIA to the devices sheet.
                    .setLinkedItemComponentName(ComponentName(context, LinkActivity::class.java))
                    .build(),
            )
        }

        fun setListing(router: MediaRouter2, listing: RouteListingPreference?) {
            // The system checks the linked activity (and rethrows its own failures).
            runCatching { router.setRouteListingPreference(listing) }.onFailure { Log.w(TAG, "Cannot set the route listing", it) }
        }
    }

    companion object {
        private const val TAG = "SystemRouting"

        private val active = MutableStateFlow<SystemRouting?>(null)

        /**
         * The running instance (the playback service's), or null: the route provider publishes
         * routes and takes requests only meanwhile, so a provider the system binds while the service
         * doesn't run does nothing (never creates or starts the engine).
         */
        val running: StateFlow<SystemRouting?> = active.asStateFlow()

        /** [running]'s value: the route provider's volume requests go through it. */
        val current: SystemRouting? get() = active.value
    }
}
