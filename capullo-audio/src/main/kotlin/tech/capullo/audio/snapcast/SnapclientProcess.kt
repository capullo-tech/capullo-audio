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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    /**
     * Whether this client really hears the station, not whether a socket opened:
     * - [STARTING] until the first proof, [ERROR] once the link has failed and until it is proven again.
     * - [CONNECTED] only on proof that the link works both ways: the time sync with the server
     *   completed, or audio has played without a break for several seconds.
     *
     * On a link too slow or lossy to sync, the native client reconnects every few seconds and its
     * TCP connect succeeds every time. Reporting that as connected told a silent listener it was
     * "playing in sync" half the time; see [connectionStateAfter].
     */
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

    @Volatile
    private var currentProcess: Process? = null

    fun destroy() {
        currentProcess?.kill()
        currentProcess = null
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

    /**
     * [scheme] `tcp` dials the raw stream port; `ws`/`wss` dial snapserver's HTTP port and stream over
     * its `/stream` websocket, the only way through the HTTPS tunnel. A joined station passes its
     * [JoinTarget.scheme], [JoinTarget.host] and [JoinTarget.port].
     */
    suspend fun start(
        snapserverAddress: String = "localhost",
        snapserverPort: Int = 1604,
        audioChannel: String = "stereo",
        freshId: Boolean = false,
        scheme: String = "tcp",
    ) = coroutineScope {
        val hostId = loadHostId(freshId)
        val pb = ProcessBuilder().command(
            "$nativeLibDir/libsnapclient.so",
            "--hostID", hostId,
            "--player", androidPlayer,
            "--sampleformat", sampleFormat,
            "--logfilter", "*:info,Stats:debug",
            "--channel", audioChannel,
            serverUri(scheme, snapserverAddress, snapserverPort),
        )

        val env = pb.environment()
        if (rate != null) env["SAMPLE_RATE"] = rate
        if (fpb != null) env["FRAMES_PER_BUFFER"] = fpb

        // A stop can land between the caller's isActive check and the spawn; don't start a client
        // nobody will own. And a spawn failure (fork EAGAIN/ENOMEM, missing binary) is reported as
        // a fault rather than thrown: a restart loop calls this repeatedly, often in exactly the
        // conditions that killed the last client, and an escaped IOException crashes the app.
        ensureActive()
        val process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "snapclient spawn failed", e)
            _connectionState.update { ConnectionState.ERROR }
            return@coroutineScope
        }
        currentProcess = process
        if (!isActive) {
            process.kill()
            if (currentProcess === process) currentProcess = null
            return@coroutineScope
        }
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                ensureActive()
                line?.let {
                    val stats = SnapclientStats.parse(it)
                    _connectionState.update { state -> connectionStateAfter(it, stats, state) }
                    stats?.also { s ->
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
        // start() returned normally either way and left connectionState on its last value, so a
        // device could go silent with a healthy server and nothing anywhere saying its own speaker
        // had stopped (reproduced on the rig with kill -9).
        //
        // Told apart by the JOB, not by a flag: readLine() is not cancellable, so an intentional
        // stop usually leaves the loop through a null line rather than a CancellationException, and
        // every caller cancels this coroutine BEFORE destroying the process. So "still active here"
        // means nobody asked for this.
        val code = runCatching { process.waitFor() }.getOrNull()
        // Only if still ours: a restarted run may already have stored its own process here, and
        // clearing that would leave destroy() unable to stop it.
        if (currentProcess === process) currentProcess = null
        if (isActive) {
            Log.e(TAG, "snapclient exited on its own (code=$code)")
            _connectionState.update { ConnectionState.ERROR }
        } else {
            Log.d(TAG, "snapclient stopped as asked (code=$code)")
        }
    }

    companion object {
        private val TAG = SnapclientProcess::class.java.simpleName

        /**
         * [current] after one line of the native client's log; [stats] is that line when it is a
         * Stats line.
         *
         * Proof of a working link, either of:
         * - `diff to server`, logged once the 50 quick time syncs that follow the hello all came
         *   back (each has 2 s). Measured at 0.9 s on a LAN and 1.3 s over a VPN.
         * - A full Stats window, which takes about 6 s of unbroken audio. It covers a link whose
         *   round trips are slow enough for the quick syncs to outlast that (300 ms each is 15 s)
         *   while the audio already plays.
         *
         * `Connected to` proves nothing and changes nothing: it is the TCP connect, which also
         * succeeds on every turn of a reconnect loop. So does a Stats line with a short window: a
         * loop plays a moment of audio each turn. A failed link is `[Error] (Connection)` or the
         * controller's `Reconnecting`, and stays failed until the next proof.
         */
        internal fun connectionStateAfter(line: String, stats: SnapclientStats?, current: ConnectionState): ConnectionState = when {
            stats != null -> if (stats.statsWindowFill >= SnapclientStats.WINDOW) ConnectionState.CONNECTED else current
            line.contains("[Error] (Connection)") || line.contains("(Controller) Reconnecting") -> ConnectionState.ERROR
            line.contains("(Controller) diff to server") -> ConnectionState.CONNECTED
            else -> current
        }

        /**
         * The snapserver URI a snapclient dials: `<scheme>://<host>:<port>`, an IPv6 host bracketed.
         * The port is always explicit: snapclient defaults `wss` to 1788, not 443. The brackets are
         * what snapclient's parser needs to tell an IPv6 literal from the port (capullo-tech/snapcast
         * 85e8cbed, in the pinned lib-snapcast-android); a bare literal is cut at its first `:`.
         */
        internal fun serverUri(scheme: String, host: String, port: Int): String = "$scheme://${JoinUrl.authority(host, port)}"

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
