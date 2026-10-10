package com.taehagen.spotifygood.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.download.DownloadRules.DownloadMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What decides whether downloads run continuously or in bursts ([DownloadRules.downloadMode],
 * docs/ARCHITECTURE.md §9.7): the app [visible] and the device on external power. Read at each
 * decision point (the sticky battery broadcast, no receiver); watched ([modes]) only while a host
 * waits out a pause, with a power connected / disconnected receiver registered for that time.
 */
internal class DownloadConditions(private val context: Context, private val visible: StateFlow<Boolean>) {
    /** The device is plugged in (charging or full). */
    fun pluggedIn(): Boolean {
        // Protected system broadcasts: only the system sends them.
        val battery = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** The mode now, for a host that may run long or not ([longRunningHost]). */
    fun mode(longRunningHost: Boolean): DownloadMode = DownloadRules.downloadMode(visible.value, pluggedIn(), longRunningHost)

    /** The mode, now and whenever the app's visibility or the power changes, while collected. */
    fun modes(longRunningHost: Boolean): Flow<DownloadMode> =
        combine(visible, power()) { shown, plugged -> DownloadRules.downloadMode(shown, plugged, longRunningHost) }.distinctUntilChanged()

    private fun power(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.action == Intent.ACTION_POWER_CONNECTED)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        trySend(pluggedIn())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
