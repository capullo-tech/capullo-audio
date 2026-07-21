package tech.capullo.audio.calibration

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ring buffer of the broadcast reference PCM, fed from the FIFO tee (48 kHz / 16-bit /
 * stereo, LOCKSTEP with SnapserverProcess) and stored as 48 kHz mono float. Armed only
 * while a calibration runs; writes are cheap enough for the playback thread.
 *
 * The ring records [lastWriteNanos] so a mic capture can be coarsely aligned to ring
 * indices by wall clock. Coarse is enough: alignment error shifts BOTH speakers' peaks
 * by the same amount, and only their spacing matters.
 */
class ReferencePcmRing(seconds: Int = 30, private val sampleRate: Int = 48_000) {

    private val buf = FloatArray(seconds * sampleRate)
    private var writePos = 0

    @Volatile var totalSamples = 0L
        private set

    @Volatile var lastWriteNanos = 0L
        private set

    /** Called from the playback thread with the tee'd 16-bit stereo buffer (position untouched). */
    fun write(pcm: ByteBuffer) {
        val b = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var wrote = 0
        while (b.remaining() >= 4) {
            val l = b.short.toInt()
            val r = b.short.toInt()
            buf[writePos] = (l + r) / 65536.0f // mono mixdown, scaled to ±1
            writePos = (writePos + 1) % buf.size
            wrote++
        }
        synchronized(this) {
            totalSamples += wrote
            lastWriteNanos = System.nanoTime()
        }
    }

    /** Ordered copy of the ring (oldest→newest) plus the wall time of its last sample. */
    fun snapshot(): Snapshot {
        val pos: Int
        val total: Long
        val nanos: Long
        synchronized(this) {
            pos = writePos
            total = totalSamples
            nanos = lastWriteNanos
        }
        val filled = if (total >= buf.size) buf.size else pos
        val out = FloatArray(filled)
        if (total >= buf.size) {
            System.arraycopy(buf, pos, out, 0, buf.size - pos)
            System.arraycopy(buf, 0, out, buf.size - pos, pos)
        } else {
            System.arraycopy(buf, 0, out, 0, filled)
        }
        return Snapshot(out, nanos, sampleRate)
    }

    class Snapshot(val pcm: FloatArray, val lastSampleNanos: Long, val sampleRate: Int)
}
