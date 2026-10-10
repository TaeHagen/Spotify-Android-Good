package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRoutesTest {
    private val phone = ConnectDevice(id = "phone", name = "Pixel", type = DeviceType.SMARTPHONE, isThisDevice = true)
    private val kitchen = ConnectDevice(id = "kitchen", name = "Kitchen", type = DeviceType.SPEAKER, volume = 32768, brand = "Sonos", model = "One")
    private val tv = ConnectDevice(id = "tv", name = "Living Room", type = DeviceType.CAST_VIDEO, volume = 65535)
    private val laptop = ConnectDevice(id = "laptop", name = "Laptop", type = DeviceType.COMPUTER, canPlay = false)
    private val list = DeviceList(thisDeviceId = "phone", devices = listOf(phone, kitchen, laptop, tv))
    private val track = PlaybackTrack(uri = "spotify:track:a", name = "A")

    private fun remote(id: String, name: String, volume: Int = 0, status: PlaybackStatus = PlaybackStatus.PLAYING) = PlaybackSnapshot(
        source = PlaybackSource.REMOTE,
        status = status,
        track = track,
        activeDevice = ActiveDeviceRef(id, name),
        volume = volume,
    )

    private val local = PlaybackSnapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PLAYING, track = track)

    // ---- device → route --------------------------------------------------------------------------

    @Test
    fun theAccountsDevicesButThisPhoneAreRoutesInTheSheetsOrder() {
        val routes = SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = false)
        assertEquals(listOf("kitchen", "tv"), routes.map { it.id })
        val kitchenRoute = routes.first()
        assertEquals("Kitchen", kitchenRoute.name)
        assertEquals(RouteKind.SPEAKER, kitchenRoute.kind)
        assertEquals("Sonos One", kitchenRoute.description)
        assertEquals(50, kitchenRoute.volume)
        assertFalse("not playing: no volume control", kitchenRoute.volumeAdjustable)
        assertFalse(kitchenRoute.active)
        assertTrue(kitchenRoute.playable)
        assertEquals(RouteKind.TV, routes[1].kind)
    }

    @Test
    fun devicesThatCantPlayAreListedOnlyWhereTheListingCanShowThemSo() {
        assertTrue(SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = false).none { it.id == "laptop" })
        val listed = SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = true).single { it.id == "laptop" }
        assertFalse(listed.playable)
        assertEquals(RouteKind.COMPUTER, listed.kind)
    }

    @Test
    fun theDeviceThatPlaysIsActiveWithTheSnapshotsVolume() {
        val routes = SystemRoutes.routes(list, remote("kitchen", "Kitchen", volume = 65535), loggedIn = true, online = true, listing = false)
        val kitchenRoute = routes.single { it.id == "kitchen" }
        assertTrue(kitchenRoute.active)
        assertEquals(100, kitchenRoute.volume)
        assertTrue(kitchenRoute.volumeAdjustable)
        assertFalse(routes.single { it.id == "tv" }.active)
        // A device without volume control takes none from the switcher either.
        val fixed = list.copy(devices = listOf(phone, kitchen.copy(supportsVolume = false)))
        assertFalse(SystemRoutes.routes(fixed, remote("kitchen", "Kitchen"), loggedIn = true, online = true, listing = false).single().volumeAdjustable)
    }

    @Test
    fun aRestrictedDeviceThatPlaysStaysPickable() {
        val routes = SystemRoutes.routes(list, remote("laptop", "Laptop"), loggedIn = true, online = true, listing = false)
        assertTrue(routes.single { it.id == "laptop" }.playable)
    }

    @Test
    fun thePlayingDeviceIsARouteEvenWhenTheListDoesntNameIt() {
        val routes = SystemRoutes.routes(list, remote("car", "Car"), loggedIn = true, online = true, listing = false)
        val car = routes.last()
        assertEquals("car", car.id)
        assertEquals("Car", car.name)
        assertTrue(car.active && car.playable && car.volumeAdjustable)
    }

    @Test
    fun noRoutesLoggedOutOfflineOrHiddenFromConnect() {
        assertTrue(SystemRoutes.routes(list, remote("kitchen", "Kitchen"), loggedIn = false, online = true, listing = true, moreDevicesName = "More").isEmpty())
        // Offline: the engine lists this phone alone (and a stale list is no use either).
        val offline = DeviceList(thisDeviceId = "phone", devices = listOf(phone))
        assertTrue(SystemRoutes.routes(offline, PlaybackSnapshot.EMPTY, loggedIn = true, online = false, listing = true, moreDevicesName = "More").isEmpty())
        assertTrue(SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = false, listing = true, moreDevicesName = "More").isEmpty())
        // Online with no other device: only "More devices" (to sign one in).
        assertEquals(
            listOf(SystemRoutes.MORE_DEVICES_ID),
            SystemRoutes.routes(offline, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = true, moreDevicesName = "More").map { it.id },
        )
        // Hidden from Connect: a stale cluster without this phone; transfers fail there.
        val hidden = list.copy(devices = listOf(kitchen, tv))
        assertTrue(SystemRoutes.routes(hidden, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = true, moreDevicesName = "More").isEmpty())
        // ... but a device the session shows playing keeps its route (its session goes on).
        assertEquals(listOf("kitchen"), SystemRoutes.routes(hidden, remote("kitchen", "Kitchen"), loggedIn = true, online = true, listing = false).map { it.id })
    }

    @Test
    fun moreDevicesComesLastWithTheListing() {
        val routes = SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = true, moreDevicesName = "More devices")
        val more = routes.last()
        assertEquals(SystemRoutes.MORE_DEVICES_ID, more.id)
        assertTrue(more.opensApp)
        assertFalse(more.playable)
        assertTrue(SystemRoutes.routes(list, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = false, moreDevicesName = "More devices").none { it.opensApp })
    }

    @Test
    fun blankNamesAndThisPhoneUnderAnyIdAreSkipped() {
        val odd = DeviceList(
            thisDeviceId = "phone",
            devices = listOf(phone, ConnectDevice(id = "x", name = "  "), ConnectDevice(id = "phone", name = "Pixel again"), kitchen),
        )
        assertEquals(listOf("kitchen"), SystemRoutes.routes(odd, PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = false).map { it.id })
    }

    @Test
    fun deviceTypesMapToRouteKindsAndPlatformTypes() {
        assertEquals(RouteKind.SPEAKER, SystemRoutes.kindOf(DeviceType.AVR, group = false))
        assertEquals(RouteKind.SPEAKER, SystemRoutes.kindOf(DeviceType.CAST_AUDIO, group = false))
        assertEquals(RouteKind.TV, SystemRoutes.kindOf(DeviceType.STB, group = false))
        assertEquals(RouteKind.COMPUTER, SystemRoutes.kindOf(DeviceType.CHROMEBOOK, group = false))
        assertEquals(RouteKind.CAR, SystemRoutes.kindOf(DeviceType.AUTOMOBILE, group = false))
        assertEquals(RouteKind.GROUP, SystemRoutes.kindOf(DeviceType.SPEAKER, group = true))
        // MediaRoute2Info.TYPE_REMOTE_SPEAKER, TYPE_REMOTE_TV, TYPE_GROUP (API 34).
        assertEquals(1002, SystemRoutes.platformType(RouteKind.SPEAKER, 34))
        assertEquals(1001, SystemRoutes.platformType(RouteKind.TV, 34))
        assertEquals(2000, SystemRoutes.platformType(RouteKind.GROUP, 34))
        // Computers and the like have types from API 35 (TYPE_REMOTE_COMPUTER); unknown before.
        assertEquals(0, SystemRoutes.platformType(RouteKind.COMPUTER, 34))
        assertEquals(1006, SystemRoutes.platformType(RouteKind.COMPUTER, 35))
        assertEquals(1010, SystemRoutes.platformType(RouteKind.SMARTPHONE, 36))
        assertEquals(0, SystemRoutes.platformType(RouteKind.UNKNOWN, 36))
    }

    // ---- active device → session -------------------------------------------------------------------

    @Test
    fun theTargetIsTheRemoteSnapshotsDeviceNeverThisPhone() {
        assertEquals("kitchen", SystemRoutes.target(list, remote("kitchen", "Kitchen"))?.id)
        assertEquals("paused too", "kitchen", SystemRoutes.target(list, remote("kitchen", "Kitchen", status = PlaybackStatus.PAUSED))?.id)
        assertNull(SystemRoutes.target(list, local))
        assertNull(SystemRoutes.target(list, PlaybackSnapshot.EMPTY))
        assertNull(SystemRoutes.target(list, remote("phone", "Pixel")))
    }

    @Test
    fun aSessionSelectsItsDeviceAndCanMoveToTheOtherPlayableOnes() {
        val routes = SystemRoutes.routes(list, remote("kitchen", "Kitchen", volume = 65535), loggedIn = true, online = true, listing = true, moreDevicesName = "More")
        val session = SystemRoutes.session("kitchen", routes)!!
        assertEquals("Kitchen", session.name)
        assertEquals("not the laptop (can't play), not More devices", listOf("tv"), session.transferable)
        assertEquals(100, session.volume)
        assertTrue(session.volumeAdjustable)
        assertNull(SystemRoutes.session(SystemRoutes.MORE_DEVICES_ID, routes))
        assertNull(SystemRoutes.session("gone", routes))
    }

    @Test
    fun aSessionFollowsTheDeviceThatPlays() {
        val routes = SystemRoutes.routes(list, remote("tv", "Living Room"), loggedIn = true, online = true, listing = false)
        assertEquals("playback moved (another Spotify app)", "tv", SystemRoutes.sessionDevice("kitchen", "tv", routes))
        assertEquals("nothing plays elsewhere: kept until released", "kitchen", SystemRoutes.sessionDevice("kitchen", null, routes))
        assertEquals("not a route", "kitchen", SystemRoutes.sessionDevice("kitchen", "nowhere", routes))
    }

    @Test
    fun aSessionKeepsItsRouteWhenTheListLosesIt() {
        val before = SystemRoutes.routes(list, remote("kitchen", "Kitchen", volume = 65535), loggedIn = true, online = true, listing = false)
        // A reconnect: the list is this phone alone and nothing shows as playing for a moment.
        val during = SystemRoutes.routes(DeviceList(thisDeviceId = "phone", devices = listOf(phone)), PlaybackSnapshot.EMPTY, loggedIn = true, online = true, listing = false)
        val kept = SystemRoutes.withSessionRoutes(during, listOf("kitchen"), before)
        val kitchenRoute = kept.single()
        assertEquals("kitchen", kitchenRoute.id)
        assertFalse(kitchenRoute.volumeAdjustable)
        assertEquals(during, SystemRoutes.withSessionRoutes(during, emptyList(), before))
    }

    // ---- pick → action -------------------------------------------------------------------------------

    @Test
    fun aPickTransfersAdoptsOrIsRefused() {
        val routes = SystemRoutes.routes(list, remote("kitchen", "Kitchen"), loggedIn = true, online = true, listing = true, moreDevicesName = "More")
        assertEquals(RoutePick.Transfer("tv"), SystemRoutes.pick("app", "app", "tv", routes, target = "kitchen"))
        assertEquals("plays already: its session at once", RoutePick.Adopt, SystemRoutes.pick("app", "app", "kitchen", routes, target = "kitchen"))
        assertEquals(RoutePick.Reject(Rejection.OTHER_APP), SystemRoutes.pick("other.app", "app", "tv", routes, target = "kitchen"))
        assertEquals(RoutePick.Reject(Rejection.CANNOT_PLAY), SystemRoutes.pick("app", "app", "laptop", routes, target = "kitchen"))
        assertEquals(RoutePick.Reject(Rejection.UNKNOWN_ROUTE), SystemRoutes.pick("app", "app", "gone", routes, target = "kitchen"))
        assertEquals(RoutePick.Reject(Rejection.OPENS_APP), SystemRoutes.pick("app", "app", SystemRoutes.MORE_DEVICES_ID, routes, target = null))
    }

    @Test
    fun failedTransfersAreToldAsTheDevicesSheetTellsThem() {
        fun native(code: String) = NativeException(NativeErrorInfo(code, "boom"))
        assertEquals(PickFailure.NOTHING_TO_PLAY, SystemRoutes.failureOf(native(NativeErrorCode.NOT_ACTIVE_DEVICE)))
        assertEquals(PickFailure.NETWORK, SystemRoutes.failureOf(native(NativeErrorCode.NETWORK)))
        assertEquals(PickFailure.NETWORK, SystemRoutes.failureOf(native(NativeErrorCode.NOT_CONNECTED)))
        assertEquals(PickFailure.OTHER, SystemRoutes.failureOf(native(NativeErrorCode.UNAVAILABLE)))
        assertEquals(PickFailure.OTHER, SystemRoutes.failureOf(IllegalStateException()))
    }

    // ---- the router side ---------------------------------------------------------------------------

    @Test
    fun anotherDevicePlayingWithoutASessionGetsOneOnce() {
        assertEquals(RoutingStep.Adopt("kitchen"), SystemRoutes.next("kitchen", hasSession = false, heldSinceMs = 0, routeKnown = true, attempt = null, nowMs = 1_000))
        assertEquals(
            "the router doesn't know the route (the provider isn't bound)",
            RoutingStep.None,
            SystemRoutes.next("kitchen", hasSession = false, heldSinceMs = 0, routeKnown = false, attempt = null, nowMs = 1_000),
        )
        val tried = AdoptAttempt("kitchen", 1_000)
        assertEquals(RoutingStep.None, SystemRoutes.next("kitchen", false, 0, true, tried, nowMs = 1_000 + SystemRoutes.ADOPT_RETRY_MS - 1))
        assertEquals(RoutingStep.Adopt("kitchen"), SystemRoutes.next("kitchen", false, 0, true, tried, nowMs = 1_000 + SystemRoutes.ADOPT_RETRY_MS))
        assertEquals("another device: at once", RoutingStep.Adopt("tv"), SystemRoutes.next("tv", false, 0, true, tried, nowMs = 1_001))
        assertEquals("a session already", RoutingStep.None, SystemRoutes.next("kitchen", hasSession = true, heldSinceMs = 0, routeKnown = true, attempt = null, nowMs = 1_000))
    }

    @Test
    fun aSessionNoDevicePlaysOnIsReleasedAfterTheGrace() {
        val since = 10_000L
        assertEquals(
            RoutingStep.RecheckAt(since + SystemRoutes.RELEASE_GRACE_MS),
            SystemRoutes.next(null, hasSession = true, heldSinceMs = since, routeKnown = false, attempt = null, nowMs = since + 1),
        )
        assertEquals(RoutingStep.Release, SystemRoutes.next(null, true, since, false, null, nowMs = since + SystemRoutes.RELEASE_GRACE_MS))
        assertEquals(RoutingStep.None, SystemRoutes.next(null, hasSession = false, heldSinceMs = since, routeKnown = false, attempt = null, nowMs = since + 60_000))
    }

    @Test
    fun playbackBackOnThisPhoneReleasesTheSessionAtOnce() {
        val since = 10_000L
        // Remote, then local with the session still there: this phone plays, the chip must say so.
        val back = SystemRoutes.handedBack(local, sawDevicePlay = true)
        assertTrue(back)
        assertEquals(RoutingStep.Release, SystemRoutes.next(null, hasSession = true, heldSinceMs = since, routeKnown = false, attempt = null, nowMs = since + 1, backHere = back))
        // A session just created for a pick, the snapshot still local: the grace applies.
        val fresh = SystemRoutes.handedBack(local, sawDevicePlay = false)
        assertFalse(fresh)
        assertEquals(
            RoutingStep.RecheckAt(since + SystemRoutes.RELEASE_GRACE_MS),
            SystemRoutes.next(null, hasSession = true, heldSinceMs = since, routeKnown = false, attempt = null, nowMs = since + 1, backHere = fresh),
        )
        // Nothing plays anywhere (the device stopped or left): the grace applies too.
        val none = SystemRoutes.handedBack(PlaybackSnapshot.EMPTY, sawDevicePlay = true)
        assertFalse(none)
        assertEquals(
            RoutingStep.RecheckAt(since + SystemRoutes.RELEASE_GRACE_MS),
            SystemRoutes.next(null, hasSession = true, heldSinceMs = since, routeKnown = false, attempt = null, nowMs = since + 1, backHere = none),
        )
        // While a device plays nothing is released, whatever came before.
        assertEquals(RoutingStep.None, SystemRoutes.next("kitchen", hasSession = true, heldSinceMs = since, routeKnown = true, attempt = null, nowMs = since + 1, backHere = false))
    }

    // ---- discovery ------------------------------------------------------------------------------------

    @Test
    fun onlyAnActiveScanForOurRoutesFromAndroid15MeansTheSwitcherIsOpen() {
        val ours = listOf(SystemRoutes.FEATURE_CONNECT, SystemRoutes.FEATURE_LOCAL_PLAYBACK)
        assertTrue(SystemRoutes.switcherOpen(35, activeScan = true, features = ours))
        assertTrue(SystemRoutes.switcherOpen(36, activeScan = true, features = ours))
        assertFalse("passive: nobody looks", SystemRoutes.switcherOpen(35, activeScan = false, features = ours))
        assertFalse("another app's scan", SystemRoutes.switcherOpen(35, activeScan = true, features = listOf("android.media.route.feature.REMOTE_PLAYBACK")))
        // Up to Android 14 SystemUI scans for every media notification while the screen is on.
        assertFalse(SystemRoutes.switcherOpen(34, activeScan = true, features = ours))
        assertFalse(SystemRoutes.switcherOpen(30, activeScan = true, features = ours))
    }

    // ---- listing ----------------------------------------------------------------------------------------

    @Test
    fun theListingKeepsTheProvidersOrderAndTellsHowEachRouteIsPicked() {
        val items = SystemRoutes.listing(
            listOf(
                ListedRoute("p:more", order = 3, playable = false, opensApp = true),
                ListedRoute("p:laptop", order = 1, playable = false, opensApp = false),
                ListedRoute("p:kitchen", order = 0, playable = true, opensApp = false),
                ListedRoute("p:tv", order = 2, playable = true, opensApp = false),
                ListedRoute("p:tv", order = 2, playable = true, opensApp = false),
            ),
        )
        assertEquals(
            listOf(
                "p:kitchen" to ListingBehavior.TRANSFER,
                "p:laptop" to ListingBehavior.NOT_SELECTABLE,
                "p:tv" to ListingBehavior.TRANSFER,
                "p:more" to ListingBehavior.OPENS_APP,
            ),
            items,
        )
    }
}
