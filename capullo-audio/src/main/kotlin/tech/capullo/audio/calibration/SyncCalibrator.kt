package tech.capullo.audio.calibration

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Attribution (which peak belongs to which client) is resolved with a probe: nudge a
 * target client's latency (negative latency = plays later = its peak moves later by
 * exactly that much — sign convention confirmed on-rig) and see which peak moved. The
 * correction is then applied through Client.SetLatency (server-persisted) and verified
 * with a final measurement. A run never trusts a single measurement: no probe movement →
 * no changes → fail safe with everything restored.
 *
 * Two strategies:
 *  - 2 clients: the v1 pair round (baseline → probe → apply → verify).
 *  - 3+ clients: simultaneous probing — every target gets a unique offset from a Sidon
 *    set in ONE probed measurement, so all peaks are attributable in a single pass and
 *    nothing needs muting. Two measurements + one verify regardless of N. Targets that
 *    end unattributed (quiet/distant speakers) degrade to a muted v1 pair round.
 */
class SyncCalibrator(
    /** Arms/disarms the reference tap on the CURRENT broadcast sink. Must survive an
     *  engine restart mid-run (a new FifoAudioBufferSink must inherit the armed ring),
     *  so the host owns the wiring rather than this class holding a sink reference. */
    private val tapArm: (ReferencePcmRing?) -> Unit,
    /** Required in production (feeds the default measurer); null only when a test injects a
     *  [measurerFactory] that doesn't record a mic. */
    private val mic: MicCapture? = null,
    private val control: CalibrationControl,
    /** Reads back the server's current per-client latency (client id → ms) from the host's
     *  live status, so every commit/restore write can be confirmed and retried. Null skips
     *  read-back (a silent SetLatency failure then goes uncaught — host should supply it). */
    private val readLatencies: (suspend () -> Map<String, Int>)? = null,
    /** Records pre-run latencies so a process death mid-run can be undone on restart (see
     *  [CalibrationJournal] and [recover]). Null disables crash recovery. */
    private val journal: CalibrationJournal? = null,
    /** Appends each verified correction for later per-sink analysis. Null disables it. */
    private val history: CalibrationHistory? = null,
    /** Builds the [Measurer] for a run's ring. Null uses the real mic measurer; tests inject
     *  a factory returning a fake to drive the orchestration deterministically. */
    private val measurerFactory: ((ReferencePcmRing) -> Measurer)? = null,
) {

    /** The production measurer: records the mic and correlates against [ring]. */
    private fun micMeasurer(ring: ReferencePcmRing) = object : Measurer {
        override suspend fun measure(peakCount: Int) = micMeasure(ring, peakCount)
        override suspend fun measureHalves(peakCount: Int) = micMeasureHalves(ring, peakCount)
    }

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

    /** Per-run map of the FINAL latency every mutated client should end at, across every
     *  path (batch, pair, restores, reference). The last write per client wins, so a
     *  transient probe followed by a commit/restore records the commit/restore. Reconciled
     *  once at the end of [calibrate] so no path's write can silently fail. */
    private val intended = LinkedHashMap<String, Int>()

    /** A FINAL latency write (commit or restore) — records it for run-level read-back.
     *  Transient probe writes use [control].sendSetLatency directly and are not recorded. */
    private suspend fun commitLatency(id: String, latencyMs: Int) {
        control.sendSetLatency(id, latencyMs)
        intended[id] = latencyMs
    }

    /**
     * Calibrate [clients] (connected snapclients with audible sinks; 2+ entries, first
     * entry = reference whose latency is never changed). Returns true if every target
     * ended calibrated (or already aligned).
     */
    suspend fun calibrate(clients: List<CalClient>): Boolean {
        if (clients.size < 2) {
            _state.value = State.Failed("need at least 2 connected clients, got ${clients.size}")
            return false
        }
        intended.clear()
        val ring = ReferencePcmRing()
        val measurer = measurerFactory?.invoke(ring) ?: micMeasurer(ring)
        tapArm(ring)
        // Record every client's pre-run latency BEFORE the first mutating write, so a
        // process death mid-run is undone on the next start (see [recover]). Cleared in
        // the finally once the run has restored/committed on its own.
        journal?.save(clients.associate { it.id to it.latencyMs })
        var result = false
        try {
            // Let the ring cover more than one full capture before the first measurement.
            progress("priming reference ring (${RING_PRIME_MS / 1000}s)…")
            delay(RING_PRIME_MS)
            result = if (clients.size >= 3) {
                calibrateSimultaneous(measurer, clients)
            } else {
                calibrateMutedPairs(measurer, clients)
            }
        } finally {
            tapArm(null)
            journal?.clear()
            // Run-level read-back: retry the final write of EVERY mutated client (batch,
            // pair, and every restore) and fail any that stay wrong. A silent failed
            // restore — client left at latency−probe while we report "restored" — is worse
            // than a failed commit, and only this run-level pass covers the pair path.
            val stillBad = reconcile(intended)
            if (stillBad.isNotEmpty()) {
                val referenceId = clients.first().id
                if (referenceId in stillBad) Log.e(TAG, "reference latency read-back failed")
                val badTargets = stillBad.filterNot { it == referenceId }
                if (badTargets.isNotEmpty()) {
                    result = false
                    val prev = (_state.value as? State.Done)?.summary
                        ?: (_state.value as? State.Failed)?.reason ?: ""
                    _state.value = State.Failed("read-back FAILED for $badTargets; $prev")
                }
            }
            if (_state.value is State.Running) _state.value = State.Failed("aborted")
        }
        return result
    }

    // ---- v1 strategy: sequential pair rounds, others muted -------------------------

    private suspend fun calibrateMutedPairs(
        measurer: Measurer,
        clients: List<CalClient>,
    ): Boolean {
        val reference = clients.first()
        val targets = clients.drop(1)
        val results = mutableListOf<String>()
        var successes = 0
        for (target in targets) {
            val others = targets.filter { it.id != target.id }
            // A failed pair (e.g. a silent/remote web client that never produces a
            // peak) is restored + reported but must not abort the remaining pairs.
            val outcome = mutedPairRound(measurer, reference, target, others)
            if (outcome != null) successes++
            results += outcome ?: "${target.name}: failed (see log)"
        }
        finish(successes > 0, results.joinToString("; "))
        return successes == targets.size
    }

    /** One isolated pair round: mutes [others], calibrates [target] against [reference],
     *  restores volumes. Also the fallback path for unattributed simultaneous targets. */
    private suspend fun mutedPairRound(
        measurer: Measurer,
        reference: CalClient,
        target: CalClient,
        others: List<CalClient>,
    ): String? {
        try {
            if (others.isNotEmpty()) {
                progress("muting ${others.size} other client(s) for pair isolation")
                others.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }
                delay(SETTLE_MS)
            }
            return calibratePair(measurer, reference, target)
        } finally {
            others.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
        }
    }

    /** Returns a human summary on success, null on failure (target latency restored). */
    private suspend fun calibratePair(
        measurer: Measurer,
        reference: CalClient,
        target: CalClient,
    ): String? {
        progress("measuring baseline (${reference.name} vs ${target.name})…")
        val baseline = measurer.measure(4) ?: return failPair(target, "baseline measurement failed")

        // Probe BOTH the reference and the target, each by its own offset, and identify
        // both by DISPLACEMENT — same common-mode fix as the batch path. Electing the
        // reference as "the peak that stayed put" (the old v1 rule) can pick a fixed music
        // self-similarity ghost and bias the delta invisibly (seen on-rig 2026-07-22).
        val refOff = PROBE_SET_MS[0]
        val tgtOff = PROBE_SET_MS[1]
        progress("probing ${reference.name} + ${target.name}…")
        control.sendSetLatency(reference.id, reference.latencyMs - refOff)
        control.sendSetLatency(target.id, target.latencyMs - tgtOff)
        delay(SETTLE_MS)
        val probed = measurer.measure(4) ?: run {
            commitLatency(reference.id, reference.latencyMs)
            return failPair(target, "probe measurement failed")
        }
        val attr = PeakAttribution.attribute(baseline, probed, listOf(refOff, tgtOff), MATCH_TOL_MS)
        commitLatency(reference.id, reference.latencyMs) // reference is never corrected
        val refM = attr.matches[0]
            ?: return failPair(target, "reference not identified (timeline drift ${attr.driftMs.roundToInt()}ms)")
        val tgtM = attr.matches[1] ?: return failPair(target, "target peak did not move by the probe")

        val deltaMs = ((tgtM.probedLagMs - tgtOff) - (refM.probedLagMs - refOff)).roundToInt()
        val newLatency = target.latencyMs + deltaMs
        Log.i(
            TAG,
            "pair ${reference.name}/${target.name}: ref@%.1f target@%.1f delta=${deltaMs}ms "
                .format(refM.baselineLagMs, tgtM.baselineLagMs) +
                "latency ${target.latencyMs} -> $newLatency",
        )

        // Verify by re-measuring the residual: apply the correction, then run another
        // reference+target probe round. Identify EACH by the peak that moved by its own
        // offset from a baseline leader (reference by displacement, never "stayed put" —
        // the ghost trap). An aligned pair coincides in the verify baseline (one merged
        // cluster) and both probed peaks trace to it, so the residual reads ~0. One extra
        // probe round, acceptable on the fallback path.
        progress("applying ${newLatency}ms to ${target.name}, verifying…")
        control.sendSetLatency(target.id, newLatency) // transient — re-probed for verify below
        delay(SETTLE_MS)
        val vBase = measurer.measure(4) ?: return failPair(target, "verify baseline failed")
        control.sendSetLatency(reference.id, reference.latencyMs - refOff)
        control.sendSetLatency(target.id, newLatency - tgtOff)
        delay(SETTLE_MS)
        val vProbed = measurer.measure(4)
        commitLatency(reference.id, reference.latencyMs) // remove reference probe
        if (vProbed == null) return failPair(target, "verify probe failed")
        // Strongest verify-probed peak that sits `off` past some verify-baseline leader.
        fun movedBy(off: Int) = vProbed.firstOrNull { p ->
            vBase.any { abs(p.lagMs - it.lagMs - off) <= MATCH_TOL_MS }
        }
        val vRef = movedBy(refOff)
        val vTgt = movedBy(tgtOff)
        if (vRef == null || vTgt == null || vRef === vTgt) {
            return failPair(target, "verify could not identify reference/target")
        }
        val residualMs = ((vTgt.lagMs - tgtOff) - (vRef.lagMs - refOff)).roundToInt()
        if (abs(residualMs) > PAIR_RESIDUAL_TOL_MS) {
            return failPair(target, "verify residual ${residualMs}ms > ${PAIR_RESIDUAL_TOL_MS}ms")
        }
        commitLatency(target.id, newLatency) // remove verify probe → aligned
        history?.record(target.id, deltaMs, newLatency)
        return "${target.name}: ${if (deltaMs == 0) "already aligned" else "trim ${deltaMs}ms"} " +
            "(latency $newLatency, residual ${residualMs}ms, verified)"
    }

    // ---- v2 strategy: simultaneous probes, nothing muted ---------------------------

    private suspend fun calibrateSimultaneous(
        measurer: Measurer,
        clients: List<CalClient>,
    ): Boolean {
        val reference = clients.first()
        // The reference consumes probe slot 0 (it is probed and identified by its own
        // displacement — see below); targets take the remaining Sidon slots. Likely-audible
        // app clients first, web clients last. Overflow targets run as muted v1 pair rounds
        // after the batch, and stay muted DURING it so they can't perturb attribution.
        val targets = clients.drop(1).sortedBy { if (isWebClient(it)) 1 else 0 }
        val sim = targets.take(PROBE_SET_MS.size - 1)
        val overflow = targets.drop(PROBE_SET_MS.size - 1)
        // Room for every audible speaker's direct path plus a few reflections each.
        val peakCount = 4 + 3 * (sim.size + 1)
        val refOffset = PROBE_SET_MS[0]
        fun targetOffset(i: Int) = PROBE_SET_MS[i + 1]

        val outcomes = LinkedHashMap<String, String>() // client id -> summary line
        val succeeded = mutableSetOf<String>()
        val errored = mutableSetOf<String>() // restored due to a fault (not just deferred)
        val fallback = mutableListOf<CalClient>()

        overflow.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }
        try {
            batch@ do {
                progress("measuring baseline (${sim.size + 1} speakers audible)…")
                val baseline = measurer.measure(peakCount)
                if (baseline == null) {
                    // Nothing changed yet; a dead measurement means silence/stall, so
                    // fallback rounds would burn minutes failing the same way.
                    finish(false, "baseline measurement failed")
                    return false
                }

                // Probe the reference too (slot 0): it is identified by DISPLACEMENT, never
                // by "the cluster that didn't move" — a fixed music self-similarity ghost
                // could be that, and electing it would bias every delta by a constant the
                // differential verify cannot see (common-mode).
                progress("probing ${sim.size + 1} client(s) concurrently…")
                control.sendSetLatency(reference.id, reference.latencyMs - refOffset)
                sim.forEachIndexed { i, t -> control.sendSetLatency(t.id, t.latencyMs - targetOffset(i)) }
                delay(SETTLE_MS)
                // Halves of the probe capture drive the split-half consistency gate below.
                val probedTriple = measurer.measureHalves(peakCount)
                if (probedTriple == null) {
                    commitLatency(reference.id, reference.latencyMs)
                    sim.forEach { commitLatency(it.id, it.latencyMs) }
                    finish(false, "probe measurement failed")
                    return false
                }
                val (probed, probedH1, probedH2) = probedTriple

                val probesMs = PROBE_SET_MS.take(sim.size + 1) // [0]=reference, [1..]=targets
                val attr = PeakAttribution.attribute(baseline, probed, probesMs, MATCH_TOL_MS)
                // The reference is never corrected; remove its probe now that it is measured.
                commitLatency(reference.id, reference.latencyMs)

                val refMatch = attr.matches[0]
                Log.i(
                    TAG,
                    "attribution: drift=${"%.0f".format(attr.driftMs)}ms " +
                        "ref=${refMatch?.let { "%.1f→%.1f".format(it.baselineLagMs, it.probedLagMs) }} " +
                        sim.indices.joinToString {
                            val m = attr.matches[it + 1]
                            "${sim[it].name}@" + (m?.let { "%.1f→%.1f(z=%.1f)".format(it.baselineLagMs, it.probedLagMs, it.z) } ?: "?")
                        } +
                        " ghosts=${attr.ghostLagsMs.map { "%.0f".format(it) }}",
                )
                if (refMatch == null) {
                    // Reference could not be identified by its displacement → whole batch
                    // untrusted. Remove target probes and degrade them to v1 pair rounds
                    // (which re-write and so overwrite these intended entries).
                    sim.forEach { commitLatency(it.id, it.latencyMs) }
                    progress("reference not identified (timeline drift ${attr.driftMs.roundToInt()}ms) — degrading to pair rounds")
                    queueFallback(sim, outcomes, fallback)
                    break@batch
                }
                val refProbed = refMatch.probedLagMs

                // Correction per target from the differential math: both entities are
                // un-shifted to their true arrival, delta = target arrival − ref arrival.
                // `consistent` = the delta re-derived on each 6 s half of the probe capture
                // agrees (split-half stability), the loudness-invariant replacement for the
                // old z gate — the mic sits by the loud reference so across-room BT targets
                // are weak (z~12-14) yet can still be stable.
                data class Candidate(val client: CalClient, val idx: Int, val offMs: Int, val consistent: Boolean, val deltaMs: Int)
                val candidates = sim.indices.mapNotNull { i ->
                    val m = attr.matches[i + 1] ?: return@mapNotNull null
                    val off = targetOffset(i)
                    val deltaMs = ((m.probedLagMs - off) - (refProbed - refOffset)).roundToInt()
                    val dH1 = PeakAttribution.halfDelta(probedH1, refProbed, m.probedLagMs, refOffset, off, SPLIT_FIND_MS)
                    val dH2 = PeakAttribution.halfDelta(probedH2, refProbed, m.probedLagMs, refOffset, off, SPLIT_FIND_MS)
                    val consistent = dH1 != null && dH2 != null && abs(dH1 - dH2) <= SPLIT_TOL_MS
                    Log.i(TAG, "${sim[i].name}: delta=$deltaMs halves=$dH1/$dH2 consistent=$consistent")
                    Candidate(sim[i], i, off, consistent, deltaMs)
                }
                val unattributed = sim.indices.filter { attr.matches[it + 1] == null }.map { sim[it] }
                unattributed.forEach { commitLatency(it.id, it.latencyMs) }
                queueFallback(unattributed, outcomes, fallback)

                // Gate each correction into a plausible band. Skipped targets keep their
                // current latency (explicit re-write — they still carry a batch probe).
                val toApply = mutableListOf<Candidate>()
                for (c in candidates) {
                    suspend fun skip(msg: String) {
                        commitLatency(c.client.id, c.client.latencyMs)
                        outcomes[c.client.id] = msg
                    }
                    when (gate(c.deltaMs, c.consistent)) {
                        Gate.ALIGNED -> {
                            skip("${c.client.name}: already aligned (Δ${c.deltaMs}ms within deadband)")
                            succeeded += c.client.id
                        }
                        Gate.IMPLAUSIBLE ->
                            skip("${c.client.name}: implausible Δ${c.deltaMs}ms — skipped (suspected mis-attribution)")
                        Gate.DEFERRED ->
                            skip("${c.client.name}: unstable Δ across capture halves — deferred")
                        Gate.APPLY -> toApply += c
                    }
                }
                if (toApply.isEmpty()) break@batch
                // Offset-consensus needs ≥2 tracked targets to name a reference; a single
                // target can't reach that, so route it to the robust v1 muted pair path.
                if (toApply.size == 1) {
                    val only = toApply.single()
                    commitLatency(only.client.id, only.client.latencyMs)
                    fallback += only.client
                    break@batch
                }

                // Differential verify: re-apply correction − offset so each target is
                // re-probed by its own unique offset; an aligned target then sits at
                // reference + offset, confirmed by offset-consensus (drift/ghost-immune).
                // Only tracked targets get the aligned value committed; the rest restored.
                progress("verifying ${toApply.size} client(s) (differential)…")
                toApply.forEach { c ->
                    Log.i(TAG, "${c.client.name}: delta=${c.deltaMs}ms verify-probe ${c.offMs}ms")
                    control.sendSetLatency(c.client.id, c.client.latencyMs + c.deltaMs - c.offMs)
                }
                delay(SETTLE_MS)
                val verify = measurer.measure(peakCount)
                if (verify == null) {
                    toApply.forEach {
                        commitLatency(it.client.id, it.client.latencyMs)
                        errored += it.client.id
                        outcomes[it.client.id] = "${it.client.name}: verify measurement failed — restored"
                    }
                    break@batch
                }
                val offsets = toApply.associate { it.idx to it.offMs }
                val conf = PeakAttribution.confirmTracking(verify, offsets, MATCH_TOL_MS)
                Log.i(
                    TAG,
                    "verify: ref=${conf.referenceLagMs?.let { "%.1f".format(it) }} " +
                        "tracked=${conf.tracked.keys.map { sim[it].name }}",
                )
                for (c in toApply) {
                    if (conf.tracked.containsKey(c.idx)) {
                        val newLatency = c.client.latencyMs + c.deltaMs
                        commitLatency(c.client.id, newLatency) // remove verify probe
                        history?.record(c.client.id, c.deltaMs, newLatency)
                        succeeded += c.client.id
                        outcomes[c.client.id] = "${c.client.name}: trim ${c.deltaMs}ms (latency $newLatency, verified)"
                    } else {
                        // Not confirmed — either this target didn't track (glitch /
                        // mis-attribution) or the batch missed quorum (tracked is empty).
                        // Don't discard it: restore and re-measure it on the robust muted
                        // pair path (which re-writes, overwriting this intended entry), so
                        // one flaky target can't waste the whole batch.
                        Log.w(TAG, "${c.client.name}: not confirmed in batch — retrying via pair round")
                        commitLatency(c.client.id, c.client.latencyMs)
                        fallback += c.client
                        outcomes[c.client.id] = "${c.client.name}: not confirmed in batch — retrying via pair round"
                    }
                }
            } while (false)

            for (target in fallback + overflow.filterNot { isWebClient(it) }) {
                val others = targets.filter { it.id != target.id }
                val outcome = mutedPairRound(measurer, reference, target, others)
                if (outcome != null) succeeded += target.id else errored += target.id
                outcomes[target.id] = outcome ?: "${target.name}: failed (see log)"
            }
            overflow.filter { isWebClient(it) }.forEach {
                outcomes[it.id] = "${it.name}: web client, skipped"
            }

            // Read-back is run-level (in calibrate's finally) so it also covers the pair
            // path and every restore, not just the batch commits.
            val summary = targets.joinToString("; ") { outcomes[it.id] ?: "${it.name}: ?" }
            // Fail only on a genuine fault with nothing to show for it. An all-deferred /
            // all-already-aligned run (no successes, no faults) is a valid "nothing to do".
            finish(succeeded.isNotEmpty() || errored.isEmpty(), summary)
            return succeeded.isNotEmpty() &&
                targets.all { it.id in succeeded || isWebClient(it) }
        } finally {
            overflow.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
        }
    }

    /** Verify each intended latency actually landed on the server (via [readLatencies]) and
     *  retry the mismatches once. Returns the ids that stayed wrong after the retry. No-op
     *  (returns empty) when the host supplied no reader. */
    private suspend fun reconcile(intended: Map<String, Int>): Set<String> {
        val read = readLatencies ?: return emptySet()
        if (intended.isEmpty()) return emptySet()
        control.sendGetStatus()
        delay(READBACK_MS)
        var actual = read()
        val bad = intended.filterNot { (id, v) -> actual[id] == v }
        if (bad.isEmpty()) return emptySet()
        Log.w(TAG, "read-back mismatch, retrying: ${bad.keys}")
        bad.forEach { (id, v) -> control.sendSetLatency(id, v) }
        control.sendGetStatus()
        delay(READBACK_MS)
        actual = read()
        var still = intended.filterNot { (id, v) -> actual[id] == v }.keys
        if (still.isNotEmpty()) {
            // Distinguish a real write failure from the host's status collector simply
            // not having refreshed yet — give it one more interval before condemning.
            delay(READBACK_MS)
            actual = read()
            still = intended.filterNot { (id, v) -> actual[id] == v }.keys
        }
        if (still.isNotEmpty()) Log.e(TAG, "read-back FAILED after retry: $still")
        return still
    }

    /** Unattributed targets degrade to a muted v1 pair round — except web clients,
     *  which are usually silent by design and would just burn a round failing. */
    private fun queueFallback(
        unattributed: List<CalClient>,
        outcomes: MutableMap<String, String>,
        fallback: MutableList<CalClient>,
    ) {
        for (client in unattributed) {
            if (isWebClient(client)) {
                outcomes[client.id] = "${client.name}: unattributed (web client, skipped)"
            } else {
                fallback += client
            }
        }
    }

    private fun isWebClient(client: CalClient) = client.id.startsWith(WEB_CLIENT_PREFIX)

    // ---- shared measurement --------------------------------------------------------

    /** Like [measure] but also returns the peaks of each 6 s half of the same capture, for
     *  the split-half consistency gate. Full peaks are salience-filtered as usual; the half
     *  peaks are returned raw (the gate locates a known lag in them, so low z is fine). */
    private suspend fun micMeasureHalves(
        ring: ReferencePcmRing,
        peakCount: Int,
    ): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>>? {
        repeat(2) { attempt ->
            val cap = mic!!.record(CAPTURE_MS)
            if (cap != null) {
                val snap = ring.snapshot()
                val full = DelayMeasurement.estimateSpeakerDelays(snap, cap, peakCount)
                Log.i(TAG, "peaks: " + full.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
                val salient = full.filter { it.z >= MIN_PEAK_Z }
                if (salient.isNotEmpty()) {
                    val h = cap.pcm.size / 2
                    val c1 = MicCapture.Capture(cap.pcm.copyOfRange(0, h), cap.firstSampleNanos, cap.sampleRate)
                    val n2 = cap.firstSampleNanos + h.toLong() * 1_000_000_000L / cap.sampleRate
                    val c2 = MicCapture.Capture(cap.pcm.copyOfRange(h, cap.pcm.size), n2, cap.sampleRate)
                    val p1 = DelayMeasurement.estimateSpeakerDelays(snap, c1, peakCount)
                    val p2 = DelayMeasurement.estimateSpeakerDelays(snap, c2, peakCount)
                    return Triple(salient, p1, p2)
                }
            }
            if (attempt == 0) {
                progress("measurement inconclusive, retrying…")
                delay(RETRY_BACKOFF_MS)
            }
        }
        return null
    }

    private suspend fun micMeasure(ring: ReferencePcmRing, peakCount: Int = 4): List<Dsp.Peak>? {
        repeat(2) { attempt ->
            val capture = mic!!.record(CAPTURE_MS)
            if (capture != null) {
                val snapshot = ring.snapshot()
                val peaks = DelayMeasurement.estimateSpeakerDelays(snapshot, capture, peakCount)
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
        commitLatency(target.id, target.latencyMs)
        progress("${target.name}: $reason")
        return null
    }

    private fun progress(message: String) {
        Log.i(TAG, message)
        _state.value = State.Running(message)
    }

    private fun finish(success: Boolean, summary: String) {
        Log.i(TAG, "${if (success) "done" else "failed"}: $summary")
        _state.value = if (success) State.Done(summary) else State.Failed(summary)
    }

    /** Outcome of gating one computed correction. */
    enum class Gate { APPLY, ALIGNED, DEFERRED, IMPLAUSIBLE }

    companion object {
        private const val TAG = "SyncCalibrator"

        /**
         * Undo a calibration run that a process death interrupted. If [journal] still holds
         * originals, the previous run never reached its finally, so restore each client's
         * pre-run latency via [setLatency] and clear the journal. Call once at host startup
         * (after the server control connection is up) BEFORE any new run. Safe to call when
         * no journal exists (returns empty). Returns the restored client ids.
         */
        suspend fun recover(
            journal: CalibrationJournal,
            setLatency: suspend (clientId: String, latencyMs: Int) -> Unit,
        ): List<String> {
            val pending = journal.load() ?: return emptyList()
            Log.w(TAG, "recovering ${pending.size} client latency(ies) from an interrupted run: ${pending.keys}")
            pending.forEach { (id, latency) -> setLatency(id, latency) }
            journal.clear()
            return pending.keys.toList()
        }

        /** Classify a correction before trusting it (pure; unit-tested). Order matters: a
         *  within-deadband delta is "already aligned" regardless of stability; only then
         *  does a large delta read as mis-attribution and an unstable estimate as deferred.
         *  [consistent] = the split-half agreement of the delta (see [PeakAttribution.halfDelta]). */
        fun gate(deltaMs: Int, consistent: Boolean): Gate = when {
            abs(deltaMs) <= DEADBAND_MS -> Gate.ALIGNED
            abs(deltaMs) > MAX_PLAUSIBLE_DELTA_MS -> Gate.IMPLAUSIBLE
            !consistent -> Gate.DEFERRED
            else -> Gate.APPLY
        }
        const val CAPTURE_MS = 12_000

        /** Pair-path verify: max post-correction target-vs-reference residual to accept.
         *  Tighter than MATCH_TOL since it's a direct re-derived offset, not a matching. */
        private const val PAIR_RESIDUAL_TOL_MS = 12

        /** Simultaneous probe offsets (slot 0 = reference, rest = targets). Chosen so
         *  every value in (offsets ∪ pairwise differences) is separated by ≥ 2×MATCH_TOL:
         *  {60,90,210,390} → offsets and diffs {30,120,150,180,300,330} are all distinct
         *  multiples of 30, so no shifted grid can be confused with another (needed for
         *  the verify offset-consensus, not just pairwise attribution). One slot goes to
         *  the reference, so this caps a batch at PROBE_SET_MS.size − 1 targets. */
        val PROBE_SET_MS = listOf(60, 90, 210, 390)

        private const val WEB_CLIENT_PREFIX = "qcweb-"

        /** Corrections below this are within measurement noise AND ~the audible-sync
         *  floor: leave the client where it is rather than chase noise. */
        private const val DEADBAND_MS = 15
        /** A residual larger than any real BT/web sink gap; a computed delta beyond it
         *  means the target was matched to a ghost. Kept well above legitimate cold-start
         *  corrections (iPhone web needed ~+217). */
        private const val MAX_PLAUSIBLE_DELTA_MS = 500
        /** Split-half consistency gate: the delta re-derived on each 6 s half of the probe
         *  capture must agree within this. Loudness-invariant (replaces the rig-dependent z
         *  gate) — a steady weak peak passes, a jittery one (run-5 noise-chasing) doesn't. */
        private const val SPLIT_TOL_MS = 12
        /** Window to relocate a full-capture peak lag within a half's peaks. */
        private const val SPLIT_FIND_MS = 30.0
        private const val SETTLE_MS = 7_000L
        /** Time for a SetLatency write + a GetStatus refresh to reflect in the host's
         *  status before read-back reads it back. */
        private const val READBACK_MS = 600L
        private const val RING_PRIME_MS = 16_000L
        private const val RETRY_BACKOFF_MS = 8_000L
        private const val MATCH_TOL_MS = 15.0
        private const val MIN_PEAK_Z = 9.0
    }
}
