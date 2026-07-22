package tech.capullo.audio.calibration

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure matching logic for the simultaneous-probe (no-muting) calibration.
 *
 * Peaks are first collapsed into clusters (a speaker's room reflections trail its direct
 * path by a few ms — rig-observed +5…+29 ms; only the closest belong to the direct
 * cluster). Matching then runs on cluster leaders only.
 *
 * KEY DESIGN (common-mode fix): the reference is probed just like every target and
 * identified by its DISPLACEMENT, never by "the cluster that didn't move". Music
 * self-similarity produces salient peaks that are fixed in absolute lag across captures;
 * electing the reference as "the unmoved salient cluster" could pick such a ghost, which
 * biases every delta by a constant the differential verify then cannot see (it is
 * common-mode). Requiring the reference to move by its own known offset excludes ghosts.
 */
object PeakAttribution {

    /** Peaks within this of a stronger peak collapse into its cluster. */
    const val CLUSTER_MS = 8.0

    /** A probe moves a speaker's cluster without changing its salience much, so a
     *  matched pair must have comparable z on both sides. Blocks correlation sidelobes
     *  (which CAN clear the salience threshold on strongly autocorrelated program
     *  material) from stealing a probe match from a quiet speaker. */
    const val Z_RATIO_MAX = 3.0

    /** Verify consensus must land at least this many probed targets at reference+offset.
     *  Two coincident hits on a Sidon grid are far harder to fake than one, so a single
     *  spurious match cannot elect a reference. The single-target case can't reach this
     *  and is handled by the caller (v1 pair path), not here. */
    const val MIN_CONSENSUS_HITS = 2

    data class Attribution(
        /** Index into the probe-offset list this match answers (0 = reference). */
        val probeIndex: Int,
        /** The entity's arrival with no probe applied (baseline cluster leader). */
        val baselineLagMs: Double,
        /** Where it sat during the probed measurement (leader, probe still applied). */
        val probedLagMs: Double,
        /** Salience of the probed leader. */
        val z: Double,
    )

    data class Result(
        /** By probe index; null where no cluster moved by that offset. */
        val matches: List<Attribution?>,
        /** Salient baseline leaders claimed by no probe (unmoved ghosts / silent speakers). */
        val ghostLagsMs: List<Double>,
    )

    data class Confirmation(
        /** Consensus reference lag in the verify capture, null if none reached quorum. */
        val referenceLagMs: Double?,
        /** slot → matched lag, only for targets whose peak landed at ref + offset. */
        val tracked: Map<Int, Double>,
    )

    /**
     * Match cluster leaders across the baseline and probed captures. Entity i moved by
     * `probesMs[i]` (a Sidon set: offsets and their pairwise differences all distinct, so
     * no shift can masquerade as another). Assignment is greedy by probed-leader salience
     * (a direct path out-peaks its reflections) then by shift error, each cluster used at
     * most once, with a z-ratio gate so a sidelobe can't steal a real speaker's match.
     * [matchTolMs] stays under half the min pairwise offset difference and also absorbs
     * the small global clock drift between the two captures.
     *
     * Entity 0 is the reference by convention; it is matched exactly like the targets. A
     * null `matches[0]` means the reference could not be identified and the batch is
     * untrusted.
     */
    fun attribute(
        baseline: List<Dsp.Peak>,
        probed: List<Dsp.Peak>,
        probesMs: List<Int>,
        matchTolMs: Double,
    ): Result {
        val b = clusterLeaders(baseline)
        val p = clusterLeaders(probed)

        data class Cand(val k: Int, val pi: Int, val bi: Int, val err: Double)
        val cands = mutableListOf<Cand>()
        for (k in probesMs.indices) for (pi in p.indices) for (bi in b.indices) {
            val err = abs((p[pi].lagMs - b[bi].lagMs) - probesMs[k])
            if (err < matchTolMs && zComparable(p[pi], b[bi])) cands += Cand(k, pi, bi, err)
        }
        cands.sortWith(compareByDescending<Cand> { p[it.pi].z }.thenBy { it.err })

        val usedP = BooleanArray(p.size)
        val usedB = BooleanArray(b.size)
        val out = arrayOfNulls<Attribution>(probesMs.size)
        for (c in cands) {
            if (out[c.k] != null || usedP[c.pi] || usedB[c.bi]) continue
            out[c.k] = Attribution(c.k, b[c.bi].lagMs, p[c.pi].lagMs, p[c.pi].z)
            usedP[c.pi] = true
            usedB[c.bi] = true
        }
        val ghosts = b.indices.filter { !usedB[it] }.map { b[it].lagMs }
        return Result(out.toList(), ghosts)
    }

    /**
     * Differential verify. After corrections are applied, each target is re-probed by a
     * KNOWN unique offset ([expectedOffsetsMs]: slot → probe ms), so an aligned target
     * sits at reference + offset. Confirms tracking WITHOUT trusting absolute lag across
     * captures: the reference is found by offset-consensus — the candidate cluster at
     * which the most targets land at ref + offset. Only SPACING matters, so coarse-clock
     * drift and fixed self-similarity ghosts cancel.
     *
     * A candidate must reach [minHits] tracked targets to be elected (a lone match can't
     * name a reference). A leader matching two expected positions within tolerance is
     * ambiguous and tracks neither slot. A target with no cluster at ref + its offset did
     * not track (SetLatency didn't take, or it was mis-attributed) and must fail.
     */
    fun confirmTracking(
        verifyPeaks: List<Dsp.Peak>,
        expectedOffsetsMs: Map<Int, Int>,
        matchTolMs: Double,
        minHits: Int = MIN_CONSENSUS_HITS,
    ): Confirmation {
        val leaders = clusterLeaders(verifyPeaks)
        if (leaders.size < 2 || expectedOffsetsMs.isEmpty()) return Confirmation(null, emptyMap())
        var bestRef: Dsp.Peak? = null
        var bestTracked: Map<Int, Double> = emptyMap()
        for (refCand in leaders) {
            // slot → index of the nearest leader to ref + offset, when within tolerance.
            val hit = LinkedHashMap<Int, Int>()
            for ((slot, off) in expectedOffsetsMs) {
                val want = refCand.lagMs + off
                leaders.indices
                    .filter { abs(leaders[it].lagMs - want) <= matchTolMs }
                    .minByOrNull { abs(leaders[it].lagMs - want) }
                    ?.let { hit[slot] = it }
            }
            // Ambiguity: drop every slot whose matched leader is claimed by another slot.
            val perLeader = hit.values.groupingBy { it }.eachCount()
            val tracked = hit.filterValues { perLeader[it] == 1 }
                .mapValues { leaders[it.value].lagMs }
            val better = tracked.size > bestTracked.size ||
                (tracked.size == bestTracked.size && (bestRef == null || refCand.z > bestRef.z))
            if (better) {
                bestRef = refCand
                bestTracked = tracked
            }
        }
        return if (bestTracked.size >= minHits) Confirmation(bestRef?.lagMs, bestTracked)
        else Confirmation(null, emptyMap())
    }

    /** Collapse peaks into clusters, strongest first: a peak within [clusterMs] of an
     *  already-kept leader joins that cluster. Returns the leaders, strongest first. */
    fun clusterLeaders(peaks: List<Dsp.Peak>, clusterMs: Double = CLUSTER_MS): List<Dsp.Peak> {
        val leaders = mutableListOf<Dsp.Peak>()
        for (p in peaks.sortedByDescending { it.z }) {
            if (leaders.none { abs(it.lagMs - p.lagMs) <= clusterMs }) leaders += p
        }
        return leaders
    }

    /**
     * Re-derive a probed target's delta from ONE half of the probe capture by locating the
     * peaks nearest the full-capture reference and target lags (within [findTolMs]).
     * Returns null if either peak is absent in this half — the estimate is not reproducible.
     * Comparing the two halves' deltas measures estimate STABILITY directly, which (unlike a
     * z threshold) is loudness-invariant: a steady weak peak passes, a jittery one doesn't.
     */
    fun halfDelta(
        halfPeaks: List<Dsp.Peak>,
        refLagFull: Double,
        tgtLagFull: Double,
        refOffMs: Int,
        tgtOffMs: Int,
        findTolMs: Double,
    ): Int? {
        fun nearest(lag: Double) = halfPeaks.minByOrNull { abs(it.lagMs - lag) }
            ?.takeIf { abs(it.lagMs - lag) <= findTolMs }
        val r = nearest(refLagFull) ?: return null
        val t = nearest(tgtLagFull) ?: return null
        return ((t.lagMs - tgtOffMs) - (r.lagMs - refOffMs)).roundToInt()
    }

    private fun zComparable(a: Dsp.Peak, b: Dsp.Peak): Boolean {
        val hi = maxOf(a.z, b.z)
        val lo = minOf(a.z, b.z)
        return hi <= lo * Z_RATIO_MAX
    }
}
