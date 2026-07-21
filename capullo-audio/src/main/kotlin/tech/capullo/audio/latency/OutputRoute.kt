package tech.capullo.audio.latency

/**
 * The audio output path media is currently routed to on THIS device. Snapcast keeps the group
 * sample-synced over its network time protocol, but a client's *output* stage - especially a
 * Bluetooth A2DP sink - adds 100-300 ms the snapclient can't observe. This reports the active route
 * (for observability / any route-aware feature), identified by [key].
 *
 * [key] must be stable for "the same speaker" across connects so the route is recognizable:
 *  - BUILTIN / WIRED: a fixed key per kind (there is only one at a time in practice).
 *  - BLUETOOTH: the sink's MAC address when available, else its product name - see
 *    [AudioRouteMonitor]. Never the empty string.
 */
public data class OutputRoute(
    val key: String,
    val kind: Kind,
    val displayName: String,
) {
    public enum class Kind { BUILTIN, WIRED, BLUETOOTH, OTHER }

    public companion object {
        public const val BUILTIN_KEY: String = "builtin"

        /** The fallback route: on-device speaker/earpiece. Also the default before the monitor starts. */
        public val BUILTIN: OutputRoute = OutputRoute(BUILTIN_KEY, Kind.BUILTIN, "Built-in speaker")
    }
}
