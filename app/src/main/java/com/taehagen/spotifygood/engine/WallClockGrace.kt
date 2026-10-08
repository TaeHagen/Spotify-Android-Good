package com.taehagen.spotifygood.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taehagen.spotifygood.App
import kotlinx.coroutines.delay

/** The longest coroutine wait between two looks at the wall clock ([delayUntil]). */
internal const val DEADLINE_CHECK_MS = 1_000L

/**
 * Waits until [deadline] on [clock] (`elapsedRealtime`, which counts deep sleep). Coroutine delays
 * count only time the CPU is awake, so the wait goes in chunks of at most [chunkMs] with a look
 * at the clock after each; [EngineIdleAlarm] gets the owner to act at the first wake-up after
 * the deadline even when the CPU sleeps through the chunks.
 */
internal suspend fun delayUntil(deadline: Long, clock: () -> Long, chunkMs: Long = DEADLINE_CHECK_MS) {
    while (true) {
        val remaining = deadline - clock()
        if (remaining <= 0) return
        delay(remaining.coerceAtMost(chunkMs))
    }
}

/**
 * A grace counted on wall time ([clock]): armed once with its deadline, due once that passed.
 * The engine's idle stop (docs/ARCHITECTURE.md §9.2, §10). Guarded by the owner's lock; [deadline]
 * may be read from anywhere.
 */
internal class WallClockGrace(private val clock: () -> Long, private val graceMs: Long) {
    @Volatile var deadline: Long? = null
        private set

    /** Arms the grace (an armed one keeps its deadline); [immediate]: due now. Returns the deadline. */
    fun arm(immediate: Boolean = false): Long {
        val now = clock()
        val at = if (immediate) now else deadline ?: (now + graceMs)
        deadline = at
        return at
    }

    fun isDue(): Boolean = deadline?.let { clock() >= it } == true

    fun cancel() {
        deadline = null
    }
}

/**
 * The earliest of the engine's wall-clock deadlines (Connect hide, idle stop) as an inexact,
 * non-wakeup `ELAPSED_REALTIME` alarm, like `PausedIdleAlarm`: it adds no wake-up of its own and
 * is delivered at the next one after the deadline (the session's keep-alive, any other app's).
 * Allow-while-idle, since a plain alarm waits for a Doze maintenance window.
 */
internal object EngineIdleAlarm {
    private const val TAG = "EngineIdleAlarm"
    private const val REQUEST = 32
    private const val ACTION = "com.taehagen.spotifygood.engine.action.IDLE_DEADLINE"

    fun schedule(context: Context, atElapsedMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, atElapsedMs, pendingIntent(context))
        } catch (e: RuntimeException) {
            // Alarm limits or a dead system service: the chunked waits remain.
            Log.w(TAG, "Cannot schedule the engine idle alarm", e)
        }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST,
        Intent(context.applicationContext, EngineIdleAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Delivers the [EngineIdleAlarm] to the engine, if this process has one (not exported). */
class EngineIdleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? App)?.graph?.engineIfCreated()?.onIdleAlarm()
    }
}
