# capullo-audio

The **delivery engine** of the [Capullo Audio Platform](https://github.com/capullo-tech).
An Android library that decodes whatever a source resolves through ExoPlayer, tees the PCM into a
Snapcast FIFO, and broadcasts time-synced audio on the LAN with a bundled web player.

Namespace `tech.capullo.audio` · Gradle 9.6.1 · Kotlin 2.3.10 · AGP 9.1.0 · Media3 1.9.0.

Where this sits: see [Platform shape](https://github.com/capullo-tech/capullo-audio-contracts#platform-shape).

## What's in here

| Package | Contents |
|---|---|
| `player` | `FifoAudioBufferSink` + `FifoRenderersFactory` - the ExoPlayer tee → 44100/16/2 FIFO chain |
| `snapcast` | `SnapserverProcess`, `SnapclientProcess`, `SnapcontrolPlugin`, JSON-RPC, discovery, NSD |
| (root) | `CapulloAudioEngine` - the public facade wiring source → player → snapserver → control plugin |
| `assets/webui` | the bundled Snapcast web player (`index.html`) |

## Dependencies (dependency direction is one-way)

- `api` → `capullo-audio-contracts` (the SPI). The engine speaks only `MediaSourceProvider` /
  `NowPlaying` / `PlaybackController` - it knows nothing about radio or Telegram, and never depends
  on a `capullo-source-*` library: sources are siblings, not a layer underneath.
- `implementation` → `lib-snapcast-android` (prebuilt native `.so`), Media3.
- The FFmpeg decoder (`lib-media3-ffmpeg-android`) is **reflection-loaded** by
  `DefaultRenderersFactory` when present on the app classpath - not a compile dependency here.

## ⚠️ Migration notes for consuming apps

1. **Artwork is published, not embedded.** Snapserver parses the now-playing JSON on the thread that
   reads the audio pipe, so a full-size image in `artData` stalls the broadcast at every track change.
   Hand the art bytes to `SnapcastArtwork.publish()`: it writes them into the snapserver doc_root
   (served as `/art/<hash>.<ext>`) and returns that path plus a ≤96 px JPEG thumbnail. Put the path in
   `extras["artUrl"]` and the thumbnail in `NowPlaying.artworkBase64`. `SnapcontrolMetadataMapper`
   embeds `artworkBase64` as `artData` only up to `MAX_ART_DATA_CHARS` (16 KiB) and labels it from its
   bytes; anything larger is dropped. Listen-in clients fetch the full image with `SnapcastRemoteArt`.
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

## License

Copyright 2026 capullo-tech. Licensed under GPLv3 - see [`LICENSE`](LICENSE) (integrates GPLv3 Snapcast via lib-snapcast-android; substantially derived from RadioCapullo).
