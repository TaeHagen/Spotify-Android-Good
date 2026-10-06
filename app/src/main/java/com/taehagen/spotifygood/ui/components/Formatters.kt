package com.taehagen.spotifygood.ui.components

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** "3:07" / "1:02:03" for track durations and positions. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** "1 hr 5 min" / "45 min" for episodes and long collections. */
fun formatLongDuration(context: Context, ms: Long): String {
    val totalMinutes = ((ms.coerceAtLeast(0) + 30_000) / 60_000).coerceAtLeast(1)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> context.getString(R.string.shell_duration_hr_min, h, m)
        h > 0 -> context.getString(R.string.shell_duration_hr, h)
        else -> context.getString(R.string.shell_duration_min, m)
    }
}

/**
 * Localised release date: "2024-05-03" → "May 3, 2024", "2024-05" → "May 2024", "2024" → "2024".
 * Unparseable input is returned unchanged.
 */
fun formatReleaseDate(date: String?, locale: Locale = Locale.getDefault()): String? {
    if (date.isNullOrBlank()) return null
    return runCatching {
        when (date.length) {
            4 -> date
            7 -> YearMonth.parse(date).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
            else -> LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    }.getOrDefault(date)
}

/** Year of a release date ("2024-05-03" → "2024"). */
fun releaseYear(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/** Human readable size ("1.2 GB"). */
fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

/** Plain text of an HTML snippet (Spotify descriptions contain tags and entities). */
fun stripHtml(html: String): String =
    if ('<' in html || '&' in html) AnnotatedString.fromHtml(html).text.trim() else html.trim()

/** User-facing text for a native error code (docs/ARCHITECTURE.md §3.3). */
fun friendlyErrorMessage(context: Context, code: String?, message: String? = null): String {
    val res = when (code) {
        NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED -> R.string.shell_error_network
        NativeErrorCode.RATE_LIMITED -> R.string.shell_error_rate_limited
        NativeErrorCode.NOT_FOUND -> R.string.shell_error_not_found
        NativeErrorCode.PREMIUM_REQUIRED -> R.string.shell_error_premium
        NativeErrorCode.NOT_LOGGED_IN, NativeErrorCode.BAD_CREDENTIALS -> R.string.shell_error_session
        NativeErrorCode.UNAVAILABLE -> R.string.shell_error_unavailable
        NativeErrorCode.NOT_ACTIVE_DEVICE -> R.string.shell_error_not_active_device
        NativeErrorCode.PLAYBACK_REFUSED -> R.string.shell_error_playback_refused
        NativeErrorCode.CANCELLED -> R.string.shell_error_cancelled
        else -> null
    }
    if (res != null) return context.getString(res)
    return context.getString(R.string.shell_error_generic)
}

fun friendlyErrorMessage(context: Context, info: NativeErrorInfo): String =
    friendlyErrorMessage(context, info.code, info.message)

fun friendlyErrorMessage(context: Context, error: Throwable): String = when (error) {
    is NativeException -> friendlyErrorMessage(context, error.code, error.info.message)
    is java.io.IOException -> context.getString(R.string.shell_error_network)
    else -> context.getString(R.string.shell_error_generic)
}
