package com.taehagen.spotifygood.ui.screens.player

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteActiveDeviceTest {
    private val phone = ConnectDevice(id = "phone", name = "Pixel", isThisDevice = true)
    private val speaker = ConnectDevice(id = "speaker", name = "Kitchen", isActive = true)

    // The cluster still names the paused speaker as the account's active device.
    private val cluster = DeviceList(activeDeviceId = "speaker", thisDeviceId = "phone", devices = listOf(phone, speaker))

    private val track = PlaybackTrack(uri = "spotify:track:a", name = "A")

    @Test
    fun localPlaybackWinsOverTheClustersActiveDevice() {
        // "This phone" kept the offline queue playing here.
        val offlineQueue = PlaybackSnapshot(source = PlaybackSource.LOCAL, offline = true, status = PlaybackStatus.PLAYING, track = track)
        assertNull(remoteActiveDevice(cluster, offlineQueue))
        assertNull("paused here too", remoteActiveDevice(cluster, offlineQueue.copy(status = PlaybackStatus.PAUSED)))
        val spirc = offlineQueue.copy(offline = false)
        assertNull(remoteActiveDevice(cluster, spirc))
        // DevicesUiState shows this phone as the current device.
        assertNull(DevicesUiState(devices = cluster, snapshot = offlineQueue).remoteActive)
    }

    @Test
    fun withoutLocalPlaybackTheClustersActiveDeviceIsCurrent() {
        assertEquals("speaker", remoteActiveDevice(cluster, PlaybackSnapshot.EMPTY)?.id)
        val stopped = PlaybackSnapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.STOPPED, track = track)
        assertEquals("nothing loaded here", "speaker", remoteActiveDevice(cluster, stopped)?.id)
        val remote = PlaybackSnapshot(
            source = PlaybackSource.REMOTE,
            status = PlaybackStatus.PAUSED,
            track = track,
            activeDevice = ActiveDeviceRef("speaker", "Kitchen"),
        )
        assertEquals("speaker", remoteActiveDevice(cluster, remote)?.id)
    }

    @Test
    fun aRemoteSnapshotNamesItsDeviceWhenTheListDoesNot() {
        val list = DeviceList(thisDeviceId = "phone", devices = listOf(phone))
        val remote = PlaybackSnapshot(
            source = PlaybackSource.REMOTE,
            status = PlaybackStatus.PLAYING,
            track = track,
            activeDevice = ActiveDeviceRef("tv", "Living Room TV"),
        )
        assertEquals("Living Room TV", remoteActiveDevice(list, remote)?.name)
        // This phone being the cluster's active device is not a remote one.
        assertNull(remoteActiveDevice(DeviceList(activeDeviceId = "phone", thisDeviceId = "phone", devices = listOf(phone)), PlaybackSnapshot.EMPTY))
    }
}
