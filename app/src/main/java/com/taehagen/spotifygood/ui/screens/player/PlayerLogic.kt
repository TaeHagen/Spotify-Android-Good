package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.LyricsLine
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackRestrictions
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.playback.AudioOutput
import com.taehagen.spotifygood.playback.OutputKind
import com.taehagen.spotifygood.playback.ResumeState
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure helpers of the player UI (no Android framework calls; covered by JVM unit tests).

// ---------------------------------------------------------------------------------------------
// Like
// ---------------------------------------------------------------------------------------------

/** The heart's state: [liked] of item [uri]; null [liked] while unknown (the lookup is pending or failed). */
@Immutable
internal data class LikeState(val uri: String?, val liked: Boolean?) {
    /**
     * What a heart tap on [currentUri] acts on: the shown state when it is known and belongs to that
     * item (right after a track change the heart may still show the previous one's), else null and
     * the tap is ignored.
     */
    fun shownFor(currentUri: String?): Boolean? = if (currentUri != null && currentUri == uri) liked else null

    companion object {
        val NONE = LikeState(null, null)
    }
}

// ---------------------------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------------------------

/** Formats a playback time as `m:ss` (or `h:mm:ss` from one hour). Negative values clamp to 0. */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) {
            append(hours)
            append(':')
            if (minutes < 10) append('0')
        }
        append(minutes)
        append(':')
        if (seconds < 10) append('0')
        append(seconds)
    }
}

/**
 * Image of the current item for the player surfaces. Offline (or without a CDN image) a downloaded
 * item shows its downloaded cover ([downloadedCover], an absolute file path, see
 * [com.taehagen.spotifygood.ui.components.imageData]), so there is artwork without a network;
 * otherwise the CDN image.
 */
internal fun playerArtwork(imageUrl: String?, downloadedCover: String?, offline: Boolean): String? =
    if (downloadedCover != null && (offline || imageUrl.isNullOrBlank())) downloadedCover else imageUrl

/** Duration of the snapshot's current item, preferring the engine's value. */
internal fun PlaybackSnapshot.effectiveDurationMs(): Long =
    if (durationMs > 0) durationMs else track?.durationMs ?: 0L

// ---------------------------------------------------------------------------------------------
// Last session placeholder (cold start)
// ---------------------------------------------------------------------------------------------

/** Only play/pause works on the resume placeholder (play loads the saved session). */
private val PLACEHOLDER_RESTRICTIONS = PlaybackRestrictions(
    canSkipPrev = false,
    canSkipNext = false,
    canSeek = false,
    canToggleShuffle = false,
    canToggleRepeat = false,
    canPause = true,
)

/** Context type of a Spotify context URI, for snapshots that are not built by the engine. */
internal fun contextTypeOf(uri: String): ContextType = when {
    uri.endsWith(":collection") -> ContextType.COLLECTION
    uri.startsWith("spotify:playlist:") || uri.contains(":playlist:") -> ContextType.PLAYLIST
    uri.startsWith("spotify:album:") -> ContextType.ALBUM
    uri.startsWith("spotify:artist:") -> ContextType.ARTIST
    uri.startsWith("spotify:show:") -> ContextType.SHOW
    uri.startsWith("spotify:station:") -> ContextType.STATION
    uri.startsWith("spotify:search:") -> ContextType.SEARCH
    else -> ContextType.UNKNOWN
}

/**
 * The last local session as a paused snapshot, shown by the mini player and Now Playing while
 * nothing is loaded (cold start, or the engine stopped after idling), like Spotify does. Its
 * artist/album/show refs carry only names (blank URIs: no page to open), and only play/pause is
 * possible: play resumes, i.e. loads, exactly this saved session.
 */
internal fun ResumeState.toPlaceholderSnapshot(): PlaybackSnapshot {
    val images = artworkUrl?.takeIf { it.isNotBlank() }?.let { listOf(Image(it)) }.orEmpty()
    val track = if (isEpisode) {
        PlaybackTrack(
            uri = trackUri,
            name = title,
            durationMs = durationMs,
            isEpisode = true,
            show = ShowRef(uri = "", name = album ?: artist.orEmpty(), images = images),
        )
    } else {
        PlaybackTrack(
            uri = trackUri,
            name = title,
            artists = artist?.takeIf { it.isNotBlank() }?.let { listOf(ArtistRef(uri = "", name = it)) }.orEmpty(),
            album = if (album != null || images.isNotEmpty()) AlbumRef(uri = "", name = album.orEmpty(), images = images) else null,
            durationMs = durationMs,
        )
    }
    val duration = durationMs?.coerceAtLeast(0) ?: 0
    return PlaybackSnapshot(
        source = PlaybackSource.NONE,
        status = PlaybackStatus.PAUSED,
        positionMs = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0),
        durationMs = duration,
        context = contextUri?.takeIf { it.isNotBlank() && it != trackUri }?.let { PlaybackContext(uri = it, type = contextTypeOf(it)) },
        track = track,
        restrictions = PLACEHOLDER_RESTRICTIONS,
    )
}

/** Whether the resume placeholder may stand in for [real]: nothing is loaded and no remote device plays. */
internal fun wantsResumePlaceholder(real: PlaybackSnapshot): Boolean =
    real.track == null && real.source != PlaybackSource.REMOTE

/**
 * What the player surfaces show: the engine's snapshot, or the resume [placeholder] while the
 * engine has nothing loaded; LOADING while [resuming] (play was tapped, the session is loading).
 */
internal fun displaySnapshot(real: PlaybackSnapshot, placeholder: PlaybackSnapshot?, resuming: Boolean): PlaybackSnapshot = when {
    placeholder == null || !wantsResumePlaceholder(real) -> real
    resuming -> placeholder.copy(status = PlaybackStatus.LOADING)
    else -> placeholder
}

// ---------------------------------------------------------------------------------------------
// Skip back / forward (podcast episodes)
// ---------------------------------------------------------------------------------------------

/** Step of the podcast skip-back / skip-forward buttons. */
internal const val SEEK_STEP_MS = 15_000L

/** Quick repeated steps within this window build on each other (see [seekStepBase]). */
internal const val SEEK_CHAIN_WINDOW_MS = 1_500L

/** A skip the engine may not reflect yet: [targetMs] in [uri], requested at [atMs] (monotonic clock). */
internal data class PendingSeek(val uri: String, val targetMs: Long, val atMs: Long)

/**
 * Where the next skip step starts. Right after a step the snapshot may still show the old position
 * (remote devices take a moment), so quick taps build on the previous target, plus the time played
 * since, instead of all starting from the same stale position.
 */
internal fun seekStepBase(
    uri: String,
    positionMs: Long,
    pending: PendingSeek?,
    nowMs: Long,
    playing: Boolean,
    windowMs: Long = SEEK_CHAIN_WINDOW_MS,
): Long {
    if (pending == null || pending.uri != uri) return positionMs
    val elapsed = nowMs - pending.atMs
    if (elapsed !in 0..windowMs) return positionMs
    return pending.targetMs + if (playing) elapsed else 0
}

/** [baseMs] moved by [deltaMs], kept inside the item (0..[durationMs] when the duration is known). */
internal fun seekStepTarget(baseMs: Long, deltaMs: Long, durationMs: Long): Long {
    val target = baseMs + deltaMs
    return if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
}

// ---------------------------------------------------------------------------------------------
// Lyrics
// ---------------------------------------------------------------------------------------------

/**
 * Index of the lyrics line active at [positionMs]: the last line whose start time is
 * `<= positionMs` (binary search; lines are sorted by start time). Returns -1 before the first line.
 */
internal fun lyricsLineIndexAt(lines: List<LyricsLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startTimeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/**
 * Lines shown by the compact lyrics preview: [size] lines starting one line before the current one
 * (so the current line is the second), clamped to the available lines.
 */
internal fun lyricsPreviewWindow(lineCount: Int, currentIndex: Int, size: Int = 6): IntRange {
    if (lineCount <= 0 || size <= 0) return IntRange.EMPTY
    val maxStart = (lineCount - size).coerceAtLeast(0)
    val start = (currentIndex - 1).coerceIn(0, maxStart)
    val end = (start + size - 1).coerceAtMost(lineCount - 1)
    return start..end
}

/** Spotify sends lyrics colours as signed ARGB ints; some omit the alpha byte. */
internal fun opaqueArgb(argb: Int): Int = if (argb ushr 24 == 0) argb or 0xFF000000.toInt() else argb

// ---------------------------------------------------------------------------------------------
// Queue
// ---------------------------------------------------------------------------------------------

/** One upcoming row of the queue screen. [key] is unique and stable across snapshots. */
@Immutable
internal data class QueueEntry(
    val track: PlaybackTrack,
    val key: String,
    /** Index of the track in `PlaybackSnapshot.nextTracks`. */
    val nextIndex: Int,
) {
    val isSuggestion: Boolean get() = track.provider == TrackProvider.SUGGESTION
    /** Commands (skip/remove/move) need the Connect uid. */
    val hasUid: Boolean get() = track.uid.isNotBlank()
}

@Immutable
internal data class QueueSections(
    /** Tracks the user queued ("Next in queue"); the only reorderable section. */
    val queued: List<QueueEntry> = emptyList(),
    /** Remaining context tracks, with smart-shuffle suggestions interleaved ("Next from …"). */
    val upNext: List<QueueEntry> = emptyList(),
    /** Autoplay recommendations that follow the context. */
    val autoplay: List<QueueEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = queued.isEmpty() && upNext.isEmpty() && autoplay.isEmpty()

    companion object {
        val EMPTY = QueueSections()
    }
}

/** Unique, stable list keys: the Connect uid when present, else `uri@index`; duplicates get `#n`. */
internal fun queueKeys(tracks: List<PlaybackTrack>): List<String> {
    val seen = HashMap<String, Int>(tracks.size * 2)
    return tracks.mapIndexed { index, track ->
        val base = track.uid.ifBlank { "${track.uri}@$index" }
        val count = seen[base] ?: 0
        seen[base] = count + 1
        if (count == 0) base else "$base#$count"
    }
}

/**
 * Splits `nextTracks` into the queue screen sections. Unavailable tracks are hidden. While autoplay
 * is already playing every non-queued track belongs to the autoplay section.
 */
internal fun partitionQueue(nextTracks: List<PlaybackTrack>, isPlayingAutoplay: Boolean): QueueSections {
    if (nextTracks.isEmpty()) return QueueSections.EMPTY
    val keys = queueKeys(nextTracks)
    val queued = ArrayList<QueueEntry>()
    val upNext = ArrayList<QueueEntry>()
    val autoplay = ArrayList<QueueEntry>()
    nextTracks.forEachIndexed { index, track ->
        val entry = QueueEntry(track, keys[index], index)
        when (track.provider) {
            TrackProvider.QUEUE -> queued += entry
            TrackProvider.AUTOPLAY -> autoplay += entry
            TrackProvider.CONTEXT, TrackProvider.SUGGESTION -> if (isPlayingAutoplay) autoplay += entry else upNext += entry
            TrackProvider.UNAVAILABLE -> Unit
        }
    }
    return QueueSections(queued, upNext, autoplay)
}

/** Returns a copy with the element at [from] moved to [to] (both clamped). */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices) return this
    val target = to.coerceIn(0, lastIndex)
    if (from == target) return this
    val result = toMutableList()
    val item = result.removeAt(from)
    result.add(target, item)
    return result
}

/**
 * The `toIndex` for `queue.move` after a drag reordered the queued section from [before] to
 * [after] (lists of keys): the moved item's final position within the user queue, or null when its
 * position did not change. Queued tracks always lead `nextTracks`, so this is also its index there.
 */
internal fun queueMoveTarget(before: List<String>, after: List<String>, movedKey: String): Int? {
    val from = before.indexOf(movedKey)
    val to = after.indexOf(movedKey)
    if (from < 0 || to < 0 || from == to) return null
    return to
}

/**
 * Applies optimistic local edits (rows removed but not yet confirmed, a reorder not yet reflected by
 * the engine) to fresh sections. Unknown keys in [pendingOrder] are ignored; queued rows missing from
 * it keep their relative order at the end.
 */
internal fun QueueSections.withPendingEdits(removedKeys: Set<String>, pendingOrder: List<String>?): QueueSections {
    if (removedKeys.isEmpty() && pendingOrder == null) return this
    val queuedVisible = queued.filter { it.key !in removedKeys }
    val ordered = if (pendingOrder == null) {
        queuedVisible
    } else {
        val byKey = queuedVisible.associateBy { it.key }
        val inOrder = pendingOrder.mapNotNull { byKey[it] }
        val orderSet = pendingOrder.toHashSet()
        inOrder + queuedVisible.filter { it.key !in orderSet }
    }
    return QueueSections(
        queued = ordered,
        upNext = if (removedKeys.isEmpty()) upNext else upNext.filter { it.key !in removedKeys },
        autoplay = if (removedKeys.isEmpty()) autoplay else autoplay.filter { it.key !in removedKeys },
    )
}

/**
 * True when the engine's queue already shows the optimistic [pendingOrder] (rows added or removed
 * since are ignored; only the relative order of the rows present in both counts).
 */
internal fun QueueSections.matchesOrder(pendingOrder: List<String>): Boolean {
    val actual = queued.map { it.key }
    val actualSet = actual.toHashSet()
    val pendingSet = pendingOrder.toHashSet()
    return actual.filter { it in pendingSet } == pendingOrder.filter { it in actualSet }
}

/** Display model for list rows (metadata may still be missing: name is then null). */
internal fun PlaybackTrack.toTrack(placeholderName: String): Track = Track(
    uri = uri,
    name = name ?: placeholderName,
    artists = artists,
    album = album ?: show?.let { AlbumRef(uri = it.uri, name = it.name, images = it.images) },
    durationMs = durationMs ?: 0L,
    explicit = explicit,
    playable = provider != TrackProvider.UNAVAILABLE,
)

/** Target for the shared actions sheet (tracks and podcast episodes). */
internal fun PlaybackTrack.toActionTarget(
    placeholderName: String,
    contextUri: String? = null,
    queueUid: String? = null,
): MediaActionTarget = if (isEpisode) {
    MediaActionTarget.EpisodeTarget(
        Episode(
            uri = uri,
            name = name ?: placeholderName,
            show = show,
            durationMs = durationMs ?: 0L,
            explicit = explicit,
            images = show?.images.orEmpty(),
        ),
    )
} else {
    MediaActionTarget.TrackTarget(
        track = toTrack(placeholderName),
        contextUri = contextUri,
        queueUid = queueUid?.takeIf { it.isNotBlank() },
    )
}

// ---------------------------------------------------------------------------------------------
// Devices / volume
// ---------------------------------------------------------------------------------------------

/** What the mini player / now playing show about where audio goes. */
@Immutable
internal sealed interface DeviceIndicator {
    /** Another Spotify Connect device is playing. */
    data class Remote(val name: String, val type: DeviceType) : DeviceIndicator
    /** This phone plays through a non-speaker output (Bluetooth, wired, USB…). */
    data class LocalOutput(val name: String, val kind: OutputKind) : DeviceIndicator
    data object None : DeviceIndicator
}

internal fun deviceIndicator(snapshot: PlaybackSnapshot, output: AudioOutput?): DeviceIndicator {
    val active = snapshot.activeDevice
    return when {
        snapshot.source == PlaybackSource.REMOTE && active != null -> DeviceIndicator.Remote(active.name, active.type)
        snapshot.source != PlaybackSource.REMOTE && output != null && output.kind != OutputKind.SPEAKER ->
            DeviceIndicator.LocalOutput(output.name, output.kind)
        else -> DeviceIndicator.None
    }
}

/**
 * Whether the remote Connect device playing [snapshot] takes volume changes (false for local or no
 * playback). An unknown or unlisted device counts as supporting it, like the playback service and
 * the [com.taehagen.spotifygood.model.ConnectDevice.supportsVolume] default.
 */
internal fun remoteVolumeSupported(snapshot: PlaybackSnapshot, devices: DeviceList): Boolean {
    if (snapshot.source != PlaybackSource.REMOTE) return false
    val active = snapshot.activeDevice ?: return true
    return devices.devices.firstOrNull { it.id == active.id }?.supportsVolume != false
}

internal const val MAX_CONNECT_VOLUME = 65535

internal fun volumeToFraction(volume: Int): Float = (volume.toFloat() / MAX_CONNECT_VOLUME).coerceIn(0f, 1f)

internal fun fractionToVolume(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * MAX_CONNECT_VOLUME).roundToInt()

internal fun volumePercent(volume: Int): Int = (volumeToFraction(volume) * 100).roundToInt()

/** True when two slider positions are close enough to consider the device caught up. */
internal fun volumeSettled(reported: Int, requestedFraction: Float): Boolean =
    abs(volumeToFraction(reported) - requestedFraction) < 0.02f

// ---------------------------------------------------------------------------------------------
// Sharing
// ---------------------------------------------------------------------------------------------

private val SHAREABLE_TYPES = setOf("track", "album", "artist", "playlist", "show", "episode")

/** `spotify:track:<id>` → `https://open.spotify.com/track/<id>`; null for non-shareable URIs. */
internal fun spotifyShareUrl(uri: String): String? {
    val parts = uri.split(':')
    if (parts.size != 3 || parts[0] != "spotify") return null
    val (_, type, id) = parts
    if (type !in SHAREABLE_TYPES || id.isBlank()) return null
    return "https://open.spotify.com/$type/$id"
}

// ---------------------------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------------------------

/**
 * Tones an artwork colour for use behind white text: keeps the hue, caps the saturation and sets the
 * HSL lightness to at most [maxLightness] (and at least [minLightness]). Alpha is forced opaque.
 */
internal fun toneForBackground(
    argb: Int,
    maxLightness: Float = 0.30f,
    minLightness: Float = 0.10f,
    maxSaturation: Float = 0.70f,
): Int {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    var hue = 0f
    var saturation = 0f
    if (delta > 0f) {
        saturation = delta / (1f - abs(2f * lightness - 1f))
        hue = when (max) {
            r -> ((g - b) / delta).mod(6f)
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
    }
    val newLightness = lightness.coerceIn(minLightness, maxLightness)
    val newSaturation = saturation.coerceIn(0f, maxSaturation)
    return hslToArgb(hue, newSaturation, newLightness)
}

internal fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val hPrime = (hue / 60f).mod(6f)
    val x = c * (1f - abs(hPrime.mod(2f) - 1f))
    val m = lightness - c / 2f
    val (r1, g1, b1) = when {
        hPrime < 1f -> Triple(c, x, 0f)
        hPrime < 2f -> Triple(x, c, 0f)
        hPrime < 3f -> Triple(0f, c, x)
        hPrime < 4f -> Triple(0f, x, c)
        hPrime < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}
