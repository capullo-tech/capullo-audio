package tech.capullo.audio.snapcast

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * The one "join this station" URL every capullo QR encodes: `http://<lan-ip>:<httpPort>/join/` on the
 * LAN, `https://<tunnel-host>/join/` through the public tunnel.
 *
 * Both halves of the audience read the same URL. A plain browser opens it against the broadcaster's
 * snapserver HTTP port, whose doc_root serves `join/index.html`: on Android it offers one "Open in
 * <app>" button per [APPS] entry ([intentLink]) next to a link to the web player at `/`; elsewhere it
 * redirects straight to the player (same origin). An app with our snapclient matches the URL with an
 * intent-filter on the `/join` path and hands the URI to [parse], which yields where to point the
 * snapclient.
 *
 * The buttons exist because Android 12+ resolves a generic http/https link to an app only for a
 * verified (App Links) or user-approved domain, and LAN IPs or `*.trycloudflare.com` can't be
 * verified: a scan opens the browser. An `intent:` link that names the package is not generic, so
 * the browser hands it to that app's matching intent-filter.
 *
 * The trailing slash matters: snapserver only maps a target ending in `/` to its `index.html`, and it
 * does not strip query strings, so [build] emits exactly `/join/` with no query.
 */
object JoinUrl {
    const val PATH = "/join/"

    const val PACKAGE_CAPULLO_TUBE = "tech.capullo.tubecapullo"
    const val PACKAGE_QUANTUMCAST = "tech.capullo.quantumcast"
    const val PACKAGE_TELECLOUD_RADIO = "tech.capullo.telecloudradio"

    /** An app that joins stations: its applicationId and the name on its landing-page button. */
    data class App(val packageName: String, val label: String)

    /** The apps the landing page offers, in button order. */
    val APPS = listOf(
        App(PACKAGE_CAPULLO_TUBE, "CapulloTube"),
        App(PACKAGE_QUANTUMCAST, "QuantumCast"),
        App(PACKAGE_TELECLOUD_RADIO, "Telecloud Radio"),
    )

    private const val HTTPS_PORT = 443
    private const val MAX_PORT = 65535

    /**
     * The join URL for a broadcaster reachable at [host]:[port]. [port] is the snapserver HTTP port on
     * the LAN, or the tunnel's HTTPS port ([https] = true, 443 is left implicit). An IPv6 [host] may be
     * given bare or bracketed.
     */
    fun build(host: String, port: Int, https: Boolean = false): String = "${if (https) "https" else "http"}://${authority(host, port, https)}$PATH"

    /**
     * An Android `intent:` link that opens [target]'s join URL in [packageName] (one of [APPS]), with
     * the web player at `/` as `S.browser_fallback_url` for a phone without that app. The fallback is
     * the bare root: a query would 404 on snapserver.
     */
    fun intentLink(target: JoinTarget, packageName: String): String {
        val scheme = if (target.secure) "https" else "http"
        val authority = authority(target.host, target.httpPort, target.secure)
        val fallback = URLEncoder.encode("$scheme://$authority/", "UTF-8")
        return "intent://$authority$PATH#Intent;scheme=$scheme;package=$packageName;" +
            "S.browser_fallback_url=$fallback;end"
    }

    /**
     * The landing page `join/index.html` with [APPS] filled into its [APPS_PLACEHOLDER], so the page
     * offers exactly the apps this list names. A page left unfilled redirects to the player.
     */
    internal fun landingPage(template: String): String = template.replace(
        APPS_PLACEHOLDER,
        APPS.joinToString(",", "[", "]") { """{"pkg":"${it.packageName}","label":"${it.label}"}""" },
    )

    private const val APPS_PLACEHOLDER = "/*CAPULLO_APPS*/[]"

    private fun authority(host: String, port: Int, https: Boolean): String {
        val bare = host.trim().removeSurrounding("[", "]")
        require(bare.isNotEmpty()) { "empty host" }
        require(port in 1..MAX_PORT) { "port out of range: $port" }
        val hostPart = if (':' in bare) "[$bare]" else bare
        return if (https && port == HTTPS_PORT) hostPart else "$hostPart:$port"
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
