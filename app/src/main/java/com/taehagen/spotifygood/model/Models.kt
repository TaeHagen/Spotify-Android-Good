package com.taehagen.spotifygood.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models shared by the native engine contract (docs/ARCHITECTURE.md §5, §6) and the UI.
// Keep field names in sync with native/spotcore (serde camelCase).

// ---------------------------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------------------------

@Serializable
data class Image(val url: String, val width: Int? = null, val height: Int? = null)

/** Picks the smallest image that is at least [minPx] wide, else the largest available. */
fun List<Image>.best(minPx: Int = 300): String? {
    if (isEmpty()) return null
    val sized = filter { it.width != null }.sortedBy { it.width }
    return (sized.firstOrNull { (it.width ?: 0) >= minPx } ?: sized.lastOrNull() ?: first()).url
}

@Serializable
data class ArtistRef(
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
)

@Serializable
enum class AlbumType {
    @SerialName("album") ALBUM,
    @SerialName("single") SINGLE,
    @SerialName("compilation") COMPILATION,
    @SerialName("ep") EP,
}

@Serializable
data class AlbumRef(
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val releaseDate: String? = null,
    val albumType: AlbumType? = null,
    val totalTracks: Int? = null,
)

@Serializable
data class Track(
    val uri: String,
    val name: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val playable: Boolean = true,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val popularity: Int? = null,
    val hasLyrics: Boolean? = null,
)

@Serializable
data class ShowRef(
    val uri: String,
    val name: String,
    val publisher: String? = null,
    val images: List<Image> = emptyList(),
)

@Serializable
data class Episode(
    val uri: String,
    val name: String,
    val show: ShowRef? = null,
    val description: String = "",
    val durationMs: Long = 0,
    val releaseDate: String? = null,
    val images: List<Image> = emptyList(),
    val explicit: Boolean = false,
    val playable: Boolean = true,
    val resumePositionMs: Long? = null,
    val fullyPlayed: Boolean? = null,
)

@Serializable
data class Album(
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val releaseDate: String? = null,
    val releaseDatePrecision: String? = null,
    val albumType: AlbumType? = null,
    val totalTracks: Int? = null,
    val label: String? = null,
    val copyrights: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
    /** Some track metadata failed to load; those tracks are uri-only placeholders (docs §6.5). */
    val partial: Boolean = false,
) {
    fun toRef() = AlbumRef(uri, name, images, artists, releaseDate, albumType, totalTracks)
}

@Serializable
data class Artist(
    val uri: String,
    val name: String,
    val images: List<Image> = emptyList(),
    val headerImages: List<Image> = emptyList(),
    val biography: String? = null,
    val topTracks: List<Track> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val singles: List<AlbumRef> = emptyList(),
    val compilations: List<AlbumRef> = emptyList(),
    val appearsOn: List<AlbumRef> = emptyList(),
    val related: List<ArtistRef> = emptyList(),
    val following: Boolean? = null,
    /** Some top tracks, releases or related artists failed to load (docs §6.5). */
    val partial: Boolean = false,
)

@Serializable
data class PlaylistOwner(val username: String, val displayName: String? = null)

@Serializable
data class PlaylistRef(
    val uri: String,
    val name: String,
    val description: String? = null,
    val images: List<Image> = emptyList(),
    val owner: PlaylistOwner? = null,
    val totalTracks: Int? = null,
)

@Serializable
data class PlaylistItem(
    val uid: String? = null,
    val addedAt: Long? = null,
    val addedBy: String? = null,
    val track: Track? = null,
    val episode: Episode? = null,
) {
    val uri: String? get() = track?.uri ?: episode?.uri
}

@Serializable
data class Playlist(
    val uri: String,
    val name: String,
    val description: String? = null,
    val images: List<Image> = emptyList(),
    val owner: PlaylistOwner? = null,
    val totalTracks: Int? = null,
    val collaborative: Boolean = false,
    val isOwnedByMe: Boolean = false,
    val canEdit: Boolean = false,
    val revision: String? = null,
    val offset: Int = 0,
    val total: Int = 0,
    val items: List<PlaylistItem> = emptyList(),
    val following: Boolean? = null,
    /** Some item metadata failed to load; those items are uri-only placeholders (docs §6.5). */
    val partial: Boolean = false,
) {
    fun toRef() = PlaylistRef(uri, name, description, images, owner, total)
}

@Serializable
data class Show(
    val uri: String,
    val name: String,
    val publisher: String? = null,
    val images: List<Image> = emptyList(),
    val description: String = "",
    val episodes: List<Episode> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val following: Boolean? = null,
    /** Some episode metadata failed to load; those episodes are uri-only placeholders (docs §6.5). */
    val partial: Boolean = false,
) {
    fun toRef() = ShowRef(uri, name, publisher, images)
}

@Serializable
enum class MediaType {
    @SerialName("track") TRACK,
    @SerialName("album") ALBUM,
    @SerialName("artist") ARTIST,
    @SerialName("playlist") PLAYLIST,
    @SerialName("show") SHOW,
    @SerialName("episode") EPISODE,
    @SerialName("collection") COLLECTION,
}

/** A generic tile/row reference used by home, search top result and recently played. */
@Serializable
data class MediaRef(
    val type: MediaType,
    val uri: String,
    val name: String,
    val subtitle: String? = null,
    val images: List<Image> = emptyList(),
)

@Serializable
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val shows: List<ShowRef> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val topResult: MediaRef? = null,
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() &&
            playlists.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}

@Serializable
data class HomeSection(val id: String, val title: String, val items: List<MediaRef> = emptyList())

@Serializable
data class HomeFeed(val sections: List<HomeSection> = emptyList())

@Serializable
enum class RootlistEntryType {
    @SerialName("playlist") PLAYLIST,
    @SerialName("folder") FOLDER,
}

@Serializable
data class RootlistEntry(
    val type: RootlistEntryType,
    val uri: String? = null,
    val name: String,
    val images: List<Image> = emptyList(),
    val owner: PlaylistOwner? = null,
    val children: List<RootlistEntry> = emptyList(),
    val collaborative: Boolean = false,
    /** True when the logged-in user may add/remove items (owner or collaborative). */
    val canEdit: Boolean = false,
)

/** `library.playlists`; [partial]: some playlist names could not be looked up right now and are missing. */
@Serializable
data class Rootlist(val items: List<RootlistEntry> = emptyList(), val partial: Boolean = false) {
    /** All playlists, folders flattened (depth first). */
    fun flatPlaylists(): List<RootlistEntry> = buildList {
        fun walk(entries: List<RootlistEntry>) {
            entries.forEach { if (it.type == RootlistEntryType.FOLDER) walk(it.children) else add(it) }
        }
        walk(items)
    }
}

/**
 * A `library.*` page: one item per slot of the window (`items.size == min(limit, total - offset)`).
 * [partial]: some item metadata failed to load; those items are uri-only placeholders (docs §6.3).
 */
@Serializable
data class Page<T>(val total: Int = 0, val items: List<T> = emptyList(), val partial: Boolean = false)

@Serializable
data class SavedTrack(val addedAt: Long? = null, val track: Track)

@Serializable
data class SavedAlbum(val addedAt: Long? = null, val album: AlbumRef)

@Serializable
data class SavedArtist(val addedAt: Long? = null, val artist: ArtistRef)

@Serializable
data class SavedShow(val addedAt: Long? = null, val show: ShowRef)

@Serializable
data class SavedEpisode(val addedAt: Long? = null, val episode: Episode)

@Serializable
enum class LyricsSyncType {
    @SerialName("LINE_SYNCED") LINE_SYNCED,
    @SerialName("UNSYNCED") UNSYNCED,
    @SerialName("SYLLABLE_SYNCED") SYLLABLE_SYNCED,
}

@Serializable
data class LyricsLine(val startTimeMs: Long = 0, val words: String = "")

@Serializable
data class LyricsColors(val background: Int? = null, val text: Int? = null, val highlightText: Int? = null)

@Serializable
data class Lyrics(
    val syncType: LyricsSyncType = LyricsSyncType.UNSYNCED,
    val lines: List<LyricsLine> = emptyList(),
    val provider: String? = null,
    val colors: LyricsColors? = null,
)

@Serializable
data class User(
    val username: String,
    val displayName: String? = null,
    val images: List<Image> = emptyList(),
    val product: String? = null,
    val country: String? = null,
    val explicitFilter: Boolean = false,
    /** Another user's public playlists (`catalog.user {username}`); empty for me (use the rootlist). */
    val publicPlaylists: List<PlaylistRef> = emptyList(),
) {
    val isPremium: Boolean get() = product == null || product == "premium"
}

// ---------------------------------------------------------------------------------------------
// Playback / Connect
// ---------------------------------------------------------------------------------------------

@Serializable
enum class PlaybackSource {
    @SerialName("local") LOCAL,
    @SerialName("remote") REMOTE,
    @SerialName("none") NONE,
}

@Serializable
enum class PlaybackStatus {
    @SerialName("stopped") STOPPED,
    @SerialName("loading") LOADING,
    @SerialName("playing") PLAYING,
    @SerialName("paused") PAUSED,
}

@Serializable
enum class RepeatMode {
    @SerialName("off") OFF,
    @SerialName("context") CONTEXT,
    @SerialName("track") TRACK,
}

@Serializable
enum class TrackProvider {
    @SerialName("context") CONTEXT,
    @SerialName("queue") QUEUE,
    @SerialName("autoplay") AUTOPLAY,
    @SerialName("suggestion") SUGGESTION,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class ActiveDeviceRef(val id: String, val name: String, val type: DeviceType = DeviceType.UNKNOWN)

@Serializable
enum class ContextType {
    @SerialName("playlist") PLAYLIST,
    @SerialName("album") ALBUM,
    @SerialName("artist") ARTIST,
    @SerialName("collection") COLLECTION,
    @SerialName("search") SEARCH,
    @SerialName("show") SHOW,
    @SerialName("station") STATION,
    @SerialName("tracks") TRACKS,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class PlaybackContext(val uri: String, val name: String? = null, val type: ContextType = ContextType.UNKNOWN)

@Serializable
data class PlaybackTrack(
    val uri: String,
    val uid: String = "",
    val provider: TrackProvider = TrackProvider.CONTEXT,
    val name: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
    val isEpisode: Boolean = false,
    val show: ShowRef? = null,
) {
    val artistLine: String get() = if (isEpisode) show?.name.orEmpty() else artists.joinToString { it.name }
    val imageUrl: String? get() = (album?.images ?: show?.images)?.best(300)
}

@Serializable
data class PlaybackRestrictions(
    val canSkipPrev: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSeek: Boolean = true,
    val canToggleShuffle: Boolean = true,
    val canToggleRepeat: Boolean = true,
    val canPause: Boolean = true,
)

@Serializable
data class PlaybackSnapshot(
    val source: PlaybackSource = PlaybackSource.NONE,
    val offline: Boolean = false,
    val activeDevice: ActiveDeviceRef? = null,
    val status: PlaybackStatus = PlaybackStatus.STOPPED,
    val positionMs: Long = 0,
    val positionTimestampMs: Long = 0,
    val playbackSpeed: Double = 1.0,
    val durationMs: Long = 0,
    val context: PlaybackContext? = null,
    val track: PlaybackTrack? = null,
    val prevTracks: List<PlaybackTrack> = emptyList(),
    val nextTracks: List<PlaybackTrack> = emptyList(),
    val shuffle: Boolean = false,
    val smartShuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val isPlayingAutoplay: Boolean = false,
    val restrictions: PlaybackRestrictions = PlaybackRestrictions(),
    val volume: Int = 0,
    val lastError: String? = null,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val isActive: Boolean get() = track != null && status != PlaybackStatus.STOPPED

    /** Current position extrapolated to [nowMs] (wall clock). */
    fun positionAt(nowMs: Long = System.currentTimeMillis()): Long {
        if (track == null) return 0
        val base = if (status == PlaybackStatus.PLAYING && positionTimestampMs > 0) {
            positionMs + ((nowMs - positionTimestampMs).coerceAtLeast(0) * playbackSpeed).toLong()
        } else {
            positionMs
        }
        return if (durationMs > 0) base.coerceIn(0, durationMs) else base.coerceAtLeast(0)
    }

    companion object {
        val EMPTY = PlaybackSnapshot()
    }
}

@Serializable
enum class DeviceType {
    @SerialName("smartphone") SMARTPHONE,
    @SerialName("computer") COMPUTER,
    @SerialName("tablet") TABLET,
    @SerialName("speaker") SPEAKER,
    @SerialName("tv") TV,
    @SerialName("avr") AVR,
    @SerialName("stb") STB,
    @SerialName("audio_dongle") AUDIO_DONGLE,
    @SerialName("game_console") GAME_CONSOLE,
    @SerialName("cast_audio") CAST_AUDIO,
    @SerialName("cast_video") CAST_VIDEO,
    @SerialName("automobile") AUTOMOBILE,
    @SerialName("smartwatch") SMARTWATCH,
    @SerialName("chromebook") CHROMEBOOK,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class AudioOutputType {
    @SerialName("speaker") SPEAKER,
    @SerialName("bluetooth") BLUETOOTH,
    @SerialName("line_out") LINE_OUT,
    @SerialName("airplay") AIRPLAY,
    @SerialName("car") CAR,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class AudioOutputInfo(val type: AudioOutputType = AudioOutputType.UNKNOWN, val name: String? = null)

@Serializable
data class ConnectDevice(
    val id: String,
    val name: String,
    val type: DeviceType = DeviceType.UNKNOWN,
    val volume: Int = 0,
    val supportsVolume: Boolean = true,
    val isActive: Boolean = false,
    val isThisDevice: Boolean = false,
    val isGroup: Boolean = false,
    val canPlay: Boolean = true,
    val brand: String? = null,
    val model: String? = null,
    val audioOutput: AudioOutputInfo? = null,
)

@Serializable
data class DeviceList(
    val activeDeviceId: String? = null,
    val thisDeviceId: String? = null,
    val devices: List<ConnectDevice> = emptyList(),
) {
    val activeDevice: ConnectDevice? get() = devices.firstOrNull { it.id == activeDeviceId }
    val others: List<ConnectDevice> get() = devices.filter { !it.isThisDevice }
}

// ---------------------------------------------------------------------------------------------
// Session / engine
// ---------------------------------------------------------------------------------------------

@Serializable
enum class SessionState {
    @SerialName("stopped") STOPPED,
    @SerialName("connecting") CONNECTING,
    @SerialName("online") ONLINE,
    @SerialName("reconnecting") RECONNECTING,
    @SerialName("offline") OFFLINE,
    @SerialName("error") ERROR,
}

@Serializable
data class NativeErrorInfo(
    val code: String = "INTERNAL",
    val message: String = "",
    val retryAfterMs: Long? = null,
    val context: String? = null,
)

@Serializable
data class SessionEvent(
    val state: SessionState = SessionState.STOPPED,
    val error: NativeErrorInfo? = null,
    val user: User? = null,
    val deviceId: String? = null,
    val nextRetryMs: Long? = null,
)

/** Reusable librespot credentials (never logged, stored encrypted). */
@Serializable
data class StoredCredentials(val username: String, val authType: Int, val authData: String)

@Serializable
enum class Bitrate(val kbps: Int) {
    @SerialName("96") LOW(96),
    @SerialName("160") NORMAL(160),
    @SerialName("320") HIGH(320),
}

@Serializable
enum class NormalizePregain {
    @SerialName("quiet") QUIET,
    @SerialName("normal") NORMAL,
    @SerialName("loud") LOUD,
}

/** Settings the native engine needs (subset of app settings). */
@Serializable
data class EngineSettings(
    val bitrate: Int = 160,
    val normalize: Boolean = true,
    val normalizePregain: NormalizePregain = NormalizePregain.NORMAL,
    val autoplay: Boolean = true,
    val gapless: Boolean = true,
    val deviceName: String = "Android",
    val streamingCacheMb: Int = 1024,
    val offline: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------------------------

@Serializable
enum class DownloadState {
    @SerialName("queued") QUEUED,
    @SerialName("preparing") PREPARING,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class DownloadProgress(
    val uri: String,
    val state: DownloadState,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: NativeErrorInfo? = null,
)

@Serializable
data class Normalisation(
    val trackGainDb: Float = 0f,
    val trackPeak: Float = 1f,
    val albumGainDb: Float = 0f,
    val albumPeak: Float = 1f,
)

/** Everything needed to play a download offline (docs/ARCHITECTURE.md §6.4). */
@Serializable
data class OfflineTrackRecord(
    val uri: String,
    val playedUri: String? = null,
    val fileId: String,
    val format: String,
    val keyHex: String,
    val path: String,
    val sizeBytes: Long,
    val normalisation: Normalisation = Normalisation(),
    val track: Track? = null,
    val episode: Episode? = null,
    val imagePath: String? = null,
)
