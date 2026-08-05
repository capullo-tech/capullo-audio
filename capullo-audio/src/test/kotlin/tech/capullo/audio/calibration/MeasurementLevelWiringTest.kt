package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins that DelayMeasurement feeds its level array from the UN-WHITENED correlation.
 *
 * The DSP tests call Dsp.crossCorrelateLevel directly and the orchestration tests use a fake
 * measurer, so without this test the one line that chooses the estimator is untested — swapping it
 * back to gccPhat leaves the whole suite green while the balance is silently broken again.
 */
class MeasurementLevelWiringTest {

    private fun program(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(n)
        var s = 0.0
        for (i in 0 until n) {
            s = 0.85 * s + (rnd.nextDouble() * 2 - 1)
            x[i] = s.toFloat()
        }
        return x
    }

    @Test
    fun `levelAt through a real Measurement recovers the true gain ratio`() {
        val fs = 48_000
        val n = fs * 6
        val source = program(n, 3)

        // DelayMeasurement's relation: mic[k] == ref[iMicStart + k - D] for a speaker of delay D.
        // So synthesize the mic by reading the ring that far back, rather than by shifting forward.
        val micStart = fs * 2          // ring index where the capture begins (also the search span)
        val micLen = fs * 3
        val dRef = fs * 1000 / 1000    // 1000 ms
        val dTgt = fs * 1200 / 1000    // 1200 ms - 200 ms apart, like a probed capture
        val micPcm = FloatArray(micLen)
        for (k in 0 until micLen) {
            micPcm[k] = source[micStart + k - dRef] + 0.25f * source[micStart + k - dTgt]
        }
        val rnd = Random(9)
        for (i in micPcm.indices) micPcm[i] += (0.02 * (rnd.nextDouble() * 2 - 1)).toFloat()

        val ring = ReferencePcmRing.Snapshot(source, lastSampleNanos = 0L, sampleRate = fs)
        val mic = MicCapture.Capture(
            micPcm,
            firstSampleNanos = -((n - 1 - micStart).toLong() * 1_000_000_000L / fs),
            sampleRate = fs,
        )
        val m = DelayMeasurement.measure(ring, mic, peakCount = 6)
            ?: error("measurement returned null")

        // Sanity: the timing path must actually find the two speakers where they were placed.
        val lags = m.peaks.map { it.lagMs }
        assertTrue(
            "expected peaks near 1000 and 1200 ms, got $lags",
            lags.any { kotlin.math.abs(it - 1000.0) < 15 } && lags.any { kotlin.math.abs(it - 1200.0) < 15 },
        )

        val ratio = m.levelAt(1200.0) / m.levelAt(1000.0)
        // With gccPhat wired in here this lands near 0.5; the un-whitened correlation gives 0.25.
        assertEquals("true gain ratio is 0.25", 0.25, ratio, 0.06)
    }
}
