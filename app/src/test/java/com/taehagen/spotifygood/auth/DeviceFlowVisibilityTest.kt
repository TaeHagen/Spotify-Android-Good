package com.taehagen.spotifygood.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFlowVisibilityTest {
    @Test
    fun pollsOnlyWhileTheScreenIsVisible() {
        assertEquals(DeviceFlowAction.CONTINUE, deviceFlowAction(screenVisible = true, screenGone = false))
        // Hidden (app switched) while a refresh-token login was in progress: paused, resumed when shown.
        assertEquals(DeviceFlowAction.PAUSE, deviceFlowAction(screenVisible = false, screenGone = false))
        // Closed for good (Back): no headless polling.
        assertEquals(DeviceFlowAction.ABANDON, deviceFlowAction(screenVisible = false, screenGone = true))
    }
}
