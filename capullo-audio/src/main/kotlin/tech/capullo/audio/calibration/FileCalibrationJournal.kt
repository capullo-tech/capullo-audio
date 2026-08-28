package tech.capullo.audio.calibration

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File-backed [CalibrationJournal] in the app's private storage. One
 * `id=latency,percent,muted` line per client; survives process death and reboots, so
 * [tech.capullo.audio.calibration.SyncCalibrator.recover] can undo a run the OS killed
 * mid-flight (restoring both latency and volume). [save] reports failure so the caller can
 * abort rather than mutate un-recoverably; [load]/[clear] swallow-and-log — a read/delete
 * failure must never crash a calibration.
 *
 * Moved here from QuantumCast 2026-08-28 - app-agnostic, so it belongs with the interface.
 */
class FileCalibrationJournal(context: Context) : CalibrationJournal {

    private val file = File(context.filesDir, "calibration_journal")

    override fun save(originals: Map<String, ClientSnapshot>): Boolean = try {
        // writeText flushes to the OS before returning, so the bytes survive an app kill
        // (our actual threat — ColorOS terminating the process, not power loss).
        file.writeText(
            originals.entries.joinToString("\n") { (id, s) ->
                "$id=${s.latencyMs},${s.volumePercent},${s.volumeMuted}"
            },
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "save failed: ${e.message}")
        false
    }

    override fun load(): Map<String, ClientSnapshot>? {
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .associate {
                    // ids never contain '='; split on the last one to be safe regardless.
                    val i = it.lastIndexOf('=')
                    val parts = it.substring(i + 1).trim().split(',')
                    it.substring(0, i) to ClientSnapshot(
                        latencyMs = parts[0].toInt(),
                        volumePercent = parts[1].toInt(),
                        volumeMuted = parts[2].toBoolean(),
                    )
                }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "load failed, discarding journal: ${e.message}")
            clear()
            null
        }
    }

    override fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CalibrationJournal"
    }
}
