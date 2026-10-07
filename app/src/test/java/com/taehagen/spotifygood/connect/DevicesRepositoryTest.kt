package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DevicesRepositoryTest {
    private fun state(contextUri: String?, trackUri: String = "spotify:track:t") = ResumeState(
        contextUri = contextUri,
        trackUri = trackUri,
        positionMs = 42_000,
        title = "Song",
        artist = null,
        album = null,
        artworkUrl = null,
        durationMs = 200_000,
        isEpisode = false,
    )

    @Test
    fun transferCarriesTheLastSession() {
        val args = DevicesRepository.transferArgs("speaker", play = true, resume = state("spotify:playlist:p"))
        assertEquals("speaker", args["deviceId"]?.jsonPrimitive?.content)
        assertEquals("true", args["play"]?.jsonPrimitive?.content)
        val resume = args["resume"]!!.jsonObject
        assertEquals("spotify:playlist:p", resume["contextUri"]?.jsonPrimitive?.content)
        assertEquals("spotify:track:t", resume["trackUri"]?.jsonPrimitive?.content)
        assertEquals(42_000L, resume["positionMs"]?.jsonPrimitive?.long)
    }

    @Test
    fun transferWithoutUsableSession() {
        assertNull(DevicesRepository.transferArgs("phone", play = false, resume = null)["resume"])
        assertNull(DevicesRepository.transferArgs("phone", play = true, resume = state(null, trackUri = " "))["resume"])
        // the track as its own "context" is sent without one
        val resume = DevicesRepository.transferArgs("phone", play = true, resume = state("spotify:track:t"))["resume"]!!.jsonObject
        assertFalse(resume.containsKey("contextUri"))
    }
}
