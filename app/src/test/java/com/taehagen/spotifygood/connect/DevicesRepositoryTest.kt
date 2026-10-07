package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesRepositoryTest {
    private fun state(contextUri: String?, trackUri: String = "spotify:track:t") = ResumeState(
        contextUri = contextUri,
        trackUri = trackUri,
        positionMs = 42_000,
        title = "Song",
        artist = null,
        album = null,
        artworkUrl = null,
        durationMs = 200_000,
        isEpisode = false,
    )

    @Test
    fun transferCarriesTheLastSession() {
        val args = DevicesRepository.transferArgs("speaker", play = true, resume = state("spotify:playlist:p"))
        assertEquals("speaker", args["deviceId"]?.jsonPrimitive?.content)
        assertEquals("true", args["play"]?.jsonPrimitive?.content)
        val resume = args["resume"]!!.jsonObject
        assertEquals("spotify:playlist:p", resume["contextUri"]?.jsonPrimitive?.content)
        assertEquals("spotify:track:t", resume["trackUri"]?.jsonPrimitive?.content)
        assertEquals(42_000L, resume["positionMs"]?.jsonPrimitive?.long)
        // modes off unless the session had them
        assertEquals(false, resume["shuffle"]?.jsonPrimitive?.boolean)
        assertEquals(false, resume["smartShuffle"]?.jsonPrimitive?.boolean)
        assertEquals("off", resume["repeat"]?.jsonPrimitive?.content)
    }

    @Test
    fun transferCarriesTheSessionModes() {
        val session = state("spotify:playlist:p").copy(smartShuffle = true, repeat = RepeatMode.TRACK)
        val resume = DevicesRepository.transferArgs("speaker", play = true, resume = session)["resume"]!!.jsonObject
        assertEquals(true, resume["shuffle"]?.jsonPrimitive?.boolean)
        assertEquals(true, resume["smartShuffle"]?.jsonPrimitive?.boolean)
        assertEquals("track", resume["repeat"]?.jsonPrimitive?.content)
    }

    @Test
    fun aDevicePickedWithNothingToPlayStaysPicked() {
        val noResume = DevicesRepository.transferArgs("speaker", play = true, resume = null)
        val pending = DevicesRepository.pendingAfterFailure(NativeErrorCode.NOT_ACTIVE_DEVICE, "speaker", false, noResume)
        assertEquals("speaker", pending)
        // other failures, this phone, or a session that was sent along: nothing pending
        assertNull(DevicesRepository.pendingAfterFailure(NativeErrorCode.NETWORK, "speaker", false, noResume))
        assertNull(DevicesRepository.pendingAfterFailure(NativeErrorCode.NOT_ACTIVE_DEVICE, "phone", true, noResume))
        val withResume = DevicesRepository.transferArgs("speaker", play = true, resume = state("spotify:playlist:p"))
        assertNull(DevicesRepository.pendingAfterFailure(NativeErrorCode.NOT_ACTIVE_DEVICE, "speaker", false, withResume))
    }

    @Test
    fun anActiveDeviceEndsThePendingTarget() {
        assertTrue(DevicesRepository.activeIn(DeviceList(activeDeviceId = "tv")))
        assertFalse(DevicesRepository.activeIn(DeviceList(activeDeviceId = "")))
        assertFalse(DevicesRepository.activeIn(DeviceList()))
        assertTrue(DevicesRepository.activeIn(PlaybackSnapshot(activeDevice = ActiveDeviceRef(id = "me", name = "Phone"))))
        assertFalse(DevicesRepository.activeIn(PlaybackSnapshot.EMPTY))
    }

    @Test
    fun transferWithoutUsableSession() {
        assertNull(DevicesRepository.transferArgs("phone", play = false, resume = null)["resume"])
        assertNull(DevicesRepository.transferArgs("phone", play = true, resume = state(null, trackUri = " "))["resume"])
        // the track as its own "context" is sent without one
        val resume = DevicesRepository.transferArgs("phone", play = true, resume = state("spotify:track:t"))["resume"]!!.jsonObject
        assertFalse(resume.containsKey("contextUri"))
    }

    @Test
    fun thePendingTargetExpiresTenMinutesAfterItWasPicked() {
        var now = 1_000L
        val pending = PendingTarget({ now })
        pending.set("speaker")
        now += PendingTarget.TTL_MS - 1
        assertEquals("speaker", pending.consume())
        assertNull(pending.consume()) // used once

        pending.set("speaker")
        now += PendingTarget.TTL_MS
        assertNull(pending.consume()) // too old, even if nothing cleared it yet
        assertNull(pending.value.value)

        // Picking again starts over; expire() only clears the expired target it names.
        pending.set("speaker")
        now += PendingTarget.TTL_MS / 2
        pending.set("speaker")
        now += PendingTarget.TTL_MS / 2
        assertFalse(pending.expire("speaker"))
        assertTrue(pending.expire("tv"))
        assertEquals("speaker", pending.value.value)
        now += PendingTarget.TTL_MS / 2
        assertTrue(pending.expire("speaker"))
        assertNull(pending.value.value)
    }

    @Test
    fun aPendingTargetNeedsItsDeviceListed() {
        val speaker = com.taehagen.spotifygood.model.ConnectDevice(id = "speaker", name = "Living Room")
        val phone = com.taehagen.spotifygood.model.ConnectDevice(id = "me", name = "Phone", isThisDevice = true)
        val tv = com.taehagen.spotifygood.model.ConnectDevice(id = "tv", name = "TV")
        assertTrue(DevicesRepository.listed("speaker", DeviceList(devices = listOf(phone, speaker))))
        assertFalse(DevicesRepository.listed("speaker", DeviceList(devices = listOf(phone, tv))))
        assertFalse(DevicesRepository.listed("me", DeviceList(devices = listOf(phone, speaker))))
        assertFalse(DevicesRepository.listed("speaker", DeviceList(devices = listOf(speaker.copy(name = " ")))))
    }

    @Test
    fun theExpiryCountsDeepSleep() = runTest {
        // the clock jumps past the expiry while the coroutine time barely moves (the phone slept)
        var now = 0L
        val repo = DevicesRepository(backgroundScope, NativeRpc(Json), NativeEvents(Json), clock = { now })
        runCurrent()
        repo.pick("speaker")
        runCurrent()
        now += PendingTarget.TTL_MS
        advanceTimeBy(DevicesRepository.EXPIRY_CHECK_MS)
        runCurrent()
        assertNull(repo.pendingTarget.value)
        // and right away when the app comes back
        repo.pick("speaker")
        now += PendingTarget.TTL_MS
        repo.expirePendingTarget()
        assertNull(repo.pendingTarget.value)
    }

    @Test
    fun theRepositoryClearsAnExpiredPendingTarget() = runTest {
        val repo = DevicesRepository(backgroundScope, NativeRpc(Json), NativeEvents(Json), clock = { testScheduler.currentTime })
        runCurrent()
        repo.pick("speaker")
        runCurrent()
        assertEquals("speaker", repo.pendingTarget.value)
        advanceTimeBy(PendingTarget.TTL_MS - 1)
        runCurrent()
        assertEquals("speaker", repo.pendingTarget.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(repo.pendingTarget.value)
        assertNull(repo.consumePendingTarget())
    }
}
