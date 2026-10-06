package com.taehagen.spotifygood.ui.screens.player

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.ConnectDevice
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.LyricsLine
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.playback.AudioOutput
import com.taehagen.spotifygood.playback.OutputKind
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:00", formatPlaybackTime(999))
        assertEquals("0:01", formatPlaybackTime(1_000))
        assertEquals("0:59", formatPlaybackTime(59_999))
        assertEquals("1:00", formatPlaybackTime(60_000))
        assertEquals("3:07", formatPlaybackTime(187_400))
        assertEquals("59:59", formatPlaybackTime(3_599_000))
    }

    @Test
    fun formatsHours() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
        assertEquals("12:34:56", formatPlaybackTime(45_296_000))
    }

    @Test
    fun clampsNegativeValues() {
        assertEquals("0:00", formatPlaybackTime(-5_000))
    }

    @Test
    fun effectiveDurationPrefersEngineValue() {
        val track = PlaybackTrack(uri = "spotify:track:a", durationMs = 200_000)
        assertEquals(180_000, PlaybackSnapshot(track = track, durationMs = 180_000).effectiveDurationMs())
        assertEquals(200_000, PlaybackSnapshot(track = track, durationMs = 0).effectiveDurationMs())
        assertEquals(0, PlaybackSnapshot().effectiveDurationMs())
    }
}

class SeekStepTest {
    private val episode = "spotify:episode:e"

    @Test
    fun stepsStayInsideTheItem() {
        assertEquals(45_000, seekStepTarget(30_000, SEEK_STEP_MS, durationMs = 600_000))
        assertEquals(15_000, seekStepTarget(30_000, -SEEK_STEP_MS, durationMs = 600_000))
        assertEquals(0, seekStepTarget(10_000, -SEEK_STEP_MS, durationMs = 600_000))
        assertEquals(600_000, seekStepTarget(590_000, SEEK_STEP_MS, durationMs = 600_000))
        // Unknown duration: only the start is a limit.
        assertEquals(605_000, seekStepTarget(590_000, SEEK_STEP_MS, durationMs = 0))
        assertEquals(0, seekStepTarget(5_000, -SEEK_STEP_MS, durationMs = 0))
    }

    @Test
    fun quickRepeatedStepsBuildOnEachOther() {
        // Back 15 s twice within a second while the snapshot still shows 100 s: 70 s, not 85 s.
        val first = seekStepTarget(seekStepBase(episode, 100_000, null, nowMs = 0, playing = false), -SEEK_STEP_MS, 600_000)
        val pending = PendingSeek(episode, first, atMs = 0)
        val second = seekStepTarget(seekStepBase(episode, 100_000, pending, nowMs = 400, playing = false), -SEEK_STEP_MS, 600_000)
        assertEquals(85_000, first)
        assertEquals(70_000, second)
    }

    @Test
    fun aChainedStepAccountsForTheTimePlayedSince() {
        val pending = PendingSeek(episode, 85_000, atMs = 1_000)
        assertEquals(85_500, seekStepBase(episode, 100_000, pending, nowMs = 1_500, playing = true))
        assertEquals(85_000, seekStepBase(episode, 100_000, pending, nowMs = 1_500, playing = false))
    }

    @Test
    fun anOldOrForeignPendingStepIsIgnored() {
        val pending = PendingSeek(episode, 85_000, atMs = 1_000)
        assertEquals(100_000, seekStepBase(episode, 100_000, pending, nowMs = 1_000 + SEEK_CHAIN_WINDOW_MS + 1, playing = false))
        assertEquals(100_000, seekStepBase("spotify:episode:other", 100_000, pending, nowMs = 1_200, playing = false))
        assertEquals(100_000, seekStepBase(episode, 100_000, pending, nowMs = 500, playing = false))
    }
}

class LyricsIndexTest {
    private val lines = listOf(1_000L, 4_000L, 4_000L, 9_500L, 15_000L).map { LyricsLine(startTimeMs = it, words = "l$it") }

    @Test
    fun beforeFirstLineIsMinusOne() {
        assertEquals(-1, lyricsLineIndexAt(lines, 0))
        assertEquals(-1, lyricsLineIndexAt(lines, 999))
    }

    @Test
    fun exactStartSelectsLine() {
        assertEquals(0, lyricsLineIndexAt(lines, 1_000))
        assertEquals(3, lyricsLineIndexAt(lines, 9_500))
        assertEquals(4, lyricsLineIndexAt(lines, 15_000))
    }

    @Test
    fun betweenStartsSelectsPreviousLine() {
        assertEquals(0, lyricsLineIndexAt(lines, 3_999))
        assertEquals(3, lyricsLineIndexAt(lines, 14_999))
    }

    @Test
    fun duplicateStartTimesPickTheLastOne() {
        assertEquals(2, lyricsLineIndexAt(lines, 4_000))
        assertEquals(2, lyricsLineIndexAt(lines, 9_000))
    }

    @Test
    fun afterLastLineStaysOnLast() {
        assertEquals(4, lyricsLineIndexAt(lines, 600_000))
    }

    @Test
    fun emptyAndSingleLine() {
        assertEquals(-1, lyricsLineIndexAt(emptyList(), 5_000))
        val single = listOf(LyricsLine(2_000, "only"))
        assertEquals(-1, lyricsLineIndexAt(single, 1_000))
        assertEquals(0, lyricsLineIndexAt(single, 2_000))
    }

    @Test
    fun matchesLinearScanForManyPositions() {
        val many = (0 until 200).map { LyricsLine(startTimeMs = it * 1_700L + (it % 3) * 10L, words = "$it") }
        for (position in -100L..340_000L step 333L) {
            val expected = many.indexOfLast { it.startTimeMs <= position }
            assertEquals("position $position", expected, lyricsLineIndexAt(many, position))
        }
    }

    @Test
    fun previewWindowKeepsCurrentLineSecond() {
        assertEquals(0..5, lyricsPreviewWindow(lineCount = 20, currentIndex = -1))
        assertEquals(0..5, lyricsPreviewWindow(lineCount = 20, currentIndex = 0))
        assertEquals(0..5, lyricsPreviewWindow(lineCount = 20, currentIndex = 1))
        assertEquals(4..9, lyricsPreviewWindow(lineCount = 20, currentIndex = 5))
        assertEquals(14..19, lyricsPreviewWindow(lineCount = 20, currentIndex = 19))
        assertEquals(0..2, lyricsPreviewWindow(lineCount = 3, currentIndex = 2))
        assertTrue(lyricsPreviewWindow(lineCount = 0, currentIndex = 0).isEmpty())
    }

    @Test
    fun opaqueArgbAddsMissingAlpha() {
        assertEquals(0xFF123456.toInt(), opaqueArgb(0x123456))
        assertEquals(0x80123456.toInt(), opaqueArgb(0x80123456.toInt()))
        assertEquals(-9206145, opaqueArgb(-9206145))
    }
}

class QueuePartitionTest {
    private fun track(uid: String, provider: TrackProvider, uri: String = "spotify:track:$uid") =
        PlaybackTrack(uri = uri, uid = uid, provider = provider, name = uid)

    @Test
    fun splitsQueueContextSuggestionsAndAutoplay() {
        val next = listOf(
            track("q1", TrackProvider.QUEUE),
            track("q2", TrackProvider.QUEUE),
            track("c1", TrackProvider.CONTEXT),
            track("s1", TrackProvider.SUGGESTION),
            track("x1", TrackProvider.UNAVAILABLE),
            track("c2", TrackProvider.CONTEXT),
            track("a1", TrackProvider.AUTOPLAY),
        )
        val sections = partitionQueue(next, isPlayingAutoplay = false)
        assertEquals(listOf("q1", "q2"), sections.queued.map { it.key })
        assertEquals(listOf("c1", "s1", "c2"), sections.upNext.map { it.key })
        assertEquals(listOf("a1"), sections.autoplay.map { it.key })
        assertEquals(listOf(0, 1), sections.queued.map { it.nextIndex })
        assertEquals(listOf(2, 3, 5), sections.upNext.map { it.nextIndex })
        assertTrue(sections.upNext[1].isSuggestion)
        assertFalse(sections.isEmpty)
    }

    @Test
    fun whilePlayingAutoplayEverythingButQueueIsAutoplay() {
        val next = listOf(track("q1", TrackProvider.QUEUE), track("c1", TrackProvider.CONTEXT), track("a1", TrackProvider.AUTOPLAY))
        val sections = partitionQueue(next, isPlayingAutoplay = true)
        assertEquals(listOf("q1"), sections.queued.map { it.key })
        assertTrue(sections.upNext.isEmpty())
        assertEquals(listOf("c1", "a1"), sections.autoplay.map { it.key })
    }

    @Test
    fun emptyQueue() {
        assertTrue(partitionQueue(emptyList(), isPlayingAutoplay = false).isEmpty)
    }

    @Test
    fun keysAreUniqueEvenWithoutOrWithDuplicateUids() {
        val tracks = listOf(
            PlaybackTrack(uri = "spotify:track:a"),
            PlaybackTrack(uri = "spotify:track:a"),
            PlaybackTrack(uri = "spotify:track:b", uid = "u1"),
            PlaybackTrack(uri = "spotify:track:c", uid = "u1"),
        )
        val keys = queueKeys(tracks)
        assertEquals(listOf("spotify:track:a@0", "spotify:track:a@1", "u1", "u1#1"), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(partitionQueue(tracks, false).upNext.first().hasUid)
    }

    @Test
    fun movedReordersAndClamps() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(listOf("a", "c", "d", "b"), list.moved(1, 10))
        assertEquals(list, list.moved(2, 2))
        assertEquals(list, list.moved(7, 0))
    }

    @Test
    fun moveTargetIsFinalIndexWithinQueue() {
        val before = listOf("q1", "q2", "q3", "q4")
        assertEquals(2, queueMoveTarget(before, before.moved(0, 2), "q1"))
        assertEquals(0, queueMoveTarget(before, before.moved(3, 0), "q4"))
        assertEquals(3, queueMoveTarget(before, before.moved(1, 3), "q2"))
        // Several swaps during one drag that end where they started: no command.
        assertNull(queueMoveTarget(before, before.moved(1, 3).moved(3, 1), "q2"))
        assertNull(queueMoveTarget(before, before, "missing"))
    }

    @Test
    fun pendingRemovalsHideRowsInAllSections() {
        val sections = partitionQueue(
            listOf(track("q1", TrackProvider.QUEUE), track("q2", TrackProvider.QUEUE), track("s1", TrackProvider.SUGGESTION), track("a1", TrackProvider.AUTOPLAY)),
            isPlayingAutoplay = false,
        )
        val edited = sections.withPendingEdits(removedKeys = setOf("q1", "s1", "a1"), pendingOrder = null)
        assertEquals(listOf("q2"), edited.queued.map { it.key })
        assertTrue(edited.upNext.isEmpty())
        assertTrue(edited.autoplay.isEmpty())
        assertTrue(sections.withPendingEdits(emptySet(), null) === sections)
    }

    @Test
    fun pendingOrderIsAppliedUntilEngineMatches() {
        val sections = partitionQueue(
            listOf(track("q1", TrackProvider.QUEUE), track("q2", TrackProvider.QUEUE), track("q3", TrackProvider.QUEUE), track("c1", TrackProvider.CONTEXT)),
            isPlayingAutoplay = false,
        )
        val pending = listOf("q3", "q1", "q2")
        val edited = sections.withPendingEdits(emptySet(), pending)
        assertEquals(pending, edited.queued.map { it.key })
        assertEquals(listOf("c1"), edited.upNext.map { it.key })
        assertFalse(sections.matchesOrder(pending))
        assertTrue(edited.matchesOrder(pending))
    }

    @Test
    fun pendingOrderToleratesNewAndRemovedRows() {
        val sections = partitionQueue(
            listOf(track("q2", TrackProvider.QUEUE), track("q1", TrackProvider.QUEUE), track("q9", TrackProvider.QUEUE)),
            isPlayingAutoplay = false,
        )
        // q3 was removed meanwhile and q9 is new: known rows follow the pending order, new ones go last.
        val edited = sections.withPendingEdits(emptySet(), listOf("q1", "q3", "q2"))
        assertEquals(listOf("q1", "q2", "q9"), edited.queued.map { it.key })
        assertFalse(sections.matchesOrder(listOf("q1", "q3", "q2")))
        val engineCaughtUp = partitionQueue(listOf(track("q1", TrackProvider.QUEUE), track("q2", TrackProvider.QUEUE)), false)
        assertTrue(engineCaughtUp.matchesOrder(listOf("q1", "q3", "q2")))
        val caughtUpWithNewRow = partitionQueue(
            listOf(track("q1", TrackProvider.QUEUE), track("q2", TrackProvider.QUEUE), track("q9", TrackProvider.QUEUE)),
            false,
        )
        assertTrue(caughtUpWithNewRow.matchesOrder(listOf("q1", "q3", "q2")))
    }

    @Test
    fun toTrackFillsPlaceholdersAndEpisodeArtwork() {
        val missing = PlaybackTrack(uri = "spotify:track:z", uid = "u")
        val display = missing.toTrack("Loading…")
        assertEquals("Loading…", display.name)
        assertEquals(0L, display.durationMs)

        val episode = PlaybackTrack(
            uri = "spotify:episode:e",
            name = "Ep",
            isEpisode = true,
            show = ShowRef(uri = "spotify:show:s", name = "Show"),
        )
        assertEquals("spotify:show:s", episode.toTrack("x").album?.uri)
        assertTrue(episode.toActionTarget("x") is MediaActionTarget.EpisodeTarget)

        val queued = PlaybackTrack(uri = "spotify:track:q", uid = "q1", provider = TrackProvider.QUEUE, name = "Q", artists = listOf(ArtistRef("spotify:artist:a", "A")))
        val target = queued.toActionTarget("x", contextUri = "spotify:album:b", queueUid = "q1") as MediaActionTarget.TrackTarget
        assertEquals("q1", target.queueUid)
        assertEquals("spotify:album:b", target.contextUri)
        assertNull((queued.toActionTarget("x", queueUid = "") as MediaActionTarget.TrackTarget).queueUid)
    }
}

class DeviceAndShareTest {
    private fun output(kind: OutputKind, name: String = kind.name) =
        AudioOutput(id = 1, name = name, kind = kind, isCurrent = true, isPreferred = false, info = null)

    @Test
    fun remoteIndicatorWinsOverLocalOutput() {
        val snapshot = PlaybackSnapshot(
            source = PlaybackSource.REMOTE,
            activeDevice = ActiveDeviceRef(id = "d", name = "Kitchen", type = DeviceType.SPEAKER),
        )
        assertEquals(DeviceIndicator.Remote("Kitchen", DeviceType.SPEAKER), deviceIndicator(snapshot, output(OutputKind.BLUETOOTH)))
    }

    @Test
    fun localOutputShownUnlessSpeaker() {
        val snapshot = PlaybackSnapshot(source = PlaybackSource.LOCAL)
        assertEquals(
            DeviceIndicator.LocalOutput("Pixel Buds", OutputKind.BLUETOOTH),
            deviceIndicator(snapshot, output(OutputKind.BLUETOOTH, "Pixel Buds")),
        )
        assertEquals(DeviceIndicator.None, deviceIndicator(snapshot, output(OutputKind.SPEAKER)))
        assertEquals(DeviceIndicator.None, deviceIndicator(snapshot, null))
        assertEquals(DeviceIndicator.None, deviceIndicator(PlaybackSnapshot(source = PlaybackSource.REMOTE), null))
    }

    @Test
    fun remoteVolumeFollowsTheDevicesSupportsVolume() {
        val kitchen = ActiveDeviceRef(id = "d", name = "Kitchen", type = DeviceType.SPEAKER)
        val remote = PlaybackSnapshot(source = PlaybackSource.REMOTE, activeDevice = kitchen)
        val fixed = DeviceList(activeDeviceId = "d", devices = listOf(ConnectDevice(id = "d", name = "Kitchen", supportsVolume = false)))
        val adjustable = DeviceList(activeDeviceId = "d", devices = listOf(ConnectDevice(id = "d", name = "Kitchen")))
        assertFalse(remoteVolumeSupported(remote, fixed))
        assertTrue(remoteVolumeSupported(remote, adjustable))
        // Not (yet) listed, or no device reference: assume it works, like the playback service.
        assertTrue(remoteVolumeSupported(remote, DeviceList()))
        assertTrue(remoteVolumeSupported(PlaybackSnapshot(source = PlaybackSource.REMOTE), fixed))
        // This phone plays: no remote volume slider at all.
        assertFalse(remoteVolumeSupported(PlaybackSnapshot(source = PlaybackSource.LOCAL), adjustable))
        assertFalse(remoteVolumeSupported(PlaybackSnapshot.EMPTY, adjustable))
    }

    @Test
    fun volumeConversionsRoundTrip() {
        assertEquals(0f, volumeToFraction(0), 0f)
        assertEquals(1f, volumeToFraction(MAX_CONNECT_VOLUME), 0f)
        assertEquals(1f, volumeToFraction(100_000), 0f)
        assertEquals(0, fractionToVolume(-1f))
        assertEquals(MAX_CONNECT_VOLUME, fractionToVolume(2f))
        assertEquals(32768, fractionToVolume(0.5f))
        assertEquals(50, volumePercent(32768))
        for (v in listOf(0, 1, 655, 30_000, 65_535)) {
            assertTrue(kotlin.math.abs(fractionToVolume(volumeToFraction(v)) - v) <= 1)
        }
        assertTrue(volumeSettled(32768, 0.5f))
        assertFalse(volumeSettled(0, 0.5f))
    }

    @Test
    fun shareUrls() {
        assertEquals("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", spotifyShareUrl("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("https://open.spotify.com/episode/abc", spotifyShareUrl("spotify:episode:abc"))
        assertEquals("https://open.spotify.com/playlist/p", spotifyShareUrl("spotify:playlist:p"))
        assertNull(spotifyShareUrl("spotify:user:me:collection"))
        assertNull(spotifyShareUrl("spotify:local:a:b:c:1"))
        assertNull(spotifyShareUrl("spotify:track:"))
        assertNull(spotifyShareUrl("https://example.com"))
    }
}

class ColorMathTest {
    private fun lightness(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return (maxOf(r, g, b) + minOf(r, g, b)) / 2f
    }

    @Test
    fun brightColoursAreDarkenedKeepingHue() {
        val toned = toneForBackground(0xFFFF0000.toInt(), maxLightness = 0.3f)
        assertEquals(0xFF, toned ushr 24)
        assertEquals(0.3f, lightness(toned), 0.01f)
        val r = (toned shr 16) and 0xFF
        val g = (toned shr 8) and 0xFF
        val b = toned and 0xFF
        assertTrue(r > g && r > b && g == b)
    }

    @Test
    fun darkColoursAreLiftedToMinimum() {
        val toned = toneForBackground(0xFF000000.toInt(), maxLightness = 0.3f, minLightness = 0.1f)
        assertEquals(0.1f, lightness(toned), 0.01f)
    }

    @Test
    fun midColoursWithinRangeKeepLightness() {
        val input = hslToArgb(200f, 0.5f, 0.2f)
        val toned = toneForBackground(input, maxLightness = 0.3f, minLightness = 0.1f)
        assertEquals(0.2f, lightness(toned), 0.01f)
    }

    @Test
    fun hslToArgbPrimaries() {
        assertEquals(0xFFFF0000.toInt(), hslToArgb(0f, 1f, 0.5f))
        assertEquals(0xFF00FF00.toInt(), hslToArgb(120f, 1f, 0.5f))
        assertEquals(0xFF0000FF.toInt(), hslToArgb(240f, 1f, 0.5f))
        assertEquals(0xFFFFFFFF.toInt(), hslToArgb(0f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), hslToArgb(0f, 0f, 0f))
    }
}
