package com.taehagen.spotifygood.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.taehagen.spotifygood.ui.screens.album.AlbumScreen
import com.taehagen.spotifygood.ui.screens.artist.ArtistDiscographyScreen
import com.taehagen.spotifygood.ui.screens.artist.ArtistScreen
import com.taehagen.spotifygood.ui.screens.home.HomeScreen
import com.taehagen.spotifygood.ui.screens.library.DownloadsScreen
import com.taehagen.spotifygood.ui.screens.library.LibraryScreen
import com.taehagen.spotifygood.ui.screens.library.LikedSongsScreen
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistScreen
import com.taehagen.spotifygood.ui.screens.profile.ProfileScreen
import com.taehagen.spotifygood.ui.screens.search.SearchResultsScreen
import com.taehagen.spotifygood.ui.screens.search.SearchScreen
import com.taehagen.spotifygood.ui.screens.settings.SettingsScreen
import com.taehagen.spotifygood.ui.screens.show.EpisodeScreen
import com.taehagen.spotifygood.ui.screens.show.ShowScreen

/**
 * Navigation host for every [Route] (type-safe navigation-compose). [contentPadding] carries the
 * mini player + navigation insets that scrolling content must keep clear of.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Home,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable<Route.Home> { HomeScreen(contentPadding) }
        composable<Route.Search> { SearchScreen(contentPadding) }
        composable<Route.Library> { LibraryScreen(contentPadding) }
        composable<Route.LikedSongs> { LikedSongsScreen(contentPadding) }
        composable<Route.Downloads> { DownloadsScreen(contentPadding) }
        composable<Route.Settings> { SettingsScreen(contentPadding) }
        composable<Route.Album> { entry -> AlbumScreen(entry.toRoute<Route.Album>().uri, contentPadding) }
        composable<Route.Artist> { entry -> ArtistScreen(entry.toRoute<Route.Artist>().uri, contentPadding) }
        composable<Route.ArtistDiscography> { entry ->
            val route = entry.toRoute<Route.ArtistDiscography>()
            ArtistDiscographyScreen(route.uri, route.group, contentPadding)
        }
        composable<Route.Playlist> { entry -> PlaylistScreen(entry.toRoute<Route.Playlist>().uri, contentPadding) }
        composable<Route.Show> { entry -> ShowScreen(entry.toRoute<Route.Show>().uri, contentPadding) }
        composable<Route.Episode> { entry -> EpisodeScreen(entry.toRoute<Route.Episode>().uri, contentPadding) }
        composable<Route.Profile> { entry -> ProfileScreen(entry.toRoute<Route.Profile>().username, contentPadding) }
        composable<Route.SearchResults> { entry ->
            val route = entry.toRoute<Route.SearchResults>()
            SearchResultsScreen(route.query, route.type, contentPadding)
        }
    }
}

private const val DURATION_MS = 260
private const val TAB_FADE_MS = 180

private fun NavBackStackEntry.isTabRoot(): Boolean =
    MainTab.entries.any { destination.hasRoute(it.route::class) }

/** Tab switches cross-fade; pushes slide in from the end with a fade. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.isTabRoot() && targetState.isTabRoot()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (isTabSwitch()) {
        fadeIn(tween(TAB_FADE_MS))
    } else {
        slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(DURATION_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (isTabSwitch()) {
        fadeOut(tween(TAB_FADE_MS))
    } else {
        slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { -it / 10 } + fadeOut(tween(DURATION_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (isTabSwitch()) {
        fadeIn(tween(TAB_FADE_MS))
    } else {
        slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { -it / 10 } + fadeIn(tween(DURATION_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (isTabSwitch()) {
        fadeOut(tween(TAB_FADE_MS))
    } else {
        slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(DURATION_MS))
    }
