package tech.capullo.audio.snapcast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.capullo.audio.calibration.ReferencePcmRing
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * A SECOND, SILENT snapclient that exists only to hand a calibration run its reference PCM.
 *
 * **Why this has to exist at all.** The mic calibration cross-correlates a room recording against
 * the exact PCM being played. On the BROADCASTER that reference is free: [tech.capullo.audio.player
 * .FifoAudioBufferSink] already writes every decoded buffer into the snapserver FIFO, so the
 * calibration just mirrors those bytes on the way past. A CLIENT has no such tee — its snapclient is
 * a native process handing PCM straight to oboe, with no interception point above it. Without a
 * reference a client cannot calibrate, which is why the feature was broadcaster-only.
 *
 * That matters because the broadcaster is usually the wrong place to measure from. The calibration
 * balances the room AT THE MICROPHONE, so running it from the server phone balances for wherever
 * that phone was left. Run it from a client sitting where someone actually listens and it balances
 * for the listener, which is the version worth having.
 *
 * **Why a second process rather than a tap on the first.** `snapclient --player` selects exactly one
 * player: `oboe` plays and exposes nothing, `file` writes PCM and plays nothing. One process cannot
 * do both. Rig-verified 2026-08-19 on the OnePlus: a second snapclient started alongside the live
 * one coexists cleanly (the audible client kept reporting `Stats sync med=0.0ms` throughout) and
 * delivers real-time audio — 193,920 B/s measured against the 192,000 B/s that 48 kHz/16-bit/stereo
 * demands, a ratio of 1.010, carrying 98.7 s of music at −9.6 dBFS.
 *
 * **The hazard this class is careful about.** The tap is a real connected client to the server:
 * unnamed, 100 % volume, and completely silent. Left in a calibration's client list it does not just
 * add noise, it disables the balance outright — two speakers plus a tap is three clients, which
 * routes the run to the batch path where `levelProbeSet(3)` declines the excursion three speakers
 * need and no levels are harvested at all. Hence [SyncCalibrator.REFERENCE_TAP_PREFIX]: every id
 * this class registers carries it, and the calibrator drops anything holding that prefix before it
 * looks at the list.
 *
 * A FIFO is used rather than a regular file because this stream never ends on its own: the rig test
 * wrote 19 MB to disk in 100 s. A pipe keeps the data in memory and back-pressures naturally.
 */
class ReferenceTapProcess(private val context: Context) {

    private var process: Process? = null
    private var fifo: File? = null

    /** The id this tap registered with, or null when it is not running. Carries the tap prefix, so
     *  a host that surfaces client lists can filter it the same way the calibrator does. */
    @Volatile
    var hostId: String? = null
        private set

    /**
     * Start the tap and stream its PCM into [ring] until [stop] is called or the coroutine is
     * cancelled. Suspends for the lifetime of the tap, so callers launch it in their own job.
     *
     * Returns having logged the reason if the tap could not be started at all; a calibration that
     * loses its reference fails through the calibrator's own "measurement failed" path rather than
     * throwing here, which keeps a missing tap indistinguishable from a bad room from the run's
     * point of view (both mean: no usable reference, restore everything, report it).
     */
    suspend fun start(
        snapserverAddress: String,
        snapserverPort: Int,
        ring: ReferencePcmRing,
    ) = withContext(Dispatchers.IO) {
        val id = tech.capullo.audio.calibration.SyncCalibrator.REFERENCE_TAP_PREFIX +
            UUID.randomUUID().toString().take(8)
        // A fresh FIFO per run. mkfifo can fail if a previous run died without cleaning up, so the
        // stale node is removed first rather than trusted.
        val path = File(context.cacheDir, "calref.pcm").also { it.delete() }
        if (!mkfifo(path)) {
            Log.w(TAG, "reference tap: could not create FIFO at $path")
            return@withContext
        }
        fifo = path
        val pb = ProcessBuilder().command(
            "${context.applicationInfo.nativeLibraryDir}/libsnapclient.so",
            "--hostID", id,
            // The whole point: PCM to the pipe, nothing to a speaker.
            "--player", "file:filename=${path.absolutePath},mode=w",
            "--sampleformat", "48000:16:*",
            "--logfilter", "*:warning",
            "tcp://$snapserverAddress:$snapserverPort",
        )
        try {
            process = pb.start().also { hostId = id }
            Log.i(TAG, "reference tap started as $id -> ${path.absolutePath}")
            // OPEN THE READ END AFTER the writer exists. Opening a FIFO for reading blocks until a
            // writer appears, which is exactly the ordering we want: this returns once snapclient
            // has connected and opened its end.
            FileInputStream(path).use { input ->
                val buf = ByteArray(READ_CHUNK_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    // The ring takes the same interleaved 16-bit stereo layout the broadcaster's
                    // FIFO sink writes, so no conversion belongs here.
                    ring.write(ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reference tap ended: ${e.message}")
        } finally {
            stop()
        }
    }

    /** Kill the tap and remove its FIFO. Safe to call twice, and safe to call when never started. */
    fun stop() {
        process?.destroyForcibly()
        process = null
        hostId = null
        fifo?.delete()
        fifo = null
    }

    /** Creates a named pipe, returning false if the platform refuses. Uses the shell's mkfifo:
     *  android.system.Os offers no mkfifo binding, and the alternative (a JNI shim) would mean
     *  shipping native code for one syscall. */
    private fun mkfifo(path: File): Boolean = try {
        val p = ProcessBuilder().command("/system/bin/mkfifo", path.absolutePath).start()
        p.waitFor()
        path.exists()
    } catch (e: Exception) {
        Log.w(TAG, "mkfifo failed: ${e.message}")
        false
    }

    companion object {
        private val TAG = ReferenceTapProcess::class.java.simpleName

        /** Read granularity from the pipe. A quarter second of 48 kHz/16-bit/stereo: large enough
         *  that the read loop is not syscall-bound, small enough that the ring's wall-clock stamp
         *  ([ReferencePcmRing.lastWriteNanos]) stays close to the audio it describes. */
        private const val READ_CHUNK_BYTES = 48_000
    }
}
