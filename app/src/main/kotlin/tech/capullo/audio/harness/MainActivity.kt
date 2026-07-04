package tech.capullo.audio.harness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.audio.CapulloAudioEngine
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.contracts.PlaybackQueue

/**
 * Minimal harness proving the engine's public surface is consumable end-to-end: build fake SPI
 * implementations, construct [CapulloAudioEngine], and confirm the app assembles with the snapcast
 * `.so` bundled. It does NOT auto-start a broadcast (that needs a foreground service + runtime
 * permissions) - this is a compile/package smoke test of the API, like lib-snapcast-android's :app.
 */
@UnstableApi
class MainActivity : Activity() {

    private val state = MutableStateFlow(NowPlaying(title = "Harness", album = "capullo-audio"))

    private val source = object : MediaSourceProvider {
        override suspend fun mediaRequestFor(id: String): MediaRequest = MediaRequest(uri = id)
        override fun queue(): PlaybackQueue = object : PlaybackQueue {
            override val size: Int = 1
            override val currentIndex: Int = 0
            override val isRotating: Boolean = true
            override fun idAt(index: Int): String? =
                if (index == 0) "https://example.org/stream.mp3" else null
        }
    }

    private val nowPlayingSource = object : NowPlayingSource {
        override val nowPlaying: StateFlow<NowPlaying> = state
    }

    private val controller = object : PlaybackController {
        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(Dispatchers.Main)
        val engine = CapulloAudioEngine(this, source, nowPlayingSource, controller, scope)
        setContentView(
            TextView(this).apply {
                text = "capullo-audio harness\nengine wired: ${engine.javaClass.simpleName}"
            },
        )
    }
}
