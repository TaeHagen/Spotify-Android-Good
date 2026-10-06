package com.taehagen.spotifygood.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.RepeatMode

/**
 * Custom session commands and the media button preferences of the notification, SysUI media
 * controls, Android Auto and Wear (docs/ARCHITECTURE.md §9.4): like/unlike, tri-state shuffle
 * (off → shuffle → smart shuffle, `ICON_SHUFFLE_STAR`) and tri-state repeat.
 *
 * Shuffle uses a custom command because Media3 has no third shuffle state; repeat uses a
 * parameterised player command (`COMMAND_SET_REPEAT_MODE`), which Media3 maps for legacy
 * controllers itself.
 */
internal object PlaybackSessionCommands {
    const val ACTION_LIKE = "com.taehagen.spotifygood.playback.LIKE"
    const val ACTION_UNLIKE = "com.taehagen.spotifygood.playback.UNLIKE"
    const val ACTION_CYCLE_SHUFFLE = "com.taehagen.spotifygood.playback.CYCLE_SHUFFLE"

    val LIKE = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    val UNLIKE = SessionCommand(ACTION_UNLIKE, Bundle.EMPTY)
    val CYCLE_SHUFFLE = SessionCommand(ACTION_CYCLE_SHUFFLE, Bundle.EMPTY)

    /** Granted to trusted controllers in `onConnectAsync`. */
    val ALL: List<SessionCommand> = listOf(LIKE, UNLIKE, CYCLE_SHUFFLE)

    /** Everything the buttons depend on (compared to avoid needless notification updates). */
    data class ButtonState(
        val hasItem: Boolean,
        /** null while unknown (e.g. offline). */
        val liked: Boolean?,
        val shuffle: ShuffleMode,
        val smartShuffleAvailable: Boolean,
        val canShuffle: Boolean,
        val repeat: RepeatMode,
    )

    /** Ordered like, shuffle, repeat: SysUI shows the first overflow buttons. */
    fun buttons(context: Context, state: ButtonState): List<CommandButton> {
        if (!state.hasItem) return emptyList()
        return buildList {
            state.liked?.let { liked ->
                add(
                    CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(context.getString(if (liked) R.string.playback_action_unlike else R.string.playback_action_like))
                        .setSessionCommand(if (liked) UNLIKE else LIKE)
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }

            val (shuffleIcon, shuffleLabel) = when (state.shuffle) {
                ShuffleMode.OFF -> CommandButton.ICON_SHUFFLE_OFF to R.string.playback_action_shuffle_on
                ShuffleMode.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON to
                    if (state.smartShuffleAvailable) R.string.playback_action_smart_shuffle_on else R.string.playback_action_shuffle_off
                ShuffleMode.SMART -> CommandButton.ICON_SHUFFLE_STAR to R.string.playback_action_shuffle_off
            }
            add(
                CommandButton.Builder(shuffleIcon)
                    .setDisplayName(context.getString(shuffleLabel))
                    .setSessionCommand(CYCLE_SHUFFLE)
                    .setEnabled(state.canShuffle)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )

            val (repeatIcon, nextMode, repeatLabel) = when (state.repeat) {
                RepeatMode.OFF -> Triple(CommandButton.ICON_REPEAT_OFF, Player.REPEAT_MODE_ALL, R.string.playback_action_repeat_all)
                RepeatMode.CONTEXT -> Triple(CommandButton.ICON_REPEAT_ALL, Player.REPEAT_MODE_ONE, R.string.playback_action_repeat_one)
                RepeatMode.TRACK -> Triple(CommandButton.ICON_REPEAT_ONE, Player.REPEAT_MODE_OFF, R.string.playback_action_repeat_off)
            }
            add(
                CommandButton.Builder(repeatIcon)
                    .setDisplayName(context.getString(repeatLabel))
                    .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, nextMode)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }
    }
}
