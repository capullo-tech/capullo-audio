package tech.capullo.audio.calibration

import kotlin.math.abs
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
     * How measured salience responds to gain: `measured ≈ gain^EXPONENT`. Fitted at roughly 0.6 from
     * the rig sweep (SW 12% → z≈13 against SW 100% → z≈34-56), i.e. salience rises much more slowly
     * than gain. EMPIRICAL AND ROUGH — it assumes the SW percentage is linear in amplitude, and it
     * must flatten once the measurement stops being noise-limited and becomes reverberation-limited.
     * It only sets the step size of the correction, never its direction or its fixed point, so an
     * error here costs an extra iteration rather than a wrong answer.
     */
    const val LEVEL_EXPONENT = 0.6

    /** Fraction of the computed step actually applied per pass. Under-stepping converges from below
     *  instead of oscillating around the target, which matters because each measurement is noisy. */
    const val DAMPING = 0.7

    /** Balanced enough: stop when the loudest and quietest measured levels are within this ratio.
     *  ~1.25 is a hair over 1.9 dB, comfortably inside what anyone notices as an imbalance. */
    const val BALANCE_TOL_RATIO = 1.25

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
        return ideal.mapValues { (_, g) ->
            (g * scale).roundToInt().coerceIn(MIN_PERCENT, HEADROOM_PERCENT)
        }
    }

    /** True if [next] differs from [current] enough on any client to be worth writing out. Avoids
     *  churning the user's volumes by a percent or two on every run. */
    fun isWorthApplying(current: Map<String, Int>, next: Map<String, Int>, minStep: Int = 3): Boolean =
        next.any { (id, g) -> abs(g - (current[id] ?: g)) >= minStep }
}
