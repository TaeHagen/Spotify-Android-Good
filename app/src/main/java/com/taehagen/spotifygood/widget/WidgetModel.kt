package com.taehagen.spotifygood.widget

import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.playback.ResumeState
import com.taehagen.spotifygood.playback.ShuffleMode
import com.taehagen.spotifygood.playback.isSmartShuffleAvailable
import com.taehagen.spotifygood.playback.shuffleMode

/**
 * What the now-playing widget shows, whatever its size (docs/ARCHITECTURE.md §9.4). It holds no
 * position: the widget is not updated while a track plays on.
 */
internal sealed interface WidgetModel {
    /** Logged out: a "Sign in" prompt that opens the app. */
    data object SignedOut : WidgetModel

    /** Logged in with nothing loaded and no session to resume: opens the app, offers nothing. */
    data object Idle : WidgetModel

    /**
     * A track or episode: the current one ([live], here or on another Connect device), or with
     * nothing loaded the last session of the resume store, whose Play resumes it the way a
     * headset Play does (it offers nothing else).
     */
    data class Item(
        val uri: String,
        val title: String,
        /** Artists, or the show of an episode. */
        val subtitle: String,
        /** The artwork in the sizes there are (the widget picks one for its size); empty: none. */
        val art: List<Image>,
        val live: Boolean,
        /** Play/pause shows Pause: playing, or loading to play. */
        val playing: Boolean = false,
        /** The Connect device it plays on; null while it plays here (and when not [live]). */
        val device: String? = null,
        /** −15 s / +15 s instead of previous / next, like the media notification. */
        val isEpisode: Boolean = false,
        /** null while unknown (lookup pending, offline): no heart. */
        val liked: Boolean? = null,
        /** null: no shuffle button (episodes, not toggleable, not [live]). */
        val shuffle: ShuffleMode? = null,
        /** The shuffle button goes on to smart shuffle (what it says it does). */
        val smartShuffleAvailable: Boolean = false,
        /** The left skip (previous or −15 s) is enabled. */
        val canSkipBack: Boolean = false,
        /** The right skip (next or +15 s) is enabled. */
        val canSkipForward: Boolean = false,
    ) : WidgetModel
}

/** Playback state → [WidgetModel] (pure). */
internal object WidgetModels {
    /** Something is loaded here or on the device this phone mirrors (not the frozen last session). */
    fun isLive(snapshot: PlaybackSnapshot): Boolean =
        snapshot.track != null && snapshot.source != PlaybackSource.NONE

    /** [of] needs the stored session: logged in with nothing loaded. */
    fun needsResume(loggedIn: Boolean, snapshot: PlaybackSnapshot): Boolean = loggedIn && !isLive(snapshot)

    /**
     * The widget for [snapshot] with the current track's [liked] state, or, with nothing loaded,
     * for the stored session [resume] (read only when [needsResume]).
     */
    fun of(loggedIn: Boolean, snapshot: PlaybackSnapshot, liked: Boolean?, resume: ResumeState?): WidgetModel {
        if (!loggedIn) return WidgetModel.SignedOut
        val track = snapshot.track
        if (track == null || !isLive(snapshot)) return resume?.let(::resumed) ?: WidgetModel.Idle
        val r = snapshot.restrictions
        val episode = track.isEpisode
        return WidgetModel.Item(
            uri = track.uri,
            title = track.name.orEmpty(),
            subtitle = track.artistLine,
            // What imageUrl picks from (the album's covers, or the show's).
            art = (track.album?.images ?: track.show?.images).orEmpty(),
            live = true,
            playing = snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.LOADING,
            device = snapshot.activeDevice?.name?.takeIf { snapshot.source == PlaybackSource.REMOTE && it.isNotBlank() },
            isEpisode = episode,
            liked = liked,
            shuffle = snapshot.shuffleMode.takeIf { !episode && r.canToggleShuffle },
            smartShuffleAvailable = !episode && r.canToggleShuffle && snapshot.isSmartShuffleAvailable,
            canSkipBack = if (episode) r.canSeek else r.canSkipPrev,
            canSkipForward = if (episode) r.canSeek else r.canSkipNext,
        )
    }

    private fun resumed(state: ResumeState) = WidgetModel.Item(
        uri = state.trackUri,
        title = state.title.orEmpty(),
        subtitle = state.artist.orEmpty(),
        // The one stored size (the snapshot's imageUrl).
        art = listOfNotNull(state.artworkUrl?.takeIf { it.isNotBlank() }?.let { Image(it) }),
        live = false,
        isEpisode = state.isEpisode,
    )
}
