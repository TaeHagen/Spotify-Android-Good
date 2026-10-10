package com.taehagen.spotifygood

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
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
 * `spotifygood://auth` login redirect, media notification taps (open Now Playing) and "More devices"
 * in Android's output switcher (open the devices sheet).
 *
 * `singleTop`: requests from outside arrive through [LinkActivity] as [EXTRA_REQUEST], so a
 * launcher tap never clears the login Custom Tab above this activity. That relies on the task's
 * root intent being the launcher's (no package): then a launcher tap only brings the task to the
 * front. The app's own entry points all use [launchIntent] (LinkActivity's forward, the media
 * session, notifications, Android Auto's "Sign in"). A root from elsewhere (the package
 * installer's or the Play Store's "Open": `getLaunchIntentForPackage`, package set) doesn't match
 * on Android 13 and below, and a launcher tap stacks a second instance on whatever is on top
 * (the tab, the "Open by default" page): [isDuplicateLaunch] finishes that one at once.
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
        if (isDuplicateLaunch(savedInstanceState)) {
            // Before any ViewModel exists: the login screen below keeps its flow (a cleared
            // LoginViewModel would cancel the device-code login).
            finish()
            return
        }
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

    /**
     * A plain launcher start stacked on this app's own task above its MainActivity (a launcher
     * tap on a task whose root intent doesn't match the launcher's): the task is already where the
     * user wanted to go, with what is on top of it.
     */
    private fun isDuplicateLaunch(savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState != null || isTaskRoot) return false
        val launch = intent ?: return false
        if (launch.action != Intent.ACTION_MAIN || !launch.hasCategory(Intent.CATEGORY_LAUNCHER)) return false
        if (launch.hasExtra(EXTRA_REQUEST) || launch.hasExtra(PlaybackService.EXTRA_OPEN_PLAYER)) return false
        // Only in our own task rooted by MainActivity, not in another app's task that started us.
        val main = ComponentName(this, MainActivity::class.java)
        val tasks = try {
            getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
        return tasks.any { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@any false
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.persistentId
            id == taskId && info.baseActivity == main
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(launch: Intent?) {
        launch ?: return
        // Media notification, lock-screen player or SysUI media card tap (the session activity). The
        // launch intent's action is MAIN, so this is independent of the action switch below.
        if (launch.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            // Consumed: later handling of the same intent object must not open the player again.
            launch.removeExtra(PlaybackService.EXTRA_OPEN_PLAYER)
            shell.openPlayer()
        }
        // A link, redirect, share or voice search forwarded by LinkActivity (consumed likewise).
        val intent = IntentCompat.getParcelableExtra(launch, EXTRA_REQUEST, Intent::class.java)
            ?.also { launch.removeExtra(EXTRA_REQUEST) }
            ?: launch
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
            // "More devices" in Android's output switcher (a listing item, API 34+): the devices
            // sheet, whose local-network section finds the speakers and TVs to sign in.
            ACTION_TRANSFER_MEDIA -> shell.openDevices()
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

    internal companion object {
        private const val AUTH_SCHEME = "spotifygood"

        /** The outside request [LinkActivity] forwards (an [Intent], never started). */
        const val EXTRA_REQUEST = "com.taehagen.spotifygood.extra.REQUEST"

        /**
         * The app's own way to start (or bring up) MainActivity: exactly the launcher's intent
         * (`makeMainActivity`, no package) plus `NEW_TASK`, so a task it creates has the
         * launcher's root intent and a launcher tap only brings it to the front. Add flags and
         * extras as needed; never `getLaunchIntentForPackage` (its package breaks that match).
         */
        fun launchIntent(context: Context): Intent =
            Intent.makeMainActivity(ComponentName(context, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** RouteListingPreference.ACTION_TRANSFER_MEDIA (API 34), forwarded by [LinkActivity]. */
        const val ACTION_TRANSFER_MEDIA = "android.media.action.TRANSFER_MEDIA"

        /** MediaStore.EXTRA_MEDIA_PLAYLIST (deprecated constant, still sent by assistants). */
        const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
    }
}
