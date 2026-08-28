package tech.capullo.audio.calibration

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only [CalibrationHistory] as a TSV in app storage: `iso8601\tclientId\tdelta\tlatency`.
 * Data for a future per-sink damping policy; never throws into a run.
 *
 * Moved here from QuantumCast 2026-08-28: it names no app type, so every app was going to copy it
 * verbatim to get calibration. The same is true of the sibling implementations beside it.
 */
class FileCalibrationHistory(context: Context) : CalibrationHistory {

    private val file = File(context.filesDir, "calibration_history.tsv")
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun record(clientId: String, deltaMs: Int, newLatencyMs: Int) {
        try {
            file.appendText("${stamp.format(Date())}\t$clientId\t$deltaMs\t$newLatencyMs\n")
        } catch (e: Exception) {
            Log.e(TAG, "history append failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CalibrationHistory"
    }
}
