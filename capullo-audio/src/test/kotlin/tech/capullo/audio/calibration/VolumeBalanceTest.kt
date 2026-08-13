package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mic-referenced volume balance: even levels AT THE MIC (not equal numbers), ratios rather
 * than absolutes, and headroom left above the loudest so a later global change still works.
 */
class VolumeBalanceTest {

    private fun c(id: String, gain: Int, level: Double?) = VolumeBalance.Client(id, gain, level)

    @Test
    fun `the far speaker ends up louder than the near one`() {
        // Both at the same gain, but the far speaker arrives at the mic 3x quieter. Balancing must
        // give it MORE gain than the near one - that is the whole point, and "same number on every
        // client" would be the wrong answer.
        val g = VolumeBalance.computeGains(
            listOf(c("near", 80, 30.0), c("far", 80, 10.0)),
        )!!
        assertTrue("far must end louder than near (near=${g["near"]} far=${g["far"]})", g["far"]!! > g["near"]!!)
    }

    @Test
    fun `the loudest client lands on the headroom cap, never at full scale`() {
        // Pinning the loudest to 100 would eat the user's global control: they could no longer turn
        // everything up without editing clients one at a time.
        val g = VolumeBalance.computeGains(
            listOf(c("a", 100, 40.0), c("b", 100, 12.0)),
        )!!
        assertEquals(VolumeBalance.HEADROOM_PERCENT, g.values.max())
        assertTrue("nothing may exceed the ceiling", g.values.none { it > VolumeBalance.HEADROOM_PERCENT })
    }

    @Test
    fun `an already-even room is left alone`() {
        val clients = listOf(c("a", 70, 20.0), c("b", 70, 19.0))
        assertTrue(VolumeBalance.isBalanced(clients))
    }

    @Test
    fun `a lopsided room is not considered balanced`() {
        assertTrue(!VolumeBalance.isBalanced(listOf(c("a", 70, 30.0), c("b", 70, 8.0))))
    }

    @Test
    fun `an undetected client keeps its gain instead of being guessed at`() {
        // Raising a speaker nobody measured would be guesswork; the detectability boost is what
        // handles those, not the balance.
        val g = VolumeBalance.computeGains(
            listOf(c("a", 60, 25.0), c("b", 60, 10.0), c("silent", 60, null)),
        )!!
        assertTrue("the undetected client must not be given a new gain", !g.containsKey("silent"))
    }

    @Test
    fun `nothing is driven below the usable floor`() {
        // A wildly dominant near speaker must not be pushed so far down it stops contributing or
        // drops under the calibration's own detection floor.
        val g = VolumeBalance.computeGains(
            listOf(c("blaring", 100, 500.0), c("distant", 100, 5.0)),
        )!!
        assertTrue("floor respected, got ${g.values.min()}", g.values.min() >= VolumeBalance.MIN_PERCENT)
    }

    @Test
    fun `one client alone gives nothing to balance`() {
        assertNull(VolumeBalance.computeGains(listOf(c("only", 80, 20.0))))
        assertTrue(VolumeBalance.isBalanced(listOf(c("only", 80, 20.0))))
    }

    @Test
    fun `repeated passes converge rather than oscillate`() {
        // Each measurement is noisy, so the step is damped. The room is modelled through the REAL
        // snapclient curve - level is proportional to amplitude, which is what a speaker does - and
        // the imbalance must shrink monotonically instead of hunting.
        //
        // This used to model the room as gain^0.6, matching the exponent the balance itself assumed.
        // A control law graded against its own assumption cannot fail, which is how a step size that
        // was ~3x too small near the top of the range survived: the test agreed with it.
        var gains = mapOf("near" to 80, "far" to 80)
        val efficiency = mapOf("near" to 3.0, "far" to 1.0) // near is 3x more efficient at the mic
        fun measure(id: String) =
            efficiency.getValue(id) * VolumeBalance.percentToAmplitude(gains.getValue(id))
        var previousSpread = Double.MAX_VALUE
        repeat(6) {
            val spread = maxOf(measure("near"), measure("far")) / minOf(measure("near"), measure("far"))
            assertTrue("spread must not grow: $spread vs $previousSpread", spread <= previousSpread + 0.01)
            previousSpread = spread
            val next = VolumeBalance.computeGains(
                gains.map { (id, g) -> c(id, g, measure(id)) },
            ) ?: return@repeat
            gains = next
        }
        // The exact law lands on the true fixed point rather than creeping toward it, so a 9.5 dB
        // room ends genuinely even, not merely closer than it started.
        val finalSpread = maxOf(measure("near"), measure("far")) / minOf(measure("near"), measure("far"))
        assertTrue("should end even, got ratio $finalSpread from gains $gains", finalSpread < 1.05)
    }

    @Test
    fun `the step is sized by the real gain curve, not by a fitted exponent`() {
        // One client measures exactly twice the other, both at the same gain, so the correction the
        // room needs is precisely 6 dB and no group scaling is involved (the quiet one is already
        // the loudest ideal). DAMPING then takes 70% of that move.
        //
        // The old percent^0.6 law asked for 80% -> 25%, which is about 17 dB, and the answer only
        // stayed sane because MAX_CORRECTION_DB clamped it back to the 6 dB cap. So a run correcting
        // a mere 6 dB imbalance spent the ENTIRE blast-radius budget, every time. The exact law asks
        // for the 6.0 dB that is actually needed and lands short of the cap, which is what leaves the
        // cap free to do its real job of bounding a wrong measurement.
        // Both start at FULL SCALE, which is where clients now start (2026-08-11 gain-staging
        // decision): the quiet one is already at the ceiling so no group scaling is involved, and
        // the loud one simply comes down to meet it.
        val start = 100
        val g = VolumeBalance.computeGains(listOf(c("loud", start, 20.0), c("quiet", start, 10.0)))!!
        val capFloor = VolumeBalance.amplitudeToPercent(
            VolumeBalance.percentToAmplitude(start) * Math.pow(10.0, -VolumeBalance.MAX_CORRECTION_DB / 20.0),
        )
        assertEquals("the quiet client is already the target and must not move", start, g.getValue("quiet"))
        assertTrue(
            "a 6dB imbalance must not consume the whole cap (got ${g["loud"]}%, cap floor ${"%.1f".format(capFloor)}%)",
            g.getValue("loud") > capFloor + 1.0,
        )
        // 70% of a 6.02 dB move, taken on the percentage axis where the damping is applied.
        assertEquals(-4.2, movedDb(start, g.getValue("loud")), 0.3)
    }

    @Test
    fun `only the ratio between measured levels reaches the output`() {
        // The production caller does not pass raw correlation values: SyncCalibrator.reduceLevels
        // hands over levels NORMALISED so the loudest is 1.0, while these tests pass whatever scale
        // reads clearly. Both must give the same volumes, or the unit tests are pinning a function
        // the calibrator never actually calls. The new law divides `target / measuredLevel`, so the
        // scale cancels - this test is what keeps that true.
        val raw = listOf(c("a", 62, 4.7e-2), c("b", 88, 1.1e-2))
        val loudest = raw.maxOf { it.measuredLevel!! }
        val normalised = raw.map { c(it.id, it.gainPercent, it.measuredLevel!! / loudest) }
        assertEquals(
            VolumeBalance.computeGains(raw),
            VolumeBalance.computeGains(normalised),
        )
        // And an arbitrary rescale, to pin that 1.0 is not itself special.
        assertEquals(
            VolumeBalance.computeGains(raw),
            VolumeBalance.computeGains(raw.map { c(it.id, it.gainPercent, it.measuredLevel!! * 137.0) }),
        )
    }

    @Test
    fun `a change too small to hear is not written out`() {
        val current = mapOf("a" to 60, "b" to 40)
        assertTrue(!VolumeBalance.isWorthApplying(current, mapOf("a" to 61, "b" to 41)))
        assertTrue(VolumeBalance.isWorthApplying(current, mapOf("a" to 70, "b" to 40)))
    }

    /** How far [to] sits from [from] in true dB of amplitude, through the snapclient volume curve. */
    private fun movedDb(from: Int, to: Int): Double = 20.0 * Math.log10(
        VolumeBalance.percentToAmplitude(to) / VolumeBalance.percentToAmplitude(from),
    )

    @Test
    fun `the volume percentage is a base-10 exponential, not a linear amplitude`() {
        // The mapping snapclient actually applies, and the reason every dB figure in this class has to
        // go through it. Checked against the curve's own arithmetic rather than a remembered number:
        // 50% is -12.4 dB and 25% is -21.3 dB, so treating the percentage as amplitude would misstate
        // a "-6 dB" step by more than the entire pass band of the sensitivity test.
        assertEquals(1.0, VolumeBalance.percentToAmplitude(100), 1e-9)
        assertEquals(0.0, VolumeBalance.percentToAmplitude(0), 1e-9)
        assertEquals(-12.38, movedDb(100, 50), 0.05)
        assertEquals(-21.26, movedDb(100, 25), 0.05)
        // -6 dB is 74%, NOT 50%. This is the number the rig sweep has to command.
        assertEquals(-6.0, movedDb(100, 74), 0.15)
        // Round trip.
        for (p in 0..100) {
            assertEquals(
                p.toDouble(),
                VolumeBalance.amplitudeToPercent(VolumeBalance.percentToAmplitude(p)),
                1e-6,
            )
        }
    }

    @Test
    fun `no single pass moves a client further than the correction cap`() {
        // A 20x level difference asks for a correction far past the cap. The point is not that the
        // answer is wrong - it is that the estimator behind it is not proven on hardware, so one run
        // must only ever be MAX_CORRECTION_DB wrong. The correct answer arrives over several runs.
        val start = 100
        val g = VolumeBalance.computeGains(
            listOf(c("near", start, 200.0), c("far", start, 10.0)),
        )!!
        // Asserted in dB THROUGH THE CURVE. Against the raw percentages this test would pass while
        // the real move was 12 dB or more, which is exactly the error the cap exists to prevent.
        val moved = movedDb(start, g.getValue("near"))
        assertTrue(
            "near moved ${"%.1f".format(moved)}dB to ${g["near"]}%, past the ${VolumeBalance.MAX_CORRECTION_DB}dB cap",
            moved >= -VolumeBalance.MAX_CORRECTION_DB - 0.5,
        )
    }

    @Test
    fun `the cap binds on the written value, not on the value before headroom scaling`() {
        // The group scaling onto HEADROOM_PERCENT is itself a move the user hears, so a cap checked
        // before it would not bound what actually gets written. Both clients start at full scale here,
        // so scaling alone already moves everything down and the cap has to account for it.
        val start = 100
        val g = VolumeBalance.computeGains(
            listOf(c("a", start, 500.0), c("b", start, 10.0)),
        )!!
        g.forEach { (id, percent) ->
            val moved = movedDb(start, percent)
            assertTrue(
                "$id moved ${"%.1f".format(moved)}dB to $percent%, past the cap",
                moved >= -VolumeBalance.MAX_CORRECTION_DB - 0.5,
            )
        }
    }

    @Test
    fun `a correction inside the cap is applied in full`() {
        // The cap must not become a tax on every run. A modest imbalance has to pass through
        // untouched, otherwise convergence stalls short of even for no reason.
        val start = 70
        val g = VolumeBalance.computeGains(listOf(c("a", start, 22.0), c("b", start, 18.0)))!!
        val capFloor = VolumeBalance.amplitudeToPercent(
            VolumeBalance.percentToAmplitude(start) * Math.pow(10.0, -VolumeBalance.MAX_CORRECTION_DB / 20.0),
        )
        assertTrue(
            "a modest correction must not be sitting on the cap ($g, floor ${"%.1f".format(capFloor)}%)",
            g.values.none { it == capFloor.toInt() },
        )
    }
}
