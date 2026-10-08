package com.taehagen.spotifygood.playback

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.model.TrackProvider
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.resumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_resume")

/**
 * What was last played locally, for Media3 playback resumption (BT button, SysUI card, Auto), the
 * "Tap to resume" alert, Assistant's "play something" and the in-app Play fallback. The modes
 * ([shuffle], [smartShuffle], [repeat]) default to off for states stored by older versions.
 *
 * Two forms: a track of its context resumes in [contextUri] (Spotify context semantics); any
 * other track (queued, autoplay, a smart-shuffle suggestion, or a context that cannot be loaded
 * again) resumes as the track list [trackUris], since loading the context would start the
 * context's first track at the saved position (the track is not part of it).
 */
data class ResumeState(
    val contextUri: String?,
    val trackUri: String,
    val positionMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val isEpisode: Boolean,
    /** Shuffle (also set with [smartShuffle], which implies it). */
    val shuffle: Boolean = false,
    val smartShuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /**
     * The track-list form: [trackUri] followed by the visible next tracks of the context and
     * autoplay, in play order (≤ [RESUME_TRACKS]); null for the context form (and for states
     * stored by older versions, which did not record it).
     */
    val trackUris: List<String>? = null,
) {
    /** [trackUris] when usable (it starts with [trackUri]). */
    private val trackList: List<String>?
        get() = trackUris?.takeIf { it.isNotEmpty() && it.first() == trackUri }?.let(::onePass)

    /**
     * The context to resume in; null for the track-list form, when there is none, it is just the
     * track itself, or it cannot be loaded again (a plain track list reports `spotify:web-api`).
     * Applied when reading, so states stored by older versions are repaired too.
     */
    private val resumeContext: String?
        get() = if (trackList != null) null else contextUri?.takeIf { it != trackUri && MediaIds.isResolvableContext(it) }

    /** Media id understood by the session player (see [MediaIds]). */
    val mediaId: String
        get() = resumeContext?.let { MediaIds.inContext(it, trackUri) } ?: trackUri

    /**
     * `player.load` request that starts this session again (e.g. when no Connect device is active).
     * It always names the modes: a load without them resets shuffle and repeat to off. The
     * track-list form plays its list in the saved order (shuffle and smart shuffle off, like the
     * engine's own hand-over of such a window). An episode session plays in order with repeat
     * off, like every podcast load ([PlayerController.withCurrentModes]). Offline,
     * [PlayerController] turns smart shuffle into a plain shuffle ([OfflineLoads.withoutSmartShuffle]).
     */
    fun toPlayRequest(): PlayRequest {
        val list = trackList
        val context = resumeContext
        val request = PlayRequest(
            contextUri = context,
            trackUris = list ?: if (context == null) listOf(trackUri) else null,
            startUri = trackUri,
            startIndex = if (list != null) 0 else null,
            positionMs = positionMs,
            play = true,
        )
        return resumeLoad.applyModes(request)
    }

    /** What the Media3 resume item carries for its load ([ResumeModes], [ResumeLoad.applyTo]). */
    internal val resumeLoad: ResumeLoad
        get() {
            val list = trackList
            val ordered = list != null || isEpisode
            return ResumeLoad(
                trackUris = list,
                shuffle = !ordered && (shuffle || smartShuffle),
                smartShuffle = !ordered && smartShuffle,
                repeat = if (isEpisode) RepeatMode.OFF else repeat,
            )
        }

    companion object {
        /** Longest stored track list (like the engine's hand-over window). */
        const val RESUME_TRACKS = 50

        /** Resume state of a local snapshot at [positionMs]; null if nothing is loaded. */
        fun from(snapshot: PlaybackSnapshot, positionMs: Long = snapshot.positionAt()): ResumeState? {
            val track = snapshot.track ?: return null
            val context = snapshot.context?.uri?.takeIf { it.isNotBlank() }
            val inContext = track.provider == TrackProvider.CONTEXT && context != null &&
                context != track.uri && MediaIds.isResolvableContext(context)
            return ResumeState(
                contextUri = context.takeIf { inContext },
                trackUri = track.uri,
                positionMs = positionMs.coerceAtLeast(0),
                title = track.name,
                artist = track.artistLine.takeIf { it.isNotBlank() },
                album = track.album?.name ?: track.show?.name,
                artworkUrl = track.imageUrl,
                durationMs = (snapshot.durationMs.takeIf { it > 0 } ?: track.durationMs),
                isEpisode = track.isEpisode,
                shuffle = snapshot.shuffle || snapshot.smartShuffle,
                smartShuffle = snapshot.smartShuffle,
                repeat = snapshot.repeat,
                trackUris = if (inContext) null else trackList(track, snapshot),
            )
        }

        /**
         * [current] and the visible next tracks that belong to the context or autoplay (not the
         * user queue, smart-shuffle suggestions or unavailable entries), in play order: one pass.
         * With repeat-all Spirc fills the next tracks past the context's end with the context
         * again (same uids; the delimiter between the passes is normally hidden already). Like the
         * engine's `restore::one_pass`, the walk ends at the first uid seen already (queued and
         * suggested entries count too) or at a second wrap, so the tracks after a wrap complete
         * the pass up to the current track and each one is stored once. Spirc's repeat cycles
         * that pass again after a resume.
         */
        private fun trackList(current: PlaybackTrack, snapshot: PlaybackSnapshot): List<String> {
            val seen = HashSet<String>()
            if (current.uid.isNotEmpty()) seen += current.uid
            val repeating = snapshot.repeat == RepeatMode.CONTEXT
            var wrapped = false
            val list = mutableListOf(current.uri)
            for (t in snapshot.nextTracks) {
                if (list.size >= RESUME_TRACKS) break
                if (t.uri.startsWith(DELIMITER)) {
                    if (repeating && wrapped) break
                    wrapped = wrapped || repeating
                    continue
                }
                if (t.uid.isNotEmpty() && !seen.add(t.uid)) break
                val ours = t.provider == TrackProvider.CONTEXT || t.provider == TrackProvider.AUTOPLAY
                if (ours && isPlayableItem(t.uri)) list += t.uri
            }
            return list
        }

        /**
         * Repairs a list stored by an earlier version of [trackList], which kept the repeated
         * passes: it ends before the start track comes again (a list that really holds its start
         * track twice loses its tail, a rare case).
         */
        private fun onePass(list: List<String>): List<String> {
            val again = list.subList(1, list.size).indexOf(list.first())
            return if (again < 0) list else list.subList(0, again + 1)
        }

        private const val DELIMITER = "spotify:delimiter"

        private fun isPlayableItem(uri: String): Boolean =
            uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")
    }
}

/**
 * The load form of a resume item ([ResumeState.resumeLoad]): the modes, and the track list of the
 * track-list form (null: the item's own media id says what to load).
 */
internal data class ResumeLoad(
    val trackUris: List<String>?,
    val shuffle: Boolean,
    val smartShuffle: Boolean,
    val repeat: RepeatMode,
) {
    fun applyModes(request: PlayRequest): PlayRequest =
        request.copy(shuffle = shuffle || smartShuffle, smartShuffle = smartShuffle, repeat = repeat)

    /**
     * [request] (the session player's load of the resume item's media id) as the stored session's
     * load: its modes, and its track list when the request starts at the list's first track.
     */
    fun applyTo(request: PlayRequest): PlayRequest {
        val withModes = applyModes(request)
        val list = trackUris?.takeIf { it.isNotEmpty() } ?: return withModes
        val start = request.trackUris?.getOrNull(request.startIndex ?: 0) ?: request.startUri
        if (request.contextUri != null || start != list.first()) return withModes
        return withModes.copy(trackUris = list, startIndex = 0, startUri = list.first(), startUid = null)
    }
}

/**
 * The modes of a [ResumeState] on its Media3 item ([LibraryTree.resumeItem]), as request extras
 * under private keys, which the session player reads back ([SpotifyPlayer.handleSetMediaItems])
 * so that a playback resumption, "Tap to resume" or "play something" loads with them. Those
 * items are created in this process and handed to the player directly, so the extras arrive.
 */
internal object ResumeModes {
    private const val SHUFFLE = "com.taehagen.spotifygood.resume.SHUFFLE"
    private const val SMART_SHUFFLE = "com.taehagen.spotifygood.resume.SMART_SHUFFLE"
    private const val REPEAT = "com.taehagen.spotifygood.resume.REPEAT"
    private const val TRACK_URIS = "com.taehagen.spotifygood.resume.TRACK_URIS"

    fun extras(state: ResumeState): Bundle = Bundle().apply {
        val load = state.resumeLoad
        putBoolean(SHUFFLE, load.shuffle)
        putBoolean(SMART_SHUFFLE, load.smartShuffle)
        putString(REPEAT, PlaybackModes.wire(load.repeat))
        load.trackUris?.let { putStringArrayList(TRACK_URIS, ArrayList(it)) }
    }

    /** The resume load in [extras]; null when they carry none (any other item). */
    fun read(extras: Bundle?): ResumeLoad? {
        val repeat = extras?.getString(REPEAT) ?: return null
        return ResumeLoad(
            trackUris = extras.getStringArrayList(TRACK_URIS)?.toList(),
            shuffle = extras.getBoolean(SHUFFLE),
            smartShuffle = extras.getBoolean(SMART_SHUFFLE),
            repeat = PlaybackModes.parseRepeat(repeat),
        )
    }

    /** [request] as the stored session's load when [extras] are a resume item's. */
    fun applyTo(request: PlayRequest, extras: Bundle?): PlayRequest = read(extras)?.applyTo(request) ?: request
}

/**
 * DataStore-backed [ResumeState] (one small preferences file; all I/O off the main thread). The
 * DataStore itself is a process singleton (extension delegate); the app shares one
 * [com.taehagen.spotifygood.AppGraph.resumeStore].
 */
class ResumeStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.resumeDataStore)

    suspend fun read(): ResumeState? = try {
        val p = store.data.first()
        p[TRACK]?.let { track ->
            ResumeState(
                contextUri = p[CONTEXT],
                trackUri = track,
                positionMs = p[POSITION] ?: 0,
                title = p[TITLE],
                artist = p[ARTIST],
                album = p[ALBUM],
                artworkUrl = p[ARTWORK],
                durationMs = p[DURATION],
                isEpisode = p[EPISODE] ?: false,
                shuffle = p[SHUFFLE] ?: false,
                smartShuffle = p[SMART_SHUFFLE] ?: false,
                repeat = PlaybackModes.parseRepeat(p[REPEAT]),
                trackUris = p[TRACK_URIS]?.split(LIST_SEPARATOR)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
            )
        }
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read resume state", e)
        null
    }

    suspend fun save(state: ResumeState) {
        try {
            store.edit { p ->
                p.setOrRemove(CONTEXT, state.contextUri)
                p[TRACK] = state.trackUri
                p[POSITION] = state.positionMs
                p.setOrRemove(TITLE, state.title)
                p.setOrRemove(ARTIST, state.artist)
                p.setOrRemove(ALBUM, state.album)
                p.setOrRemove(ARTWORK, state.artworkUrl)
                if (state.durationMs != null) p[DURATION] = state.durationMs else p.remove(DURATION)
                p[EPISODE] = state.isEpisode
                p[SHUFFLE] = state.shuffle
                p[SMART_SHUFFLE] = state.smartShuffle
                p[REPEAT] = PlaybackModes.wire(state.repeat)
                p.setOrRemove(TRACK_URIS, state.trackUris?.takeIf { it.isNotEmpty() }?.joinToString(LIST_SEPARATOR))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot save resume state", e)
        }
    }

    /** Forget everything (logout). */
    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot clear resume state", e)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value != null) this[key] = value else remove(key)
    }

    private companion object {
        const val TAG = "ResumeStore"
        val CONTEXT = stringPreferencesKey("context")
        val TRACK = stringPreferencesKey("track")
        val POSITION = longPreferencesKey("position")
        val TITLE = stringPreferencesKey("title")
        val ARTIST = stringPreferencesKey("artist")
        val ALBUM = stringPreferencesKey("album")
        val ARTWORK = stringPreferencesKey("artwork")
        val DURATION = longPreferencesKey("duration")
        val EPISODE = booleanPreferencesKey("episode")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val SMART_SHUFFLE = booleanPreferencesKey("smart_shuffle")
        val REPEAT = stringPreferencesKey("repeat")
        /** The track-list form, newline-separated (uris never contain one). */
        val TRACK_URIS = stringPreferencesKey("track_uris")
        const val LIST_SEPARATOR = "\n"
    }
}
