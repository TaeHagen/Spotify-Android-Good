package com.taehagen.spotifygood.ui.components

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.data.PlaylistAddChoice
import com.taehagen.spotifygood.data.PlaylistAddPlan

/**
 * An add to the playlist [playlistUri] ([playlistName]) that waits for the user: some of its items
 * are in it already (Spotify's "Already added" question, [AlreadyAddedDialog]).
 */
@Immutable
data class PlaylistAddPrompt(val playlistUri: String, val playlistName: String, val plan: PlaylistAddPlan) {
    /** Some of the items are new: "Add new ones" is offered too. */
    val offersNewOnes: Boolean get() = plan.checked && plan.fresh.isNotEmpty() && plan.fresh.size < plan.requested.size

    /** The playlist couldn't be checked for them: "Add anyway?" */
    val unchecked: Boolean get() = !plan.checked
}

/** What an add sends and says ([playlistAddOutcome]). */
internal sealed interface PlaylistAddOutcome {
    /** Nothing was asked for (no new ones): nothing is sent or said. */
    data object Nothing : PlaylistAddOutcome

    /** None fits: the playlist is at its item limit. */
    data object Full : PlaylistAddOutcome

    /** [items] are sent; [left] of those asked for don't fit (the item limit). */
    data class Send(val items: List<String>, val left: Int) : PlaylistAddOutcome
}

/** The outcome of adding per [choice] what [plan] says. */
internal fun playlistAddOutcome(plan: PlaylistAddPlan, choice: PlaylistAddChoice): PlaylistAddOutcome {
    val wanted = when (choice) {
        PlaylistAddChoice.ALL -> plan.requested.size
        PlaylistAddChoice.NEW_ONES -> plan.fresh.size
    }
    val items = plan.itemsFor(choice)
    return when {
        wanted == 0 -> PlaylistAddOutcome.Nothing
        items.isEmpty() -> PlaylistAddOutcome.Full
        else -> PlaylistAddOutcome.Send(items, left = wanted - items.size)
    }
}
