# capullo-audio

The **delivery engine** (Layer 2) of the [Capullo Audio Platform](https://github.com/capullo-tech).
An Android library that decodes whatever a source resolves through ExoPlayer, tees the PCM into a
Snapcast FIFO, and broadcasts time-synced audio on the LAN with a bundled web player.

Namespace `tech.capullo.audio` · Gradle 9.6.1 · Kotlin 2.3.10 · AGP 9.1.0 · Media3 1.9.0.

## What's in here

| Package | Contents |
|---|---|
| `player` | `FifoAudioBufferSink` + `FifoRenderersFactory` - the ExoPlayer tee → 44100/16/2 FIFO chain |
| `snapcast` | `SnapserverProcess`, `SnapclientProcess`, `SnapcontrolPlugin`, JSON-RPC, discovery, NSD |
| (root) | `CapulloAudioEngine` - the public facade wiring source → player → snapserver → control plugin |
| `assets/webui` | the bundled Snapcast web player (`index.html`) |

## Dependencies (dependency direction is one-way)

- `api` → `capullo-audio-contracts` (Layer 1 SPI). The engine speaks only `MediaSourceProvider` /
  `NowPlaying` / `PlaybackController` - it knows nothing about radio or Telegram.
- `implementation` → `lib-snapcast-android` (Layer 0 native `.so`), Media3.
- The FFmpeg decoder (`lib-media3-ffmpeg-android`) is **reflection-loaded** by
  `DefaultRenderersFactory` when present on the app classpath - not a compile dependency here.

## ⚠️ Migration notes for consuming apps

1. **Artwork moved to the app.** The old plugin downloaded artwork from a URL / read an art file
   itself. The engine's `SnapcontrolPlugin` now emits `NowPlaying.artworkBase64` verbatim - the
   **source/app is responsible for producing base64 artwork** (and may set `extras["artExtension"]`).
2. **`SnapserverProcess.STREAM_NAME` is hardcoded `"name=QuantumCast"`** - parameterize per app
   before Telecloud consumes the engine.
3. `CapulloAudioEngine` covers the broadcast core. Still to wire (see its KDoc): prefetch feedback
   (`onQueueAdvanced`), snapclient/listen-in mode, and app-owned Service concerns (notifications,
   watchdog, buffering UI).

## Build

```bash
./gradlew :capullo-audio:assembleRelease   # library AAR
./gradlew :app:assembleDebug               # harness APK (bundles the snapcast .so)
```

Requires the Android SDK. Contracts resolves from jitpack by default; for local co-development,
check it out beside this repo and `settings.gradle.kts` substitutes it via a composite build.
