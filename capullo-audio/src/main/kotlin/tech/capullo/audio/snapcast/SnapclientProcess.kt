package tech.capullo.audio.snapcast

import android.app.Service.AUDIO_SERVICE
import android.content.Context
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class SnapclientProcess(private val context: Context) {

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val androidPlayer = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "opensl" else "oboe"

    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
    private val rate: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
    private val fpb: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
    private val sampleFormat = "$rate:16:*"

    enum class ConnectionState { STARTING, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.STARTING)
    val connectionState = _connectionState.asStateFlow()

    // Buffered so slow collectors never stall the stdout reader; samples arrive ~1/s.
    private val _stats = MutableSharedFlow<SnapclientStats>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Per-second sync stats parsed from the running client's stdout; see [SnapclientStats]. */
    val stats = _stats.asSharedFlow()

    val storedHostId: String
        get() = localHostId(context)

    private var _process: Process? = null

    fun destroy() {
        _process?.destroyForcibly()
        _process = null
    }

    fun setChannel(channel: String) {
        try {
            val sock = LocalSocket()
            sock.connect(LocalSocketAddress("snapclient_channel", LocalSocketAddress.Namespace.ABSTRACT))
            sock.outputStream.write("$channel\n".toByteArray())
            sock.outputStream.flush()
            sock.close()
            Log.d(TAG, "Channel → $channel (via socket)")
        } catch (e: Exception) {
            Log.w(TAG, "setChannel failed: ${e.message}")
        }
    }

    private fun loadHostId(fresh: Boolean = false): String {
        if (fresh) {
            context.getSharedPreferences("SNAPCAST_CLIENT_HOST_ID", Context.MODE_PRIVATE)
                .edit { remove("SNAPCAST_CLIENT_HOST_ID_PREFERENCE") }
        }
        return localHostId(context)
    }

    suspend fun start(
        snapserverAddress: String = "localhost",
        snapserverPort: Int = 1604,
        audioChannel: String = "stereo",
        freshId: Boolean = false,
    ) = coroutineScope {
        val hostId = loadHostId(freshId)
        val pb = ProcessBuilder().command(
            "$nativeLibDir/libsnapclient.so",
            "--hostID", hostId,
            "--player", androidPlayer,
            "--sampleformat", sampleFormat,
            "--logfilter", "*:info,Stats:debug",
            "--channel", audioChannel,
            "tcp://$snapserverAddress:$snapserverPort",
        )

        val env = pb.environment()
        if (rate != null) env["SAMPLE_RATE"] = rate
        if (fpb != null) env["FRAMES_PER_BUFFER"] = fpb

        val process = pb.start().also { _process = it }
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                ensureActive()
                line?.let {
                    when {
                        it.contains("[Error] (Connection)") -> _connectionState.update { ConnectionState.ERROR }
                        it.contains("[Notice] (Connection) Connected to") -> _connectionState.update { ConnectionState.CONNECTED }
                        else -> SnapclientStats.parse(it)?.also { s ->
                            _stats.tryEmit(s)
                            // Labeled per-second sync diagnostic (raw line is Log.d below). reportedDac
                            // is Stats col 6 = player-reported output latency; on A2DP the BT estimate.
                            Log.i(
                                TAG,
                                "Stats sync med=${s.medianErrorMs}ms " +
                                    "(mini=${s.miniMedianErrorMs} short=${s.shortMedianErrorMs}) " +
                                    "reportedDac=${s.reportedOutputLatencyMs}ms " +
                                    "fill=${s.statsWindowFill} frameDelta=${s.frameDelta}",
                            )
                        }
                    }
                    Log.d(TAG, it)
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Snapclient cancelled")
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Snapclient error", e)
        }
        // REACHED WHENEVER THE NATIVE CLIENT'S STDOUT ENDS, AND THE TWO REASONS ARE NOT THE SAME.
        //
        // Either a caller stopped us (destroy() closed the stream) or libsnapclient.so exited on
        // its own. Only the second is a fault, and before this the two were indistinguishable:
        // start() returned normally either way, left connectionState on its last value, and the
        // caller's launch{} simply completed. A broadcaster then went silent with a healthy server,
        // a healthy app and nothing anywhere saying its own speaker had stopped (rig, 2026-09-05:
        // gone for 1h50m before anyone noticed).
        //
        // Told apart by the JOB, not by a flag: readLine() is not cancellable, so an intentional
        // stop usually leaves the loop through a null line rather than a CancellationException, and
        // every caller cancels this coroutine BEFORE destroying the process. So "still active here"
        // means nobody asked for this.
        val code = runCatching { process.waitFor() }.getOrNull()
        _process = null
        if (isActive) {
            Log.e(TAG, "snapclient exited on its own (code=$code) - this device is now silent")
            _connectionState.update { ConnectionState.ERROR }
        } else {
            Log.d(TAG, "snapclient stopped as asked (code=$code)")
        }
    }

    companion object {
        private val TAG = SnapclientProcess::class.java.simpleName

        /** The persistent --hostID this device's snapclient registers with -
         *  equals its client id on any snapserver (used to exclude self from
         *  connected-client counts). Generated + persisted on first read, so the
         *  UI has a stable self-id even before the first client run (otherwise a
         *  cold first launch mis-counts self as an "other" / hides the self card). */
        fun localHostId(context: Context): String {
            val prefs = context.getSharedPreferences("SNAPCAST_CLIENT_HOST_ID", Context.MODE_PRIVATE)
            return prefs.getString("SNAPCAST_CLIENT_HOST_ID_PREFERENCE", null) ?: run {
                val id = UUID.randomUUID().toString()
                prefs.edit { putString("SNAPCAST_CLIENT_HOST_ID_PREFERENCE", id) }
                id
            }
        }
    }
}
