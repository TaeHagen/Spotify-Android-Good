package com.taehagen.spotifygood

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taehagen.spotifygood.playback.PlaybackService
import com.taehagen.spotifygood.ui.AppRoot
import com.taehagen.spotifygood.ui.MediaSearchRequest
import com.taehagen.spotifygood.ui.ShellViewModel
import com.taehagen.spotifygood.ui.navigation.SpotifyLinks
import com.taehagen.spotifygood.ui.theme.SpotifyGoodTheme

/**
 * Single activity hosting the Compose UI (owned by the UI shell): edge-to-edge, splash screen,
 * notification permission request, deep links (`spotify:`, open.spotify.com), the
 * `spotifygood://auth` login redirect and media notification taps (open Now Playing).
 */
class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as App).graph

    private val shell: ShellViewModel by viewModels {
        viewModelFactory { initializer { ShellViewModel((application as App).graph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the splash up while the login state is unknown (no login-screen flash on launch).
        splash.setKeepOnScreenCondition { !shell.ready.value }

        graph.playbackConnector.attach(this)

        // Recreated activities (rotation) or launches from Recents must not replay old intents.
        val fromHistory = intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)

        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            SpotifyGoodTheme(settings) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Media notification, lock-screen player or SysUI media card tap (the session activity). The
        // launch intent's action is MAIN, so this is independent of the action switch below.
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            // Consumed: later handling of the same intent object must not open the player again.
            intent.removeExtra(PlaybackService.EXTRA_OPEN_PLAYER)
            shell.openPlayer()
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme.equals(AUTH_SCHEME, ignoreCase = true)) {
                    graph.auth.handleRedirect(data)
                } else {
                    shell.openLink(data.toString())
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val link = SpotifyLinks.findLink(text)
                if (link != null) {
                    shell.openLink(link)
                } else {
                    shell.showMessage(getString(R.string.shell_msg_no_link_in_share))
                }
            }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                shell.playFromSearch(
                    MediaSearchRequest(
                        query = intent.getStringExtra(SearchManager.QUERY),
                        focus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS),
                        artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST),
                        album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM),
                        title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE),
                        playlist = intent.getStringExtra(EXTRA_MEDIA_PLAYLIST),
                    ),
                )
            }
        }
    }

    private companion object {
        const val AUTH_SCHEME = "spotifygood"

        /** MediaStore.EXTRA_MEDIA_PLAYLIST (deprecated constant, still sent by assistants). */
        const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
    }
}
