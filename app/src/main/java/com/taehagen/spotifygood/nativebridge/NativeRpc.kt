package com.taehagen.spotifygood.nativebridge

import android.util.Log
import com.taehagen.spotifygood.model.NativeErrorInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspend-style calls into the native engine. Coroutine cancellation cancels the native task.
 */
class NativeRpc(val json: Json) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CancellableContinuation<JsonElement>>()

    /** Raw call. Throws [NativeException] on a native error. */
    suspend fun callRaw(method: String, args: JsonObject = EMPTY): JsonElement =
        suspendCancellableCoroutine { cont ->
            val id = nextId.getAndIncrement()
            pending[id] = cont
            cont.invokeOnCancellation {
                if (pending.remove(id) != null) runCatching { NativeBridge.nativeCancel(id) }
            }
            try {
                NativeBridge.nativeCall(id, method, args.toString())
            } catch (t: Throwable) {
                pending.remove(id)
                cont.resumeWithException(t)
            }
        }

    /** Typed call; the result is decoded on [Dispatchers.Default] (pages can be large). */
    suspend inline fun <reified T> call(method: String, args: JsonObject = EMPTY): T {
        val element = callRaw(method, args)
        return withContext(Dispatchers.Default) { json.decodeFromJsonElement<T>(element) }
    }

    /** Call whose result is ignored (still waits for completion and surfaces errors). */
    suspend fun callUnit(method: String, args: JsonObject = EMPTY) {
        callRaw(method, args)
    }

    inline fun <reified A> args(value: A): JsonObject = json.encodeToJsonElement(value) as JsonObject

    /** Fire and forget: no result, errors only logged natively. */
    fun fire(method: String, args: JsonObject = EMPTY) {
        runCatching { NativeBridge.nativeCall(0, method, args.toString()) }
            .onFailure { Log.w(TAG, "fire $method failed", it) }
    }

    /** Invoked from [NativeCallbacks.onResult] on a native thread. */
    internal fun complete(requestId: Long, ok: Boolean, payload: String) {
        val cont = pending.remove(requestId) ?: return
        try {
            if (ok) {
                cont.resume(json.parseToJsonElement(payload.ifEmpty { "{}" }))
            } else {
                val info = runCatching { json.decodeFromString<NativeErrorInfo>(payload) }
                    .getOrElse { NativeErrorInfo(NativeErrorCode.INTERNAL, payload) }
                cont.resumeWithException(NativeException(info))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to deliver result $requestId", t)
            if (cont.isActive) cont.resumeWithException(NativeException(NativeErrorInfo(NativeErrorCode.INTERNAL, t.message ?: "")))
        }
    }

    companion object {
        private const val TAG = "NativeRpc"
        val EMPTY = JsonObject(emptyMap())
    }
}
