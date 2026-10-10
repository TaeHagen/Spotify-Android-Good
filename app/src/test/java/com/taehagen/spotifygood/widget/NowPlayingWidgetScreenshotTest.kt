package com.taehagen.spotifygood.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.playback.ShuffleMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Draws every layout of the home-screen widget in each of its states, light and dark, to contact
 * sheets (run with `-Pscreenshots`; images in app/build/outputs/roborazzi/widget, not in git), and
 * checks that a launcher could apply them: RemoteViews refuse views and methods they don't allow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class NowPlayingWidgetScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val out = File("build/outputs/roborazzi/widget").apply { mkdirs() }

    /** The widget sizes drawn (dp): the grid sizes of each layout, and each layout's minimum. */
    private val sizes = listOf(
        "2x1" to WidgetSize(130f, 102f),
        "2x2" to WidgetSize(130f, 220f),
        "3x1" to WidgetSize(203f, 102f),
        "4x1" to WidgetSize(276f, 102f),
        "4x1 land" to WidgetSize(553f, 51f),
        "3x2" to WidgetSize(203f, 220f),
        "4x2" to WidgetSize(276f, 220f),
        "4x2 land" to WidgetSize(553f, 117f),
        "4x3" to WidgetSize(276f, 338f),
        // The lowest heights: the smallest resize (40dp) and one landscape row.
        "2x1 40dp" to WidgetSize(110f, 40f),
        "2x1 51dp" to WidgetSize(130f, 51f),
        "row 40dp" to WidgetSize(220f, 40f),
    ) + WidgetLayout.entries.map { "${it.name.lowercase()} min" to it.minSize }

    private val local = WidgetModel.Item(
        uri = "spotify:track:t",
        title = "Midnight City",
        subtitle = "M83",
        art = listOf(Image("https://i.scdn.co/image/a", 300, 300)),
        live = true,
        playing = true,
        liked = true,
        shuffle = ShuffleMode.SHUFFLE,
        smartShuffleAvailable = true,
        canSkipBack = true,
        canSkipForward = true,
    )

    private val states = listOf(
        "local" to local,
        "remote" to local.copy(
            title = "A Very Long Song Title That Does Not Fit On One Line At All",
            subtitle = "Somebody, Someone Else and The Orchestra",
            playing = false,
            device = "Kitchen",
            liked = false,
            shuffle = ShuffleMode.SMART,
        ),
        "episode" to local.copy(title = "Episode 12: The Widget", subtitle = "The Show", isEpisode = true, liked = null, shuffle = null),
        "resume" to WidgetModel.Item(uri = "spotify:track:r", title = "Stored Song", subtitle = "Stored Artist", art = emptyList(), live = false),
        "idle" to WidgetModel.Idle,
        "signed-out" to WidgetModel.SignedOut,
    )

    @Test
    fun drawsEveryLayoutInLightAndDark() {
        sheets("light")
        RuntimeEnvironment.setQualifiers("+night")
        sheets("dark")
    }

    @Test
    fun theResponsiveAndThePairedViewsAreAccepted() {
        val model = states.first().second
        val sized = WidgetViews.responsive(context, model, art())
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 276)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 117)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 553)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
        }
        val paired = WidgetViews.forOptions(context, model, art(), options)
        // Throws for a view or method RemoteViews do not allow.
        for (views in listOf(sized, paired)) {
            val view = views.apply(context, FrameLayout(context))
            assertTrue(view.findViewById<View>(android.R.id.background) != null)
        }
    }

    private fun sheets(theme: String) {
        val art = art()
        for ((name, model) in states) {
            val cells = sizes.map { (label, size) ->
                val layout = WidgetLayout.bestFit(size)
                "$label ${layout.name.lowercase()}" to draw(WidgetViews.build(context, layout, model, art), size)
            }
            save(sheet(cells), "widget-$name-$theme.png")
        }
    }

    /** What the launcher shows: the views applied and laid out at [size] over a wallpaper grey. */
    private fun draw(views: RemoteViews, size: WidgetSize): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).roundToInt()
        val height = (size.height * density).roundToInt()
        val view = views.apply(context, FrameLayout(context))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(WALLPAPER)
        view.draw(canvas)
        return bitmap
    }

    /** The cells in rows, each with its label above it. */
    private fun sheet(cells: List<Pair<String, Bitmap>>): Bitmap {
        val gap = 24
        val label = 28
        val maxWidth = 1800
        val placed = mutableListOf<Triple<Int, Int, Pair<String, Bitmap>>>()
        var x = gap
        var y = gap
        var rowHeight = 0
        for (cell in cells) {
            val bitmap = cell.second
            if (x + bitmap.width + gap > maxWidth && x > gap) {
                x = gap
                y += rowHeight + gap
                rowHeight = 0
            }
            placed += Triple(x, y, cell)
            x += bitmap.width + gap
            rowHeight = maxOf(rowHeight, bitmap.height + label)
        }
        val sheet = Bitmap.createBitmap(maxWidth, y + rowHeight + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
        }
        for ((left, top, cell) in placed) {
            canvas.drawText(cell.first, left.toFloat(), top + 20f, text)
            canvas.drawBitmap(cell.second, left.toFloat(), (top + label).toFloat(), null)
        }
        return sheet
    }

    /** Stand-in artwork: a gradient square, rounded like the widget's (a tenth of the side). */
    private fun art(): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), Color.rgb(233, 64, 87), Color.rgb(64, 32, 160), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.1f, size * 0.1f, paint)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(out, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val WALLPAPER = Color.rgb(96, 110, 120)
    }
}
