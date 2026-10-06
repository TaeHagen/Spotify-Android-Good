package com.taehagen.spotifygood.ui.screens.home

import com.taehagen.spotifygood.model.HomeSection
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private fun ref(type: MediaType, id: String) = MediaRef(type, "spotify:${type.name.lowercase()}:$id", id)

    @Test
    fun greetingBoundaries() {
        assertEquals(Greeting.EVENING, greetingForHour(4))
        assertEquals(Greeting.MORNING, greetingForHour(5))
        assertEquals(Greeting.MORNING, greetingForHour(11))
        assertEquals(Greeting.AFTERNOON, greetingForHour(12))
        assertEquals(Greeting.AFTERNOON, greetingForHour(17))
        assertEquals(Greeting.EVENING, greetingForHour(18))
        assertEquals(Greeting.EVENING, greetingForHour(0))
    }

    @Test
    fun filterSectionsKeepsMatchingItemsAndDropsEmptySections() {
        val sections = listOf(
            HomeSection("mixed", "Mixed", listOf(ref(MediaType.ALBUM, "a"), ref(MediaType.SHOW, "s"))),
            HomeSection("shows", "Shows", listOf(ref(MediaType.SHOW, "s2"), ref(MediaType.EPISODE, "e"))),
            HomeSection("mixed", "Duplicate id", listOf(ref(MediaType.ALBUM, "b"))),
        )
        val music = filterSections(sections, HomeFilter.MUSIC)
        assertEquals(listOf("mixed"), music.map { it.id })
        assertEquals(listOf(MediaType.ALBUM), music.single().items.map { it.type })

        val podcasts = filterSections(sections, HomeFilter.PODCASTS)
        assertEquals(listOf("mixed", "shows"), podcasts.map { it.id })
        assertEquals(1, podcasts[0].items.size)

        val all = filterSections(sections, HomeFilter.ALL)
        assertEquals(2, all.size)
        assertTrue(all[0] === sections[0])
    }

    @Test
    fun recentlyPlayedShelfAddedOnlyWhenFeedLacksOne() {
        val recent = listOf(ref(MediaType.PLAYLIST, "p"))
        val feed = listOf(HomeSection("made-for-you", "Made for you", listOf(ref(MediaType.PLAYLIST, "m"))))
        val assembled = assembleSections(feed, recent, HomeFilter.ALL)
        assertEquals(listOf(RECENTLY_PLAYED_SECTION_ID, "made-for-you"), assembled.map { it.id })

        val withRecents = feed + HomeSection("recently-played", "Recently played", recent)
        assertTrue(hasRecentlyPlayedSection(withRecents))
        assertEquals(listOf("made-for-you", "recently-played"), assembleSections(withRecents, recent, HomeFilter.ALL).map { it.id })

        assertEquals(feed, assembleSections(feed, emptyList(), HomeFilter.ALL))
    }

    @Test
    fun quickAccessPinsLikedSongsAndSkipsNonContexts() {
        val recent = listOf(
            ref(MediaType.TRACK, "t"),
            ref(MediaType.PLAYLIST, "p1"),
            ref(MediaType.COLLECTION, "liked"),
            ref(MediaType.ALBUM, "a1"),
            ref(MediaType.PLAYLIST, "p1"),
            ref(MediaType.EPISODE, "e"),
            ref(MediaType.SHOW, "s"),
        )
        val fallback = (1..10).map { ref(MediaType.ARTIST, "ar$it") }
        val items = buildQuickAccess(recent, fallback, HomeFilter.ALL, includeLikedSongs = true)
        assertEquals(8, items.size)
        assertEquals(QuickAccessItem.LikedSongs, items.first())
        val uris = items.drop(1).map { (it as QuickAccessItem.Media).ref.uri }
        assertEquals(
            listOf("spotify:playlist:p1", "spotify:album:a1", "spotify:show:s", "spotify:artist:ar1", "spotify:artist:ar2", "spotify:artist:ar3", "spotify:artist:ar4"),
            uris,
        )
        assertEquals(uris.size, uris.toSet().size)
    }

    @Test
    fun quickAccessRespectsFilter() {
        val recent = listOf(ref(MediaType.PLAYLIST, "p"), ref(MediaType.SHOW, "s"))
        val podcasts = buildQuickAccess(recent, emptyList(), HomeFilter.PODCASTS, includeLikedSongs = true)
        assertEquals(listOf("quick:spotify:show:s"), podcasts.map { it.key })
        val music = buildQuickAccess(recent, emptyList(), HomeFilter.MUSIC, includeLikedSongs = false)
        assertEquals(listOf("quick:spotify:playlist:p"), music.map { it.key })
        assertFalse(music.contains(QuickAccessItem.LikedSongs))
    }
}
