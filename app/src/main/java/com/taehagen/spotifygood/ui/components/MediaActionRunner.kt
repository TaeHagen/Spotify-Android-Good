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
import com.taehagen.spotifygood.data.PlaylistAddChoice
import com.taehagen.spotifygood.data.PlaylistAddPlan
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.data.planPlaylistAdd
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.OfflineMembers
import com.taehagen.spotifygood.ui.screens.album.engineReach
import com.taehagen.spotifygood.ui.screens.album.isNetworkClassError
import com.taehagen.spotifygood.ui.screens.library.DownloadMetadata
import com.taehagen.spotifygood.ui.screens.library.decodeDownloadMetadata
import com.taehagen.spotifygood.ui.screens.library.explicitFilterFlow
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
     * "Add to queue" of the collection [uri] (playlist or album). While the session is ONLINE (by
     * the engine's reach) the server's list ([online]: URIs only, bounded to [LOAD_TIMEOUT_MS]);
     * otherwise, or when fetching it fails for lack of connection or in time, the collection's
     * downloaded members in order (offline nothing else can be queued), without explicit ones
     * while Hide explicit content is on ([collectionQueuePlan]). The queue add says how many went
     * in ([addToQueue]).
     */
    fun addCollectionToQueue(uri: String, online: suspend () -> List<String>) {
        launch {
            var failure: Throwable? = null
            var timedOut = false
            val fromServer = if (graph.engineReach() == EngineReach.ONLINE) {
                try {
                    withTimeoutOrNull(LOAD_TIMEOUT_MS) { online() }.also { timedOut = it == null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isNetworkClassError(e)) throw e
                    failure = e
                    null
                }
            } else {
                null
            }
            val downloaded = if (fromServer == null) graph.downloadedQueueUris(uri) else emptyList()
            when (val plan = collectionQueuePlan(fromServer, downloaded, failure, timedOut)) {
                is CollectionQueuePlan.Queue -> addToQueue(plan.uris)
                CollectionQueuePlan.NothingToQueue -> message(R.string.shell_msg_nothing_to_add)
                CollectionQueuePlan.NothingDownloaded -> withContext(Dispatchers.Main) { message(R.string.shell_msg_queue_nothing_downloaded) }
                is CollectionQueuePlan.Failed ->
                    plan.error?.let { navigator?.showMessage(friendlyErrorMessage(appContext, it)) } ?: message(R.string.shell_msg_list_timed_out)
            }
        }
    }

    /**
     * Adds [uris] in order, one player command each, and reports what really happened in one
     * message: the batch stops at the first failed add (e.g. the engine's "The queue is full" once
     * this phone's queue is full), whose reason is shown here (the player does not report it).
     */
    fun addToQueue(uris: List<String>) {
        if (uris.isEmpty()) return
        graph.appScope.launch {
            val result = graph.player.addToQueueCounted(uris).await()
            when (val outcome = queueAddOutcome(uris.size, result.added, result.error)) {
                is QueueAddOutcome.Added ->
                    if (outcome.added == 1) message(R.string.shell_msg_added_to_queue) else message(R.string.shell_msg_added_n_to_queue, outcome.added)
                is QueueAddOutcome.QueueFull ->
                    if (outcome.added == 0) {
                        message(R.string.shell_msg_queue_full)
                    } else {
                        message(R.string.shell_msg_queue_full_added, outcome.added, outcome.requested)
                    }
                is QueueAddOutcome.Stopped -> {
                    if (outcome.error.code == NativeErrorCode.CANCELLED) return@launch
                    val reason = friendlyErrorMessage(appContext, outcome.error)
                    navigator?.showMessage(
                        if (outcome.added == 0) {
                            reason
                        } else {
                            "$reason ${appContext.getString(R.string.shell_msg_added_some_to_queue, outcome.added, outcome.requested)}"
                        },
                    )
                }
            }
        }
    }

    /**
     * "Add to playlist" of a whole collection (an album, a playlist): its items ([resolve], fetched
     * here so the sheet can close at once) go to the playlist picker, which leaves [excludeUri]
     * (the playlist itself) out.
     */
    fun pickPlaylistFor(excludeUri: String? = null, resolve: suspend () -> List<String>) {
        launch {
            val uris = resolve()
            if (uris.isEmpty()) message(R.string.shell_msg_nothing_to_add) else navigator?.addToPlaylist(uris, excludeUri)
        }
    }

    /**
     * Adds [uris] to the playlist [playlistUri] ([playlistName]), as Spotify does: it looks at what
     * the playlist holds first (its item URIs, bounded), asks "Already added" when some are in it
     * already, or "Add anyway?" when that couldn't be checked ([AppNavigator.confirmPlaylistAdd]),
     * and stops at the playlist's item limit. Without a connection it says so (nothing could be
     * added either).
     */
    fun addToPlaylist(playlistUri: String, playlistName: String, uris: List<String>) {
        if (uris.isEmpty()) return
        launch {
            val contents = try {
                withTimeoutOrNull(CONTENTS_TIMEOUT_MS) { graph.playlists.contents(playlistUri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNetworkClassError(e)) throw e // the friendly "offline" message, nothing added
                Log.w(TAG, "Items of $playlistUri unavailable", e)
                null
            }
            val plan = planPlaylistAdd(uris, contents)
            val nav = navigator
            when {
                plan.asks && nav != null -> nav.confirmPlaylistAdd(PlaylistAddPrompt(playlistUri, playlistName, plan))
                // Nobody to ask: no silent duplicates.
                else -> sendPlaylistAdd(playlistUri, playlistName, plan, if (plan.asks) PlaylistAddChoice.NEW_ONES else PlaylistAddChoice.ALL)
            }
        }
    }

    /** The user's answer to "Already added" ([prompt]): [choice] is sent. */
    fun addToPlaylist(prompt: PlaylistAddPrompt, choice: PlaylistAddChoice) {
        launch { sendPlaylistAdd(prompt.playlistUri, prompt.playlistName, prompt.plan, choice) }
    }

    /** Sends [choice] of [plan] and says what was added (all of it, part of it for the limit, or none: full). */
    private suspend fun sendPlaylistAdd(playlistUri: String, playlistName: String, plan: PlaylistAddPlan, choice: PlaylistAddChoice) {
        when (val outcome = playlistAddOutcome(plan, choice)) {
            PlaylistAddOutcome.Nothing -> Unit
            PlaylistAddOutcome.Full -> message(R.string.shell_msg_playlist_full, playlistName)
            is PlaylistAddOutcome.Send -> {
                graph.playlists.addItems(playlistUri, outcome.items)
                if (outcome.left == 0) {
                    message(R.string.shell_msg_added_to_playlist, playlistName)
                } else {
                    message(R.string.shell_msg_added_some_to_playlist, outcome.items.size, outcome.items.size + outcome.left, playlistName)
                }
            }
        }
    }

    fun setSaved(uri: String, saved: Boolean, addedRes: Int, removedRes: Int) =
        launch(if (saved) addedRes else removedRes) { graph.library.setSaved(listOf(uri), saved) }

    companion object {
        private const val TAG = "MediaActions"
        private const val LOAD_TIMEOUT_MS = 20_000L
        /**
         * Longest look at what a playlist holds before an add (URIs only: a few small requests,
         * so rarely reached; then the user is asked whether to add anyway).
         */
        private const val CONTENTS_TIMEOUT_MS = 20_000L

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

/**
 * The downloaded members of the collection [contextUri] to queue (see [offlineQueueUris]), as
 * playback's offline loads find them (playlist / album / show membership, or an album's
 * individually downloaded tracks).
 */
internal suspend fun AppGraph.downloadedQueueUris(contextUri: String): List<String> {
    val members = player.environment?.downloadedMembers(contextUri, startUri = null)
        ?: downloads.collections.first().firstOrNull { it.ref.uri == contextUri }?.let { collection ->
            OfflineMembers(collection.itemUris, downloads.downloadedUris.value)
        }
        ?: return emptyList()
    val skipped = if (explicitFilterFlow().first()) {
        downloads.items.first()
            .filter { it.state == DownloadState.COMPLETED && it.uri in members.downloaded }
            .filter { item ->
                when (val meta = decodeDownloadMetadata(json, item.uri, item.metadataJson)) {
                    is DownloadMetadata.OfTrack -> meta.track.explicit
                    is DownloadMetadata.OfEpisode -> meta.episode.explicit
                    null -> false
                }
            }
            .mapTo(HashSet()) { it.uri }
    } else {
        emptySet()
    }
    return offlineQueueUris(members, skipped)
}
