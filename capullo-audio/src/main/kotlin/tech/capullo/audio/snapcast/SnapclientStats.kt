package tech.capullo.audio.snapcast

/**
 * One per-second sync-stats sample from the native snapclient, parsed from its stdout line
 * (emitted under `--logfilter *:info,Stats:debug`, see `client/stream.cpp` in the snapcast fork):
 *
 * ```
 * [Debug] (Stats) Chunk: <age> <miniMedian> <shortMedian> <median> <fill> <dacMs> <frameDelta>
 * ```
 *
 * Columns 1-4 arrive in 0.1 ms units and are converted to ms here. Positive error = playing late
 * relative to server time. The error is measured against the player-reported DAC time, so it reads
 * ~0 whenever snapclient believes itself synced — any reported-vs-acoustic latency gap is invisible
 * to it. Use the medians to detect non-convergence after route/codec transitions, and
 * [reportedOutputLatencyMs] to see what the player thinks the output (e.g. BT A2DP) latency is.
 */
data class SnapclientStats(
    /** Sync error of the latest chunk, ms. */
    val chunkErrorMs: Double,
    /** Median sync error over the last 20 samples (sub-second window), ms. */
    val miniMedianErrorMs: Double,
    /** Median sync error over the last 100 samples (~seconds), ms. */
    val shortMedianErrorMs: Double,
    /** Median sync error over the last 500 samples (~tens of seconds), ms. */
    val medianErrorMs: Double,
    /** Fill count of the 500-sample stats window (maxes out at 500). */
    val statsWindowFill: Int,
    /** Player-reported output (DAC) latency, ms — on A2DP this is the BT stack's estimate. */
    val reportedOutputLatencyMs: Double,
    /** Net frames inserted/dropped for soft-sync since the previous sample. */
    val frameDelta: Int,
) {
    companion object {
        private const val MARKER = "(Stats) Chunk: "

        /** Parses a raw snapclient stdout line; null if it isn't a well-formed Stats line. */
        fun parse(line: String): SnapclientStats? {
            val start = line.indexOf(MARKER)
            if (start < 0) return null
            val fields = line.substring(start + MARKER.length).trim().split(WHITESPACE)
            if (fields.size != 7) return null
            val values = fields.map { it.toDoubleOrNull() ?: return null }
            return SnapclientStats(
                chunkErrorMs = values[0] / 10.0,
                miniMedianErrorMs = values[1] / 10.0,
                shortMedianErrorMs = values[2] / 10.0,
                medianErrorMs = values[3] / 10.0,
                statsWindowFill = values[4].toInt(),
                reportedOutputLatencyMs = values[5],
                frameDelta = values[6].toInt(),
            )
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
