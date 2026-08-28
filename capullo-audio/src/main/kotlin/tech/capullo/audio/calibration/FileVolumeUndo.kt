package tech.capullo.audio.calibration

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File-backed [VolumeUndo] in the app's private storage: one `id=percent` line per client.
 *
 * Deliberately a SEPARATE file from the calibration journal, not another field in it. The journal is
 * deleted the moment a run finishes, because a completed run's writes are intentional and blindly
 * reverting them would undo the calibration itself. This record has to survive exactly that moment —
 * it exists so an intentional write can still be taken back afterwards — so sharing storage with the
 * journal would mean sharing its lifetime and losing the record every time.
 *
 * Every failure is swallowed and logged. A storage problem must not crash a calibration, and it must
 * not stop the balance either: the worst case is losing the undo, which is where the feature was
 * before this existed.
 *
 * Moved here from QuantumCast 2026-08-28 - app-agnostic, so it belongs with the interface.
 */
class FileVolumeUndo(context: Context) : VolumeUndo {

    private val file = File(context.filesDir, "calibration_volume_undo")

    override fun save(previous: Map<String, Int>) {
        try {
            file.writeText(previous.entries.joinToString("\n") { (id, p) -> "$id=$p" })
        } catch (e: Exception) {
            Log.e(TAG, "save failed, this balance will not be undoable: ${e.message}")
        }
    }

    override fun load(): Map<String, Int>? {
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .associate {
                    // Client ids never contain '=', but split on the last one regardless.
                    val i = it.lastIndexOf('=')
                    it.substring(0, i) to it.substring(i + 1).trim().toInt()
                }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // A record that cannot be parsed cannot be applied, and keeping it would offer the user
            // an undo that does nothing. Drop it.
            Log.e(TAG, "load failed, discarding undo record: ${e.message}")
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
        private const val TAG = "CalibrationUndo"
    }
}
