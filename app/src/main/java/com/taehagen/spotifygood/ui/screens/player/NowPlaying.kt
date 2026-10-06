package com.taehagen.spotifygood.ui.screens.player

import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.model.LyricsSyncType
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NowPlayingContent(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val liked by viewModel.isLiked.collectAsStateWithLifecycle()
    val indicator by viewModel.indicator.collectAsStateWithLifecycle()
    val remoteVolumeSupported by viewModel.remoteVolumeSupported.collectAsStateWithLifecycle()
    val lyricsPreview by viewModel.lyricsPreview.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }

    // The scaffold may also collapse on back: only the first request within a short window counts.
    val currentOnCollapse by rememberUpdatedState(onCollapse)
    val lastCollapse = remember { longArrayOf(0L) }
    val collapse: () -> Unit = remember {
        {
            val now = SystemClock.uptimeMillis()
            if (now - lastCollapse[0] > 600) {
                lastCollapse[0] = now
                currentOnCollapse()
            }
        }
    }

    // Predictive back: shrink with the gesture, collapse on commit. Only enabled while this screen
    // really covers the window, so a host that keeps it composed while collapsed keeps back working.
    val coverage = rememberWindowCoverage()
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    var backCommits by remember { mutableIntStateOf(0) }
    PredictiveBackHandler(enabled = coverage.coversWindow) { events ->
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            collapse()
            backCommits++
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f) }
            throw e
        }
    }
    LaunchedEffect(backCommits) {
        // Still on screen after a committed back (e.g. the host ignored it): restore the layout.
        if (backCommits > 0) {
            delay(700)
            backProgress.animateTo(0f)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                PlayerMessage.LIKE_FAILED -> navigator.showMessage(context.getString(R.string.player_like_failed))
            }
        }
    }

    val track = snapshot.track
    // Read only in the draw phase / leaf composables: the colour animates on every track change.
    val artworkColor = rememberArtworkColor(track?.imageUrl)

    PlayerSurfaceTheme {
        Box(
            modifier
                .fillMaxSize()
                .then(coverage.modifier)
                .graphicsLayer {
                    val p = backProgress.value
                    val scale = 1f - 0.08f * p
                    scaleX = scale
                    scaleY = scale
                    translationY = p * 48.dp.toPx()
                    shape = RoundedCornerShape((28 * p).dp)
                    clip = p > 0f
                }
                .drawBehind {
                    val topColor = artworkColor.value.toned(maxLightness = 0.36f, minLightness = 0.16f)
                    drawRect(PlayerDefaults.Background)
                    drawRect(Brush.verticalGradient(0f to topColor, 0.85f to PlayerDefaults.Background))
                },
        ) {
            if (track == null) {
                NothingPlaying(onCollapse = collapse)
            } else {
                NowPlayingBody(
                    snapshot = snapshot,
                    track = track,
                    liked = liked,
                    indicator = indicator,
                    remoteVolumeSupported = remoteVolumeSupported,
                    lyrics = (lyricsPreview as? LyricsState.Loaded)?.lyrics,
                    lyricsFallbackColor = artworkColor,
                    sleepTimer = sleepTimer,
                    viewModel = viewModel,
                    navigator = navigator,
                    onCollapse = collapse,
                    onShowSleepTimer = { showSleepTimer = true },
                )
            }
        }
    }

    if (showSleepTimer) {
        SleepTimerSheetContent(onDismiss = { showSleepTimer = false })
    }
}

@Composable
private fun NothingPlaying(onCollapse: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse), modifier = Modifier.size(32.dp))
        }
        EmptyState(title = stringResource(R.string.player_nothing_playing), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NowPlayingBody(
    snapshot: PlaybackSnapshot,
    track: PlaybackTrack,
    liked: Boolean,
    indicator: DeviceIndicator,
    remoteVolumeSupported: Boolean,
    lyrics: Lyrics?,
    lyricsFallbackColor: State<Color>,
    sleepTimer: SleepTimerState,
    viewModel: PlayerViewModel,
    navigator: AppNavigator,
    onCollapse: () -> Unit,
    onShowSleepTimer: () -> Unit,
) {
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.player_share_failed)
    val shareChooser = stringResource(R.string.player_share_chooser)
    val placeholderName = stringResource(R.string.player_track_loading)
    val openAndCollapse: (String) -> Unit = { uri -> if (navigator.openUri(uri)) onCollapse() }
    val onShare: () -> Unit = {
        if (!shareSpotifyLink(context, track.uri, track.name, shareChooser)) navigator.showMessage(shareFailed)
    }
    val restrictions = snapshot.restrictions
    val isRemote = snapshot.source == PlaybackSource.REMOTE
    // One lifecycle-aware ticker feeds the seek bar and the lyrics preview (stops when not visible).
    val position = viewModel.position.collectAsStateWithLifecycle(initialValue = remember { viewModel.positionNow() })

    val topBar: @Composable () -> Unit = {
        NowPlayingTopBar(
            playbackContext = snapshot.context,
            isPlayingAutoplay = snapshot.isPlayingAutoplay,
            onCollapse = onCollapse,
            onOpenContext = snapshot.context?.uri?.let { uri -> { openAndCollapse(uri) } },
        ) {
            NowPlayingMenu(
                track = track,
                sleepTimer = sleepTimer,
                onAddToPlaylist = { navigator.addToPlaylist(listOf(track.uri)) },
                onOpenUri = openAndCollapse,
                onShare = onShare,
                onSleepTimer = onShowSleepTimer,
                onStartRadio = { viewModel.startRadio(track.uri) },
                onMoreActions = {
                    navigator.showActions(track.toActionTarget(placeholderName, contextUri = snapshot.context?.uri))
                },
            )
        }
    }
    val artwork: @Composable (Modifier) -> Unit = { artworkModifier ->
        NowPlayingArtwork(
            track = track,
            canNext = restrictions.canSkipNext,
            canPrevious = restrictions.canSkipPrev,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            modifier = artworkModifier,
        )
    }
    val details: @Composable () -> Unit = {
        TitleRow(
            track = track,
            liked = liked,
            onToggleLike = viewModel::toggleLike,
            onOpenUri = openAndCollapse,
        )
        PlaybackProblem(snapshot)
        Spacer(Modifier.height(12.dp))
        PlayerSeekBar(
            position = position,
            durationMs = snapshot.effectiveDurationMs(),
            enabled = restrictions.canSeek,
            onSeek = viewModel::seekTo,
            trackKey = track.uri,
        )
        TransportControls(
            snapshot = snapshot,
            onShuffle = viewModel::cycleShuffle,
            onPrevious = viewModel::previous,
            onPlayPause = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onRepeat = viewModel::cycleRepeat,
        )
        // Fixed-volume receivers and some groups ignore volume changes (the thumb would snap back).
        if (isRemote && remoteVolumeSupported) {
            VolumeSlider(
                volume = snapshot.volume,
                onVolumeChange = viewModel::setVolume,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BottomActions(
            indicator = indicator,
            showDeviceName = !isRemote,
            onDevices = navigator::openDevices,
            onShare = onShare,
            onQueue = navigator::openQueue,
        )
        if (isRemote && indicator is DeviceIndicator.Remote) {
            RemoteBanner(indicator = indicator, onClick = navigator::openDevices)
        }
    }
    val lyricsCard: @Composable () -> Unit = {
        if (lyrics != null) {
            LyricsPreviewCard(
                lyrics = lyrics,
                position = position,
                fallbackColor = lyricsFallbackColor,
                onOpen = navigator::openLyrics,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        // Too short to give the artwork a useful share of one screen (split screen, huge fonts).
        val compact = !wide && maxHeight < 560.dp
        when {
            wide -> Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    topBar()
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        artwork(Modifier.aspectRatio(1f))
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        details()
                        lyricsCard()
                    }
                }
            }
            compact -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                topBar()
                artwork(
                    Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(0.6f)
                        .aspectRatio(1f),
                )
                details()
                lyricsCard()
            }
            else -> {
                // The first screen holds artwork + controls; the lyrics card follows when scrolling.
                val viewportHeight = maxHeight
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    Column(Modifier.fillMaxWidth().height(viewportHeight)) {
                        topBar()
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            artwork(Modifier.aspectRatio(1f))
                        }
                        details()
                        Spacer(Modifier.height(8.dp))
                    }
                    lyricsCard()
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    playbackContext: PlaybackContext?,
    isPlayingAutoplay: Boolean,
    onCollapse: () -> Unit,
    onOpenContext: (() -> Unit)?,
    menu: @Composable () -> Unit,
) {
    val header = stringResource(contextHeaderRes(playbackContext?.type))
    val name = contextName(playbackContext, isPlayingAutoplay)
    val openLabel = stringResource(R.string.player_open_context, name.ifEmpty { header })
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_collapse),
                modifier = Modifier.size(32.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (onOpenContext != null) {
                        Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenContext)
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = PlayerDefaults.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (name.isNotEmpty()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        menu()
    }
}

private fun contextHeaderRes(type: ContextType?): Int = when (type) {
    ContextType.PLAYLIST -> R.string.player_from_playlist
    ContextType.ALBUM -> R.string.player_from_album
    ContextType.ARTIST -> R.string.player_from_artist
    ContextType.COLLECTION -> R.string.player_from_library
    ContextType.SEARCH -> R.string.player_from_search
    ContextType.SHOW -> R.string.player_from_podcast
    ContextType.STATION -> R.string.player_from_radio
    ContextType.TRACKS, ContextType.UNKNOWN, null -> R.string.player_now_playing_header
}

@Composable
internal fun contextName(playbackContext: PlaybackContext?, isPlayingAutoplay: Boolean): String = when {
    playbackContext == null -> ""
    !playbackContext.name.isNullOrBlank() -> playbackContext.name
    isPlayingAutoplay -> stringResource(R.string.player_autoplay_context)
    playbackContext.type == ContextType.COLLECTION -> stringResource(R.string.player_liked_songs)
    else -> ""
}

@Composable
private fun NowPlayingMenu(
    track: PlaybackTrack,
    sleepTimer: SleepTimerState,
    onAddToPlaylist: () -> Unit,
    onOpenUri: (String) -> Unit,
    onShare: () -> Unit,
    onSleepTimer: () -> Unit,
    onStartRadio: () -> Unit,
    onMoreActions: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pickArtist by remember { mutableStateOf(false) }
    val dismiss = {
        expanded = false
        pickArtist = false
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.player_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
            if (pickArtist) {
                track.artists.forEach { artist ->
                    DropdownMenuItem(
                        text = { Text(artist.name) },
                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        onClick = {
                            dismiss()
                            onOpenUri(artist.uri)
                        },
                    )
                }
            } else {
                MainMenuItems(
                    track = track,
                    sleepTimer = sleepTimer,
                    dismiss = dismiss,
                    onPickArtist = { pickArtist = true },
                    onAddToPlaylist = onAddToPlaylist,
                    onOpenUri = onOpenUri,
                    onShare = onShare,
                    onSleepTimer = onSleepTimer,
                    onStartRadio = onStartRadio,
                    onMoreActions = onMoreActions,
                )
            }
        }
    }
}

@Composable
private fun MainMenuItems(
    track: PlaybackTrack,
    sleepTimer: SleepTimerState,
    dismiss: () -> Unit,
    onPickArtist: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenUri: (String) -> Unit,
    onShare: () -> Unit,
    onSleepTimer: () -> Unit,
    onStartRadio: () -> Unit,
    onMoreActions: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.player_add_to_playlist)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
        onClick = {
            dismiss()
            onAddToPlaylist()
        },
    )
    val album = track.album
    if (!track.isEpisode && album != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_go_to_album)) },
            leadingIcon = { Icon(Icons.Rounded.Album, contentDescription = null) },
            onClick = {
                dismiss()
                onOpenUri(album.uri)
            },
        )
    }
    if (!track.isEpisode && track.artists.isNotEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_go_to_artist)) },
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            onClick = {
                if (track.artists.size == 1) {
                    dismiss()
                    onOpenUri(track.artists.first().uri)
                } else {
                    onPickArtist()
                }
            },
        )
    }
    val show = track.show
    if (track.isEpisode && show != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_go_to_podcast)) },
            leadingIcon = { Icon(Icons.Rounded.Podcasts, contentDescription = null) },
            onClick = {
                dismiss()
                onOpenUri(show.uri)
            },
        )
    }
    if (spotifyShareUrl(track.uri) != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_share)) },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            onClick = {
                dismiss()
                onShare()
            },
        )
    }
    val timerLabel = sleepTimerShortLabel(context, sleepTimer)
    DropdownMenuItem(
        text = {
            Text(
                if (timerLabel == null) {
                    stringResource(R.string.player_sleep_timer)
                } else {
                    stringResource(R.string.player_sleep_timer_active, timerLabel)
                },
            )
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.Bedtime,
                contentDescription = null,
                tint = if (timerLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = {
            dismiss()
            onSleepTimer()
        },
    )
    if (!track.isEpisode) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_start_radio)) },
            leadingIcon = { Icon(Icons.Rounded.Radio, contentDescription = null) },
            onClick = {
                dismiss()
                onStartRadio()
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.player_more_actions)) },
        leadingIcon = { Icon(Icons.Rounded.MoreHoriz, contentDescription = null) },
        onClick = {
            dismiss()
            onMoreActions()
        },
    )
}

@Composable
private fun NowPlayingArtwork(
    track: PlaybackTrack,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = track.name.orEmpty()
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val artworkDescription = stringResource(R.string.player_artwork, title)
    SwipeToSkipBox(
        canNext = canNext,
        canPrevious = canPrevious,
        onNext = onNext,
        onPrevious = onPrevious,
        modifier = modifier.semantics {
            customActions = buildList {
                if (canNext) add(CustomAccessibilityAction(nextLabel) { onNext(); true })
                if (canPrevious) add(CustomAccessibilityAction(previousLabel) { onPrevious(); true })
            }
        },
    ) {
        Crossfade(targetState = track.imageUrl, animationSpec = tween(durationMillis = 450), label = "nowPlayingArtwork") { url ->
            Artwork(
                url = url,
                contentDescription = artworkDescription,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun TitleRow(
    track: PlaybackTrack,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    val titleTarget = if (track.isEpisode) track.show?.uri else track.album?.uri
    val openAlbumLabel = stringResource(if (track.isEpisode) R.string.player_go_to_podcast else R.string.player_open_album)
    val currentOpen by rememberUpdatedState(onOpenUri)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name ?: stringResource(R.string.player_track_loading),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .then(
                        if (titleTarget != null) {
                            Modifier.clickable(onClickLabel = openAlbumLabel) { currentOpen(titleTarget) }
                        } else {
                            Modifier
                        },
                    )
                    .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_500),
            )
            val linkStyle = TextLinkStyles(
                style = SpanStyle(color = PlayerDefaults.SecondaryText),
                pressedStyle = SpanStyle(color = PlayerDefaults.PrimaryText, textDecoration = TextDecoration.Underline),
            )
            val subtitle = remember(track.uri, track.artists, track.show, track.isEpisode) {
                buildAnnotatedString {
                    if (track.isEpisode) {
                        val show = track.show
                        if (show != null) {
                            withLink(LinkAnnotation.Clickable(show.uri, linkStyle) { currentOpen(show.uri) }) { append(show.name) }
                        }
                    } else {
                        track.artists.forEachIndexed { index, artist ->
                            if (index > 0) append(", ")
                            withLink(LinkAnnotation.Clickable(artist.uri, linkStyle) { currentOpen(artist.uri) }) { append(artist.name) }
                        }
                    }
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PlayerDefaults.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        LikeButton(liked = liked, onToggle = onToggleLike)
    }
}

@Composable
private fun PlaybackProblem(snapshot: PlaybackSnapshot) {
    val unavailable = snapshot.track?.provider == TrackProvider.UNAVAILABLE
    val message = snapshot.lastError?.takeIf { it.isNotBlank() }
        ?: if (unavailable) stringResource(R.string.player_unavailable) else null
    // Keeps the text while the row animates out.
    val lastMessage = remember { mutableStateOf("") }
    SideEffect { if (message != null) lastMessage.value = message }
    AnimatedVisibility(visible = message != null) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = PlayerDefaults.SecondaryText,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = message ?: lastMessage.value,
                style = MaterialTheme.typography.bodySmall,
                color = PlayerDefaults.SecondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransportControls(
    snapshot: PlaybackSnapshot,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    val restrictions = snapshot.restrictions
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShuffleButton(
            shuffle = snapshot.shuffle,
            smart = snapshot.smartShuffle,
            enabled = restrictions.canToggleShuffle,
            onClick = onShuffle,
        )
        SkipButton(next = false, enabled = restrictions.canSkipPrev, onClick = onPrevious, iconSize = 40.dp)
        PlayPauseButton(
            status = snapshot.status,
            onClick = onPlayPause,
            enabled = !(snapshot.status == PlaybackStatus.PLAYING && !restrictions.canPause),
            size = 68.dp,
        )
        SkipButton(next = true, enabled = restrictions.canSkipNext, onClick = onNext, iconSize = 40.dp)
        RepeatButton(mode = snapshot.repeat, enabled = restrictions.canToggleRepeat, onClick = onRepeat)
    }
}

@Composable
private fun BottomActions(
    indicator: DeviceIndicator,
    showDeviceName: Boolean,
    onDevices: () -> Unit,
    onShare: () -> Unit,
    onQueue: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val devicesLabel = stringResource(R.string.player_devices)
    val name = when (indicator) {
        is DeviceIndicator.Remote -> indicator.name
        is DeviceIndicator.LocalOutput -> indicator.name
        DeviceIndicator.None -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onDevices)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (name != null) "$devicesLabel, $name" else devicesLabel
                    },
            ) {
                Icon(
                    indicator.icon(),
                    contentDescription = null,
                    tint = if (indicator is DeviceIndicator.None) PlayerDefaults.PrimaryText else accent,
                    modifier = Modifier.size(20.dp),
                )
                if (showDeviceName && name != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = name,
                        color = accent,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.player_share))
        }
        IconButton(onClick = onQueue) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.player_open_queue))
        }
    }
}

@Composable
private fun RemoteBanner(indicator: DeviceIndicator.Remote, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(indicator.type.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.player_playing_on, indicator.name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact lyrics card under the controls; the highlighted line follows playback. */
@Composable
private fun LyricsPreviewCard(
    lyrics: Lyrics,
    position: State<Long>,
    fallbackColor: State<Color>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberLyricsPalette(lyrics, fallbackColor.value)
    val synced = lyrics.syncType != LyricsSyncType.UNSYNCED
    val currentIndex by remember(lyrics, synced, position) {
        derivedStateOf { if (synced) lyricsLineIndexAt(lyrics.lines, position.value) else -1 }
    }
    val window = lyricsPreviewWindow(lyrics.lines.size, currentIndex)
    Surface(
        onClick = onOpen,
        color = colors.background,
        contentColor = colors.highlight,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.player_lyrics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.player_open_lyrics),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            for (index in window) {
                val line = lyrics.lines[index]
                Text(
                    text = line.words.ifBlank { stringResource(R.string.player_lyrics_instrumental) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.colorFor(index, currentIndex, synced),
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}
