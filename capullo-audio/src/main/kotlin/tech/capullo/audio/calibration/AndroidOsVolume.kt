package tech.capullo.audio.calibration

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.io.File

/**
 * [DeviceVolume] backed by this device's media stream. `flags = 0` so a calibration boost adjusts
 * the volume silently instead of popping the system volume HUD on every step.
 *
 * `setStreamVolume` can throw when a Do-Not-Disturb policy owns the stream (that needs
 * ACCESS_NOTIFICATION_POLICY, which we deliberately do not request for a calibration nicety), so
 * writes are swallowed and logged: a boost that cannot be applied must degrade to "this speaker
 * stays quiet and is reported unmeasurable", never to a crash.
 *
 * Moved here from QuantumCast 2026-08-28 - pure AudioManager + filesDir, no app types.
 */
class AndroidOsVolume(context: Context) : DeviceVolume {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val maxIndex: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun current(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun set(index: Int) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume($index) refused: ${e.message}")
        }
    }

    private companion object {
        private const val TAG = "OsVolumeBoost"
    }
}

/**
 * File-backed [OsVolumeJournal] in the app's private storage: one line holding the pre-boost
 * media-volume index. Survives process death and reboots, so a boost the OS killed mid-run is
 * undone on the next start. [save] reports failure so the caller can refuse to boost rather than
 * strand the user's phone loud with no record of the original.
 */
class FileOsVolumeJournal(context: Context) : OsVolumeJournal {

    private val file = File(context.filesDir, "os_volume_journal")

    override fun save(preBoostIndex: Int): Boolean = try {
        file.writeText(preBoostIndex.toString())
        true
    } catch (e: Exception) {
        Log.e(TAG, "save failed: ${e.message}")
        false
    }

    override fun load(): Int? {
        if (!file.exists()) return null
        return try {
            file.readText().trim().toInt()
        } catch (e: Exception) {
            Log.e(TAG, "load failed, discarding journal: ${e.message}")
            clear()
            null
        }
    }

    override fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private companion object {
        private const val TAG = "OsVolumeBoost"
    }
}
