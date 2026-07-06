package tech.capullo.audio.snapcast

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * @param streamName the Snapcast stream's `name=` - the identity shown in the web player and to
 *   snapclients. Each app supplies its own (e.g. "QuantumCast", "Telecloud") so multiple capullo
 *   apps on one LAN stay distinguishable.
 */
class SnapserverProcess(
    private val context: Context,
    streamName: String = DEFAULT_STREAM_NAME,
    /**
     * The TCP ports Snapserver binds. Default is the legacy fixed 1604/1605/1680; pass
     * [SnapserverPorts.free] for OS-assigned ports so multiple capullo apps coexist on one device.
     * Read back off [ports] to wire NSD / the listen-in URL / the local snapclient / control client.
     */
    val ports: SnapserverPorts = SnapserverPorts.Fixed,
) {

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val cacheDir: File = context.cacheDir
    private val confFile: String = getSnapserverConfPath()
    val pipeFilepath: String = createFifo()

    private val streamNameArg: String = "name=$streamName"

    companion object {
        const val DEFAULT_STREAM_NAME = "Capullo"
        private const val PIPE_NAME = "filifo"
        private const val CODEC = "codec=pcm"
        private const val PIPE_MODE = "mode=read"
        private const val DRYOUT_MS = "dryout_ms=10000"
        private const val SAMPLE_FORMAT = "sampleformat=44100:16:2"
        private val TAG = SnapserverProcess::class.java.simpleName
    }

    init {
        copyWebUiAsset()
    }

    private fun copyWebUiAsset() {
        try {
            val webuiDir = File(context.filesDir, "webui").apply { mkdirs() }
            context.assets.open("webui/index.html").use { input ->
                File(webuiDir, "index.html").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy WebUI: ${e.message}")
        }
    }

    private val pipeArgs = listOf(
        streamNameArg, CODEC, PIPE_MODE, DRYOUT_MS, SAMPLE_FORMAT,
        "controlscript=$nativeLibDir/libsnapcontrol.so",
    ).joinToString("&")

    private fun createFifo(): String {
        val pipeFile = File(cacheDir, PIPE_NAME)
        // Always recreate as a named pipe (FIFO). With a regular file, Snapserver 0.34.0
        // crashes when it hits EOF trying to encode the first chunk. With a named FIFO:
        //   - VLC opens with O_RDWR (its sout file module default) → no blocking, holds write end
        //   - Snapserver opens with O_RDONLY|O_NONBLOCK → succeeds (write end is held by VLC)
        //   - Snapserver read() blocks/returns EAGAIN instead of EOF → no crash
        //   - When VLC actually starts writing PCM, Snapserver reads real audio
        if (pipeFile.exists()) pipeFile.delete()
        try {
            Os.mkfifo(pipeFile.absolutePath,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR or
                OsConstants.S_IRGRP or OsConstants.S_IWGRP)
        } catch (e: Exception) {
            Log.e(TAG, "mkfifo failed: ${e.message}")
        }
        Log.d(TAG, "FIFO created: ${pipeFile.absolutePath}")
        return pipeFile.absolutePath
    }

    private fun getSnapserverConfPath(): String {
        val confFile = File(cacheDir, "snapserver.conf")
        val webUiPath = File(context.filesDir, "webui").absolutePath
        try {
            // Always rewrite so doc_root stays current and server.json reset takes effect.
            // Ports come from `ports` (default fixed 1604/1605/1680; or free() so a capullo-audio
            // app never collides with other Snapcast apps / instances on the same device).
            confFile.writeText(
                """
                [stream]
                port = ${ports.streamPort}

                [tcp]
                port = ${ports.tcpPort}

                [http]
                port = ${ports.httpPort}
                doc_root = $webUiPath
                """.trimIndent() + "\n"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapserver.conf: ${e.message}")
        }
        // Snapserver 0.34.0 skips chunk delivery to clients persisted with muted=true in
        // server.json, so audio dies on reconnect. Clear every muted flag (client and group)
        // but keep the rest - notably per-client latency calibration and volumes.
        clearMutedFlags(File(cacheDir, "server.json"))
        return confFile.absolutePath
    }

    private fun clearMutedFlags(file: File) {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            clearMuted(root)
            file.writeText(root.toString())
        } catch (e: Exception) {
            // Unparseable state file - fall back to the old behavior of starting fresh.
            Log.e(TAG, "Failed to clear muted flags, deleting server.json: ${e.message}")
            file.delete()
        }
    }

    private fun clearMuted(node: Any?) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (key in keys) {
                    if (key == "muted") node.put("muted", false)
                    else clearMuted(node.opt(key))
                }
            }
            is JSONArray -> for (i in 0 until node.length()) clearMuted(node.opt(i))
        }
    }

    suspend fun start() = coroutineScope {
        val pb = ProcessBuilder()
            .command(
                "$nativeLibDir/libsnapserver.so",
                "--config", confFile,
                "--server.datadir=$cacheDir",
                "--stream.source", "pipe://$pipeFilepath?$pipeArgs",
                "--http.doc_root=${File(context.filesDir, "webui").absolutePath}",
                "--server.name=${android.os.Build.MODEL}",
            )
            .redirectErrorStream(true)

        val process = pb.start()
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                ensureActive()
                Log.d(TAG, line!!)
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Snapserver cancelled")
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Snapserver error", e)
        }
    }
}
