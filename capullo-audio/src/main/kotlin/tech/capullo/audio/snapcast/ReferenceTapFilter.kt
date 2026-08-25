package tech.capullo.audio.snapcast

import tech.capullo.audio.calibration.SyncCalibrator

/**
 * Drops the calibration reference tap from a server status.
 *
 * A client-side calibration runs a second, silent snapclient to obtain its reference PCM (see
 * [ReferenceTapProcess]). For the duration of that run the tap is a genuinely CONNECTED client:
 * unnamed, 100 % volume, audible to nobody. Nothing in either app wants to see it —
 * [SyncCalibrator] already drops it before choosing speakers, for the concrete reason that two
 * speakers plus a tap is three clients, which routes the run to a batch path that declines the
 * excursion three speakers need and harvests no levels at all.
 *
 * Every OTHER consumer of the client list wants it gone for the same reason: it is not a speaker.
 * Without this it appears as a phantom card in the control sheet, inflates the connected-device
 * count beside it, and is swept up by reset-all. Applying this once where the status arrives keeps
 * that from being six separate filters that must each remember the rule.
 *
 * Groups left with no clients are dropped too. Snapserver puts each newly registered client in a
 * group of its own, so a tap always arrives as a one-client group; without this the phantom card
 * disappears but its empty group remains.
 *
 * The web player is NOT covered. It talks to snapserver over its own websocket and never sees
 * these models, so it needs the equivalent filter in its own client-list rendering.
 */
fun List<Group>.withoutReferenceTaps(): List<Group> = mapNotNull { group ->
    val kept = group.clients.filterNot { it.id.startsWith(SyncCalibrator.REFERENCE_TAP_PREFIX) }
    when {
        kept.isEmpty() -> null
        kept.size == group.clients.size -> group
        else -> group.copy(clients = kept)
    }
}
