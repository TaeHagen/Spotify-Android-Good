package com.taehagen.spotifygood.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.SpotifyLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The process-wide [AppGraph] from a composable. */
@Composable
fun rememberAppGraph(): AppGraph = (LocalContext.current.applicationContext as App).graph

/**
 * Runs sheet actions in the process scope (so they survive the sheet being dismissed) and
 * reports results / failures through [AppNavigator.showMessage].
 */
internal class MediaActionRunner(
    private val graph: AppGraph,
    private val navigator: AppNavigator?,
    context: Context,
) {
    private val appContext = context.applicationContext

    fun message(resId: Int, vararg args: Any) {
        navigator?.showMessage(appContext.getString(resId, *args))
    }

    /** Launches [block]; on success shows [successRes] (if any), on failure a friendly error. */
    fun launch(successRes: Int? = null, vararg successArgs: Any, block: suspend () -> Unit): Job =
        graph.appScope.launch {
            try {
                block()
                if (successRes != null) withContext(Dispatchers.Main) { message(successRes, *successArgs) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Action failed", t)
                navigator?.showMessage(friendlyErrorMessage(appContext, t))
            }
        }

    /**
     * Adds [uris] in order, one player command each, and reports what really happened: the engine
     * refuses adds once this phone's queue is full, so a batch can stop part-way. The batch stops
     * at the first failed add (the player reports that failure itself).
     */
    fun addToQueue(uris: List<String>) {
        if (uris.isEmpty()) return
        graph.appScope.launch {
            val queuedBefore = localQueuedCount(graph.playback.snapshot.value)
            var added = 0
            for (uri in uris) {
                if (!graph.player.addToQueueAsync(listOf(uri)).await()) break
                added++
            }
            when (val outcome = queueAddOutcome(uris.size, added, queuedBefore)) {
                is QueueAddOutcome.Added ->
                    if (outcome.added == 1) message(R.string.shell_msg_added_to_queue) else message(R.string.shell_msg_added_n_to_queue, outcome.added)
                is QueueAddOutcome.QueueFull ->
                    if (outcome.added == 0) {
                        message(R.string.shell_msg_queue_full)
                    } else {
                        message(R.string.shell_msg_queue_full_added, outcome.added, outcome.requested)
                    }
                // The player's own error message already explains why; say how far it got.
                is QueueAddOutcome.Stopped ->
                    if (outcome.added > 0) message(R.string.shell_msg_added_some_to_queue, outcome.added, outcome.requested)
            }
        }
    }

    fun setSaved(uri: String, saved: Boolean, addedRes: Int, removedRes: Int) =
        launch(if (saved) addedRes else removedRes) { graph.library.setSaved(listOf(uri), saved) }

    companion object {
        private const val TAG = "MediaActions"
        private const val LOAD_TIMEOUT_MS = 20_000L

        /** First non-loading value of a stale-while-revalidate flow (cached data if offline). */
        suspend fun <T> Flow<Resource<T>>.awaitData(): T? = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            val first = first { it !is Resource.Loading }
            if (first is Resource.Error && first.cached == null) throw first.error
            first.dataOrNull
        }
    }
}

/** Opens the system share sheet with the public link for [uri]. Returns false if not shareable. */
fun shareSpotifyLink(context: Context, uri: String, title: String?): Boolean {
    val link = SpotifyLinks.webUrl(uri) ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, title)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.shell_share_title)).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(chooser) }.isSuccess
}
