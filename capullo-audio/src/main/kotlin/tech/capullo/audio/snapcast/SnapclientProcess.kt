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
import kotlinx.coroutines.flow.MutableStateFlow
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
        val prefs = context.getSharedPreferences("SNAPCAST_CLIENT_HOST_ID", Context.MODE_PRIVATE)
        if (fresh) prefs.edit { remove("SNAPCAST_CLIENT_HOST_ID_PREFERENCE") }
        return prefs.getString("SNAPCAST_CLIENT_HOST_ID_PREFERENCE", null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit { putString("SNAPCAST_CLIENT_HOST_ID_PREFERENCE", id) }
            Log.d(TAG, "Generated new hostId: $id")
            id
        }
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
    }

    companion object {
        private val TAG = SnapclientProcess::class.java.simpleName

        /** The persistent --hostID this device's snapclient registers with -
         *  equals its client id on any snapserver (used to exclude self from
         *  connected-client counts). Empty until the first client run. */
        fun localHostId(context: Context): String =
            context.getSharedPreferences("SNAPCAST_CLIENT_HOST_ID", Context.MODE_PRIVATE)
                .getString("SNAPCAST_CLIENT_HOST_ID_PREFERENCE", null) ?: ""
    }
}
