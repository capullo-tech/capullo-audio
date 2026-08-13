package tech.capullo.audio.calibration

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Works out per-client volumes that make every speaker arrive at the MICROPHONE at the same level.
 *
 * The point is not "all speakers set to the same number" — it is that a listener sitting where the
 * mic sits hears them evenly, so the stereo image centres on that seat. A far speaker therefore ends
 * up with more gain than a near one, by exactly the amount the room took away.
 *
 * This is why the mic's position matters: run it from the server phone and you balance for wherever
 * that phone happens to be; run it from a client sitting at the listening position and you balance
 * for the listener. Same code, and the second is the one worth having.
 *
 * TWO RULES THAT SHAPE THE OUTPUT, both from how it gets used rather than from the acoustics:
 *
 *  - **Ceiling.** The loudest client is placed at [HEADROOM_PERCENT], which is 100: SW gain is
 *    full scale and the balance only comes DOWN from it.
 *
 *    This used to be 80, to leave the user room to turn everything up afterwards without editing
 *    clients one by one. That reasoning was overtaken by the 2026-08-11 gain-staging decision: the
 *    user's global control is the DEVICE volume (their own ceiling, which the app never touches),
 *    so the SW axis does not need to reserve anything for them. Holding it at 80 was actively
 *    harmful, because the client that ends up highest in SW is the FAR speaker — the one already
 *    fighting to stay above the mic's detection floor — and the reserve cost it 4.6 dB of the level
 *    it least had to spare. Rig-observed 2026-08-11: a successful balance left the far speaker at
 *    60 % and pinned the near one at exactly this cap.
 *  - **Attenuate rather than amplify.** Equalising by pulling the near speaker down, instead of
 *    pushing the far one up, gives the same measurement quality with less noise in the room, and it
 *    is the direction that always has somewhere to go: SW gain starts at 100%, so there is headroom
 *    downward and none upward.
 */
object VolumeBalance {

    /** Where the loudest client lands: full scale. The absolute level of the room is the user's
     *  DEVICE volume, which the app never touches, so the SW axis reserves nothing. */
    const val HEADROOM_PERCENT = 100

    /** Never drive a client below this: past it a speaker stops contributing to the room at all,
     *  and it would also fall under the calibration's own detection floor. */
    const val MIN_PERCENT = 15

    /** Fraction of the computed step actually applied per pass. Under-stepping converges from below
     *  instead of oscillating around the target, which matters because each measurement is noisy. */
    const val DAMPING = 0.7

    /** Balanced enough: stop when the loudest and quietest measured levels are within this ratio.
     *  ~1.25 is a hair over 1.9 dB, comfortably inside what anyone notices as an imbalance. */
    const val BALANCE_TOL_RATIO = 1.25

    /**
     * The most any single run may move one client from the gain it started at, in dB.
     *
     * This is a blast radius, not an acoustic judgement, and it exists because the estimator behind
     * the correction is good rather than proven. Bounding the mistake is what makes acting on a
     * merely-good measurement rational: with a cap plus an undo record, being wrong costs one action
     * instead of re-levelling every speaker in the room by hand.
     *
     * Six dB is deliberately smaller than the ~10 dB room asymmetry this rig actually shows, so a
     * correct measurement is CLAMPED rather than fully applied and convergence takes more than one
     * run. That is the intended trade: repeated runs walk toward the answer, and each one can only
     * ever be 6 dB wrong. It also composes with the damping, which already converges from below.
     */
    const val MAX_CORRECTION_DB = 6.0

    /**
     * Amplitude a snapclient SW volume percentage actually produces, 0..1.
     *
     * **The percentage is nowhere near linear in amplitude, and assuming it was is a real bug this
     * code carried.** snapclient's default software mixer is the base-10 exponential curve
     * (`snapclient.cpp` defaults `--mixer` to `software` with no parameter, so `Player::setVolume`
     * falls through to `setVolume_exp(volume, 10.)`), and the server passes `percent` straight to the
     * client as `percent/100` with no curve of its own:
     *
     * ```
     * amplitude = (10^(percent/100) - 1) / 9
     * ```
     *
     * The consequences are large enough to invalidate measurements rather than just skew them.
     * 50% is **-12.4 dB**, not -6 dB. 25% is **-21.3 dB**, not -12 dB. A sensitivity test that
     * commanded 50% expecting -6 dB and measured -12 dB would have read as an estimator wrong by
     * 6 dB — outside any sane pass band — and would have killed a working feature on a unit error.
     */
    fun percentToAmplitude(percent: Int): Double = percentToAmplitude(percent.toDouble())

    /** The same curve on a continuous axis. The intermediate arithmetic in [computeGains] lives on
     *  the amplitude side and must not be forced through integer percentages on the way. */
    private fun percentToAmplitude(percent: Double): Double =
        ((10.0.pow(percent.coerceIn(0.0, 100.0) / 100.0) - 1.0) / 9.0).coerceIn(0.0, 1.0)

    /** Inverse of [percentToAmplitude]: the percentage that yields [amplitude]. */
    fun amplitudeToPercent(amplitude: Double): Double =
        100.0 * log10((9.0 * amplitude.coerceIn(0.0, 1.0)) + 1.0)

    data class Client(
        val id: String,
        /** Its current SW gain, 0-100. */
        val gainPercent: Int,
        /** How loud it measured AT THE MIC. Any monotonic level proxy works as long as it is
         *  consistent across clients in the same capture; correlation-peak salience is what the
         *  calibrator has to hand. Null means it was not detected at all this pass. */
        val measuredLevel: Double?,
    )

    /**
     * True when the detected clients are already even enough at the mic to leave alone. Clients that
     * were not detected cannot be judged and do not block the verdict.
     */
    fun isBalanced(clients: List<Client>): Boolean {
        val levels = clients.mapNotNull { it.measuredLevel }.filter { it > 0 }
        if (levels.size < 2) return true
        return levels.max() / levels.min() <= BALANCE_TOL_RATIO
    }

    /**
     * One pass of gains that should even the clients out at the mic, or null if there is nothing to
     * act on (fewer than two clients measured).
     *
     * The gain a client should be on follows directly from what it measured, because the curve from
     * percentage to amplitude is KNOWN ([percentToAmplitude]): ask for the amplitude that lands this
     * client on the target and convert it straight back. The results are then scaled together so the
     * loudest sits at [HEADROOM_PERCENT], which preserves the ratios while leaving room above.
     * Only a fraction [DAMPING] of each move is taken, because the measurement behind it is noisy
     * and converging from below beats hunting around the answer.
     *
     * **This used to model the response as `measured ≈ percent^0.6`, and that was a real defect
     * rather than a rough edge.** The exponent was fitted against the percentage axis, which is
     * itself a base-10 exponential in amplitude, so it was standing in for a curve this class
     * already knows exactly. It also had the wrong SHAPE, not merely the wrong value: the true local
     * elasticity of amplitude with respect to percentage runs about 1.4 at 30% to 2.2 at 80%, so
     * every step came out roughly three times too small near the top of the range and the loop
     * leaned on [MAX_CORRECTION_DB] and repeated passes to make up the shortfall. Simulating the
     * loop against snapclient's real curve (`rig-loopsim.py`, `FINDINGS-loopsim-2026-08-07.md`) put
     * corrections that leave the room MORE unbalanced at 607 per 2000 runs with the exponent and 57
     * without it, with direction reversals falling from 25% of runs to 2%. Deleting the constant
     * removes a fitted number; it does not add one.
     *
     * Undetected clients keep the gain they have: raising a speaker nobody measured would be
     * guessing, and it is the calibration's detectability boost that deals with those.
     */
    fun computeGains(clients: List<Client>): Map<String, Int>? {
        val measured = clients.filter { (it.measuredLevel ?: 0.0) > 0.0 && it.gainPercent > 0 }
        if (measured.size < 2) return null

        // Aim at the QUIETEST client's current output: matching down rather than up keeps the room
        // quieter and stays inside the range every client actually has available.
        val target = measured.minOf { it.measuredLevel!! }

        val ideal = measured.associate { c ->
            // Level is proportional to amplitude, so the amplitude that hits the target is just the
            // current one scaled by how far off this client measured. No fitted constant anywhere.
            val wantAmplitude = percentToAmplitude(c.gainPercent) * (target / c.measuredLevel!!)
            val want = amplitudeToPercent(wantAmplitude)
            val damped = c.gainPercent + DAMPING * (want - c.gainPercent)
            c.id to damped.coerceIn(MIN_PERCENT.toDouble(), 100.0)
        }
        // Scale as a group so the loudest lands on the headroom cap: the ratios are the balance,
        // the absolute position is the user's to change afterwards.
        //
        // ON THE AMPLITUDE AXIS, because that is the only axis where a common factor preserves the
        // ratios this line claims to preserve. Multiplying the PERCENTAGES by a common factor, which
        // is what this did before, distorts them: {30, 60} scaled to {40, 80} moves the pair from
        // 9.53 dB apart to 10.91 dB apart, re-applied on every pass, in the same units the balance
        // is trying to converge in.
        val loudestAmplitude = ideal.values.maxOf { percentToAmplitude(it) }
        val scale =
            if (loudestAmplitude > 0) percentToAmplitude(HEADROOM_PERCENT) / loudestAmplitude else 1.0
        val startPercent = measured.associate { it.id to it.gainPercent }
        return ideal.mapValues { (id, g) ->
            val scaled = amplitudeToPercent((percentToAmplitude(g) * scale).coerceAtMost(1.0))
            // CAP LAST, against the gain this client STARTED at, and after the group scaling rather
            // than before it: the scaling is itself a move the user will hear, so a cap applied to
            // the pre-scale figure would not bound what actually gets written. Clamping one client
            // does distort the balance the ratios encode — that is the point. A bounded partial
            // correction that the next run continues is worth more than a full correction resting on
            // an estimator whose hardware sensitivity is not yet established.
            // Convert to AMPLITUDE before applying a dB cap. Capping the percentages directly would
            // not be a 6 dB cap at all: the percent axis is a base-10 exponential (see
            // [percentToAmplitude]), so halving the percentage is about 12 dB near the top of the
            // range and much more further down.
            val startAmp = percentToAmplitude(startPercent.getValue(id))
            val loAmp = startAmp * 10.0.pow(-MAX_CORRECTION_DB / 20.0)
            val hiAmp = startAmp * 10.0.pow(MAX_CORRECTION_DB / 20.0)
            val lo = amplitudeToPercent(loAmp)
            val hi = amplitudeToPercent(hiAmp)
            scaled.coerceIn(lo, hi).roundToInt().coerceIn(MIN_PERCENT, HEADROOM_PERCENT)
        }
    }

    /** True if [next] differs from [current] enough on any client to be worth writing out. Avoids
     *  churning the user's volumes by a percent or two on every run. */
    fun isWorthApplying(current: Map<String, Int>, next: Map<String, Int>, minStep: Int = 3): Boolean =
        next.any { (id, g) -> abs(g - (current[id] ?: g)) >= minStep }
}
