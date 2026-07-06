package tech.capullo.audio.snapcast

import java.net.ServerSocket

/**
 * The three TCP ports a Snapserver instance uses:
 *  - [streamPort]  the client-facing stream port snapclients connect to (also advertised over NSD
 *                  as `_snapcast._tcp`).
 *  - [tcpPort]     the JSON-RPC control port over TCP (advertised as `_snapcast-stream._tcp`).
 *  - [httpPort]    the HTTP port that serves the web player and the JSON-RPC / WebSocket control
 *                  API - the port a "listen here" URL / QR points at.
 *
 * Use [free] so the OS picks three distinct unused ports: multiple capullo apps (and stock
 * Snapcast) then coexist on one device with zero configuration, and consumers read the resolved
 * ports back off [SnapserverProcess.ports] to wire NSD / the listen-in URL / the local snapclient /
 * the control client. [Fixed] keeps the legacy 1604/1605/1680 for a deterministic setup.
 */
data class SnapserverPorts(
    val streamPort: Int,
    val tcpPort: Int,
    val httpPort: Int,
) {
    companion object {
        /** Legacy fixed ports (stream/tcp/http = 1604/1605/1680). */
        val Fixed = SnapserverPorts(streamPort = 1604, tcpPort = 1605, httpPort = 1680)

        /**
         * Three distinct free ephemeral ports. The sockets are opened simultaneously so the OS
         * hands out three different ports, then closed immediately before Snapserver binds them
         * (a negligible TOCTOU window - Snapserver binds within a beat of construction).
         */
        fun free(): SnapserverPorts {
            val sockets = List(3) { ServerSocket(0) }
            return try {
                SnapserverPorts(
                    streamPort = sockets[0].localPort,
                    tcpPort = sockets[1].localPort,
                    httpPort = sockets[2].localPort,
                )
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
        }
    }
}
