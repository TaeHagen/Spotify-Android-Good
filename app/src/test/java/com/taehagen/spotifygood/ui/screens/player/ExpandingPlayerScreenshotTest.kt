package com.taehagen.spotifygood.ui.screens.player

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.components.PlaylistAddPrompt
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalPageCovered
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.PageBackHandler
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The expanding player under Robolectric (run with `-Pscreenshots`; images in
 * app/build/outputs/roborazzi, not in git).
 *
 * Frames at fixed progress values: the art must travel from the mini player's thumbnail to Now
 * Playing's slot, the mini player's content fade out early and Now Playing's fade in late, the
 * navigation bar slide away. Gestures: real touch events through the sheet (drag, fling, slow
 * release, nested scroll, a tap) and on Now Playing's seek bar and volume slider, each checked and
 * captured at the end. Also Back (plain and predictive) against the pages' own handlers, and the
 * art's first frame where it appears. Fake state only: a generated cover through a fake image
 * loader, no network, no view models.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ExpandingPlayerScreenshotTest {
    private val frames = listOf(0f, 0.15f, 0.35f, 0.5f, 0.75f, 1f)

    /** Where the fake loader says each cover came from (a memory-cache hit never fades in). */
    private var coverSource = DataSource.MEMORY_CACHE

    /** The covers under [SLOW_COVER_URL]: downloads held until a test lets them go. */
    private lateinit var slowCovers: SlowCovers

    @Before
    fun fakeImages() {
        val cover = coverBitmap()
        slowCovers = SlowCovers(Bitmap.createScaledBitmap(cover, 640, 640, true))
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(CoverInterceptor(cover) { coverSource })
                    add(slowCovers)
                }
                // Coil's engine (slow covers only) on the main looper the tests drive.
                .fetcherCoroutineContext(Dispatchers.Main.immediate)
                .decoderCoroutineContext(Dispatchers.Main.immediate)
                .build()
        }
    }

    @After
    fun resetImages() {
        SingletonImageLoader.reset()
    }

    // ---- Frames ---------------------------------------------------------------------------------

    @Test
    fun phoneDark() = frames.forEach { p -> shot("dark_${percent(p)}") { Harness(dark = true, progress = p) } }

    @Test
    fun phoneLight() = frames.forEach { p -> shot("light_${percent(p)}") { Harness(dark = false, progress = p) } }

    // Titles that fit: an overflowing one starts a marquee, whose endless frames make every
    // settle very slow to render here.

    @Test
    fun connectDevice() = listOf(0f, 0.5f, 1f).forEach { p ->
        shot("remote_dark_${percent(p)}") { Harness(dark = true, progress = p, remote = true, title = SHORT_TITLE) }
    }

    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(1.6f)
        listOf(0f, 0.5f, 1f).forEach { p ->
            shot("font160_light_${percent(p)}") { Harness(dark = false, progress = p, title = SHORT_TITLE) }
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun landscape() = listOf(0f, 0.5f, 1f).forEach { p ->
        shot("landscape_dark_${percent(p)}") { Harness(dark = true, progress = p) }
    }

    // ---- Gestures -------------------------------------------------------------------------------

    @Test
    fun theDraggedPlayerFollowsTheFinger() = gesture("held", expanded = false) { finger, probe, dp ->
        val sheet = probe.sheet!!
        val card = sheet.collapsedBounds
        finger.down(card.center)
        finger.moveTo(card.center - Offset(0f, 300 * dp), durationMs = 500)
        finger.hold(100)
        // The surface's top edge moved with the finger, less (at most) the touch slop the drag
        // starts after.
        val moved = card.top - sheet.surfaceBounds(sheet.progress).top
        val slop = ViewConfiguration.get(finger.view.context).scaledTouchSlop
        assertTrue("moved $moved", moved >= 300 * dp - slop - 1f && moved <= 300 * dp + 1f)
        finger.up()
    }

    @Test
    fun aFlingUpOnTheMiniPlayerExpands() = gesture("fling_up", expanded = false) { finger, probe, dp ->
        val card = probe.sheet!!.collapsedBounds
        finger.swipe(card.center, card.center - Offset(0f, 200 * dp), durationMs = 100)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        assertEquals(listOf(true), probe.settled)
    }

    @Test
    fun aShortSlowDragSettlesBackDocked() = gesture("short_slow", expanded = false) { finger, probe, dp ->
        val card = probe.sheet!!.collapsedBounds
        finger.swipe(card.center, card.center - Offset(0f, 150 * dp), durationMs = 700, holdMs = 200)
        assertEquals(0f, probe.sheet!!.progress, 0.001f)
        assertEquals(listOf(false), probe.settled)
    }

    @Test
    fun aLongSlowDragExpands() = gesture("long_slow", expanded = false) { finger, probe, _ ->
        val card = probe.sheet!!.collapsedBounds
        finger.swipe(card.center, Offset(card.center.x, card.top * 0.35f), durationMs = 900, holdMs = 200)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        assertEquals(listOf(true), probe.settled)
    }

    @Test
    fun aTapOnTheMiniPlayerExpands() = gesture("tap", expanded = false) { finger, probe, _ ->
        finger.tap(probe.sheet!!.collapsedBounds.center)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
    }

    @Test
    fun aFlingDownOnTheArtCollapses() = gesture("fling_down", expanded = true) { finger, probe, dp ->
        val art = probe.sheet!!.largeArtwork
        finger.swipe(art.center, art.center + Offset(0f, 200 * dp), durationMs = 100)
        assertEquals(0f, probe.sheet!!.progress, 0.001f)
        assertEquals(listOf(false), probe.settled)
    }

    @Test
    fun aDragDownOnASliderLeavesThePlayerOpen() = gesture("slider", expanded = true) { finger, probe, dp ->
        val at = seekBarPoint(probe.seekBar, 0.5f, dp)
        finger.swipe(at, at + Offset(0f, 250 * dp), durationMs = 100)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        assertEquals(0, probe.scroll!!.value)
        assertTrue(probe.settled.isEmpty())
        assertTrue(probe.seeks.isEmpty())
    }

    @Test
    fun aSlowDragDownOnTheVolumeSliderLeavesThePlayerOpen() = gesture("volume_down", expanded = true) { finger, probe, dp ->
        val at = probe.volume.center
        // 2 dp steps: the slop is crossed on one of them, here before the sheet or the scroll.
        finger.swipe(at, at + Offset(0f, 60 * dp), durationMs = 600, steps = 30)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        assertEquals(0, probe.scroll!!.value)
        assertTrue(probe.settled.isEmpty())
        assertTrue(probe.volumes.isEmpty())
    }

    @Test
    fun aSidewaysDragOnTheSeekBarSeeks() = gesture("seek_drag", expanded = true) { finger, probe, dp ->
        val bar = probe.seekBar
        val from = seekBarPoint(bar, 0.2f, dp)
        val to = from + Offset(60 * dp, 0f)
        // 30 steps of 2 dp, each well under the touch slop.
        finger.swipe(from, to, durationMs = 600, steps = 30)
        // The thumb follows from the press, less the slop the drag starts after.
        val slop = ViewConfiguration.get(finger.view.context).scaledTouchSlop
        val seek = probe.seeks.single()
        assertTrue("seek $seek", seek in seekAt(bar, to.x - slop - 2 * dp, dp)..seekAt(bar, to.x + 2 * dp, dp))
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        assertEquals(0, probe.scroll!!.value)
        assertTrue(probe.settled.isEmpty())
    }

    @Test
    fun aTapThatJittersOnTheSeekBarSeeks() = gesture("seek_tap", expanded = true) { finger, probe, dp ->
        val bar = probe.seekBar
        val at = seekBarPoint(bar, 0.75f, dp)
        finger.down(at)
        finger.moveTo(at + Offset(1f, 1f), durationMs = 32, steps = 1)
        finger.up()
        assertEquals(seekAt(bar, at.x, dp).toFloat(), probe.seeks.single().toFloat(), SEEK_DURATION_MS * 0.01f)
    }

    @Test
    fun aSidewaysDragOnTheVolumeSliderSetsTheVolume() = gesture("volume_drag", expanded = true) { finger, probe, dp ->
        val row = probe.volume
        val from = Offset(row.left + row.width * 0.4f, row.center.y)
        finger.swipe(from, from + Offset(60 * dp, 0f), durationMs = 600, steps = 30)
        val volumes = probe.volumes
        // Followed the finger: many rising values, from above the starting volume.
        assertTrue("volumes $volumes", volumes.size > 5 && volumes.first() > VOLUME && volumes.last() > volumes.first())
        assertEquals(volumes.sorted(), volumes)
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
    }

    // ---- Back -----------------------------------------------------------------------------------

    @Test
    fun backCollapsesNowPlayingAboveAPageAddedLater() = back(guarded = false) {
        // The player (and its Back) came first, a page with a Back of its own later (a search
        // query typed after the mini player appeared); then Now Playing opens over it.
        page.value = true
        frames(100)
        open.value = true
        frames()
        assertEquals(1f, probe.sheet!!.progress, 0.001f)
        dispatcher.onBackPressed()
        frames()
        assertFalse(open.value)
        assertEquals(0f, probe.sheet!!.progress, 0.001f)
        assertEquals(0, probe.pageBacks)
        // Collapsed, the page has Back again.
        dispatcher.onBackPressed()
        frames(100)
        assertEquals(1, probe.pageBacks)
    }

    @Test
    fun aPageComposedUnderNowPlayingLeavesBackToIt() = back(guarded = true) {
        open.value = true
        frames()
        // Composed while covered, so its handler is newer than the player's.
        page.value = true
        frames(100)
        dispatcher.onBackPressed()
        frames()
        assertFalse(open.value)
        assertEquals(0, probe.pageBacks)
        dispatcher.onBackPressed()
        frames(100)
        assertEquals(1, probe.pageBacks)
    }

    @Test
    fun predictiveBackShrinksThePlayerThenSpringsBackOrCollapses() = back(guarded = true) {
        page.value = true
        open.value = true
        frames()
        val sheet = probe.sheet!!
        dispatcher.dispatchOnBackStarted(backEvent(0f))
        dispatcher.dispatchOnBackProgressed(backEvent(1f))
        frames(100)
        assertEquals(1f - PlayerMotion.BACK_PREVIEW_RANGE, sheet.progress, 0.01f)
        dispatcher.dispatchOnBackCancelled()
        frames()
        assertTrue(open.value)
        assertEquals(1f, sheet.progress, 0.001f)
        dispatcher.dispatchOnBackStarted(backEvent(0f))
        dispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        frames(100)
        dispatcher.onBackPressed()
        frames()
        assertFalse(open.value)
        assertEquals(0f, sheet.progress, 0.001f)
        assertEquals(0, probe.pageBacks)
    }

    // ---- Art ------------------------------------------------------------------------------------

    @Test
    fun artLoadedOutOfSightShowsAtOnceWhenItAppears() {
        // Fresh decodes, which Coil fades in from the painter's first draw.
        coverSource = DataSource.DISK
        val probe = Probe()
        val cover = mutableStateOf(COVER_URL)
        launch({ Harness(dark = true, progress = 0f, cover = { cover.value }, probe = probe) }) { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val sheet = probe.sheet!!
                // A new cover while docked: the moving art loads it out of sight and has it on
                // the first frame it shows (not the grey placeholder fading into it).
                cover.value = "$COVER_URL?2"
                runFrames(view, 300)
                moveSheet(sheet, 0.5f)
                assertShowsCover(view, "moving art, expanding") { sheet.artworkBounds(0.5f).center }
                // Another while expanded: the moving art, then the thumbnail (not even placed
                // above p 0.3) load it out of sight, and have it as the player comes down.
                moveSheet(sheet, 1f)
                runFrames(view, 300)
                cover.value = "$COVER_URL?3"
                runFrames(view, 300)
                moveSheet(sheet, 0.5f)
                assertShowsCover(view, "moving art, collapsing") { sheet.artworkBounds(0.5f).center }
                moveSheet(sheet, 0f)
                assertShowsCover(view, "thumbnail") { sheet.miniArtwork.center }
                // One that arrives where its copy is on screen still fades in: loaded before any
                // draw, its first frame is the placeholder the fade starts from, and the cover
                // once the fade's 200 ms (Coil's clock, real time) have passed.
                cover.value = "$COVER_URL?4"
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                assertShowsCover(view, "thumbnail as its fade starts", cover = false) { sheet.miniArtwork.center }
                Thread.sleep(250)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                assertShowsCover(view, "thumbnail after its fade") { sheet.miniArtwork.center }
            }
        }
    }

    @Test
    fun aCoverStillDownloadingAsThePlayerExpandsIsOnNowPlayingWhenItLands() {
        val probe = Probe()
        val cover = mutableStateOf(COVER_URL)
        launch({ Harness(dark = true, progress = 0f, cover = { cover.value }, probe = probe) }) { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val sheet = probe.sheet!!
                // A new cover on a slow network: the thumbnail and the moving art start downloading it.
                cover.value = SLOW_COVER_URL
                runFrames(view, 100)
                val first = slowCovers.downloads.toList()
                assertTrue(first.isNotEmpty())
                // The player starts to expand: Now Playing composes and starts a download of its own.
                moveSheet(sheet, 0.5f)
                runFrames(view, 100)
                val started = slowCovers.downloads.size
                assertTrue("Now Playing's own download", started > first.size)
                // The first downloads finish mid-flight. Now Playing's copy takes the cover from the
                // memory cache (no new download) and drops its own.
                first.forEach { it.done.complete(Unit) }
                runFrames(view, 100)
                assertEquals(started, slowCovers.downloads.size)
                assertTrue(slowCovers.downloads.drop(first.size).all { it.dropped })
                // Landed: Now Playing's art shows the cover from its first frame, not its placeholder.
                moveSheet(sheet, 1f)
                assertShowsCover(view, "Now Playing's art as the player lands") { sheet.largeArtwork.center }
            }
        }
    }

    @Test
    fun scrolledContentScrollsBackBeforeThePlayerCollapses() = gesture("scrolled", expanded = true) { finger, probe, dp ->
        val sheet = probe.sheet!!
        val scroll = probe.scroll!!
        val middle = sheet.fullBounds.center
        // Up: the content scrolls (the player is already expanded).
        finger.swipe(middle, middle - Offset(0f, 300 * dp), durationMs = 700, holdMs = 200)
        val scrolled = scroll.value
        assertTrue(scrolled > 200 * dp)
        assertEquals(1f, sheet.progress, 0.001f)
        // A shorter drag down only scrolls back.
        finger.swipe(middle, middle + Offset(0f, 150 * dp), durationMs = 700, holdMs = 200)
        assertEquals(1f, sheet.progress, 0.001f)
        assertTrue(scroll.value in 1 until scrolled)
        // A long fast one reaches the top, then takes the player down.
        finger.swipe(middle - Offset(0f, 250 * dp), middle + Offset(0f, 250 * dp), durationMs = 150)
        assertEquals(0, scroll.value)
        assertEquals(0f, sheet.progress, 0.001f)
        assertEquals(listOf(false), probe.settled)
    }

    // ---- Harnesses ------------------------------------------------------------------------------

    private fun percent(p: Float) = "p" + (p * 100).toInt().toString().padStart(3, '0')

    /** A point on the seek bar's slider, [fraction] across: the slider is the bar's top 48 dp (its touch size). */
    private fun seekBarPoint(bar: Rect, fraction: Float, dp: Float) = Offset(bar.left + bar.width * fraction, bar.top + 24 * dp)

    /** The seek a thumb at window x [x] stands for (the 16 dp thumb travels between its half widths). */
    private fun seekAt(bar: Rect, x: Float, dp: Float): Long {
        val thumb = 16 * dp
        val fraction = ((x - bar.left - thumb / 2) / (bar.width - thumb)).coerceIn(0f, 1f)
        return (fraction * SEEK_DURATION_MS).toLong()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    /** Puts the player at [p] at once, so the next draw is the first one at p (nothing animates). */
    private fun moveSheet(sheet: PlayerSheetState, p: Float) {
        sheet.draggable.dispatchRawDelta((1f - p) * sheet.collapsedBounds.top - sheet.draggable.offset)
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Draws [view] once and checks that [point] (in its coordinates) shows the cover's middle, or
     * ([cover] false) doesn't: the grey placeholder, where a fade is starting. [point] is read
     * after the draw, whose layout pass may have just placed Now Playing's art slot (the moving
     * art's target).
     */
    private fun assertShowsCover(view: View, what: String, cover: Boolean = true, point: () -> Offset) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val at = point()
        val pixel = bitmap.getPixel(at.x.roundToInt(), at.y.roundToInt())
        val actual = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
        // The gradient's middle in coverBitmap.
        val expected = listOf(166, 92, 96)
        val showsCover = actual.zip(expected).all { (a, e) -> abs(a - e) <= 24 }
        assertTrue("$what shows $actual at $at", showsCover == cover)
    }

    /**
     * Composes [content] in an activity, runs 800 ms of frames (the layout callbacks settle, the
     * sheet moves) and hands over the activity. Captures go straight to the decor view, not through
     * an Espresso idle wait.
     */
    private fun launch(content: @Composable () -> Unit, block: (ActivityScenario<ComponentActivity>) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent(content = content)
                runFrames(it.window.decorView, 800)
            }
            block(scenario)
        }
    }

    private fun capture(scenario: ActivityScenario<ComponentActivity>, name: String) =
        scenario.onActivity { it.window.decorView.captureRoboImage("build/outputs/roborazzi/expanding_player_$name.png") }

    private fun shot(name: String, content: @Composable () -> Unit) = launch(content) { capture(it, name) }

    private fun gesture(name: String, expanded: Boolean, block: (Finger, Probe, Float) -> Unit) {
        val probe = Probe()
        launch({ GestureHarness(expanded, probe) }) { scenario ->
            lateinit var view: View
            var density = 1f
            scenario.onActivity {
                view = it.window.decorView
                density = it.resources.displayMetrics.density
            }
            block(Finger(view), probe, density)
            capture(scenario, "gesture_$name")
        }
    }

    /** A [BackHarness] running in an activity, with its back dispatcher. */
    private class BackRig(
        val activity: ComponentActivity,
        val probe: Probe,
        val open: MutableState<Boolean>,
        val page: MutableState<Boolean>,
    ) {
        val dispatcher get() = activity.onBackPressedDispatcher

        fun frames(ms: Long = 1_500) = runFrames(activity.window.decorView, ms)
    }

    private fun back(guarded: Boolean, block: BackRig.() -> Unit) {
        val probe = Probe()
        val open = mutableStateOf(false)
        val page = mutableStateOf(false)
        launch({ BackHarness(probe, open, page, guarded) }) { scenario ->
            scenario.onActivity { BackRig(it, probe, open, page).block() }
        }
    }

    @Composable
    private fun Harness(
        dark: Boolean,
        progress: Float,
        remote: Boolean = false,
        title: String = LONG_TITLE,
        cover: () -> String = { COVER_URL },
        probe: Probe? = null,
    ) {
        SpotifyGoodTheme(darkTheme = dark) {
            val sheet = rememberPlayerSheetStateInternal(initiallyExpanded = false)
            SideEffect { probe?.sheet = sheet }
            LaunchedEffect(sheet) {
                // Put the player at [progress] once the dock holds the card's measured height: an
                // earlier change of the anchors would snap the resting sheet back to its target.
                snapshotFlow {
                    val card = sheet.collapsedBounds
                    card.top > 0f && sheet.miniHeight > 0 && abs(card.height - sheet.miniHeight) < 1f &&
                        !sheet.draggable.offset.isNaN()
                }.first { it }
                sheet.draggable.dispatchRawDelta((1f - progress) * sheet.collapsedBounds.top - sheet.draggable.offset)
            }
            val artworkColor = remember { mutableStateOf(CoverColor) }
            val snapshot = snapshot(remote, title)
            val indicator = if (remote) DeviceIndicator.Remote("Living Room", DeviceType.SPEAKER) else DeviceIndicator.None
            Box(Modifier.fillMaxSize()) {
                Shell(sheet)
                ExpandingPlayerLayout(
                    sheet = sheet,
                    artwork = cover(),
                    artworkColor = artworkColor,
                    mini = {
                        PlayerSurfaceTheme {
                            MiniPlayerBar(
                                snapshot = snapshot,
                                liked = true,
                                indicator = indicator,
                                artwork = cover(),
                                position = flowOf(POSITION_MS),
                                initialPositionMs = { POSITION_MS },
                                commands = NoCommands,
                                onExpand = {},
                                onDevices = {},
                            )
                        }
                    },
                    nowPlaying = {
                        PlayerSurfaceTheme {
                            Box(Modifier.fillMaxSize()) {
                                NowPlayingBody(
                                    snapshot = snapshot,
                                    track = snapshot.track!!,
                                    artwork = cover(),
                                    liked = true,
                                    indicator = indicator,
                                    remoteVolumeSupported = remote,
                                    lyrics = null,
                                    lyricsUnavailable = false,
                                    lyricsFallbackColor = artworkColor,
                                    sleepTimer = SleepTimerState.Off,
                                    position = remember { mutableLongStateOf(POSITION_MS) },
                                    podcastSpeed = 1f,
                                    podcastSpeedInEffect = 1f,
                                    commands = NoCommands,
                                    navigator = NoNavigator,
                                    onCollapse = {},
                                    onShowSleepTimer = {},
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    /** What the gesture tests look at. */
    private class Probe {
        var sheet: PlayerSheetState? = null
        var scroll: ScrollState? = null
        var seekBar = Rect.Zero
        var volume = Rect.Zero
        val settled = mutableListOf<Boolean>()
        val seeks = mutableListOf<Long>()
        val volumes = mutableListOf<Int>()
        var pageBacks = 0
    }

    /**
     * The real sheet and surface with a stand-in Now Playing of known geometry: room for a top bar,
     * the art slot, the real seek bar and volume slider (kept local, as Now Playing wraps them) and
     * long content that scrolls. A tap on the card expands, as the shell does.
     */
    @Composable
    private fun GestureHarness(expanded: Boolean, probe: Probe) {
        SpotifyGoodTheme(darkTheme = true) {
            val sheet = rememberPlayerSheetStateInternal(initiallyExpanded = expanded)
            val scope = rememberCoroutineScope()
            SideEffect {
                probe.sheet = sheet
                sheet.onSettle = { probe.settled += it }
            }
            Box(Modifier.fillMaxSize()) {
                Shell(sheet)
                ExpandingPlayerLayout(
                    sheet = sheet,
                    artwork = COVER_URL,
                    artworkColor = remember { mutableStateOf(CoverColor) },
                    mini = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .clickable { scope.launch { sheet.animateTo(expanded = true) } },
                        )
                    },
                    nowPlaying = {
                        val scroll = rememberScrollState()
                        SideEffect { probe.scroll = scroll }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(horizontal = 24.dp),
                        ) {
                            Spacer(Modifier.height(80.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .onGloballyPositioned(sheet::updateLargeArtwork),
                            )
                            PlayerSeekBar(
                                position = remember { mutableLongStateOf(POSITION_MS) },
                                durationMs = SEEK_DURATION_MS,
                                enabled = true,
                                onSeek = { probe.seeks += it },
                                modifier = Modifier
                                    .padding(vertical = 24.dp)
                                    .keepDragsLocal()
                                    .onGloballyPositioned { probe.seekBar = it.boundsInWindow() },
                            )
                            VolumeSlider(
                                volume = VOLUME,
                                onVolumeChange = { probe.volumes += it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .keepDragsLocal()
                                    .onGloballyPositioned { probe.volume = it.boundsInWindow() },
                            )
                            Spacer(Modifier.height(1_500.dp))
                        }
                    },
                )
            }
        }
    }

    /**
     * The real sheet with Now Playing's Back ([PlayerBackHandler]) following [open], as the shell
     * wires them, and once [page] is set a page under the player with a Back of its own: a plain
     * handler, or ([guarded]) the pages' [PageBackHandler], which the shell turns off while covered.
     */
    @Composable
    private fun BackHarness(probe: Probe, open: MutableState<Boolean>, page: State<Boolean>, guarded: Boolean) {
        SpotifyGoodTheme(darkTheme = true) {
            val sheet = rememberPlayerSheetStateInternal(initiallyExpanded = false)
            SideEffect { probe.sheet = sheet }
            LaunchedEffect(sheet) {
                snapshotFlow { open.value }.collectLatest { if (it != sheet.isExpanded) sheet.animateTo(it) }
            }
            CompositionLocalProvider(LocalPageCovered provides open.value) {
                Box(Modifier.fillMaxSize()) {
                    Shell(sheet)
                    if (page.value) {
                        if (guarded) PageBackHandler(enabled = true) { probe.pageBacks++ } else BackHandler { probe.pageBacks++ }
                    }
                    ExpandingPlayerLayout(
                        sheet = sheet,
                        artwork = COVER_URL,
                        artworkColor = remember { mutableStateOf(CoverColor) },
                        mini = { Box(Modifier.fillMaxWidth().height(58.dp)) },
                        nowPlaying = { Box(Modifier.fillMaxSize()) },
                    )
                    PlayerBackHandler(sheet = sheet, enabled = open.value, onCollapse = { open.value = false })
                }
            }
        }
    }

    /** Touches on the window, the (virtual) clock advancing between them so velocities are real. */
    private class Finger(val view: View) {
        private var downTime = 0L
        private var at = Offset.Zero

        fun down(position: Offset) {
            downTime = SystemClock.uptimeMillis()
            at = position
            send(MotionEvent.ACTION_DOWN)
        }

        fun moveTo(target: Offset, durationMs: Long, steps: Int = 12) {
            val start = at
            for (step in 1..steps) {
                runFrames(view, durationMs / steps)
                at = lerp(start, target, step / steps.toFloat())
                send(MotionEvent.ACTION_MOVE)
            }
        }

        fun hold(ms: Long) = runFrames(view, ms)

        /** Lifts the finger and lets the player settle. */
        fun up() {
            send(MotionEvent.ACTION_UP)
            runFrames(view, 1_500)
        }

        fun swipe(from: Offset, to: Offset, durationMs: Long, holdMs: Long = 0, steps: Int = 12) {
            down(from)
            moveTo(to, durationMs, steps)
            if (holdMs > 0) hold(holdMs)
            up()
        }

        fun tap(position: Offset) {
            down(position)
            hold(50)
            up()
        }

        private fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, at.x, at.y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /** The shell around the player: a page, the dock, a navigation bar sliding out with p. */
    @Composable
    private fun Shell(sheet: PlayerSheetState) {
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Page()
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        PlayerDockSpace(sheet)
                    }
                }
                ShortNavigationBar(
                    containerColor = colors.surfaceContainerLowest,
                    modifier = Modifier.graphicsLayer { translationY = size.height * sheet.progress },
                ) {
                    listOf("Home" to Icons.Rounded.Home, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)
                        .forEachIndexed { index, (label, icon) ->
                            ShortNavigationBarItem(
                                selected = index == 0,
                                onClick = {},
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(label) },
                            )
                        }
                }
            }
        }
    }

    @Composable
    private fun Page() {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Good evening", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            repeat(4) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) { column ->
                        Row(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(colors.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(56.dp).background(colors.primary.copy(alpha = 0.3f + 0.1f * column), RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text("Mix ${row * 2 + column + 1}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text("Recently played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Box(Modifier.size(120.dp).background(colors.surfaceContainerHighest, RoundedCornerShape(6.dp))) }
            }
        }
    }

    private fun snapshot(remote: Boolean, title: String) = PlaybackSnapshot(
        source = if (remote) PlaybackSource.REMOTE else PlaybackSource.LOCAL,
        activeDevice = if (remote) ActiveDeviceRef("speaker", "Living Room", DeviceType.SPEAKER) else null,
        status = PlaybackStatus.PLAYING,
        positionMs = POSITION_MS,
        durationMs = 318_000,
        context = PlaybackContext("spotify:album:inrainbows", "In Rainbows", ContextType.ALBUM),
        track = PlaybackTrack(
            uri = "spotify:track:weirdfishes",
            name = title,
            artists = listOf(ArtistRef("spotify:artist:radiohead", "Radiohead")),
            album = AlbumRef("spotify:album:inrainbows", "In Rainbows"),
            durationMs = 318_000,
        ),
        volume = 40_000,
    )

    /** A 300 px cover with a diagonal gradient and a disc, so placement and cropping show. */
    private fun coverBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 300f, 300f, 0xFF5B2A86.toInt(), 0xFFF28F3B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 300f, 300f, paint)
        paint.shader = null
        paint.color = 0xFF1B998B.toInt()
        canvas.drawCircle(210f, 90f, 60f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(30f, 240f, 150f, 260f, paint)
        return bitmap
    }

    /**
     * Every request gets the cover, by default as a memory-cache hit (what a copy of the player's
     * shared request gets once another has loaded it). Slow covers go on to Coil's engine.
     */
    private class CoverInterceptor(private val cover: Bitmap, private val source: () -> DataSource) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            if (chain.request.data.toString().startsWith(SLOW_COVER_URL)) {
                chain.proceed()
            } else {
                SuccessResult(image = cover.asImage(), request = chain.request, dataSource = source())
            }
    }

    /**
     * Downloads of the covers under [SLOW_COVER_URL], each held until the test completes it. They
     * run inside Coil's engine, so its memory cache works as in the app: a request that misses it
     * makes a download of its own, as Coil joins none in flight. [cover] is 640 px, the size the
     * player asks for, so the cached bitmap serves its later requests.
     */
    private class SlowCovers(private val cover: Bitmap) : Fetcher.Factory<coil3.Uri> {
        class Download {
            val done = CompletableDeferred<Unit>()

            /** Cancelled before it was done (its request was replaced or went away). */
            var dropped = false
        }

        val downloads = mutableListOf<Download>()

        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (!data.toString().startsWith(SLOW_COVER_URL)) {
                null
            } else {
                Fetcher {
                    val download = Download().also { downloads += it }
                    try {
                        download.done.await()
                    } catch (e: CancellationException) {
                        download.dropped = true
                        throw e
                    }
                    ImageFetchResult(image = cover.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
                }
            }
    }

    private object NoCommands : PlayerCommands {
        override fun togglePlayPause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun cycleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun setVolume(volume: Int) = Unit
        override fun startRadio(uri: String) = Unit
        override fun setPodcastSpeed(speed: Float) = Unit
        override fun toggleLike() = Unit
    }

    private object NoNavigator : AppNavigator {
        override fun navigate(route: Route) = Unit
        override fun back() = Unit
        override fun open(ref: MediaRef) = Unit
        override fun openUri(uri: String) = false
        override fun openNowPlaying() = Unit
        override fun closeNowPlaying() = Unit
        override fun openQueue() = Unit
        override fun openLyrics() = Unit
        override fun openDevices() = Unit
        override fun showActions(target: MediaActionTarget) = Unit
        override fun addToPlaylist(uris: List<String>, excludeUri: String?) = Unit
        override fun confirmPlaylistAdd(prompt: PlaylistAddPrompt) = Unit
        override fun showMessage(message: String) = Unit
    }

    private companion object {
        const val COVER_URL = "https://i.scdn.co/image/fake-cover"
        const val SLOW_COVER_URL = "https://i.scdn.co/image/slow-cover"
        const val LONG_TITLE = "Weird Fishes / Arpeggi"
        const val SHORT_TITLE = "Nude"
        const val POSITION_MS = 96_000L
        const val SEEK_DURATION_MS = 318_000L
        /** About a tenth of the Connect range. */
        const val VOLUME = 6_500
        val CoverColor = Color(0xFF7A3E8E)
    }
}

/**
 * Advances the (virtual) clock by [ms] in 16 ms frames and draws [view] after each one. Robolectric
 * runs Compose's recompositions here but no view traversals, so without the draws (which measure,
 * lay out and update the layers) a layout callback's state change would only land at the capture.
 */
internal fun runFrames(view: View, ms: Long) {
    val looper = shadowOf(Looper.getMainLooper())
    val canvas = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    var left = ms
    while (left > 0) {
        val step = minOf(16L, left)
        looper.idleFor(Duration.ofMillis(step))
        view.draw(canvas)
        left -= step
    }
}
