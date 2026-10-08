package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.playback.VoiceMatch.Candidate
import com.taehagen.spotifygood.playback.VoiceMatch.Kind
import com.taehagen.spotifygood.playback.VoiceMatch.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMatchTest {
    @Test
    fun namesCompareWithoutCaseAccentsOrPunctuation() {
        assertEquals("beyonce lemonade", VoiceMatch.normalize("Beyoncé — Lemonade!"))
        assertEquals(Strength.EXACT, VoiceMatch.strength("road trip", "Road Trip"))
        assertEquals(Strength.EXACT, VoiceMatch.strength("rock n roll", "Rock'n'Roll"))
        // The words a request wraps a name in.
        assertEquals(Strength.LOOSE, VoiceMatch.strength("my road trip playlist", "Road Trip"))
        assertEquals(Strength.LOOSE, VoiceMatch.strength("the album abbey road", "Abbey Road"))
        assertEquals(Strength.LOOSE, VoiceMatch.strength("my liked songs", "Liked Songs"))
        // A name that only starts with the request.
        assertEquals(Strength.PREFIX, VoiceMatch.strength("road trip", "Road Trip 2024"))
        assertNull("a word must start there", VoiceMatch.strength("road tr", "Road Trip"))
        assertNull(VoiceMatch.strength("trip", "Road Trip"))
        assertNull(VoiceMatch.strength("my playlist", "Road Trip"))
        assertNull(VoiceMatch.strength("", "Road Trip"))
    }

    @Test
    fun theUsersOwnCollectionIsFoundByKindAndName() {
        val candidates = listOf(
            Candidate("Road Trip 2024", Kind.PLAYLIST, "p1"),
            Candidate("Road Trip", Kind.PLAYLIST, "p2"),
            Candidate("Road Trip", Kind.ALBUM, "a1"),
            Candidate("Daily Mix", Kind.PLAYLIST, "p3"),
        )
        // The exact name over one that only starts so, the first listed on a tie.
        assertEquals("p2", VoiceMatch.best("road trip", candidates, VoiceMatch.COLLECTIONS)?.candidate?.value)
        assertEquals(Strength.EXACT, VoiceMatch.best("road trip", candidates, VoiceMatch.COLLECTIONS)?.strength)
        // A playlist focus: the album is not a candidate.
        assertEquals("p2", VoiceMatch.best("road trip", candidates, setOf(Kind.PLAYLIST))?.candidate?.value)
        assertEquals("a1", VoiceMatch.best("road trip", candidates, setOf(Kind.ALBUM))?.candidate?.value)
        // The name in the extras for its kind wins over the spoken query.
        val named = mapOf(Kind.PLAYLIST to "Daily Mix")
        assertEquals("p3", VoiceMatch.best("something else", candidates, setOf(Kind.PLAYLIST), named)?.candidate?.value)
        // Nothing of that name.
        assertNull(VoiceMatch.best("workout", candidates, VoiceMatch.COLLECTIONS))
        val prefix = VoiceMatch.best("road trip 20", listOf(Candidate("Road Trip 2024", Kind.PLAYLIST, "p1")), VoiceMatch.COLLECTIONS)
        assertNull("not at a word's end", prefix)
    }

    @Test
    fun theFocusNamesWhatIsAskedFor() {
        assertEquals(setOf(Kind.PLAYLIST), VoiceMatch.kindsFor(VoiceMatch.PLAYLIST_FOCUS))
        assertEquals(setOf(Kind.ALBUM), VoiceMatch.kindsFor("vnd.android.cursor.item/album"))
        assertEquals(setOf(Kind.ARTIST), VoiceMatch.kindsFor("vnd.android.cursor.item/artist"))
        assertEquals(setOf(Kind.SONG), VoiceMatch.kindsFor("vnd.android.cursor.item/audio"))
        assertEquals(VoiceMatch.COLLECTIONS + Kind.SONG, VoiceMatch.kindsFor(null))
    }

    @Test
    fun anOfflineSearchFindsWordsAnywhereInAName() {
        assertTrue(VoiceMatch.mentions("trip", "Road Trip"))
        assertTrue(VoiceMatch.mentions("road", "Road Trip"))
        assertTrue(VoiceMatch.mentions("beyonce", "Beyoncé"))
        assertFalse(VoiceMatch.mentions("oad", "Road Trip"))
        assertFalse(VoiceMatch.mentions("", "Road Trip"))
    }
}
