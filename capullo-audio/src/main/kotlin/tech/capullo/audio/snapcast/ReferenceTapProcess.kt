package tech.capullo.audio.snapcast

import android.content.Context
import android.provider.Settings
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
 *
 * **The tap must be deregistered, not just killed.** Snapserver deliberately KEEPS disconnected
 * clients in its status with their last-known values — the calibration's own read-back depends on
 * that — so killing the process leaves the tap in the server's client list and in `server.json`
 * for ever. With a random id per run that is one permanent orphan per calibration: the rig had
 * accumulated 14 of them across 15 groups by 2026-08-25, each alone in its own group, inflating
 * `Server.GetStatus` from 2.9 KB to 9.4 KB. Two things stop that, and both are needed:
 *
 * - [deregister] issues `Server.DeleteClient` for the id once the process is gone, which is what
 *   that RPC exists for. It was implemented and never called.
 * - The id is now STABLE per device rather than a fresh UUID, so a run that dies before it can
 *   deregister is reused by the next run instead of adding another entry. At worst one orphan per
 *   device, never one per run.
 */
class ReferenceTapProcess(
    private val context: Context,
    /**
     * Removes the tap from the server's client list, given the id it registered with. Called after
     * the process is killed. Optional only so a caller with no control connection still gets a
     * working tap; omitting it reintroduces the orphan the stable id then bounds to one.
     *
     * Implementations should let the server notice the disconnect before deleting, and must not
     * block: [stop] is called from a plain (non-suspending) disarm lambda.
     */
    private val deregister: ((String) -> Unit)? = null,
) {

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
        val id = stableTapId()
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

    /**
     * Kill the tap, deregister it from the server, and remove its FIFO. Safe to call twice, and
     * safe to call when never started.
     *
     * The id is captured before it is cleared, so [deregister] still receives it.
     */
    @Synchronized
    fun stop() {
        // Claim the id atomically. stop() is reached twice by design on a normal run — the disarm
        // lambda calls it, and the cancelled coroutine's own finally calls it again — and without
        // this both callers read the same non-null id and fire two Server.DeleteClient calls, the
        // second answering "Client not found". Harmless, but it reads as a failure in the log.
        val id = hostId
        hostId = null
        process?.destroyForcibly()
        process = null
        fifo?.delete()
        fifo = null
        if (id != null) deregister?.invoke(id)
    }

    /**
     * The id this device's tap always registers with. Stable so a crashed run leaves at most one
     * orphan rather than one per run, and distinct per device so two calibrating clients cannot
     * collide on the server.
     *
     * `ANDROID_ID` is per-device, per-app and needs no permission. It can be null on a badly
     * behaved image, hence the fallback to a UUID persisted next to the FIFO — that file survives
     * anything short of clearing app data, which is a fresh start anyway.
     */
    private fun stableTapId(): String {
        val android = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.w(TAG, "ANDROID_ID unavailable: ${e.message}"); null
        }
        val suffix = android?.takeIf { it.isNotBlank() }?.take(8) ?: persistedTapSuffix()
        return tech.capullo.audio.calibration.SyncCalibrator.REFERENCE_TAP_PREFIX + suffix
    }

    private fun persistedTapSuffix(): String {
        val f = File(context.filesDir, "calref-tap-id")
        return runCatching { f.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
            ?: UUID.randomUUID().toString().take(8).also {
                runCatching { f.writeText(it) }
            }
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
