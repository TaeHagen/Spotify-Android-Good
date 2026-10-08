package com.taehagen.spotifygood.playback

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem

/**
 * A voice "play X" request as both of its entries receive it (docs/ARCHITECTURE.md §9.4): the
 * media session (Assistant, Android Auto: a set item with a search query, `PlaybackService`) and
 * the activity (`MEDIA_PLAY_FROM_SEARCH` through `LinkActivity`, `ShellViewModel.playFromSearch`).
 * Both hand it to the one resolver, [LibraryTree.resolveVoice]: [query] is what was said, [focus]
 * `MediaStore.EXTRA_MEDIA_FOCUS`, [names] the names the assistant split out per kind
 * (`EXTRA_MEDIA_PLAYLIST`, `_ALBUM`, `_ARTIST`, `_TITLE`).
 */
internal data class VoiceRequest(
    val query: String,
    val focus: String? = null,
    val names: Map<VoiceMatch.Kind, String> = emptyMap(),
) {
    /** "Play something": nothing named. */
    val isBlank: Boolean get() = query.isBlank() && names.isEmpty()

    /** The kinds asked for ([VoiceMatch.kindsFor]). */
    val kinds: Set<VoiceMatch.Kind> get() = VoiceMatch.kindsFor(focus)

    /** The text to search the catalog for: what was said, else the name given for the focus (or any). */
    val text: String
        get() = query.trim().ifEmpty { kinds.firstNotNullOfOrNull { names[it] } ?: names.values.firstOrNull().orEmpty() }

    companion object {
        fun of(query: String?, focus: String?, artist: String?, album: String?, title: String?, playlist: String?): VoiceRequest =
            VoiceRequest(
                query = query.orEmpty(),
                focus = focus,
                names = buildMap {
                    playlist?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.PLAYLIST, it) }
                    album?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.ALBUM, it) }
                    artist?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.ARTIST, it) }
                    title?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.SONG, it) }
                },
            )

        /** A media session's request: the item's search query and its request extras. */
        fun of(query: String, extras: Bundle?): VoiceRequest = of(
            query,
            extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
        )
    }
}

/** What a [VoiceRequest] comes to, on either entry. */
internal sealed interface VoiceOutcome {
    /** Play these (a collection as its context, or songs / episodes as a list). */
    data class Play(val items: List<MediaItem>) : VoiceOutcome

    /** "Play something": the session's stored session, or playback resumed. */
    data object PlaySomething : VoiceOutcome

    /** Nothing matches: Not found, or offline Not available offline. */
    data class NoMatch(val kind: PlaybackErrorKind) : VoiceOutcome

    companion object {
        /** The outcome of a resolved request: its [items], or no match ([offline]: not downloaded). */
        fun of(items: List<MediaItem>, offline: Boolean): VoiceOutcome = when {
            items.isNotEmpty() -> Play(items)
            offline -> NoMatch(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE)
            else -> NoMatch(PlaybackErrorKind.NOT_FOUND)
        }
    }
}
