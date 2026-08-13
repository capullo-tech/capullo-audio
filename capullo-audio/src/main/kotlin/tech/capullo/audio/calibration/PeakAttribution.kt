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

    /** How far one source's arrivals spread: its direct path plus the room reflections of it.
     *  Rig-measured at 50-80 ms indoors, so [patternSupport] looks this far around an anchor when
     *  asking whether the surrounding pattern moved with the candidate. */
    const val SOURCE_SPREAD_MS = 80.0

    /** A probe moves a speaker's cluster without changing its salience much, so a
     *  matched pair must have comparable z on both sides. Blocks correlation sidelobes
     *  (which CAN clear the salience threshold on strongly autocorrelated program
     *  material) from stealing a probe match from a quiet speaker. */
    const val Z_RATIO_MAX = 3.0

    /**
     * Match errors within this of each other count as equally good, and the EARLIEST arrival then
     * wins. Sound reaches the mic by the direct path before any reflection of it, so among equally
     * plausible candidates the earliest is the physical one.
     *
     * This matters because a reflection is not a weak copy that a salience test can reject: a
     * speaker's reflection in the baseline paired with the SAME reflection in the probed capture
     * shifts by exactly the probe offset, so it is a zero-error match that simply reports a lag
     * later than the truth by the reflection delay. Ranking by salience picked it whenever
     * constructive interference made a reflection out-peak the direct path, and rig-measured
     * reflections run 50-80 ms — the same scale as the correction errors observed.
     */
    const val EARLIEST_TIE_MS = 3.0

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
        /** Estimated global timeline drift between the baseline and probe captures (ms),
         *  removed before matching. ~0 normally; large when the two captures are far apart
         *  or a sink shifted. Logged so "reference not identified" becomes "timeline drift N". */
        val driftMs: Double = 0.0,
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

        // Greedy assignment for a given global drift: match each entity to a (baseline,
        // probed) pair whose shift, drift-removed, is within tol of its offset; strongest
        // probed leader first, then smallest error, each cluster used once.
        fun matchAt(drift: Double): Array<Attribution?> {
            data class Cand(val k: Int, val pi: Int, val bi: Int, val err: Double, val pattern: Int)
            val cands = mutableListOf<Cand>()
            for (k in probesMs.indices) for (pi in p.indices) for (bi in b.indices) {
                val err = abs((p[pi].lagMs - b[bi].lagMs - drift) - probesMs[k])
                if (err < matchTolMs && zComparable(p[pi], b[bi])) {
                    cands += Cand(k, pi, bi, err, patternSupport(b, p, b[bi].lagMs, probesMs[k] + drift, matchTolMs))
                }
            }
            // Rank by CLUSTER PATTERN SUPPORT first, then match quality, then earliest arrival,
            // then salience. A probe shifts a source's whole cluster rigidly, so the right answer
            // is the one where the surrounding pattern moved with it; a lone coincidence that
            // happens to sit at the expected offset has no such support. This is the fix for the
            // dominant error found on-rig: across five identical captures the peak LAGS were stable
            // (the same bins recurred in 4-5 of 5) but WHICH peak won kept flipping, and the flips
            // came from the weakest captures. Individual-peak matching had no way to tell a real
            // direct path from a well-placed coincidence; the surrounding pattern does.
            cands.sortWith(
                compareByDescending<Cand> { it.pattern }
                    .thenBy { (it.err / EARLIEST_TIE_MS).toInt() }
                    .thenBy { p[it.pi].lagMs }
                    .thenByDescending { p[it.pi].z },
            )
            val usedP = BooleanArray(p.size)
            val usedB = BooleanArray(b.size)
            val out = arrayOfNulls<Attribution>(probesMs.size)
            for (c in cands) {
                if (out[c.k] != null || usedP[c.pi] || usedB[c.bi]) continue
                out[c.k] = Attribution(c.k, b[c.bi].lagMs, p[c.pi].lagMs, p[c.pi].z)
                usedP[c.pi] = true; usedB[c.bi] = true
            }
            return out
        }

        // Global timeline drift between the two captures: for a true (entity, baseline,
        // probed) triple, probed − baseline − offset = drift (direct paths and reflections
        // agree; fixed ghosts scatter), so drift is the mode of those residuals. But with
        // few entities and dense spacing a spurious cluster can tie the true one, so only
        // ADOPT the estimated drift if it attributes strictly more entities than drift=0 —
        // the normal (near-zero drift) case is never perturbed, and a big inter-capture
        // shift (the "reference not identified" failure) is recovered.
        val atZero = matchAt(0.0)
        val estimate = modalValue(
            buildList {
                for (k in probesMs.indices) for (pi in p.indices) for (bi in b.indices) {
                    if (zComparable(p[pi], b[bi])) add(p[pi].lagMs - b[bi].lagMs - probesMs[k])
                }
            },
            matchTolMs,
        )
        var out = atZero
        var drift = 0.0
        if (estimate != null && abs(estimate) > matchTolMs) {
            val atEstimate = matchAt(estimate)
            if (atEstimate.count { it != null } > atZero.count { it != null }) {
                out = atEstimate
                drift = estimate
            }
        }
        val usedB = out.mapNotNull { it?.baselineLagMs }.toSet()
        val ghosts = b.map { it.lagMs }.filter { it !in usedB }
        return Result(out.toList(), ghosts, drift)
    }

    /**
     * Pair-path verify matching: given the probed and baseline peaks of a re-probed pair
     * (reference by [refOffMs], target by [tgtOffMs]), assign each to a DISTINCT probed
     * cluster leader — never the same one. Picking the strongest peak independently per
     * offset grabs the SAME peak when the pair is aligned (its re-probed peaks overlap in a
     * reflective room), a false "could not identify"; a distinct joint assignment doesn't.
     * Inter-capture drift is removed for the MATCHING (the two captures are ~19 s apart);
     * it then cancels in the caller's residual = (target−tgtOff) − (ref−refOff). Returns
     * (referenceLeader, targetLeader) or null if two distinct peaks can't be found.
     */
    fun assignVerifyPair(
        probed: List<Dsp.Peak>,
        baseline: List<Dsp.Peak>,
        refOffMs: Int,
        tgtOffMs: Int,
        matchTolMs: Double,
    ): Pair<Dsp.Peak, Dsp.Peak>? {
        val p = clusterLeaders(probed)
        val b = clusterLeaders(baseline)
        if (p.size < 2 || b.isEmpty()) return null
        val drift = modalValue(
            buildList {
                for (off in intArrayOf(refOffMs, tgtOffMs)) for (pp in p) for (bb in b) {
                    add(pp.lagMs - bb.lagMs - off)
                }
            },
            matchTolMs,
        ) ?: 0.0
        data class Cand(val slot: Int, val pi: Int, val err: Double, val z: Double)
        val cands = mutableListOf<Cand>()
        intArrayOf(refOffMs, tgtOffMs).forEachIndexed { slot, off ->
            for (pi in p.indices) {
                val err = b.minOf { abs(p[pi].lagMs - it.lagMs - off - drift) }
                if (err <= matchTolMs) cands += Cand(slot, pi, err, p[pi].z)
            }
        }
        // Same rule as the batch matcher: equal-quality matches are resolved by earliest arrival,
        // so a reflection cannot stand in for the direct path just by being louder.
        cands.sortWith(
            compareBy<Cand> { (it.err / EARLIEST_TIE_MS).toInt() }
                .thenBy { p[it.pi].lagMs }
                .thenByDescending { it.z },
        )
        val used = BooleanArray(p.size)
        val out = arrayOfNulls<Dsp.Peak>(2)
        for (c in cands) {
            if (out[c.slot] != null || used[c.pi]) continue
            out[c.slot] = p[c.pi]
            used[c.pi] = true
        }
        val r = out[0]
        val t = out[1]
        return if (r != null && t != null) r to t else null
    }

    /**
     * Verify-pair fallback for when [assignVerifyPair] cannot find TWO distinct salient probed
     * leaders: one side of the pair is usually sub-salient in the shared capture (its correlation
     * share sits under the salience floor — the masking physics measured 2026-08-10, in BOTH
     * directions across two runs: 21:32 lost the reference, 21:51 lost the target at z 8.9).
     *
     * Whichever slot CAN be blind-matched among the salient leaders anchors the drift; the other
     * is read straight out of the whitened correlation via [windowPeak] at every sub-salient
     * baseline anchor (full pre-filter list, z ≥ [minWindowZ]) + its offset + drift. A window
     * read below [minWindowZ] fails, exactly as the blind assignment would have.
     *
     * Pure: the correlation access is injected, so this is unit-testable without audio.
     * Returns (reference, target) or null.
     */
    fun assignVerifyPairWithWindows(
        probed: List<Dsp.Peak>,
        baseline: List<Dsp.Peak>,
        baselineFull: List<Dsp.Peak>,
        refOffMs: Int,
        tgtOffMs: Int,
        matchTolMs: Double,
        minWindowZ: Double,
        windowPeak: ((Double, Double) -> Dsp.Peak?)?,
    ): Pair<Dsp.Peak, Dsp.Peak>? {
        val b = clusterLeaders(baseline)
        val p = clusterLeaders(probed)
        if (b.isEmpty() || p.isEmpty()) return null
        fun blind(off: Int): Dsp.Peak? = p
            .mapNotNull { pp ->
                val err = b.minOf { abs(pp.lagMs - it.lagMs - off) }
                if (err <= matchTolMs) pp to err else null
            }
            .minByOrNull { it.second }?.first
        fun drift(pp: Dsp.Peak, off: Int): Double =
            pp.lagMs - b.minByOrNull { abs(pp.lagMs - it.lagMs - off) }!!.lagMs - off
        fun window(off: Int, d: Double, exclude: Dsp.Peak): Dsp.Peak? {
            if (windowPeak == null) return null
            return clusterLeaders(baselineFull)
                .filter { it.z >= minWindowZ }
                .mapNotNull { anchor -> windowPeak(anchor.lagMs + off + d, matchTolMs) }
                .filter { it.z >= minWindowZ && abs(it.lagMs - exclude.lagMs) > CLUSTER_MS }
                .maxByOrNull { it.z }
        }
        val bt = blind(tgtOffMs)
        val br = blind(refOffMs)
        return when {
            bt != null && br != null && abs(bt.lagMs - br.lagMs) > CLUSTER_MS -> br to bt
            bt != null -> window(refOffMs, drift(bt, tgtOffMs), bt)?.let { it to bt }
            br != null -> window(tgtOffMs, drift(br, refOffMs), br)?.let { br to it }
            else -> null
        }
    }

    /**
     * How much of the baseline pattern around [anchorLagMs] reappears in [probed], shifted by
     * [shiftMs]. Counts the baseline peaks within [SOURCE_SPREAD_MS] of the anchor that have a
     * probed counterpart at their own lag + shift.
     *
     * The physics: a latency probe delays one source's ENTIRE arrival pattern — direct path and
     * every reflection of it — by exactly the same amount. So a genuine match drags its neighbours
     * along, while a coincidence (a reflection of another source, or a music self-similarity ghost)
     * that merely happens to sit at the expected offset brings nothing with it. Counting the
     * corroborating peaks separates the two, which matching a single peak cannot do.
     */
    fun patternSupport(
        baseline: List<Dsp.Peak>,
        probed: List<Dsp.Peak>,
        anchorLagMs: Double,
        shiftMs: Double,
        tolMs: Double,
    ): Int = baseline.count { bb ->
        abs(bb.lagMs - anchorLagMs) <= SOURCE_SPREAD_MS &&
            probed.any { pp -> abs(pp.lagMs - (bb.lagMs + shiftMs)) <= tolMs }
    }

    /** The value around which the most of [values] cluster within [tolMs], returned as that
     *  cluster's mean; null unless at least two values agree (a lone point isn't a mode). */
    private fun modalValue(values: List<Double>, tolMs: Double): Double? {
        var best: Double? = null
        var bestCount = 1
        for (center in values) {
            val cluster = values.filter { abs(it - center) <= tolMs }
            if (cluster.size > bestCount) {
                bestCount = cluster.size
                best = cluster.average()
            }
        }
        return best
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

    /** Collapse peaks into clusters seeded strongest-first: a peak within [clusterMs] of an
     *  already-kept leader joins that cluster. The cluster is then REPRESENTED BY ITS EARLIEST
     *  member, not its loudest — within one cluster the earliest arrival is the direct path and
     *  anything after it is a reflection of the same sound, so taking the loudest biased the lag
     *  later by up to [clusterMs]. Salience still decides which peaks seed clusters at all. */
    fun clusterLeaders(peaks: List<Dsp.Peak>, clusterMs: Double = CLUSTER_MS): List<Dsp.Peak> {
        val leaders = mutableListOf<Dsp.Peak>()
        for (p in peaks.sortedByDescending { it.z }) {
            val i = leaders.indexOfFirst { abs(it.lagMs - p.lagMs) <= clusterMs }
            if (i < 0) leaders += p else if (p.lagMs < leaders[i].lagMs) leaders[i] = p
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
