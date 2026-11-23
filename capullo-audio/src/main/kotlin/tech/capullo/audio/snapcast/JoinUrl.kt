package tech.capullo.audio.snapcast

import java.net.URI
import java.net.URISyntaxException

/**
 * The one "join this station" URL every capullo QR encodes: `http://<lan-ip>:<httpPort>/join/` on the
 * LAN, `https://<tunnel-host>/join/` through the public tunnel.
 *
 * Both halves of the audience read the same URL. A plain browser opens it against the broadcaster's
 * snapserver HTTP port, whose doc_root serves `join/index.html` (a redirect to the web player at `/`,
 * same origin). An app with our snapclient matches it with an intent-filter on the `/join` path and
 * hands the URI to [parse], which yields where to point the snapclient.
 *
 * The trailing slash matters: snapserver only maps a target ending in `/` to its `index.html`, and it
 * does not strip query strings, so [build] emits exactly `/join/` with no query.
 */
object JoinUrl {
    const val PATH = "/join/"

    private const val HTTPS_PORT = 443
    private const val MAX_PORT = 65535

    /**
     * The join URL for a broadcaster reachable at [host]:[port]. [port] is the snapserver HTTP port on
     * the LAN, or the tunnel's HTTPS port ([https] = true, 443 is left implicit). An IPv6 [host] may be
     * given bare or bracketed.
     */
    fun build(host: String, port: Int, https: Boolean = false): String {
        val bare = host.trim().removeSurrounding("[", "]")
        require(bare.isNotEmpty()) { "empty host" }
        require(port in 1..MAX_PORT) { "port out of range: $port" }
        val authority = if (':' in bare) "[$bare]" else bare
        val portPart = if (https && port == HTTPS_PORT) "" else ":$port"
        return "${if (https) "https" else "http"}://$authority$portPart$PATH"
    }

    /**
     * The broadcaster a join URL points at, or null when [uri] is not one: scheme other than
     * http/https, path other than `/join` or `/join/`, no host, or an http URL without an explicit
     * port (a capullo broadcaster never serves on 80, so a portless http URL is not ours).
     */
    fun parse(uri: String): JoinTarget? {
        val u = try {
            URI(uri.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val secure = when (u.scheme?.lowercase()) {
            "http" -> false
            "https" -> true
            else -> return null
        }
        if (u.rawPath != "/join" && u.rawPath != PATH) return null
        val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
        val port = when {
            u.port in 1..MAX_PORT -> u.port
            u.port == -1 && secure -> HTTPS_PORT
            else -> return null
        }
        return JoinTarget(host, port, secure)
    }
}

/**
 * A broadcaster parsed from a join URL ([JoinUrl.parse]).
 *
 * @property host bare host (an IPv6 literal without brackets): the LAN IP or the tunnel hostname.
 * @property httpPort the port the URL names: snapserver's HTTP port on the LAN (web player, JSON-RPC
 *   websocket `/jsonrpc`, stream websocket `/stream`), or the tunnel's HTTPS port.
 * @property secure true for the https (tunnel) form.
 */
data class JoinTarget(val host: String, val httpPort: Int, val secure: Boolean) {

    /**
     * The broadcaster's port block as derivable from the URL alone, null through the tunnel (only its
     * HTTPS port is reachable there). LAN: the block invariant of [SnapserverPorts] (stream = http-2,
     * tcp = http-1), or [SnapserverPorts.Fixed] for the legacy 1680. A guess for a broadcaster that fell
     * back to OS-assigned ports; [SnapserverListenInfo.fetch] (host, [httpPort]) is authoritative.
     */
    val ports: SnapserverPorts?
        get() = when {
            secure -> null
            httpPort == SnapserverPorts.Fixed.httpPort -> SnapserverPorts.Fixed
            httpPort > 2 -> SnapserverPorts(streamPort = httpPort - 2, tcpPort = httpPort - 1, httpPort = httpPort)
            else -> null
        }

    /**
     * The snapclient URL scheme: `tcp` to the raw stream port on the LAN, `wss` through the tunnel,
     * where snapclient streams over snapserver's `/stream` websocket on the HTTP port.
     */
    val scheme: String get() = if (secure) "wss" else "tcp"

    /** The port snapclient dials with [scheme]: the derived stream port on the LAN, [httpPort] through the tunnel. */
    val port: Int get() = ports?.streamPort ?: httpPort
}
