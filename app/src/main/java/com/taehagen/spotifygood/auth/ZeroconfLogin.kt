package com.taehagen.spotifygood.auth

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.taehagen.spotifygood.engine.SpotifyEngine

/**
 * "Use another device" login: the engine advertises itself via mDNS (Spotify Connect zeroconf)
 * until another Spotify app hands over credentials. Wi-Fi drops multicast packets unless a
 * [WifiManager.MulticastLock] is held, so one is held exactly while [run] is in progress and
 * released on completion, failure, cancellation or [release].
 */
internal class ZeroconfLogin(context: Context, private val engine: SpotifyEngine) {
    private val multicastLock: WifiManager.MulticastLock? = try {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    } catch (e: RuntimeException) {
        Log.w(TAG, "No multicast lock available", e)
        null
    }

    suspend fun run(timeoutMs: Long = TIMEOUT_MS) {
        acquire()
        try {
            engine.loginWithZeroconf(timeoutMs)
        } finally {
            release()
        }
    }

    /** Releases the multicast lock now (idempotent). */
    fun release() {
        try {
            if (multicastLock?.isHeld == true) multicastLock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Releasing multicast lock failed", e)
        }
    }

    private fun acquire() {
        try {
            multicastLock?.acquire()
        } catch (e: RuntimeException) {
            // Missing permission or Wi-Fi off: discovery may still work on some networks.
            Log.w(TAG, "Acquiring multicast lock failed", e)
        }
    }

    private companion object {
        const val TAG = "ZeroconfLogin"
        const val LOCK_TAG = "spotifygood:zeroconf"
        const val TIMEOUT_MS = 180_000L
    }
}
