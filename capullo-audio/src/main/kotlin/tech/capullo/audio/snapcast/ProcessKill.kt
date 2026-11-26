package tech.capullo.audio.snapcast

import android.os.Build

/**
 * Ends a child process without waiting for it to cooperate. `Process.destroyForcibly()` is
 * API 26 and missing on 23 to 25, where calling it fails at run time; `destroy()` stands in
 * there (the library's minSdk is 23).
 */
internal fun Process.kill() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}
