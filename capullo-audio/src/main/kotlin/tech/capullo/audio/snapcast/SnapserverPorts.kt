package tech.capullo.audio.snapcast

import android.util.Log
import java.net.ServerSocket
import kotlin.random.Random

/**
 * The three TCP ports a Snapserver instance uses:
 *  - [streamPort]  the client-facing stream port snapclients connect to (also advertised over NSD
 *                  as `_snapcast._tcp`).
 *  - [tcpPort]     the JSON-RPC control port over TCP (advertised as `_snapcast-stream._tcp`).
 *  - [httpPort]    the HTTP port that serves the web player and the JSON-RPC / WebSocket control
 *                  API - the port a "listen here" URL / QR points at.
 *
 * BLOCK INVARIANT: a capullo broadcaster's ports are one consecutive block in a fixed order,
 * stream = base, tcp = base+1, http = base+2, whether pinned ([fixed]) or random ([free]). A listener
 * who knows ANY one of them can find the others: the HTTP port is the last of the block and serves
 * `listen.json`, so [SnapserverListenInfo.resolve] probes typed..typed+2. Outside the invariant: the
 * legacy [Fixed] 1604/1605/1680 and [free]'s last-resort OS-assigned fallback; those still resolve
 * from their HTTP port.
 *
 * Use [free] so multiple capullo apps (and stock Snapcast) coexist on one device with zero
 * configuration; consumers read the resolved ports back off [SnapserverProcess.ports] to wire NSD /
 * the listen-in URL / the local snapclient / the control client. [Fixed] keeps the legacy
 * 1604/1605/1680 for a deterministic setup.
 */
data class SnapserverPorts(
    val streamPort: Int,
    val tcpPort: Int,
    val httpPort: Int,
) {
    companion object {
        /** Legacy fixed ports (stream/tcp/http = 1604/1605/1680). */
        val Fixed = SnapserverPorts(streamPort = 1604, tcpPort = 1605, httpPort = 1680)

        /** Three consecutive ports from [base] (base / base+1 / base+2). A fixed port keeps
         *  the server's address stable across broadcast restarts, so snapclients reconnect
         *  to the same host:port without rediscovery and the web player's origin (hence its
         *  localStorage) doesn't reset. Trade-off: two capullo apps must not share a base. */
        fun fixed(base: Int) = SnapserverPorts(streamPort = base, tcpPort = base + 1, httpPort = base + 2)

        /**
         * [fixed] ports iff all three actually bind right now, else null so the caller can
         * fall back to [free]. A user-editable fixed base can collide (busy port, two capullo
         * apps sharing a base, a stale snapserver still holding the trio); without this a
         * collision would leave the broadcast silently dead. Pre-flight: bind all three, then
         * close them before Snapserver binds them a beat later (a negligible TOCTOU window).
         */
        fun fixedIfFree(base: Int): SnapserverPorts? {
            val opened = mutableListOf<ServerSocket>()
            return try {
                for (p in intArrayOf(base, base + 1, base + 2)) opened += ServerSocket(p)
                SnapserverPorts(streamPort = base, tcpPort = base + 1, httpPort = base + 2)
            } catch (e: Exception) {
                null
            } finally {
                opened.forEach { runCatching { it.close() } }
            }
        }

        /**
         * A free consecutive block at a random base, in [fixed]'s order (stream/tcp/http =
         * base/base+1/base+2). Each draw picks a base inside the Linux/Android default ephemeral
         * range (`ip_local_port_range` 32768..60999) and pre-flights it with [fixedIfFree]; a busy
         * block costs another draw. Only after [BLOCK_ATTEMPTS] busy draws (a near-exhausted port
         * space) does it fall back to three OS-assigned ports, which breaks the block invariant: a
         * listener then needs the HTTP port itself to resolve the others.
         */
        fun free(): SnapserverPorts = free { Random.nextInt(BLOCK_BASE_FIRST, BLOCK_BASE_LAST + 1) }

        /** [free] with an injectable base source, so tests can drive the retry and fallback paths. */
        internal fun free(nextBase: () -> Int): SnapserverPorts {
            repeat(BLOCK_ATTEMPTS) { fixedIfFree(nextBase())?.let { return it } }
            Log.w(TAG, "no free consecutive port block in $BLOCK_ATTEMPTS draws; using OS-assigned ports")
            return osAssigned()
        }

        /**
         * Three distinct OS-assigned ports. The sockets are opened simultaneously so the OS hands
         * out three different ports, then closed immediately before Snapserver binds them.
         */
        private fun osAssigned(): SnapserverPorts {
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

        private const val TAG = "SnapserverPorts"
        private const val BLOCK_BASE_FIRST = 32768
        private const val BLOCK_BASE_LAST = 60999 - 2
        private const val BLOCK_ATTEMPTS = 50
    }
}
