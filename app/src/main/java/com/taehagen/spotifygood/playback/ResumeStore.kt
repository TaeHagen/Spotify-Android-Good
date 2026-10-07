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
import com.taehagen.spotifygood.model.RepeatMode
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.resumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_resume")

/**
 * What was last played locally, for Media3 playback resumption (BT button, SysUI card, Auto), the
 * "Tap to resume" alert, Assistant's "play something" and the in-app Play fallback. The modes
 * ([shuffle], [smartShuffle], [repeat]) default to off for states stored by older versions.
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
) {
    /**
     * The context to resume in; null when there is none, it is just the track itself, or it cannot
     * be loaded again (a plain track list reports `spotify:web-api`). Applied when reading, so
     * states stored by older versions are repaired too.
     */
    private val resumeContext: String?
        get() = contextUri?.takeIf { it != trackUri && MediaIds.isResolvableContext(it) }

    /** Media id understood by the session player (see [MediaIds]). */
    val mediaId: String
        get() = resumeContext?.let { MediaIds.inContext(it, trackUri) } ?: trackUri

    /**
     * `player.load` request that starts this session again (e.g. when no Connect device is active).
     * It always names the modes: a load without them resets shuffle and repeat to off. Offline,
     * [PlayerController] turns smart shuffle into a plain shuffle ([OfflineLoads.withoutSmartShuffle]).
     */
    fun toPlayRequest(): PlayRequest {
        val context = resumeContext
        return withModes(
            PlayRequest(
                contextUri = context,
                trackUris = if (context == null) listOf(trackUri) else null,
                startUri = trackUri,
                positionMs = positionMs,
                play = true,
            ),
        )
    }

    /** [request] with the modes of this session. */
    private fun withModes(request: PlayRequest): PlayRequest =
        request.copy(shuffle = shuffle || smartShuffle, smartShuffle = smartShuffle, repeat = repeat)

    companion object {
        /** Resume state of a local snapshot at [positionMs]; null if nothing is loaded. */
        fun from(snapshot: PlaybackSnapshot, positionMs: Long = snapshot.positionAt()): ResumeState? {
            val track = snapshot.track ?: return null
            return ResumeState(
                contextUri = snapshot.context?.uri?.takeIf { it.isNotBlank() },
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
            )
        }
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

    fun extras(state: ResumeState): Bundle = Bundle().apply {
        putBoolean(SHUFFLE, state.shuffle)
        putBoolean(SMART_SHUFFLE, state.smartShuffle)
        putString(REPEAT, PlaybackModes.wire(state.repeat))
    }

    /** Whether [extras] are those of a resume item (the stored session). */
    fun isResumeItem(extras: Bundle?): Boolean = extras?.getString(REPEAT) != null

    /** [request] with the modes in [extras]; unchanged when they carry none (any other item). */
    fun applyTo(request: PlayRequest, extras: Bundle?): PlayRequest {
        val repeat = extras?.getString(REPEAT) ?: return request
        val smartShuffle = extras.getBoolean(SMART_SHUFFLE)
        return request.copy(
            shuffle = extras.getBoolean(SHUFFLE) || smartShuffle,
            smartShuffle = smartShuffle,
            repeat = PlaybackModes.parseRepeat(repeat),
        )
    }
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
    }
}
