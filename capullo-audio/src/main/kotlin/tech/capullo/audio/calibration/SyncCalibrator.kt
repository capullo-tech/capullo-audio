package tech.capullo.audio.calibration

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.audio.snapcast.SnapcastControlClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mic-based automatic residual sync calibration.
 *
 * Physics: the native snapclients already track the OS-reported output latency; what
 * remains per sink is the reported-vs-acoustic error, invisible to every digital signal
 * (see FINDINGS-fable-residual-2026-07-21.md). This calibrator measures it acoustically:
 * the device running the snapserver records the room while music plays, cross-correlates
 * the mic against the exact PCM it broadcast ([ReferencePcmRing] tee tap), and reads each
 * speaker's total delay as a correlation peak. Peak spacing = pair misalignment.
 *
 * Attribution (which peak belongs to which client) is resolved with a probe: nudge the
 * target client by [PROBE_MS] (negative latency = plays later = its peak moves later by
 * exactly that much — sign convention confirmed on-rig) and see which peak moved. The
 * correction is then applied through Client.SetLatency (server-persisted) and verified
 * with a final measurement. A run never trusts a single measurement: no probe movement →
 * no changes → fail safe with everything restored.
 *
 * For 3+ clients, each non-reference client is calibrated pairwise against the same
 * reference while all other clients are temporarily muted, which reduces N speakers to
 * N−1 independent two-speaker problems.
 */
class SyncCalibrator(
    /** Arms/disarms the reference tap on the CURRENT broadcast sink. Must survive an
     *  engine restart mid-run (a new FifoAudioBufferSink must inherit the armed ring),
     *  so the host owns the wiring rather than this class holding a sink reference. */
    private val tapArm: (ReferencePcmRing?) -> Unit,
    private val mic: MicCapture,
    private val control: SnapcastControlClient,
) {

    data class CalClient(
        val id: String,
        val name: String,
        val latencyMs: Int,
        val volumePercent: Int,
        val muted: Boolean,
    )

    sealed class State {
        data object Idle : State()
        data class Running(val message: String) : State()
        data class Done(val summary: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    /**
     * Calibrate [clients] (connected snapclients with audible sinks; 2+ entries, first
     * entry = reference whose latency is never changed). Returns true if every pair
     * ended calibrated (or already aligned).
     */
    suspend fun calibrate(clients: List<CalClient>): Boolean {
        if (clients.size < 2) {
            _state.value = State.Failed("need at least 2 connected clients, got ${clients.size}")
            return false
        }
        val reference = clients.first()
        val targets = clients.drop(1)
        val ring = ReferencePcmRing()
        tapArm(ring)
        try {
            // Let the ring cover more than one full capture before the first measurement.
            progress("priming reference ring (${RING_PRIME_MS / 1000}s)…")
            delay(RING_PRIME_MS)
            val results = mutableListOf<String>()
            var successes = 0
            for (target in targets) {
                val others = targets.filter { it.id != target.id }
                try {
                    if (others.isNotEmpty()) {
                        progress("muting ${others.size} other client(s) for pair isolation")
                        others.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }
                        delay(SETTLE_MS)
                    }
                    // A failed pair (e.g. a silent/remote web client that never produces a
                    // peak) is restored + reported but must not abort the remaining pairs.
                    val outcome = calibratePair(ring, reference, target)
                    if (outcome != null) successes++
                    results += outcome ?: "${target.name}: failed (see log)"
                } finally {
                    others.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
                }
            }
            val summary = results.joinToString("; ")
            _state.value = if (successes > 0) State.Done(summary) else State.Failed(summary)
            return successes == targets.size
        } finally {
            tapArm(null)
            if (_state.value is State.Running) _state.value = State.Failed("aborted")
        }
    }

    /** Returns a human summary on success, null on failure (target latency restored). */
    private suspend fun calibratePair(
        ring: ReferencePcmRing,
        reference: CalClient,
        target: CalClient,
    ): String? {
        progress("measuring baseline (${reference.name} vs ${target.name})…")
        val baseline = measure(ring) ?: return failPair(target, "baseline measurement failed")

        progress("probing ${target.name} by ${PROBE_MS}ms…")
        control.sendSetLatency(target.id, target.latencyMs - PROBE_MS)
        delay(SETTLE_MS)
        val probed = measure(ring) ?: return failPair(target, "probe measurement failed")

        // The target's peak is the probed peak that sits PROBE_MS later than a baseline
        // peak; the reference's is a peak that stayed put.
        val targetProbed = probed.firstOrNull { p ->
            baseline.any { abs(p.lagMs - it.lagMs - PROBE_MS) < MATCH_TOL_MS }
        } ?: return failPair(target, "no peak moved by the probe — measurement not trusted")
        val refPeak = probed.firstOrNull { p ->
            baseline.any { abs(p.lagMs - it.lagMs) < MATCH_TOL_MS } &&
                abs(p.lagMs - targetProbed.lagMs) > MATCH_TOL_MS
        } ?: return failPair(target, "reference peak not stable across probe")

        val targetLag = targetProbed.lagMs - PROBE_MS // un-shift the probe
        val deltaMs = (targetLag - refPeak.lagMs).roundToInt()
        val newLatency = target.latencyMs + deltaMs
        Log.i(
            TAG,
            "pair ${reference.name}/${target.name}: refLag=${"%.1f".format(refPeak.lagMs)} " +
                "targetLag=${"%.1f".format(targetLag)} delta=${deltaMs}ms " +
                "latency ${target.latencyMs} -> $newLatency",
        )

        progress("applying ${newLatency}ms to ${target.name}, verifying…")
        control.sendSetLatency(target.id, newLatency)
        delay(SETTLE_MS)
        val verify = measure(ring) ?: return failPair(target, "verify measurement failed")
        val spread = pairSpreadMs(verify)
        if (spread > VERIFY_TOL_MS) {
            return failPair(target, "verify spread ${spread.roundToInt()}ms > ${VERIFY_TOL_MS}ms")
        }
        return "${target.name}: ${if (deltaMs == 0) "already aligned" else "trim ${deltaMs}ms"} " +
            "(latency $newLatency, residual spread ${spread.roundToInt()}ms)"
    }

    /** Distance between the two strongest peaks, or 0.0 when they merged into one. */
    private fun pairSpreadMs(peaks: List<Dsp.Peak>): Double {
        val salient = peaks.filter { it.z >= MIN_PEAK_Z }
        return if (salient.size < 2) 0.0 else abs(salient[0].lagMs - salient[1].lagMs)
    }

    private suspend fun measure(ring: ReferencePcmRing): List<Dsp.Peak>? {
        repeat(2) { attempt ->
            val capture = mic.record(CAPTURE_MS)
            if (capture != null) {
                val snapshot = ring.snapshot()
                val peaks = DelayMeasurement.estimateSpeakerDelays(snapshot, capture)
                Log.i(TAG, "peaks: " + peaks.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
                val salient = peaks.filter { it.z >= MIN_PEAK_Z }
                if (salient.isNotEmpty()) return salient
            }
            // A transient (engine restart, silence gap) can void one measurement; let the
            // ring refill with fresh audio before the single retry.
            if (attempt == 0) {
                progress("measurement inconclusive, retrying…")
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    /** Restores the target's latency and reports the failure; terminal state is decided by
     *  the pair loop (other targets may still succeed). */
    private suspend fun failPair(target: CalClient, reason: String): String? {
        Log.w(TAG, "pair ${target.name} failed: $reason — restoring latency ${target.latencyMs}")
        control.sendSetLatency(target.id, target.latencyMs)
        progress("${target.name}: $reason")
        return null
    }

    private fun progress(message: String) {
        Log.i(TAG, message)
        _state.value = State.Running(message)
    }

    companion object {
        private const val TAG = "SyncCalibrator"
        const val CAPTURE_MS = 12_000
        const val PROBE_MS = 60
        private const val SETTLE_MS = 7_000L
        private const val RING_PRIME_MS = 16_000L
        private const val RETRY_BACKOFF_MS = 8_000L
        private const val MATCH_TOL_MS = 15.0
        private const val VERIFY_TOL_MS = 10.0
        private const val MIN_PEAK_Z = 9.0
    }
}
