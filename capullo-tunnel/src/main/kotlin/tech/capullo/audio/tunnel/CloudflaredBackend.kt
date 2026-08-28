package tech.capullo.audio.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare quick-tunnel backend: execs the bundled cloudflared binary (GOOS=android
 * build - see the fetchCloudflared task in this module's build.gradle.kts), which
 * dials Cloudflare's edge outbound (QUIC/UDP 7844, built-in TCP fallback) and proxies a random
 * https://<words>.trycloudflare.com URL to the snapserver HTTP port. No account, no
 * credentials. The URL exists only in cloudflared's log output, so it is scraped with
 * [parsePublicUrl]; process exit = tunnel dropped.
 *
 * Known-harmless on Android: cloudflared subsystems that bypass the OS resolver (feature
 * fetch, local DNS resolver diagnostics) log [::1]:53 lookup errors - HTTP proxying does
 * not depend on them. On networks that block UDP 7844 a "--protocol http2" retry would be
 * the fallback (not implemented; QUIC prechecks passed on the test fleet).
 */
class CloudflaredBackend(private val context: Context) : TunnelBackend {

    @Volatile private var process: Process? = null

    override suspend fun run(localPort: Int, onUp: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val bin = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            check(bin.exists()) { "cloudflared binary missing at ${bin.absolutePath}" }
            val proc = ProcessBuilder(
                bin.absolutePath,
                "tunnel",
                "--url",
                "http://127.0.0.1:$localPort",
                "--no-autoupdate",
            ).redirectErrorStream(true).start()
            process = proc
            try {
                var url: String? = null
                val urlSeen = AtomicBoolean(false)
                // Watchdog: no URL in time → kill the process; the resulting EOF ends the
                // read loop and surfaces as the "no URL" error below.
                val watchdog = launch {
                    delay(URL_TIMEOUT_MS)
                    if (!urlSeen.get()) proc.destroy()
                }
                // cloudflared logs forever (stats); EOF = process exited or stop() fired.
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (url == null) {
                            url = parsePublicUrl(line)?.also {
                                urlSeen.set(true)
                                Log.d(TAG, "cloudflared issued $it")
                                onUp(it)
                            }
                        }
                    }
                }
                watchdog.cancel()
                if (url == null) throw IOException("cloudflared exited without issuing a URL")
            } finally {
                proc.destroy()
                process = null
            }
        }
    }

    override fun stop() {
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "CloudflaredBackend"
        private const val URL_TIMEOUT_MS = 30_000L
    }
}

private val ANSI_RE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

// Only the tunnel URL itself matches: the banner also logs doc/ToS links
// (www.cloudflare.com, developers.cloudflare.com) BEFORE the URL line, and the
// "Requesting new quick Tunnel on trycloudflare.com..." line has no https:// host.
private val PUBLIC_URL_RE = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")

/** Extracts the quick-tunnel URL from cloudflared log output (null if none yet). */
internal fun parsePublicUrl(output: String): String? = PUBLIC_URL_RE.find(ANSI_RE.replace(output, ""))?.value
