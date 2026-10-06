package com.taehagen.spotifygood.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.appViewModel
import java.util.Date

internal val SLEEP_TIMER_OPTIONS_MINUTES = listOf(5, 10, 15, 30, 45, 60)

/** Wall-clock end time of a running timer, formatted for the user's locale (e.g. "10:45 PM"). */
internal fun sleepTimerEndTime(context: Context, state: SleepTimerState.Running): String {
    val remaining = (state.endsAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    return DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + remaining))
}

/** Short label for menus: the end time, "end of track", or null when off. */
internal fun sleepTimerShortLabel(context: Context, state: SleepTimerState): String? = when (state) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> context.getString(R.string.player_sleep_short_end_of_track)
    is SleepTimerState.Running -> sleepTimerEndTime(context, state)
}

@Composable
internal fun SleepTimerSheetContent(onDismiss: () -> Unit) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val timer by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    // Re-evaluates the countdown about once per second, only while the sheet is visible.
    val tick by viewModel.clock.collectAsStateWithLifecycle(initialValue = 0L)
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEpisode = snapshot.track?.isEpisode == true

    val status: String? = when (val state = timer) {
        SleepTimerState.Off -> null
        SleepTimerState.EndOfTrack -> stringResource(R.string.player_sleep_at_end_of_track)
        is SleepTimerState.Running -> {
            val remaining = remember(tick, state) { (state.endsAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
            val endsAt = remember(state) { sleepTimerEndTime(context, state) }
            stringResource(R.string.player_sleep_remaining, formatPlaybackTime(remaining)) + " · " +
                stringResource(R.string.player_sleep_ends_at, endsAt)
        }
    }

    PlayerSurfaceTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_sleep_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .semantics { heading() },
                )
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                    )
                }
                Column(Modifier.padding(top = 8.dp)) {
                    SLEEP_TIMER_OPTIONS_MINUTES.forEach { minutes ->
                        val state = timer
                        SleepOption(
                            text = pluralStringResource(R.plurals.player_sleep_minutes, minutes, minutes),
                            selected = state is SleepTimerState.Running && state.totalMs == minutes * 60_000L,
                            onClick = {
                                viewModel.startSleepTimer(minutes)
                                onDismiss()
                            },
                        )
                    }
                    SleepOption(
                        text = stringResource(if (isEpisode) R.string.player_sleep_end_of_episode else R.string.player_sleep_end_of_track),
                        selected = timer == SleepTimerState.EndOfTrack,
                        onClick = {
                            viewModel.sleepAtEndOfTrack()
                            onDismiss()
                        },
                    )
                    if (timer != SleepTimerState.Off) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.player_sleep_off), color = MaterialTheme.colorScheme.error) },
                            leadingContent = { Icon(Icons.Rounded.TimerOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.selectable(selected = false, role = Role.Button) {
                                viewModel.cancelSleepTimer()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        headlineContent = { Text(text, color = if (selected) accent else Color.Unspecified) },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = accent) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
