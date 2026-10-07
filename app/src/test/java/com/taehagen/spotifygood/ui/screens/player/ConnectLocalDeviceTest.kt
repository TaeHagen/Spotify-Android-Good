package com.taehagen.spotifygood.ui.screens.player

import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectLocalDeviceTest {
    private fun native(code: String) = NativeException(NativeErrorInfo(code, "x"))

    @Test
    fun loginAndTransferSucceed() = runTest {
        var transferredTo: String? = null
        val event = connectLocalDevice("s", "Kitchen", login = { "dev-1" }, transfer = { transferredTo = it })
        assertEquals(LocalConnectEvent.Connected("s"), event)
        assertEquals("dev-1", transferredTo)
    }

    @Test
    fun nothingToPlayIsNotAFailure() = runTest {
        val event = connectLocalDevice("s", "Kitchen", login = { "dev-1" }, transfer = { throw native(NativeErrorCode.NOT_ACTIVE_DEVICE) })
        assertEquals(LocalConnectEvent.Ready("s", "Kitchen", selected = false), event)
    }

    @Test
    fun aDevicePickedForTheNextPlayIsReadyAndSelected() = runTest {
        // The repository keeps the device as the pending target after NOT_ACTIVE_DEVICE.
        var pending: String? = null
        val event = connectLocalDevice(
            "s",
            "Kitchen",
            login = { "dev-1" },
            transfer = { id ->
                pending = id
                throw native(NativeErrorCode.NOT_ACTIVE_DEVICE)
            },
            isSelected = { it == pending },
        )
        assertEquals(LocalConnectEvent.Ready("s", "Kitchen", selected = true), event)
    }

    @Test
    fun loginFailureNeverTransfers() = runTest {
        var transferred = false
        val event = connectLocalDevice("s", "Kitchen", login = { throw native(NativeErrorCode.NETWORK) }, transfer = { transferred = true })
        assertEquals(LocalConnectEvent.Failed("s", "Kitchen", network = true), event)
        assertTrue(!transferred)
        val other = connectLocalDevice("s", "Kitchen", login = { throw IllegalStateException() }, transfer = {})
        assertEquals(LocalConnectEvent.Failed("s", "Kitchen", network = false), other)
    }

    @Test
    fun transferFailureIsReportedAsSuch() = runTest {
        val event = connectLocalDevice("s", "Kitchen", login = { "dev-1" }, transfer = { throw native(NativeErrorCode.UNAVAILABLE) })
        assertEquals(LocalConnectEvent.TransferFailed("s", "Kitchen", network = false), event)
        val network = connectLocalDevice("s", "Kitchen", login = { "dev-1" }, transfer = { throw native(NativeErrorCode.NETWORK) })
        assertEquals(LocalConnectEvent.TransferFailed("s", "Kitchen", network = true), network)
    }

    @Test
    fun cancellationPropagates() = runTest {
        try {
            connectLocalDevice("s", "Kitchen", login = { "dev-1" }, transfer = { throw CancellationException("gone") })
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("gone", e.message)
        }
    }
}

class TransferFailureEventTest {
    private fun native(code: String) = NativeException(NativeErrorInfo(code, "x"))

    @Test
    fun aDevicePickedWithNothingToPlayIsSelectedForTheNextPlay() {
        assertEquals(
            DevicesEvent.NothingToPlay("s", "Living Room", isThisDevice = false, selected = true),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = native(NativeErrorCode.NOT_ACTIVE_DEVICE), selected = true),
        )
        // This phone is never a pending target (picking it clears one).
        assertEquals(
            DevicesEvent.NothingToPlay("s", "This phone", isThisDevice = true, selected = false),
            transferFailureEvent("s", "This phone", isThisDevice = true, error = native(NativeErrorCode.NOT_ACTIVE_DEVICE), selected = true),
        )
        // A real failure is reported as one, whatever is pending.
        assertEquals(
            DevicesEvent.TransferFailed("s", "Living Room", network = true),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = native(NativeErrorCode.NETWORK), selected = true),
        )
    }

    @Test
    fun pendingTargetNameComesFromTheDeviceList() {
        val list = DeviceList(
            devices = listOf(ConnectDevice(id = "a", name = "Living Room"), ConnectDevice(id = "b", name = " ")),
        )
        assertEquals("Living Room", pendingTargetName("a", list))
        assertNull("no pending target", pendingTargetName(null, list))
        assertNull("not listed", pendingTargetName("gone", list))
        assertNull("no name to show", pendingTargetName("b", list))
    }

    @Test
    fun nothingToMoveIsNotADeviceFailure() {
        // Nothing playing anywhere and no saved session: the speaker is fine.
        assertEquals(
            DevicesEvent.NothingToPlay("s", "Living Room", isThisDevice = false),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = native(NativeErrorCode.NOT_ACTIVE_DEVICE)),
        )
        assertEquals(
            DevicesEvent.NothingToPlay("s", "This phone", isThisDevice = true),
            transferFailureEvent("s", "This phone", isThisDevice = true, error = native(NativeErrorCode.NOT_ACTIVE_DEVICE)),
        )
    }

    @Test
    fun realFailuresStayFailures() {
        assertEquals(
            DevicesEvent.TransferFailed("s", "Living Room", network = true),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = native(NativeErrorCode.NETWORK)),
        )
        assertEquals(
            DevicesEvent.TransferFailed("s", "Living Room", network = false),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = native(NativeErrorCode.PLAYBACK_REFUSED)),
        )
        assertEquals(
            DevicesEvent.TransferFailed("s", "Living Room", network = false),
            transferFailureEvent("s", "Living Room", isThisDevice = false, error = IllegalStateException()),
        )
    }
}
