package com.taehagen.spotifygood.ui.screens.album

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DurationFormattingTest {
    @Test
    fun durationPartsSplitsAndRounds() {
        assertEquals(DurationParts(0, 0, 0), durationParts(0))
        assertEquals(DurationParts(0, 0, 0), durationParts(-5_000))
        assertEquals(DurationParts(0, 0, 1), durationParts(500))
        assertEquals(DurationParts(0, 3, 5), durationParts(185_000))
        assertEquals(DurationParts(0, 3, 5), durationParts(184_600))
        assertEquals(DurationParts(1, 0, 0), durationParts(3_600_000))
        assertEquals(DurationParts(2, 5, 30), durationParts((2 * 3600 + 5 * 60 + 30) * 1000L))
    }

    @Test
    fun clockFormat() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:00", formatClock(-1))
        assertEquals("0:59", formatClock(59_999))
        assertEquals("3:05", formatClock(185_000))
        assertEquals("59:59", formatClock(3_599_000))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("1:02:03", formatClock(3_723_000))
    }
}

class DateFormattingTest {
    @Test
    fun releaseYearParsesAllPrecisions() {
        assertEquals(2024, releaseYear("2024"))
        assertEquals(2024, releaseYear("2024-03"))
        assertEquals(2024, releaseYear("2024-03-01"))
        assertNull(releaseYear(null))
        assertNull(releaseYear("24"))
        assertNull(releaseYear("abcd"))
    }

    @Test
    fun precisionIsLimitedByTheString() {
        assertEquals(DatePrecision.DAY, datePrecision("2024-03-01", null))
        assertEquals(DatePrecision.MONTH, datePrecision("2024-03", null))
        assertEquals(DatePrecision.YEAR, datePrecision("2024", "day"))
        assertEquals(DatePrecision.YEAR, datePrecision("2024-03-01", "year"))
        assertEquals(DatePrecision.MONTH, datePrecision("2024-03-01", "MONTH"))
    }

    @Test
    fun releaseDateFormats() {
        assertEquals("March 1, 2024", formatReleaseDate("2024-03-01", "day", Locale.US))
        assertEquals("March 2024", formatReleaseDate("2024-03", "month", Locale.US))
        assertEquals("2024", formatReleaseDate("2024-03-01", "year", Locale.US))
        assertEquals("2024", formatReleaseDate("2024", null, Locale.US))
        assertEquals("2024-13-45", formatReleaseDate("2024-13-45", "day", Locale.US))
        assertNull(formatReleaseDate(null, null, Locale.US))
        assertNull(formatReleaseDate("  ", null, Locale.US))
    }

    @Test
    fun shortDateDropsTheCurrentYear() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals("Mar 3", formatShortDate("2026-03-03", today, Locale.US))
        assertEquals("Mar 3, 2023", formatShortDate("2023-03-03", today, Locale.US))
        assertEquals("Mar 3", formatShortDate("2026-03-03T10:00:00Z", today, Locale.US))
        assertEquals("2021", formatShortDate("2021", today, Locale.US))
        assertNull(formatShortDate(null, today, Locale.US))
    }
}

class UriHelpersTest {
    @Test
    fun playlistUriFormsAreTheSameContext() {
        assertEquals("spotify:playlist:abc", canonicalUri("spotify:user:someone:playlist:abc"))
        assertEquals("spotify:album:xyz", canonicalUri("spotify:album:xyz"))
        assertTrue(isSameContext("spotify:user:me:playlist:abc", "spotify:playlist:abc"))
        assertTrue(isSameContext("spotify:album:1", "spotify:album:1"))
        assertFalse(isSameContext("spotify:album:1", "spotify:album:2"))
        assertFalse(isSameContext(null, "spotify:album:1"))
        assertFalse(isSameContext("spotify:user:me:collection", "spotify:playlist:collection"))
    }

    @Test
    fun shareUrls() {
        assertEquals("https://open.spotify.com/episode/123", shareUrl("spotify:episode:123"))
        assertEquals("https://open.spotify.com/playlist/abc", shareUrl("spotify:user:me:playlist:abc"))
        assertNull(shareUrl("spotify:user:me:collection"))
        assertNull(shareUrl("https://example.com"))
        assertNull(shareUrl("spotify:track:"))
    }
}

class DiscGroupingTest {
    private fun track(n: Int, disc: Int?) = Track(uri = "spotify:track:$n", name = "T$n", trackNumber = n, discNumber = disc)

    @Test
    fun singleDiscIsOneGroup() {
        val groups = groupByDisc(listOf(track(1, 1), track(2, 1), track(3, null)))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].disc)
        assertEquals(listOf(0, 1, 2), groups[0].tracks.map { it.index })
    }

    @Test
    fun multipleDiscsAreGroupedInOrderWithFlatIndices() {
        val tracks = listOf(track(1, 1), track(2, 1), track(1, 2), track(2, 2), track(1, 3))
        val groups = groupByDisc(tracks)
        assertEquals(listOf(1, 2, 3), groups.map { it.disc })
        assertEquals(listOf(0, 1), groups[0].tracks.map { it.index })
        assertEquals(listOf(2, 3), groups[1].tracks.map { it.index })
        assertEquals(listOf(4), groups[2].tracks.map { it.index })
    }

    @Test
    fun discsAreSortedAndMissingDiscIsDiscOne() {
        val groups = groupByDisc(listOf(track(1, 2), track(2, null)))
        assertEquals(listOf(1, 2), groups.map { it.disc })
        assertEquals(1, groups[0].tracks.single().index)
        assertEquals(0, groups[1].tracks.single().index)
    }

    @Test
    fun emptyAlbumHasNoGroups() {
        assertTrue(groupByDisc(emptyList()).isEmpty())
    }

    @Test
    fun artistLineJoinsNames() {
        val indexed = IndexedTrack(0, Track("u", "n", artists = listOf(ArtistRef("a", "A"), ArtistRef("b", "B"))))
        assertEquals("A, B", indexed.artistLine)
    }
}

class ReorderMathTest {
    private val list = listOf("a", "b", "c", "d", "e")

    @Test
    fun movedUsesFinalPosition() {
        assertEquals(listOf("b", "c", "a", "d", "e"), list.moved(0, 2))
        assertEquals(listOf("a", "d", "b", "c", "e"), list.moved(3, 1))
        assertEquals(listOf("b", "c", "d", "e", "a"), list.moved(0, 4))
        assertEquals(list, list.moved(2, 2))
        assertEquals(list, list.moved(-1, 2))
        assertEquals(list, list.moved(1, 5))
    }

    @Test
    fun insertBeforeIndexMatchesFinalPositionForAllMoves() {
        for (from in list.indices) {
            for (to in list.indices) {
                val expected = list.moved(from, to)
                val actual = list.movedBefore(from, insertBeforeIndex(from, to))
                assertEquals("move $from → $to", expected, actual)
            }
        }
    }

    @Test
    fun insertBeforeExamples() {
        assertEquals(3, insertBeforeIndex(0, 2))
        assertEquals(1, insertBeforeIndex(3, 1))
        assertEquals(5, insertBeforeIndex(0, 4))
        assertEquals(2, insertBeforeIndex(2, 2))
    }

    @Test
    fun insertBeforeMatchesTheContract() {
        // docs/ARCHITECTURE.md §6.3: moving item 2 to the end of a 5-item list is from 2, to 5.
        assertEquals(5, insertBeforeIndex(2, 4))
        assertEquals(listOf("a", "b", "d", "e", "c"), list.movedBefore(2, 5))
        // Drag / "move down" one row: insert before target + 1; "move up": before the target.
        assertEquals(4, insertBeforeIndex(2, 3))
        assertEquals(listOf("a", "b", "d", "c", "e"), list.movedBefore(2, insertBeforeIndex(2, 3)))
        assertEquals(1, insertBeforeIndex(2, 1))
        assertEquals(listOf("a", "c", "b", "d", "e"), list.movedBefore(2, insertBeforeIndex(2, 1)))
        assertEquals(0, insertBeforeIndex(4, 0))
        assertEquals(listOf("e", "a", "b", "c", "d"), list.movedBefore(4, insertBeforeIndex(4, 0)))
    }

    @Test
    fun indexAfterMoveTracksEveryElement() {
        for (from in list.indices) {
            for (to in list.indices) {
                val result = list.moved(from, to)
                list.indices.forEach { index ->
                    assertEquals("element $index after $from → $to", list[index], result[indexAfterMove(index, from, to)])
                }
            }
        }
    }
}

class PlaylistRowHelpersTest {
    private fun item(uri: String, uid: String? = null) = PlaylistItem(uid = uid, track = Track(uri = uri, name = uri))

    @Test
    fun keysPreferUidAndStayUnique() {
        val items = listOf(item("t1", "x"), item("t2"), item("t2"), item("t3", "x"), PlaylistItem())
        val keys = assignRowKeys(items, HashSet())
        assertEquals(listOf("u:x", "i:t2", "i:t2#2", "u:x#2", "i:unknown"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun appendingKeepsEarlierKeys() {
        val first = listOf(item("t1"), item("t2"))
        val used = HashSet<String>()
        val firstKeys = assignRowKeys(first, used)
        val more = assignRowKeys(listOf(item("t1"), item("t3")), used)
        assertEquals(listOf("i:t1", "i:t2"), firstKeys)
        assertEquals(listOf("i:t1#2", "i:t3"), more)
        assertEquals(firstKeys, assignRowKeys(first, HashSet()))
    }

    @Test
    fun filterIgnoresCaseAndAccents() {
        assertEquals("beyonce", normalizeForSearch("Beyoncé"))
        assertEquals(listOf("sigur", "ros"), searchTokens("  Sigur  Rós "))
        assertTrue(searchTokens("   ").isEmpty())
        val track = PlaylistItem(
            track = Track(
                uri = "u",
                name = "Hoppípolla",
                artists = listOf(ArtistRef("a", "Sigur Rós")),
                album = AlbumRef("al", "Takk..."),
            ),
        )
        val text = track.searchText()
        assertTrue(matchesTokens(text, searchTokens("hoppipolla")))
        assertTrue(matchesTokens(text, searchTokens("ros takk")))
        assertFalse(matchesTokens(text, searchTokens("ros radiohead")))
        assertTrue(matchesTokens(text, emptyList()))
    }

    @Test
    fun episodesAreSearchableByShow() {
        val episode = PlaylistItem(episode = Episode(uri = "e", name = "Episode 12", show = ShowRef("s", "The Daily")))
        assertTrue(matchesTokens(episode.searchText(), searchTokens("daily 12")))
        assertEquals("", PlaylistItem().searchText())
    }
}

class PagingMathTest {
    @Test
    fun oldestFirstWalksBackwardsFromTheEnd() {
        assertEquals(70 to 50, oldestFirstPage(total = 120, loaded = 0, pageSize = 50))
        assertEquals(20 to 50, oldestFirstPage(total = 120, loaded = 50, pageSize = 50))
        assertEquals(0 to 20, oldestFirstPage(total = 120, loaded = 100, pageSize = 50))
        assertNull(oldestFirstPage(total = 120, loaded = 120, pageSize = 50))
        assertNull(oldestFirstPage(total = 0, loaded = 0, pageSize = 50))
        assertEquals(0 to 10, oldestFirstPage(total = 10, loaded = 0, pageSize = 50))
    }

    @Test
    fun oldestFirstPagesCoverEverythingExactlyOnce() {
        val total = 237
        var loaded = 0
        val seen = mutableListOf<Int>()
        while (true) {
            val (offset, limit) = oldestFirstPage(total, loaded, 50) ?: break
            seen += (offset until offset + limit).reversed()
            loaded += limit
        }
        assertEquals((0 until total).reversed().toList(), seen)
    }
}

class HtmlParsingTest {
    @Test
    fun entitiesAreDecoded() {
        assertEquals("Hello & welcome", stripHtml("Hello &amp; welcome"))
        assertEquals("'' / ’ —", decodeEntities("&#39;&#x27; &#x2F; &rsquo; &mdash;"))
        assertEquals("&unknown; stays", decodeEntities("&unknown; stays"))
        assertEquals("", stripHtml(null))
    }

    @Test
    fun paragraphsAndBreaksBecomeNewlines() {
        assertEquals("First\n\nSecond", stripHtml("<p>First</p><p>Second</p>"))
        assertEquals("Line\nbreak", stripHtml("Line<br>break"))
        assertEquals("Line\nbreak", stripHtml("Line<BR />break"))
        assertEquals("Hello world", stripHtml("<p>  Hello \n  world </p>"))
    }

    @Test
    fun listsGetBullets() {
        assertEquals("Intro\n\n• One\n• Two", stripHtml("<p>Intro</p><ul><li>One</li><li>Two</li></ul>"))
        assertEquals("• One\n• Two", stripHtml("<ul><li>One</li><li>Two</li></ul>"))
    }

    @Test
    fun anchorsBecomeLinks() {
        val rich = parseHtml("Listen to <a href=\"spotify:playlist:abc\">This Is</a> now")
        assertEquals("Listen to This Is now", rich.text)
        assertEquals(listOf(LinkSpan(10, 17, "spotify:playlist:abc")), rich.links)
        val single = parseHtml("<a href='https://x.y/?a=1&amp;b=2'>x</a>")
        assertEquals("https://x.y/?a=1&b=2", single.links.single().url)
    }

    @Test
    fun bareUrlsAreLinkedWithoutTrailingPunctuation() {
        val rich = parseHtml("Visit https://example.com/path. Thanks!")
        assertEquals("Visit https://example.com/path. Thanks!", rich.text)
        val link = rich.links.single()
        assertEquals("https://example.com/path", link.url)
        assertEquals("https://example.com/path", rich.text.substring(link.start, link.end))
    }

    @Test
    fun plainTextKeepsLineBreaksAndLiteralBrackets() {
        assertEquals("Line one\nLine two", stripHtml("Line one\r\nLine two"))
        assertEquals("a < b and c > d", stripHtml("a < b and c > d"))
        assertEquals("unterminated <b", stripHtml("unterminated <b"))
    }

    @Test
    fun boldAndItalicSpans() {
        val rich = parseHtml("<p>A <b>bold</b> and <em>soft</em> word</p>")
        assertEquals("A bold and soft word", rich.text)
        assertEquals(
            listOf(StyleSpan(2, 6, RichStyle.BOLD), StyleSpan(11, 15, RichStyle.ITALIC)),
            rich.styles,
        )
    }

    @Test
    fun spansNeverExceedTheText() {
        val rich = parseHtml("<p>Trailing <b>bold   </b></p>\n\n<a href=\"x\">")
        assertEquals("Trailing bold", rich.text)
        rich.styles.forEach { assertTrue(it.end <= rich.text.length && it.start < it.end) }
        rich.links.forEach { assertTrue(it.end <= rich.text.length && it.start < it.end) }
    }

    @Test
    fun commentsAndUnknownTagsAreDropped() {
        assertEquals("Hi there", stripHtml("<!-- note --><span class=\"x\">Hi</span> <font>there</font>"))
    }
}
