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
        override suspend fun measure(peakCount: Int, sources: Int) = micMeasure(ring, peakCount, sources)
        override suspend fun measureHalves(peakCount: Int, sources: Int) =
            micMeasureHalves(ring, peakCount, sources)
        // LEVELS ARE READ WITH A LOCAL SEARCH, not at a point. The lag a round knows an arrival by
        // carries 10-70 ms of uncertainty once a speaker is more than a few dB down (rig
        // 2026-08-11: the same quiet speaker matched 74 ms apart in consecutive captures), and a
        // ±3 ms point read at a lag that far off lands on nothing — which is how a 12 dB lopsided
        // room came to be reported as "already even". A window wide enough to contain that
        // uncertainty but far narrower than the 380 ms probe separation finds the arrival without
        // ever reaching the other speaker. This is exactly what makes the offline grader
        // trustworthy at this imbalance (it tracked a commanded 12.4 dB step to 0.08 dB while a
        // blind search could not locate the quiet speaker at all). Both clients get the same
        // treatment, so the RATIO — the only thing the balance uses — stays honest.
        override fun levelReader(): ((Double) -> Double)? =
            lastMeasurement?.let { m -> { lag -> Dsp.levelAt(m.levelCorrelation, m.fs, lag, LEVEL_READ_HALF_MS) } }
        override fun timingReader(): ((Double, Double) -> Dsp.Peak?)? =
            lastMeasurement?.let { m -> { lag, tol -> m.timingPeakNear(lag, tol) } }
        override fun fullPeaks(): List<Dsp.Peak>? = lastMeasurement?.peaks
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

    /**
     * Per level capture, whether its GEOMETRY looked wrong — parallel to [levelCaptures].
     *
     * A genuine attribution swap and ordinary level noise look identical if you only inspect
     * levels, which is all the sign gate used to do. They do NOT look identical in the lags: a
     * swap exchanges which arrival each speaker was read at, so the pair's measured separation
     * departs from the round's consensus spacing by about twice the probe difference, while noise
     * leaves the separation intact and moves only the value. Recording the geometry per capture
     * lets the gate tell "this run may contain a swap" from "this run is noisy near balance".
     *
     * Rig 2026-08-13: one capture of five reported the opposite sign, and its lags (1264.4 /
     * 1651.4 ms, separation 387) matched the rest of the round (1267.3 / 1643.3, separation 377)
     * almost exactly — noise, not a swap, yet the whole run was declined as a suspected swap.
     */
    private val levelCaptureSwapSuspect = mutableListOf<Boolean>()

    /** True once any round in this run harvested no levels because it was BOOSTED. A boosted
     *  speaker is louder than its SW gain says, so its levels are disqualified deliberately —
     *  which is a different thing from the mic never hearing it, and must not be reported as if
     *  the user should go and move a speaker. */
    private var levelsSkippedForBoost = false

    /** True once [harvestLevelsUnboosted] has actually run for some target this run. Separates the
     *  two ways a boosted run can end with no levels — "nothing tried to recover them" from "the
     *  recovery ran and the speaker was not measurable at its real volume" — which are different
     *  problems with different user actions, and the whole point of the recovery is that the second
     *  one gets SAID rather than looking like the first. */
    private var levelRecoveryAttempted = false


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
    suspend fun calibrate(connectedClients: List<CalClient>): Boolean {
        // DROP THE REFERENCE TAP BEFORE ANYTHING ELSE LOOKS AT THE LIST.
        //
        // A client-side calibration run gets its reference PCM by starting a SECOND snapclient with
        // `--player file:`, because one snapclient can either play or expose its PCM, never both
        // (rig-verified 2026-08-19). That tap is a genuine connected client from the server's point
        // of view: it appears in the client list at 100 % volume with no name, and it emits no
        // sound at all.
        //
        // Left in, it is not merely noise — it silently disables the balance. Two real speakers
        // plus the tap is THREE clients, which routes the run to the batch path, where
        // [levelProbeSet] declines the 940 ms excursion three speakers need and the round harvests
        // no levels by design. The user would see sync work and the balance quietly do nothing,
        // which is the exact failure the boost-gap work exists to prevent.
        //
        // Filtered here rather than at the call site so no host can forget: the calibrator owns the
        // rule that a silent instrument is not a speaker.
        val allClients = connectedClients.filterNot { isReferenceTap(it) }
        val taps = connectedClients.size - allClients.size
        if (taps > 0) Log.i(TAG, "ignoring $taps reference tap client(s) — they emit no sound")
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
        levelCaptureSwapSuspect.clear()
        levelsSkippedForBoost = false
        levelRecoveryAttempted = false
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
            var boosted = false
            if (outcome == null && canBoost(target)) {
                for (level in BOOST_RAMP_PERCENT) {
                    progress("${target.name}: retrying with a $level% detectability boost")
                    outcome = mutedPairRound(measurer, reference, target, others, boostPercent = level)
                    if (outcome != null) {
                        boosted = true
                        break
                    }
                }
            }
            // THE BOOST GAP. A boosted round throws its loudness readings away on purpose (a
            // boosted speaker is louder than the SW gain the balance reasons about), so up to here
            // the run has calibrated SYNC and left the balance with nothing — in exactly the rooms
            // that need balancing most, since a room where detection is marginal is a room where
            // one speaker is much further from the listener than the other. Easy rooms barely need
            // balancing; hard ones silently got none.
            //
            // What the boost bought is the TIMING, and timing is the expensive half: the two
            // arrivals' separation under the level probe set is now known before a single capture
            // is taken. Levels are the only thing missing, and they need the speakers at their real
            // gains — so take one more round with nothing boosted anywhere.
            //
            // Gated on a MEASURED standing misalignment, not merely on the round returning a
            // summary: the implausible and deferred paths also return one, and they leave a
            // standing error nobody measured. Harvesting at an assumed spacing then reads the quiet
            // speaker at a lag hundreds of ms off — a confident wrong number, which is worse than
            // today's silent nothing.
            val standing = outcome?.standingMisalignmentMs
            if (boosted && standing != null && outcome != null) {
                harvestLevelsUnboosted(
                    measurer, reference, target, others, outcome.targetLatencyMs, standing,
                )
            }
            if (outcome != null) successes++
            results += outcome?.summary ?: "${target.name}: failed (see log)"
        }
        finish(successes > 0, results.joinToString("; "))
        return successes == targets.size
    }

    /**
     * What a pair round achieved, beyond the human summary that used to be its whole return value.
     *
     * [standingMisalignmentMs] is the target-minus-reference arrival difference REMAINING at the
     * latencies this round left committed, and it is non-null only on the two paths that actually
     * measured it: the verified trim (its re-measured residual) and the deadband (the delta it
     * deliberately declined to act on). The implausible-delta and history-deferred paths return a
     * summary and NO misalignment on purpose — both end with a standing error nobody established,
     * so a follow-up round must not assume it knows where the two arrivals sit.
     */
    private data class PairResult(
        val summary: String,
        /** The latency the target is LEFT AT — the new trim on a verified round, its pre-run value
         *  on every other path. A follow-up round must probe from here; probing from
         *  `target.latencyMs` would quietly undo a trim this round just verified. */
        val targetLatencyMs: Int,
        val standingMisalignmentMs: Int?,
    )

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
    ): PairResult? {
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
            if (boost) levelsSkippedForBoost = true
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
    ): PairResult? {
        // REPEAT THE BASELINE, not just the probe. Rig-measured: five identical baseline captures
        // put the two speakers' peak SPACING — which is exactly what the delta is made of — at
        // 37.6/41.0/45.8/54.3/71.5 ms, a 34 ms wander with nothing changed. That single number
        // explains the run-to-run correction spread chased all day, and it explains why three probe
        // captures inside one run agreed to 1 ms: they were all compared against ONE baseline and
        // inherited the same error. The noise is drawn once per run, before probing starts.
        progress("measuring baseline (${reference.name} vs ${target.name})…")
        val baselines = mutableListOf<List<Dsp.Peak>>()
        // The UNFILTERED peaks of each baseline, kept for the reference rescue's window anchors.
        // When the speakers are far apart at the mic the quiet one is sub-salient in the baseline
        // too (rig 2026-08-10 21:32: reference region z 6.4-8.3, deleted by the z≥9 filter), so
        // the salient leaders alone leave the rescue nowhere to anchor.
        val baselineFull = mutableListOf<List<Dsp.Peak>>()
        repeat(PROBE_REPEATS) { attempt ->
            measurer.measure(PAIR_PEAKS, sources = 2)?.let {
                baselines += it
                baselineFull += measurer.fullPeaks() ?: it
            }
            if (attempt < PROBE_REPEATS - 1) progress("baseline capture ${attempt + 2}/$PROBE_REPEATS…")
        }
        if (baselines.isEmpty()) return failPair(target, "baseline measurement failed")

        // Probe BOTH the reference and the target, each by its own offset, and identify
        // both by DISPLACEMENT — same common-mode fix as the batch path. Electing the
        // reference as "the peak that stayed put" (the old v1 rule) can pick a fixed music
        // self-similarity ghost and bias the delta invisibly (seen on-rig 2026-07-22).
        // WIDER OFFSETS WHEN LEVELS ARE BEING HARVESTED. Sync only needs the arrivals separated
        // past the reflection spread ([PROBE_SET_MS], 90 ms gaps); levels need ~380 ms so the quiet
        // arrival clears the loud speaker's reverb tail. Taking the wider set unconditionally would
        // pay a 470 ms excursion on every sync-only run for nothing, so it is chosen by what this
        // round is actually for. If the wider set is unavailable the round still calibrates sync and
        // simply does not harvest — see [levelProbeSet].
        val levelSet = if (harvestLevels) levelProbeSet(2) else null
        val probeSet = levelSet ?: PROBE_SET_MS
        val harvest = harvestLevels && levelSet != null
        if (harvestLevels && levelSet == null) {
            Log.i(
                TAG,
                "balance: not harvesting levels from this pair — no probe set separates 2 speakers " +
                    "within the ${MAX_VERIFIED_PROBE_MS}ms verified write envelope",
            )
        }
        val refOff = probeSet[0]
        val tgtOff = probeSet[1]
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
        // TARGETED REFERENCE RESCUE, collected while iterating and adjudicated after the loop.
        //
        // Measured on this rig (replayed offline from the 2026-08-10 20:04 failing run and four PCM
        // dumps): in a two-speaker capture a PHAT peak carries only its source's share of the
        // energy, so a reference that reads z≈12 solo reads z≈6-9 beside a sibling — under
        // MIN_PEAK_Z — and the blind per-source search can spend its second anchor on the loud
        // speaker's 84-127 ms reflection tail instead. The reference then never reaches attribution
        // at all: in all nine baseline×probe pairings of the failing run the +[refOff] shift had NO
        // candidate within tolerance (residuals 301-466 ms), while the target matched every time.
        // Its position is still pinned by geometry — baseline leader + [refOff] + drift — so when
        // blind attribution finds the target but not the reference, the whitened correlation is
        // read directly at that window instead of declaring the reference missing.
        //
        // A single window read cannot be trusted alone: true arrivals measured z 6.5-9.0 against
        // ±15 ms noise-window maxima of p95 5.1-6.0, an overlap. What separates them is that a real
        // reference RECURS: the within-capture spacing target−reference is common-mode clean (the
        // whole timeline wobbles ±10-20 ms between captures, but both speakers wobble together), so
        // rescue is accepted only when the spacing agrees across probe captures. Window noise peaks
        // land at a fresh lag every capture and cannot fake that.
        data class RefRescue(
            val capture: Int,
            val spacingMs: Double, // target probed lag − rescued reference lag (wobble-free)
            val refLagMs: Double,
            val z: Double,
            val deltaMs: Int,
            val weight: Double,
            val tgtZ: Double,
            val read: ((Double) -> Double)?,
            val peak: ((Double, Double) -> Dsp.Peak?)?,
        )
        val rescues = mutableListOf<RefRescue>()
        /** Probe captures that already produced a level candidate. */
        val levelledCaptures = mutableSetOf<Int>()

        val levelCands = mutableListOf<LevelCand>()
        // HARVEST ROUNDS TAKE TWICE THE CAPTURES, because the level estimator is far noisier per
        // capture than the sync estimator and the balance is the thing being starved.
        //
        // Two independent measurements agree on the number. The project's own solo sweep
        // (2026-08-07) put the spread at a FIXED gain at 8.29 dB and concluded that 5-6 captures
        // averaged are needed to reach 0.5 dB. Reproduced 2026-08-11 in the simplest possible
        // configuration — one speaker playing alone, gain untouched, no attribution involved — where
        // consecutive captures read 8-13 dB apart. The estimator is not broken; it is noisy, and
        // the remedy is arithmetic rather than cleverness.
        //
        // Against that, the three runs of 2026-08-11 yielded 3, 1 and 4 usable level captures from
        // 4 probes (attribution does not succeed on every capture, and a run whose sync lands in
        // the deadband returns before the verify harvest). Six probes at the observed ~65 % yield
        // lands around 4 usable plus the verify capture, which reaches the 5-6 the estimator needs.
        // Sync-only rounds are unaffected and keep paying 3.
        val probeRepeats = if (harvest) PROBE_REPEATS * 2 else PROBE_REPEATS
        // ABANDON A HARVEST ROUND'S EXTRA CAPTURES ONCE IT IS CLEARLY NOT WORKING.
        //
        // The doubling above is bought for the LEVELS, and a round that cannot attribute harvests no
        // levels at all — so on a failing round those extra three captures buy literally nothing and
        // cost 36 s. That is not hypothetical: both failed unboosted attempts of 2026-08-18 spent
        // all six probes and produced zero samples and zero level candidates before failing, and a
        // boosted run pays this on its FIRST round by construction (the unboosted attempt failing is
        // what triggers the boost).
        //
        // The bar is deliberately weak — nothing at all after [PROBE_REPEATS] captures, neither a
        // blind sample nor a rescue candidate. A round that is merely struggling (one sample, or
        // rescue candidates that have not yet reached quorum) still gets its full budget, because
        // those are exactly the marginal rounds the extra captures exist to rescue. Only a round
        // with no evidence whatsoever stops early, and it would have to invent all of it in the
        // remaining captures to change its own outcome.
        for (attempt in 0 until probeRepeats) {
            if (harvest && attempt >= PROBE_REPEATS && samples.isEmpty() && rescues.isEmpty()) {
                Log.i(
                    TAG,
                    "${target.name}: abandoning the harvest round's remaining " +
                        "${probeRepeats - attempt} capture(s) — $attempt probes produced no " +
                        "attribution and no rescue candidate, and the extra captures exist only " +
                        "to feed levels this round can no longer harvest",
                )
                break
            }
            val probed = measurer.measure(PAIR_PEAKS, sources = 2)
            val probedLevel = measurer.levelReader()
            val probedTiming = measurer.timingReader()
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
                    if (r == null && t != null && probedTiming != null) {
                        // The target anchors this capture: its own residual carries the capture's
                        // drift, and every baseline leader OUTSIDE the target's cluster — taken
                        // from the FULL pre-filter list, because the masked reference is usually
                        // sub-salient in the baseline as well — is a candidate origin for the
                        // reference. Read the probed correlation at leader + refOff and keep the
                        // strongest window peak as this pairing's rescue candidate. Anchors still
                        // need z ≥ RESCUE_MIN_Z: below that the "origin" is indistinguishable
                        // from the correlation floor and every lag would open a window.
                        val cand = PeakAttribution.clusterLeaders(baselineFull[bi])
                            .filter {
                                it.z >= RESCUE_MIN_Z &&
                                    abs(it.lagMs - t.baselineLagMs) > PeakAttribution.CLUSTER_MS
                            }
                            .mapNotNull { b -> probedTiming(b.lagMs + refOff + a.driftMs, MATCH_TOL_MS) }
                            .maxByOrNull { it.z }
                        if (cand != null && cand.z >= RESCUE_MIN_Z) {
                            val baseZ = base.maxOfOrNull { it.z } ?: 0.0
                            rescues += RefRescue(
                                capture = attempt,
                                spacingMs = t.probedLagMs - cand.lagMs,
                                refLagMs = cand.lagMs,
                                z = cand.z,
                                deltaMs = ((t.probedLagMs - tgtOff) - (cand.lagMs - refOff)).roundToInt(),
                                weight = minOf(baseZ, cand.z, t.z),
                                tgtZ = t.z,
                                read = probedLevel,
                                peak = probedTiming,
                            )
                        }
                    }
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
                        if (harvest && !levelled && probedLevel != null) {
                            levelled = true
                            levelledCaptures += attempt
                            levelCands += LevelCand(
                                attempt, probedLevel, probedTiming,
                                r.probedLagMs, t.probedLagMs, r.z, t.z,
                            )
                        }
                    }
                }
            }
            if (attempt < probeRepeats - 1) progress("probe capture ${attempt + 2}/$probeRepeats…")
        }
        commitLatency(reference.id, reference.latencyMs) // reference is never corrected
        // MASKING IS PER-CAPTURE, so adjudicate rescues whenever there are any — not only when the
        // whole round failed. Rig 2026-08-11 01:36: captures 1 and 4 attributed blind while 2 and 3
        // lost the reference entirely, and gating the rescue on "nothing attributed" silently threw
        // the other two away, leaving the balance one capture short of MIN_LEVEL_SAMPLES.
        //
        // Rescued captures contribute LEVELS (the starved quantity) always, but contribute a sync
        // DELTA only when blind attribution produced none: the blind samples are the more
        // trustworthy evidence for the correction that actually gets written, and this keeps the
        // proven sync path exactly as it was.
        val hadBlindSamples = samples.isNotEmpty()
        if (rescues.isNotEmpty()) {
            // Adjudicate the rescue: the modal spacing group must span at least
            // RESCUE_MIN_CAPTURES distinct probe captures. One capture's agreement with itself
            // (several baselines pairing the same probed capture) proves nothing — the same noise
            // peak would be read each time — so the quorum counts CAPTURES, not candidates.
            // The quorum SCALES WITH THE NUMBER OF CAPTURES. "At least k of n agree within a
            // window" gets easier to satisfy by chance as n grows — with a ±15 ms window and a
            // 5 ms agreement band there are n(n−1)/2 chances for two noise reads to coincide, so a
            // fixed k=2 that was safe at 3 captures is not safe at 6. Caught by the adversarial
            // noise test the moment harvest rounds doubled their captures: window noise elected a
            // reference and produced a 26 ms "correction" out of nothing.
            val quorum = maxOf(RESCUE_MIN_CAPTURES, (probeRepeats + 2) / 3 + 1)
            val group = rescues
                .map { c -> rescues.filter { abs(it.spacingMs - c.spacingMs) <= RESCUE_AGREE_MS } }
                .maxByOrNull { g -> g.distinctBy { it.capture }.size }
                ?.takeIf { g -> g.distinctBy { it.capture }.size >= quorum }
            if (group != null) {
                Log.i(
                    TAG,
                    "reference ${reference.name} rescued at its expected probe position: " +
                        group.joinToString {
                            "cap${it.capture}@%.1fms(z=%.1f, spacing %.1fms)".format(it.refLagMs, it.z, it.spacingMs)
                        },
                )
                if (!hadBlindSamples) for (c in group) samples += c.deltaMs to c.weight
                if (harvest) {
                    // Never twice from one capture: a capture that already produced a candidate
                    // reads the same two lags of the same audio.
                    for (c in group.distinctBy { it.capture }.filter { it.capture !in levelledCaptures }) {
                        val read = c.read ?: continue
                        levelledCaptures += c.capture
                        levelCands += LevelCand(
                            c.capture,
                            read,
                            c.peak,
                            c.refLagMs,
                            c.refLagMs + c.spacingMs,
                            c.z,
                            c.tgtZ,
                        )
                    }
                }
            }
        }
        if (samples.isEmpty()) {
            return if (refNotFound) {
                // NOT "too quiet or too far": both speakers on this rig measure well above the
                // solo detection floor (verified 2026-08-10). In a shared capture the reference's
                // correlation share sits below the salience floor and the targeted rescue found no
                // spacing-consistent arrival either. Raising volumes does not help — it lifts the
                // other speaker's reflections too.
                failPair(
                    target,
                    "reference speaker ${reference.name} was not found at its expected probe " +
                        "position (baseline+${refOff}ms) — it is masked below the shared-capture " +
                        "correlation floor. Its solo level is fine; do not raise volumes. " +
                        "(rescue candidates=${rescues.size}, timeline drift " +
                        "${lastAttr?.driftMs?.roundToInt() ?: 0}ms)",
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
        // PINNED LEVEL HARVEST — read each capture at ONE searched lag and one DERIVED lag.
        //
        // Reading both arrivals where each capture happened to match them is what broke the
        // balance on a genuinely lopsided room (rig 2026-08-11 01:54, ONEPLUS 12.4 dB down): the
        // quiet speaker's peak cannot be located reliably capture to capture — it was matched at
        // 1765.9 ms in one and 1691.8 ms in the next, 74 ms apart — and a level read at the wrong
        // lag is a wrong level. The two captures reported −8.7 dB and −1.2 dB for a room an
        // independent capture measured at −7.23 dB, so the agreement gate declined, correctly and
        // uselessly.
        //
        // The geometry is already known better than any single capture knows it: within one
        // capture the two arrivals are separated by (tgtOff − refOff) + the consensus delta, which
        // is the median of every sample this round took. So anchor on whichever arrival that
        // capture saw MORE SALIENTLY — the loud speaker, whose peak is never in doubt — and derive
        // the other from the consensus spacing. This is exactly the pinned-lag method that made
        // the offline grader trustworthy at this imbalance (`scratchpad/grade.py`, which tracked a
        // commanded 12.4 dB step to 0.08 dB while a blind top-2 search could not find the quiet
        // speaker at all).
        //
        // `levelAt` still takes its max over ±3 ms, so a small per-capture wobble is absorbed
        // without letting the read wander onto a neighbouring arrival.
        if (harvest) {
            harvestPinned(reference, target, levelCands, ((tgtOff - refOff) + deltaMs).toDouble())
        }
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
            // NO standing misalignment: the delta this path throws away is the only estimate the
            // round had, so what the two arrivals are really doing is unknown. A follow-up levels
            // round must not assume it.
            return PairResult(
                "${target.name}: implausible Δ${deltaMs}ms — skipped (suspected mis-attribution)",
                target.latencyMs,
                standingMisalignmentMs = null,
            )
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
                // Deferred means "this measurement is not trusted", so it is not geometry either.
                return PairResult(
                    "${target.name}: Δ${deltaMs}ms disagrees with recent history (~${prior}ms) — " +
                        "deferred, re-run to confirm",
                    target.latencyMs,
                    standingMisalignmentMs = null,
                )
            }
        }
        if (abs(deltaMs) <= DEADBAND_MS) {
            // Already aligned within tolerance (same deadband as the batch). Skip the
            // verify: with the two speakers coincident their re-probed peaks overlap and
            // the movedBy step can't tell reference from target — a false "could not
            // identify". Leave the latency untouched and report aligned.
            commitLatency(target.id, target.latencyMs)
            // The delta stands as the measured misalignment: it was believed enough to decide no
            // correction was warranted, and the target is left exactly where it was measured.
            return PairResult(
                "${target.name}: already aligned (Δ${deltaMs}ms within deadband)",
                target.latencyMs,
                standingMisalignmentMs = deltaMs,
            )
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
        val vBase = measurer.measure(PAIR_PEAKS, sources = 2)
            ?: return failPair(target, "verify baseline failed")
        val vBaseFull = measurer.fullPeaks() ?: vBase
        control.sendSetLatency(reference.id, reference.latencyMs - refOff)
        control.sendSetLatency(target.id, newLatency - tgtOff)
        delay(SETTLE_MS)
        val vProbed = measurer.measure(PAIR_PEAKS, sources = 2)
        val vTiming = measurer.timingReader()
        val vLevel = measurer.levelReader()
        commitLatency(reference.id, reference.latencyMs) // remove reference probe
        if (vProbed == null) return failPair(target, "verify probe failed")
        // Assign reference and target to DISTINCT re-probed peaks (drift-corrected); the
        // residual is then drift-immune. Avoids the false "could not identify" that picking
        // the strongest peak per offset causes on an aligned pair in a reflective room.
        //
        // TARGETED FALLBACK when the blind assignment fails: the same masking that loses a speaker
        // in the measurement loses it here — the verify probe separates the pair again, so one
        // side's correlation share drops below the salience floor. Rig-measured in BOTH directions
        // (21:32 lost the reference, 21:51 lost the target at z 8.9), so the fallback is
        // symmetric: whichever side is still salient is matched blind and pins the drift, and the
        // other is read from the whitened correlation at its expected window. A window read below
        // RESCUE_MIN_Z fails the verify exactly as before.
        val (vRef, vTgt) = PeakAttribution.assignVerifyPair(vProbed, vBase, refOff, tgtOff, MATCH_TOL_MS)
            ?: PeakAttribution.assignVerifyPairWithWindows(
                vProbed, vBase, vBaseFull, refOff, tgtOff, MATCH_TOL_MS, RESCUE_MIN_Z, vTiming,
            )?.also { (r, t) ->
                Log.i(
                    TAG,
                    "verify: pair identified via targeted window fallback " +
                        "ref=%.1fms(z=%.1f) tgt=%.1fms(z=%.1f)".format(r.lagMs, r.z, t.lagMs, t.z),
                )
            }
            ?: return failPair(target, "verify could not identify reference/target")
        val residualMs = ((vTgt.lagMs - tgtOff) - (vRef.lagMs - refOff)).roundToInt()
        if (abs(residualMs) > PAIR_RESIDUAL_TOL_MS) {
            return failPair(target, "verify residual ${residualMs}ms > ${PAIR_RESIDUAL_TOL_MS}ms")
        }
        // HARVEST FROM THE VERIFY PROBE TOO. It is an unboosted probed capture whose pair was just
        // identified and residual-checked — the most trustworthy lags of the whole round — and it
        // costs no extra rig time. One more sample against MIN_LEVEL_SAMPLES, which the 3-capture
        // yield (~2 of 3, rig-measured) chronically undershoots.
        if (harvest) {
            val rl = vLevel?.invoke(vRef.lagMs)
            val tl = vLevel?.invoke(vTgt.lagMs)
            if (rl != null && tl != null && rl > 0.0 && tl > 0.0) {
                Log.i(
                    TAG,
                    "level capture ${levelCaptures.size}: " +
                        "${reference.name}@%.1fms=%.3e ".format(vRef.lagMs, rl) +
                        "${target.name}@%.1fms=%.3e ".format(vTgt.lagMs, tl) +
                        "delta=%+.1fdB (verify)".format(20.0 * log10(tl / rl)),
                )
                levelCaptures += mapOf(reference.id to rl, target.id to tl)
                // The verify pair was just residual-checked against the probe offsets, so its
                // geometry is confirmed rather than assumed: never swap-suspect.
                levelCaptureSwapSuspect += false
            }
        }
        commitLatency(target.id, newLatency) // remove verify probe → aligned
        history?.record(target.id, deltaMs, newLatency)
        return PairResult(
            "${target.name}: ${if (deltaMs == 0) "already aligned" else "trim ${deltaMs}ms"} " +
                "(latency $newLatency, residual ${residualMs}ms, verified)",
            newLatency,
            // The re-measured residual, not the delta: the trim has been applied, so THIS is what
            // is left between the two arrivals at the latency the target is committed to.
            standingMisalignmentMs = residualMs,
        )
    }


    // ---- pair-round building blocks --------------------------------------------------
    //
    // Extracted so the sync round ([calibratePair]) and the levels-only round
    // ([harvestLevelsUnboosted]) share ONE implementation of the delicate parts: how a probed
    // capture is attributed, how a masked reference is rescued, and how a level pair is read at
    // pinned lags. A second copy of any of these would drift from the first, and every one of them
    // encodes a rig failure that took days to find.

    /**
     * One capture's raw material for a level pair: the readers bound to that capture plus the two
     * arrivals as this capture SAW them. Levels are not read here — see [harvestPinned], whose
     * pinned re-read is what makes a quiet speaker measurable at all.
     */
    private data class LevelCand(
        val capture: Int,
        val read: (Double) -> Double,
        /** Locates the actual correlation peak near a lag, so BOTH arrivals can be read at a
         *  peak of their own signal rather than one at a peak and one at a derived position. */
        val peak: ((Double, Double) -> Dsp.Peak?)?,
        val refLagMs: Double,
        val tgtLagMs: Double,
        val refZ: Double,
        val tgtZ: Double,
    )

    /**
     * PINNED LEVEL HARVEST — read each capture at ONE searched lag and one DERIVED lag, and append
     * the pairs to [levelCaptures] / [levelCaptureSwapSuspect] (always both, never one).
     *
     * Reading both arrivals where each capture happened to match them is what broke the balance on
     * a genuinely lopsided room (rig 2026-08-11 01:54, ONEPLUS 12.4 dB down): the quiet speaker's
     * peak cannot be located reliably capture to capture — it was matched at 1765.9 ms in one and
     * 1691.8 ms in the next, 74 ms apart — and a level read at the wrong lag is a wrong level. The
     * two captures reported −8.7 dB and −1.2 dB for a room an independent capture measured at
     * −7.23 dB, so the agreement gate declined, correctly and uselessly.
     *
     * The geometry is known better than any single capture knows it, which is what [spacingMs]
     * carries: within one capture the two arrivals are separated by (tgtOff − refOff) plus the
     * pair's misalignment. So anchor on whichever arrival that capture saw MORE SALIENTLY — the
     * loud speaker, whose peak is never in doubt — and derive the other from that spacing. This is
     * exactly the pinned-lag method that made the offline grader trustworthy at this imbalance
     * (`scratchpad/grade.py`, which tracked a commanded 12.4 dB step to 0.08 dB while a blind top-2
     * search could not find the quiet speaker at all).
     *
     * `levelAt` still takes its max over ±3 ms, so a small per-capture wobble is absorbed without
     * letting the read wander onto a neighbouring arrival.
     */
    private fun harvestPinned(
        reference: CalClient,
        target: CalClient,
        cands: List<LevelCand>,
        spacingMs: Double,
    ) {
        for (c in cands.sortedBy { it.capture }) {
            val anchorOnRef = c.refZ >= c.tgtZ
            val refSeed = if (anchorOnRef) c.refLagMs else c.tgtLagMs - spacingMs
            val tgtSeed = if (anchorOnRef) c.refLagMs + spacingMs else c.tgtLagMs
            // SYMMETRIC SELECTION. Both arrivals are re-located as the peak of their own
            // correlation before their level is read, even though one of the two seeds is
            // already a matched peak.
            //
            // Otherwise the anchor is read at a lag that was CHOSEN by maximising that
            // speaker's own signal while the other is read at a merely derived lag, and a
            // selected maximum is biased upward. The ratio then inflates whichever speaker
            // anchored, and the balance duly attenuates it. Rig-measured 2026-08-11 iteration
            // 1: ONEPLUS anchored in all six captures, its readings varied 2.1x (5.4-11.5e-05)
            // while the derived PFFM10 stayed inside 3.5-4.9e-05, the ratio over-read by
            // 2.6 dB against an independent solo measurement, and two runs walked the speaker
            // 3.7 dB past even. Same family as the settled "a peak's z is not a level" trap:
            // select a maximum, then read its value, and the selection is in the answer.
            val refLag = c.peak?.invoke(refSeed, LEVEL_PEAK_FIND_MS)?.lagMs ?: refSeed
            val tgtLag = c.peak?.invoke(tgtSeed, LEVEL_PEAK_FIND_MS)?.lagMs ?: tgtSeed
            // Geometry check, on the SEEDS (what this capture actually matched) rather than
            // on the derived pair, which is consistent by construction and so proves nothing.
            val seedSpacing = c.tgtLagMs - c.refLagMs
            val swapSuspect = abs(seedSpacing - spacingMs) > SWAP_GEOMETRY_TOL_MS
            val rl = c.read(refLag)
            val tl = c.read(tgtLag)
            Log.i(
                TAG,
                "level capture ${levelCaptures.size}: " +
                    "${reference.name}@%.1fms=%.3e ".format(refLag, rl) +
                    "${target.name}@%.1fms=%.3e ".format(tgtLag, tl) +
                    "delta=%+.1fdB (anchored on %s, spacing %.0fms, seed %.0fms%s)".format(
                        if (rl > 0.0 && tl > 0.0) 20.0 * log10(tl / rl) else 0.0,
                        if (anchorOnRef) reference.name else target.name,
                        spacingMs,
                        seedSpacing,
                        if (swapSuspect) " SWAP-SUSPECT" else "",
                    ),
            )
            if (rl > 0.0 && tl > 0.0) {
                levelCaptures += mapOf(reference.id to rl, target.id to tl)
                levelCaptureSwapSuspect += swapSuspect
            }
        }
    }

    /**
     * LEVELS-ONLY ROUND: re-measure [target] against [reference] with NOTHING boosted anywhere, so
     * a run whose sync needed a detectability boost can still balance. Closes the boost gap.
     *
     * Only the LEVELS are missing by the time this runs. The boosted round bought the timing, and
     * timing is the expensive half — [standingMisalignmentMs] is what the pair has left at the
     * latencies it is committed to, so the separation the two arrivals will show under the level
     * probe set is known before the first capture is taken, and no delta is re-derived here. That
     * is also why one baseline capture is enough where a sync round takes three: a sync round
     * repeats the baseline because the baseline's own noise IS the delta's noise (five identical
     * captures spanned 34 ms of peak spacing) and it is about to act on that delta, whereas here
     * the baseline is only the origin the probed peaks are matched against.
     *
     * THE ONE THING IT MUST NOT DO IS BOOST. The speaker is hard to detect — that is why the sync
     * round was boosted — so boosting it again to make the levels harvestable would measure a gain
     * the balance is not allowed to reason about, which is the exact disqualification this round
     * exists to work around. The honest ceiling is therefore: the balance works when the speakers
     * are detectable at their real gains, and says so plainly when they are not. Expect this round
     * to decline sometimes; a stated decline is a large improvement on a silent nothing.
     *
     * Everything it writes is transient and restored in the finally: the target goes back to
     * [targetLatencyMs] (the trim the sync round just verified — NOT its pre-run latency, which
     * would undo that trim), the reference to its own, and any muted others are unmuted.
     */
    private suspend fun harvestLevelsUnboosted(
        measurer: Measurer,
        reference: CalClient,
        target: CalClient,
        others: List<CalClient>,
        targetLatencyMs: Int,
        standingMisalignmentMs: Int,
    ): Boolean {
        val probeSet = levelProbeSet(2)
        if (probeSet == null) {
            Log.i(
                TAG,
                "balance: no levels-only round for ${target.name} — no probe set separates " +
                    "2 speakers within the ${MAX_VERIFIED_PROBE_MS}ms verified write envelope",
            )
            return false
        }
        levelRecoveryAttempted = true
        val refOff = probeSet[0]
        val tgtOff = probeSet[1]
        val spacingMs = (tgtOff - refOff) + standingMisalignmentMs
        val before = levelCaptures.size
        try {
            // [others] is empty in practice — this round is only reached from the N=2 pair loop —
            // so the muting is structural symmetry with [mutedPairRound] rather than something that
            // fires today. Kept because the loop it hangs off is written generically, and a levels
            // round that let a third speaker play would harvest a two-client pair out of a
            // three-speaker capture.
            if (others.isNotEmpty()) {
                progress("muting ${others.size} other client(s) to read levels")
                others.forEach { control.sendSetVolume(it.id, muted = true, percent = it.volumePercent) }
            }
            // SETTLE UNCONDITIONALLY, and specifically for the BOOST RELEASE that just happened.
            // This round's whole premise is that nothing is boosted while it measures, and the
            // slowest restore in the system is the one most likely to be outstanding: the reference
            // on this rig sits at 100% SW, so it gets the OS-volume flavour, whose release is a
            // metadata publish that a client's AudioManager acts on at its own pace. The single
            // baseline capture below would mostly cover it by accident; relying on that would make
            // this round's correctness depend on how long a capture happens to take.
            delay(SETTLE_MS)
            progress("${target.name}: sync used a boost — re-reading levels at the real volumes…")
            // ONE baseline, and it is used the way the VERIFY step uses its baseline, not the way
            // a sync round uses its own. That difference is forced by the physics of an aligned
            // pair, and getting it wrong is silent: after sync has done its job both speakers
            // arrive at nearly the same lag, so they collapse into ONE baseline cluster, and
            // [PeakAttribution.attribute] spends each baseline cluster on at most one entity — the
            // reference claims it and the target is reported missing, every capture, forever. The
            // verify step already faced exactly this ("an aligned pair coincides in the verify
            // baseline (one merged cluster) and both probed peaks trace to it") and answers it with
            // [PeakAttribution.assignVerifyPair], which assigns the two probed peaks to distinct
            // clusters and lets them share a baseline origin. A levels round runs on an aligned
            // pair by construction, so it must use that matcher.
            val vBase = measurer.measure(PAIR_PEAKS, sources = 2)
            if (vBase == null) {
                Log.w(TAG, "balance: levels-only round for ${target.name} — baseline measurement failed")
                return false
            }
            val vBaseFull = measurer.fullPeaks() ?: vBase
            // Log the values actually WRITTEN, not just the offsets: a probe writes latency −
            // offset, so a client already sitting at a negative latency can be pushed past
            // MAX_VERIFIED_PROBE_MS in absolute terms even though the offset itself is inside it
            // (the ONEPLUS at −99 ms took −479 ms for a 380 ms probe). If a rig run fails here,
            // this line is what says whether the write was the reason.
            val refProbe = reference.latencyMs - refOff
            val tgtProbe = targetLatencyMs - tgtOff
            progress("${target.name}: probing for levels…")
            Log.i(
                TAG,
                "balance: levels-only probe ${reference.name}→${refProbe}ms " +
                    "${target.name}→${tgtProbe}ms (offsets $refOff/$tgtOff, " +
                    "expected spacing ${spacingMs}ms from a standing ${standingMisalignmentMs}ms)",
            )
            control.sendSetLatency(reference.id, refProbe)
            control.sendSetLatency(target.id, tgtProbe)
            delay(SETTLE_MS)
            // Six probed captures, same count a harvest round takes and for the same reason: the
            // level estimator is noisy per capture (8-13 dB between consecutive captures of one
            // speaker at a fixed gain, rig 2026-08-11), so MIN_LEVEL_SAMPLES needs the yield.
            val cands = mutableListOf<LevelCand>()
            // STOP ONCE THERE IS ENOUGH, rather than always paying the worst case.
            //
            // The 6 is a YIELD calculation, not a requirement: it assumes ~65 % of probes attribute
            // (rig 2026-08-11: 3, 1 and 4 usable from 4 probes) and buys 6 to land the 5-6 the level
            // estimator needs. But the two healthy runs of 2026-08-14 yielded 6 of 6, so a good room
            // pays two extra captures — 24 s — for samples it already has.
            //
            // Safe HERE and not in the sync round, and the difference is worth stating: a sync
            // round's probes feed BOTH the consensus delta and the levels, so cutting them short
            // weakens the correction that gets written. This round re-derives no delta at all — its
            // spacing was fixed before the first capture — so a capture only ever banks one more
            // level sample. Stopping at [LEVEL_HARVEST_ENOUGH] therefore trades precision the
            // estimator has already declared sufficient, not correctness.
            //
            // Deliberately ABOVE [MIN_LEVEL_SAMPLES]: the agreement gate can still discard a capture
            // as an outlier, and stopping at exactly 3 would leave a round one bad capture short of
            // its own floor with no way back.
            for (attempt in 0 until PROBE_REPEATS * 2) {
                if (cands.size >= LEVEL_HARVEST_ENOUGH) {
                    Log.i(
                        TAG,
                        "balance: levels-only round stopping at ${cands.size} usable capture(s) " +
                            "after ${attempt} probe(s) — enough for the estimator, so the " +
                            "remaining ${PROBE_REPEATS * 2 - attempt} are not worth the time",
                    )
                    break
                }
                val probed = measurer.measure(PAIR_PEAKS, sources = 2)
                val read = measurer.levelReader()
                val peak = measurer.timingReader()
                if (probed != null && read != null) {
                    // The targeted window fallback is the LIKELY path here, not an edge case: the
                    // reference masked below the shared-capture correlation floor is exactly what
                    // failed on the rig on 2026-08-14, and it is why this round exists at all.
                    val pair = PeakAttribution.assignVerifyPair(probed, vBase, refOff, tgtOff, MATCH_TOL_MS)
                        ?: PeakAttribution.assignVerifyPairWithWindows(
                            probed, vBase, vBaseFull, refOff, tgtOff, MATCH_TOL_MS, RESCUE_MIN_Z, peak,
                        )
                    if (pair != null) {
                        val (r, t) = pair
                        cands += LevelCand(attempt, read, peak, r.lagMs, t.lagMs, r.z, t.z)
                    } else {
                        Log.i(
                            TAG,
                            "balance: levels-only capture $attempt could not identify the pair — " +
                                "at their real volumes one speaker is under the floor",
                        )
                    }
                }
                if (attempt < PROBE_REPEATS * 2 - 1) {
                    progress("levels capture ${attempt + 2}/${PROBE_REPEATS * 2}…")
                }
            }
            harvestPinned(reference, target, cands, spacingMs.toDouble())
        } finally {
            commitLatency(reference.id, reference.latencyMs)
            commitLatency(target.id, targetLatencyMs)
            others.forEach { control.sendSetVolume(it.id, muted = it.muted, percent = it.volumePercent) }
        }
        val gained = levelCaptures.size - before
        if (gained == 0) {
            Log.w(
                TAG,
                "balance: levels-only round for ${target.name} harvested nothing — at their real " +
                    "volumes the speakers did not clear the measurement floor. This is the expected " +
                    "decline, not a fault: the boost that found them for sync is not available here.",
            )
        } else {
            Log.i(TAG, "balance: levels-only round for ${target.name} harvested $gained capture(s)")
        }
        return gained > 0
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
        // As in the pair path: levels need far wider separation than attribution does, so the batch
        // takes the level set when one exists for this many speakers and the sync set otherwise.
        // At three speakers the level set needs a 940 ms excursion, which is past the verified write
        // envelope, so [levelProbeSet] declines and the batch calibrates sync without harvesting —
        // the balance then has no level captures and simply does not act, which is the correct
        // outcome rather than acting on tail-contaminated readings.
        val levelSet = levelProbeSet(sim.size + 1)
        val probeSet = levelSet ?: PROBE_SET_MS
        val harvestLevels = levelSet != null
        if (!harvestLevels) {
            Log.i(
                TAG,
                "balance: not harvesting levels — ${sim.size + 1} speakers cannot be separated by " +
                    "the ~380ms the level readout needs within the ${MAX_VERIFIED_PROBE_MS}ms " +
                    "verified write envelope; sync is unaffected",
            )
        }
        val refOffset = probeSet[0]
        fun targetOffset(i: Int) = probeSet[i + 1]

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
                val baseline = measurer.measure(peakCount, sources = sim.size + 1)
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
                val probedTriple = measurer.measureHalves(peakCount, sources = sim.size + 1)
                val probedLevel = measurer.levelReader()
                if (probedTriple == null) {
                    commitLatency(reference.id, reference.latencyMs)
                    sim.forEach { commitLatency(it.id, it.latencyMs) }
                    finish(false, "probe measurement failed")
                    return false
                }
                val (probed, probedH1, probedH2) = probedTriple

                val probesMs = probeSet.take(sim.size + 1) // [0]=reference, [1..]=targets
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
                // Only when the probe set actually separated the arrivals far enough for the level
                // readout to mean anything; otherwise every value here would be the loud speaker's
                // tail read at the quiet speaker's lag.
                if (harvestLevels) {
                    for (i in -1 until sim.size) {
                        val client = if (i < 0) reference else sim[i]
                        val m = attr.matches[i + 1] ?: continue
                        if (client.id in boosted) continue
                        probedLevel?.invoke(m.probedLagMs)?.takeIf { it > 0.0 }
                            ?.let { probedLevels[client.id] = it }
                    }
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
                    // The batch path reads every client at its OWN attributed lag against a shared
                    // drift, so a per-capture pair spacing (the pair path's swap signature) does
                    // not exist here. Not suspect by default; the batch has its own consensus
                    // guards upstream.
                    levelCaptureSwapSuspect += false
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
                val verify = measurer.measure(peakCount, sources = sim.size + 1)
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
                // No boost-gap recovery round here, deliberately. Every fallback round is boosted
                // (the batch only degrades to one after attribution already failed), so a recovery
                // would fire on every 3+ client run and spend ~100 s per target producing TWO-client
                // captures of a room with three speakers in it — which [reduceLevels] would then
                // centre on a two-client mean and balance those two against each other. The batch
                // has no usable balance at 3+ clients anyway ([levelProbeSet] declines the 940 ms
                // excursion), so there is nothing here to recover.
                val outcome = mutedPairRound(measurer, reference, target, others, boostPercent = BOOST_RAMP_PERCENT.first())
                if (outcome != null) succeeded += target.id else errored += target.id
                outcomes[target.id] = outcome?.summary ?: "${target.name}: failed (see log)"
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
                outcomes[target.id] = outcome?.summary
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

    /** True for the silent second snapclient a client-side run uses to obtain reference PCM.
     *  Identified by an id prefix the host controls, the same mechanism [isWebClient] uses, because
     *  the server offers nothing else to distinguish it: it is a real, connected, correctly
     *  behaving client that happens to have no speaker. */
    private fun isReferenceTap(client: CalClient) = client.id.startsWith(REFERENCE_TAP_PREFIX)

    // ---- shared measurement --------------------------------------------------------

    /** Like [measure] but also returns the peaks of each 6 s half of the same capture, for
     *  the split-half consistency gate. Full peaks are salience-filtered as usual; the half
     *  peaks are returned raw (the gate locates a known lag in them, so low z is fine). */
    private suspend fun micMeasureHalves(
        ring: ReferencePcmRing,
        peakCount: Int,
        sources: Int = 0,
    ): Triple<List<Dsp.Peak>, List<Dsp.Peak>, List<Dsp.Peak>>? {
        repeat(2) { attempt ->
            val cap = mic!!.record(CAPTURE_MS)
            if (cap != null) {
                val snap = ring.snapshot()
                val full = DelayMeasurement.estimateSpeakerDelays(snap, cap, peakCount, sources)
                Log.i(TAG, "peaks: " + full.joinToString { "%.1fms(z=%.1f)".format(it.lagMs, it.z) })
                val salient = full.filter { it.z >= MIN_PEAK_Z }
                if (salient.isNotEmpty()) {
                    val h = cap.pcm.size / 2
                    val c1 = MicCapture.Capture(cap.pcm.copyOfRange(0, h), cap.firstSampleNanos, cap.sampleRate)
                    val n2 = cap.firstSampleNanos + h.toLong() * 1_000_000_000L / cap.sampleRate
                    val c2 = MicCapture.Capture(cap.pcm.copyOfRange(h, cap.pcm.size), n2, cap.sampleRate)
                    val p1 = DelayMeasurement.estimateSpeakerDelays(snap, c1, peakCount, sources)
                    val p2 = DelayMeasurement.estimateSpeakerDelays(snap, c2, peakCount, sources)
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

    private suspend fun micMeasure(
        ring: ReferencePcmRing,
        peakCount: Int = 4,
        sources: Int = 0,
    ): List<Dsp.Peak>? {
        repeat(2) { attempt ->
            val capture = mic!!.record(CAPTURE_MS)
            if (capture != null) {
                val snapshot = ring.snapshot()
                val m = DelayMeasurement.measure(snapshot, capture, peakCount, sources)
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
    private suspend fun failPair(target: CalClient, reason: String): PairResult? {
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
        // NAME THE SPEAKERS THE MIC COULD NOT HEAR. Some clients going unmeasured is an accepted
        // outcome of an empirical feature — a speaker can be too far, too quiet, or masked — but it
        // must never be silent. The user is the only one who can act on it (move the speaker, raise
        // its device volume, move the phone), and they cannot act on a number they never see.
        // "Not detected" means the mic never heard it, NOT "a gate declined it". A client that
        // produced level captures and then failed the sign or agreement check WAS heard, and
        // telling the user to move it or turn it up would send them after the wrong problem —
        // the same defect as the old "too quiet, raise its volume" message this file replaced.
        val everMeasured = levelCaptures.flatMap { it.keys }.toSet()
        val undetected = clients.filterNot { it.muted }.filter { it.id !in everMeasured }
        if (undetected.isNotEmpty() && levelsSkippedForBoost && levelCaptures.isEmpty()) {
            // Heard fine, but every round that got far enough had been BOOSTED for detectability,
            // and a boosted round's levels are disqualified on purpose. Saying "not detected" here
            // sends the user to move speakers or raise volume when nothing is wrong with either.
            //
            // Two different endings, and the difference is what the user should do about it. If the
            // levels-only recovery round RAN and still came back empty, the speakers genuinely are
            // not measurable at their real volumes — the run has done everything it can and the
            // remaining levers are physical (move the speaker, raise its device volume). If it
            // never ran, the round ended on a path that left the pair's misalignment unmeasured,
            // so there was no geometry to harvest at and re-running is the useful advice.
            Log.i(
                TAG,
                if (levelRecoveryAttempted) {
                    "balance: no levels this run — sync needed a detectability boost, and the " +
                        "levels-only round that followed could not hear the speakers at their " +
                        "real volumes. Raise the device volume or move the quiet speaker closer, " +
                        "then re-run."
                } else {
                    "balance: no levels this run — every round that completed was boosted for " +
                        "detectability, and a boosted speaker's level does not describe its SW " +
                        "gain. Re-run once the speakers are detectable unboosted."
                },
            )
        } else if (undetected.isNotEmpty()) {
            Log.w(
                TAG,
                "balance: NOT DETECTED by the mic: ${undetected.joinToString { it.name }} — " +
                    "each is too far, too quiet at its device volume, or masked by a louder " +
                    "speaker. Their volumes are left untouched.",
            )
            // NOT via progress(): the balance runs from the run's `finally`, after the terminal
            // state has been published, so emitting progress here would overwrite Done/Failed with
            // a Running state. Surfacing this in the user-visible summary needs the summary itself
            // to carry it — a follow-up, not a log call.
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
    internal fun reduceLevels(
        captures: List<Map<String, Double>> = levelCaptures,
        swapSuspect: List<Boolean> = levelCaptureSwapSuspect,
    ): Map<String, Double> {
        // Centred log levels, in dB, one list per client. A capture with fewer than two clients
        // carries no between-client information at all and is not evidence of anything.
        val centred = LinkedHashMap<String, MutableList<Double>>()
        for (capture in captures) {
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
                // A SIGN DISAGREEMENT IS ONLY A SWAP IF THE GEOMETRY SAYS SO.
                //
                // Levels alone cannot distinguish the two cases this gate has to separate. A real
                // swap reports the reciprocal ratio with full confidence, so no amount of
                // repetition or median-taking catches it — that is why the gate exists. But
                // ordinary noise near balance produces the same level pattern: with a room a few dB
                // out and a per-capture spread around 5 dB (rig-measured), one capture of five
                // landing the other side of zero is the expected outcome, not a defect.
                //
                // The lags separate them. A swap exchanges which arrival each speaker was read at,
                // so that capture's measured pair spacing departs from the round's consensus by
                // about twice the probe difference; noise leaves the spacing intact and moves only
                // the value. Rig 2026-08-13: the one dissenting capture of five had spacing 387 ms
                // against the round's 377 — geometry perfect, and the whole run was declined
                // anyway, on a room that genuinely needed correcting.
                if (swapSuspect.any { it }) {
                    Log.w(
                        TAG,
                        "balance: $id DECLINED — captures disagree on SIGN ($fmt dB) AND at least " +
                            "one capture's geometry departs from the round's spacing: a swapped " +
                            "attribution is the likely cause, not noise",
                    )
                    continue
                }
                Log.i(
                    TAG,
                    "balance: $id captures disagree on SIGN ($fmt dB) but every capture's geometry " +
                        "matches the round's spacing — treating as noise near balance, and letting " +
                        "the agreement gate below judge it",
                )
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
        /**
         * Peak budget for the two-speaker pair path, split across the two sources by
         * [Dsp.findPeaksPerSource] rather than spent on whichever speaker is loudest.
         *
         * Was a bare 4 for the whole capture, which is the same budget the batch path gives to a
         * SINGLE source (`4 + 3*(n+1)` over n+1 speakers). With a source spreading its reflections
         * over 50-80 ms, four peaks is roughly one speaker's cluster, so on this rig the pair path's
         * list was six views of the loud speaker and the quiet one was absent — the iteration-zero
         * failure of a balance run, where one client deliberately starts low.
         */
        internal const val PAIR_PEAKS = 8

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
         *
         * THIS SET IS FOR SYNC AND IS DELIBERATELY NOT WIDE ENOUGH FOR LEVELS. See
         * [LEVEL_PROBE_SET_MS] — the two halves need different separations, and trying to satisfy
         * both with one set is what made the three-client case look impossible.
         */
        val PROBE_SET_MS = listOf(90, 180, 360)

        /**
         * Probe offsets when a capture also has to yield LEVELS, which needs far more separation
         * than attribution does.
         *
         * **Two different constraints on two different quantities, and conflating them is what
         * blocked this.** Attribution needs every value in (offsets ∪ pairwise differences) distinct
         * and separated by more than the room's 50-80 ms reflection spread — [PROBE_SET_MS] does
         * that with 90 ms gaps and is rig-validated. Levels need each PAIR of compared arrivals
         * separated by ~380 ms, because [Dsp.levelAt] reads a max over ±3 ms with no notion of a
         * baseline, so a quieter arrival any closer parks on the louder speaker's decaying reverb
         * tail and stops responding to its own gain (FINDINGS §15-16, §20). The tail decays about
         * 5.4 dB/100 ms, so 380 ms puts the quiet arrival 13-15 dB clear.
         *
         * The level constraint binds only PAIRWISE DIFFERENCES; the attribution constraint binds the
         * whole union. They do not scale alike, which is why "a Sidon set with 380 ms gaps" reads as
         * needing a ~1000 ms member and looks impossible, while the real minimum for two clients is
         * 470 ms and for three is 940 ms.
         *
         * Only as many offsets as the run needs are ever written ([levelProbeSet]), so the common
         * two-client case pays 470 ms and nothing more. 470 ms is inside the envelope already
         * verified on hardware: a direct RPC test wrote −380 ms on Guer and −479 ms on the ONEPLUS
         * and read both back unchanged, no clamping (FINDINGS §20). The three-client 940 ms member
         * has NOT been tried and may rebuffer — [levelProbeSet] refuses it rather than guessing.
         */
        val LEVEL_PROBE_SET_MS = listOf(90, 470, 940)

        /**
         * The largest probe excursion verified on hardware to be written and read back unchanged.
         *
         * A probe writes `latency − offset`, so a client already sitting at a negative latency pays
         * more than the offset itself: the ONEPLUS at −99 ms took −479 ms for a 380 ms probe. Beyond
         * this nothing is known — snapclient might clamp (which reads as a measurement failure, not
         * a write one) or the stream might rebuffer. Levels are declined rather than harvested from
         * a capture whose probe was never proven to land.
         */
        const val MAX_VERIFIED_PROBE_MS = 480

        /**
         * Level-harvesting probe offsets for [n] audible speakers, or null if this many cannot be
         * separated inside [MAX_VERIFIED_PROBE_MS].
         *
         * Returning null is a real answer: the run still calibrates SYNC with [PROBE_SET_MS] and
         * simply does not harvest levels, which is strictly better than harvesting levels that the
         * tail makes meaningless. Three speakers need a 940 ms excursion — untried, plausibly enough
         * to rebuffer — so this declines until that is measured rather than shipping a guess.
         */
        fun levelProbeSet(n: Int): List<Int>? {
            if (n < 2 || n > LEVEL_PROBE_SET_MS.size) return null
            val set = LEVEL_PROBE_SET_MS.take(n)
            return if (set.max() <= MAX_VERIFIED_PROBE_MS) set else null
        }

        private const val WEB_CLIENT_PREFIX = "qcweb-"

        /** Id prefix the host must give the reference-tap snapclient of a client-side run, so the
         *  calibrator can tell a silent instrument from a speaker. Rig-verified 2026-08-19: a
         *  second snapclient started with `--player file:` coexists with the audible one, delivers
         *  real-time PCM (193,920 B/s against the 192,000 B/s that 48 kHz/16-bit/stereo demands),
         *  and shows up as an unnamed 100 % client on the server. Anything the host starts with
         *  this prefix is excluded from calibration entirely. */
        const val REFERENCE_TAP_PREFIX = "qctap-"


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

        /** Acceptance floor for a TARGETED window read ([Dsp.peakNear]) rescuing a reference the
         *  blind search lost. Deliberately below [MIN_PEAK_Z]: a PHAT peak carries only its
         *  source's share of the capture energy, so beside a comparably loud sibling a real
         *  speaker measures z 6.5-9.0 (three two-speaker dumps, 2026-08-10) while ±15 ms noise
         *  windows on the same dumps top out at p95 5.1-6.0 and an absent speaker's expected
         *  window read 5.3. 6.0 passes every measured real arrival and rejects the measured
         *  absent one; the cross-capture spacing quorum below carries the rest of the
         *  discrimination, so this floor need not separate perfectly on its own. */
        private const val RESCUE_MIN_Z = 6.0

        /** Two rescue candidates agree when their within-capture spacings (target − reference)
         *  match this closely. Spacing is the wobble-free observable: the whole timeline drifts
         *  ±10-20 ms between captures of one run (measured on the 2026-08-10 baselines), but both
         *  speakers drift together, and true probe deltas repeated within ±4 ms in the failing
         *  run's target matches. A ±15 ms window noise peak lands at a fresh lag every capture,
         *  so demanding 5 ms spacing agreement across captures is what noise cannot fake. */
        private const val RESCUE_AGREE_MS = 5.0

        /** Distinct probe CAPTURES the modal spacing group must span before a rescue is
         *  believed. Candidates from one capture all read the same window of the same audio, so
         *  they corroborate nothing; two independent captures agreeing on spacing is the
         *  smallest real quorum (of PROBE_REPEATS = 3). */
        private const val RESCUE_MIN_CAPTURES = 2

        /** Half-width of the level read's local search. Larger than the lag uncertainty a quiet
         *  speaker's arrival carries (10-70 ms rig-measured), far smaller than the 380 ms the
         *  level probe set puts between the two speakers, so it can never read the wrong one. */
        private const val LEVEL_READ_HALF_MS = 25.0

        /** How far either side of a seed lag both arrivals are re-located before their level is
         *  read. Wide enough to absorb the consensus spacing's own error (10-20 ms rig-measured),
         *  narrow enough that it cannot reach the other speaker, which the level probe set puts
         *  380 ms away. */
        private const val LEVEL_PEAK_FIND_MS = 25.0

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

        /** Usable captures at which the LEVELS-ONLY round stops early, saving ~24 s on a healthy
         *  room. One above [MIN_LEVEL_SAMPLES] on purpose: the agreement gate can still throw a
         *  capture out as an outlier, and stopping at exactly the floor would leave a round one bad
         *  capture short of it with the probes already restored and no way to take another.
         *
         *  Only the levels-only round may do this. A sync round's probes feed the consensus delta as
         *  well as the levels, so stopping it early would degrade the correction it writes; this
         *  round re-derives nothing and each capture only banks one more level sample. */
        private const val LEVEL_HARVEST_ENOUGH = 4

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

        /** How far one capture's own pair spacing may sit from the round's consensus spacing
         *  before that capture is treated as possibly SWAPPED. Generous against the 10-20 ms of
         *  per-capture drift and the ±25 ms peak re-location, and still an order of magnitude
         *  under a real swap, which displaces the pair by about twice the probe difference
         *  (2 × 380 ms here). */
        internal const val SWAP_GEOMETRY_TOL_MS = 100.0

        private const val LN10 = 2.302585092994046
    }
}
