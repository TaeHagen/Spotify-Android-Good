package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
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
import com.taehagen.spotifygood.playback.PodcastSpeeds
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator

/**
 * Now Playing inside the expanding player (which draws its background and handles back and the
 * collapse gestures): view model state in, [NowPlayingBody] out.
 */
@Composable
internal fun NowPlayingContent(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val liked by viewModel.isLiked.collectAsStateWithLifecycle()
    val indicator by viewModel.indicator.collectAsStateWithLifecycle()
    // The downloaded cover while offline, so there is artwork without a network.
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val remoteVolumeSupported by viewModel.remoteVolumeSupported.collectAsStateWithLifecycle()
    val lyricsPreview by viewModel.lyricsPreview.collectAsStateWithLifecycle()
    // Ungated by the preview setting: decides whether the lyrics button can open anything.
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val podcastSpeed by viewModel.podcastSpeed.collectAsStateWithLifecycle()
    val podcastSpeedInEffect by viewModel.podcastSpeedInEffect.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                PlayerMessage.LIKE_FAILED -> navigator.showMessage(context.getString(R.string.player_like_failed))
            }
        }
    }

    val track = snapshot.track
    // Read only in the draw phase / leaf composables: the colour animates on every track change.
    val artworkColor = rememberArtworkColor(artwork)

    PlayerSurfaceTheme {
        Box(modifier.fillMaxSize()) {
            if (track == null) {
                NothingPlaying(onCollapse = onCollapse)
            } else {
                // One lifecycle-aware ticker feeds the seek bar and the lyrics preview (stops when not visible).
                val position = viewModel.position.collectAsStateWithLifecycle(initialValue = remember { viewModel.positionNow() })
                NowPlayingBody(
                    snapshot = snapshot,
                    track = track,
                    artwork = artwork,
                    liked = liked,
                    indicator = indicator,
                    remoteVolumeSupported = remoteVolumeSupported,
                    lyrics = (lyricsPreview as? LyricsState.Loaded)?.lyrics,
                    lyricsUnavailable = lyricsState == LyricsState.Unavailable,
                    lyricsFallbackColor = artworkColor,
                    sleepTimer = sleepTimer,
                    position = position,
                    podcastSpeed = podcastSpeed,
                    podcastSpeedInEffect = podcastSpeedInEffect,
                    commands = viewModel,
                    navigator = navigator,
                    onCollapse = onCollapse,
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

/**
 * Now Playing from plain state. Inside the expanding player ([LocalPlayerSheet]) its parts follow
 * p: the top bar and the details fade in late (riding the surface's top edge and the moving art),
 * the art slot reports its bounds and shows its own art only at rest. Draws no background (the
 * player surface does).
 */
@Composable
internal fun NowPlayingBody(
    snapshot: PlaybackSnapshot,
    track: PlaybackTrack,
    artwork: String?,
    liked: Boolean?,
    indicator: DeviceIndicator,
    remoteVolumeSupported: Boolean,
    lyrics: Lyrics?,
    lyricsUnavailable: Boolean,
    lyricsFallbackColor: State<Color>,
    sleepTimer: SleepTimerState,
    position: State<Long>,
    podcastSpeed: Float,
    podcastSpeedInEffect: Float,
    commands: PlayerCommands,
    navigator: AppNavigator,
    onCollapse: () -> Unit,
    onShowSleepTimer: () -> Unit,
) {
    val sheet = LocalPlayerSheet.current
    DisposableEffect(sheet) { onDispose { sheet?.updateLargeArtwork(null) } }
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

    val topBar: @Composable () -> Unit = {
        NowPlayingTopBar(
            playbackContext = snapshot.context,
            isPlayingAutoplay = snapshot.isPlayingAutoplay,
            onCollapse = onCollapse,
            onOpenContext = snapshot.context?.uri?.let { uri -> { openAndCollapse(uri) } },
            modifier = Modifier.playerTopBarMotion(sheet),
        ) {
            NowPlayingMenu(
                track = track,
                sleepTimer = sleepTimer,
                onAddToPlaylist = { navigator.addToPlaylist(listOf(track.uri)) },
                onOpenUri = openAndCollapse,
                onShare = onShare,
                onSleepTimer = onShowSleepTimer,
                onStartRadio = { commands.startRadio(track.uri) },
                onMoreActions = {
                    navigator.showActions(track.toActionTarget(placeholderName, contextUri = snapshot.context?.uri))
                },
            )
        }
    }
    // The art's slot: the moving art lands here, and the art drawn in it shows once at rest.
    val artwork: @Composable (Modifier) -> Unit = { artworkModifier ->
        Box(artworkModifier.then(if (sheet != null) Modifier.onGloballyPositioned(sheet::updateLargeArtwork) else Modifier)) {
            NowPlayingArtwork(
                track = track,
                artwork = artwork,
                canNext = restrictions.canSkipNext,
                canPrevious = restrictions.canSkipPrev,
                onNext = commands::next,
                onPrevious = commands::previous,
                onCollapse = onCollapse,
                modifier = Modifier
                    .fillMaxSize()
                    .playerArtworkAtRest(sheet),
            )
        }
    }
    val details: @Composable (besideArtwork: Boolean) -> Unit = { besideArtwork ->
        Column(
            Modifier
                .fillMaxWidth()
                .playerDetailsMotion(sheet, besideArtwork),
        ) {
            TitleRow(
                track = track,
                liked = liked,
                onToggleLike = commands::toggleLike,
                onOpenUri = openAndCollapse,
            )
            PlaybackProblem(snapshot)
            Spacer(Modifier.height(12.dp))
            PlayerSeekBar(
                position = position,
                durationMs = snapshot.effectiveDurationMs(),
                enabled = restrictions.canSeek,
                onSeek = commands::seekTo,
                trackKey = track.uri,
                modifier = Modifier.keepDragsLocal(),
            )
            TransportControls(
                snapshot = snapshot,
                onShuffle = commands::cycleShuffle,
                onPrevious = commands::previous,
                onPlayPause = commands::togglePlayPause,
                onNext = commands::next,
                onRepeat = commands::cycleRepeat,
                onSeekBy = commands::seekBy,
            )
            // Fixed-volume receivers and some groups ignore volume changes (the thumb would snap back).
            if (isRemote && remoteVolumeSupported) {
                VolumeSlider(
                    volume = snapshot.volume,
                    onVolumeChange = commands::setVolume,
                    modifier = Modifier
                        .fillMaxWidth()
                        .keepDragsLocal(),
                )
            }
            BottomActions(
                indicator = indicator,
                showDeviceName = !isRemote,
                onDevices = navigator::openDevices,
                // Always reachable (the preview card can be turned off or missing after a failed
                // load; the full screen offers Retry). Disabled once Spotify has none for the track.
                lyricsButton = if (track.isEpisode) null else !lyricsUnavailable,
                onLyrics = navigator::openLyrics,
                // Episodes played here (Spotify Connect has no speed command for other devices).
                speed = if (track.isEpisode && !isRemote) podcastSpeedInEffect else null,
                chosenSpeed = podcastSpeed,
                onSpeed = commands::setPodcastSpeed,
                onShare = onShare,
                onQueue = navigator::openQueue,
            )
            if (isRemote && indicator is DeviceIndicator.Remote) {
                RemoteBanner(indicator = indicator, onClick = navigator::openDevices)
            }
        }
    }
    val lyricsCard: @Composable (besideArtwork: Boolean) -> Unit = { besideArtwork ->
        if (lyrics != null) {
            LyricsPreviewCard(
                lyrics = lyrics,
                position = position,
                fallbackColor = lyricsFallbackColor,
                onOpen = navigator::openLyrics,
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 24.dp)
                    .playerDetailsMotion(sheet, besideArtwork),
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        // Too short to give the artwork a useful share of one screen (split screen, huge fonts:
        // the details grow with the font scale and would squeeze the art to nothing).
        val compact = !wide && maxHeight < 560.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
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
                        details(true)
                        lyricsCard(true)
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
                details(false)
                lyricsCard(false)
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
                        details(false)
                        Spacer(Modifier.height(8.dp))
                    }
                    lyricsCard(false)
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
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    val header = stringResource(contextHeaderRes(playbackContext?.type))
    val name = contextName(playbackContext, isPlayingAutoplay)
    val openLabel = stringResource(R.string.player_open_context, name.ifEmpty { header })
    val collapseLabel = stringResource(R.string.player_collapse)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = collapseLabel,
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
                .semantics(mergeDescendants = true) {
                    heading()
                    collapse(collapseLabel) { onCollapse(); true }
                },
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
                track.artists.filter { it.uri.isNotBlank() }.forEach { artist ->
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
    // Refs may carry only a name (the last-session placeholder): nothing to go to then.
    val album = track.album?.takeIf { it.uri.isNotBlank() }
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
    val artistPages = track.artists.filter { it.uri.isNotBlank() }
    if (!track.isEpisode && artistPages.isNotEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_go_to_artist)) },
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            onClick = {
                if (artistPages.size == 1) {
                    dismiss()
                    onOpenUri(artistPages.first().uri)
                } else {
                    onPickArtist()
                }
            },
        )
    }
    val show = track.show?.takeIf { it.uri.isNotBlank() }
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
    artwork: String?,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = track.name.orEmpty()
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val collapseLabel = stringResource(R.string.player_collapse)
    val artworkDescription = stringResource(R.string.player_artwork, title)
    val sheet = LocalPlayerSheet.current
    SwipeToSkipBox(
        canNext = canNext,
        canPrevious = canPrevious,
        onNext = onNext,
        onPrevious = onPrevious,
        modifier = modifier.semantics {
            collapse(collapseLabel) { onCollapse(); true }
            customActions = buildList {
                if (canNext) add(CustomAccessibilityAction(nextLabel) { onNext(); true })
                if (canPrevious) add(CustomAccessibilityAction(previousLabel) { onPrevious(); true })
            }
        },
    ) {
        Crossfade(targetState = artwork, animationSpec = tween(durationMillis = 450), label = "nowPlayingArtwork") { url ->
            PlayerArtwork(
                url = url,
                contentDescription = artworkDescription,
                shape = RoundedCornerShape(LargeArtworkCorner),
                shown = { sheet.showsPlayerArtwork() },
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(elevation = LargeArtworkElevation, shape = RoundedCornerShape(LargeArtworkCorner)),
            )
        }
    }
}

@Composable
private fun TitleRow(
    track: PlaybackTrack,
    liked: Boolean?,
    onToggleLike: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    // Refs may carry only a name (the last-session placeholder): no link then.
    val titleTarget = (if (track.isEpisode) track.show?.uri else track.album?.uri)?.takeIf { it.isNotBlank() }
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
                    fun appendLinked(uri: String, name: String) {
                        if (uri.isBlank()) {
                            append(name)
                        } else {
                            withLink(LinkAnnotation.Clickable(uri, linkStyle) { currentOpen(uri) }) { append(name) }
                        }
                    }
                    if (track.isEpisode) {
                        track.show?.let { show -> appendLinked(show.uri, show.name) }
                    } else {
                        track.artists.forEachIndexed { index, artist ->
                            if (index > 0) append(", ")
                            appendLinked(artist.uri, artist.name)
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
    onSeekBy: (deltaMs: Long) -> Unit,
) {
    val restrictions = snapshot.restrictions
    // Podcasts: skip back / forward 15 s next to play/pause instead of shuffle and repeat.
    val episode = snapshot.track?.isEpisode == true
    val skipSize = if (episode) 32.dp else 40.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!episode) {
            ShuffleButton(
                shuffle = snapshot.shuffle,
                smart = snapshot.smartShuffle,
                enabled = restrictions.canToggleShuffle,
                onClick = onShuffle,
            )
        }
        SkipButton(next = false, enabled = restrictions.canSkipPrev, onClick = onPrevious, iconSize = skipSize)
        if (episode) SeekStepButton(forward = false, enabled = restrictions.canSeek, onClick = { onSeekBy(-SEEK_STEP_MS) })
        PlayPauseButton(
            status = snapshot.status,
            onClick = onPlayPause,
            enabled = !(snapshot.status == PlaybackStatus.PLAYING && !restrictions.canPause),
            size = 68.dp,
        )
        if (episode) SeekStepButton(forward = true, enabled = restrictions.canSeek, onClick = { onSeekBy(SEEK_STEP_MS) })
        SkipButton(next = true, enabled = restrictions.canSkipNext, onClick = onNext, iconSize = skipSize)
        if (!episode) RepeatButton(mode = snapshot.repeat, enabled = restrictions.canToggleRepeat, onClick = onRepeat)
    }
}

@Composable
private fun BottomActions(
    indicator: DeviceIndicator,
    showDeviceName: Boolean,
    onDevices: () -> Unit,
    /** null: no lyrics button (podcast episodes); otherwise whether it is enabled. */
    lyricsButton: Boolean?,
    onLyrics: () -> Unit,
    /** null: no speed button (music, another device playing); otherwise the podcast speed. */
    speed: Float?,
    chosenSpeed: Float,
    onSpeed: (Float) -> Unit,
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
        if (lyricsButton != null) {
            IconButton(onClick = onLyrics, enabled = lyricsButton) {
                Icon(Icons.Rounded.Lyrics, contentDescription = stringResource(R.string.player_open_lyrics))
            }
        }
        if (speed != null) SpeedButton(speed = speed, chosen = chosenSpeed, onSpeed = onSpeed)
        IconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.player_share))
        }
        IconButton(onClick = onQueue) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.player_open_queue))
        }
    }
}

/**
 * The podcast speed in effect ("1.5×"), Spotify's choices in a menu. When the audio output
 * refused the [chosen] speed ([speed] is the highest it takes below it), the menu says so and
 * the speeds known to be refused are disabled.
 */
@Composable
private fun SpeedButton(speed: Float, chosen: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.playback_speed)
    val label = PodcastSpeeds.label(speed)
    val normal = PodcastSpeeds.same(speed, PodcastSpeeds.NORMAL)
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(role = Role.Button) { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$description, $label" },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (normal) PlayerDefaults.PrimaryText else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!PodcastSpeeds.same(speed, chosen)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playback_speed_unsupported, PodcastSpeeds.label(chosen))) },
                    onClick = {},
                    enabled = false,
                )
            }
            PodcastSpeeds.STEPS.forEach { step ->
                val selected = PodcastSpeeds.same(step, speed)
                DropdownMenuItem(
                    text = { Text(PodcastSpeeds.label(step), fontWeight = if (selected) FontWeight.Bold else null) },
                    enabled = !PodcastSpeeds.refused(step, chosen = chosen, inEffect = speed),
                    onClick = {
                        open = false
                        onSpeed(step)
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
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
