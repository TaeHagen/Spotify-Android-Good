package com.taehagen.spotifygood.ui.screens.login

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.auth.LoginState
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.friendlyErrorMessage
import com.taehagen.spotifygood.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Login flows (docs/ARCHITECTURE.md §9.3); thin wrapper around [com.taehagen.spotifygood.auth.AuthRepository]. */
class LoginViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<LoginState> = graph.auth.state

    /** Last engine failure (e.g. PREMIUM_REQUIRED after a successful OAuth exchange). */
    val engineError: StateFlow<NativeErrorInfo?> = graph.engine.state
        .map { it.error }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.engine.state.value.error)

    /** Whether the engine is logged in (a stale [LoginState.Success] then shows the options). */
    val loggedIn: StateFlow<Boolean> = graph.engine.isLoggedIn

    /** Name other Spotify apps see during zeroconf login (the same rule the engine uses). */
    val deviceName: String
        get() = graph.engine.zeroconfDeviceName()

    fun startDeviceLogin() = graph.auth.startDeviceLogin()
    fun openApprovalPage(activity: Activity) = graph.auth.openApprovalPage(activity)
    fun startBrowserLogin(activity: Activity) = graph.auth.startBrowserLogin(activity)
    fun startZeroconfLogin(context: Context) = graph.auth.startZeroconfLogin(context)
    fun cancel() = graph.auth.cancel()

    /** Visible (ON_START, first composition): resumes a paused or persisted device login. */
    fun onScreenShown() = graph.auth.onLoginScreenShown()

    /** Hidden (ON_STOP, left composition): stops zeroconf, pauses device-code polling. */
    fun onScreenHidden() = graph.auth.onLoginScreenHidden()

    override fun onCleared() {
        // The login screen is left for good (activity finished, not a configuration change):
        // stop polling / LAN advertising. Process death keeps the persisted device code.
        graph.auth.onLoginScreenLeft()
    }
}

internal enum class LoginStep { OPTIONS, DEVICE_CODE, BROWSER, ZEROCONF, CONNECTING }

/**
 * The card for [state]. A [LoginState.Success] while logged out is stale (a logout or rejected
 * credentials undid it): the options are shown, never an endless "Connecting".
 */
internal fun loginStep(state: LoginState, loggedIn: Boolean): LoginStep = when (state) {
    LoginState.Idle, is LoginState.Failed -> LoginStep.OPTIONS
    is LoginState.AwaitingApproval -> LoginStep.DEVICE_CODE
    LoginState.WaitingForBrowser -> LoginStep.BROWSER
    LoginState.WaitingForDevice -> LoginStep.ZEROCONF
    LoginState.Connecting -> LoginStep.CONNECTING
    LoginState.Success -> if (loggedIn) LoginStep.CONNECTING else LoginStep.OPTIONS
}

@Composable
fun LoginScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { LoginViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val engineError by vm.engineError.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Polling and LAN advertising only while the screen is visible (docs/ARCHITECTURE.md §9.3).
    // A configuration change is not "hidden": the flows survive it.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onScreenShown() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.onScreenHidden()
    }
    DisposableEffect(vm) {
        onDispose {
            if (activity?.isChangingConfigurations != true) vm.onScreenHidden()
        }
    }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.shell_login_code_copied)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .background(
                Brush.verticalGradient(
                    0f to colors.primary.copy(alpha = 0.30f),
                    0.45f to colors.primary.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Hero()
                Spacer(Modifier.weight(1f).heightIn(min = 40.dp))
                Box(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = loginStep(state, loggedIn),
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "loginStep",
                    ) { step ->
                        when (step) {
                            LoginStep.OPTIONS -> LoginOptions(
                                error = loginErrorText(context, state as? LoginState.Failed, engineError),
                                onDeviceLogin = vm::startDeviceLogin,
                                onBrowserLogin = { activity?.let(vm::startBrowserLogin) },
                                onZeroconfLogin = { vm.startZeroconfLogin(context) },
                            )
                            LoginStep.DEVICE_CODE -> (state as? LoginState.AwaitingApproval)?.let { awaiting ->
                                DeviceCodeCard(
                                    state = awaiting,
                                    onOpenApproval = { activity?.let(vm::openApprovalPage) },
                                    onCopy = {
                                        copyToClipboard(context, awaiting.userCode)
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            scope.launch { snackbar.showSnackbar(copiedMessage) }
                                        }
                                    },
                                    onNewCode = vm::startDeviceLogin,
                                    onCancel = vm::cancel,
                                )
                            } ?: ConnectingCard()
                            LoginStep.BROWSER -> WaitingCard(
                                icon = { Icon(Icons.Rounded.Language, null, tint = colors.primary, modifier = Modifier.size(32.dp)) },
                                title = stringResource(R.string.shell_login_browser_title),
                                body = stringResource(R.string.shell_login_browser_body),
                                onCancel = vm::cancel,
                            )
                            LoginStep.ZEROCONF -> ZeroconfCard(deviceName = vm.deviceName, onCancel = vm::cancel)
                            LoginStep.CONNECTING -> ConnectingCard()
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                PremiumNote()
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

private fun loginErrorText(context: Context, failed: LoginState.Failed?, engineError: NativeErrorInfo?): String? {
    // LoginState.Failed.message is already user-facing (localised by the auth layer).
    if (failed != null) return failed.message.ifBlank { friendlyErrorMessage(context, failed.code) }
    return engineError
        ?.takeIf { it.code == NativeErrorCode.PREMIUM_REQUIRED || it.code == NativeErrorCode.BAD_CREDENTIALS }
        ?.let { friendlyErrorMessage(context, it) }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.shell_login_code_label), text))
}

/** Original brand mark: green rounded tile with three equalizer bars cut out. */
@Composable
fun AppMark(modifier: Modifier = Modifier, tint: Color = AppColors.Brand, cutout: Color = MaterialTheme.colorScheme.background) {
    Canvas(modifier.size(88.dp)) {
        val s = size.minDimension
        // Same proportions as res/drawable/ic_launcher_foreground.xml (48-unit tile).
        drawRoundRect(color = tint, size = Size(s, s), cornerRadius = CornerRadius(s * 0.25f))
        val barW = s * 0.146f
        val gap = s * 0.083f
        val left = (s - (barW * 3 + gap * 2)) / 2f
        val bottom = s * 0.8125f
        listOf(0.29f, 0.5625f, 0.417f).forEachIndexed { i, h ->
            val height = s * h
            drawRoundRect(
                color = cutout,
                topLeft = Offset(left + i * (barW + gap), bottom - height),
                size = Size(barW, height),
                cornerRadius = CornerRadius(barW * 0.36f),
            )
        }
    }
}

@Composable
private fun Hero() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark()
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.shell_login_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoginOptions(
    error: String?,
    onDeviceLogin: () -> Unit,
    onBrowserLogin: () -> Unit,
    onZeroconfLogin: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(
            onClick = onDeviceLogin,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = AppColors.OnBrand),
        ) {
            Text(stringResource(R.string.shell_login_primary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onBrowserLogin,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shell_login_browser), style = MaterialTheme.typography.titleSmall)
        }
        TextButton(
            onClick = onZeroconfLogin,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shell_login_zeroconf), textAlign = TextAlign.Center)
        }
    }
}

/** Live "mm:ss" until [expiresAtMs]; ticks only while the screen is started. */
@Composable
private fun rememberRemainingMs(expiresAtMs: Long): Long {
    var remaining by remember(expiresAtMs) { mutableLongStateOf(expiresAtMs - System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(expiresAtMs, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                remaining = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)
                if (remaining <= 0) break
                delay(1_000L - (System.currentTimeMillis() % 1_000L))
            }
        }
    }
    return remaining.coerceAtLeast(0)
}

@Composable
private fun DeviceCodeCard(
    state: LoginState.AwaitingApproval,
    onOpenApproval: () -> Unit,
    onCopy: () -> Unit,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val remaining = rememberRemainingMs(state.expiresAtMs)
    val expired = remaining <= 0
    val spokenCode = remember(state.userCode) { state.userCode.filter { it.isLetterOrDigit() }.toList().joinToString(" ") }
    Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.shell_login_code_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.shell_login_code_body, state.verificationUri),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            SelectionContainer {
                Text(
                    text = state.userCode,
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.12.em),
                    fontWeight = FontWeight.Bold,
                    color = if (expired) colors.onSurfaceVariant else colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = spokenCode },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expired) {
                    stringResource(R.string.shell_login_code_expired)
                } else {
                    stringResource(R.string.shell_login_code_expires, formatCountdown(remaining))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (expired) colors.error else colors.onSurfaceVariant,
            )
            TextButton(onClick = onCopy, enabled = !expired, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shell_login_copy_code))
            }
            Spacer(Modifier.height(8.dp))
            if (expired) {
                Button(
                    onClick = onNewCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = AppColors.OnBrand),
                ) { Text(stringResource(R.string.shell_login_new_code), fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = onOpenApproval,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = AppColors.OnBrand),
                ) {
                    Text(stringResource(R.string.shell_login_open_approval), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.shell_login_waiting_approval),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shell_cancel))
            }
        }
    }
}

private fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun WaitingCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onCancel: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            content()
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(4.dp),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shell_cancel))
            }
        }
    }
}

@Composable
private fun ZeroconfCard(deviceName: String, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    WaitingCard(
        icon = { Icon(Icons.Rounded.Wifi, null, tint = colors.primary, modifier = Modifier.size(32.dp)) },
        title = stringResource(R.string.shell_login_zeroconf_title),
        body = stringResource(R.string.shell_login_zeroconf_body),
        onCancel = onCancel,
    ) {
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                stringResource(R.string.shell_login_zeroconf_step1),
                stringResource(R.string.shell_login_zeroconf_step2),
                stringResource(R.string.shell_login_zeroconf_step3, deviceName),
            ).forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(colors.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = colors.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConnectingCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.shell_login_connecting), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PremiumNote() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Rounded.WorkspacePremium,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.shell_login_premium_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.shell_login_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
