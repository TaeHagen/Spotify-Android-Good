package com.taehagen.spotifygood.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import com.taehagen.spotifygood.MainActivity
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.playback.PlaybackService
import com.taehagen.spotifygood.playback.ShuffleMode
import androidx.media3.session.R as Media3R

/**
 * The widget's RemoteViews for a [WidgetModel] (docs/ARCHITECTURE.md §9.4). Every update sets
 * every view state it uses: the launcher re-applies an update with the same layout onto the views
 * it shows, so nothing may be left over from the previous one. The icons are the media
 * notification's (Media3's).
 */
internal object WidgetViews {
    /** Android 12+: every layout in one RemoteViews; the launcher shows the best fit for its size, also while resizing. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun responsive(context: Context, model: WidgetModel, art: Bitmap?): RemoteViews {
        val actions = WidgetActions(context)
        return RemoteViews(
            WidgetLayout.entries.associate { SizeF(it.minSize.width, it.minSize.height) to build(context, it, model, art, actions) },
        )
    }

    /** Every size (dp) the launcher reports for one widget ([options]); the default size without any. */
    fun sizesOf(options: Bundle?): List<WidgetSize> {
        if (options == null) return listOf(WidgetLayout.DEFAULT_SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            if (!sizes.isNullOrEmpty()) return sizes.map { WidgetSize(it.width, it.height) }
        }
        return orientationSizes(options)?.toList() ?: listOf(WidgetLayout.DEFAULT_SIZE)
    }

    private fun orientationSizes(options: Bundle): Pair<WidgetSize, WidgetSize>? = WidgetLayout.orientationSizes(
        minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
        minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
        maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
        maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
    )

    /** Below Android 12: the layouts for the portrait and landscape sizes of one widget ([options]). */
    fun forOptions(context: Context, model: WidgetModel, art: Bitmap?, options: Bundle?): RemoteViews {
        val sizes = options?.let(::orientationSizes)
        val portrait = WidgetLayout.bestFit(sizes?.first ?: WidgetLayout.DEFAULT_SIZE)
        val landscape = WidgetLayout.bestFit(sizes?.second ?: WidgetLayout.DEFAULT_SIZE)
        val actions = WidgetActions(context)
        val portraitViews = build(context, portrait, model, art, actions)
        return if (landscape == portrait) portraitViews else RemoteViews(build(context, landscape, model, art, actions), portraitViews)
    }

    fun build(context: Context, layout: WidgetLayout, model: WidgetModel, art: Bitmap?, actions: WidgetActions = WidgetActions(context)): RemoteViews =
        when (model) {
            WidgetModel.SignedOut -> signIn(context, layout, actions)
            WidgetModel.Idle -> item(context, layout, null, null, actions)
            is WidgetModel.Item -> item(context, layout, model, art, actions)
        }

    private val WidgetLayout.layoutRes: Int
        get() = when (this) {
            WidgetLayout.SMALL -> R.layout.widget_np_small
            WidgetLayout.SMALL_TALL -> R.layout.widget_np_small_tall
            WidgetLayout.ROW, WidgetLayout.ROW_FULL -> R.layout.widget_np_row
            WidgetLayout.STACKED -> R.layout.widget_np_stacked
            WidgetLayout.LARGE -> R.layout.widget_np_large
            WidgetLayout.TALL_NARROW, WidgetLayout.TALL -> R.layout.widget_np_tall
        }

    /** An item, or nothing to show ([item] null: [WidgetModel.Idle]). */
    private fun item(context: Context, layout: WidgetLayout, item: WidgetModel.Item?, art: Bitmap?, actions: WidgetActions): RemoteViews {
        val views = RemoteViews(context.packageName, layout.layoutRes)
        // Taps beside the buttons: Now Playing (as from the media notification), or the app.
        views.setOnClickPendingIntent(android.R.id.background, if (item != null) actions.openPlayer else actions.openApp)
        if (art != null) views.setImageViewBitmap(R.id.widget_art, art) else views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)

        val title = item?.title ?: context.getString(R.string.widget_nothing_playing)
        val subtitle = item?.subtitle ?: context.getString(R.string.widget_nothing_playing_text)
        if (layout.showsText) {
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setContentDescription(android.R.id.background, null)
        } else {
            // Artwork only: the widget itself tells TalkBack what plays.
            val what = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(", ")
            views.setContentDescription(android.R.id.background, context.getString(R.string.widget_open_player, what))
        }

        val playing = item?.playing == true
        views.setImageViewResource(R.id.widget_play, if (playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play)
        views.setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
        // Play resumes the stored session too; nothing to play or resume: disabled.
        button(views, R.id.widget_play, item?.let { actions.transport(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY) })

        if (layout.showsText) skips(context, views, layout, item?.takeIf { it.live }, actions)
        if (layout.isLarge) large(context, views, layout, item?.takeIf { it.live }, actions)
        return views
    }

    /** Previous / next (−15 s / +15 s for an episode); only for what is loaded ([live]). */
    private fun skips(context: Context, views: RemoteViews, layout: WidgetLayout, live: WidgetModel.Item?, actions: WidgetActions) {
        val visibility = if (layout.showsSkips) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_skip_back, visibility)
        views.setViewVisibility(R.id.widget_skip_forward, visibility)
        if (!layout.showsSkips) return
        val episode = live?.isEpisode == true
        views.setImageViewResource(R.id.widget_skip_back, if (episode) Media3R.drawable.media3_icon_skip_back_15 else Media3R.drawable.media3_icon_previous)
        views.setImageViewResource(R.id.widget_skip_forward, if (episode) Media3R.drawable.media3_icon_skip_forward_15 else Media3R.drawable.media3_icon_next)
        views.setContentDescription(R.id.widget_skip_back, context.getString(if (episode) R.string.playback_action_seek_back else R.string.widget_previous))
        views.setContentDescription(R.id.widget_skip_forward, context.getString(if (episode) R.string.playback_action_seek_forward else R.string.widget_next))
        val back = if (episode) KeyEvent.KEYCODE_MEDIA_REWIND else KeyEvent.KEYCODE_MEDIA_PREVIOUS
        val forward = if (episode) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_NEXT
        button(views, R.id.widget_skip_back, live?.takeIf { it.canSkipBack }?.let { actions.transport(back) })
        button(views, R.id.widget_skip_forward, live?.takeIf { it.canSkipForward }?.let { actions.transport(forward) })
    }

    /** The device line, like and shuffle; only for what is loaded ([live]). */
    private fun large(context: Context, views: RemoteViews, layout: WidgetLayout, live: WidgetModel.Item?, actions: WidgetActions) {
        val device = live?.device
        views.setViewVisibility(R.id.widget_device, if (device != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_device_text, device?.let { context.getString(R.string.playback_playing_on, it) }.orEmpty())

        // A hidden slot keeps its place in the row (INVISIBLE) where the layout has like and shuffle.
        fun slot(available: Boolean) = when {
            !layout.showsExtras -> View.GONE
            available -> View.VISIBLE
            else -> View.INVISIBLE
        }

        val liked = live?.liked
        views.setViewVisibility(R.id.widget_like_slot, slot(liked != null))
        if (live != null && liked != null) {
            views.setViewVisibility(R.id.widget_like, if (liked) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_liked, if (liked) View.VISIBLE else View.GONE)
            val toggle = actions.like(live.uri, liked)
            views.setOnClickPendingIntent(R.id.widget_like, toggle)
            views.setOnClickPendingIntent(R.id.widget_liked, toggle)
        }

        val shuffle = live?.shuffle
        views.setViewVisibility(R.id.widget_shuffle_slot, slot(shuffle != null))
        if (live != null && shuffle != null) {
            val on = shuffle != ShuffleMode.OFF
            views.setViewVisibility(R.id.widget_shuffle, if (on) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_shuffle_on, if (on) View.VISIBLE else View.GONE)
            views.setImageViewResource(
                R.id.widget_shuffle_on,
                if (shuffle == ShuffleMode.SMART) Media3R.drawable.media3_icon_shuffle_star else Media3R.drawable.media3_icon_shuffle_on,
            )
            // What a tap does, like the notification's button: off → shuffle → smart shuffle → off.
            val next = when (shuffle) {
                ShuffleMode.OFF -> R.string.playback_action_shuffle_on
                ShuffleMode.SHUFFLE -> if (live.smartShuffleAvailable) R.string.playback_action_smart_shuffle_on else R.string.playback_action_shuffle_off
                ShuffleMode.SMART -> R.string.playback_action_shuffle_off
            }
            views.setContentDescription(if (on) R.id.widget_shuffle_on else R.id.widget_shuffle, context.getString(next))
            views.setOnClickPendingIntent(R.id.widget_shuffle, actions.shuffle)
            views.setOnClickPendingIntent(R.id.widget_shuffle_on, actions.shuffle)
        }
    }

    /** Logged out: the app's mark and "Sign in", opening the app the way the launcher does. */
    private fun signIn(context: Context, layout: WidgetLayout, actions: WidgetActions): RemoteViews {
        val views = RemoteViews(context.packageName, if (layout.isRow) R.layout.widget_np_message_row else R.layout.widget_np_message)
        views.setOnClickPendingIntent(android.R.id.background, actions.openApp)
        if (!layout.isRow) {
            // The small shapes: the mark and the button only.
            val text = if (layout.showsText) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.widget_message_title, text)
            views.setViewVisibility(R.id.widget_message_text, text)
            views.setViewVisibility(R.id.widget_sign_in, View.VISIBLE)
        }
        return views
    }

    /** A button that does [action], or is shown disabled without one. */
    private fun button(views: RemoteViews, @IdRes id: Int, action: PendingIntent?) {
        views.setBoolean(id, "setEnabled", action != null)
        views.setFloat(id, "setAlpha", if (action != null) 1f else DISABLED_ALPHA)
        // A disabled view takes no clicks: an older intent left on it is never sent.
        if (action != null) views.setOnClickPendingIntent(id, action)
    }

    private const val DISABLED_ALPHA = 0.38f
}

/**
 * The widget's PendingIntents, made once per update (each is a call to the system) and shared by
 * its layouts. Buttons go to [NowPlayingWidgetReceiver] (not exported), which hands playback keys
 * to [PlaybackService] as media-button intents, the headset's path. Taps on the widget open the
 * app with [MainActivity.launchIntent], the launcher's intent, so the app's task keeps a root a
 * launcher tap matches.
 */
internal class WidgetActions(private val context: Context) {
    /** Now Playing, as from the media notification (the session activity's intent). */
    val openPlayer: PendingIntent by lazy {
        val launch = MainActivity.launchIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(PlaybackService.EXTRA_OPEN_PLAYER, true)
        PendingIntent.getActivity(context, REQUEST_OPEN_PLAYER, launch, FLAGS)
    }

    /** The app, exactly as a launcher tap opens it (a login in progress stays on top). */
    val openApp: PendingIntent by lazy {
        PendingIntent.getActivity(context, REQUEST_OPEN_APP, MainActivity.launchIntent(context), FLAGS)
    }

    val shuffle: PendingIntent by lazy { broadcast(REQUEST_SHUFFLE, NowPlayingWidgetReceiver.ACTION_SHUFFLE) {} }

    private val transports = HashMap<Int, PendingIntent>()

    /** A playback key ([KeyEvent] code); its code is the request code, so each key has its own. */
    fun transport(keyCode: Int): PendingIntent = transports.getOrPut(keyCode) {
        broadcast(keyCode, NowPlayingWidgetReceiver.ACTION_TRANSPORT) { putExtra(NowPlayingWidgetReceiver.EXTRA_KEY_CODE, keyCode) }
    }

    /** Toggles the like of [uri], which shows [liked]. */
    fun like(uri: String, liked: Boolean): PendingIntent = broadcast(REQUEST_LIKE, NowPlayingWidgetReceiver.ACTION_LIKE) {
        putExtra(NowPlayingWidgetReceiver.EXTRA_URI, uri)
        putExtra(NowPlayingWidgetReceiver.EXTRA_LIKED, liked)
    }

    private inline fun broadcast(requestCode: Int, action: String, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NowPlayingWidgetReceiver::class.java).setAction(action).apply(extras),
            FLAGS,
        )

    private companion object {
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Activities: apart from the service's session activity and "Sign in" (same intents).
        const val REQUEST_OPEN_PLAYER = 41
        const val REQUEST_OPEN_APP = 42
        // Broadcasts to the receiver; a playback key uses its key code.
        const val REQUEST_LIKE = 1
        const val REQUEST_SHUFFLE = 2
    }
}
