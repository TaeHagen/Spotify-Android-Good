package com.taehagen.spotifygood.ui.screens.player

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.model.LyricsColors
import com.taehagen.spotifygood.model.LyricsLine
import com.taehagen.spotifygood.model.LyricsSyncType
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * Now Playing's lyrics card under Robolectric (run with `-Pscreenshots`; images in
 * app/build/outputs/roborazzi, not in git). Filmstrips of the card as the current line advances
 * (light and dark, Spotify's colours and the artwork fallback, past a line that wraps), after a
 * seek, on another song and at the end; checks of what TalkBack reads, that a card out of view or
 * in a collapsed player doesn't follow the position, and that a tap opens the lyrics while a drag
 * scrolls the page. Fake state only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class LyricsPreviewScreenshotTest {

    // ---- Frames ---------------------------------------------------------------------------------

    @Test
    fun scrollsToTheNextLineDark() = scrollStrip("next_line_dark", dark = true, lyrics = SONG.copy(colors = SPOTIFY_COLORS), from = 4, to = 5)

    @Test
    fun scrollsToTheNextLineLight() = scrollStrip("next_line_light", dark = false, lyrics = SONG, from = 4, to = 5)

    /** The line leaving at the top wraps: the scroll is two rows long, same timing. */
    @Test
    fun scrollsPastAWrappedLine() = scrollStrip("wrapped_line_dark", dark = true, lyrics = SONG.copy(colors = SPOTIFY_COLORS), from = 8, to = 9)

    @Test
    fun aSeekSnaps() {
        val card = CardState(SONG.copy(colors = SPOTIFY_COLORS), lineStart(4))
        launch({ CardHarness(dark = true, card) }) { view ->
            val frames = mutableListOf("line 5" to grab(view, card.bounds))
            card.position.longValue = lineStart(15)
            frames += timed(view, card, listOf(16L, 50L, 150L, 400L))
            strip(frames).captureRoboImage(path("seek_dark"))
        }
    }

    @Test
    fun anotherSongStartsAtTheTop() {
        val card = CardState(SONG.copy(colors = SPOTIFY_COLORS), lineStart(10))
        launch({ CardHarness(dark = true, card) }) { view ->
            val frames = mutableListOf("line 11" to grab(view, card.bounds))
            card.lyrics.value = OTHER_SONG
            card.position.longValue = 1_000
            frames += timed(view, card, listOf(16L, 50L, 150L, 400L))
            strip(frames).captureRoboImage(path("next_song_dark"))
        }
    }

    @Test
    fun theLastLinesStayInView() {
        val card = CardState(SONG, lineStart(SONG.lines.size - 3))
        launch({ CardHarness(dark = false, card) }) { view ->
            val frames = mutableListOf("3rd last" to grab(view, card.bounds))
            card.position.longValue = lineStart(SONG.lines.size - 2)
            frames += timed(view, card, listOf(300L))
            card.position.longValue = lineStart(SONG.lines.size - 1)
            frames += timed(view, card, listOf(300L, 1_000L)).map { (label, frame) -> "last $label" to frame }
            strip(frames).captureRoboImage(path("end_light"))
        }
    }

    @Test
    fun unsyncedLyricsStayStill() {
        val card = CardState(SONG.copy(syncType = LyricsSyncType.UNSYNCED), lineStart(6))
        launch({ CardHarness(dark = true, card) }) { view ->
            grab(view, card.bounds).captureRoboImage(path("unsynced_dark"))
        }
    }

    // ---- Behaviour ------------------------------------------------------------------------------

    /** TalkBack reads the window's lines through the card, as before the lines scrolled. */
    @Test
    fun talkBackReadsTheWindow() {
        val card = CardState(SONG, lineStart(6))
        launch({ CardHarness(dark = true, card) }) { view ->
            assertEquals(spokenWindow(current = 6), cardNode(view).texts)
            assertEquals(listOf("Show lyrics"), cardNode(view).descriptions)
            card.position.longValue = lineStart(7)
            runFrames(view, 800)
            assertEquals(spokenWindow(current = 7), cardNode(view).texts)
        }
    }

    /** Below the screen the card reads nothing; scrolled into view it snaps to the line playing. */
    @Test
    fun aCardOutOfViewDoesNotFollow() {
        val card = CardState(SONG, lineStart(2))
        val scroll = ScrollState(0)
        launch({ CardHarness(dark = true, card, scroll = scroll, above = 1_200.dp) }) { view ->
            assertEquals(spokenWindow(current = 2), cardNode(view).texts)
            card.position.longValue = lineStart(10)
            runFrames(view, 800)
            assertEquals("hidden: unchanged", spokenWindow(current = 2), cardNode(view).texts)
            scroll.dispatchRawDelta(card.top - 300f)
            runFrames(view, 100)
            assertEquals("in view: the line playing", spokenWindow(current = 10), cardNode(view).texts)
            grab(view, card.bounds).captureRoboImage(path("scrolled_into_view_dark"))
        }
    }

    /** Collapsed (Now Playing may stay composed), the card reads nothing. */
    @Test
    fun aCollapsedPlayerDoesNotFollow() {
        val card = CardState(SONG, lineStart(2))
        launch({ CardHarness(dark = true, card, sheetExpanded = false) }) { view ->
            card.position.longValue = lineStart(10)
            runFrames(view, 800)
            assertEquals(spokenWindow(current = 2), cardNode(view).texts)
        }
        val expanded = CardState(SONG, lineStart(2))
        launch({ CardHarness(dark = true, expanded, sheetExpanded = true) }) { view ->
            expanded.position.longValue = lineStart(10)
            runFrames(view, 800)
            assertEquals(spokenWindow(current = 10), cardNode(view).texts)
        }
    }

    /** The lines take no touches: a tap opens the full lyrics, a drag scrolls the page. */
    @Test
    fun aTapOpensAndADragScrollsThePage() {
        val card = CardState(SONG, lineStart(6))
        val scroll = ScrollState(0)
        launch({ CardHarness(dark = true, card, scroll = scroll, above = 200.dp) }) { view ->
            val density = view.resources.displayMetrics.density
            val lines = card.bounds.let { Offset(it.center.x, it.bottom - 100 * density) }
            tap(view, lines)
            assertEquals(1, card.opened)
            drag(view, lines, lines - Offset(0f, 250 * density))
            assertTrue("page scrolled: ${scroll.value}", scroll.value > 150 * density)
            assertEquals(1, card.opened)
        }
    }

    // ---- Harness --------------------------------------------------------------------------------

    private class CardState(lyrics: Lyrics, positionMs: Long) {
        val lyrics: MutableState<Lyrics> = mutableStateOf(lyrics)
        val position: MutableLongState = mutableLongStateOf(positionMs)
        /** In the window, clipped (empty while off screen). */
        var bounds = Rect.Zero
        /** The card's top in the window, unclipped. */
        var top = 0f
        var opened = 0
    }

    /**
     * The card as Now Playing lays it out (24 dp margins, its padding) on the player background,
     * [above] it some space and below it more, in a scrolling column. With [sheetExpanded] it sits
     * in a resting player sheet (no geometry: progress is 1 expanded, 0 collapsed).
     */
    @Composable
    private fun CardHarness(
        dark: Boolean,
        card: CardState,
        scroll: ScrollState? = null,
        above: Dp = 48.dp,
        sheetExpanded: Boolean? = null,
    ) {
        SpotifyGoodTheme(darkTheme = dark) {
            val density = LocalDensity.current
            val sheet = remember { sheetExpanded?.let { PlayerSheetState(it, density) } }
            CompositionLocalProvider(LocalPlayerSheet provides sheet) {
                PlayerSurfaceTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(PlayerDefaults.Background),
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll ?: rememberScrollState())
                                .padding(horizontal = 24.dp),
                        ) {
                            Spacer(Modifier.height(above))
                            LyricsPreviewCard(
                                lyrics = card.lyrics.value,
                                position = card.position,
                                fallbackColor = remember { mutableStateOf(COVER_COLOR) },
                                onOpen = { card.opened++ },
                                modifier = Modifier
                                    .padding(top = 16.dp, bottom = 24.dp)
                                    .onGloballyPositioned {
                                        card.bounds = it.boundsInWindow()
                                        card.top = it.positionInWindow().y
                                    },
                            )
                            Spacer(Modifier.height(1_500.dp))
                        }
                    }
                }
            }
        }
    }

    private fun path(name: String) = "build/outputs/roborazzi/lyrics_preview_$name.png"

    /**
     * Composes [content], runs 800 ms of frames (the card settles, its visibility is known).
     *
     * The choreographer is paused: a frame comes only as [runFrames] moves the clock. Running
     * freely, Robolectric answers each frame request at once and moves the clock a frame ahead,
     * so an animation plays to its end inside one step and no frame shows it in between.
     */
    private fun launch(content: @Composable () -> Unit, block: (View) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                // Only once the activity is up (a launch waits on a frame drawn at once), and
                // only until it's done with (a test may launch another).
                ShadowChoreographer.setPaused(true)
                try {
                    it.setContent(content = content)
                    val view = it.window.decorView
                    runFrames(view, 800)
                    block(view)
                } finally {
                    ShadowChoreographer.setPaused(false)
                }
            }
        }
    }

    /**
     * The card at [from] (+100 ms), then the position at [to]'s start and frames through the
     * scroll (650 ms) and the colour fades (300 ms), as one filmstrip.
     */
    private fun scrollStrip(name: String, dark: Boolean, lyrics: Lyrics, from: Int, to: Int) {
        val card = CardState(lyrics, lineStart(from) + 100)
        launch({ CardHarness(dark, card) }) { view ->
            val frames = mutableListOf("rest" to grab(view, card.bounds))
            card.position.longValue = lineStart(to) + 100
            frames += timed(view, card, listOf(50L, 150L, 250L, 350L, 450L, 550L, 700L, 1_000L))
            strip(frames).captureRoboImage(path(name))
        }
    }

    /** Frames of the card at these times (ms) after now. */
    private fun timed(view: View, card: CardState, at: List<Long>): List<Pair<String, Bitmap>> {
        var elapsed = 0L
        return at.map { ms ->
            runFrames(view, ms - elapsed)
            elapsed = ms
            "+$ms ms" to grab(view, card.bounds)
        }
    }

    /** The window's region [bounds] as drawn now. */
    private fun grab(view: View, bounds: Rect): Bitmap {
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        val margin = 8
        val left = (bounds.left.toInt() - margin).coerceAtLeast(0)
        val top = (bounds.top.toInt() - margin).coerceAtLeast(0)
        val right = (bounds.right.toInt() + margin).coerceAtMost(view.width)
        val bottom = (bounds.bottom.toInt() + margin).coerceAtMost(view.height)
        return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    }

    /** [frames] in a grid of three columns at half size, each labelled. */
    private fun strip(frames: List<Pair<String, Bitmap>>): Bitmap {
        val columns = 3.coerceAtMost(frames.size)
        val cellWidth = frames.maxOf { it.second.width } / 2
        val cellHeight = frames.maxOf { it.second.height } / 2
        val label = 44
        val rows = (frames.size + columns - 1) / columns
        val out = Bitmap.createBitmap(columns * cellWidth, rows * (cellHeight + label), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF000000.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 30f
        }
        val scaled = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { index, (name, frame) ->
            val x = (index % columns) * cellWidth
            val y = (index / columns) * (cellHeight + label)
            canvas.drawText(name, x + 12f, y + 32f, text)
            val destination = AndroidRect(x, y + label, x + frame.width / 2, y + label + frame.height / 2)
            canvas.drawBitmap(frame, null, destination, scaled)
        }
        return out
    }

    private class CardNode(val texts: List<String>, val descriptions: List<String>)

    /** The card's merged node (what TalkBack reads). */
    private fun cardNode(view: View): CardNode {
        val root = checkNotNull(view.composeRoot())
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            .single { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == "Lyrics" }
        return CardNode(
            texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
            descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
        )
    }

    /** What the card read before it scrolled: its title and the window's lines. */
    private fun spokenWindow(current: Int): List<String> =
        listOf("Lyrics") + lyricsPreviewWindow(SONG.lines.size, current).map { SONG.lines[it].words.ifBlank { "♪" } }

    private fun tap(view: View, at: Offset) {
        val down = SystemClock.uptimeMillis()
        touch(view, down, MotionEvent.ACTION_DOWN, at)
        runFrames(view, 50)
        touch(view, down, MotionEvent.ACTION_UP, at)
        runFrames(view, 300)
    }

    private fun drag(view: View, from: Offset, to: Offset, steps: Int = 12, durationMs: Long = 400) {
        val down = SystemClock.uptimeMillis()
        touch(view, down, MotionEvent.ACTION_DOWN, from)
        for (step in 1..steps) {
            runFrames(view, durationMs / steps)
            touch(view, down, MotionEvent.ACTION_MOVE, from + (to - from) * (step / steps.toFloat()))
        }
        runFrames(view, 100)
        touch(view, down, MotionEvent.ACTION_UP, to)
        runFrames(view, 1_000)
    }

    private fun touch(view: View, downTime: Long, action: Int, at: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, at.x, at.y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun View.composeRoot(): ViewRootForTest? = when (this) {
        is ViewRootForTest -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).composeRoot() }
        else -> null
    }

    private companion object {
        /** Line i starts at 10 s + 4 s × i. */
        fun lineStart(index: Int): Long = 10_000L + 4_000L * index

        fun synced(words: List<String>) = Lyrics(
            syncType = LyricsSyncType.LINE_SYNCED,
            lines = words.mapIndexed { index, line -> LyricsLine(startTimeMs = lineStart(index), words = line) },
            provider = "Test",
        )

        val SONG = synced(
            listOf(
                "",
                "Streetlights hum along the avenue",
                "I count the windows still awake",
                "Your voice is static on the radio",
                "Somewhere between the bridge and the lake",
                "We were the echo in the hallway",
                "Carry me home",
                "Carry me home before the morning finds us out here asking where the night went",
                "Paper boats and borrowed time",
                "Every song we ever sang",
                "Rings out loud across the bay",
                "",
                "Hold on",
                "Hold on to the sound",
                "Hold on, it's coming round",
                "Hold on to the sound",
                "Till the lights come down",
                "Till the lights come down on this long and winding avenue we used to call our own",
                "Goodnight",
            ),
        )

        val OTHER_SONG = synced(
            listOf(
                "A brand new morning",
                "Coffee on the windowsill",
                "Nothing left to say",
                "But hello again",
                "Hello again",
                "Hello",
                "Again and again",
            ),
        ).copy(colors = LyricsColors(background = 0xFF1E3264.toInt(), text = 0xFF000000.toInt(), highlightText = 0xFFFFFFFF.toInt()))

        /** Spotify's colours for a red song (background, upcoming text, highlight). */
        val SPOTIFY_COLORS = LyricsColors(background = 0xFFB02A3A.toInt(), text = 0xFF000000.toInt(), highlightText = 0xFFFFFFFF.toInt())

        val COVER_COLOR = Color(0xFF7A3E8E)
    }
}
