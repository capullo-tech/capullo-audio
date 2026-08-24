package tech.capullo.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stereo balance control for the broadcast mix, shared by every capullo app's Settings screen.
// Applied downstream by a BalanceAudioProcessor in the FIFO sink chain.
//
// Stateless on purpose: value in, callback out. QuantumCast hoists the whole settings object and
// already had this shape; Telecloud reads a StateFlow, so its screen collects ABOVE the call and
// passes the value down.
//
// Emits the CONTROLS only, never a section header — the apps head this block differently
// ("Audio" vs "Balance") and each keeps its own.
//
// The padding is BAKED IN, not a parameter: it is the part that must look the same in every app,
// so no call site can get it wrong by forgetting an argument.
//
// The horizontal 16dp was a parameter until 2026-08-24, because Telecloud's settings screen inset
// every child by 20dp from the scroll container while QuantumCast's LazyColumn insets nothing and
// lets each row pad itself. There is no negative padding, so a shared value could not serve both.
// Telecloud's screen was reshaped to QuantumCast's arrangement — every row self-pads — and with
// both screens agreeing the parameter had nothing left to express.
//
// Padding is applied inside rather than left to [modifier] so it lands within anything the block
// itself wraps (see [WebPlayerToggles], where a clickable sits above it).
@Composable
fun BalanceControls(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "Left/right channel volume for the broadcast mix",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("L", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f),
            )
            Text("R", style = MaterialTheme.typography.labelMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    value < -0.01f -> "Left ${(value * -100).toInt()}%"
                    value > 0.01f -> "Right ${(value * 100).toInt()}%"
                    else -> "Centered"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onValueChange(0f) }, enabled = value != 0f) { Text("Center") }
        }
    }
}
