package com.taehagen.spotifygood.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.taehagen.spotifygood.App

/**
 * [SleepWakeups] with AlarmManager (`ELAPSED_REALTIME_WAKEUP`, allow-while-idle so it also fires
 * with network access in Doze) and a short timed partial wake lock.
 *
 * Exact at the end where that needs no runtime grant (before Android 12, or when the user allowed
 * exact alarms: SCHEDULE_EXACT_ALARM). Otherwise inexact, staged so that its heuristic window ends
 * at the end ([SleepSchedule.stageTrigger]); [SleepTimer.onWakeupAlarm] re-arms after an early
 * delivery. Allow-while-idle alarms have an idle quota (72 an hour for apps targeting Android 12+);
 * a timer uses a handful of stages at most.
 */
internal class AndroidSleepWakeups(context: Context) : SleepWakeups {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val wakeLock: PowerManager.WakeLock? = app.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotifyGood:sleep-timer")
        ?.apply { setReferenceCounted(false) }

    override fun schedule(endsAtElapsedMs: Long) {
        val alarms = alarms ?: return
        val now = SystemClock.elapsedRealtime()
        val pending = pendingIntent(REQUEST_WAKE)
        try {
            if (exactAllowed(alarms)) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endsAtElapsedMs, pending)
                    return
                } catch (e: SecurityException) {
                    // The exact-alarm grant was revoked meanwhile: fall back to the staged alarm.
                    Log.i(TAG, "Exact alarm refused; using the staged inexact alarm")
                }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SleepSchedule.stageTrigger(endsAtElapsedMs, now), pending)
        } catch (e: RuntimeException) {
            // SecurityException (alarm limits) or a dead system service: the timer still runs.
            Log.w(TAG, "Cannot schedule the sleep timer wake-up", e)
        }
    }

    private fun exactAllowed(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun cancel() {
        val alarms = alarms ?: return
        runCatching {
            alarms.cancel(pendingIntent(REQUEST_WAKE))
            // Windowed alarm of earlier versions.
            alarms.cancel(pendingIntent(REQUEST_LEGACY_WINDOW))
        }
    }

    override fun holdAwake(ms: Long) {
        runCatching { wakeLock?.acquire(ms.coerceAtLeast(1)) }.onFailure { Log.w(TAG, "Wake lock failed", it) }
    }

    override fun release() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        app,
        requestCode,
        Intent(app, SleepTimerAlarmReceiver::class.java).setAction(ACTION_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val TAG = "SleepTimerAlarms"
        const val ACTION_WAKE = "com.taehagen.spotifygood.playback.action.SLEEP_TIMER_WAKE"
        const val REQUEST_LEGACY_WINDOW = 21
        const val REQUEST_WAKE = 22
    }
}

/**
 * The sleep timer's wake-up alarm (not exported). The system holds a wake lock while this runs;
 * [SleepTimer.onWakeupAlarm] takes the timer's own timed wake lock (until the end plus the pause
 * request) before returning and pokes the timer, so no `goAsync` is needed. A timer that no longer
 * exists (process restarted) ignores it.
 */
class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        app.graph.sleepTimer.onWakeupAlarm()
    }
}
