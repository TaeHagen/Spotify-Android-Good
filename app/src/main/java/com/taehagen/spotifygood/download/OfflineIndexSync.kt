package com.taehagen.spotifygood.download

import android.util.Log
import com.taehagen.spotifygood.data.callUnitOffMain
import com.taehagen.spotifygood.data.putStrings
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the native offline index in step with the database (docs/ARCHITECTURE.md §6.4).
 *
 * The `offline.*` calls run as independent native tasks, so they can take effect in another order
 * than they were sent, and the engine's `offline.setIndex` carries a snapshot read before later
 * commits and removals. Every change of the set of completed downloads therefore takes the next
 * number ([next]) in the same critical section (the manager's mutation lock) as its database write,
 * and its `offline.add` / `offline.remove` carries it; a snapshot carries the number of the last
 * change it contains ([last], read with the rows, announced with [beginSnapshot]). The native index
 * applies each URI's newest change and lets a snapshot set only URIs that did not change after it.
 */
internal class OfflineIndexSync(private val rpc: NativeRpc) {
    private val seq = AtomicLong()

    /** Number of a new change. Call under the mutation lock, together with the database write. */
    fun next(): Long = seq.incrementAndGet()

    /** Number of the last change. Call under the mutation lock, together with the snapshot read. */
    fun last(): Long = seq.get()

    /** Registers completed downloads (change [seq]). Best effort: the next engine start re-pushes all. */
    suspend fun add(records: List<OfflineTrackRecord>, seq: Long) {
        if (records.isEmpty()) return
        val tracks = rpc.json.encodeToJsonElement(ListSerializer(OfflineTrackRecord.serializer()), records)
        call("offline.add", rpcArgs {
            put("tracks", tracks)
            put("seq", seq)
        })
    }

    /** Unregisters downloads (change [seq]). Best effort, like [add]. */
    suspend fun remove(uris: List<String>, seq: Long) {
        uris.chunked(CHUNK).forEach { chunk ->
            call("offline.remove", rpcArgs {
                putStrings("uris", chunk)
                put("seq", seq)
            })
        }
    }

    /** The next `offline.setIndex` without a number is a snapshot containing the changes up to [seq]. */
    suspend fun beginSnapshot(seq: Long) {
        call("offline.beginIndex", rpcArgs { put("seq", seq) })
    }

    private suspend fun call(method: String, args: JsonObject) {
        try {
            if (withTimeoutOrNull(TIMEOUT_MS) { rpc.callUnitOffMain(method, args) } == null) Log.w(TAG, "$method timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not fatal: the engine pushes the whole index again (offline.setIndex) at its next start.
            Log.w(TAG, "$method failed", e)
        }
    }

    private companion object {
        const val TAG = "OfflineIndexSync"
        const val TIMEOUT_MS = 10_000L
        const val CHUNK = 500
    }
}
