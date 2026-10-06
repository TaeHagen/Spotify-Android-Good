package com.taehagen.spotifygood.ui.screens.album

import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

// Pure helpers shared by the detail pages (album, artist, playlist, show, episode).
// No Android dependencies so they are covered by JVM unit tests.

// ---------------------------------------------------------------------------------------------
// Durations
// ---------------------------------------------------------------------------------------------

/** A duration split for "1 hr 5 min" style labels (rounded to the nearest second). */
internal data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

internal fun durationParts(ms: Long): DurationParts {
    val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
    return DurationParts(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}

/** Clock style duration: "3:05", "1:02:03" (truncated to whole seconds). */
internal fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

// ---------------------------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------------------------

internal enum class DatePrecision { YEAR, MONTH, DAY }

/** Year of a Spotify release date ("2024", "2024-03", "2024-03-01"). */
internal fun releaseYear(date: String?): Int? =
    date?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

/** Effective precision: the declared one, limited by what the string actually contains. */
internal fun datePrecision(date: String, declared: String?): DatePrecision {
    val available = when {
        date.length >= 10 -> DatePrecision.DAY
        date.length >= 7 -> DatePrecision.MONTH
        else -> DatePrecision.YEAR
    }
    val stated = when (declared?.lowercase(Locale.ROOT)) {
        "day" -> DatePrecision.DAY
        "month" -> DatePrecision.MONTH
        "year" -> DatePrecision.YEAR
        else -> available
    }
    return if (stated.ordinal <= available.ordinal) stated else available
}

/** "March 1, 2024" / "March 2024" / "2024" depending on precision. Unparseable input is returned as is. */
internal fun formatReleaseDate(date: String?, precision: String?, locale: Locale): String? {
    val value = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        when (datePrecision(value, precision)) {
            DatePrecision.DAY -> LocalDate.parse(value.take(10))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            DatePrecision.MONTH -> YearMonth.parse(value.take(7))
                .format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
            DatePrecision.YEAR -> value.take(4)
        }
    } catch (_: DateTimeParseException) {
        value
    }
}

/** Short episode date: "Mar 3" within [today]'s year, "Mar 3, 2023" otherwise. */
internal fun formatShortDate(date: String?, today: LocalDate, locale: Locale): String? {
    val value = date?.trim()?.takeIf { it.length >= 10 } ?: return releaseYear(date)?.toString()
    return try {
        val parsed = LocalDate.parse(value.take(10))
        if (parsed.year == today.year) {
            parsed.format(DateTimeFormatter.ofPattern("MMM d", locale))
        } else {
            parsed.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    } catch (_: DateTimeParseException) {
        value
    }
}

// ---------------------------------------------------------------------------------------------
// URIs
// ---------------------------------------------------------------------------------------------

/** Canonical context URI: `spotify:user:<u>:playlist:<id>` → `spotify:playlist:<id>`. */
internal fun canonicalUri(uri: String): String {
    val parts = uri.split(':')
    if (parts.size == 5 && parts[0] == "spotify" && parts[1] == "user" && parts[3] == "playlist") {
        return "spotify:playlist:${parts[4]}"
    }
    return uri
}

/** True if two context URIs denote the same context (playlist URI forms are normalised). */
internal fun isSameContext(a: String?, b: String?): Boolean =
    a != null && b != null && canonicalUri(a) == canonicalUri(b)

/** `spotify:episode:<id>` → `https://open.spotify.com/episode/<id>` (null for unsupported URIs). */
internal fun shareUrl(uri: String): String? {
    val parts = canonicalUri(uri).split(':')
    if (parts.size != 3 || parts[0] != "spotify" || parts[2].isEmpty()) return null
    return "https://open.spotify.com/${parts[1]}/${parts[2]}"
}

// ---------------------------------------------------------------------------------------------
// Album discs
// ---------------------------------------------------------------------------------------------

/** A track with its position in the album's (flattened) track list. */
internal data class IndexedTrack(val index: Int, val track: Track) {
    /** Artist names for the row subtitle (precomputed off the UI thread). */
    val artistLine: String = track.artists.joinToString { it.name }
}

internal data class DiscGroup(val disc: Int, val tracks: List<IndexedTrack>)

/** Groups album tracks by disc number (missing = disc 1), discs ascending, track order kept. */
internal fun groupByDisc(tracks: List<Track>): List<DiscGroup> {
    val groups = LinkedHashMap<Int, MutableList<IndexedTrack>>()
    tracks.forEachIndexed { index, track ->
        groups.getOrPut(track.discNumber ?: 1) { mutableListOf() }.add(IndexedTrack(index, track))
    }
    return groups.entries.sortedBy { it.key }.map { DiscGroup(it.key, it.value) }
}

// ---------------------------------------------------------------------------------------------
// Reordering
// ---------------------------------------------------------------------------------------------

/** Moves the element at [from] so that it ends up at index [to] (final position semantics). */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val result = toMutableList()
    result.add(to, result.removeAt(from))
    return result
}

/**
 * Converts a final position ([to]) into the "insert before" index of the list *before* the move,
 * which is what Spotify's playlist move operation (`playlist.moveItems` toIndex) expects.
 */
internal fun insertBeforeIndex(from: Int, to: Int): Int = if (to > from) to + 1 else to

/** Applies an "insert before" move (inverse helper of [insertBeforeIndex], used in tests). */
internal fun <T> List<T>.movedBefore(from: Int, insertBefore: Int): List<T> {
    if (from !in indices || insertBefore !in 0..size) return this
    val result = toMutableList()
    val item = result.removeAt(from)
    result.add(if (insertBefore > from) insertBefore - 1 else insertBefore, item)
    return result
}

/** Index after a single move of [from] → [to] (final position) for an element at [index]. */
internal fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    from < to && index in (from + 1)..to -> index - 1
    to < from && index in to until from -> index + 1
    else -> index
}

// ---------------------------------------------------------------------------------------------
// Playlist rows
// ---------------------------------------------------------------------------------------------

/**
 * Stable, unique LazyColumn keys for playlist items: the item uid when present, otherwise the
 * item URI plus its occurrence number. [used] holds keys already taken (and is updated).
 */
internal fun assignRowKeys(items: List<PlaylistItem>, used: MutableSet<String>): List<String> =
    items.map { item ->
        val base = item.uid?.let { "u:$it" } ?: "i:${item.uri ?: "unknown"}"
        var key = base
        var n = 1
        while (!used.add(key)) {
            n++
            key = "$base#$n"
        }
        key
    }

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val WHITESPACE = Regex("\\s+")

/** Lower-cased, accent-free text for client-side filtering. */
internal fun normalizeForSearch(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

/** Query tokens (normalised); empty for a blank query. */
internal fun searchTokens(query: String): List<String> =
    normalizeForSearch(query).split(WHITESPACE).filter { it.isNotEmpty() }

/** Searchable text of a playlist item: title, artists, album / show. */
internal fun PlaylistItem.searchText(): String {
    val track = track
    val episode = episode
    val raw = when {
        track != null -> buildString {
            append(track.name)
            track.artists.forEach { append(' ').append(it.name) }
            track.album?.let { append(' ').append(it.name) }
        }
        episode != null -> episode.name + " " + episode.show?.name.orEmpty()
        else -> ""
    }
    return normalizeForSearch(raw)
}

/** True if every token occurs in [haystack] (already normalised). */
internal fun matchesTokens(haystack: String, tokens: List<String>): Boolean =
    tokens.all { haystack.contains(it) }

// ---------------------------------------------------------------------------------------------
// Paging
// ---------------------------------------------------------------------------------------------

/**
 * Next page of a newest-first collection (e.g. show episodes) when listing it oldest first:
 * [loaded] items have been taken from the end. Returns (offset, limit) or null when done.
 */
internal fun oldestFirstPage(total: Int, loaded: Int, pageSize: Int): Pair<Int, Int>? {
    val remaining = total - loaded
    if (remaining <= 0 || pageSize <= 0) return null
    val limit = minOf(pageSize, remaining)
    return (remaining - limit) to limit
}

// ---------------------------------------------------------------------------------------------
// HTML descriptions
// ---------------------------------------------------------------------------------------------

internal enum class RichStyle { BOLD, ITALIC }

internal data class LinkSpan(val start: Int, val end: Int, val url: String)

internal data class StyleSpan(val start: Int, val end: Int, val style: RichStyle)

/** Plain text plus link/style ranges ([start], [end]) parsed from an HTML-ish description. */
internal data class RichText(
    val text: String,
    val links: List<LinkSpan> = emptyList(),
    val styles: List<StyleSpan> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val EMPTY = RichText("")
    }
}

/** HTML stripped to plain text (entities decoded, block tags → line breaks). */
internal fun stripHtml(html: String?): String = if (html.isNullOrEmpty()) "" else parseHtml(html).text

private val HTML_TAG = Regex("<\\s*/?\\s*(a|p|br|b|i|em|strong|ul|ol|li|div|span|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
private val URL = Regex("https?://[^\\s<>\"]+")
private val HREF = Regex("href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
    "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•",
)
private const val TRAILING_URL_PUNCTUATION = ".,;:!?)]}'\""

internal fun decodeEntities(text: String): String {
    if (text.indexOf('&') < 0) return text
    return ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") ->
                name.substring(2).toIntOrNull(16)?.let(::codePointString) ?: match.value
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePointString) ?: match.value
            else -> NAMED_ENTITIES[name.lowercase(Locale.ROOT)] ?: match.value
        }
    }
}

private fun codePointString(codePoint: Int): String? =
    if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

/**
 * Parses descriptions as delivered by Spotify: either HTML (links, paragraphs, lists, bold) or
 * plain text with line breaks. Bare http(s) URLs become links in both cases.
 */
internal fun parseHtml(input: String): RichText = HtmlParser(input, isHtml = HTML_TAG.containsMatchIn(input)).parse()

private class HtmlParser(private val input: String, private val isHtml: Boolean) {
    private val out = StringBuilder()
    private val links = mutableListOf<LinkSpan>()
    private val styles = mutableListOf<StyleSpan>()
    private val openStyles = ArrayList<Pair<RichStyle, Int>>()
    private var openLink: Pair<Int, String>? = null

    fun parse(): RichText {
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '<' && i + 1 < input.length && isTagStart(input[i + 1])) {
                val close = input.indexOf('>', i + 1)
                if (close < 0) {
                    appendText(input.substring(i))
                    break
                }
                handleTag(input.substring(i + 1, close))
                i = close + 1
            } else {
                var next = i + 1
                while (next < input.length && !(input[next] == '<' && next + 1 < input.length && isTagStart(input[next + 1]))) next++
                appendText(input.substring(i, next))
                i = next
            }
        }
        // Close dangling spans and trim trailing whitespace.
        closeLink()
        while (openStyles.isNotEmpty()) closeStyle(openStyles.last().first)
        var end = out.length
        while (end > 0 && out[end - 1].isWhitespace()) end--
        out.setLength(end)
        val length = out.length
        return RichText(
            text = out.toString(),
            links = links.mapNotNull { span -> clamp(span.start, span.end, length)?.let { (s, e) -> span.copy(start = s, end = e) } },
            styles = styles.mapNotNull { span -> clamp(span.start, span.end, length)?.let { (s, e) -> span.copy(start = s, end = e) } },
        )
    }

    private fun clamp(start: Int, end: Int, length: Int): Pair<Int, Int>? {
        val e = minOf(end, length)
        return if (start < e) start to e else null
    }

    private fun isTagStart(c: Char) = c.isLetter() || c == '/' || c == '!'

    private fun appendText(raw: String) {
        var text = decodeEntities(raw)
        if (isHtml) {
            text = WHITESPACE.replace(text, " ")
            if (out.isEmpty() || out.last() == '\n' || out.last() == ' ') text = text.trimStart(' ')
        } else {
            text = text.replace("\r\n", "\n").replace('\r', '\n')
            if (out.isEmpty()) text = text.trimStart()
        }
        if (text.isEmpty()) return
        val start = out.length
        out.append(text)
        if (openLink == null) {
            URL.findAll(text).forEach { match ->
                val url = match.value.trimEnd { it in TRAILING_URL_PUNCTUATION }
                if (url.length > "https://".length) links += LinkSpan(start + match.range.first, start + match.range.first + url.length, url)
            }
        }
    }

    private fun handleTag(rawTag: String) {
        if (rawTag.startsWith("!")) return // comment / doctype
        val closing = rawTag.startsWith("/")
        val body = rawTag.removePrefix("/").trim()
        val name = body.takeWhile { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        when (name) {
            "br" -> newline()
            "p" -> ensureNewlines(2)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                if (closing) closeStyle(RichStyle.BOLD)
                ensureNewlines(2)
                if (!closing) openStyle(RichStyle.BOLD)
            }
            "div", "ul", "ol" -> ensureNewlines(1)
            "li" -> if (!closing) {
                ensureNewlines(1)
                out.append("• ")
            }
            "a" -> if (closing) closeLink() else openLink(body)
            "b", "strong" -> if (closing) closeStyle(RichStyle.BOLD) else openStyle(RichStyle.BOLD)
            "i", "em" -> if (closing) closeStyle(RichStyle.ITALIC) else openStyle(RichStyle.ITALIC)
            else -> Unit
        }
    }

    private fun openLink(tagBody: String) {
        closeLink()
        val match = HREF.find(tagBody) ?: return
        val href = decodeEntities(match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: "").trim()
        if (href.isNotEmpty()) openLink = out.length to href
    }

    private fun closeLink() {
        val (start, url) = openLink ?: return
        openLink = null
        var end = out.length
        while (end > start && out[end - 1].isWhitespace()) end--
        if (end > start) links += LinkSpan(start, end, url)
    }

    private fun openStyle(style: RichStyle) {
        openStyles += style to out.length
    }

    private fun closeStyle(style: RichStyle) {
        val index = openStyles.indexOfLast { it.first == style }
        if (index < 0) return
        val (_, start) = openStyles.removeAt(index)
        var end = out.length
        while (end > start && out[end - 1].isWhitespace()) end--
        if (end > start) styles += StyleSpan(start, end, style)
    }

    private fun newline() {
        trimTrailingSpaces()
        if (out.isNotEmpty()) out.append('\n')
    }

    private fun ensureNewlines(count: Int) {
        trimTrailingSpaces()
        if (out.isEmpty()) return
        var trailing = 0
        while (trailing < out.length && out[out.length - 1 - trailing] == '\n') trailing++
        repeat(count - trailing) { out.append('\n') }
    }

    private fun trimTrailingSpaces() {
        var end = out.length
        while (end > 0 && out[end - 1] == ' ') end--
        out.setLength(end)
    }
}
