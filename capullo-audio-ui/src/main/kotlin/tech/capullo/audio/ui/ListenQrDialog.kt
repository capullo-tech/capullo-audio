package tech.capullo.audio.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class LocalIp(val label: String, val address: String)

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

// Single floating QR dialog for the web-player address. When several
// interfaces have an address, a chip row toggles between them; with no
// address at all it shows the "check Wi-Fi/hotspot/VPN" note instead.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListenQrDialog(
    ips: List<LocalIp>,
    httpPort: Int,
    initial: LocalIp? = null,
    onDismiss: () -> Unit,
) {
    if (ips.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No network detected") },
            text = { Text(NO_IP_NOTE) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }

    val context = LocalContext.current
    var selected by remember(ips) { mutableStateOf(initial?.takeIf { it in ips } ?: ips.first()) }
    val url = "http://${selected.address}:$httpPort"
    val qr = remember(url) { qrBitmap(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen via ${selected.label}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (ips.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ips.forEach { ip ->
                            FilterChip(
                                selected = ip == selected,
                                onClick = { selected = ip },
                                label = { Text(ip.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                qr?.let {
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
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, "Share listening address"))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
