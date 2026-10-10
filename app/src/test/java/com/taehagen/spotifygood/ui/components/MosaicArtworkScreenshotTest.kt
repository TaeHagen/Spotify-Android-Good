package com.taehagen.spotifygood.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the mosaic of a playlist without an image (run with `-Pscreenshots`; images in
 * app/build/outputs/roborazzi, not in git): four solid tiles laid out as [MosaicArtwork] lays its
 * covers, on a magenta backdrop, so a gap between tiles would show as a magenta line — at sizes
 * that split evenly and oddly into pixels, rounded and square.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-xxhdpi", application = Application::class)
class MosaicArtworkScreenshotTest {
    private val tiles = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFFDD835))

    @Composable
    private fun Mosaic(size: Dp, shape: Shape = RoundedCornerShape(4.dp)) {
        MosaicLayout(Modifier.size(size).clip(shape)) {
            tiles.forEach { Box(Modifier.background(it)) }
        }
    }

    @Composable
    private fun Page() {
        SpotifyGoodTheme(darkTheme = true) {
            Column(
                Modifier
                    .background(Color.Magenta)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The playlist page header (rounded), then list-row sizes (even and odd pixels).
                Mosaic(200.dp, RoundedCornerShape(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Mosaic(56.dp)
                    Mosaic(57.dp)
                    Mosaic(47.dp, RectangleShape)
                    Mosaic(33.dp, RectangleShape)
                }
            }
        }
    }

    @Test
    fun aMosaicHasNoGaps() = captureRoboImage("build/outputs/roborazzi/playlist_mosaic.png") { Page() }
}
