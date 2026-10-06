package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo

/**
 * Whether a full-screen player surface currently covers most of the window. Back handlers of the
 * player overlays are only enabled while it does, so a host that keeps a surface composed while it
 * is collapsed or off-screen never swallows the back gesture.
 */
@Stable
internal class WindowCoverage(private val windowInfo: WindowInfo) {
    var coversWindow by mutableStateOf(true)
        private set

    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates ->
        val windowHeight = windowInfo.containerSize.height
        coversWindow = windowHeight <= 0 || coordinates.boundsInWindow().height >= windowHeight * 0.5f
    }
}

@Composable
internal fun rememberWindowCoverage(): WindowCoverage {
    val windowInfo = LocalWindowInfo.current
    return remember(windowInfo) { WindowCoverage(windowInfo) }
}
