package com.taehagen.spotifygood.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Renders the fast scroller (run with `-Pscreenshots`; images in app/build/outputs/roborazzi, not
 * in git): shown after a scroll, dragged with its bubble, and a long list half way down past the
 * loaded rows (placeholders), in the dark and the light theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class FastScrollerScreenshotTest {
    private val names = listOf(
        "Airbag", "Bones", "Creep", "Daydreaming", "Everything In Its Right Place", "Fake Plastic Trees",
        "Glass Eyes", "High and Dry", "Idioteque", "Jigsaw Falling into Place", "Karma Police", "Let Down",
        "Lucky", "Morning Bell", "No Surprises", "Nude", "Optimistic", "Paranoid Android", "Pyramid Song",
        "Reckoner", "Street Spirit", "There There", "Videotape", "Weird Fishes",
    )

    /** 2,000 songs (titles A–Z), the first [loaded] of them loaded. */
    private fun track(index: Int) = Track(
        uri = "spotify:track:$index",
        name = names[(index * names.size) / 2_000] + " ($index)",
        artists = listOf(ArtistRef("spotify:artist:r", "Radiohead")),
        album = AlbumRef("spotify:album:a", "Album"),
    )

    @Composable
    private fun Page(dark: Boolean, firstRow: Int, loaded: Int, drag: Float?, sorted: Boolean) {
        val total = 2_000
        SpotifyGoodTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstRow)
                    val bottom = 72.dp
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottom), modifier = Modifier.fillMaxSize()) {
                        item(key = "header") {
                            Column(Modifier.fillMaxWidth().height(220.dp).padding(16.dp)) {
                                Text("Long playlist", style = MaterialTheme.typography.headlineMedium)
                                Text("2,000 songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items((0 until loaded).toList(), key = { "t$it" }) { index ->
                            TrackRow(track = track(index), onClick = {}, onMoreClick = {})
                        }
                        items(count = total - loaded, key = { "pos:${loaded + it}" }) { PlaceholderTrackRow() }
                    }
                    FastScroller(
                        listState = listState,
                        modifier = Modifier,
                        contentStart = 1,
                        contentCount = total,
                        enabled = true,
                        topPadding = 0.dp,
                        bottomPadding = bottom,
                        label = { index ->
                            if (sorted) fastScrollLetter(track(index).name, Locale.US) else fastScrollPosition(index, total, Locale.US)
                        },
                        previewShown = true,
                        previewDrag = drag,
                    )
                    // The mini player's place: the scroller stays above it.
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(bottom)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text("Mini player") }
                }
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/fast_scroller_$name.png", content = content)

    @Test
    fun shownAfterAScrollDark() = shot("shown_dark") { Page(dark = true, firstRow = 40, loaded = 2_000, drag = null, sorted = false) }

    @Test
    fun shownAfterAScrollLight() = shot("shown_light") { Page(dark = false, firstRow = 40, loaded = 2_000, drag = null, sorted = false) }

    @Test
    fun draggingWithTheLetterBubble() = shot("dragging_letter_dark") { Page(dark = true, firstRow = 0, loaded = 2_000, drag = 0.62f, sorted = true) }

    @Test
    fun draggingWithThePositionBubbleLight() = shot("dragging_position_light") { Page(dark = false, firstRow = 0, loaded = 2_000, drag = 0.35f, sorted = false) }

    @Test
    fun halfWayDownPastTheLoadedRows() = shot("placeholders_dark") { Page(dark = true, firstRow = 0, loaded = 100, drag = 0.5f, sorted = false) }
}
