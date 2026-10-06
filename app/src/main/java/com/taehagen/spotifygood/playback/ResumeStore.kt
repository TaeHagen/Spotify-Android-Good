package com.taehagen.spotifygood.playback

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taehagen.spotifygood.model.PlaybackSnapshot
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.resumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_resume")

/** What was last played locally, for Media3 playback resumption (BT button, SysUI card, Auto). */
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
) {
    /** The context to resume in; null when there is none or it is just the track itself. */
    private val resumeContext: String?
        get() = contextUri?.takeIf { it != trackUri && !MediaIds.isItemUri(it) }

    /** Media id understood by the session player (see [MediaIds]). */
    val mediaId: String
        get() = resumeContext?.let { MediaIds.inContext(it, trackUri) } ?: trackUri

    /** `player.load` request that starts this session again (e.g. when no Connect device is active). */
    fun toPlayRequest(): PlayRequest {
        val context = resumeContext
        return PlayRequest(
            contextUri = context,
            trackUris = if (context == null) listOf(trackUri) else null,
            startUri = trackUri,
            positionMs = positionMs,
            play = true,
        )
    }

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
            )
        }
    }
}

/**
 * DataStore-backed [ResumeState] (one small preferences file; all I/O off the main thread). The
 * DataStore itself is a process singleton (extension delegate); the app shares one
 * [com.taehagen.spotifygood.AppGraph.resumeStore].
 */
class ResumeStore(context: Context) {
    private val store = context.applicationContext.resumeDataStore

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
    }
}
