package com.taehagen.spotifygood.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.ConfirmDialog
import com.taehagen.spotifygood.ui.screens.library.StateBox
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.ui.screens.library.messageRes

/** [username] null = the logged-in user. */
@Composable
fun ProfileScreen(username: String?, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = "profile:${username.orEmpty()}") { graph -> ProfileViewModel(graph, username) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val user = state.user
        val error = state.error
        when {
            user == null && error != null -> StateBox {
                ErrorState(message = stringResource(error.messageRes()), onRetry = viewModel::retry)
            }
            user == null -> LoadingState()
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    ProfileHeader(user = user, playlistCount = if (state.isMe) state.playlists.size else null, following = state.followedArtists)
                }
                if (state.isMe) {
                    item(key = "actions", contentType = "actions") {
                        ProfileActions(
                            loggingOut = state.loggingOut,
                            onSettings = { navigator.navigate(Route.Settings) },
                            onLogout = { confirmLogout = true },
                        )
                    }
                }
                if (state.playlists.isNotEmpty()) {
                    item(key = "playlistsHeader", contentType = "sectionHeader") {
                        SectionHeader(
                            title = stringResource(R.string.browse_profile_playlists),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                        )
                    }
                    items(state.playlists, key = { "playlist:${it.uri}" }, contentType = { "playlist" }) { ref ->
                        MediaRow(
                            ref = ref,
                            onClick = { navigator.open(ref) },
                            onLongClick = { navigator.showActions(ref.toOwnedPlaylistTarget(user.username)) },
                        )
                    }
                }
            }
        }
        // Back button floating over the header gradient.
        IconButton(onClick = navigator::back, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.browse_profile_logout_title),
            text = stringResource(R.string.browse_profile_logout_message),
            confirmLabel = stringResource(R.string.browse_profile_logout),
            onConfirm = viewModel::logout,
            onDismiss = { confirmLogout = false },
        )
    }
}

private fun MediaRef.toOwnedPlaylistTarget(username: String): MediaActionTarget =
    MediaActionTarget.PlaylistTarget(
        PlaylistRef(uri = uri, name = name, images = images, owner = PlaylistOwner(username, subtitle)),
        isOwned = true,
    )

@Composable
private fun ProfileHeader(user: User, playlistCount: Int?, following: Int?) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.background),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                url = user.images.best(480),
                contentDescription = null,
                shape = CircleShape,
                placeholderIcon = Icons.Rounded.Person,
                modifier = Modifier.size(144.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = user.displayName?.takeIf { it.isNotBlank() } ?: user.username,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            user.product?.let { product ->
                Spacer(Modifier.height(8.dp))
                ProductBadge(product)
            }
            val stats = buildList {
                if (playlistCount != null) add(pluralStringResource(R.plurals.browse_profile_playlist_count, playlistCount, playlistCount))
                if (following != null) add(pluralStringResource(R.plurals.browse_profile_following_count, following, following))
            }
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stats.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProductBadge(product: String) {
    val premium = product.equals("premium", ignoreCase = true)
    Surface(
        shape = CircleShape,
        color = if (premium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (premium) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (premium) Icon(Icons.Rounded.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = if (premium) stringResource(R.string.browse_profile_premium) else product.replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = if (premium) 4.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun ProfileActions(loggingOut: Boolean, onSettings: () -> Unit, onLogout: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        OutlinedButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.browse_settings))
        }
        OutlinedButton(
            onClick = onLogout,
            enabled = !loggingOut,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            if (loggingOut) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(if (loggingOut) R.string.browse_profile_logging_out else R.string.browse_profile_logout))
        }
    }
}
