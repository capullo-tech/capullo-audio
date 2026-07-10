package tech.capullo.audio.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.capullo.audio.snapcast.Group
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

private enum class KnobMode { LATENCY, VOLUME }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SnapcastControlSheet(
    groups: List<Group>,
    snapclientChannel: String = "stereo",
    onClientVolumeChange: (clientId: String, muted: Boolean, percent: Int) -> Unit,
    onClientLatencyChange: (clientId: String, latencyMs: Int) -> Unit = { _, _ -> },
    onSetChannel: (String) -> Unit = {},
    onChangeClientChannel: (clientId: String, channel: String) -> Unit = { _, _ -> },
    isBroadcaster: Boolean = false,
    isStreamLocked: Boolean = false,
    onToggleStreamLock: () -> Unit = {},
    // This device's own snapclient id. When the stream is locked and this device is NOT the
    // broadcaster, every card except this one is read-only (a non-server client may only touch
    // its own row). Empty string ⇒ unknown ⇒ nothing is treated as "self".
    localClientId: String = "",
    // Broadcaster-only reset. Short press resets THIS device (own snapclient → stereo/100/0 and
    // clears its persisted spatial role); long press resets EVERY connected client.
    onResetSelf: () -> Unit = {},
    onResetAll: () -> Unit = {},
    httpPort: Int = 1680,
    onDismiss: () -> Unit,
) {
    val connectedClients = remember(groups) {
        groups.flatMap { it.clients }.filter { it.connected }
    }
    val sheetState = rememberModalBottomSheetState()
    // When a 3rd client connects (second row), expand the sheet so both rows are
    // immediately visible without the user needing to drag up manually.
    // Never auto-collapses - user can drag down, but new arrivals won't shrink it.
    LaunchedEffect(connectedClients.size) {
        if (connectedClients.size > 2) sheetState.expand()
    }

    var showQr by remember { mutableStateOf(false) }
    if (showQr) {
        val ips = remember { usefulLocalIps() }
        ListenQrDialog(ips = ips, httpPort = httpPort, onDismiss = { showQr = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Darken the backdrop to match the web player's snapcontrol overlay
        // (`.mbg { background: rgba(0,0,0,.65) }`). The M3 default scrim is much
        // lighter; the callers additionally blur their own content behind the sheet
        // (web `backdrop-filter: blur`), which the sheet's own window can't reach.
        scrimColor = Color.Black.copy(alpha = 0.6f),
        // Compact handle: the default M3 one pads 22dp top+bottom, pushing the
        // QR/lock row far down (web parity: slim grab bar close to content)
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        // Reset (broadcaster only, left) · QR share for everyone + stream lock (broadcaster
        // only) on the right. Channel is set per-card via the channel badge - the L/Stereo/R
        // segment was removed. SpaceBetween keeps the QR/lock group right-aligned whether or
        // not the left reset slot is present.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isBroadcaster) {
                // Short press = reset this device; long press = reset all clients. An IconButton
                // can't distinguish long-press, so use a combinedClickable Box sized like one.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .combinedClickable(onClick = onResetSelf, onLongClick = onResetAll),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsBackupRestore,
                        contentDescription = "Reset this device (long-press: reset all)",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Keep SpaceBetween pushing the QR/lock group to the right edge.
                Spacer(Modifier.size(1.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showQr = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = "Show listening address QR code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isBroadcaster) {
                    IconButton(onClick = onToggleStreamLock, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (isStreamLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isStreamLocked) "Unlock stream control" else "Lock stream control",
                            tint = if (isStreamLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Group volume slider
        val serverAvg = if (connectedClients.isEmpty()) {
            1f
        } else {
            connectedClients.map { it.config.volume.percent }.average().toFloat() / 100f
        }
        var groupVolume by remember { mutableFloatStateOf(serverAvg) }
        var isDragging by remember { mutableStateOf(false) }
        // Snapshot of each client's volume at the moment the drag started
        var dragStartGroupVol by remember { mutableFloatStateOf(serverAvg) }
        var dragStartClientVols by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
        // Mirror server state when not dragging; brief settle delay after release lets all
        // ClientOnVolumeChanged echoes arrive before the slider re-syncs
        LaunchedEffect(serverAvg) {
            if (!isDragging) groupVolume = serverAvg
        }
        val sliderScope = rememberCoroutineScope()
        var debounceJob by remember { mutableStateOf<Job?>(null) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        var dragClientVols by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var lastGrpSentMs by remember { mutableStateOf(0L) }

        // Slim bar like the web player's group slider (thin track, small round
        // thumb) instead of the tall M3 pill style. Disabled for a locked-out
        // non-broadcaster - the group slider drives EVERY client, so leaving it live
        // would bypass the per-card lock gating (parity with the web group slider).
        val sliderEnabled = connectedClients.isNotEmpty() && !(isStreamLocked && !isBroadcaster)
        val sliderColor = (
            if (!isBroadcaster) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            )
            .copy(alpha = if (sliderEnabled) 1f else 0.4f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.VolumeDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = groupVolume,
                onValueChange = { v ->
                    settleJob?.cancel()
                    if (!isDragging) {
                        isDragging = true
                        dragStartGroupVol = serverAvg
                        dragStartClientVols = connectedClients.associate {
                            it.id to it.config.volume.percent.toFloat() / 100f
                        }
                    }
                    groupVolume = v
                    // Proportional scaling from drag-start snapshot
                    val startGroup = dragStartGroupVol
                    // Compute new per-client volumes synchronously so cards update immediately
                    dragClientVols = connectedClients.associate { client ->
                        val startVol = dragStartClientVols[client.id] ?: v
                        val newVol = when {
                            startGroup <= 0f -> v
                            v <= 0f -> 0f
                            v >= 1f -> 1f
                            v < startGroup -> startVol * (v / startGroup)
                            else -> startVol + (1f - startVol) * ((v - startGroup) / (1f - startGroup))
                        }
                        client.id to round(newVol.coerceIn(0f, 1f) * 100f).toInt()
                    }
                    // THROTTLE the server send (not debounce): emit every ~80ms
                    // DURING the drag so other clients (web + app) track live.
                    // A debounce only fired after the drag stopped, so remote
                    // cards/sliders jumped to the final value on release.
                    val sendVols = {
                        val vols = dragClientVols
                        connectedClients.forEach { client ->
                            val pct = vols[client.id] ?: return@forEach
                            onClientVolumeChange(client.id, client.config.volume.muted, pct)
                        }
                    }
                    val now = System.currentTimeMillis()
                    debounceJob?.cancel()
                    if (now - lastGrpSentMs >= 80) {
                        lastGrpSentMs = now
                        sendVols()
                    } else {
                        debounceJob = sliderScope.launch {
                            delay(80 - (now - lastGrpSentMs))
                            lastGrpSentMs = System.currentTimeMillis()
                            sendVols()
                        }
                    }
                },
                onValueChangeFinished = {
                    debounceJob?.cancel()
                    // Send final proportional values immediately on release
                    val startGroup = dragStartGroupVol
                    val finalVol = groupVolume
                    connectedClients.forEach { client ->
                        val startVol = dragStartClientVols[client.id] ?: finalVol
                        val newVol = when {
                            startGroup <= 0f -> finalVol
                            finalVol <= 0f -> 0f
                            finalVol >= 1f -> 1f
                            finalVol < startGroup -> startVol * (finalVol / startGroup)
                            else -> startVol + (1f - startVol) * ((finalVol - startGroup) / (1f - startGroup))
                        }
                        onClientVolumeChange(
                            client.id,
                            client.config.volume.muted,
                            round(newVol.coerceIn(0f, 1f) * 100f).toInt(),
                        )
                    }
                    // Wait for all server echoes before re-enabling server sync
                    settleJob = sliderScope.launch {
                        delay(300)
                        isDragging = false
                        dragClientVols = emptyMap()
                    }
                },
                enabled = sliderEnabled,
                modifier = Modifier.weight(1f).height(28.dp),
                thumb = {
                    Box(Modifier.size(14.dp).background(sliderColor, CircleShape))
                },
                track = { state ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(sliderColor.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(state.value.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(sliderColor),
                        )
                    }
                },
            )
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${round(groupVolume * 100f).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        if (groups.isEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Connecting to server…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (connectedClients.isEmpty()) {
            Text(
                "No devices connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                items(connectedClients, key = { it.id }) { client ->
                    val rawName = client.config.name.ifBlank { client.host.name.ifBlank { client.host.ip } }
                    val channelTag = Regex("\\s*\\[([LRS])\\]$").find(rawName)?.groupValues?.get(1)
                    val displayName = if (channelTag != null) {
                        rawName.replace(Regex("\\s*\\[[LRS]\\]$"), "").trim()
                            .ifBlank { client.host.name.ifBlank { client.host.ip } }
                    } else {
                        rawName
                    }
                    // Lock gating: when the stream is locked and this device is NOT the broadcaster,
                    // only this device's OWN row is editable (server stays fully editable via its own
                    // broadcaster sheet). Note this is COOPERATIVE - the real snapserver has no lock/ACL
                    // concept, so this UI gate is the enforcement for our own clients.
                    val isSelf = localClientId.isNotEmpty() && (
                        client.id == localClientId ||
                            client.id.contains(localClientId) ||
                            localClientId.contains(client.id)
                        )
                    val editable = !(isStreamLocked && !isBroadcaster && !isSelf)
                    ClientCard(
                        name = displayName,
                        channelTag = channelTag,
                        muted = client.config.volume.muted,
                        volume = (dragClientVols[client.id]?.toFloat() ?: client.config.volume.percent.toFloat()) / 100f,
                        latency = client.config.latency,
                        isSnapclient = !isBroadcaster,
                        editable = editable,
                        onVolumeChange = { percent ->
                            onClientVolumeChange(client.id, client.config.volume.muted, percent)
                        },
                        // Mute must preserve the volume the knob is CURRENTLY showing, not the
                        // server-state percent: while a drag value (dragClientVols) or an optimistic
                        // update is in flight, client.config.volume.percent can lag/diverge, so
                        // reading it here made mute snap the volume to the stale value. The card
                        // passes up its displayed percent instead (single source of truth).
                        onMutedToggle = { muted, percent ->
                            onClientVolumeChange(client.id, muted, percent)
                        },
                        onLatencyChange = { latencyMs ->
                            onClientLatencyChange(client.id, latencyMs)
                        },
                        onChannelCycle = if (channelTag != null && editable) {
                            {
                                val nextChannel = when (channelTag) {
                                    "S" -> "left"
                                    "L" -> "right"
                                    else -> "stereo"
                                }
                                onChangeClientChannel(client.id, nextChannel)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ClientCard(
    name: String,
    channelTag: String?,
    muted: Boolean,
    volume: Float,
    latency: Int,
    isSnapclient: Boolean = false,
    editable: Boolean = true,
    onVolumeChange: (Int) -> Unit,
    onMutedToggle: (Boolean, Int) -> Unit,
    onLatencyChange: (Int) -> Unit,
    onChannelCycle: (() -> Unit)? = null,
) {
    var mutedState by remember(muted) { mutableStateOf(muted) }
    var volumeState by remember(volume) { mutableFloatStateOf(volume) }
    var latencyState by remember(latency) { mutableStateOf(latency) }
    var knobMode by remember { mutableStateOf(KnobMode.LATENCY) }

    Box {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // Locked non-owner rows read dimmed and non-interactive.
                .then(if (editable) Modifier else Modifier.alpha(0.45f)),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 8.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val knobBaseSize = maxWidth * 0.75f
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 14.dp, end = 16.dp)
                            // long names scroll instead of truncating (web parity)
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        text = name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClientKnob(
                            mode = knobMode,
                            onModeToggle = { knobMode = if (knobMode == KnobMode.LATENCY) KnobMode.VOLUME else KnobMode.LATENCY },
                            latency = latencyState,
                            onLatencyChange = { v ->
                                latencyState = v
                                onLatencyChange(v)
                            },
                            volume = round(volumeState * 100f).toInt(),
                            onVolumeChange = { v ->
                                volumeState = v / 100f
                                onVolumeChange(v)
                            },
                            muted = mutedState,
                            onMutedToggle = {
                                mutedState = !mutedState
                                // Send the percent the knob is showing (volumeState), so toggling
                                // mute never changes the volume value.
                                onMutedToggle(mutedState, round(volumeState * 100f).toInt())
                            },
                            baseSize = knobBaseSize,
                            isSnapclient = isSnapclient,
                            interactive = editable,
                        )
                    }
                }
            }
        }

        if (channelTag != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(28.dp)
                    // Clip BEFORE clickable so the tap ripple is bounded to the circle
                    // (without this the indication draws as a square around the badge).
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .then(if (onChannelCycle != null) Modifier.clickable(onClick = onChannelCycle) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = channelTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ClientKnob(
    mode: KnobMode,
    onModeToggle: () -> Unit,
    latency: Int,
    onLatencyChange: (Int) -> Unit,
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    muted: Boolean,
    onMutedToggle: () -> Unit,
    baseSize: Dp = 124.dp,
    isSnapclient: Boolean = false,
    interactive: Boolean = true,
) {
    val minLatency = -500
    val maxLatency = 1000
    val degreesPerStep = 7f
    val minActiveRadiusRatio = 0.28f
    val tinyMotionDeadzoneDeg = 0.18f
    val maxEventDeltaDeg = 10f
    val smoothingPrevWeight = 0.35f
    val smoothingCurrentWeight = 0.65f
    val angularGain = 1.35f

    var isActive by remember { mutableStateOf(false) }
    var displayLatency by remember(latency) { mutableStateOf(latency) }
    var displayVolume by remember(volume) { mutableStateOf(volume) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var knobRadiusPx by remember { mutableStateOf(0f) }
    var prevSmoothedDelta by remember { mutableStateOf(0f) }
    var accDelta by remember { mutableStateOf(0f) }

    val knobSize by animateDpAsState(if (isActive) baseSize * 1.28f else baseSize, label = "knobSize")
    val indicatorScale by animateFloatAsState(if (isActive) 1.06f else 1f, label = "indScale")

    val knobFillColor = if (isSnapclient) {
        when {
            isActive && mode == KnobMode.LATENCY -> MaterialTheme.colorScheme.errorContainer
            isActive && mode == KnobMode.VOLUME -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.errorContainer
        }
    } else {
        when {
            isActive && mode == KnobMode.LATENCY -> MaterialTheme.colorScheme.primaryContainer
            isActive && mode == KnobMode.VOLUME -> MaterialTheme.colorScheme.tertiary
            mode == KnobMode.LATENCY -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer
        }
    }
    val knobEdgeColor = if (isSnapclient) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    val indicatorColor = if (isSnapclient) {
        when (mode) {
            KnobMode.LATENCY -> MaterialTheme.colorScheme.onErrorContainer
            KnobMode.VOLUME -> MaterialTheme.colorScheme.onError
        }
    } else {
        when (mode) {
            KnobMode.LATENCY -> MaterialTheme.colorScheme.onPrimaryContainer
            KnobMode.VOLUME -> MaterialTheme.colorScheme.onTertiary
        }
    }
    val centerTextColor = if (isSnapclient) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        when (mode) {
            KnobMode.LATENCY -> MaterialTheme.colorScheme.onSecondaryContainer
            KnobMode.VOLUME -> MaterialTheme.colorScheme.onTertiaryContainer
        }
    }

    Box(
        modifier = Modifier
            .size(knobSize)
            .onSizeChanged { size ->
                center = Offset(size.width / 2f, size.height / 2f)
                knobRadiusPx = minOf(size.width, size.height) / 2f
            }
            .pointerInput(mode, interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(
                    onTap = { onModeToggle() },
                    onDoubleTap = {
                        isActive = false
                        prevSmoothedDelta = 0f
                        accDelta = 0f
                        when (mode) {
                            KnobMode.LATENCY -> if (displayLatency != 0) {
                                displayLatency = 0
                                onLatencyChange(0)
                            }
                            KnobMode.VOLUME -> onMutedToggle()
                        }
                    },
                )
            }
            .pointerInput(mode, interactive) {
                if (!interactive) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isActive = true
                        prevSmoothedDelta = 0f
                        accDelta = 0f
                    },
                    onDragEnd = {
                        isActive = false
                        prevSmoothedDelta = 0f
                        accDelta = 0f
                    },
                    onDragCancel = {
                        isActive = false
                        prevSmoothedDelta = 0f
                        accDelta = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = computeAngularDelta(
                            center,
                            change.position,
                            dragAmount,
                            knobRadiusPx,
                            minActiveRadiusRatio,
                            tinyMotionDeadzoneDeg,
                            maxEventDeltaDeg,
                        )
                        val smoothed = prevSmoothedDelta * smoothingPrevWeight + delta * smoothingCurrentWeight
                        prevSmoothedDelta = smoothed
                        accDelta += smoothed * angularGain
                        while (accDelta >= degreesPerStep) {
                            when (mode) {
                                KnobMode.LATENCY -> if (displayLatency < maxLatency) {
                                    val v = (displayLatency + 1).coerceAtMost(maxLatency)
                                    displayLatency = v
                                    onLatencyChange(v)
                                }
                                KnobMode.VOLUME -> if (displayVolume < 100) {
                                    val v = (displayVolume + 1).coerceAtMost(100)
                                    displayVolume = v
                                    onVolumeChange(v)
                                }
                            }
                            accDelta -= degreesPerStep
                        }
                        while (accDelta <= -degreesPerStep) {
                            when (mode) {
                                KnobMode.LATENCY -> if (displayLatency > minLatency) {
                                    val v = (displayLatency - 1).coerceAtLeast(minLatency)
                                    displayLatency = v
                                    onLatencyChange(v)
                                }
                                KnobMode.VOLUME -> if (displayVolume > 0) {
                                    val v = (displayVolume - 1).coerceAtLeast(0)
                                    displayVolume = v
                                    onVolumeChange(v)
                                }
                            }
                            accDelta += degreesPerStep
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(knobSize * indicatorScale)) {
            val radius = size.minDimension / 2f
            val edgeStroke = size.minDimension * 0.045f
            val shadowRadius = radius * 0.86f

            val indicatorAngle = when (mode) {
                KnobMode.LATENCY -> {
                    val range = (maxLatency - minLatency).toFloat()
                    val normalized = (displayLatency - minLatency).toFloat() / range
                    val zeroNorm = (0 - minLatency).toFloat() / range
                    (normalized - zeroNorm) * 360f
                }
                KnobMode.VOLUME -> -135f + (displayVolume / 100f) * 270f
            }

            val indRad = ((indicatorAngle - 90f) * PI / 180f).toFloat()
            val indRadius = radius * 0.67f
            val indCenter = Offset(center.x + cos(indRad) * indRadius, center.y + sin(indRad) * indRadius)

            drawCircle(color = knobFillColor, radius = shadowRadius)
            drawCircle(color = knobEdgeColor, radius = shadowRadius, style = Stroke(width = edgeStroke))
            drawCircle(color = knobEdgeColor.copy(alpha = 0.14f), radius = shadowRadius * 0.76f)
            drawCircle(color = indicatorColor, radius = size.minDimension * 0.055f, center = indCenter)
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (mode == KnobMode.LATENCY) "LAT" else "VOL",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = centerTextColor.copy(alpha = 0.5f),
            )
            when (mode) {
                KnobMode.LATENCY -> {
                    Text("$displayLatency", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, color = centerTextColor)
                    Text("ms", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = centerTextColor.copy(alpha = 0.8f))
                }
                KnobMode.VOLUME -> {
                    Text("$displayVolume%", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, color = centerTextColor.copy(alpha = if (muted) 0.4f else 1f))
                    Text(if (muted) "muted" else " ", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = centerTextColor.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun computeAngularDelta(
    center: Offset,
    pointerPosition: Offset,
    dragAmount: Offset,
    knobRadiusPx: Float,
    minActiveRadiusRatio: Float,
    tinyMotionDeadzoneDeg: Float,
    maxEventDeltaDeg: Float,
): Float {
    val v = pointerPosition - center
    val r = sqrt(v.x * v.x + v.y * v.y)
    if (r < knobRadiusPx * minActiveRadiusRatio || r <= 0f) return 0f
    val tx = -v.y / r
    val ty = v.x / r
    val tangentialPx = dragAmount.x * tx + dragAmount.y * ty
    val deg = (tangentialPx / r) * (180f / PI.toFloat())
    if (abs(deg) < tinyMotionDeadzoneDeg) return 0f
    return deg.coerceIn(-maxEventDeltaDeg, maxEventDeltaDeg)
}
