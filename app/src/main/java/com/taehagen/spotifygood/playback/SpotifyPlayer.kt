package com.taehagen.spotifygood.playback

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaConstants
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 view of Spotify playback (local or a remote Connect device), built from
 * [PlaybackRepository.snapshot] (docs/ARCHITECTURE.md §9.4; research §1):
 * * playlist = [QueueWindow] (10 previous + current + 50 next, unique uids, play order);
 * * state: IDLE without a track, BUFFERING while loading, READY otherwise; `playWhenReady` while
 *   playing/loading; position extrapolated from the snapshot; while nothing plays, IDLE with a
 *   player error when there is one to show (logged out, Premium required, a failed start), so Auto
 *   and other controllers tell the user (the playlist is kept);
 * * commands gated by Spotify restrictions; remote devices expose `DeviceInfo(REMOTE, 0..100)` and
 *   device-volume commands (hardware volume keys control the remote device); local playback is
 *   `DeviceInfo(LOCAL)` without volume commands (the system stream volume is synced natively).
 *
 * Every handler sends a command through [PlayerController] and completes once the engine's next
 * snapshot arrives (bounded), so controllers see the optimistic placeholder state instead of a
 * flicker back to the old state. Main thread only; call [refresh] whenever an input changed.
 */
internal class SpotifyPlayer(
    context: Context,
    private val playback: PlaybackRepository,
    private val controller: PlayerController,
    private val devices: DevicesRepository,
    private val volume: VolumeSync,
    private val audioSessionId: Int,
    private val downloadedUris: () -> List<String>,
    /** Absolute path of the downloaded cover of a track / episode uri (offline artwork), if any. */
    private val downloadedImage: (String) -> String? = { null },
    /** The error to publish while nothing plays (logged out, Premium, failed start), see [PlayerErrors]. */
    private val playerError: () -> PlaybackException? = { null },
    /** A controller retries (`prepare()`, e.g. Android Auto's retry): drop sticky errors. */
    private val onRetry: () -> Unit = {},
    /**
     * A command that needs the engine (play, load, seek, queue, modes, volume) is about to be
     * sent: the service then holds its engine holder (a pause or stop does not start anything).
     */
    private val onCommand: () -> Unit = {},
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Window of the last published state (handlers map indices back to queue uids with it). */
    private var queueWindow: QueueWindow = QueueWindow.EMPTY
    private var itemCache: Map<String, CachedItem> = emptyMap()
    private var unmutePercent = DEFAULT_UNMUTE_PERCENT
    private var mutedByUs = false
    /** Last remote volume we sent, so quick volume-key steps accumulate before the snapshot catches up. */
    private val remoteVolume = RemoteVolumeTarget(SystemClock::elapsedRealtime)
    private var remoteVolumeExpiry: Job? = null

    private data class ItemKey(
        val track: PlaybackTrack,
        val contextUri: String?,
        val remoteDevice: String?,
        val durationMs: Long,
        val seekable: Boolean,
        /** Downloaded cover, preferred over the CDN url (part of the key: downloads come and go). */
        val imagePath: String?,
    )

    private class CachedItem(val key: ItemKey, val data: MediaItemData)

    /** Re-reads the snapshot/devices/volume. Main thread. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = playback.snapshot.value
        val w = QueueWindow.build(s)
        queueWindow = w
        val hasItem = !w.isEmpty
        // Podcasts skip back / forward 15 s (notification, Auto, Wear), like Now Playing.
        val episodeSkips = hasItem && w.current?.track?.isEpisode == true && s.restrictions.canSeek
        val remote = s.source == PlaybackSource.REMOTE
        val remoteName = if (remote) s.activeDevice?.name else null
        val remoteVolumeSupported = remote && s.activeDevice?.let { active ->
            devices.devices.value.devices.firstOrNull { it.id == active.id }?.supportsVolume
        } != false
        val r = s.restrictions

        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_RELEASE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_AUDIO_ATTRIBUTES,
                COMMAND_GET_DEVICE_VOLUME,
            )
            .addIf(COMMAND_SEEK_TO_MEDIA_ITEM, hasItem)
            .addIf(COMMAND_SEEK_TO_DEFAULT_POSITION, hasItem)
            .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, hasItem && r.canSeek)
            .addIf(COMMAND_SEEK_BACK, episodeSkips)
            .addIf(COMMAND_SEEK_FORWARD, episodeSkips)
            .addIf(COMMAND_SEEK_TO_NEXT, hasItem && r.canSkipNext)
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasItem && r.canSkipNext)
            .addIf(COMMAND_SEEK_TO_PREVIOUS, hasItem && r.canSkipPrev)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasItem && r.canSkipPrev)
            .addIf(COMMAND_SET_SHUFFLE_MODE, hasItem && r.canToggleShuffle)
            .addIf(COMMAND_SET_REPEAT_MODE, hasItem && r.canToggleRepeat)
            .addIf(COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, remoteVolumeSupported)
            .addIf(COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS, remoteVolumeSupported)
            .build()
            .let { if (!r.canPause && s.isPlaying) it.buildUpon().remove(COMMAND_PLAY_PAUSE).build() else it }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(buildItems(w, s, remoteName))
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAudioSessionId(audioSessionId)
            .setSeekBackIncrementMs(EPISODE_SKIP_MS)
            .setSeekForwardIncrementMs(EPISODE_SKIP_MS)
            .setShuffleModeEnabled(s.shuffle || s.smartShuffle)
            .setRepeatMode(
                when (s.repeat) {
                    RepeatMode.OFF -> REPEAT_MODE_OFF
                    RepeatMode.CONTEXT -> REPEAT_MODE_ALL
                    RepeatMode.TRACK -> REPEAT_MODE_ONE
                },
            )

        if (remote) {
            // While a target we sent is in flight, report it instead of the stale snapshot value
            // (the system volume UI would otherwise bounce between old and new).
            val percent = remoteVolume.reported(s.activeDevice?.id, VolumeMath.connectToPercent(s.volume))
            if (percent > 0) mutedByUs = false
            builder.setDeviceInfo(REMOTE_DEVICE_INFO)
                .setDeviceVolume(percent)
                .setIsDeviceMuted(mutedByUs && percent == 0)
        } else {
            remoteVolume.clear()
            val min = volume.minIndex
            val max = volume.maxIndex.coerceAtLeast(min)
            builder.setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).setMinVolume(min).setMaxVolume(max).build())
                .setDeviceVolume(volume.streamIndex.value.coerceIn(min, max))
        }

        if (hasItem) {
            val loading = s.status == PlaybackStatus.LOADING
            val speed = s.playbackSpeed.toFloat().takeIf { it > 0f } ?: 1f
            builder.setCurrentMediaItemIndex(w.currentIndex)
                .setPlaybackState(if (loading) STATE_BUFFERING else STATE_READY)
                .setIsLoading(loading)
                .setPlayWhenReady(
                    s.status == PlaybackStatus.PLAYING || loading,
                    if (remote) PLAY_WHEN_READY_CHANGE_REASON_REMOTE else PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )
                .setPlaybackParameters(PlaybackParameters(speed))
                // Extrapolates from this snapshot (wall clock); the next snapshot replaces it.
                .setContentPositionMs(PositionSupplier { s.positionAt() })
        } else {
            builder.setPlaybackState(STATE_IDLE)
                .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        }
        playerError()?.let { error ->
            // Media3 allows a player error only in STATE_IDLE.
            builder.setPlaybackState(STATE_IDLE)
                .setIsLoading(false)
                .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlayerError(error)
        }
        return builder.build()
    }

    private fun buildItems(w: QueueWindow, s: PlaybackSnapshot, remoteName: String?): List<MediaItemData> {
        if (w.isEmpty) {
            itemCache = emptyMap()
            return emptyList()
        }
        val contextUri = s.context?.uri?.takeIf { it.isNotBlank() }
        val cache = HashMap<String, CachedItem>(w.entries.size)
        val items = w.entries.mapIndexed { i, entry ->
            val isCurrent = i == w.currentIndex
            val durationMs = if (isCurrent && s.durationMs > 0) s.durationMs else entry.track.durationMs ?: 0
            val key = ItemKey(
                track = entry.track,
                contextUri = contextUri,
                remoteDevice = remoteName.takeIf { isCurrent },
                durationMs = durationMs,
                seekable = s.restrictions.canSeek,
                imagePath = downloadedImage(entry.track.uri),
            )
            val cached = itemCache[entry.uid]?.takeIf { it.key == key } ?: CachedItem(key, itemData(entry.uid, key))
            cache[entry.uid] = cached
            cached.data
        }
        itemCache = cache
        return items
    }

    private fun itemData(uid: String, key: ItemKey): MediaItemData {
        val t = key.track
        val extras = if (t.explicit) {
            Bundle().apply { putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT) }
        } else {
            null
        }
        val artist = t.artistLine.ifBlank { null }
        val deviceLine = key.remoteDevice?.let { context.getString(R.string.playback_playing_on, it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(t.name)
            // SysUI media controls (API 30+: QS / lock screen player) and Wear show only title and
            // artist, so the "Playing on <device>" line goes into the artist there (docs §8). Older
            // releases render our notification, whose content text adds the subtitle instead
            // (the notification provider of PlaybackService), keeping the artist clean.
            .setArtist(if (Build.VERSION.SDK_INT >= DeviceLine.IN_ARTIST_SDK) DeviceLine.join(context, artist, deviceLine) else artist)
            .setAlbumTitle(t.album?.name ?: t.show?.name)
            // The downloaded cover works offline (notification, lock screen, Auto) and is the one
            // the download stored; the CDN url (best(300)) usually names a different image file.
            .setArtworkUri(artworkUri(context, key.imagePath) ?: artworkUri(context, t.imageUrl))
            .setSubtitle(deviceLine)
            .setDurationMs(key.durationMs.takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(if (t.isEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        // Only loadable contexts go into the id (controllers cache and replay it).
        val mediaId = key.contextUri?.takeIf { it != t.uri && MediaIds.isResolvableContext(it) }?.let { MediaIds.inContext(it, t.uri) } ?: t.uri
        return MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (key.durationMs > 0) key.durationMs * 1_000 else C.TIME_UNSET)
            .setIsSeekable(key.seekable)
            .setIsDynamic(false)
            .build()
    }

    // ---- handlers -------------------------------------------------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onCommand()
        return track(if (playWhenReady) controller.resumeAsync() else controller.pauseAsync())
    }

    override fun handlePrepare(): ListenableFuture<*> {
        onCommand()
        // Auto's "retry" (and Media3's play button in STATE_IDLE) prepare first.
        controller.clearFailure()
        onRetry()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> = track(controller.pauseAsync())

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        onCommand()
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        val w = queueWindow
        return when (seekCommand) {
            // Spotify decides what next/previous mean (restart when > 3 s, autoplay, repeat).
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> track(controller.nextAsync())
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> track(controller.previousAsync())
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD ->
                track(controller.seekAsync(position))
            else -> {
                val target = w.entries.getOrNull(mediaItemIndex) ?: return Futures.immediateVoidFuture()
                when {
                    mediaItemIndex == w.currentIndex -> track(controller.seekAsync(position))
                    mediaItemIndex > w.currentIndex -> {
                        val ops = mutableListOf(controller.skipToAsync(target.uid))
                        if (position > 0) ops += controller.seekAsync(position)
                        track(*ops.toTypedArray())
                    }
                    else -> {
                        // Already played items: step back with Spotify's "previous" (which first
                        // restarts the current track when it played for more than 3 s).
                        val steps = (w.currentIndex - mediaItemIndex) +
                            if (playback.snapshot.value.positionAt() > RESTART_THRESHOLD_MS) 1 else 0
                        track(*Array(steps) { controller.previousAsync() })
                    }
                }
            }
        }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        onCommand()
        val s = playback.snapshot.value
        return if (!shuffleModeEnabled && s.smartShuffle) {
            track(controller.setSmartShuffleAsync(false), controller.setShuffleAsync(false))
        } else {
            track(controller.setShuffleAsync(shuffleModeEnabled))
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        onCommand()
        return track(
            controller.setRepeatAsync(
                when (repeatMode) {
                    REPEAT_MODE_ALL -> RepeatMode.CONTEXT
                    REPEAT_MODE_ONE -> RepeatMode.TRACK
                    else -> RepeatMode.OFF
                },
            ),
        )
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        onCommand()
        val plan = MediaIds.plan(mediaItems.map { it.mediaId }, startIndex, downloadedUris)
            ?: return Futures.immediateVoidFuture()
        val request = PlayRequest(
            contextUri = plan.contextUri,
            trackUris = plan.trackUris,
            startUri = plan.startUri,
            startIndex = plan.startIndex,
            positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0),
            // Media3 semantics: setMediaItems keeps playWhenReady; controllers call play() next.
            play = playWhenReady,
        )
        // The stored session (playback resumption, "Tap to resume", "play something") comes back
        // with its shuffle / repeat: its item carries them (LibraryTree.resumeItem).
        val withModes = ResumeModes.applyTo(request, mediaItems.singleOrNull()?.requestMetadata?.extras)
        // Media-session loads (Auto, Assistant, watches, resumption) play on this phone, never on
        // the pending Connect target nor on another active device: in a car, a speaker at home
        // would be wrong (and the stored session must not overwrite what it plays now).
        return trackLoad(controller.playAsync(withModes, toPendingTarget = false, onThisPhone = true))
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val uris = mediaItems.mapNotNull { MediaIds.itemUriOf(it.mediaId) }
        if (uris.isEmpty()) return Futures.immediateVoidFuture()
        onCommand()
        return track(controller.addToQueueAsync(uris))
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val w = queueWindow
        // Only upcoming entries can be removed from a Spotify queue.
        val ops = (maxOf(fromIndex, w.currentIndex + 1) until toIndex)
            .mapNotNull { w.entries.getOrNull(it)?.uid }
            .map { controller.removeFromQueueAsync(it) }
        if (ops.isNotEmpty()) onCommand()
        return if (ops.isEmpty()) Futures.immediateVoidFuture() else track(*ops.toTypedArray())
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val w = queueWindow
        val uid = w.entries.getOrNull(fromIndex)?.uid
        if (uid == null || toIndex - fromIndex != 1 || fromIndex <= w.currentIndex || newIndex <= w.currentIndex) {
            return Futures.immediateVoidFuture()
        }
        onCommand()
        return track(controller.moveInQueueAsync(uid, newIndex - w.currentIndex - 1))
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        mutedByUs = false
        return sendRemoteVolume(deviceVolume)
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume((currentRemotePercent() + VOLUME_STEP_PERCENT).coerceAtMost(100), flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume((currentRemotePercent() - VOLUME_STEP_PERCENT).coerceAtLeast(0), flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        return if (muted) {
            currentRemotePercent().takeIf { it > 0 }?.let { unmutePercent = it }
            mutedByUs = true
            sendRemoteVolume(0)
        } else {
            handleSetDeviceVolume(unmutePercent, flags)
        }
    }

    /** Sends [percent] to the active (remote) device and remembers it as the base of the next step. */
    private fun sendRemoteVolume(percent: Int): ListenableFuture<*> {
        onCommand()
        val target = percent.coerceIn(0, 100)
        remoteVolume.set(target, playback.snapshot.value.activeDevice?.id)
        // Re-publish from the snapshot once the target expires unconfirmed (e.g. a failed PUT).
        remoteVolumeExpiry?.cancel()
        remoteVolumeExpiry = scope.launch {
            delay((remoteVolume.remainingMs() ?: 0) + 1)
            invalidateState()
        }
        return track(controller.setVolumeAsync(VolumeMath.percentToConnect(target)))
    }

    /** The remote volume a relative step starts from: our in-flight target, else the device's. */
    private fun currentRemotePercent(): Int {
        val s = playback.snapshot.value
        return remoteVolume.base(s.activeDevice?.id, VolumeMath.connectToPercent(s.volume))
    }

    /**
     * Future completing when all [ops] finished and the engine published a new snapshot (or after
     * [settleMs]), so the placeholder state is replaced by the confirmed one without flicker.
     */
    private fun track(vararg ops: Deferred<Boolean>, settleMs: Long = SETTLE_MS): ListenableFuture<*> {
        val before = playback.snapshot.value
        return scope.future {
            var ok = true
            for (op in ops) ok = op.await() && ok
            if (ok) withTimeoutOrNull(settleMs) { playback.snapshot.first { it != before } }
            Unit
        }
    }

    /**
     * [track] for a load: completes once the loaded item shows on this phone, or the start
     * failed ([LoadSettle]), so Media3's placeholder and foreground last over a cold session's
     * activation; bounded by [LOAD_SETTLE_MS] after the command went through.
     */
    private fun trackLoad(op: Deferred<Boolean>): ListenableFuture<*> {
        val before = playback.snapshot.value
        return scope.future {
            if (op.await()) LoadSettle.await(before, playback.snapshot, controller.failure, LOAD_SETTLE_MS)
            Unit
        }
    }

    internal companion object {
        /** Skip back / forward of podcast episodes (the Now Playing ±15 s buttons). */
        const val EPISODE_SKIP_MS = 15_000L
        private const val SETTLE_MS = 2_000L
        /** A cold context resolve can take a while after the load command went through. */
        private const val LOAD_SETTLE_MS = 15_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val VOLUME_STEP_PERCENT = 5
        private const val DEFAULT_UNMUTE_PERCENT = 50

        private val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        private val REMOTE_DEVICE_INFO: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()
    }
}
