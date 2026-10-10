package com.taehagen.spotifygood.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Precision
import coil3.toBitmap
import coil3.transform.RoundedCornersTransformation
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.best
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the home-screen widgets current (docs/ARCHITECTURE.md §9.4, §10) with nothing scheduled:
 * the playback service pushes its state while it runs ([follow]), and the receiver draws what the
 * system asks for (a widget placed, a reboot, an app update, a resize below Android 12) and a
 * widget whose button found its state gone ([render]). With no widget placed nothing is
 * collected, read or drawn, and nothing here keeps the process alive.
 */
internal object NowPlayingWidgets {
    private const val TAG = "NowPlayingWidgets"

    /** At most one push per this long: a track change brings several snapshots. */
    private const val PUSH_INTERVAL_MS = 500L

    /** The artwork's corners: a tenth of its side, so that they scale with the layouts' artwork sizes. */
    private const val ART_CORNER = 0.1f
    private const val ART_TIMEOUT_MS = 5_000L

    @Volatile private var widgetIds: WidgetIds? = null

    /** What [follow] shows; null while nothing follows the playback state. */
    @Volatile private var live: WidgetModel? = null

    /** One update at a time, in order. */
    private val publishing = Mutex()

    /** The last artwork, reused while the item and the size step stay. Guarded by [publishing]. */
    private var lastArt: Art? = null

    /** The artwork size (px) of the last update; a resize that changes it draws again. Guarded by [publishing]. */
    private var publishedArtPx = 0

    private class Art(val url: String, val px: Int, val bitmap: Bitmap)

    /** The placed widgets of this process (read once, then on the provider's broadcasts). */
    fun ids(context: Context): WidgetIds = widgetIds ?: synchronized(this) {
        widgetIds ?: WidgetIds { placedIds(context.applicationContext) }.also { widgetIds = it }
    }

    private fun placedIds(context: Context): IntArray {
        // Null on devices without app widgets.
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetReceiver::class.java))
    }

    /**
     * Follows the playback state for as long as the playback service runs (in its scope), with
     * [liked] the current track's like state; nothing while no widget is placed. When the service
     * goes the widgets show the stored session, which a Play resumes without it.
     */
    suspend fun follow(service: Context, liked: Flow<Boolean?>) {
        val context = service.applicationContext
        val graph = (context as App).graph
        val ids = ids(context)
        // Logged out is only known once the stored credentials were read.
        val loggedIn = flow {
            graph.engine.awaitReady()
            emitAll(graph.engine.isLoggedIn)
        }
        val models = combine(loggedIn, graph.playback.snapshot, liked, ::Triple)
            .map { (signedIn, snapshot, like) ->
                val resume = if (WidgetModels.needsResume(signedIn, snapshot)) graph.resumeStore.read() else null
                WidgetModels.of(signedIn, snapshot, like, resume)
            }
            .distinctUntilChanged()
            .onEach { live = it }
        try {
            WidgetUpdates.pushes(models, ids.placed, PUSH_INTERVAL_MS)
                // A slow push (artwork being fetched) is followed by the latest state only.
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { model -> publish(context, ids.current()) { model } }
        } finally {
            live = null
            // Its buttons would find the session gone; the stored session resumes without it.
            graph.appScope.launch { publish(context, ids.current()) { restingModel(context) } }
        }
    }

    /** Draws [ids] from the live state, or without one from the stored state. */
    suspend fun render(context: Context, ids: IntArray) {
        publish(context.applicationContext, ids) { live ?: restingModel(context) }
    }

    /**
     * A widget was resized on Android 12+, where the launcher picks the layout itself: the widgets
     * are drawn again only when the artwork's size step changes with it.
     */
    suspend fun resized(context: Context) {
        val app = context.applicationContext
        val placed = ids(app).current()
        val manager = AppWidgetManager.getInstance(app) ?: return
        val px = withContext(Dispatchers.IO) { artPx(app, manager, placed) }
        if (px != publishing.withLock { publishedArtPx }) render(app, placed)
    }

    /** Without the playback service: logged out, the stored session (Play resumes it), or nothing. */
    private suspend fun restingModel(context: Context): WidgetModel = withContext(Dispatchers.IO) {
        val graph = (context.applicationContext as App).graph
        // A cached value or a file check: no Keystore, no engine.
        val loggedIn = graph.credentialStore.hasCredentials()
        WidgetModels.of(loggedIn, PlaybackSnapshot.EMPTY, liked = null, resume = if (loggedIn) graph.resumeStore.read() else null)
    }

    /** Pushes [model] (taken in turn, so that an older state never lands after a newer one) to [ids]. */
    private suspend fun publish(context: Context, ids: IntArray, model: suspend () -> WidgetModel) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            publishing.withLock {
                val manager = AppWidgetManager.getInstance(context) ?: return@withLock
                val shown = model()
                // One bitmap for every widget and layout, as large as the largest placed widget shows it.
                val px = artPx(context, manager, ids(context).current())
                val art = art(context, (shown as? WidgetModel.Item)?.art.orEmpty(), px)
                publishedArtPx = px
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        manager.updateAppWidget(ids, WidgetViews.responsive(context, shown, art))
                    } else {
                        for (id in ids) manager.updateAppWidget(id, WidgetViews.forOptions(context, shown, art, manager.getAppWidgetOptions(id)))
                    }
                } catch (e: RuntimeException) {
                    // A widget removed meanwhile, the system refusing the update: the next one tries again.
                    Log.w(TAG, "Widget update failed", e)
                }
            }
        }
    }

    /**
     * The artwork's size step (px, [WidgetArtSize]) for the placed widgets [ids]: every size their
     * options report (one binder call per widget), at the screen's density, within a quarter of the
     * update's bitmap limit.
     */
    private fun artPx(context: Context, manager: AppWidgetManager, ids: IntArray): Int {
        val sizes = ids.flatMap { id -> WidgetViews.sizesOf(runCatching { manager.getAppWidgetOptions(id) }.getOrNull()) }
        val metrics = context.resources.displayMetrics
        return WidgetArtSize.px(sizes, metrics.density, WidgetArtSize.budgetBytes(metrics.widthPixels, metrics.heightPixels))
    }

    /**
     * The artwork decoded at [px] (square, exactly) from the smallest of [images] that covers it,
     * from Coil's memory or disk cache or a downloaded cover when there is one (the app's loader
     * maps those), else fetched; never larger. Null without one: the layouts show the placeholder.
     */
    private suspend fun art(context: Context, images: List<Image>, px: Int): Bitmap? {
        val url = images.best(px)?.takeIf { it.isNotBlank() } ?: return null
        lastArt?.let { if (it.url == url && it.px == px) return it.bitmap }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(px)
            .precision(Precision.EXACT)
            // RemoteViews are parcelled: no hardware bitmaps.
            .allowHardware(false)
            .transformations(RoundedCornersTransformation(px * ART_CORNER))
            .build()
        val bitmap = withTimeoutOrNull(ART_TIMEOUT_MS) {
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        if (bitmap != null) lastArt = Art(url, px, bitmap)
        return bitmap
    }
}
