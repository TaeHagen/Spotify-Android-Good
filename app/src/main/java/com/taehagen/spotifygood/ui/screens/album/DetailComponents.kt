package com.taehagen.spotifygood.ui.screens.album

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.AlbumType
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

// Composables shared by the detail pages. Built on the shared ui.components where they exist.

internal val DetailTopBarHeight: Dp = 64.dp
internal const val DisabledRowAlpha = 0.38f

/** Height covered by the overlay top bar (status bar + bar). */
@Composable
internal fun detailTopInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + DetailTopBarHeight

/** [this] padding with its top replaced by [top] (keeps the shell's bottom/side insets). */
@Composable
internal fun PaddingValues.withTop(top: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = top,
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}

/**
 * Page frame: content drawn edge-to-edge with an overlay top bar (back button, title fading in
 * once the header scrolled away, optional actions and a thin progress line while revalidating).
 */
@Composable
internal fun DetailScaffold(
    title: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
        DetailTopBar(title = title, showTitle = showTitle, onBack = onBack, refreshing = refreshing, actions = actions)
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    refreshing: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    val solid by animateFloatAsState(if (showTitle) 1f else 0f, label = "topBarAlpha")
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(barColor.copy(alpha = solid)) }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DetailTopBarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = if (showTitle) 0f else 0.4f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.detail_back))
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .graphicsLayer { alpha = solid }
                            .then(if (showTitle) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
                    )
                }
            }
            actions()
        }
        if (refreshing) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}

/** Loading / error / content switch for a page (error and loading stay clear of the top bar). */
@Composable
internal fun <T> LoadStateContent(
    state: LoadState<T>,
    offline: Boolean,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> LoadingState(Modifier.padding(top = detailTopInset()))
        is LoadState.Failed -> Box(
            Modifier
                .fillMaxSize()
                .padding(top = detailTopInset())
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val message = when {
                offline || state.reason == FailureReason.OFFLINE -> R.string.detail_error_offline
                state.reason == FailureReason.NOT_FOUND -> R.string.detail_error_not_found
                else -> R.string.detail_error_generic
            }
            ErrorState(message = stringResource(message), onRetry = onRetry)
        }
        is LoadState.Ready -> content(state.data)
    }
}

/** Shows ViewModel messages through the app navigator (snackbar host) while started. */
@Composable
internal fun MessageEffect(messages: Flow<UiMessage>) {
    val navigator = LocalAppNavigator.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(messages, lifecycleOwner, resources) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collect { message ->
                navigator.showMessage(resources.getString(message.res, *message.args.toTypedArray()))
            }
        }
    }
}

/** Action row below a header: [leading] icon actions on the left, [trailing] (shuffle, play) on the right. */
@Composable
internal fun DetailActionRow(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun HeartButton(saved: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = stringResource(if (saved) R.string.detail_remove_from_library else R.string.detail_save_to_library),
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Plus / check circle used for "save to library" style toggles. */
@Composable
internal fun AddedButton(
    added: Boolean,
    onClick: () -> Unit,
    @StringRes addDescription: Int,
    @StringRes removeDescription: Int,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (added) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
            contentDescription = stringResource(if (added) removeDescription else addDescription),
            tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Rounded.MoreVert,
            contentDescription = label?.let { stringResource(R.string.detail_more_options_for, it) }
                ?: stringResource(R.string.detail_more_options),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ShuffleButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (active) R.string.detail_state_on else R.string.detail_state_off)
    IconButton(onClick = onClick, modifier = modifier.semantics { stateDescription = state }) {
        Icon(
            Icons.Rounded.Shuffle,
            contentDescription = stringResource(R.string.detail_shuffle),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
internal fun SmartShuffleButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (active) R.string.detail_state_on else R.string.detail_state_off)
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick, modifier = modifier.semantics { stateDescription = state }) {
        Box(Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.detail_smart_shuffle),
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.BottomStart),
            )
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
internal fun FollowButton(following: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(if (following) R.string.detail_following else R.string.detail_follow),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Download toggle for a whole collection with progress ring. Removing asks for confirmation.
 */
@Composable
internal fun CollectionDownloadButton(
    ui: CollectionDownloadUi,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val status = ui.status
    val description = when {
        ui.downloaded && status is CollectionDownloadStatus.InProgress ->
            stringResource(R.string.detail_downloading_progress, status.done, status.total)
        ui.downloaded -> stringResource(R.string.detail_remove_download)
        else -> stringResource(R.string.detail_download)
    }
    IconButton(
        onClick = { if (ui.downloaded) confirmRemove = true else onDownload() },
        modifier = modifier,
    ) {
        when {
            ui.downloaded && status is CollectionDownloadStatus.InProgress -> Box(contentAlignment = Alignment.Center) {
                if (status.total > 0) {
                    CircularProgressIndicator(
                        progress = { (status.done.toFloat() / status.total).coerceIn(0f, 1f) },
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            ui.downloaded -> Icon(
                Icons.Rounded.DownloadForOffline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            else -> Icon(
                Icons.Rounded.ArrowCircleDown,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
    if (confirmRemove) {
        RemoveDownloadDialog(
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
internal fun RemoveDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_remove_download_title)) },
        text = { Text(stringResource(R.string.detail_remove_download_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.detail_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) } },
    )
}

/** Tappable artist names (with the first artist's avatar) under a header title. */
@Composable
internal fun ArtistLinks(artists: List<ArtistRef>, onArtistClick: (ArtistRef) -> Unit, modifier: Modifier = Modifier) {
    if (artists.isEmpty()) return
    FlowRow(modifier = modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        val avatar = artists.first().images.best(64)
        if (avatar != null) {
            Artwork(
                url = avatar,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                shape = CircleShape,
                placeholderIcon = Icons.Rounded.Person,
            )
        }
        artists.forEachIndexed { index, artist ->
            Box(
                Modifier
                    .clickable(role = Role.Button) { onArtistClick(artist) }
                    .height(48.dp)
                    .padding(end = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (index < artists.lastIndex) artist.name + "," else artist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Secondary info line in headers ("Album • 2024", "12 songs, 45 min"). */
@Composable
internal fun HeaderMetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Opens `spotify:` / open.spotify.com links in the app, anything else in the browser. */
@Composable
internal fun rememberLinkHandler(): (String) -> Unit {
    val navigator = LocalAppNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(navigator, uriHandler) {
        { url: String ->
            if (!navigator.openUri(url)) {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }
}

/** [RichText] → AnnotatedString with clickable links and bold/italic spans. */
@Composable
internal fun rememberRichText(text: RichText): AnnotatedString {
    val onLink by rememberUpdatedState(rememberLinkHandler())
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        buildAnnotatedString {
            append(text.text)
            text.styles.forEach { span ->
                val style = when (span.style) {
                    RichStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    RichStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                addStyle(style, span.start, span.end)
            }
            text.links.forEach { span ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = span.url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onLink(span.url) },
                    ),
                    span.start,
                    span.end,
                )
            }
        }
    }
}

/** Text collapsed to [collapsedLines] with a "Show more / Show less" toggle when it overflows. */
@Composable
internal fun ExpandableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 3,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(
                    stringResource(if (expanded) R.string.detail_show_less else R.string.detail_show_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Disc separator for multi-disc albums. */
@Composable
internal fun SubsectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** "1 hr 5 min", "45 min 12 sec" ([withSeconds]), "45 min", "50 sec". */
@Composable
internal fun durationText(ms: Long, withSeconds: Boolean = false): String {
    val parts = durationParts(ms)
    return when {
        parts.hours > 0 -> stringResource(R.string.detail_duration_hr_min, parts.hours.toInt(), parts.minutes.toInt())
        parts.minutes > 0 && withSeconds && parts.seconds > 0 ->
            stringResource(R.string.detail_duration_min_sec, parts.minutes.toInt(), parts.seconds.toInt())
        parts.minutes > 0 -> stringResource(R.string.detail_duration_min, parts.minutes.toInt())
        else -> stringResource(R.string.detail_duration_sec, parts.seconds.toInt())
    }
}

/** "12 songs, 45 min" (duration omitted when null). */
@Composable
internal fun songsAndDuration(count: Int, durationMs: Long?, withSeconds: Boolean = false): String {
    val songs = pluralStringResource(R.plurals.detail_songs, count, count)
    return if (durationMs == null || durationMs <= 0) songs
    else stringResource(R.string.detail_count_and_duration, songs, durationText(durationMs, withSeconds))
}

@Composable
internal fun albumTypeLabel(type: AlbumType?): String = stringResource(
    when (type) {
        AlbumType.SINGLE -> R.string.detail_type_single
        AlbumType.EP -> R.string.detail_type_ep
        AlbumType.COMPILATION -> R.string.detail_type_compilation
        AlbumType.ALBUM, null -> R.string.detail_type_album
    },
)

/** Releases as carousel/grid tiles with "2024 • Album" subtitles. */
@Composable
internal fun rememberReleaseRefs(albums: List<AlbumRef>, showType: Boolean = true): List<MediaRef> {
    val album = stringResource(R.string.detail_type_album)
    val single = stringResource(R.string.detail_type_single)
    val ep = stringResource(R.string.detail_type_ep)
    val compilation = stringResource(R.string.detail_type_compilation)
    val separator = stringResource(R.string.detail_separator_plain)
    return remember(albums, showType, album, single, ep, compilation, separator) {
        albums.map { ref ->
            val year = releaseYear(ref.releaseDate)?.toString()
            val type = when (ref.albumType) {
                AlbumType.SINGLE -> single
                AlbumType.EP -> ep
                AlbumType.COMPILATION -> compilation
                AlbumType.ALBUM, null -> album
            }
            val subtitle = if (showType) listOfNotNull(year, type).joinToString(separator) else year
            MediaRef(type = MediaType.ALBUM, uri = ref.uri, name = ref.name, subtitle = subtitle, images = ref.images)
        }
    }
}

/** Current locale for date formatting (follows configuration changes). */
@Composable
internal fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.locales.get(0) ?: Locale.getDefault() }
}

/** Calls [onLoadMore] whenever the list is scrolled within [buffer] items of its end. */
@Composable
internal fun LoadMoreEffect(listState: LazyListState, enabled: Boolean, buffer: Int = 10, onLoadMore: () -> Unit) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= info.totalItemsCount - buffer) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (nearEnd, _) -> if (nearEnd) currentOnLoadMore() }
    }
}

/** Footer of a paged list: spinner while loading, retry after a failure. */
@Composable
internal fun PagingFooter(loading: Boolean, failed: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.detail_load_more_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
            }
        }
    }
}
