package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the level estimator and the properties the volume balance depends on.
 *
 * History, because the constraints are not obvious: level was first read off a matched peak's
 * PHAT z. That silently reported every speaker as equally loud, including one that was inaudible,
 * because findPeaks is a top-N search whose outputs are order statistics of the noise floor. The
 * replacement is an un-whitened normalized correlation, compared BETWEEN CLIENTS WITHIN ONE
 * CAPTURE, harvested from the probed capture where the probe offsets guarantee separation.
 */
class LevelAtTest {

    /** Autocorrelated, like real program material rather than white noise. */
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

    /** [sources] = (delay in samples, gain), plus optional decaying reflections. */
    private fun room(
        ref: FloatArray,
        sources: List<Pair<Int, Float>>,
        noise: Double = 0.05,
        seed: Int = 1,
        reverb: Boolean = false,
    ): FloatArray {
        val rnd = Random(seed)
        val mic = FloatArray(ref.size)
        for ((delay, gain) in sources) {
            for (i in delay until ref.size) mic[i] += gain * ref[i - delay]
            if (reverb) {
                for ((dt, g) in listOf(7 to 0.5f, 19 to 0.35f, 34 to 0.25f, 61 to 0.15f)) {
                    val d = delay + dt
                    for (i in d until ref.size) mic[i] += gain * g * ref[i - d]
                }
            }
        }
        for (i in mic.indices) mic[i] += (noise * (rnd.nextDouble() * 2 - 1)).toFloat()
        return mic
    }

    private fun ratioAt(ref: FloatArray, mic: FloatArray, lagA: Int, lagB: Int): Double {
        val r = Dsp.crossCorrelateLevel(ref, mic)
        return Dsp.levelAt(r, fs = 1000, lagMs = lagB.toDouble()) /
            Dsp.levelAt(r, fs = 1000, lagMs = lagA.toDouble())
    }

    @Test
    fun `the ratio between two separated speakers recovers their true gain ratio`() {
        // The core property. PHAT z gets this wrong by about a factor of two (0.50 -> 0.281,
        // 0.25 -> 0.130); the un-whitened normalized correlation lands on the truth.
        val n = 1 shl 16
        val ref = program(n, 2)
        for (truth in listOf(0.5f, 0.25f, 0.1f)) {
            val mic = room(ref, listOf(600 to 1.0f, 900 to truth), seed = 7)
            val got = ratioAt(ref, mic, 600, 900)
            assertEquals("true ratio $truth", truth.toDouble(), got, 0.05)
        }
    }

    @Test
    fun `it still works with room reflections at the probe separation`() {
        // 90 ms is the smallest probe offset, and reflections trail each direct path.
        val n = 1 shl 16
        val ref = program(n, 22)
        for (truth in listOf(0.5f, 0.25f, 0.1f)) {
            val mic = room(ref, listOf(600 to 1.0f, 690 to truth), seed = 13, reverb = true)
            assertEquals(truth.toDouble(), ratioAt(ref, mic, 600, 690), 0.06)
        }
    }

    @Test
    fun `a speaker that is silent reads as absent, not as level with the reference`() {
        // THE RIG FAILURE this estimator exists to fix: an inaudible speaker was reported at 0.93
        // of the reference and the room called "already even".
        val n = 1 shl 16
        val ref = program(n, 23)
        val mic = room(ref, listOf(600 to 1.0f, 690 to 0.0f), seed = 15, reverb = true)
        val got = ratioAt(ref, mic, 600, 690)
        assertTrue("a silent speaker must read near zero, got $got", got < 0.15)
    }

    @Test
    fun `separation below about thirty milliseconds is not trustworthy`() {
        // Why levels are harvested from the PROBED capture only. At the speakers' natural arrivals
        // they can be a millisecond apart, and then the estimator reports them as near-equal
        // however different they really are. The probe offsets (90 ms minimum) clear this.
        val n = 1 shl 16
        val ref = program(n, 21)
        val truth = 0.25
        val tooClose = ratioAt(ref, room(ref, listOf(600 to 1.0f, 601 to 0.25f), seed = 11), 600, 601)
        val separated = ratioAt(ref, room(ref, listOf(600 to 1.0f, 690 to 0.25f), seed = 11), 600, 690)
        assertTrue(
            "1 ms apart must read far too high (got $tooClose)",
            tooClose > truth * 2,
        )
        assertEquals("90 ms apart must be accurate", truth, separated, 0.05)
    }

    @Test
    fun `the ratio is stable across captures`() {
        // Program material and noise change every capture; the ratio must not.
        val n = 1 shl 16
        val ratios = (1..6).map { seed ->
            val ref = program(n, seed * 17)
            ratioAt(ref, room(ref, listOf(600 to 1.0f, 690 to 0.4f), seed = seed * 29, reverb = true), 600, 690)
        }
        val spread = ratios.max() / ratios.min()
        assertTrue("ratio should be stable across captures, spread was $spread", spread < 1.25)
    }

    @Test
    fun `a raw value on its own says nothing about gain`() {
        // Guards the invariant that makes within-capture normalisation mandatory: with one speaker
        // alone the value is nearly independent of its gain, because that speaker IS the mic
        // signal. Anyone tempted to compare raw values across captures should read this.
        val n = 1 shl 16
        val ref = program(n, 1)
        fun solo(gain: Float): Double {
            val r = Dsp.crossCorrelateLevel(ref, room(ref, listOf(600 to gain), seed = 42))
            return Dsp.levelAt(r, fs = 1000, lagMs = 600.0)
        }
        val full = solo(1.0f)
        val quarter = solo(0.25f)
        assertTrue(
            "solo values must NOT be read as levels (full=$full quarter=$quarter)",
            quarter / full > 0.9,
        )
    }

    @Test
    fun `levelAt finds a peak a bin or two off the requested lag`() {
        val n = 1 shl 16
        val ref = program(n, 8)
        val r = Dsp.crossCorrelateLevel(ref, room(ref, listOf(600 to 1.0f), seed = 4))
        assertEquals(
            Dsp.levelAt(r, fs = 1000, lagMs = 600.0),
            Dsp.levelAt(r, fs = 1000, lagMs = 602.0),
            1e-12,
        )
    }

    @Test
    fun `top-N peak heights stay close together even when nothing is playing`() {
        // The original defect, kept as a measurement: this is why a peak's z is never a level.
        val n = 1 shl 15
        val ratios = (0 until 8).map { seed ->
            val peaks = Dsp.findPeaks(
                Dsp.gccPhat(program(n, seed), program(n, seed + 500)),
                fs = 1000, loMs = 0, hiMs = 20_000, count = 4,
            )
            peaks[1].z / peaks[0].z
        }
        assertTrue("pure-noise peaks sit near each other: ${ratios.average()}", ratios.average() > 0.7)
    }
}
