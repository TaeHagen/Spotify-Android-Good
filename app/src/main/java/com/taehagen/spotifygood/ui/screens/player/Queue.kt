package com.taehagen.spotifygood.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

private const val QUEUED_KEY_PREFIX = "q:"

@Composable
internal fun QueueScreenContent(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val sections by viewModel.queue.collectAsStateWithLifecycle()
    val coverage = rememberWindowCoverage()
    BackHandler(enabled = coverage.coversWindow, onBack = onDismiss)

    PlayerSurfaceTheme {
        Scaffold(
            modifier = modifier.then(coverage.modifier),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.player_queue_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.player_close))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                QueueBottomBar(
                    snapshot = snapshot,
                    onShuffle = viewModel::cycleShuffle,
                    onPlayPause = viewModel::togglePlayPause,
                    onRepeat = viewModel::cycleRepeat,
                )
            },
        ) { padding ->
            QueueList(
                snapshot = snapshot,
                sections = sections,
                viewModel = viewModel,
                contentPadding = padding,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun QueueList(
    snapshot: PlaybackSnapshot,
    sections: QueueSections,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onDismiss: () -> Unit,
) {
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val reorder = remember(listState) { QueueReorderState(listState) }
    LaunchedEffect(reorder) {
        for (delta in reorder.scrollRequests) listState.scrollBy(delta)
    }

    // Local order while dragging (and right after the drop until the engine reflects it).
    val localOrder = reorder.localOrder
    val queued = remember(sections.queued, localOrder) {
        if (localOrder == null) sections.queued else QueueSections(queued = sections.queued).withPendingEdits(emptySet(), localOrder).queued
    }
    val dragging = reorder.draggingKey != null
    LaunchedEffect(sections.queued, localOrder, dragging) {
        if (localOrder != null && !dragging) {
            if (!QueueSections(queued = sections.queued).matchesOrder(localOrder)) delay(1_500)
            reorder.release()
        }
    }

    val placeholder = stringResource(R.string.player_track_loading)
    val contextUri = snapshot.context?.uri
    val contextLabel = contextName(snapshot.context, snapshot.isPlayingAutoplay)
    val current = snapshot.track
    val queuedKeys by rememberUpdatedState(queued.map { it.key })
    val showActions: (QueueEntry) -> Unit = { entry ->
        navigator.showActions(
            entry.track.toActionTarget(
                placeholderName = placeholder,
                contextUri = contextUri,
                queueUid = entry.track.uid.takeIf { entry.track.provider == TrackProvider.QUEUE },
            ),
        )
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (current != null) {
            item(key = "header:now", contentType = "header") {
                SectionHeader(stringResource(R.string.player_queue_now_playing))
            }
            item(key = "now", contentType = "track") {
                QueueTrackRow(
                    track = current,
                    placeholder = placeholder,
                    isCurrent = true,
                    isPlaying = snapshot.status == PlaybackStatus.PLAYING,
                    onClick = {
                        navigator.openNowPlaying()
                        onDismiss()
                    },
                    onMoreClick = { navigator.showActions(current.toActionTarget(placeholder, contextUri = contextUri)) },
                )
            }
        }
        if (queued.isNotEmpty()) {
            item(key = "header:queue", contentType = "header") {
                SectionHeader(
                    title = stringResource(R.string.player_queue_next_in_queue),
                    action = {
                        TextButton(onClick = viewModel::clearQueue) {
                            Text(stringResource(R.string.player_queue_clear))
                        }
                    },
                )
            }
            itemsIndexed(queued, key = { _, entry -> QUEUED_KEY_PREFIX + entry.key }, contentType = { _, _ -> "queued" }) { index, entry ->
                QueuedRow(
                    entry = entry,
                    index = index,
                    count = queued.size,
                    placeholder = placeholder,
                    reorder = reorder,
                    currentKeys = { queuedKeys },
                    onCommitMove = { key, toIndex, newOrder ->
                        queued.firstOrNull { it.key == key }?.let { viewModel.move(it, toIndex, newOrder) }
                    },
                    onClick = { viewModel.skipTo(entry) },
                    onRemove = { viewModel.remove(entry) },
                    onMoveBy = { delta -> viewModel.moveBy(entry, delta) },
                    onMoreClick = { showActions(entry) },
                )
            }
        }
        if (sections.upNext.isNotEmpty()) {
            item(key = "header:next", contentType = "header") {
                SectionHeader(
                    if (contextLabel.isEmpty()) {
                        stringResource(R.string.player_queue_next_up)
                    } else {
                        stringResource(R.string.player_queue_next_from, contextLabel)
                    },
                )
            }
            items(sections.upNext, key = { "c:" + it.key }, contentType = { "upcoming" }) { entry ->
                UpcomingRow(
                    entry = entry,
                    placeholder = placeholder,
                    onClick = { viewModel.skipTo(entry) },
                    onRemove = { viewModel.remove(entry) },
                    onAddToPlaylist = { navigator.addToPlaylist(listOf(entry.track.uri)) },
                    onMoreClick = { showActions(entry) },
                )
            }
        }
        if (sections.autoplay.isNotEmpty()) {
            item(key = "header:autoplay", contentType = "header") {
                Column {
                    SectionHeader(stringResource(R.string.player_queue_autoplay))
                    Text(
                        text = stringResource(R.string.player_queue_autoplay_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
            items(sections.autoplay, key = { "a:" + it.key }, contentType = { "upcoming" }) { entry ->
                UpcomingRow(
                    entry = entry,
                    placeholder = placeholder,
                    onClick = { viewModel.skipTo(entry) },
                    onRemove = { viewModel.remove(entry) },
                    onAddToPlaylist = { navigator.addToPlaylist(listOf(entry.track.uri)) },
                    onMoreClick = { showActions(entry) },
                )
            }
        }
        // Gate on the live snapshot too, so a stale state never flashes the empty message.
        val nothingUpNext = snapshot.nextTracks.all { it.provider == TrackProvider.UNAVAILABLE }
        if (nothingUpNext && queued.isEmpty() && sections.upNext.isEmpty() && sections.autoplay.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    title = stringResource(R.string.player_queue_empty),
                    message = stringResource(R.string.player_queue_empty_message),
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    modifier = Modifier.padding(vertical = 48.dp),
                )
            }
        }
    }
}

/** A user-queued row: drag handle to reorder, swipe or button to remove. */
@Composable
private fun LazyItemScope.QueuedRow(
    entry: QueueEntry,
    index: Int,
    count: Int,
    placeholder: String,
    reorder: QueueReorderState,
    currentKeys: () -> List<String>,
    onCommitMove: (key: String, toIndex: Int, newOrder: List<String>) -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveBy: (Int) -> Unit,
    onMoreClick: () -> Unit,
) {
    val isDragged = reorder.draggingKey == entry.key
    val dismissState = rememberSwipeToDismissBoxState()
    val commit by rememberUpdatedState(onCommitMove)
    val keys by rememberUpdatedState(currentKeys)
    val moveUp = stringResource(R.string.player_queue_move_up)
    val moveDown = stringResource(R.string.player_queue_move_down)
    val removeLabel = stringResource(R.string.player_queue_remove)
    val dragLabel = stringResource(R.string.player_queue_drag_handle)
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DismissBackground(dismissState.dismissDirection) },
        gesturesEnabled = entry.hasUid && reorder.draggingKey == null,
        onDismiss = { onRemove() },
        modifier = if (isDragged) {
            Modifier
                .zIndex(1f)
                .graphicsLayer { translationY = reorder.draggedOffset() }
        } else {
            Modifier.animateItem()
        },
    ) {
        Surface(
            color = if (isDragged) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
            shadowElevation = if (isDragged) 8.dp else 0.dp,
            modifier = Modifier.semantics {
                customActions = buildList {
                    if (entry.hasUid && index > 0) add(CustomAccessibilityAction(moveUp) { onMoveBy(-1); true })
                    if (entry.hasUid && index < count - 1) add(CustomAccessibilityAction(moveDown) { onMoveBy(1); true })
                    if (entry.hasUid) add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                }
            },
        ) {
            QueueTrackRow(
                track = entry.track,
                placeholder = placeholder,
                onClick = onClick,
                onMoreClick = onMoreClick,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRemove, enabled = entry.hasUid) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = removeLabel)
                        }
                        if (entry.hasUid) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clearAndSetSemantics { contentDescription = dragLabel }
                                    .pointerInput(entry.key) {
                                        detectDragGestures(
                                            onDragStart = { reorder.start(entry.key, keys()) },
                                            onDragEnd = { reorder.end()?.let { move -> commit(move.key, move.toIndex, move.newOrder) } },
                                            onDragCancel = { reorder.cancel() },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                reorder.drag(amount.y)
                                            },
                                        )
                                    },
                            ) {
                                Icon(Icons.Rounded.DragHandle, contentDescription = null)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun LazyItemScope.UpcomingRow(
    entry: QueueEntry,
    placeholder: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val removeSuggestion = stringResource(R.string.player_queue_remove_suggestion)
    val addSuggestion = stringResource(R.string.player_queue_add_suggestion)
    QueueTrackRow(
        track = entry.track,
        placeholder = placeholder,
        onClick = onClick,
        isSuggestion = entry.isSuggestion,
        onMoreClick = onMoreClick,
        modifier = Modifier.animateItem(),
        trailing = if (entry.isSuggestion) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = addSuggestion)
                    }
                    IconButton(onClick = onRemove, enabled = entry.hasUid) {
                        Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = removeSuggestion)
                    }
                }
            }
        } else {
            null
        },
    )
}

/** Track row, or a skeleton while the engine has not delivered the metadata yet. */
@Composable
private fun QueueTrackRow(
    track: PlaybackTrack,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isSuggestion: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (track.name == null) {
        PlaceholderRow(label = placeholder, onClick = onClick, modifier = modifier, trailing = trailing)
        return
    }
    val display = remember(track) { track.toTrack(placeholder) }
    TrackRow(
        track = display,
        onClick = onClick,
        modifier = modifier,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        subtitleOverride = if (track.isEpisode) track.artistLine else null,
        isSuggestion = isSuggestion,
        onMoreClick = onMoreClick,
        trailing = trailing,
    )
}

@Composable
private fun PlaceholderRow(label: String, onClick: () -> Unit, modifier: Modifier, trailing: (@Composable () -> Unit)?) {
    val shimmer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmer),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmer),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmer),
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun DismissBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    Box(
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
    ) {
        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun QueueBottomBar(
    snapshot: PlaybackSnapshot,
    onShuffle: () -> Unit,
    onPlayPause: () -> Unit,
    onRepeat: () -> Unit,
) {
    val restrictions = snapshot.restrictions
    // Podcasts have no shuffle or repeat (as on Now Playing); empty slots keep play/pause centred.
    val episode = snapshot.track?.isEpisode == true
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (episode) {
                Spacer(Modifier.size(48.dp))
            } else {
                ShuffleButton(
                    shuffle = snapshot.shuffle,
                    smart = snapshot.smartShuffle,
                    enabled = restrictions.canToggleShuffle,
                    onClick = onShuffle,
                )
            }
            PlayPauseButton(
                status = snapshot.status,
                onClick = onPlayPause,
                enabled = snapshot.track != null && !(snapshot.status == PlaybackStatus.PLAYING && !restrictions.canPause),
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.onSurface,
                iconColor = MaterialTheme.colorScheme.surface,
            )
            if (episode) {
                Spacer(Modifier.size(48.dp))
            } else {
                RepeatButton(mode = snapshot.repeat, enabled = restrictions.canToggleRepeat, onClick = onRepeat)
            }
        }
    }
}

/** Result of a drag: [key] moved to [toIndex] within the user queue; [newOrder] = keys after it. */
internal data class QueueMove(val key: String, val toIndex: Int, val newOrder: List<String>)

/**
 * Drag-and-drop state of the "Next in queue" rows. The dragged row follows the finger
 * (`initialOffset + delta - currentOffset`), swapping places with the queued row under its centre;
 * dragging past the viewport edge scrolls the list.
 */
@Stable
internal class QueueReorderState(private val listState: LazyListState) {
    var draggingKey by mutableStateOf<String?>(null)
        private set

    /** Keys in display order while dragging, kept after the drop until [release]. */
    var localOrder by mutableStateOf<List<String>?>(null)
        private set

    private var startOrder: List<String> = emptyList()
    private var initialOffset = 0
    private var dragDelta by mutableFloatStateOf(0f)

    val scrollRequests = Channel<Float>(Channel.CONFLATED)

    private fun lazyKey(key: String) = QUEUED_KEY_PREFIX + key

    private fun itemInfo(key: String?) =
        key?.let { k -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lazyKey(k) } }

    /** Translation of the dragged row relative to its current slot. */
    fun draggedOffset(): Float = itemInfo(draggingKey)?.let { initialOffset + dragDelta - it.offset } ?: 0f

    fun start(key: String, currentKeys: List<String>) {
        val info = itemInfo(key) ?: return
        startOrder = currentKeys
        localOrder = currentKeys
        initialOffset = info.offset
        dragDelta = 0f
        draggingKey = key
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        val order = localOrder ?: return
        dragDelta += dy
        val item = itemInfo(key) ?: return
        val start = item.offset + draggedOffset()
        val end = start + item.size
        val middle = (start + end) / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            val otherKey = other.key as? String ?: return@firstOrNull false
            otherKey != item.key && otherKey.startsWith(QUEUED_KEY_PREFIX) &&
                middle >= other.offset && middle <= other.offset + other.size
        }
        if (target != null) {
            val from = order.indexOf(key)
            val to = order.indexOf((target.key as String).removePrefix(QUEUED_KEY_PREFIX))
            if (from >= 0 && to >= 0) {
                // Keep the first visible row anchored so the list does not jump while swapping it.
                if (item.index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
                    listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
                localOrder = order.moved(from, to)
            }
        } else {
            val layout = listState.layoutInfo
            val visibleEnd = layout.viewportEndOffset - layout.afterContentPadding
            val overscroll = when {
                dragDelta > 0 && end > visibleEnd -> end - visibleEnd
                dragDelta < 0 && start < 0 -> start
                else -> 0f
            }
            if (overscroll != 0f) scrollRequests.trySend(overscroll * 0.5f)
        }
    }

    /** Ends the drag and returns the move to commit (null when the position did not change). */
    fun end(): QueueMove? {
        val key = draggingKey ?: return null
        val order = localOrder ?: startOrder
        draggingKey = null
        dragDelta = 0f
        val toIndex = queueMoveTarget(startOrder, order, key)
        if (toIndex == null) localOrder = null
        return toIndex?.let { QueueMove(key, it, order) }
    }

    fun cancel() {
        draggingKey = null
        dragDelta = 0f
        localOrder = null
    }

    /** Drops the local order once the engine (or the optimistic VM state) shows it. */
    fun release() {
        if (draggingKey == null) localOrder = null
    }
}
