package com.taehagen.spotifygood.ui.screens.playlist

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Edit mode of owned/collaborative playlists: drag-and-drop reordering, row controls, dialogs and
// the "Add songs" sheet.

/**
 * Drag-to-reorder for a LazyColumn whose reorderable items are identified by key. The dragged item
 * follows the finger ([translationFor]); crossing another item's centre calls [onMove] (live,
 * local), and releasing calls [onEnd] with the start and final positions. Auto-scrolls near the
 * edges ([edgeTopPx] / [edgeBottomPx] cover overlay bars).
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val indexOfKey: (Any) -> Int?,
    private val onStart: () -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onEnd: (from: Int, to: Int) -> Unit,
    private val edgeTopPx: Float,
    private val edgeBottomPx: Float,
    private val maxScrollPerFrame: Float,
) {
    var draggingKey: Any? by mutableStateOf(null)
        private set

    /** Desired top of the dragged item in viewport coordinates. */
    private var draggedTop by mutableFloatStateOf(0f)
    private var startIndex = -1
    private var awaitingLazyIndex: Int? = null
    private var autoScroll: Job? = null

    fun translationFor(key: Any): Float {
        if (key != draggingKey) return 0f
        val item = currentItem() ?: return 0f
        return draggedTop - item.offset
    }

    fun start(key: Any) {
        val index = indexOfKey(key) ?: return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        startIndex = index
        draggedTop = item.offset.toFloat()
        awaitingLazyIndex = null
        draggingKey = key
        onStart()
    }

    fun drag(delta: Float) {
        if (draggingKey == null) return
        val info = listState.layoutInfo
        val size = currentItem()?.size ?: 0
        draggedTop = (draggedTop + delta).coerceIn(
            info.viewportStartOffset.toFloat() - size / 2f,
            info.viewportEndOffset.toFloat() - size / 2f,
        )
        trySwap()
        ensureAutoScroll()
    }

    fun end() {
        val key = draggingKey ?: return
        val to = indexOfKey(key) ?: startIndex
        autoScroll?.cancel()
        draggingKey = null
        val from = startIndex
        startIndex = -1
        onEnd(from, to)
    }

    private fun currentItem(): LazyListItemInfo? {
        val key = draggingKey ?: return null
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    }

    private fun trySwap() {
        val current = currentItem() ?: return
        // Wait until the previous move is reflected in the layout.
        awaitingLazyIndex?.let { expected ->
            if (current.index != expected) return
            awaitingLazyIndex = null
        }
        val center = draggedTop + current.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != current.key && indexOfKey(item.key) != null &&
                center >= item.offset && center < item.offset + item.size
        } ?: return
        val from = indexOfKey(current.key) ?: return
        val to = indexOfKey(target.key) ?: return
        // Moving the first visible item would make the list follow it; pin the scroll position.
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        val pin = current.index == firstIndex || target.index == firstIndex
        awaitingLazyIndex = target.index
        onMove(from, to)
        if (pin) scope.launch { listState.scrollToItem(firstIndex, firstOffset) }
    }

    private fun scrollSpeed(): Float {
        val item = currentItem() ?: return 0f
        val info = listState.layoutInfo
        val top = draggedTop
        val bottom = draggedTop + item.size
        val start = info.viewportStartOffset + edgeTopPx
        val end = info.viewportEndOffset - edgeBottomPx
        return when {
            top < start -> -((start - top) / 4f).coerceIn(1f, maxScrollPerFrame)
            bottom > end -> ((bottom - end) / 4f).coerceIn(1f, maxScrollPerFrame)
            else -> 0f
        }
    }

    private fun ensureAutoScroll() {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (draggingKey != null) {
                val speed = scrollSpeed()
                if (speed == 0f) break
                val consumed = listState.scrollBy(speed)
                if (consumed == 0f) break
                trySwap()
                withFrameNanos { }
            }
        }
    }
}

@Composable
internal fun rememberReorderState(
    listState: LazyListState,
    indexOfKey: (Any) -> Int?,
    onStart: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onEnd: (Int, Int) -> Unit,
    edgeTop: Dp,
    edgeBottom: Dp,
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentIndexOfKey by rememberUpdatedState(indexOfKey)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnEnd by rememberUpdatedState(onEnd)
    return remember(listState, scope, density, edgeTop, edgeBottom) {
        with(density) {
            ReorderState(
                listState = listState,
                scope = scope,
                indexOfKey = { currentIndexOfKey(it) },
                onStart = { currentOnStart() },
                onMove = { from, to -> currentOnMove(from, to) },
                onEnd = { from, to -> currentOnEnd(from, to) },
                edgeTopPx = (edgeTop + 48.dp).toPx(),
                edgeBottomPx = (edgeBottom + 48.dp).toPx(),
                maxScrollPerFrame = 12.dp.toPx(),
            )
        }
    }
}

/** Drag handle gesture for [key]. */
internal fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectVerticalDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            state.drag(dragAmount)
        },
    )
}

/** A playlist row in edit mode: remove, title, move up/down, drag handle. */
@Composable
internal fun EditableRow(
    row: VisibleRow,
    count: Int,
    reorderState: ReorderState,
    onRemove: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = row.row.item
    // A placeholder (metadata failed) has no name; it can still be removed or moved.
    val title = (item.track?.name ?: item.episode?.name).orEmpty().ifBlank { stringResource(R.string.shell_state_unavailable) }
    val index = row.index
    val moveUp = stringResource(R.string.detail_move_up)
    val moveDown = stringResource(R.string.detail_move_down)
    val reorderLabel = stringResource(R.string.detail_reorder, title)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.RemoveCircleOutline,
                contentDescription = stringResource(R.string.detail_remove_from_playlist, title),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Box(Modifier.weight(1f)) {
            val track = item.track
            val episode = item.episode
            when {
                track != null -> TrackRow(track = track, onClick = {}, showArtwork = false)
                episode != null -> EpisodeRow(episode = episode, onClick = {})
                else -> Spacer(Modifier.height(56.dp))
            }
        }
        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = moveUp)
        }
        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < count - 1) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = moveDown)
        }
        Box(
            Modifier
                .size(48.dp)
                .reorderHandle(reorderState, row.row.key)
                .semantics {
                    contentDescription = reorderLabel
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true })
                        if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Edit mode actions shown instead of the header action row. */
@Composable
internal fun EditToolbar(
    isOwned: Boolean,
    onAddSongs: () -> Unit,
    onEditDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onAddSongs) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.detail_add_songs))
        }
        if (isOwned) {
            OutlinedButton(onClick = onEditDetails) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_edit_details))
            }
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_delete_playlist))
            }
        }
    }
}

@Composable
internal fun EditDetailsDialog(
    initialName: String,
    initialDescription: String,
    onSave: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.detail_name)) },
                    singleLine = true,
                    isError = name.isBlank(),
                    supportingText = if (name.isBlank()) {
                        { Text(stringResource(R.string.detail_name_required)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_DESCRIPTION_LENGTH) },
                    label = { Text(stringResource(R.string.detail_description)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.detail_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) }
        },
    )
}

@Composable
internal fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_delete_playlist_title)) },
        text = { Text(stringResource(R.string.detail_delete_playlist_message, name)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.detail_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) }
        },
    )
}

/** Search field used for the in-playlist filter and the add-songs sheet. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.detail_clear_search))
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Sheet that searches tracks and adds them to the playlist. */
@Composable
internal fun AddSongsSheet(
    state: AddSongsUi,
    onQueryChange: (String) -> Unit,
    onAdd: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
        ) {
            Text(
                stringResource(R.string.detail_add_songs),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SearchField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                placeholder = stringResource(R.string.detail_search_songs),
                modifier = Modifier.padding(16.dp),
            )
            when {
                query.isBlank() -> SheetMessage(stringResource(R.string.detail_search_songs_hint))
                state.loading && state.results.isEmpty() -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.failed -> SheetMessage(stringResource(R.string.detail_search_failed))
                state.results.isEmpty() && state.query == query -> SheetMessage(stringResource(R.string.detail_search_no_results, query))
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.results, key = { it.uri }, contentType = { "track" }) { track ->
                        val added = track.uri in state.addedUris
                        TrackRow(
                            track = track,
                            onClick = { if (!added) onAdd(track) },
                            trailing = {
                                IconButton(onClick = { if (!added) onAdd(track) }) {
                                    Icon(
                                        if (added) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                        contentDescription = stringResource(
                                            if (added) R.string.detail_already_in_playlist else R.string.detail_add_to_this_playlist,
                                            track.name,
                                        ),
                                        tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

private const val MAX_NAME_LENGTH = 100
private const val MAX_DESCRIPTION_LENGTH = 300
