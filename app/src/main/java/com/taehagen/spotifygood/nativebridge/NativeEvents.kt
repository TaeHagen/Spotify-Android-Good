package com.taehagen.spotifygood.nativebridge

import android.util.Log
import com.taehagen.spotifygood.model.CollectionChangedPush
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.DownloadProgress
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.LibraryPush
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaylistChangedPush
import com.taehagen.spotifygood.model.RootlistChangedPush
import com.taehagen.spotifygood.model.SessionEvent
import com.taehagen.spotifygood.model.StoredCredentials
import com.taehagen.spotifygood.model.Track
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QueueMetadataEvent(val tracks: List<Track> = emptyList(), val episodes: List<Episode> = emptyList())

/**
 * Fan-out of native events (docs/ARCHITECTURE.md §5). State-like events are exposed as
 * [StateFlow]s (latest value wins, replayed to new collectors), the rest as [SharedFlow]s with a
 * bounded buffer; `credentials` replays its latest value and `queueMetadata` its last few events.
 */
class NativeEvents(private val json: Json) {
    private val _session = MutableStateFlow(SessionEvent())
    val session: StateFlow<SessionEvent> = _session.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackSnapshot.EMPTY)
    val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    private val _devices = MutableStateFlow(DeviceList())
    val devices: StateFlow<DeviceList> = _devices.asStateFlow()

    private val _credentials = MutableSharedFlow<StoredCredentials>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val credentials: SharedFlow<StoredCredentials> = _credentials.asSharedFlow()

    // The engine sends metadata for a URI only once; replay keeps early events (before
    // PlaybackRepository subscribes, it is created lazily) from being lost.
    private val _queueMetadata = MutableSharedFlow<QueueMetadataEvent>(
        replay = QUEUE_METADATA_REPLAY,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val queueMetadata: SharedFlow<QueueMetadataEvent> = _queueMetadata.asSharedFlow()

    private val _downloads = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val downloads: SharedFlow<DownloadProgress> = _downloads.asSharedFlow()

    private val _errors = MutableSharedFlow<NativeErrorInfo>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<NativeErrorInfo> = _errors.asSharedFlow()

    // Library changes made elsewhere (`playlistChanged`, `rootlistChanged`, `collectionChanged`):
    // no replay, they matter to whoever listens when they come (LibraryPushes).
    private val _libraryPushes = MutableSharedFlow<LibraryPush>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val libraryPushes: SharedFlow<LibraryPush> = _libraryPushes.asSharedFlow()

    /** Invoked from [NativeCallbacks.onEvent] on a native thread. */
    internal fun dispatch(type: String, payload: String) {
        try {
            when (type) {
                "session" -> _session.value = json.decodeFromString<SessionEvent>(payload)
                "playback" -> _playback.value = json.decodeFromString<PlaybackSnapshot>(payload)
                "devices" -> _devices.value = json.decodeFromString<DeviceList>(payload)
                "credentials" -> _credentials.tryEmit(json.decodeFromString<StoredCredentials>(payload))
                "queueMetadata" -> _queueMetadata.tryEmit(json.decodeFromString<QueueMetadataEvent>(payload))
                "download" -> _downloads.tryEmit(json.decodeFromString<DownloadProgress>(payload))
                "error" -> _errors.tryEmit(json.decodeFromString<NativeErrorInfo>(payload))
                "playlistChanged" -> _libraryPushes.tryEmit(json.decodeFromString<PlaylistChangedPush>(payload))
                "rootlistChanged" -> _libraryPushes.tryEmit(json.decodeFromString<RootlistChangedPush>(payload))
                "collectionChanged" -> _libraryPushes.tryEmit(json.decodeFromString<CollectionChangedPush>(payload))
                else -> Log.d(TAG, "Ignoring unknown event $type")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Malformed $type event", t)
        }
    }

    /** Clears per-account state (logout). */
    fun reset() {
        _session.value = SessionEvent()
        _playback.value = PlaybackSnapshot.EMPTY
        _devices.value = DeviceList()
        resetAccountReplays()
    }

    /**
     * Forgets the replayed events of the previous account (another account logs in). The live
     * session, playback and device states stay: the engine republishes them for the new session.
     */
    fun resetAccountReplays() {
        _credentials.resetReplayCache()
        _queueMetadata.resetReplayCache()
    }

    private companion object {
        const val TAG = "NativeEvents"
        const val QUEUE_METADATA_REPLAY = 8
    }
}

/** Routes JNI callbacks to [NativeRpc] and [NativeEvents]. */
class NativeCallbacksImpl(private val rpc: NativeRpc, private val events: NativeEvents) : NativeCallbacks {
    override fun onResult(requestId: Long, ok: Boolean, json: String) = rpc.complete(requestId, ok, json)
    override fun onEvent(type: String, json: String) = events.dispatch(type, json)
}
