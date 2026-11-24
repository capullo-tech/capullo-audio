package tech.capullo.audio.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.capullo.audio.snapcast.JoinUrl

data class LocalIp(val label: String, val address: String)

// Snapshot of the broadcaster's public (tunnel) link, mapped by the app from its own tunnel
// state machine - the library stays agnostic of how the tunnel is implemented.
sealed interface PublicLinkState {
    data object Off : PublicLinkState
    data object Starting : PublicLinkState
    data class Active(val url: String) : PublicLinkState
    data class Error(val message: String) : PublicLinkState
}

const val NO_IP_NOTE =
    "No network address found. Make sure Wi-Fi or the hotspot is turned on, " +
        "and switch any VPN off - some VPNs hide the local network."

// Only interfaces a listener could realistically reach: Wi-Fi, hotspot,
// Ethernet, VPN (Tailscale etc.). Cellular (rmnet), loopback and link-local
// addresses are useless for LAN clients and omitted.
fun usefulLocalIps(): List<LocalIp> {
    val out = mutableListOf<LocalIp>()
    try {
        for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val label = when {
                name.startsWith("swlan") || name.startsWith("ap") -> "Hotspot"
                name.startsWith("wlan") -> "Wi-Fi"
                name.startsWith("eth") -> "Ethernet"
                name.startsWith("tun") || name.startsWith("tailscale") ||
                    name.startsWith("wg") || name.startsWith("ppp") -> "VPN"
                else -> continue
            }
            for (addr in nif.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    out += LocalIp(label, addr.hostAddress ?: continue)
                }
            }
        }
    } catch (_: Exception) {}
    return out.sortedBy { it.label }
}

internal fun qrBitmap(content: String, size: Int = 512): android.graphics.Bitmap? = try {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content,
        com.google.zxing.BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)
} catch (e: Exception) {
    null
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share listening address"))
}

// The tunnel's base URL (https://<host>) as its join URL; an unparsable URL is shown as-is.
private fun publicJoinUrl(base: String): String = try {
    val u = java.net.URI(base)
    val https = u.scheme.equals("https", ignoreCase = true)
    JoinUrl.build(
        u.host,
        if (u.port != -1) {
            u.port
        } else if (https) {
            443
        } else {
            80
        },
        https,
    )
} catch (e: Exception) {
    base
}

// Single floating QR dialog for the web-player address. One chip row picks how the listener
// reaches this device: a chip per local network (Wi-Fi, hotspot, Ethernet, VPN) and, when the
// app can open one, "Cloudflare" for the zero-install public link. Picking Cloudflare is what
// turns the link on, and it stays on while another chip shows its QR, until "Turn off". With
// nothing to offer at all it shows the "check Wi-Fi/hotspot/VPN" note.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenQrDialog(
    ips: List<LocalIp>,
    httpPort: Int,
    initial: LocalIp? = null,
    publicLink: PublicLinkState = PublicLinkState.Off,
    // Turns the public link on (true) or off (false); called again for a link that is already
    // on. Null: the app has no tunnel, or this device is not broadcasting right now. The
    // Cloudflare chip is then offered only for a link that is on, without its "Turn off".
    onSetPublicLink: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val linkOn = publicLink !is PublicLinkState.Off
    // A link that is on always has its chip: it must never be exposed and missing from here.
    val offerCloudflare = onSetPublicLink != null || linkOn
    if (ips.isEmpty() && !offerCloudflare) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No network detected") },
            text = { Text(NO_IP_NOTE) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    var selected by remember(ips) { mutableStateOf(initial?.takeIf { it in ips } ?: ips.firstOrNull()) }
    // A link that is already on opens on its own chip: sharing it again or turning it off is what
    // the broadcaster most likely came back for.
    var cloudflarePicked by remember { mutableStateOf(linkOn) }
    // The link went off under the dialog (broadcast ended, turned off elsewhere): back to the
    // local networks rather than a pane that waits for a link nobody is starting.
    LaunchedEffect(linkOn) { if (!linkOn) cloudflarePicked = false }
    val showCloudflare = offerCloudflare && cloudflarePicked
    val lan = selected.takeIf { !showCloudflare }
    val lanUrl = lan?.let { JoinUrl.build(it.address, httpPort) }

    // One name per chip, the same in the title: a second name for the public link ("Listen
    // anywhere" next to a "Public link" setting) read as two different features.
    val title = when {
        showCloudflare -> "Listen via Cloudflare"
        lan != null -> "Listen via ${lan.label}"
        else -> "Listen"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (offerCloudflare || ips.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ips.forEach { ip ->
                            FilterChip(
                                selected = ip == lan,
                                onClick = {
                                    selected = ip
                                    cloudflarePicked = false
                                },
                                label = { Text(ip.label) },
                            )
                        }
                        if (offerCloudflare) {
                            FilterChip(
                                selected = showCloudflare,
                                onClick = {
                                    cloudflarePicked = true
                                    onSetPublicLink?.invoke(true)
                                },
                                label = { Text("Cloudflare") },
                                // Still up while another chip shows its QR - keep that visible.
                                leadingIcon = if (linkOn) {
                                    {
                                        Icon(
                                            Icons.Default.Cloud,
                                            contentDescription = "Public link is on",
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    showCloudflare -> PublicLinkContent(publicLink)
                    lanUrl != null -> LanContent(lanUrl)
                    else -> Text(
                        NO_IP_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // Cloudflare renders its own copy/share icon row in-content; LAN keeps the Share text button.
            when {
                showCloudflare -> if (onSetPublicLink != null) {
                    TextButton(
                        onClick = {
                            onSetPublicLink(false)
                            cloudflarePicked = false
                        },
                    ) { Text("Turn off") }
                }
                lanUrl != null -> {
                    val context = LocalContext.current
                    TextButton(onClick = { shareText(context, lanUrl) }) { Text("Share") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LanContent(url: String) {
    qrBitmap(url)?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = url,
            modifier = Modifier
                .size(240.dp)
                .background(Color.White)
                .padding(8.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    Text(
        "Scan with the other device's camera to open the web player, or a capullo app if " +
            "installed - pick the network the other device is on.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PublicLinkContent(publicLink: PublicLinkState) {
    when (publicLink) {
        is PublicLinkState.Active -> {
            val url = publicJoinUrl(publicLink.url)
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            qrBitmap(url)?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR code for $url",
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White)
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            PublicLinkWarning()
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy link")
                }
                IconButton(onClick = { shareText(context, url) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share link")
                }
            }
        }
        // Off: the chip was just picked and the app has not reported the start yet.
        PublicLinkState.Off, PublicLinkState.Starting -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Establishing tunnel…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PublicLinkWarning()
        }
        is PublicLinkState.Error -> {
            Text(
                "Tunnel error: ${publicLink.message}. Retrying automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            PublicLinkWarning()
        }
    }
}

// In every state of the pane, so it is on screen from the moment the chip is picked, seconds
// before the link exists and with "Turn off" right below: no settings switch carries it any more.
@Composable
private fun PublicLinkWarning() {
    Text(
        "Anyone with the link can listen and control - no app needed. " +
            "About 1.4 Mbps of your upload per listener.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
