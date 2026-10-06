package com.taehagen.spotifygood.ui.screens.home

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.model.HomeSection
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType

// Pure home-feed shaping (JVM-testable).

enum class Greeting { MORNING, AFTERNOON, EVENING }

/** 05:00–11:59 morning, 12:00–17:59 afternoon, otherwise evening. */
fun greetingForHour(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.MORNING
    in 12..17 -> Greeting.AFTERNOON
    else -> Greeting.EVENING
}

enum class HomeFilter { ALL, MUSIC, PODCASTS }

fun MediaType.matches(filter: HomeFilter): Boolean = when (filter) {
    HomeFilter.ALL -> true
    HomeFilter.MUSIC -> this != MediaType.SHOW && this != MediaType.EPISODE
    HomeFilter.PODCASTS -> this == MediaType.SHOW || this == MediaType.EPISODE
}

/** Synthetic section ids (titles are localised by the UI). */
const val RECENTLY_PLAYED_SECTION_ID = "browse:recently-played"
const val DOWNLOADED_SECTION_ID = "browse:downloaded"

/** Keeps the items matching [filter]; drops sections left empty and duplicate section ids. */
fun filterSections(sections: List<HomeSection>, filter: HomeFilter): List<HomeSection> {
    val seen = HashSet<String>()
    return sections.mapNotNull { section ->
        if (!seen.add(section.id)) return@mapNotNull null
        val items = section.items.filter { it.type.matches(filter) }.distinctBy { it.uri }
        if (items.isEmpty()) null else if (items.size == section.items.size) section else section.copy(items = items)
    }
}

/** Whether the feed already contains a "recently played" shelf. */
fun hasRecentlyPlayedSection(sections: List<HomeSection>): Boolean = sections.any {
    it.id.contains("recent", ignoreCase = true) || it.title.contains("recently played", ignoreCase = true)
}

/** Feed sections with a "Recently played" shelf (from recently played) first when the feed lacks one. */
fun assembleSections(feed: List<HomeSection>, recent: List<MediaRef>, filter: HomeFilter): List<HomeSection> {
    val withRecent = if (recent.isNotEmpty() && !hasRecentlyPlayedSection(feed)) {
        listOf(HomeSection(RECENTLY_PLAYED_SECTION_ID, "", recent)) + feed
    } else {
        feed
    }
    return filterSections(withRecent, filter)
}

sealed interface QuickAccessItem {
    val key: String

    data object LikedSongs : QuickAccessItem {
        override val key: String = "quick:liked"
    }

    @Immutable
    data class Media(val ref: MediaRef) : QuickAccessItem {
        override val key: String get() = "quick:${ref.uri}"
    }
}

/**
 * Up to [max] quick-access tiles: pinned Liked Songs (when [includeLikedSongs] and the filter
 * allows music), then recently played contexts, then [fallback] contexts. Tracks, episodes and
 * collections are skipped (Liked Songs is pinned), duplicates removed.
 */
fun buildQuickAccess(
    recent: List<MediaRef>,
    fallback: List<MediaRef>,
    filter: HomeFilter,
    includeLikedSongs: Boolean,
    max: Int = 8,
): List<QuickAccessItem> {
    val out = ArrayList<QuickAccessItem>(max)
    if (includeLikedSongs && filter != HomeFilter.PODCASTS) out += QuickAccessItem.LikedSongs
    val seen = HashSet<String>()
    (recent.asSequence() + fallback.asSequence())
        .filter { it.type != MediaType.TRACK && it.type != MediaType.EPISODE && it.type != MediaType.COLLECTION }
        .filter { it.type.matches(filter) }
        .filter { seen.add(it.uri) }
        .take((max - out.size).coerceAtLeast(0))
        .forEach { out += QuickAccessItem.Media(it) }
    return out
}
