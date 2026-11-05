package tech.capullo.audio.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Holds the broadcast's PCM while the player is paused, so remote listeners hear a clean stop and a
 * clean resume instead of the scattered "blips" a pause used to produce.
 *
 * Why there are blips at all: pausing stops the AudioTrack, but ExoPlayer keeps processing until
 * the track's buffer is full, so a few hundred ms of already-decoded audio still runs through the
 * tee in bursts after the user pressed pause. Each burst reaches snapserver after its stream went
 * idle (120 ms without data), gets treated as a new stream start, resynced, and played on every
 * speaker as an isolated fragment of the song.
 *
 * While [holding], incoming PCM is kept here instead of reaching the FIFO. The first
 * [fadeBytes] of it are released straight away with a linear fade to silence, so the stop doesn't
 * click; the rest waits. On [resume] the remainder is released with a matching fade-in, so the
 * broadcast loses nothing - listeners hear exactly the audio they would have heard, with a short
 * dip where the pause was.
 *
 * Format: 16-bit interleaved stereo in native byte order (what the tee delivers). Not
 * thread-safe; the owner serializes calls (the FIFO sink does so under its write lock).
 */
internal class PcmPauseGate(capacityBytes: Int, private val fadeBytes: Int) {

    private val ring = ByteArray(capacityBytes)
    private var held = 0 // bytes stored, from ring[0]
    private var released = 0 // bytes of ring already handed out (the faded-out tail)
    private var fadedOut = false
    private val scratch: ByteBuffer = ByteBuffer.allocate(maxOf(fadeBytes, 4)).order(ByteOrder.nativeOrder())

    /** Bytes dropped because more arrived while paused than [capacityBytes] could hold. */
    var droppedBytes = 0L
        private set

    var holding = false
        private set

    fun pause() {
        if (holding) return
        holding = true
        held = 0
        released = 0
        fadedOut = false
    }

    /**
     * Stores [input] (consuming it) while holding. Returns the faded-out tail once enough audio has
     * arrived to make it, else null. The returned buffer is reused - write it out before the next
     * call.
     *
     * [fadeOutAllowed] = false when the output has already gone quiet since the pause: writing a
     * fade-out then would itself be the isolated fragment this class exists to prevent, so
     * everything is held instead (resume still fades it in).
     */
    fun offer(input: ByteBuffer, fadeOutAllowed: Boolean = true): ByteBuffer? {
        if (!fadedOut && held == 0 && !fadeOutAllowed) fadedOut = true
        val n = input.remaining()
        val room = ring.size - held
        val take = minOf(n, room)
        input.get(ring, held, take)
        if (take < n) {
            droppedBytes += (n - take).toLong()
            input.position(input.limit())
        }
        held += take
        if (fadedOut || held < fadeBytes) return null
        fadedOut = true
        released = fadeBytes
        return faded(0, fadeBytes, fadeIn = false)
    }

    /**
     * Ends the hold. Returns what must be written before any new audio (the held audio after the
     * faded-out tail, faded back in), or null if nothing is left. The returned buffer is only valid
     * until the next call.
     */
    fun resume(): ByteBuffer? {
        if (!holding) return null
        holding = false
        val start = released
        val len = held - start
        held = 0
        released = 0
        if (len <= 0) return null
        if (!fadedOut) return ByteBuffer.wrap(ring, 0, len).order(ByteOrder.nativeOrder())
        val fadeLen = minOf(fadeBytes, len) and 3.inv()
        if (fadeLen > 0) {
            // Fade the first part in place, then hand out the whole remainder as one view.
            val view = faded(start, fadeLen, fadeIn = true)
            view.get(ring, start, fadeLen)
        }
        return ByteBuffer.wrap(ring, start, len).order(ByteOrder.nativeOrder())
    }

    /** Drop everything held (seek, stop, format change): that audio must never play. */
    fun discard() {
        holding = false
        held = 0
        released = 0
        fadedOut = false
    }

    private fun faded(offset: Int, len: Int, fadeIn: Boolean): ByteBuffer {
        val src = ByteBuffer.wrap(ring, offset, len).order(ByteOrder.nativeOrder())
        scratch.clear()
        val frames = len / 4
        for (f in 0 until frames) {
            val t = if (frames > 1) f.toFloat() / (frames - 1) else 1f
            val g = if (fadeIn) t else 1f - t
            scratch.putShort((src.short * g).toInt().toShort())
            scratch.putShort((src.short * g).toInt().toShort())
        }
        scratch.flip()
        return scratch
    }
}
