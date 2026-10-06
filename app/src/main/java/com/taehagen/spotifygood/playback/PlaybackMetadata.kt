package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.nativebridge.QueueMetadataEvent

/**
 * LRU of display metadata (uri → metadata-only [PlaybackTrack] template) fed by `queueMetadata`
 * events and by snapshot entries that already carry metadata. Used to fill the gaps of later
 * snapshots (docs/ARCHITECTURE.md §5: the engine sends metadata for a uri only once).
 *
 * Not thread-safe: owned by the single collector coroutine of [PlaybackRepository].
 */
internal class PlaybackMetadataCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = object : LinkedHashMap<String, PlaybackTrack>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaybackTrack>): Boolean =
            size > capacity
    }

    val size: Int get() = entries.size

    operator fun get(uri: String): PlaybackTrack? = entries[uri]

    /** Adds the metadata of an event; returns true if anything new was learned. */
    fun addAll(event: QueueMetadataEvent): Boolean {
        var changed = false
        event.tracks.forEach { changed = put(it.toTemplate()) || changed }
        event.episodes.forEach { changed = put(it.toTemplate()) || changed }
        return changed
    }

    /** Remembers snapshot entries that carry metadata (so it survives the engine omitting it later). */
    fun remember(snapshot: PlaybackSnapshot) {
        snapshot.track?.let(::rememberTrack)
        snapshot.prevTracks.forEach(::rememberTrack)
        snapshot.nextTracks.forEach(::rememberTrack)
    }

    /** [snapshot] with missing name/artists/album/duration filled from the cache. */
    fun merge(snapshot: PlaybackSnapshot): PlaybackSnapshot {
        val track = snapshot.track?.let(::fill)
        val prev = snapshot.prevTracks.fillAll()
        val next = snapshot.nextTracks.fillAll()
        val duration = if (snapshot.durationMs > 0) snapshot.durationMs else track?.durationMs ?: 0
        if (track === snapshot.track && prev === snapshot.prevTracks && next === snapshot.nextTracks &&
            duration == snapshot.durationMs
        ) {
            return snapshot
        }
        return snapshot.copy(track = track, prevTracks = prev, nextTracks = next, durationMs = duration)
    }

    private fun List<PlaybackTrack>.fillAll(): List<PlaybackTrack> {
        var result: MutableList<PlaybackTrack>? = null
        forEachIndexed { i, t ->
            val filled = fill(t)
            if (filled !== t) {
                if (result == null) result = toMutableList()
                result[i] = filled
            }
        }
        return result ?: this
    }

    private fun fill(track: PlaybackTrack): PlaybackTrack =
        if (track.isComplete()) track else track.fillFrom(entries[track.uri])

    private fun rememberTrack(track: PlaybackTrack) {
        if (track.name == null) return
        put(
            PlaybackTrack(
                uri = track.uri,
                name = track.name,
                artists = track.artists,
                album = track.album,
                durationMs = track.durationMs,
                explicit = track.explicit,
                isEpisode = track.isEpisode,
                show = track.show,
            ),
        )
    }

    private fun put(template: PlaybackTrack): Boolean {
        val existing = entries[template.uri]
        val merged = existing?.let { template.fillFrom(it) } ?: template
        if (merged == existing) return false
        entries[template.uri] = merged
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 1_500
    }
}

internal fun Track.toTemplate(): PlaybackTrack = PlaybackTrack(
    uri = uri,
    name = name,
    artists = artists,
    album = album,
    durationMs = durationMs.takeIf { it > 0 },
    explicit = explicit,
)

internal fun Episode.toTemplate(): PlaybackTrack = PlaybackTrack(
    uri = uri,
    name = name,
    durationMs = durationMs.takeIf { it > 0 },
    explicit = explicit,
    isEpisode = true,
    show = show?.let { if (it.images.isEmpty() && images.isNotEmpty()) it.copy(images = images) else it },
)

private fun PlaybackTrack.isComplete(): Boolean =
    name != null && (durationMs ?: 0) > 0 &&
        (if (isEpisode) show != null else artists.isNotEmpty() && album?.images?.isNotEmpty() == true)

/** Fills fields missing in this entry from [template]; returns `this` if nothing changes. */
internal fun PlaybackTrack.fillFrom(template: PlaybackTrack?): PlaybackTrack {
    if (template == null) return this
    val newName = name ?: template.name
    val newArtists = artists.ifEmpty { template.artists }
    val newAlbum = mergeAlbum(album, template.album)
    val newDuration = durationMs?.takeIf { it > 0 } ?: template.durationMs?.takeIf { it > 0 } ?: durationMs
    val newExplicit = explicit || template.explicit
    val newEpisode = isEpisode || template.isEpisode
    val newShow = show?.let { s ->
        if (s.images.isEmpty() && template.show?.images?.isNotEmpty() == true) s.copy(images = template.show.images) else s
    } ?: template.show
    if (newName == name && newArtists === artists && newAlbum === album && newDuration == durationMs &&
        newExplicit == explicit && newEpisode == isEpisode && newShow === show
    ) {
        return this
    }
    return copy(
        name = newName,
        artists = newArtists,
        album = newAlbum,
        durationMs = newDuration,
        explicit = newExplicit,
        isEpisode = newEpisode,
        show = newShow,
    )
}

private fun mergeAlbum(current: AlbumRef?, template: AlbumRef?): AlbumRef? = when {
    current == null -> template
    template == null -> current
    current.images.isEmpty() && template.images.isNotEmpty() ->
        current.copy(images = template.images, name = current.name.ifEmpty { template.name })
    else -> current
}
