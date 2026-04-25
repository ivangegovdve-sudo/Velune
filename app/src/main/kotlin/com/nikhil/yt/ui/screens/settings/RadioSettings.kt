/*
 * Velune Web Radio — RadioSettings
 *
 * Material You settings screen for configuring and controlling the Icecast
 * web-radio stream. Designed for a11y, scannability, and quick listener-link
 * sharing.
 *
 *   ┌──────────────────────────────────┐
 *   │  STATUS HERO  · live indicator   │
 *   │  Title — Artist  ·  uptime       │
 *   ├──────────────────────────────────┤
 *   │  LISTENER URL  · copy share QR   │
 *   ├──────────────────────────────────┤
 *   │  SERVER  ▸ host port mount pwd   │
 *   ├──────────────────────────────────┤
 *   │  QUALITY  · bitrate presets      │
 *   ├──────────────────────────────────┤
 *   │  START / STOP                    │
 *   └──────────────────────────────────┘
 */

package com.nikhil.yt.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.radio.ACTION_STOP
import com.nikhil.yt.radio.EXTRA_BITRATE
import com.nikhil.yt.radio.EXTRA_HOST
import com.nikhil.yt.radio.EXTRA_MOUNT
import com.nikhil.yt.radio.EXTRA_PASSWORD
import com.nikhil.yt.radio.EXTRA_PORT
import com.nikhil.yt.radio.RadioBitrateKey
import com.nikhil.yt.radio.RadioBridge
import com.nikhil.yt.radio.RadioDefaults
import com.nikhil.yt.radio.RadioHostKey
import com.nikhil.yt.radio.RadioMountKey
import com.nikhil.yt.radio.RadioPasswordKey
import com.nikhil.yt.radio.RadioPortKey
import com.nikhil.yt.radio.RadioStreamingService
import com.nikhil.yt.radio.qr.QrCodeView
import com.nikhil.yt.radio.qr.QrEcc
import com.nikhil.yt.ui.component.IconButton as AppIconButton
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberPreference

/* -------------------------------------------------------------------------- */
/*  Bitrate presets                                                           */
/* -------------------------------------------------------------------------- */

private val BITRATE_PRESETS = listOf(64, 96, 128, 192, 256)

/**
 * Public listener page hosted on GitHub Pages (assets/webradio/index.html
 * served from the repo root). Replace the host below if you fork or
 * move the page — the listener URL itself is built by inserting %s/%d
 * for host/port/mount via String.format.
 */
private const val WEB_LISTENER_TEMPLATE =
    "https://ivangegovdve-sudo.github.io/Velune/assets/webradio/?host=%s&port=%d&mount=%s"

/* -------------------------------------------------------------------------- */
/*  Screen                                                                    */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current

    // ---------- Persisted preferences ------------------------------------------------
    var host by rememberPreference(RadioHostKey, RadioDefaults.HOST)
    var port by rememberPreference(RadioPortKey, RadioDefaults.PORT)
    var mount by rememberPreference(RadioMountKey, RadioDefaults.MOUNT)
    var password by rememberPreference(RadioPasswordKey, RadioDefaults.PASSWORD)
    var bitrate by rememberPreference(RadioBitrateKey, RadioDefaults.BITRATE)

    // ---------- Local UI state -------------------------------------------------------
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }
    var mountError by remember { mutableStateOf<String?>(null) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showQrSheet by remember { mutableStateOf(false) }

    val state by RadioBridge.state.collectAsState()

    // Validation runs whenever the field changes
    LaunchedEffect(host) { hostError = validateHost(host) }
    LaunchedEffect(port) { portError = validatePort(port) }
    LaunchedEffect(mount) { mountError = validateMount(mount) }

    val mountNormalized = remember(mount) { if (mount.startsWith("/")) mount else "/$mount" }
    val listenerUrl = remember(host, port, mountNormalized) {
        if (host.isBlank()) "" else "http://${host.trim()}:$port$mountNormalized"
    }
    val webListenerUrl = remember(host, port, mountNormalized) {
        if (host.isBlank()) "" else WEB_LISTENER_TEMPLATE.format(
            Uri.encode(host.trim()), port, Uri.encode(mountNormalized)
        )
    }

    val canStart = host.isNotBlank() &&
        hostError == null && portError == null && mountError == null

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("Web Radio") },
            navigationIcon = {
                AppIconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = "Back")
                }
            },
            scrollBehavior = scrollBehavior,
        )

        // ---------- Status hero ------------------------------------------------------
        StatusHero(state, listenerUrl)

        // ---------- Listener URL card ------------------------------------------------
        ListenerLinkCard(
            listenerUrl = listenerUrl,
            webListenerUrl = webListenerUrl,
            onCopy = { copy(context, "Velune listener URL", listenerUrl) },
            onShare = { share(context, listenerUrl) },
            onOpen = { openUrl(context, listenerUrl) },
            onShowQr = { showQrSheet = true },
            enabled = listenerUrl.isNotBlank(),
        )

        // ---------- Server settings --------------------------------------------------
        PreferenceGroupTitle(title = "Icecast server")

        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("Host") },
            placeholder = { Text("radio.example.com") },
            supportingText = {
                Text(hostError ?: "Domain or IP of your Icecast server")
            },
            isError = hostError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { contentDescription = "Server host" },
        )

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it.filter(Char::isDigit).take(5)
                    portText.toIntOrNull()?.let { v -> port = v }
                },
                label = { Text("Port") },
                supportingText = { Text(portError ?: "1–65535") },
                isError = portError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = mount,
                onValueChange = { mount = it },
                label = { Text("Mount") },
                placeholder = { Text("/velune") },
                supportingText = { Text(mountError ?: "Path on the server") },
                isError = mountError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Source password") },
            supportingText = { Text("Matches the source password in your Icecast config") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(
                    onClick = { showPassword = !showPassword },
                    modifier = Modifier.semantics {
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    },
                ) {
                    Icon(
                        painter = painterResource(
                            if (showPassword) R.drawable.visibility_off else R.drawable.visibility,
                        ),
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // ---------- Quality ----------------------------------------------------------
        PreferenceGroupTitle(title = "Audio quality")

        BitratePresets(
            currentKbps = bitrate / 1000,
            onSelect = { bitrate = it * 1000 },
        )

        Text(
            text = "AAC-LC · ${bitrate / 1000} kbps · stereo passthrough\n" +
                "Higher bitrate = better fidelity, more bandwidth.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // ---------- Stream control ---------------------------------------------------
        Spacer(Modifier.height(8.dp))
        StreamControlButton(
            state = state,
            canStart = canStart,
            onStart = {
                Intent(context, RadioStreamingService::class.java).apply {
                    putExtra(EXTRA_HOST, host.trim())
                    putExtra(EXTRA_PORT, port)
                    putExtra(EXTRA_MOUNT, mountNormalized)
                    putExtra(EXTRA_PASSWORD, password)
                    putExtra(EXTRA_BITRATE, bitrate)
                }.also(context::startForegroundService)
            },
            onStop = {
                Intent(context, RadioStreamingService::class.java)
                    .setAction(ACTION_STOP)
                    .also(context::startService)
            },
        )

        // ---------- Help -------------------------------------------------------------
        PreferenceGroupTitle(title = "How listeners tune in")
        HelpCard()

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ---------- Bottom sheet: QR code ------------------------------------------------
    if (showQrSheet && listenerUrl.isNotBlank()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQrSheet = false },
            sheetState = sheetState,
        ) {
            QrShareSheet(
                listenerUrl = listenerUrl,
                webListenerUrl = webListenerUrl,
                onCopyDirect = { copy(context, "Velune listener URL", listenerUrl) },
                onCopyWeb = { copy(context, "Velune web player URL", webListenerUrl) },
                onShare = { share(context, listenerUrl) },
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Status hero                                                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun StatusHero(
    state: RadioBridge.State,
    listenerUrl: String,
) {
    val tint = when (state.status) {
        RadioBridge.Status.Streaming -> MaterialTheme.colorScheme.primary
        RadioBridge.Status.Connecting -> MaterialTheme.colorScheme.tertiary
        RadioBridge.Status.Error -> MaterialTheme.colorScheme.error
        RadioBridge.Status.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container = when (state.status) {
        RadioBridge.Status.Streaming -> MaterialTheme.colorScheme.primaryContainer
        RadioBridge.Status.Connecting -> MaterialTheme.colorScheme.tertiaryContainer
        RadioBridge.Status.Error -> MaterialTheme.colorScheme.errorContainer
        RadioBridge.Status.Idle -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (state.status) {
        RadioBridge.Status.Streaming -> MaterialTheme.colorScheme.onPrimaryContainer
        RadioBridge.Status.Connecting -> MaterialTheme.colorScheme.onTertiaryContainer
        RadioBridge.Status.Error -> MaterialTheme.colorScheme.onErrorContainer
        RadioBridge.Status.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(state.status, tint)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(state.status).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer,
                    modifier = Modifier.semantics {
                        contentDescription = "Status: ${statusLabel(state.status)}"
                    },
                )
                Spacer(Modifier.weight(1f))
                if (state.status == RadioBridge.Status.Streaming) {
                    Text(
                        text = uptime(state.startedAtMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
            )
            if (state.track.title.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                NowPlayingRow(state.track, onContainer)
            } else if (state.status == RadioBridge.Status.Idle) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Start playback in Velune, then begin streaming. " +
                        "Listeners can tune in from any device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
            if (state.status == RadioBridge.Status.Streaming && state.bytesSent > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${humanBytes(state.bytesSent)} sent · $listenerUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun LiveDot(status: RadioBridge.Status, tint: Color) {
    val animating = status == RadioBridge.Status.Streaming || status == RadioBridge.Status.Connecting
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (animating) alpha else 1f)),
    )
}

@Composable
private fun NowPlayingRow(track: RadioBridge.State.Track, on: Color) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = on,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = on,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = on.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
        track.videoId?.takeIf { it.isNotBlank() }?.let { vid ->
            TextButton(
                onClick = { openUrl(context, "https://www.youtube.com/watch?v=$vid") },
                modifier = Modifier.semantics {
                    contentDescription = "Open ${track.title} on YouTube"
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.link),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("YouTube")
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Listener link card                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun ListenerLinkCard(
    listenerUrl: String,
    webListenerUrl: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onShowQr: () -> Unit,
    enabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LISTENER LINK",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (enabled) listenerUrl
                       else "Set a host to generate the listener link.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Copy", R.drawable.copy, enabled, onCopy)
                ActionChip("Share", R.drawable.share, enabled, onShare)
                ActionChip("Open", R.drawable.link, enabled, onOpen)
                ActionChip("QR", R.drawable.qr_code_2, enabled, onShowQr)
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = Modifier.semantics { contentDescription = "$label listener link" },
    )
}

/* -------------------------------------------------------------------------- */
/*  Bitrate presets                                                           */
/* -------------------------------------------------------------------------- */

@Composable
private fun BitratePresets(currentKbps: Int, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        BITRATE_PRESETS.forEach { kbps ->
            FilterChip(
                selected = kbps == currentKbps,
                onClick = { onSelect(kbps) },
                label = { Text("${kbps}k") },
                modifier = Modifier.semantics {
                    contentDescription = "$kbps kilobits per second"
                },
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Stream control                                                            */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamControlButton(
    state: RadioBridge.State,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val active = state.status == RadioBridge.Status.Streaming ||
                 state.status == RadioBridge.Status.Connecting
    if (!active) {
        Button(
            onClick = onStart,
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(56.dp)
                .semantics { contentDescription = "Start streaming" },
        ) {
            Icon(painterResource(R.drawable.graphic_eq), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start streaming", fontSize = 16.sp)
        }
    } else {
        OutlinedButton(
            onClick = onStop,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(56.dp)
                .semantics { contentDescription = "Stop streaming" },
        ) {
            Icon(painterResource(R.drawable.close), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stop streaming", fontSize = 16.sp)
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Help                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun HelpCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            HelpRow("1.", "Deploy AzuraCast (Docker) or Icecast 2 on a VPS.")
            HelpRow("2.", "Set the source password above to match your server config.")
            HelpRow("3.", "Hit Start streaming. Velune captures playback and pushes it to your mount point.")
            HelpRow("4.", "Anyone with the listener link can tune in from a browser, VLC, or any media player. Share via QR or link.")
        }
    }
}

@Composable
private fun HelpRow(num: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = num,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  QR share sheet                                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun QrShareSheet(
    listenerUrl: String,
    webListenerUrl: String,
    onCopyDirect: () -> Unit,
    onCopyWeb: () -> Unit,
    onShare: () -> Unit,
) {
    var mode by remember { mutableStateOf(ShareMode.Web) }
    val activeUrl = when (mode) {
        ShareMode.Web -> webListenerUrl
        ShareMode.Direct -> listenerUrl
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Share your station",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Listeners scan this with their camera and start hearing your stream — no app required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ShareMode.Web,
                onClick = { mode = ShareMode.Web },
                label = { Text("Web player") },
            )
            FilterChip(
                selected = mode == ShareMode.Direct,
                onClick = { mode = ShareMode.Direct },
                label = { Text("Direct stream") },
            )
        }
        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.padding(8.dp)) {
            QrCodeView(
                text = activeUrl,
                sizeDp = 240.dp,
                ecc = QrEcc.Medium,
                foreground = MaterialTheme.colorScheme.onSurface,
                background = MaterialTheme.colorScheme.surface,
                contentDesc = "Scannable QR code for ${if (mode == ShareMode.Web) "web player" else "direct stream"} URL",
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = activeUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(visible = mode == ShareMode.Web) {
            Text(
                text = "Web player: opens in any browser, includes now-playing & YouTube link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(visible = mode == ShareMode.Direct) {
            Text(
                text = "Direct stream: works in VLC, Apple Music, browser <audio>, foobar2000, etc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = if (mode == ShareMode.Web) onCopyWeb else onCopyDirect,
            ) {
                Icon(painterResource(R.drawable.copy), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy link")
            }
            Button(onClick = onShare) {
                Icon(painterResource(R.drawable.share), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private enum class ShareMode { Web, Direct }

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                   */
/* -------------------------------------------------------------------------- */

private fun statusLabel(s: RadioBridge.Status): String = when (s) {
    RadioBridge.Status.Idle -> "Idle"
    RadioBridge.Status.Connecting -> "Connecting"
    RadioBridge.Status.Streaming -> "Live"
    RadioBridge.Status.Error -> "Error"
}

private fun uptime(startedAtMs: Long): String {
    if (startedAtMs == 0L) return ""
    val secs = (System.currentTimeMillis() - startedAtMs) / 1000
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun validateHost(host: String): String? = when {
    host.isBlank() -> null // empty is allowed; we just disable Start
    host.contains(' ') || host.contains('/') -> "Hostname only — no scheme or path"
    host.length > 253 -> "Too long"
    else -> null
}

private fun validatePort(port: Int): String? = when {
    port !in 1..65535 -> "Port must be 1–65535"
    else -> null
}

private fun validateMount(mount: String): String? = when {
    mount.isBlank() -> "Mount required (e.g. /velune)"
    mount.contains(' ') -> "No spaces"
    else -> null
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Velune Web Radio")
    }
    context.startActivity(Intent.createChooser(intent, "Share listener link"))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "No app can open this URL", Toast.LENGTH_SHORT).show()
    }
}
