package com.taehagen.spotifygood.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the swipe-to-queue states of a track row (run with `-Pscreenshots`; images in
 * app/build/outputs/roborazzi): at rest, half way to the threshold (96 dp on this 400 dp screen)
 * and past it, plus an episode row past it, in the dark and the light theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h640dp-xxhdpi", application = Application::class)
class SwipeToQueueScreenshotTest {
    private val track = Track(
        uri = "spotify:track:1",
        name = "Paranoid Android",
        artists = listOf(ArtistRef("spotify:artist:r", "Radiohead")),
        album = AlbumRef("spotify:album:o", "OK Computer"),
        explicit = false,
    )
    private val episode = Episode(
        uri = "spotify:episode:1",
        name = "The making of OK Computer",
        description = "Twenty-five years on, the band looks back at the album.",
        show = ShowRef("spotify:show:s", "Album stories"),
        durationMs = 42 * 60_000L,
        releaseDate = "2024-03-01",
    )

    @Composable
    private fun States(dark: Boolean) {
        SpotifyGoodTheme(darkTheme = dark) {
            Surface {
                Column(Modifier.fillMaxWidth()) {
                    for ((label, offset) in listOf("Rest" to 0f, "Half way (48 dp)" to 48f, "Past the threshold (110 dp)" to 110f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        SwipeToQueueRow(onQueue = {}, modifier = Modifier, previewOffset = offset) { slide ->
                            TrackRow(track = track, onClick = {}, modifier = slide, onMoreClick = {}, swipeToQueue = false)
                        }
                        HorizontalDivider()
                    }
                    Text("Episode, past the threshold", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    SwipeToQueueRow(onQueue = {}, modifier = Modifier, previewOffset = 110f) { slide ->
                        EpisodeRow(episode = episode, onClick = {}, modifier = slide, swipeToQueue = false, onMoreClick = {})
                    }
                }
            }
        }
    }

    @Test
    fun swipeStatesDark() = captureRoboImage("build/outputs/roborazzi/swipe_to_queue_dark.png") { States(dark = true) }

    @Test
    fun swipeStatesLight() = captureRoboImage("build/outputs/roborazzi/swipe_to_queue_light.png") { States(dark = false) }
}
