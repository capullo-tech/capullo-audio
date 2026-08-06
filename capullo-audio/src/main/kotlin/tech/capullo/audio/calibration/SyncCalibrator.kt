package tech.capullo.audio.calibration

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
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
    /** Records the volumes the balance overwrote so [undoBalance] can put them back. Null makes the
     *  balance one-way, which is why it is worth supplying: see [VolumeUndo]. */
    private val volumeUndo: VolumeUndo? = null,
    /** Builds the [Measurer] for a run's ring. Null uses the real mic measurer; tests inject
     *  a factory returning a fake to drive the orchestration deterministically. */
    private val measurerFactory: ((ReferencePcmRing) -> Measurer)? = null,
    /** Broadcasts client-side OS media-volume boost leases (client id → target percent), or clears
     *  them all with an empty map. The knob to use when a target's SW gain is already at 100% and so
     *  has no headroom left. Takes a MAP because the no-mute batch boosts several speakers at once.
     *  Null when the host offers no such channel, in which case a default-volume target simply
     *  stays unboostable. See [OsVolumeBoost]. */
    private val publishOsBoost: (suspend (targets: Map<String, Int>, leaseMs: Long) -> Unit)? = null,
) {

    /** Correlation of the most recent [micMeasure], kept so a known lag's LEVEL can be read back
     *  out of it after attribution has decided which lag belongs to whom. */
    @Volatile private var lastMeasurement: DelayMeasurement.Measurement? = null

    /** The production measurer: records the mic and correlates against [ring]. */
    private fun micMeasurer(ring: ReferencePcmRing) = object : Measurer {
        override suspend fun measure(peakCount: Int) = micMeasure(ring, peakCount)
        override suspend fun measureHalves(peakCount: Int) = micMeasureHalves(ring, peakCount)
        override fun levelReader(): ((Double) -> Double)? =
            lastMeasurement?.let { m -> { lag -> m.levelAt(lag) } }
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

    /** Appended to the run summary so muted speakers are reported as deliberately skipped rather
     *  than silently absent. Set once per run by [calibrate]. */
    private var mutedNote: String = ""

    /** Per-client loudness AT THE MIC, one entry per capture, reduced at the end of the run into the
     *  levels [VolumeBalance] balances on.
     *
     *  Kept PER CAPTURE rather than as a flat list per client, because a capture's absolute salience
     *  is not trustworthy but the ratio between two clients WITHIN one capture is: z divides the
     *  correlation peak by that capture's own noise floor, so the floor cancels out of a ratio taken
     *  inside a capture and does not cancel out of one taken across captures. Rig-measured, the
     *  across-capture part is the larger effect by far — the same unchanged pair read top-z 22.6,
     *  13.8 and 11.6 on three consecutive captures — so each capture is normalised on its own before
     *  anything is compared, which removes that swing as common mode.
     *
     *  Only UNBOOSTED captures are recorded. A boosted client is acoustically louder while the SW
     *  gain the balance reasons about is unchanged — worse for the OS-volume boost, which the server
     *  cannot see at all — so it would read as a genuinely loud speaker and get attenuated for it. */
    private val levelCaptures = mutableListOf<Map<String, Double>>()


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
    suspend fun calibrate(allClients: List<CalClient>): Boolean {
        if (allClients.size < 2) {
            _state.value = State.Failed("need at least 2 connected clients, got ${allClients.size}")
            return false
        }
        // EXCLUDE MUTED CLIENTS UP FRONT. A muted speaker emits nothing, so the mic cannot find it
        // and we must not unmute it (a mute is explicit user intent). Left in the list it is probed
        // anyway, fails attribution, and burns a full pair round plus both boost escalations —
        // minutes — before reporting "target peak did not move by the probe", which reads as a
        // probe fault rather than the real reason. Skip it and say so.
        val reference = allClients.first()
        if (reference.muted) {
            _state.value = State.Failed(
                "the reference speaker ${reference.name} is muted — everything is measured against " +
                    "it, so unmute it and re-run",
            )
            return false
        }
        val muted = allClients.drop(1).filter { it.muted }
        val clients = listOf(reference) + allClients.drop(1).filterNot { it.muted }
        mutedNote = if (muted.isEmpty()) "" else {
            "; skipped (muted): " + muted.joinToString { it.name }
        }
        if (clients.size < 2) {
            _state.value = State.Failed(
                "no unmuted speaker to calibrate against ${reference.name}${mutedNote}",
            )
            return false
        }
        intended.clear()
        levelCaptures.clear()
        val ring = ReferencePcmRing()
        val measurer = measurerFactory?.invoke(ring) ?: micMeasurer(ring)
        // Journal every client's pre-run latency AND volume BEFORE the first mutating write, so
        // a process death mid-run is undone on the next start (see [recover]). Volume matters
        // because the muted-pair fallback silences other clients — an un-journaled crash there
        // would strand a room muted. If the journal can't be durably written, abort rather than
        // mutate un-recoverably. Cleared in the finally once the run has restored/committed.
        if (journal != null && !journal.save(
                clients.associate { it.id to ClientSnapshot(it.latencyMs, it.volumePercent, it.muted) },
            )
        ) {
            _state.value = State.Failed("could not journal pre-run state — aborting to stay crash-safe")
            return false
        }
        tapArm(ring)
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
            // Balance the volumes LAST, and here rather than inside a strategy: this is the one
            // point where every transient write the run made — the muted others, the detectability
            // boosts — has already been restored by its own finally, so the levels being acted on
            // and the gains being written both describe the room the listener is left with. It runs
            // before the journal is cleared so a process death mid-balance is still recoverable.
            balanceVolumes(clients)
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
            //
            // UNBOOSTED first. This is the PRIMARY path at N=2, where `others` is empty so nothing
            // is muted and the listener is hearing both speakers: shouting a target to full volume
            // on every routine calibration is exactly the disruption the no-mute design exists to
            // avoid. Boost only buys detectability, so it costs nothing to find out we didn't need
            // it — and only a target that actually failed pays for the retry.
            //
            // Then a COARSE RAMP rather than a jump to full volume. Rig-measured: one speaker alone
            // put 0 peaks over the z=9 floor at 25% device volume, 4 at 50%, but 9 at 75% — because
            // raising the level lifts its ROOM REFLECTIONS over the floor too, and those ghosts
            // cluster within ~50 ms while the probe offsets are only 30 ms apart. Louder is
            // therefore not monotonically better: past the floor it degrades attribution. So try the
            // modest level first and escalate only if that failed to find the speaker at all.
            var outcome = mutedPairRound(measurer, reference, target, others, boostPercent = null)
            if (outcome == null && canBoost(target)) {
                for (level in BOOST_RAMP_PERCENT) {
                    progress("${target.name}: retrying with a $level% detectability boost")
                    outcome = mutedPairRound(measurer, reference, target, others, boostPercent = level)
                    if (outcome != null) break
                }
            }
            if (outcome != null) successes++
            results += outcome ?: "${target.name}: failed (see log)"
        }
        finish(successes > 0, results.joinToString("; "))
        return successes == targets.size
    }

    /** True if [target] has any loudness headroom left to give (and may be touched at all).
     *  A user mute is explicit intent — R6: we exclude such a client, never unmute it. */
    private fun canBoost(target: CalClient): Boolean = !target.muted &&
        (target.volumePercent < BOOST_RAMP_PERCENT.last() || (publishOsBoost != null && !isWebClient(target)))

    /** One isolated pair round: mutes [others], calibrates [target] against [reference], then
     *  restores every volume. Used both as the N=2 primary path and as the fallback for targets
     *  the no-mute batch couldn't attribute.
     *
     *  [boostPercent] (null = no boost) raises volume for the round so a quiet/distant speaker
     *  becomes measurable. It is OPT-IN per round rather than automatic: at N=2 nothing is muted and
     *  the listener is hearing the room, so a routine calibration must not shout — the caller boosts
     *  only on a retry, after an unboosted attempt actually failed. The boost is transient (restored
     *  in the finally along with the muted others) and never touches a user-muted client. Timing is
     *  unaffected: a boost changes peak height, not peak spacing, so the measured latency delta is
     *  volume-independent. The pre-boost volume is already in the crash journal (saved for every
     *  client before the first write), so a process death mid-boost is recovered on the next start.
     *
     *  THE REFERENCE IS BOOSTED TOO. It is never *corrected* (its latency is untouched), but it must
     *  still be FOUND, and it is just as able to be too quiet as any target — rig-observed: at 25%
     *  device volume the reference produced zero above-floor peaks and every round died with
     *  "reference not identified". Leaving the one speaker everything is measured against out of the
     *  boost was a hole: a quiet reference fails the whole calibration, not just its own row. */
    private suspend fun mutedPairRound(
        measurer: Measurer,
        reference: CalClient,
        target: CalClient,
        others: List<CalClient>,
        boostPercent: Int?,
    ): String? {
        // Knob selection: use whichever stage of the gain chain actually has headroom. The SW gain
        // is server-side, instant and non-intrusive, so prefer it — but it sits at 100% by default,
        // and then the ONLY headroom is the client's own OS media volume (which drives its paired BT
        // sink too, rig-proven). Boosting SW when it is already maxed would be a no-op, which is
        // exactly the hole this fixes.
        val boost = boostPercent != null
        val swBoost = boost && !target.muted && target.volumePercent < boostPercent!!
        val osBoost = boost && !target.muted && !swBoost && publishOsBoost != null && !isWebClient(target)
        // Same treatment for the reference, independently: it may need a different knob than the
        // target, and it is skipped entirely if it already has the level to be found.
        val refSwBoost = boost && !reference.muted && reference.volumePercent < boostPercent!!
        val refOsBoost = boost && !reference.muted && !refSwBoost &&
            publishOsBoost != null && !isWebClient(reference)
        try {
            if (others.isNotEmpty()) {
                progress("muting ${others.size} other client(s) for pair isolation")
                others.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }
            }
            if (swBoost) {
                progress("boosting ${target.name} to $boostPercent% for detectability")
                control.sendSetVolume(target.id, muted = false, percent = boostPercent!!)
            }
            if (refSwBoost) {
                progress("boosting reference ${reference.name} to $boostPercent% so it can be found")
                control.sendSetVolume(reference.id, muted = false, percent = boostPercent!!)
            }
            // One lease covers both device-volume boosts: the payload is a list, and issuing it once
            // avoids a second metadata publish (each re-serializes the host's art blob).
            val osTargets = buildMap {
                if (osBoost) put(target.id, boostPercent!!)
                if (refOsBoost) put(reference.id, boostPercent!!)
            }
            if (osTargets.isNotEmpty()) {
                progress("boosting device volume for detectability (${osTargets.size} speaker(s))")
                publishOsBoost?.invoke(osTargets, OS_BOOST_LEASE_MS)
            }
            if (others.isNotEmpty() || swBoost || refSwBoost || osTargets.isNotEmpty()) delay(SETTLE_MS)
            // Levels are only usable for balancing when nothing has been boosted: a boosted speaker
            // is louder than its SW gain says, so the balance would read it as needing attenuation.
            return calibratePair(measurer, reference, target, harvestLevels = !boost)
        } finally {
            others.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
            if (swBoost) control.sendSetVolume(target.id, muted = target.muted, percent = target.volumePercent)
            if (refSwBoost) {
                control.sendSetVolume(reference.id, muted = reference.muted, percent = reference.volumePercent)
            }
            // Explicit release so the volume drops immediately; the lease is only the crash failsafe.
            if (osBoost || refOsBoost) publishOsBoost?.invoke(emptyMap(), 0)
        }
    }

    /** Returns a human summary on success, null on failure (target latency restored). */
    private suspend fun calibratePair(
        measurer: Measurer,
        reference: CalClient,
        target: CalClient,
        harvestLevels: Boolean = false,
    ): String? {
        // REPEAT THE BASELINE, not just the probe. Rig-measured: five identical baseline captures
        // put the two speakers' peak SPACING — which is exactly what the delta is made of — at
        // 37.6/41.0/45.8/54.3/71.5 ms, a 34 ms wander with nothing changed. That single number
        // explains the run-to-run correction spread chased all day, and it explains why three probe
        // captures inside one run agreed to 1 ms: they were all compared against ONE baseline and
        // inherited the same error. The noise is drawn once per run, before probing starts.
        progress("measuring baseline (${reference.name} vs ${target.name})…")
        val baselines = mutableListOf<List<Dsp.Peak>>()
        repeat(PROBE_REPEATS) { attempt ->
            measurer.measure(4)?.let { baselines += it }
            if (attempt < PROBE_REPEATS - 1) progress("baseline capture ${attempt + 2}/$PROBE_REPEATS…")
        }
        if (baselines.isEmpty()) return failPair(target, "baseline measurement failed")

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
        // MEASURE REPEATEDLY AND TAKE THE MEDIAN. One 12 s capture resolves this residual to only
        // about ±20 ms — established on-rig by elimination: program material, level/boost,
        // cross-speaker probe confusion, within-cluster reflections and per-BT-session drift were
        // each ruled out by controlled tests, yet three back-to-back runs of an unchanged rig still
        // spanned 36 ms. The error is intrinsic to a single capture, so the remedy is statistical.
        // MEDIAN rather than mean: the observed outliers are wild (a −104 ms against a −65 ms run),
        // and a mean is dragged by them while a median ignores them outright. The probes stay
        // applied across the repeats, so this costs captures, not settle time.
        // Every baseline is paired with every probe capture, so N captures each yield up to N*N
        // independent deltas — the baseline error that dominates is sampled repeatedly instead of
        // being fixed for the whole run.
        //
        // Samples are WEIGHTED BY CAPTURE QUALITY, because quality demonstrably predicts accuracy:
        // in the five-capture diagnostic the two baselines with the lowest salience (top z 11.8 and
        // 9.9) produced the two worst spacings (71.5 and 37.6 ms) while the three strong ones
        // (z 30.8/28.2/24.7) clustered at 41-54 ms. A sample is only as trustworthy as the weakest
        // evidence behind it, so its weight is the smallest salience in its own chain: the baseline
        // it came from, and the two peaks that were matched in the probed capture.
        val samples = mutableListOf<Pair<Int, Double>>() // delta -> weight
        var lastAttr: PeakAttribution.Result? = null
        var refNotFound = false
        repeat(PROBE_REPEATS) { attempt ->
            val probed = measurer.measure(4)
            val probedLevel = measurer.levelReader()
            // One level sample per CAPTURE, not per baseline pairing: the N pairings of one probed
            // capture all read the same two lags, so counting them N times would just weight that
            // capture N-fold in the median.
            var levelled = false
            if (probed != null) {
                for ((bi, base) in baselines.withIndex()) {
                    val a = PeakAttribution.attribute(base, probed, listOf(refOff, tgtOff), MATCH_TOL_MS)
                    lastAttr = a
                    val r = a.matches[0]
                    val t = a.matches[1]
                    if (r == null) refNotFound = true
                    if (r != null && t != null) {
                        val delta = ((t.probedLagMs - tgtOff) - (r.probedLagMs - refOff)).roundToInt()
                        val baseZ = base.maxOfOrNull { it.z } ?: 0.0
                        samples += delta to minOf(baseZ, r.z, t.z)
                        // LEVELS COME FROM THE PROBED CAPTURE ONLY. The probe offsets
                        // (90/180/360 ms) have pulled the speakers apart, and the level estimator
                        // needs about 30 ms of separation to be accurate — at 10 ms a true 0.25
                        // ratio reads 0.42, from 30 ms it reads 0.26. In the BASELINE the speakers
                        // sit at their natural arrivals, which on a real rig can be a millisecond
                        // apart, so a baseline harvest would report two speakers as equal however
                        // different they are. Probing solves the separation problem for free.
                        if (harvestLevels && !levelled) {
                            levelled = true
                            val rl = probedLevel?.invoke(r.probedLagMs)
                            val tl = probedLevel?.invoke(t.probedLagMs)
                            // LOG THE WHOLE PROVENANCE OF THE LEVEL PAIR, not just the numbers. A
                            // swapped attribution (drift wrong by the 90 ms between refOff and
                            // tgtOff) reports the reciprocal ratio with full confidence, and the
                            // only way to see that in a log rather than infer it a week later is to
                            // have the two lags, the two raw values, the dB between them and the
                            // drift on one line. Both lags in particular: a swap is visible as the
                            // reference sitting at the target's arrival, and no reduced number can
                            // show that.
                            Log.i(
                                TAG,
                                "level capture ${levelCaptures.size}: " +
                                    "${reference.name}@%.1fms=%.3e ".format(r.probedLagMs, rl ?: 0.0) +
                                    "${target.name}@%.1fms=%.3e ".format(t.probedLagMs, tl ?: 0.0) +
                                    "delta=%+.1fdB drift=%.1fms base=$bi".format(
                                        if (rl != null && tl != null && rl > 0.0 && tl > 0.0) {
                                            20.0 * log10(tl / rl)
                                        } else {
                                            0.0
                                        },
                                        a.driftMs,
                                    ),
                            )
                            if (rl != null && tl != null && rl > 0.0 && tl > 0.0) {
                                levelCaptures += mapOf(reference.id to rl, target.id to tl)
                            }
                        }
                    }
                }
            }
            if (attempt < PROBE_REPEATS - 1) progress("probe capture ${attempt + 2}/$PROBE_REPEATS…")
        }
        commitLatency(reference.id, reference.latencyMs) // reference is never corrected
        if (samples.isEmpty()) {
            return if (refNotFound) {
                failPair(
                    target,
                    "reference speaker ${reference.name} was not detected — it is probably too quiet " +
                        "or too far from this phone; raise its volume and re-run " +
                        "(timeline drift ${lastAttr?.driftMs?.roundToInt() ?: 0}ms)",
                )
            } else {
                failPair(target, "target peak did not move by the probe")
            }
        }
        val deltaMs = weightedMedian(samples)
        Log.i(
            TAG,
            "${target.name}: probe samples=${samples.map { it.first }} " +
                "weights=${samples.map { "%.0f".format(it.second) }} weighted-median=${deltaMs}ms",
        )
        val newLatency = target.latencyMs + deltaMs
        Log.i(
            TAG,
            "pair ${reference.name}/${target.name}: delta=${deltaMs}ms " +
                "latency ${target.latencyMs} -> $newLatency",
        )
        // PLAUSIBILITY FIRST. A delta larger than any real sink gap means the target was matched to
        // a ghost, and it must be thrown out before anything else looks at it.
        //
        // Rig-caught 2026-08-03, calibrating from the OnePlus (its mic sits beside its own speaker,
        // so the reference came in at z~170-190 while the across-room target barely registered):
        // attribution latched a music self-similarity cluster ~2.1 s away and the pair path computed
        // delta=-2144ms. Both probe samples agreed, and they agreed at high weight (19 and 21), so
        // neither repetition, the weighted median, nor the split-half idea can catch this — a stable
        // WRONG match is perfectly self-consistent. The batch path has always gated on
        // MAX_PLAUSIBLE_DELTA_MS; the pair path checked only the deadband and then went straight to
        // applying a two-second correction, which is audible catastrophe rather than a bad trim.
        if (abs(deltaMs) > MAX_PLAUSIBLE_DELTA_MS) {
            commitLatency(target.id, target.latencyMs)
            return "${target.name}: implausible Δ${deltaMs}ms — skipped (suspected mis-attribution)"
        }
        // DAMPING: distrust a correction that contradicts this sink's own recent history by more
        // than the measurement can resolve. A single run is one noisy sample, so without this the
        // system confidently applies a value its next run disagrees with; deferring costs nothing
        // (the next run decides) while applying a bad one moves a speaker audibly out of sync.
        val recent = history?.recent(target.id, DAMPING_HISTORY) ?: emptyList()
        if (recent.size >= DAMPING_MIN_SAMPLES) {
            val prior = recent.sorted()[recent.size / 2]
            if (abs(deltaMs - prior) > DAMPING_TOL_MS) {
                commitLatency(target.id, target.latencyMs)
                return "${target.name}: Δ${deltaMs}ms disagrees with recent history (~${prior}ms) — " +
                    "deferred, re-run to confirm"
            }
        }
        if (abs(deltaMs) <= DEADBAND_MS) {
            // Already aligned within tolerance (same deadband as the batch). Skip the
            // verify: with the two speakers coincident their re-probed peaks overlap and
            // the movedBy step can't tell reference from target — a false "could not
            // identify". Leave the latency untouched and report aligned.
            commitLatency(target.id, target.latencyMs)
            return "${target.name}: already aligned (Δ${deltaMs}ms within deadband)"
        }

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
        // Assign reference and target to DISTINCT re-probed peaks (drift-corrected); the
        // residual is then drift-immune. Avoids the false "could not identify" that picking
        // the strongest peak per offset causes on an aligned pair in a reflective room.
        val (vRef, vTgt) = PeakAttribution.assignVerifyPair(vProbed, vBase, refOff, tgtOff, MATCH_TOL_MS)
            ?: return failPair(target, "verify could not identify reference/target")
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
        val webRetry = mutableListOf<CalClient>() // web clients worth one boosted re-measurement

        overflow.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }

        // Detectability pre-boost. The batch mutes nothing, so this is its only loudness lever, and
        // a speaker left far below full volume cannot clear the peak floor however good the
        // attribution is. It matters most for WEB clients: they are skipped by the muted pair
        // fallback entirely, so the batch is their only measurement and this their only rescue.
        //
        // Gentle by construction, per the "detect without going too loud" rule: it lifts to a FLOOR
        // rather than to the cap, and only touches clients already below that floor, so speakers the
        // listener had at a sane level are left exactly where they were. Restored in the finally.
        //
        // Deliberately EAGER (before the baseline) rather than a boost-then-re-measure round: which
        // speaker is too quiet cannot be known before attribution succeeds, and attribution is the
        // very thing failing, so a lazy round would cost a second full baseline+probe. Acting on the
        // volumes the server already knows is free.
        val preBoost = sim.filter { !it.muted && it.volumePercent < BOOST_FLOOR_PERCENT }
        if (preBoost.isNotEmpty()) {
            progress("raising ${preBoost.size} quiet client(s) to $BOOST_FLOOR_PERCENT% for detectability")
            preBoost.forEach { control.sendSetVolume(it.id, muted = false, percent = BOOST_FLOOR_PERCENT) }
            delay(SETTLE_MS)
        }
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
                val probedLevel = measurer.levelReader()
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
                // Harvest levels for the balance, FROM THE PROBED CAPTURE ONLY.
                //
                // Read at the MATCHED LAG, never off the matched peak's z: a peak's z comes from a
                // top-N search, so a speaker that is absent or under the floor still gets one (the
                // largest noise lump), and those heights track each other instead of the gains —
                // measured at a 0.93 ratio between two pure-noise peaks, which is what the rig kept
                // reporting for an inaudible speaker.
                //
                // The baseline is NOT harvested. There the speakers sit at their natural arrivals,
                // which can be a millisecond apart in a real room, and the level estimator needs
                // about 30 ms of separation to be accurate. The probe offsets (90/180/360 ms)
                // guarantee that separation, so the probed capture is the one place levels can be
                // trusted. Clients the pre-boost touched are excluded: their gain no longer
                // describes their loudness.
                val boosted = preBoost.map { it.id }.toSet()
                val probedLevels = mutableMapOf<String, Double>()
                for (i in -1 until sim.size) {
                    val client = if (i < 0) reference else sim[i]
                    val m = attr.matches[i + 1] ?: continue
                    if (client.id in boosted) continue
                    probedLevel?.invoke(m.probedLagMs)?.takeIf { it > 0.0 }
                        ?.let { probedLevels[client.id] = it }
                }
                if (probedLevels.size >= 2) {
                    // Same provenance line as the pair path: matched lag and raw value per client,
                    // plus the drift, so a swapped attribution is readable in the log.
                    Log.i(
                        TAG,
                        "level capture ${levelCaptures.size}: " + probedLevels.entries.joinToString {
                            val lag = attr.matches
                                .getOrNull((listOf(reference) + sim).indexOfFirst { c -> c.id == it.key })
                                ?.probedLagMs ?: 0.0
                            "${it.key}@%.1fms=%.3e".format(lag, it.value)
                        } + " drift=%.1fms".format(attr.driftMs),
                    )
                    levelCaptures += probedLevels
                }
                if (refMatch == null) {
                    // Reference could not be identified by its displacement → whole batch
                    // untrusted. Remove target probes and degrade them to v1 pair rounds
                    // (which re-write and so overwrite these intended entries).
                    sim.forEach { commitLatency(it.id, it.latencyMs) }
                    progress(
                        "reference speaker ${reference.name} was not detected (too quiet or too far?) " +
                            "— degrading to pair rounds (timeline drift ${attr.driftMs.roundToInt()}ms)",
                    )
                    queueFallback(sim, outcomes, fallback, webRetry)
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
                queueFallback(unattributed, outcomes, fallback, webRetry)

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
                // Boost here: this round only runs because the batch already failed to attribute
                // the target, and the others are muted for it anyway.
                val outcome = mutedPairRound(measurer, reference, target, others, boostPercent = BOOST_RAMP_PERCENT.first())
                if (outcome != null) succeeded += target.id else errored += target.id
                outcomes[target.id] = outcome ?: "${target.name}: failed (see log)"
            }
            // Targeted rescue for web clients the batch couldn't attribute. They have no muted
            // fallback and no OS volume of their own, so without this a single noisy capture ends
            // their story. Bounded: fires only on failure, one extra round each, and nothing is
            // muted for it (the boost alone is the lever).
            for (target in webRetry) {
                progress("re-measuring ${target.name} at the boost ceiling…")
                val outcome = mutedPairRound(
                    measurer, reference, target, others = emptyList(),
                    boostPercent = BOOST_RAMP_PERCENT.last(),
                )
                if (outcome != null) succeeded += target.id
                outcomes[target.id] = outcome
                    ?: "${target.name}: too quiet to measure even at the boost ceiling — " +
                    "move it closer or raise its device volume, then re-run"
            }
            overflow.filter { isWebClient(it) }.forEach {
                outcomes[it.id] = "${it.name}: web client, skipped (too many speakers for one batch)"
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
            // Undo the detectability pre-boost. Last, so a fallback pair round (which may have
            // boosted the same client again) has already finished and restored its own writes.
            preBoost.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
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
        webRetry: MutableList<CalClient>,
    ) {
        for (client in unattributed) {
            if (!isWebClient(client)) {
                fallback += client
            } else if (!client.muted && client.volumePercent < BOOST_RAMP_PERCENT.last()) {
                // A web client has no muted fallback round and no OS volume of its own, so the batch
                // is its ONLY measurement — one noisy capture would otherwise end its story. Give it
                // the one thing left: its SW gain raised to full, and a single re-measurement.
                webRetry += client
            } else {
                // Already at full SW gain (or muted): nothing left to give. Say so in terms the
                // listener can act on rather than reporting an opaque skip.
                outcomes[client.id] = "${client.name}: too quiet to measure at the boost ceiling — " +
                    "move it closer or raise its device volume, then re-run"
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
                val m = DelayMeasurement.measure(snapshot, capture, peakCount)
                lastMeasurement = m
                val peaks = m?.peaks ?: emptyList()
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
        val full = summary + mutedNote
        Log.i(TAG, "${if (success) "done" else "failed"}: $full")
        _state.value = if (success) State.Done(full) else State.Failed(full)
    }

    // ---- volume balance ------------------------------------------------------------


    /**
     * Even the speakers out AT THE MIC, so the stereo image centres where the recording device sits
     * (see [VolumeBalance] for why that, and not equal numbers, is the goal).
     *
     * PERSISTENT, unlike the detectability boost: this is the volume the listener is left with, so
     * it is written once at the end of a run and never restored. Deliberately conservative — it acts
     * only on clients measured at least [MIN_LEVEL_SAMPLES] times in unboosted captures, only when
     * they are actually uneven, and only when the resulting move is big enough to be worth writing.
     * A run that measured nothing usable changes no volumes at all.
     */
    private suspend fun balanceVolumes(clients: List<CalClient>) {
        val byId = clients.associateBy { it.id }
        val name = { id: String -> byId[id]?.name ?: id }
        // Log the RAW per-capture ratios, not just the reduced answer. Whether this feature is
        // viable at all turns on how much of the between-client level difference is real and how
        // much is capture noise, and only the spread of these lines can say: run 1's three
        // unchanged baselines had between-leader ratios of 1.40/1.17/1.00 against a decision
        // threshold of BALANCE_TOL_RATIO=1.25, i.e. the scatter alone straddles the decision. A
        // logged median with no spread behind it is a number nobody can act on.
        levelCaptures.forEachIndexed { i, capture ->
            val loudest = capture.values.maxOrNull() ?: return@forEachIndexed
            Log.i(
                TAG,
                "balance: capture $i " + capture.entries.joinToString {
                    // Raw value printed in exponent form: it is a normalized correlation (~1e-2),
                    // not a z-score, and %.1f truncated every one of them to "0.0" on the rig.
                    "${name(it.key)}=%.2f(raw=%.3e)".format(
                        if (loudest > 0) it.value / loudest else 0.0,
                        it.value,
                    )
                },
            )
        }
        val levels = reduceLevels()
        val measured = clients.filterNot { it.muted }.mapNotNull { c ->
            VolumeBalance.Client(c.id, c.volumePercent, levels[c.id] ?: return@mapNotNull null)
        }
        if (measured.size < 2) {
            Log.i(
                TAG,
                "balance: skipped — only ${measured.size} client(s) measured at least " +
                    "$MIN_LEVEL_SAMPLES time(s) unboosted (${levelCaptures.size} usable capture(s))",
            )
            return
        }
        val describe = { m: VolumeBalance.Client -> "${name(m.id)}=${"%.2f".format(m.measuredLevel)}" }
        if (VolumeBalance.isBalanced(measured)) {
            Log.i(TAG, "balance: already even at the mic (${measured.joinToString { describe(it) }})")
            return
        }
        val gains = VolumeBalance.computeGains(measured)
        if (gains == null) {
            Log.i(TAG, "balance: nothing to act on (${measured.joinToString { describe(it) }})")
            return
        }
        val current = measured.associate { it.id to it.gainPercent }
        if (!VolumeBalance.isWorthApplying(current, gains)) {
            Log.i(TAG, "balance: correction too small to write ($current -> $gains)")
            return
        }
        Log.i(TAG, "balance: levels ${measured.joinToString { describe(it) }} volumes $current -> $gains")
        // Record what is about to be overwritten BEFORE overwriting it, so an interruption can never
        // leave written volumes with no way back. Only the clients actually being written are
        // recorded: restoring a client the balance never touched would be its own surprise.
        volumeUndo?.save(current.filterKeys { it in gains.keys })
        // NonCancellable: these writes are PERSISTENT and the room is left in whatever state they
        // reach. Cancelling the run (the host cancels the job on stop) between two of them would
        // suspend-throw partway through and strand a half-balanced set of volumes — worse than
        // either balancing or not. The full set is small and local, so completing it always is
        // cheap. Every other write in this class is either transient or restored by a finally.
        withContext(NonCancellable) {
            gains.forEach { (id, percent) -> control.sendSetVolume(id, muted = false, percent = percent) }
        }
        val note = "; balanced volumes: " + gains.entries.joinToString { (id, g) -> "${name(id)} $g%" } +
            if (volumeUndo != null) " (undoable)" else ""
        when (val s = _state.value) {
            is State.Done -> _state.value = State.Done(s.summary + note)
            is State.Failed -> _state.value = State.Failed(s.reason + note)
            else -> Unit
        }
    }

    /** True when a balance is outstanding and [undoBalance] has something to put back. Lets the host
     *  offer the undo only when it would do something. */
    fun canUndoBalance(): Boolean = volumeUndo?.load() != null

    /**
     * Put back the volumes the last balance overwrote, and return the clients restored (empty if
     * there was nothing to undo).
     *
     * Unmutes as it goes, matching what the balance itself wrote: a balanced client was written
     * `muted = false`, so restoring the percentage while leaving a mute the balance had cleared would
     * hand back a state the user never had. Unlike [recover] this is a deliberate user action on a
     * completed run, so the record is consumed — pressing undo twice does not walk further back.
     *
     * NonCancellable for the same reason the balance writes are: a partial restore is worse than
     * either end state.
     */
    suspend fun undoBalance(): List<String> {
        val previous = volumeUndo?.load() ?: return emptyList()
        Log.i(TAG, "undoing balance for ${previous.size} client(s): $previous")
        withContext(NonCancellable) {
            previous.forEach { (id, percent) ->
                control.sendSetVolume(id, muted = false, percent = percent)
            }
        }
        volumeUndo.clear()
        return previous.keys.toList()
    }

    /**
     * Reduce [levelCaptures] to one level per client, keeping only clients whose captures AGREE
     * with each other. Clients that disagree are dropped, and a dropped client is not balanced.
     *
     * Two steps, and the second one is a safety gate rather than statistics.
     *
     * **Centre each capture on its own mean, in log space.** A raw correlation value depends on that
     * capture's program material and mic gain, and on a solo capture it is nearly independent of the
     * speaker's own volume (see [Dsp.crossCorrelateLevel]) — but those factors are COMMON to every
     * client in one capture, so they cancel in a within-capture comparison and leave exactly the
     * between-speaker difference the balance acts on. Values from different captures never cancel
     * and are meaningless to compare directly. Log space rather than "divide by the loudest" because
     * the quantity is a ratio, decibels are the unit a tolerance is meaningful in, and centring on
     * the mean is symmetric: it does not silently anoint one client as the reference.
     *
     * **Then require the captures to agree, and treat a SIGN flip as fatal.** Before this gate
     * existed the median was taken and written out with full confidence however much the inputs
     * disagreed: given {0.81, 0.38, 0.90} it wrote 0.81. The only thing that ever stopped a
     * persistent wrong volume was a capture happening to fail. Two distinct rejections:
     *
     *  - **Sign disagreement.** Attribution can swap the two clients — a global drift estimate wrong
     *    by the 90 ms between `refOff` and `tgtOff` matches each client at the other's arrival
     *    ([PeakAttribution.attribute] adopts whichever drift attributes more entities). A swap
     *    reports the RECIPROCAL ratio, so in centred log space it flips the sign of every client.
     *    One capture calling a speaker louder than average while another calls it quieter is that
     *    signature, and nothing else plausible produces it, so it declines the client outright
     *    instead of being averaged away. Guarded by [LEVEL_SIGN_DEADBAND_DB] so a genuinely
     *    balanced room, sitting near zero, does not trip it on noise.
     *  - **Spread.** Captures further than [LEVEL_AGREEMENT_DB] from the median are dropped as
     *    outliers, and the surviving count — not the count that merely produced a number — is what
     *    must reach [MIN_LEVEL_SAMPLES]. The gate is "survived AND agreed".
     *
     * Returns levels on a linear scale normalised to the loudest surviving client, which is what
     * [VolumeBalance] expects; it uses only ratios, so the choice of scale does not reach the output.
     */
    internal fun reduceLevels(): Map<String, Double> {
        // Centred log levels, in dB, one list per client. A capture with fewer than two clients
        // carries no between-client information at all and is not evidence of anything.
        val centred = LinkedHashMap<String, MutableList<Double>>()
        for (capture in levelCaptures) {
            val usable = capture.filterValues { it > 0.0 }
            if (usable.size < 2) continue
            val meanLog = usable.values.sumOf { ln(it) } / usable.size
            usable.forEach { (id, v) ->
                centred.getOrPut(id) { mutableListOf() } += 20.0 / LN10 * (ln(v) - meanLog)
            }
        }
        val kept = LinkedHashMap<String, Double>()
        for ((id, samples) in centred) {
            val hiSide = samples.any { it > LEVEL_SIGN_DEADBAND_DB }
            val loSide = samples.any { it < -LEVEL_SIGN_DEADBAND_DB }
            val fmt = samples.joinToString { "%+.1f".format(it) }
            if (hiSide && loSide) {
                Log.w(
                    TAG,
                    "balance: $id DECLINED — captures disagree on SIGN ($fmt dB), which is the " +
                        "signature of a swapped attribution, not noise",
                )
                continue
            }
            val mid = median(samples)
            val agreed = samples.filter { abs(it - mid) <= LEVEL_AGREEMENT_DB }
            if (agreed.size < MIN_LEVEL_SAMPLES) {
                Log.i(
                    TAG,
                    "balance: $id DECLINED — only ${agreed.size} of ${samples.size} capture(s) " +
                        "agree within ${LEVEL_AGREEMENT_DB}dB of the median %+.1fdB ($fmt dB); ".format(mid) +
                        "need $MIN_LEVEL_SAMPLES",
                )
                continue
            }
            val estimate = median(agreed)
            Log.i(
                TAG,
                "balance: $id level %+.1fdB from ${agreed.size}/${samples.size} agreeing capture(s) "
                    .format(estimate) + "(spread %.1fdB)".format(
                    (agreed.maxOrNull() ?: 0.0) - (agreed.minOrNull() ?: 0.0),
                ),
            )
            kept[id] = estimate
        }
        if (kept.isEmpty()) return emptyMap()
        // Back to the linear scale VolumeBalance works in, largest at 1.0.
        val loudestDb = kept.values.max()
        return kept.mapValues { (_, db) -> 10.0.pow((db - loudestDb) / 20.0) }
    }

    /** Middle value of [values] (mean of the two middle ones when there is an even number). */
    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    /** Outcome of gating one computed correction. */
    enum class Gate { APPLY, ALIGNED, DEFERRED, IMPLAUSIBLE }

    /**
     * Median of [samples] (value to weight) with each sample counted in proportion to its weight:
     * sort by value and take the value where the running weight crosses half the total.
     *
     * Still a median, so a wild outlier is ignored outright rather than dragging the result the way
     * a weighted mean would — but a confident measurement now outvotes a marginal one instead of
     * counting the same. Degenerates to a plain median when the weights are equal.
     */
    internal fun weightedMedian(samples: List<Pair<Int, Double>>): Int {
        val sorted = samples.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2.0
        var run = 0.0
        for ((value, weight) in sorted) {
            run += weight
            if (run >= half) return value
        }
        return sorted.last().first
    }

    companion object {
        private const val TAG = "SyncCalibrator"

        /**
         * Undo a calibration run that a process death interrupted. If [journal] still holds
         * originals, the previous run never reached its finally, so restore each client's
         * pre-run latency via [setLatency] and volume via [setVolume], then clear the journal.
         * Call once at host startup
         * (after the server control connection is up) BEFORE any new run. Safe to call when
         * no journal exists (returns empty). Returns the restored client ids.
         */
        suspend fun recover(
            journal: CalibrationJournal,
            setLatency: suspend (clientId: String, latencyMs: Int) -> Unit,
            setVolume: suspend (clientId: String, muted: Boolean, percent: Int) -> Unit,
        ): List<String> {
            val pending = journal.load() ?: return emptyList()
            Log.w(TAG, "recovering ${pending.size} client(s) from an interrupted run: ${pending.keys}")
            // Blind restore of pre-run state (latency AND volume/mute) — NOT compare-and-restore:
            // the likeliest crash state is a transient probe value (latency − probe, and possibly
            // a muted "other" from a pair round) that matches no durable record, so a
            // restore-only-if-unchanged guard would strand exactly that state. The only cost is
            // reverting a deliberate human tweak made in the crash→next-start window: visible,
            // one-off, human-recoverable (see ADVISOR-volume-calibration-REPLY-2.md). Restoring
            // an offline client is fine — the server persists it and applies it on reconnect.
            pending.forEach { (id, s) ->
                setLatency(id, s.latencyMs)
                setVolume(id, s.volumeMuted, s.volumePercent)
            }
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

        /**
         * Simultaneous probe offsets (slot 0 = reference, rest = targets): a Sidon set, so every
         * value in (offsets ∪ pairwise differences) is distinct and no shifted grid can be confused
         * with another. Here that union is {90,180,270,360}, a **90 ms minimum gap**.
         *
         * The gap must exceed the ROOM REFLECTION SPREAD, not merely 2×MATCH_TOL. The previous set
         * {60,90,210,390} satisfied the tolerance rule (30 ms gaps = 2×MATCH_TOL) but rig-measured
         * reflections spread 50-80 ms around a direct path, so a speaker's own reflection could sit
         * exactly where another speaker's probe shift was expected: with offsets 60 and 90, a
         * reference reflection 30 ms late masquerades perfectly as the target. That produced
         * confident, verify-passing corrections that disagreed across runs by tens of ms.
         *
         * Cost of the fix: three slots instead of four, so a batch covers 2 targets and the rest
         * degrade to pair rounds. Correctness over capacity — a bigger batch is worthless if its
         * attributions are ambiguous. Max offset 360 ms stays below the old 390 ms, so the
         * stream-hiccup risk of a large latency step does not get worse.
         */
        val PROBE_SET_MS = listOf(90, 180, 360)

        private const val WEB_CLIENT_PREFIX = "qcweb-"


        /** Floor the no-mute batch lifts a too-quiet client to before measuring. A FLOOR, not the
         *  cap: the batch runs while people are listening, so it must not shout — it only raises
         *  speakers already below this and leaves the rest untouched. PLACEHOLDER pending a rig
         *  characterization of z against volume at a realistic distance; the one sweep so far
         *  crossed the z=9 attribution floor near 10-12% on a mid-room speaker, so this carries
         *  margin without going loud. */
        private const val BOOST_FLOOR_PERCENT = 50

        /** Coarse boost ramp, tried in order. It stops WELL SHORT OF FULL VOLUME, and that ceiling
         *  is a hard requirement rather than a tuning choice.
         *
         *  Rig-measured (one speaker alone, only its device volume varied, counting peaks over the
         *  z=9 floor): 25% → 0 peaks, 50% → 4 peaks (direct path z=13), 75% → 9 peaks. Level lifts a
         *  speaker's ROOM REFLECTIONS over the floor along with its direct path, and those ghosts
         *  cluster within ~50 ms while the probe offsets are only 30 ms apart — so past the point of
         *  being detectable, louder actively degrades attribution.
         *
         *  The old ramp ended at 100. It was removed on 2026-08-01 for three converging reasons: the
         *  owner's standing rule is the MINIMUM level that measures, not the maximum available; a
         *  calibration that shouts is one people switch off, which costs far more accuracy than any
         *  extra z buys; and full scale is the one operating point where limiter/compressor
         *  nonlinearity plausibly distorts the correlation the whole method rests on. A speaker that
         *  cannot be found at 75% is reported as too quiet and the listener is told to move it or
         *  raise its device volume — a decision they can make, unlike an automatic blast.
         *
         *  Not an estimator — near the floor a single capture is far too noisy to invert (the same
         *  speaker read z=13 and z=9 on adjacent captures), so these are fixed coarse levels. */
        private val BOOST_RAMP_PERCENT = listOf(60, 75)

        /** Lease handed to a boosted client. Sized off the round it must cover, not a round number:
         *  a pair round is 4 measurements × [CAPTURE_MS] plus four [SETTLE_MS] ≈ 76 s nominal, and
         *  each measurement may retry once (+[RETRY_BACKOFF_MS] +[CAPTURE_MS]) for ≈156 s worst
         *  case. The rounds most likely to retry are the marginal quiet-target ones — exactly the
         *  rounds the boost exists for — so a lease that only covered the nominal case would lapse
         *  precisely when it matters. Renewals are kept rare (each re-serializes the host's
         *  metadata, art blob included), hence one generous lease rather than a ticker. */
        private const val OS_BOOST_LEASE_MS = 180_000L

        /** Number of probe captures whose deltas are reduced to a median. Three trades ~24 s of
         *  extra capture for roughly halving the error and, more importantly, discarding the
         *  occasional wild sample outright. */
        private const val PROBE_REPEATS = 3

        /** Corrections below this are within measurement noise AND ~the audible-sync floor: leave
         *  the client where it is rather than chase noise. Raised from 15 ms once the rig showed a
         *  single capture resolves the residual to only ~±20 ms — at 15 ms the SAME unchanged setup
         *  reported "trim -60ms" and "already aligned" on consecutive runs, which are one
         *  measurement drawn twice. Sized at ~2x the post-median noise. */
        private const val DEADBAND_MS = 25

        /** How many past corrections the damping check considers, and the minimum it needs before
         *  it will veto anything (one prior value is not a history). */
        private const val DAMPING_HISTORY = 5
        private const val DAMPING_MIN_SAMPLES = 2

        /** A new delta further than this from the sink's recent median is treated as a bad sample
         *  and deferred. Wide enough to let a genuine change through after two consistent runs,
         *  tight enough to reject the ±40 ms outliers seen on-rig. */
        private const val DAMPING_TOL_MS = 45
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

        /** Unboosted level readings a client needs before the balance will move its volume.
         *
         *  THREE, not two, and the first rig run is why. It balanced off two captures that put the
         *  same speaker at 0.93 and 0.51 — a 1.8x spread on the very quantity being balanced — and a
         *  median of two is just their midpoint, so a 44-point volume change rested on two readings
         *  that flatly disagreed. Three is the smallest count at which the median is an actual vote
         *  rather than an average, so one bad capture is outvoted instead of averaged in.
         *
         *  The cost is runs that decline to balance, which is the right way to be wrong here: the
         *  levels are still logged, and the next run decides. */
        internal const val MIN_LEVEL_SAMPLES = 3

        /** How far apart two captures' level readings for one client may sit and still count as the
         *  same measurement, in dB of centred log level.
         *
         *  Tied to the decision it feeds, not chosen for comfort: [VolumeBalance.BALANCE_TOL_RATIO]
         *  decides "even enough" at 1.25, which is 1.9 dB, so evidence whose own captures scatter by
         *  more than a few dB cannot support a verdict at that granularity. Three is the point where
         *  the rig's own history splits cleanly: runs B and C agreed to better than 0.5 dB, while
         *  run A's two captures (1.23:1 against 2.63:1) sit 3.3 dB apart in this space and are
         *  exactly the reading that must not be acted on. */
        internal const val LEVEL_AGREEMENT_DB = 3.0

        /** Below this the sign of a centred level is not evidence of anything, so the swap check
         *  ignores it. A room that really is balanced sits at 0 dB and its captures will straddle
         *  zero by noise alone; without a deadband that reads as a swap every time. 1 dB is half the
         *  balance's own decision threshold, so nothing inside it could change an outcome anyway. */
        internal const val LEVEL_SIGN_DEADBAND_DB = 1.0

        private const val LN10 = 2.302585092994046
    }
}
