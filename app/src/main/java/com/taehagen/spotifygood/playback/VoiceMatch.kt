package com.taehagen.spotifygood.playback

import android.provider.MediaStore
import java.text.Normalizer
import java.util.Locale

/**
 * Matching of a voice request ("play my Road Trip playlist", Assistant / Android Auto) and of an
 * offline search against the user's own names (pure, JVM-testable): the downloads and Library's
 * playlists, albums, artists and podcasts ([LibraryTree.resolveVoiceQuery]). Names compare
 * without case, accents or punctuation; "loose" also without the words a request wraps a name
 * in ("my", "the", "playlist", ...).
 */
internal object VoiceMatch {
    /** How well a name matches the request, weakest first. */
    enum class Strength {
        /** The name starts with the request's words ("road trip" for "Road Trip 2024"). */
        PREFIX,

        /** The same words but for "my", "the", "playlist", ... ("my road trip playlist"). */
        LOOSE,

        /** The same name. */
        EXACT,
    }

    /** What a request may ask for. */
    enum class Kind { PLAYLIST, ALBUM, ARTIST, SHOW, SONG }

    /** The collections (no songs): what a request without a focus is matched against first. */
    val COLLECTIONS: Set<Kind> = setOf(Kind.PLAYLIST, Kind.ALBUM, Kind.ARTIST, Kind.SHOW)

    /** `MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE` (the constant is deprecated). */
    const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"

    /** The kinds a request with `MediaStore.EXTRA_MEDIA_FOCUS` [focus] asks for. */
    fun kindsFor(focus: String?): Set<Kind> = when (focus) {
        PLAYLIST_FOCUS -> setOf(Kind.PLAYLIST)
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> setOf(Kind.ALBUM)
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> setOf(Kind.ARTIST)
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> setOf(Kind.SONG)
        else -> COLLECTIONS + Kind.SONG
    }

    fun normalize(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.lowercase(Locale.ROOT).replace(NON_WORD, " ").trim()
    }

    /** [text] normalized, without the words a request wraps a name in. */
    fun core(text: String): String = normalize(text).split(' ').filter { it.isNotEmpty() && it !in NOISE }.joinToString(" ")

    /** How well [name] matches [query]; null: not at all. */
    fun strength(query: String, name: String): Strength? {
        val q = normalize(query)
        val n = normalize(name)
        if (q.isEmpty() || n.isEmpty()) return null
        if (q == n) return Strength.EXACT
        val qc = core(query)
        val nc = core(name)
        if (qc.isEmpty()) return null
        if (qc == nc) return Strength.LOOSE
        if (qc.length >= MIN_PREFIX && nc.startsWith("$qc ")) return Strength.PREFIX
        return null
    }

    /** Whether [name] has [query]'s words, from the start of one of its words on (offline search). */
    fun mentions(query: String, name: String): Boolean {
        val qc = core(query).ifEmpty { normalize(query) }
        if (qc.isEmpty()) return false
        return " ${normalize(name)} ".contains(" $qc")
    }

    data class Candidate<T>(val name: String, val kind: Kind, val value: T)

    data class Match<T>(val candidate: Candidate<T>, val strength: Strength)

    /**
     * The best match among [candidates] of [kinds] (on a tie the one listed first): each matched
     * against the name the request gives for its kind ([named], e.g. `EXTRA_MEDIA_PLAYLIST`), or
     * else [query].
     */
    fun <T> best(query: String, candidates: List<Candidate<T>>, kinds: Set<Kind>, named: Map<Kind, String> = emptyMap()): Match<T>? {
        var best: Match<T>? = null
        for (candidate in candidates) {
            if (candidate.kind !in kinds) continue
            val strength = strength(named[candidate.kind] ?: query, candidate.name) ?: continue
            if (best == null || strength > best.strength) best = Match(candidate, strength)
            if (strength == Strength.EXACT) break
        }
        return best
    }

    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val NOISE = setOf("my", "the", "a", "playlist", "album", "podcast", "show")
    private const val MIN_PREFIX = 3
}
