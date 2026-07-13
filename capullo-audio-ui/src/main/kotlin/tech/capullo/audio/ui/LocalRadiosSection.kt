package tech.capullo.audio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.capullo.audio.snapcast.DiscoveredSnapserver

// Shared "scanning for local radios" section, consumed by every capullo app's station-picker home
// (QuantumCast's HomeScreen, Telecloud's GroupSelectorScreen). A contrasting surface with an
// always-scanning radar-sweep row, the discovered local snapcast servers listed below it, and a
// tap-to-expand manual host:port field. Tapping a server (or submitting a manual address) joins it
// as a listener (snapclient).
//
// Ports are DYNAMIC: broadcasters bind OS-assigned ephemeral ports (ServerSocket(0)) and advertise
// them over NSD, so a discovered server is always joined on its advertised [DiscoveredSnapserver.port]
// / [DiscoveredSnapserver.httpPort]. [fallbackStreamPort]/[fallbackHttpPort] are ONLY the manual-entry
// fallback - the stream port assumed when a user types a bare host with no ":port", plus a best-guess
// http/control port the remote may not actually use. Against a real dynamic-port broadcaster a bare
// host won't match, so manual entry realistically needs `host:port` typed; the fallback only helps a
// legacy fixed-port server. Each app supplies its own fallback (QC 1604/1680, TC 1804/1880).
@Composable
fun LocalRadiosSection(
    servers: List<DiscoveredSnapserver>,
    onJoinServer: (DiscoveredSnapserver) -> Unit,
    onJoinManual: (host: String, port: Int, httpPort: Int) -> Unit,
    fallbackStreamPort: Int,
    fallbackHttpPort: Int,
    initialManualHost: String = "",
    onClearManualHost: () -> Unit = {},
) {
    var manualExpanded by remember { mutableStateOf(false) }
    var manualInput by remember(initialManualHost) { mutableStateOf(initialManualHost) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // Scanning row - tap to reveal/hide the manual connect field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { manualExpanded = !manualExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadarSweep(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Scanning for local radios…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Tap to enter an address manually",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (manualExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (manualExpanded) "Hide manual connect" else "Show manual connect",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = manualExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fun submit() {
                        val input = manualInput.trim()
                        if (input.isEmpty()) return
                        val host = input.substringBefore(":")
                        // Manual entry can't know the remote's dynamic ports; fall back to the
                        // app's convention (stream from host:port if typed, http a best guess).
                        val port = input.substringAfter(":", "").toIntOrNull() ?: fallbackStreamPort
                        onJoinManual(host, port, fallbackHttpPort)
                    }
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Host or host:port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        trailingIcon = if (manualInput.isNotEmpty()) {
                            {
                                IconButton(onClick = {
                                    manualInput = ""
                                    onClearManualHost()
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        } else {
                            null
                        },
                    )
                    Button(onClick = { submit() }, enabled = manualInput.isNotBlank()) { Text("Join") }
                }
            }

            // Discovered servers - same row style as the scanning row
            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJoinServer(server) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.DeviceHub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            server.serviceName.ifBlank { server.hostAddress },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${server.hostAddress}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

// Slow radar sweep - a rotating wedge with a fading trail over faint range rings.
// Deliberately unhurried (~3.5s/rev) so it reads as "scanning the airwaves",
// not a busy loading spinner.
@Composable
private fun RadarSweep(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        // Range rings
        drawCircle(color = accent.copy(alpha = 0.25f), radius = r, style = Stroke(width = r * 0.06f))
        drawCircle(color = accent.copy(alpha = 0.18f), radius = r * 0.62f, style = Stroke(width = r * 0.05f))
        drawCircle(color = accent.copy(alpha = 0.5f), radius = r * 0.08f) // hub
        // Fading trail wedge: sweep gradient from transparent to accent, rotated
        rotate(degrees = angle, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to accent.copy(alpha = 0.45f),
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = true,
            )
            // Bright leading line
            drawLine(
                color = accent,
                start = center,
                end = Offset(center.x + r, center.y),
                strokeWidth = r * 0.06f,
            )
        }
    }
}
