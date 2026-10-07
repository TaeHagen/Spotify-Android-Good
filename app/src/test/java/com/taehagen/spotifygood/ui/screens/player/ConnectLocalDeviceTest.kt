package com.taehagen.spotifygood.ui.screens.player

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        assertEquals(LocalConnectEvent.Ready("s", "Kitchen"), event)
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
