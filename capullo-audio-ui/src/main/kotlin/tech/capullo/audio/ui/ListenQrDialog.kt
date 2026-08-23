package tech.capullo.audio.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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

// Single floating QR dialog for the web-player address. With a public link available it is
// segmented: "On this network" (LAN addresses, chip row toggles interfaces) vs "Public link"
// (zero-install tunnel URL). With no public link it renders the plain LAN dialog; with no LAN
// address at all it shows the "check Wi-Fi/hotspot/VPN" note instead.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListenQrDialog(
    ips: List<LocalIp>,
    httpPort: Int,
    initial: LocalIp? = null,
    publicLink: PublicLinkState = PublicLinkState.Off,
    onDismiss: () -> Unit,
) {
    if (ips.isEmpty() && publicLink is PublicLinkState.Off) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No network detected") },
            text = { Text(NO_IP_NOTE) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    val hasPublic = publicLink !is PublicLinkState.Off
    // The sheet's QR icon is a local-sharing gesture, so LAN stays the default unless there is none.
    var publicSelected by remember { mutableStateOf(ips.isEmpty() && hasPublic) }
    var selected by remember(ips) { mutableStateOf(initial?.takeIf { it in ips } ?: ips.firstOrNull()) }
    val lanUrl = selected?.let { "http://${it.address}:$httpPort" }

    val title = when {
        hasPublic && publicSelected -> "Listen anywhere"
        selected != null -> "Listen via ${selected!!.label}"
        else -> "Listen"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (hasPublic) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !publicSelected,
                            onClick = { publicSelected = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("On this network") }
                        SegmentedButton(
                            selected = publicSelected,
                            onClick = { publicSelected = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Public link") }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    hasPublic && publicSelected -> PublicLinkContent(publicLink)
                    selected != null -> LanContent(
                        ips = ips,
                        selected = selected!!,
                        onSelect = { selected = it },
                        url = lanUrl!!,
                    )
                    else -> Text(
                        NO_IP_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // Public renders its own copy/share icon row in-content; LAN keeps the Share text button.
            if (!(hasPublic && publicSelected) && lanUrl != null) {
                val context = LocalContext.current
                TextButton(onClick = { shareText(context, lanUrl) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanContent(
    ips: List<LocalIp>,
    selected: LocalIp,
    onSelect: (LocalIp) -> Unit,
    url: String,
) {
    if (ips.size > 1) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ips.forEach { ip ->
                FilterChip(
                    selected = ip == selected,
                    onClick = { onSelect(ip) },
                    label = { Text(ip.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
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
        "Scan with the other device's camera to open the web player - " +
            "pick the network the other device is on.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PublicLinkContent(publicLink: PublicLinkState) {
    when (publicLink) {
        is PublicLinkState.Active -> {
            val url = publicLink.url
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
            Text(
                "Anyone with the link can listen - no app needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
        PublicLinkState.Starting -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Establishing tunnel…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PublicLinkState.Error -> Text(
            "Tunnel error: ${publicLink.message}. Retrying automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        // The segmented control is only shown when the link is not Off - unreachable.
        PublicLinkState.Off -> Unit
    }
}
