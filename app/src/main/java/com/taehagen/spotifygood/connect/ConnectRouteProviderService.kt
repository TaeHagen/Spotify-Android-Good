package com.taehagen.spotifygood.connect

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.playback.SystemRouting
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.screens.player.DevicePicks
import com.taehagen.spotifygood.ui.screens.player.message
import com.taehagen.spotifygood.ui.screens.player.transferFailureEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The account's Spotify Connect devices as routes of Android's system output switcher (the "This
 * phone" chip of the media controls, its output dialog, the volume panel's media output;
 * docs/ARCHITECTURE.md §8, "System output switcher"). A framework `MediaRoute2ProviderService`
 * (API 30): only the system binds it (every binder call checks the caller's uid), and only this
 * app's media sees its routes: they carry a feature only this app's router asks for
 * ([SystemRoutes.FEATURE_CONNECT]), from API 34 they are visible to this app alone, and a session
 * request from another package is refused.
 *
 * * Routes: [SystemRoutes.routes] from [DevicesRepository.devices] and the playback snapshot (what
 *   is already there, pushed by the engine: the provider never scans, polls or starts the engine),
 *   published only when they change. From Android 15 the output switcher opening (an active scan
 *   asking for our routes) refreshes the device list once, as the devices sheet does on opening.
 * * A pick (a session request from the app's router, which the system sends for a pick in the
 *   switcher, or [SystemRouting]'s request for the device that plays) moves playback there with
 *   [DevicesRepository.transferTo], the devices sheet's transfer (the stored session, the pending
 *   target, the same messages), then reports the session; a device that plays already gets its
 *   session at once. A pick between devices while a session exists is a transfer within it.
 * * Sessions follow the device that plays ([SystemRoutes.sessionDevice]): its name, its volume,
 *   the other devices to move to. [SystemRouting] releases them once no device plays.
 * * Volume: the playing device's, through the media session player (the remote volume path of the
 *   volume keys).
 *
 * Enabled while an account is logged in, from Android 12 ([RouteProviderSwitch], [RouteProviderRule]);
 * it lists routes and takes requests only while the playback service runs ([SystemRouting.running]):
 * bound otherwise it publishes nothing and touches nothing. Main thread.
 */
@RequiresApi(31)
class ConnectRouteProviderService : MediaRoute2ProviderService() {
    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val picks = Mutex()

    /** The routes from the current state, the published ones, and every route seen (for sessions). */
    private var fresh: List<ConnectRoute> = emptyList()
    private var routes: List<ConnectRoute> = emptyList()
    private val known = HashMap<String, ConnectRoute>()
    private var published = false

    /** The device the media session shows playing elsewhere ([SystemRoutes.target]). */
    private var target: String? = null

    private class Session(
        var deviceId: String,
        /** A pick moved playback to [deviceId]: shown until the snapshot follows, or this time. */
        var pinnedUntil: Long,
    ) {
        /** What the system was told last; null until the session is created. */
        var spec: SessionSpec? = null
    }

    private val sessions = LinkedHashMap<String, Session>()
    private var switcherOpen = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        graph = (application as App).graph
        val moreDevices = if (Build.VERSION.SDK_INT >= SystemRoutes.LISTING_SDK) getString(R.string.system_route_more_devices) else null
        scope.launch {
            SystemRouting.running.flatMapLatest { routing ->
                if (routing == null) {
                    // The system binds an enabled provider by its own rules, also while the playback
                    // service doesn't run (screen on with a media control on Android 12–14, another
                    // foreground app's router): nothing to list then, and nothing of the app is
                    // created or started (no engine, no repository).
                    flowOf(null)
                } else {
                    // The playback service made these (its player and router use them).
                    combine(
                        combine(graph.devices.devices, graph.playback.snapshot, ::Pair),
                        graph.engine.isLoggedIn,
                        graph.engine.isOnline,
                        ::Triple,
                    )
                }
            }.collect { inputs ->
                val (state, loggedIn, online) = inputs ?: Triple(null, false, false)
                // Logged out or no router: no routes and no sessions (the router lets its own go too).
                if (!loggedIn) sessions.keys.toList().forEach(::release)
                target = state?.let { (devices, snapshot) -> SystemRoutes.target(devices, snapshot)?.id }
                fresh = state?.let { (devices, snapshot) ->
                    SystemRoutes.routes(devices, snapshot, loggedIn, online, listing = moreDevices != null, moreDevicesName = moreDevices)
                }.orEmpty()
                update()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        super.onDestroy()
    }

    // ---- routes and sessions --------------------------------------------------------------------

    /** Publishes [fresh] (with the sessions' routes) if it changed, and the sessions on [target]. */
    private fun update() {
        fresh.forEach { known[it.id] = it }
        val next = SystemRoutes.withSessionRoutes(fresh, sessions.values.map { it.deviceId }, known.values.toList())
        if (next != routes || !published) {
            routes = next
            published = true
            notifyRoutes(next.mapIndexed(::routeInfo))
        }
        val now = SystemClock.elapsedRealtime()
        for ((id, session) in sessions) {
            if (session.deviceId == target) session.pinnedUntil = 0
            val pinned = now < session.pinnedUntil
            session.deviceId = SystemRoutes.sessionDevice(session.deviceId, target.takeUnless { pinned }, routes)
            publish(id, session)
        }
    }

    private fun publish(id: String, session: Session) {
        if (session.spec == null) return // not created yet
        val spec = SystemRoutes.session(session.deviceId, routes) ?: return
        if (spec == session.spec) return
        session.spec = spec
        notifySessionUpdated(sessionInfo(id, spec))
    }

    private fun routeInfo(order: Int, route: ConnectRoute): MediaRoute2Info {
        val extras = Bundle().apply {
            putString(SystemRoutes.EXTRA_DEVICE_ID, route.id)
            putInt(SystemRoutes.EXTRA_ORDER, order)
            putBoolean(SystemRoutes.EXTRA_PLAYABLE, route.playable)
            putBoolean(SystemRoutes.EXTRA_OPENS_APP, route.opensApp)
        }
        val builder = MediaRoute2Info.Builder(route.id, route.name)
            .addFeature(SystemRoutes.FEATURE_CONNECT)
            .setDescription(route.description)
            .setVolumeHandling(if (route.volumeAdjustable) MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE else MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
            .setVolumeMax(VOLUME_MAX)
            .setVolume(route.volume.coerceIn(0, VOLUME_MAX))
            .setConnectionState(if (route.active) MediaRoute2Info.CONNECTION_STATE_CONNECTED else MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
            // The output dialog says "Active" for a route an app uses.
            .setClientPackageName(if (route.active) packageName else null)
            .setExtras(extras)
        if (Build.VERSION.SDK_INT >= SystemRoutes.LISTING_SDK) Api34.typeAndRestrict(builder, route.kind)
        return builder.build()
    }

    @RequiresApi(34)
    private object Api34 {
        fun typeAndRestrict(builder: MediaRoute2Info.Builder, kind: RouteKind) {
            builder.setType(SystemRoutes.platformType(kind, Build.VERSION.SDK_INT))
                // This app's media only (the publisher sees its own routes; SystemUI lists them for it).
                .setVisibilityRestricted(emptySet())
        }
    }

    private fun sessionInfo(id: String, spec: SessionSpec): RoutingSessionInfo =
        RoutingSessionInfo.Builder(id, packageName)
            .setName(spec.name)
            .addSelectedRoute(spec.deviceId)
            .apply { spec.transferable.forEach { addTransferableRoute(it) } }
            .setVolumeHandling(if (spec.volumeAdjustable) MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE else MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
            .setVolumeMax(VOLUME_MAX)
            .setVolume(spec.volume.coerceIn(0, VOLUME_MAX))
            .build()

    private fun createSession(requestId: Long, deviceId: String, picked: Boolean) {
        if (destroyed) return
        // A new id per session: the system ignores a second session under an id it still knows.
        val id = UUID.randomUUID().toString()
        val pinnedUntil = if (picked && deviceId != target) SystemClock.elapsedRealtime() + PIN_MS else 0
        val session = Session(deviceId, pinnedUntil)
        sessions[id] = session
        // Its device's route stays published while it lasts (a list that lost it meanwhile).
        update()
        val spec = SystemRoutes.session(session.deviceId, routes)
        if (spec == null) {
            sessions.remove(id)
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        session.spec = spec
        notifySessionCreated(requestId, sessionInfo(id, spec))
    }

    private fun release(sessionId: String) {
        if (sessions.remove(sessionId) != null) notifySessionReleased(sessionId)
    }

    /**
     * Moves playback to [deviceId] as the devices sheet does, then runs [done]; on failure tells
     * the system (the switcher shows the pick failed) and the app (the sheet's message). The
     * transfer runs in the app's scope: the system may unbind this service before it ends.
     */
    private fun transfer(requestId: Long, deviceId: String, done: () -> Unit) {
        val name = (routes.firstOrNull { it.id == deviceId } ?: known[deviceId])?.name ?: deviceId
        DevicePicks.mark()
        graph.appScope.launch {
            val error = try {
                picks.withLock { graph.devices.transferTo(deviceId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            withContext(Dispatchers.Main) {
                if (error == null) {
                    done()
                    return@withContext
                }
                Log.w(TAG, "Transfer to a picked device failed", error)
                if (!destroyed) notifyRequestFailed(requestId, reasonOf(SystemRoutes.failureOf(error)))
                // Nothing to play: the device is the pending target, as after a pick in the sheet.
                val selected = graph.devices.pendingTarget.value == deviceId
                transferFailureEvent(sheet = "", deviceName = name, isThisDevice = false, error = error, selected = selected)
                    .message()
                    ?.let { BackgroundMessages.post(getString(it.res, *it.args.toTypedArray())) }
            }
        }
    }

    // ---- MediaRoute2ProviderService ---------------------------------------------------------------

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) {
        when (val pick = SystemRoutes.pick(packageName, this.packageName, routeId, routes, target)) {
            RoutePick.Adopt -> createSession(requestId, routeId, picked = false)
            is RoutePick.Transfer -> transfer(requestId, pick.deviceId) { createSession(requestId, pick.deviceId, picked = true) }
            is RoutePick.Reject -> {
                Log.i(TAG, "Session request refused: ${pick.reason}")
                notifyRequestFailed(requestId, reasonOf(pick.reason))
            }
        }
    }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        val session = sessions[sessionId]
        if (session == null) {
            notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
            return
        }
        when (val pick = SystemRoutes.pick(packageName, packageName, routeId, routes, target)) {
            RoutePick.Adopt -> {
                session.deviceId = routeId
                publish(sessionId, session)
            }
            is RoutePick.Transfer -> transfer(requestId, pick.deviceId) {
                val moved = sessions[sessionId] ?: return@transfer
                moved.deviceId = pick.deviceId
                if (pick.deviceId != target) moved.pinnedUntil = SystemClock.elapsedRealtime() + PIN_MS
                publish(sessionId, moved)
            }
            is RoutePick.Reject -> notifyRequestFailed(requestId, reasonOf(pick.reason))
        }
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        // The router let it go (playback came back here, or no device plays). Nothing is paused:
        // the device plays on until someone moves or stops it.
        release(sessionId)
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) = setVolume(requestId, routeId, volume)

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) =
        setVolume(requestId, sessions[sessionId]?.deviceId, volume)

    /** Connect sets the volume of the device that plays only. */
    private fun setVolume(requestId: Long, deviceId: String?, volume: Int) {
        val route = routes.firstOrNull { it.id == deviceId }
        if (deviceId == null || deviceId != target || route?.volumeAdjustable != true) {
            notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
            return
        }
        val routing = SystemRouting.current
        if (routing == null) {
            notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
            return
        }
        // The session player's remote volume (its in-flight target, the volume keys' base).
        routing.setVolume(volume.coerceIn(0, VOLUME_MAX))
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) {
        // Spotify Connect plays on one device at a time.
        notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
    }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) {
        notifyRequestFailed(requestId, REASON_INVALID_COMMAND)
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        val open = SystemRoutes.switcherOpen(Build.VERSION.SDK_INT, preference.shouldPerformActiveScan(), preference.preferredFeatures)
        // Only for a running playback service's online session: a bound provider starts nothing.
        val online = SystemRouting.current != null && graph.engineIfCreated()?.isOnline?.value == true
        if (open && !switcherOpen && online) {
            // The user looks at the switcher: the list again, as the devices sheet fetches it on
            // opening (one request, debounced in the engine). Nothing else is ever scanned here.
            graph.appScope.launch {
                try {
                    graph.devices.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Device refresh failed", e)
                }
            }
        }
        switcherOpen = open
    }

    private companion object {
        const val TAG = "ConnectRoutes"
        const val VOLUME_MAX = 100

        /** How long a picked device stays a session's device while the playback snapshot catches up. */
        const val PIN_MS = 10_000L

        fun reasonOf(rejection: Rejection): Int = when (rejection) {
            Rejection.OTHER_APP, Rejection.OPENS_APP -> MediaRoute2ProviderService.REASON_REJECTED
            Rejection.UNKNOWN_ROUTE, Rejection.CANNOT_PLAY -> MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE
        }

        fun reasonOf(failure: PickFailure): Int = when (failure) {
            PickFailure.NETWORK -> MediaRoute2ProviderService.REASON_NETWORK_ERROR
            PickFailure.NOTHING_TO_PLAY -> MediaRoute2ProviderService.REASON_REJECTED
            PickFailure.OTHER -> MediaRoute2ProviderService.REASON_UNKNOWN_ERROR
        }
    }
}
