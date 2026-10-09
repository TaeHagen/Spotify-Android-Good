package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R

@Composable
fun trackSortLabel(sort: TrackSort): String = stringResource(
    when (sort) {
        TrackSort.CUSTOM -> R.string.browse_sort_custom
        TrackSort.RECENTLY_ADDED -> R.string.browse_library_sort_recently_added
        TrackSort.TITLE -> R.string.browse_sort_title
        TrackSort.ARTIST -> R.string.browse_sort_artist
        TrackSort.ALBUM -> R.string.browse_sort_album
    },
)

/** Sort button with its menu of [options] (the current [sort] checked), next to a list's filter field. */
@Composable
fun TrackSortButton(sort: TrackSort, options: List<TrackSort>, onSort: (TrackSort) -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.browse_sort_cd, trackSortLabel(sort)),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = stringResource(R.string.browse_library_sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(trackSortLabel(option)) },
                    onClick = {
                        menuOpen = false
                        onSort(option)
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Progress while every page is fetched for a sort or a filter ([loaded] of [total], indeterminate
 * while the total is unknown).
 */
@Composable
fun LoadingAllProgress(loaded: Int, total: Int?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (total != null && total > 0) {
            LinearProgressIndicator(progress = { (loaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.browse_sort_loading, loaded, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
