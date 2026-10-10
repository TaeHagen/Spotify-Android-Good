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
import com.taehagen.spotifygood.model.PlaybackSnapshot
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

    /** The artwork is decoded at this size (px, square) from the cached or downloaded image. */
    private const val ART_PX = 256

    /** Its corners: a tenth of its side, so that they scale with the layouts' artwork sizes. */
    private const val ART_CORNER = 0.1f
    private const val ART_TIMEOUT_MS = 5_000L

    @Volatile private var widgetIds: WidgetIds? = null

    /** What [follow] shows; null while nothing follows the playback state. */
    @Volatile private var live: WidgetModel? = null

    /** One update at a time, in order. */
    private val publishing = Mutex()

    /** The last artwork (url, bitmap), reused while the item stays. Guarded by [publishing]. */
    private var lastArt: Pair<String, Bitmap>? = null

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
                val art = art(context, (shown as? WidgetModel.Item)?.artUrl)
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
     * The artwork at [ART_PX], from Coil's memory or disk cache or a downloaded cover when there
     * is one (the app's loader maps those), else fetched; never the full-size image. Null without
     * one: the layouts show the placeholder.
     */
    private suspend fun art(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        lastArt?.let { (lastUrl, bitmap) -> if (lastUrl == url) return bitmap }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ART_PX)
            .precision(Precision.EXACT)
            // RemoteViews are parcelled: no hardware bitmaps.
            .allowHardware(false)
            .transformations(RoundedCornersTransformation(ART_PX * ART_CORNER))
            .build()
        val bitmap = withTimeoutOrNull(ART_TIMEOUT_MS) {
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        if (bitmap != null) lastArt = url to bitmap
        return bitmap
    }
}
