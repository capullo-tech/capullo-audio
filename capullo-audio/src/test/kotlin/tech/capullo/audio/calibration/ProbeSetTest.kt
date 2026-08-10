package tech.capullo.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the probe offsets against the two constraints they answer to, because the sets look like
 * arbitrary numbers and the next person to widen one will not have the derivation to hand.
 *
 * The constraints are DIFFERENT and apply to DIFFERENT quantities, which is the thing that made the
 * three-client case look impossible:
 *
 *  - ATTRIBUTION needs every value in (offsets ∪ pairwise differences) distinct and separated by
 *    more than the room's reflection spread, so no speaker's reflection can sit where another
 *    speaker's probe shift is expected. It binds the whole union.
 *  - LEVEL needs each compared PAIR of arrivals separated by ~380 ms, so the quieter one clears the
 *    louder one's reverb tail. It binds pairwise differences only.
 *
 * Read "a Sidon set with 380 ms gaps" as one requirement and three clients need a ~1000 ms member;
 * separate them and three clients need 940 ms while two need only 470.
 */
class ProbeSetTest {

    /** Rig-measured spread of one source's arrivals: direct path plus its reflections. */
    private val reflectionSpreadMs = PeakAttribution.SOURCE_SPREAD_MS

    /** Separation the level readout needs, from the tail decay of ~5.4 dB/100 ms: at this distance
     *  a quiet arrival sits 13-15 dB above the loud speaker's tail (FINDINGS §20). */
    private val tailClearanceMs = 380

    private fun pairwiseDiffs(set: List<Int>): List<Int> =
        set.sorted().let { s -> s.indices.flatMap { i -> (i + 1 until s.size).map { j -> s[j] - s[i] } } }

    /** Sidon (B_2): all pairwise differences distinct, so no shifted grid masquerades as another. */
    private fun isSidon(set: List<Int>): Boolean =
        pairwiseDiffs(set).let { it.size == it.toSet().size }

    private fun union(set: List<Int>): List<Int> = (set + pairwiseDiffs(set)).distinct().sorted()

    @Test
    fun `the sync probe set is Sidon with gaps wider than the room's reflection spread`() {
        val set = SyncCalibrator.PROBE_SET_MS
        assertTrue("must be Sidon, got ${pairwiseDiffs(set)}", isSidon(set))
        val u = union(set)
        assertTrue(
            "the smallest shift ${u.first()}ms must clear the ${reflectionSpreadMs}ms spread, " +
                "or a source's own reflection lands on it",
            u.first() > reflectionSpreadMs,
        )
        u.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "union gap ${b - a}ms (between $a and $b) must exceed the ${reflectionSpreadMs}ms " +
                    "spread; union=$u",
                b - a > reflectionSpreadMs,
            )
        }
    }

    @Test
    fun `the sync set is deliberately too narrow for levels`() {
        // Documents intent. Its 90 ms differences are ample for attribution and nowhere near the
        // ~380 ms the level readout needs, which is exactly why a separate set exists. If someone
        // widens PROBE_SET_MS enough to satisfy levels, this test failing is the prompt to delete
        // the second set rather than to keep paying for both.
        assertTrue(
            "sync differences ${pairwiseDiffs(SyncCalibrator.PROBE_SET_MS)} should NOT reach " +
                "${tailClearanceMs}ms",
            pairwiseDiffs(SyncCalibrator.PROBE_SET_MS).any { it < tailClearanceMs },
        )
    }

    @Test
    fun `the level probe set satisfies BOTH constraints`() {
        val set = SyncCalibrator.LEVEL_PROBE_SET_MS
        assertTrue("must still be Sidon for attribution, got ${pairwiseDiffs(set)}", isSidon(set))
        union(set).zipWithNext().forEach { (a, b) ->
            assertTrue("union gap ${b - a}ms must exceed ${reflectionSpreadMs}ms", b - a > reflectionSpreadMs)
        }
        pairwiseDiffs(set).forEach {
            assertTrue(
                "every pair must be separated by at least ${tailClearanceMs}ms for the level " +
                    "readout to clear the tail, got $it",
                it >= tailClearanceMs,
            )
        }
    }

    @Test
    fun `two clients get a level set inside the verified write envelope`() {
        // The common case, and the one the rig runs. 470 ms is inside the envelope proven on
        // hardware: -380 on Guer and -479 on the ONEPLUS both read back unchanged.
        val set = SyncCalibrator.levelProbeSet(2)
        assertNotNull("two clients must be harvestable", set)
        assertEquals(listOf(90, 470), set)
        assertTrue(
            "max offset ${set!!.max()}ms must be within the verified " +
                "${SyncCalibrator.MAX_VERIFIED_PROBE_MS}ms",
            set.max() <= SyncCalibrator.MAX_VERIFIED_PROBE_MS,
        )
        assertTrue("and must clear the tail", set[1] - set[0] >= tailClearanceMs)
    }

    @Test
    fun `three clients decline rather than probe past what hardware has verified`() {
        // Three speakers need a 940 ms excursion to keep every PAIR 380 ms apart. Nothing that large
        // has ever been written to a client: the largest verified is -479 ms, and a bigger step may
        // be clamped (which reads as a measurement failure) or rebuffer the stream. Declining loses
        // the balance for that run and keeps sync, which is the right trade for an unmeasured risk.
        assertNull("three clients must decline until a 940ms probe is measured", SyncCalibrator.levelProbeSet(3))
        assertNull(SyncCalibrator.levelProbeSet(4))
        assertNull("fewer than two speakers has no ratio to measure", SyncCalibrator.levelProbeSet(1))
    }

    @Test
    fun `the declined three-client set is otherwise valid, so only the envelope blocks it`() {
        // If the excursion is ever proven on hardware, raising MAX_VERIFIED_PROBE_MS is the whole
        // change — the offsets themselves already satisfy both constraints. This pins that, so the
        // next person does not re-derive the set.
        val three = SyncCalibrator.LEVEL_PROBE_SET_MS
        assertEquals(3, three.size)
        assertTrue(isSidon(three))
        pairwiseDiffs(three).forEach { assertTrue("pair separation $it", it >= tailClearanceMs) }
        assertTrue(
            "and it is blocked ONLY by the envelope: ${three.max()}ms > " +
                "${SyncCalibrator.MAX_VERIFIED_PROBE_MS}ms",
            three.max() > SyncCalibrator.MAX_VERIFIED_PROBE_MS,
        )
    }

    @Test
    fun `a probe writes latency minus offset, so a negative-latency client pays more`() {
        // The excursion that reaches the wire is not the offset. The ONEPLUS sits at -99 ms, so a
        // 380 ms probe wrote -479 ms — which is why the envelope is stated as an absolute write and
        // not as an offset. Anything comparing an OFFSET against the envelope would understate it.
        val oneplusLatency = -99
        val offset = 380
        assertEquals(-479, oneplusLatency - offset)
        assertTrue(
            "the verified envelope must cover the WRITE, not the offset",
            kotlin.math.abs(oneplusLatency - offset) <= SyncCalibrator.MAX_VERIFIED_PROBE_MS,
        )
    }
}
