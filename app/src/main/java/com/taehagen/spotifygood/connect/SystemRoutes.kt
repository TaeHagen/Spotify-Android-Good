package com.taehagen.spotifygood.connect

import android.annotation.SuppressLint
import android.media.MediaRoute2Info
import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.VolumeMath

/** The icon class of a route: the platform route type from API 34 ([SystemRoutes.platformType]). */
internal enum class RouteKind { SPEAKER, TV, COMPUTER, TABLET, SMARTPHONE, GAME_CONSOLE, CAR, SMARTWATCH, GROUP, UNKNOWN }

/** One entry of Android's output switcher for this app's media: a Connect device, or [opensApp]. */
internal data class ConnectRoute(
    /** The Connect device id (the route's id within the provider). */
    val id: String,
    val name: String,
    val kind: RouteKind,
    val description: String?,
    /** Its volume, 0..100. */
    val volume: Int,
    /** The switcher may set the volume: only the active device's, when it takes one. */
    val volumeAdjustable: Boolean,
    /** The device playing what the media session shows ([SystemRoutes.target]). */
    val active: Boolean,
    /** A pick moves playback there (the devices sheet's enabled rows). */
    val playable: Boolean,
    /** Not a device: "More devices", which opens the app's devices sheet (API 34+, a listing item). */
    val opensApp: Boolean = false,
)

/** The routing session on [deviceId] (its selected route) and the devices it can move to. */
internal data class SessionSpec(
    val deviceId: String,
    val name: String,
    val transferable: List<String>,
    val volume: Int,
    val volumeAdjustable: Boolean,
)

/** What a pick in the output switcher (a session request for a route) does. */
internal sealed interface RoutePick {
    /** The device plays already: the session is created at once, nothing moves. */
    data object Adopt : RoutePick

    /** Playback moves there first, as a pick in the devices sheet does. */
    data class Transfer(val deviceId: String) : RoutePick

    data class Reject(val reason: Rejection) : RoutePick
}

internal enum class Rejection {
    /** Another app's router asked: the routes are this app's media only. */
    OTHER_APP,

    /** Not a published route (any more). */
    UNKNOWN_ROUTE,

    /** A device that can't play (the sheet's "Can't play here"). */
    CANNOT_PLAY,

    /** "More devices": its listing item opens the app; a pick of it means the item was ignored. */
    OPENS_APP,
}

/** Why a transfer for a pick failed (the provider's failure reason, the app's message). */
internal enum class PickFailure { NETWORK, NOTHING_TO_PLAY, OTHER }

/** What the router side does next for the media session's routing session ([SystemRoutes.next]). */
internal sealed interface RoutingStep {
    data object None : RoutingStep

    /** Ask for a session on the playing device's route (`MediaRouter2.transferTo`). */
    data class Adopt(val deviceId: String) : RoutingStep

    /** Release our session: nothing plays on another device any more. */
    data object Release : RoutingStep

    /** Nothing now; look again at [atMs] (the end of the release grace). */
    data class RecheckAt(val atMs: Long) : RoutingStep
}

/** One of our routes as the app's router knows it, for the listing preference ([SystemRoutes.listing]). */
internal data class ListedRoute(
    /** The router's id of the route (not the device id: the system prefixes it). */
    val routerId: String,
    val order: Int,
    val playable: Boolean,
    val opensApp: Boolean,
)

/** How the output switcher treats a listed route (API 34+). */
internal enum class ListingBehavior {
    /** A pick transfers (the session request). */
    TRANSFER,

    /** Shown, "Can't play here", not selectable. */
    NOT_SELECTABLE,

    /** A pick opens the app (the listing's linked activity): "More devices". */
    OPENS_APP,
}

/** A session request the router sent for [deviceId] at [atMs] ([RoutingStep.Adopt]). */
internal data class AdoptAttempt(val deviceId: String, val atMs: Long)

/**
 * Spotify Connect devices as routes of Android's system output switcher (docs/ARCHITECTURE.md §8,
 * "System output switcher"; pure, JVM-testable). The provider service
 * ([ConnectRouteProviderService]) publishes [routes] and keeps its routing sessions on [target];
 * the router side ([com.taehagen.spotifygood.playback.SystemRouting]) asks for a session when
 * another device plays without one ([next]) and names it to the media session.
 */
internal object SystemRoutes {
    /** The feature of every route the app publishes; nobody else asks for it. */
    const val FEATURE_CONNECT = "com.taehagen.spotifygood.route.feature.SPOTIFY_CONNECT"

    /**
     * The (hidden) feature of the system's own routes (this phone's speaker, wired, Bluetooth, from
     * Android 11 on): asking for it lists them next to the Connect devices while another device
     * plays, without other providers' live-audio routes.
     */
    const val FEATURE_LOCAL_PLAYBACK = "android.media.route.feature.LOCAL_PLAYBACK"

    /** Route id of "More devices" (Connect ids are hex). */
    const val MORE_DEVICES_ID = "more-devices"

    /** Route extras: the Connect device id, the place in the list, playable, opens the app. */
    const val EXTRA_DEVICE_ID = "com.taehagen.spotifygood.route.extra.DEVICE_ID"
    const val EXTRA_ORDER = "com.taehagen.spotifygood.route.extra.ORDER"
    const val EXTRA_PLAYABLE = "com.taehagen.spotifygood.route.extra.PLAYABLE"
    const val EXTRA_OPENS_APP = "com.taehagen.spotifygood.route.extra.OPENS_APP"

    /**
     * The output switcher integration: MediaRoute2ProviderService and MediaRouter2 exist from
     * Android 11, but Android 11 binds an enabled provider for good ([RouteProviderRule.MIN_SDK]).
     */
    const val MIN_SDK = RouteProviderRule.MIN_SDK

    /**
     * Route types, the listing preference (order, "Can't play here", "More devices") and the
     * visibility restriction (Android 14).
     */
    const val LISTING_SDK = 34

    /** The route types of computers, tablets, phones, consoles, cars and watches (Android 15). */
    const val MORE_TYPES_SDK = 35

    /**
     * From Android 15 only the output switcher dialog asks for an active scan; before, SystemUI
     * asked for one for every media notification while the screen was on.
     */
    const val SWITCHER_SCAN_SDK = 35

    /**
     * A session that no device plays on any more is released after this (from its creation or the
     * last time a device played): a transfer's cluster update, a reconnect or a switch between two
     * speakers can leave a short gap.
     */
    const val RELEASE_GRACE_MS = 8_000L

    /** A session request for the same device that failed is tried again only after this. */
    const val ADOPT_RETRY_MS = 30_000L

    /**
     * The device another device's playback runs on, as the media session shows it (`DeviceInfo`
     * remote): the remote snapshot's active device, never this phone. Null otherwise.
     */
    fun target(devices: DeviceList, snapshot: PlaybackSnapshot): ActiveDeviceRef? {
        if (snapshot.source != PlaybackSource.REMOTE) return null
        val ref = snapshot.activeDevice ?: return null
        val thisId = devices.thisDeviceId ?: devices.devices.firstOrNull { it.isThisDevice }?.id
        if (ref.id.isBlank() || ref.id == thisId) return null
        return ref
    }

    /**
     * The routes for the output switcher, in the devices sheet's order: the account's Connect
     * devices but this phone (none logged out, while the session isn't [online], or while hidden
     * from Connect, when the list has no entry for this phone and is a stale cluster), the device
     * that plays even when the list doesn't name it, and, with [listing] (API 34+,
     * [moreDevicesName] given), "More devices" last (signing a speaker in needs the session).
     * Devices that can't play are left out without [listing], which alone can show them as not
     * selectable.
     */
    fun routes(
        devices: DeviceList,
        snapshot: PlaybackSnapshot,
        loggedIn: Boolean,
        online: Boolean,
        listing: Boolean,
        moreDevicesName: String? = null,
    ): List<ConnectRoute> {
        if (!loggedIn) return emptyList()
        val target = target(devices, snapshot)
        val thisId = devices.thisDeviceId ?: devices.devices.firstOrNull { it.isThisDevice }?.id
        // Online with Spotify Connect: the list has this phone (it alone while offline).
        val live = online && devices.devices.any { it.isThisDevice }
        val out = ArrayList<ConnectRoute>()
        if (live) {
            for (device in devices.devices.distinctBy { it.id }) {
                if (device.isThisDevice || device.id == thisId || device.id.isBlank() || device.name.isBlank()) continue
                val route = route(device, target, snapshot)
                if (route.playable || listing) out += route
            }
        }
        if (target != null && target.name.isNotBlank() && out.none { it.id == target.id }) {
            val volumeSupported = devices.devices.firstOrNull { it.id == target.id }?.supportsVolume != false
            out += ConnectRoute(
                id = target.id,
                name = target.name.trim(),
                kind = kindOf(target.type, group = false),
                description = null,
                volume = VolumeMath.connectToPercent(snapshot.volume),
                volumeAdjustable = volumeSupported,
                active = true,
                playable = true,
            )
        }
        if (listing && live && !moreDevicesName.isNullOrBlank()) {
            out += ConnectRoute(
                id = MORE_DEVICES_ID,
                name = moreDevicesName,
                kind = RouteKind.UNKNOWN,
                description = null,
                volume = 0,
                volumeAdjustable = false,
                active = false,
                playable = false,
                opensApp = true,
            )
        }
        return out
    }

    private fun route(device: ConnectDevice, target: ActiveDeviceRef?, snapshot: PlaybackSnapshot): ConnectRoute {
        val active = device.id == target?.id
        // The snapshot carries the playing device's latest volume (as the sheet's current device card).
        val volume = if (active) snapshot.volume else device.volume
        return ConnectRoute(
            id = device.id,
            name = device.name.trim(),
            kind = kindOf(device.type, device.isGroup),
            description = listOfNotNull(device.brand, device.model).joinToString(" ").trim().ifBlank { null },
            volume = VolumeMath.connectToPercent(volume),
            volumeAdjustable = active && device.supportsVolume,
            active = active,
            playable = device.canPlay || active,
        )
    }

    /**
     * [fresh] plus the routes of the sessions' devices it lost ([sessionDevices], from [previous]):
     * a session's selected route stays published until the session goes (a reconnect empties the
     * list for a moment). Kept ones take no volume.
     */
    fun withSessionRoutes(fresh: List<ConnectRoute>, sessionDevices: Collection<String>, previous: List<ConnectRoute>): List<ConnectRoute> {
        val missing = sessionDevices.toSet().filter { id -> fresh.none { it.id == id } }
            .mapNotNull { id -> previous.firstOrNull { it.id == id && !it.opensApp } }
        return if (missing.isEmpty()) fresh else fresh + missing.map { it.copy(volumeAdjustable = false, active = false) }
    }

    /**
     * The device a session shows next: the one that plays when it is a route (playback moved,
     * also by another Spotify app), else the one it showed ([current]: the router side releases
     * it once no device plays).
     */
    fun sessionDevice(current: String, target: String?, routes: List<ConnectRoute>): String =
        target?.takeIf { id -> routes.any { it.id == id && !it.opensApp } } ?: current

    /** The session on [deviceId]: its name and volume, and the other playable devices to move to. */
    fun session(deviceId: String, routes: List<ConnectRoute>): SessionSpec? {
        val route = routes.firstOrNull { it.id == deviceId && !it.opensApp } ?: return null
        return SessionSpec(
            deviceId = deviceId,
            name = route.name,
            transferable = routes.filter { it.id != deviceId && it.playable && !it.opensApp }.map { it.id },
            volume = route.volume,
            volumeAdjustable = route.volumeAdjustable,
        )
    }

    /** What a session request from [callerPackage] for [routeId] does, with [target] playing. */
    fun pick(callerPackage: String, ownPackage: String, routeId: String, routes: List<ConnectRoute>, target: String?): RoutePick {
        if (callerPackage != ownPackage) return RoutePick.Reject(Rejection.OTHER_APP)
        val route = routes.firstOrNull { it.id == routeId } ?: return RoutePick.Reject(Rejection.UNKNOWN_ROUTE)
        return when {
            route.opensApp -> RoutePick.Reject(Rejection.OPENS_APP)
            route.id == target -> RoutePick.Adopt
            !route.playable -> RoutePick.Reject(Rejection.CANNOT_PLAY)
            else -> RoutePick.Transfer(route.id)
        }
    }

    /** A failed transfer as the devices sheet tells it ([com.taehagen.spotifygood.ui.screens.player.transferFailureEvent]). */
    fun failureOf(error: Throwable): PickFailure = when {
        error is NativeException && error.code == NativeErrorCode.NOT_ACTIVE_DEVICE -> PickFailure.NOTHING_TO_PLAY
        error is NativeException && error.isNetwork -> PickFailure.NETWORK
        else -> PickFailure.OTHER
    }

    /**
     * The router side's next step. [target]: the device that plays ([SystemRoutes.target]);
     * [hasSession] / [heldSinceMs]: our routing session and the later of its creation and the last
     * time a device played; [routeKnown]: the router knows the target's route (the provider is
     * bound); [attempt]: the last session request; [backHere]: the session showed a device playing
     * and playback is on this phone now ([handedBack]).
     *
     * Another device plays without a session: ask for one on its route, once per device (again
     * after [ADOPT_RETRY_MS] when it failed). Playback came back to this phone from the session's
     * device: release it at once, the chip names this phone again. No device plays any more for
     * another reason (nothing plays anywhere, a gap of a transfer or a reconnect, a session just
     * created whose device the snapshot doesn't show yet): release it after [RELEASE_GRACE_MS].
     */
    fun next(
        target: String?,
        hasSession: Boolean,
        heldSinceMs: Long,
        routeKnown: Boolean,
        attempt: AdoptAttempt?,
        nowMs: Long,
        backHere: Boolean = false,
    ): RoutingStep = when {
        target != null && hasSession -> RoutingStep.None
        target != null -> {
            val waiting = attempt != null && attempt.deviceId == target && nowMs - attempt.atMs < ADOPT_RETRY_MS
            if (routeKnown && !waiting) RoutingStep.Adopt(target) else RoutingStep.None
        }
        !hasSession -> RoutingStep.None
        backHere -> RoutingStep.Release
        nowMs - heldSinceMs >= RELEASE_GRACE_MS -> RoutingStep.Release
        else -> RoutingStep.RecheckAt(heldSinceMs + RELEASE_GRACE_MS)
    }

    /**
     * The listing preference's items: [routes] in the provider's order (the devices sheet's), each
     * with how the switcher treats a pick. Every route must be named: the switcher hides routes of
     * the app a listing leaves out.
     */
    fun listing(routes: List<ListedRoute>): List<Pair<String, ListingBehavior>> =
        routes.distinctBy { it.routerId }.sortedBy { it.order }.map { route ->
            route.routerId to when {
                route.opensApp -> ListingBehavior.OPENS_APP
                route.playable -> ListingBehavior.TRANSFER
                else -> ListingBehavior.NOT_SELECTABLE
            }
        }

    /**
     * Playback came back to this phone from the device a routing session showed: the snapshot
     * shows playback here (`source` local), and the session [sawDevicePlay] (a device played while
     * it existed). A session just created for a pick, whose device the snapshot doesn't show yet,
     * hasn't: it keeps the release grace.
     */
    fun handedBack(snapshot: PlaybackSnapshot, sawDevicePlay: Boolean): Boolean =
        sawDevicePlay && snapshot.source == PlaybackSource.LOCAL

    /**
     * Whether a discovery preference means the output switcher dialog is open for this app: an
     * active scan that asks for our routes, from Android 15 ([SWITCHER_SCAN_SDK]; before, every
     * media notification asked for one while the screen was on).
     */
    fun switcherOpen(sdk: Int, activeScan: Boolean, features: Collection<String>): Boolean =
        sdk >= SWITCHER_SCAN_SDK && activeScan && FEATURE_CONNECT in features

    /** The icon class of a Connect device type. */
    fun kindOf(type: DeviceType, group: Boolean): RouteKind = if (group) {
        RouteKind.GROUP
    } else {
        when (type) {
            DeviceType.SPEAKER, DeviceType.CAST_AUDIO, DeviceType.AUDIO_DONGLE, DeviceType.AVR -> RouteKind.SPEAKER
            DeviceType.TV, DeviceType.CAST_VIDEO, DeviceType.STB -> RouteKind.TV
            DeviceType.COMPUTER, DeviceType.CHROMEBOOK -> RouteKind.COMPUTER
            DeviceType.TABLET -> RouteKind.TABLET
            DeviceType.SMARTPHONE -> RouteKind.SMARTPHONE
            DeviceType.GAME_CONSOLE -> RouteKind.GAME_CONSOLE
            DeviceType.AUTOMOBILE -> RouteKind.CAR
            DeviceType.SMARTWATCH -> RouteKind.SMARTWATCH
            DeviceType.UNKNOWN -> RouteKind.UNKNOWN
        }
    }

    /**
     * The platform route type (`MediaRoute2Info.TYPE_*`, set from API 34; the system picks the
     * icon by it). Types the release doesn't have are unknown (a generic device icon). The
     * constants are compile-time ints, inlined: this stays testable on the JVM.
     */
    @SuppressLint("InlinedApi")
    fun platformType(kind: RouteKind, sdk: Int): Int = when (kind) {
        RouteKind.SPEAKER -> MediaRoute2Info.TYPE_REMOTE_SPEAKER
        RouteKind.TV -> MediaRoute2Info.TYPE_REMOTE_TV
        RouteKind.GROUP -> MediaRoute2Info.TYPE_GROUP
        RouteKind.UNKNOWN -> MediaRoute2Info.TYPE_UNKNOWN
        else -> if (sdk < MORE_TYPES_SDK) {
            MediaRoute2Info.TYPE_UNKNOWN
        } else {
            when (kind) {
                RouteKind.COMPUTER -> MediaRoute2Info.TYPE_REMOTE_COMPUTER
                RouteKind.TABLET -> MediaRoute2Info.TYPE_REMOTE_TABLET
                RouteKind.SMARTPHONE -> MediaRoute2Info.TYPE_REMOTE_SMARTPHONE
                RouteKind.GAME_CONSOLE -> MediaRoute2Info.TYPE_REMOTE_GAME_CONSOLE
                RouteKind.CAR -> MediaRoute2Info.TYPE_REMOTE_CAR
                else -> MediaRoute2Info.TYPE_REMOTE_SMARTWATCH
            }
        }
    }
}
