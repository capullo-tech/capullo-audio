package tech.capullo.audio.calibration

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/**
 * Does regularized deconvolution rescue the level estimator on self-correlated program material?
 *
 * The un-whitened estimator fails there for a specific, measured reason: it returns the impulse
 * response convolved with the program's own autocorrelation, so the value at one speaker's arrival is
 * mostly the OTHER speaker's sidelobes, which do not move when the first speaker's gain moves. On
 * tonal material a commanded 6 dB change moved the reading 0.06 dB; on hardware a commanded 12 dB
 * change produced 4.3 dB (`FINDINGS-level-sensitivity-2026-08-06.md`).
 *
 * `R/(|Ref|^2 + lambda)` divides that autocorrelation back out. These tests are the GO/NO-GO for the
 * whole feature, and they cost no rig time: if sensitivity does not come back here, nothing on the
 * hardware will bring it back either.
 */
class WienerLevelTest {

    private fun broadband(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(n)
        var s = 0.0
        for (i in 0 until n) {
            s = 0.85 * s + (rnd.nextDouble() * 2 - 1)
            x[i] = s.toFloat()
        }
        return x
    }

    /** Sustained harmonic partials with slow tremolo: the material the estimator dies on. */
    private fun tonal(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(n)
        val phases = DoubleArray(5) { rnd.nextDouble() * 6.283 }
        val freqs = doubleArrayOf(110.0, 165.0, 220.0, 330.0, 440.0)
        for (i in 0 until n) {
            val t = i / 1000.0
            var v = 0.0
            for (k in freqs.indices) v += kotlin.math.sin(2 * Math.PI * freqs[k] * t + phases[k])
            x[i] = (v * (1 + 0.3 * kotlin.math.sin(2 * Math.PI * 0.7 * t))).toFloat()
        }
        return x
    }

    /**
     * BAND-LIMITED material: broadband, then hard low-passed so the top of the spectrum holds
     * essentially no energy. This is what the calibrator actually correlates against — the broadcast
     * PCM is lossily coded, so above the codec cutoff `|Ref|^2` is not merely small but ~zero.
     *
     * It is the case the synthetic suite was missing, and it matters because it is exactly where
     * deconvolution is ill-conditioned: in a bin with no reference energy, `R/(|Ref|^2 + lambda)`
     * reduces to `R/lambda`, so whatever noise sits there is amplified by 1/lambda. With lambda set
     * as a fraction of MEAN band power, a spectrum whose energy is concentrated at the bottom makes
     * that mean small and the amplification large.
     */
    private fun bandLimited(n: Int, seed: Int): FloatArray {
        var x = broadband(n, seed)
        // Cascaded smoothing = steep rolloff, leaving the upper spectrum effectively empty.
        repeat(6) {
            val y = FloatArray(x.size)
            for (i in x.indices) {
                val a = if (i > 0) x[i - 1] else x[i]
                val c = if (i + 1 < x.size) x[i + 1] else x[i]
                y[i] = 0.25f * a + 0.5f * x[i] + 0.25f * c
            }
            x = y
        }
        return x
    }

    /**
     * Program with NO LOW END: partials from 80 Hz up, nothing below. A real one is band-limited the
     * same way at the bottom — music has little sub-50 Hz content and a BT speaker reproduces none of
     * it — while the MIC has plenty down there that no speaker put in the room.
     */
    private fun noLowEnd(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val freqs = DoubleArray(24) { 80.0 + rnd.nextDouble() * 320.0 }
        val phases = DoubleArray(freqs.size) { rnd.nextDouble() * 6.283 }
        return FloatArray(n) { i ->
            val t = i / 1000.0
            var v = 0.0
            for (k in freqs.indices) v += kotlin.math.sin(2 * Math.PI * freqs[k] * t + phases[k])
            v.toFloat()
        }
    }

    /** [mic] plus RUMBLE the reference cannot explain: a slow drift and two sub-audio tones. */
    private fun withRumble(mic: FloatArray, level: Double, seed: Int): FloatArray {
        val rnd = Random(seed)
        var walk = 0.0
        return FloatArray(mic.size) { i ->
            val t = i / 1000.0
            walk = 0.999 * walk + rnd.nextDouble() * 2 - 1
            (mic[i] + level * (0.02 * walk + kotlin.math.sin(2 * Math.PI * 3.0 * t) +
                0.7 * kotlin.math.sin(2 * Math.PI * 11.0 * t))).toFloat()
        }
    }

    /** A 2 s bar on repeat — the pathological case, with sidelobes at the loop period. */
    private fun looped(n: Int, seed: Int): FloatArray {
        val bar = broadband(2000, seed)
        return FloatArray(n) { bar[it % bar.size] }
    }

    private fun room(
        ref: FloatArray,
        sources: List<Pair<Int, Float>>,
        noise: Double = 0.05,
        seed: Int = 1,
    ): FloatArray {
        val rnd = Random(seed)
        val mic = FloatArray(ref.size)
        for ((delay, gain) in sources) {
            for (i in delay until ref.size) mic[i] += gain * ref[i - delay]
            for ((dt, g) in listOf(7 to 0.5f, 19 to 0.35f, 34 to 0.25f, 61 to 0.15f)) {
                val d = delay + dt
                for (i in d until ref.size) mic[i] += gain * g * ref[i - d]
            }
        }
        for (i in mic.indices) mic[i] += (noise * (rnd.nextDouble() * 2 - 1)).toFloat()
        return mic
    }

    /** dB the arrival at [lagB] reads above the one at [lagA], under the chosen estimator. */
    private fun deltaDb(
        ref: FloatArray,
        mic: FloatArray,
        lagA: Int,
        lagB: Int,
        eps: Double?,
    ): Double {
        val r = if (eps == null) {
            Dsp.crossCorrelateLevel(ref, mic)
        } else {
            // These fixtures are generated at 1 sample per ms, so the "sample rate" is 1000 and the
            // 50 Hz cutoff removes the bottom 10% of the 500 Hz band — against 0.4% of the rig's
            // 6 kHz band at the decimated 12 kHz. The SAME constant is a much harsher filter here,
            // so these tables are not directly comparable to rig numbers; they are comparable to
            // each other, which is all the eps choice needs. Passing 12000 here would cut nothing at
            // all and the tests would stop exercising the path the rig uses.
            Dsp.crossCorrelateWiener(ref, mic, sampleRate = 1000, eps = eps)
        }
        val a = Dsp.levelAt(r, fs = 1000, lagMs = lagA.toDouble())
        val b = Dsp.levelAt(r, fs = 1000, lagMs = lagB.toDouble())
        return 20.0 * log10(b / a)
    }

    /** How far the reported ratio moves when the source at 1400 is attenuated by [db]. */
    private fun sensitivity(ref: FloatArray, db: Int, eps: Double?): Double {
        val base = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1400 to 1.0f), seed = 9), 1200, 1400, eps)
        val amp = Math.pow(10.0, db / 20.0).toFloat()
        val got = deltaDb(ref, room(ref, listOf(1200 to 1.0f, 1400 to amp), seed = 9), 1200, 1400, eps)
        return got - base
    }

    @Test
    fun `eps sweep - report sensitivity on every program type`() {
        // The experiment itself. Prints a table so the choice of eps is made from data rather than
        // from the literature's 0.01-0.1 hand-wave; the assertion at the end only demands that SOME
        // eps rescues the case that is currently broken.
        val programs = listOf(
            "broadband" to broadband(20_000, 2),
            "tonal" to tonal(20_000, 2),
            "looped" to looped(20_000, 2),
            "bandlimited" to bandLimited(20_000, 2),
        )
        val epsValues = listOf(null, 0.001, 0.01, 0.03, 0.1, 0.3, 1.0)
        println("sensitivity to a commanded -6 / -12 dB change (want -6.0 / -12.0)")
        println("%-11s %-8s %-9s %-9s".format("program", "eps", "-6dB", "-12dB"))
        var tonalRescued = false
        var loopedRescued = false
        for ((name, ref) in programs) {
            for (eps in epsValues) {
                val m6 = sensitivity(ref, -6, eps)
                val m12 = sensitivity(ref, -12, eps)
                println(
                    "%-11s %-8s %-9s %-9s".format(
                        name, eps?.toString() ?: "none(raw)",
                        "%+.2f".format(m6), "%+.2f".format(m12),
                    ),
                )
                // Also report how much of the deconvolution goes NEGATIVE. A level array that is
                // half negative is not a level array: on the rig, captures with >50% negative bins
                // disagreed by 20 dB at identical gains. Stability, not just sensitivity.
                val ok = abs(m6 - (-6.0)) < 3.0 && abs(m12 - (-12.0)) < 3.0
                if (ok && name == "tonal") tonalRescued = true
                if (ok && name == "looped") loopedRescued = true
            }
        }
        println("tonal rescued=$tonalRescued  looped rescued=$loopedRescued")
        assertTrue(
            "no eps restored sensitivity on tonal material - the feature cannot be saved this way",
            tonalRescued,
        )
    }

    @Test
    fun `SNR is the variable that decides eps, and the rig sits at the hard end`() {
        // ================== WHY THE HARDWARE DISAGREED WITH THE SYNTHETIC RESULT ==================
        // Every other test here uses noise=0.05 against unit-amplitude sources: roughly +30 dB SNR.
        // The rig is nowhere near that. Measured (finding 3): the speaker's contribution to the mic
        // is about equal to the room noise, i.e. ~0 dB SNR - a commanded -12.4 dB moved the mic RMS
        // by only -2.2 dB, which solves to S ~= N.
        //
        // This matters because lambda in a Wiener deconvolution IS the noise-to-signal ratio. Setting
        // it to 0.01 asserts the mic is 20 dB cleaner than the reference; at 0 dB SNR that assertion
        // is wrong by two orders of magnitude, and the deconvolution amplifies noise instead of
        // sharpening signal. On the rig that showed up as level arrays more than half NEGATIVE and
        // two identical-gain captures disagreeing by 20 dB.
        //
        // The table below is the honest picture: how much sensitivity survives at each SNR, and which
        // eps is best there. Read it before choosing eps for anything.
        println("sensitivity to a commanded -12 dB change, by SNR and eps (want -12.0)")
        print("%-9s".format("noise"))
        val epsValues = listOf(null, 0.01, 0.1, 0.3, 1.0, 3.0)
        for (e in epsValues) print("%-9s".format(e?.toString() ?: "raw"))
        println()
        val ref = tonal(20_000, 2)
        for (noise in listOf(0.05, 0.5, 1.5, 3.5)) {
            print("%-9s".format(noise.toString()))
            for (eps in epsValues) {
                val base = deltaDb(
                    ref, room(ref, listOf(1200 to 1.0f, 1400 to 1.0f), noise = noise, seed = 9),
                    1200, 1400, eps,
                )
                val got = deltaDb(
                    ref, room(ref, listOf(1200 to 1.0f, 1400 to 0.2512f), noise = noise, seed = 9),
                    1200, 1400, eps,
                )
                print("%-9s".format("%+.2f".format(got - base)))
            }
            println()
        }
        // No assertion: this test exists to publish the trade-off, not to gate on it.
    }

    @Test
    fun `the chosen eps recovers known gain ratios on every program type`() {
        // Sensitivity is necessary but not sufficient: the estimator also has to report the RIGHT
        // ratio, not merely a responsive one. Truth 0.50 / 0.25 / 0.10 at 200 ms separation.
        val eps = Dsp.WIENER_EPS
        for ((name, ref) in listOf(
            "broadband" to broadband(20_000, 5),
            "tonal" to tonal(20_000, 5),
            "looped" to looped(20_000, 5),
        )) {
            for (truth in listOf(0.50, 0.25, 0.10)) {
                val mic = room(ref, listOf(1200 to 1.0f, 1400 to truth.toFloat()), seed = 3)
                val got = Math.pow(10.0, deltaDb(ref, mic, 1200, 1400, eps) / 20.0)
                val errDb = 20 * log10(got / truth)
                assertTrue(
                    "$name truth $truth read %.3f (%.1f dB off)".format(got, errDb),
                    abs(errDb) < 3.0,
                )
            }
        }
    }

    @Test
    fun `separation still matters - the 30ms floor survives deconvolution`() {
        // The old estimator needed ~30 ms of separation, which is why levels are only ever harvested
        // from the PROBED capture. The hope was that deconvolution would dissolve that and make the
        // baseline harvestable too (a free independent check on attribution). MEASURED: it does not.
        //
        // Both estimators, so the comparison is visible rather than asserted from memory. The reason
        // the floor survives: `levelAt` reads a max over +/-3 ms, so at 10 ms separation the two read
        // windows nearly touch and each collects part of the other's arrival. That is a property of
        // the READOUT, not of the correlation, and it is why deconvolution cannot fix it.
        val ref = tonal(20_000, 7)
        println("truth 0.25 recovered vs arrival separation (-12.0 dB is exact)")
        println("%-9s %-12s %-12s".format("sep(ms)", "raw", "wiener"))
        val errors = mutableMapOf<Int, Double>()
        for (sep in listOf(10, 30, 90, 200)) {
            val mic = room(ref, listOf(1200 to 1.0f, (1200 + sep) to 0.25f), seed = 3)
            val raw = deltaDb(ref, mic, 1200, 1200 + sep, null)
            val wie = deltaDb(ref, mic, 1200, 1200 + sep, Dsp.WIENER_EPS)
            errors[sep] = wie - (-12.041)
            println("%-9d %-12s %-12s".format(sep, "%+.2f".format(raw), "%+.2f".format(wie)))
        }
        // From 30 ms on, which is what the probe offsets already guarantee, the estimator is accurate.
        for (sep in listOf(30, 90, 200)) {
            assertTrue(
                "at ${sep}ms the error is %.1f dB".format(errors.getValue(sep)),
                abs(errors.getValue(sep)) < 4.0,
            )
        }
        // Pinned as a known limit: 10 ms is still not usable, so the baseline capture stays
        // un-harvestable and the probe stays necessary.
        assertTrue(
            "10ms separation became accurate (%.1f dB) - the baseline could now be harvested, update the harvest policy"
                .format(errors.getValue(10)),
            abs(errors.getValue(10)) >= 4.0,
        )
    }

    @Test
    fun `mic rumble becomes a baseline unless the low bins are dropped`() {
        // FOUND ON A REAL CAPTURE, not in simulation. dumps/pcm-20260806-180313 deconvolved with no
        // cutoff is NEGATIVE across a 470 ms span - which an impulse response cannot be - and its
        // reported arrivals were the least-negative points on that baseline, 130 ms away from where
        // PHAT independently put them. With the cutoff the top peak lands at 1178.0 ms against
        // PHAT's 1186.8, and the two estimators agree on the whole cluster.
        //
        // The mechanism: below the program's low-frequency edge, |Ref|^2 is ~0, so the denominator is
        // LAMBDA ALONE and the bin passes as R/lambda. The regularizer stops the division exploding
        // but not amplifying, and what it amplifies is mic rumble no speaker produced.
        // The arrivals are DELIBERATELY tiny against the rumble. On the rig the mic sits ~37 dB below
        // the reference, and an earlier version of this fixture (arrivals at 1.0, rumble at 3.0)
        // could not show the defect at all — the same reason the offline synthetic pair kept passing
        // while the real capture failed. A defect that only appears at realistic SNR needs a fixture
        // at realistic SNR.
        val ref = noLowEnd(20_000, 11)
        val clean = room(ref, listOf(1200 to 0.02f, 1400 to 0.005f), noise = 0.0005, seed = 3)
        val mic = withRumble(clean, level = 2.0, seed = 5)

        val cut = Dsp.crossCorrelateWiener(ref, mic, sampleRate = 1000)
        val uncut = Dsp.crossCorrelateWiener(ref, mic, sampleRate = 1000, hpHz = 0.0)

        // A baseline shows up as a profile whose MEAN over a wide quiet span is comparable to its
        // arrivals, instead of ~0. That is the defect itself, before any question of readout.
        fun meanOver(r: DoubleArray, fromMs: Int, toMs: Int) =
            (fromMs until toMs).sumOf { r[it] } / (toMs - fromMs)
        val baseUncut = abs(meanOver(uncut, 1500, 2500))
        val baseCut = abs(meanOver(cut, 1500, 2500))
        assertTrue(
            "the cutoff left a baseline: %.2e vs %.2e uncut".format(baseCut, baseUncut),
            baseCut < 0.05 * baseUncut,
        )

        // And the consequence that reaches the balance: levelAt reads a max over +/-3 ms, so the
        // offset survives the readout, is added to BOTH clients, and inflates the weaker toward the
        // stronger. Truth here is -12.04 dB.
        val ratioCut = 20 * log10(
            Dsp.levelAt(cut, fs = 1000, lagMs = 1400.0) / Dsp.levelAt(cut, fs = 1000, lagMs = 1200.0),
        )
        val ratioUncut = 20 * log10(
            Dsp.levelAt(uncut, fs = 1000, lagMs = 1400.0) / Dsp.levelAt(uncut, fs = 1000, lagMs = 1200.0),
        )
        println("rumble: truth -12.04 dB, uncut %+.2f dB, cut %+.2f dB".format(ratioUncut, ratioCut))
        assertTrue("with the cutoff the ratio is %.2f dB, truth -12.04".format(ratioCut), abs(ratioCut + 12.04) < 3.0)
        // NaN counts as failing, and is the usual outcome: the uncut profile goes NEGATIVE at the
        // arrival, so the ratio is the log of a negative number. A profile that cannot even be read
        // is exactly what the real capture showed.
        assertTrue(
            "the uncut estimator survived the rumble (%.2f dB) - the fixture is too weak to show the defect"
                .format(ratioUncut),
            !(abs(ratioUncut + 12.04) < 3.0),
        )
    }
}
