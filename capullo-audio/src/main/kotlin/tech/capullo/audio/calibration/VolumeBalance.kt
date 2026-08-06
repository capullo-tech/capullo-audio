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
 *  - **Headroom.** The loudest client is placed at [HEADROOM_PERCENT], never at 100. Balance is a set
 *    of RATIOS, and pinning the loudest to full scale would leave the user unable to turn everything
 *    up afterwards without editing clients one by one — the balance would have silently consumed
 *    their global control. Leaving room means a later global change scales all clients together and
 *    preserves the balance.
 *  - **Attenuate rather than amplify.** Equalising by pulling the near speaker down, instead of
 *    pushing the far one up, gives the same measurement quality with less noise in the room, and it
 *    is the direction that always has somewhere to go: SW gain starts at 100%, so there is headroom
 *    downward and none upward.
 */
object VolumeBalance {

    /** Where the loudest client lands, leaving the rest of the range for a later global change. */
    const val HEADROOM_PERCENT = 80

    /** Never drive a client below this: past it a speaker stops contributing to the room at all,
     *  and it would also fall under the calibration's own detection floor. */
    const val MIN_PERCENT = 15

    /**
     * How measured salience responds to gain: `measured ≈ percent^EXPONENT`. Fitted at roughly 0.6
     * from the rig sweep (SW 12% → z≈13 against SW 100% → z≈34-56), i.e. salience rises much more
     * slowly than the percentage.
     *
     * EMPIRICAL AND ROUGH, and now known to be fitted against the WRONG AXIS: the 0.6 was measured
     * against `percent`, but percent is a base-10 exponential in amplitude (see [percentToAmplitude]),
     * so this exponent is silently absorbing that curve along with whatever the room does. It is not
     * an acoustic property and should not be read as one.
     *
     * Left as-is deliberately rather than re-derived on the spot: it only sets the STEP SIZE of a
     * correction, never its direction or its fixed point, so an error here costs an extra iteration
     * rather than a wrong answer — and the correction is now capped at [MAX_CORRECTION_DB] anyway,
     * which bounds the cost of a bad step. Re-deriving it needs a sensitivity measurement that does
     * not exist yet; doing it from the same rig data that produced 0.6 would just relabel the error.
     */
    const val LEVEL_EXPONENT = 0.6

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
    fun percentToAmplitude(percent: Int): Double =
        ((10.0.pow(percent.coerceIn(0, 100) / 100.0) - 1.0) / 9.0).coerceIn(0.0, 1.0)

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
     * Each client's measurement tells us what it produces per unit of gain, so the gain that would
     * put them all at a common level follows directly; the results are then scaled together so the
     * loudest sits at [HEADROOM_PERCENT], which preserves the ratios while leaving room above.
     * Only a fraction [DAMPING] of each move is taken, because the measurement behind it is noisy
     * and converging from below beats hunting around the answer.
     *
     * Undetected clients keep the gain they have: raising a speaker nobody measured would be
     * guessing, and it is the calibration's detectability boost that deals with those.
     */
    fun computeGains(clients: List<Client>): Map<String, Int>? {
        val measured = clients.filter { (it.measuredLevel ?: 0.0) > 0.0 && it.gainPercent > 0 }
        if (measured.size < 2) return null

        // What each client yields per unit gain, backing the gain law out of the measurement.
        val efficiency = measured.associate { c ->
            c.id to (c.measuredLevel!! / c.gainPercent.toDouble().pow(LEVEL_EXPONENT))
        }
        // Aim at the QUIETEST client's current output: matching down rather than up keeps the room
        // quieter and stays inside the range every client actually has available.
        val target = measured.minOf { it.measuredLevel!! }

        val ideal = measured.associate { c ->
            val want = (target / efficiency.getValue(c.id)).pow(1.0 / LEVEL_EXPONENT)
            val damped = c.gainPercent + DAMPING * (want - c.gainPercent)
            c.id to damped.coerceIn(MIN_PERCENT.toDouble(), 100.0)
        }
        // Scale as a group so the loudest lands on the headroom cap: the ratios are the balance,
        // the absolute position is the user's to change afterwards.
        val loudest = ideal.values.max()
        val scale = if (loudest > 0) HEADROOM_PERCENT / loudest else 1.0
        val startPercent = measured.associate { it.id to it.gainPercent }
        return ideal.mapValues { (id, g) ->
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
            (g * scale).coerceIn(lo, hi).roundToInt().coerceIn(MIN_PERCENT, HEADROOM_PERCENT)
        }
    }

    /** True if [next] differs from [current] enough on any client to be worth writing out. Avoids
     *  churning the user's volumes by a percent or two on every run. */
    fun isWorthApplying(current: Map<String, Int>, next: Map<String, Int>, minStep: Int = 3): Boolean =
        next.any { (id, g) -> abs(g - (current[id] ?: g)) >= minStep }
}
