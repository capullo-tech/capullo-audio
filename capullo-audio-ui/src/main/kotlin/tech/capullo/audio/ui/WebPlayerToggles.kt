package tech.capullo.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The two web-player switches, shared by every capullo app's Settings screen. Both configure the
// browser client that capullo-audio serves, which is why they live here.
//
// ONE block rather than a reusable toggle row: the descriptions are the thing being converged, and
// a generic row would leave them copy-pasted into each app and converge nothing. Apps keep their
// own "Web player" section header.
//
// See [BalanceControls] for why the vertical rhythm is baked in and only [horizontalPadding] is
// exposed. Applying it inside matters more here than there: the row's `clickable` sits ABOVE the
// padding, so a ripple covers the whole padded row and the full row is tappable. Padding routed
// through [modifier] would land outside the clickable and shrink the touch target.
@Composable
fun WebPlayerToggles(
    autostart: Boolean,
    onAutostartChange: (Boolean) -> Unit,
    debugPanel: Boolean,
    onDebugPanelChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ToggleRow(
            label = "Autostart listening",
            description = "Web clients start listening automatically on page load instead of " +
                "waiting for the headphones button. Browsers may still require one tap the " +
                "first time. Applies on the web page's next reload.",
            checked = autostart,
            onToggle = onAutostartChange,
            horizontalPadding = horizontalPadding,
        )
        ToggleRow(
            label = "Debug panel",
            description = "Show the audio debug bar at the bottom of the web player. Applies on " +
                "the web page's next reload.",
            checked = debugPanel,
            onToggle = onDebugPanelChange,
            horizontalPadding = horizontalPadding,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    horizontalPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
