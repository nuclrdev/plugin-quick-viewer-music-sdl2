# 🎵🐢 Music Quick Viewer (SDL2)

A rich audio quick viewer plugin for **Nuclr Commander**. It opens music files directly inside the quick view panel, plays them through **SDL2 / SDL2_mixer**, and renders a live waveform so tracker modules feel as lively as they sound. 🎛️🐢

## ✨ What It Does

- 🎵 Plays music and audio files from Nuclr's quick view panel
- 🎛️ Supports tracker formats: `xm`, `mod`, `s3m`, `it`, `669`
- 🔊 Supports common audio formats: `mp3`, `ogg`, `flac`, `wav`, `aac`, `aiff`, `voc`, `mid`
- 🌈 **18 live visualizers** driven from the SDL post-mix audio callback and an FFT — pick one from the right-click menu on the visualizer
- ▶️ Play/pause, stop, seek, rewind (−10 s), and forward (+10 s) controls
- 🎚️ Progress bar with seeking, plus a volume slider
- ⏱️ Current time and total duration display (when SDL_mixer exposes them)

## 🌈 Visualizers

Right-click the visualizer area to choose an effect. The choice is remembered for the rest of the session (the default is **Amiga Cracktro**).

| Effect | Style |
|---|---|
| Reactor Core ☢ | Pulsing reactor with a layered background |
| Invasion '95 ▣ | Descending-swarm shooter where each column of aliens is an FFT band — they ride their band, flash on its spikes, and the swarm marches on the kick |
| Hyperspace ◆ | Vector-arcade rock shooter that plays itself — splitting asteroids, saucers, screen wrap, bloom; every shot fires on the kick |
| Second Horizon ▩ | 1993 PC demo style — six parts (starfield, glenz vectors, moiré, dot morph, warped logo, kefrens) that cut to the beat |
| Zivert ✈ | Retrowave |
| Aurora Mirror Wave | Neon mirrored waveform with a Catmull-Rom spline and multi-pass glow |
| Neon Spectrum Bars | FFT spectrum analyser |
| Assembly Demoscene ▲ | Demoscene tribute |
| id Inferno ☠ | Rip & Tear |
| Amiga Cracktro ◉ | Default — Boing! |
| ZX Spectrum ▚ | `LOAD ""` loading stripes |
| Norton Commander ▓ | MS-DOS text mode |
| BBS / ANSI ▒ | 14400 baud ANSI art |
| Dendy ▲ | TANK 1990 |
| C64 PETSCII ▞ | `LOAD"*",8,1` |
| Vectrex ✦ | Vector glow |
| Game Boy ▦ | Falling blocks |
| Macintosh ☺ | 1-bit dither |

## 🖼️ Screenshots

### Main Player View

![Main player view](images/screenshot-1.jpg)

### Quick View In Action

![Quick view in action](images/screenshot-2.jpg)

## 🎚️ Player Controls

| Control | Action |
|---|---|
| `⏮` | Jump back 10 seconds |
| `▶` / `⏸` | Toggle play / pause |
| `■` | Stop playback |
| `⏭` | Jump forward 10 seconds |
| Progress bar | Click or drag to seek |
| Volume slider | Adjust output level |
| Right-click on visualizer | Choose the visualizer effect |

## 🧩 Supported Extensions

| Category | Extensions |
|---|---|
| 🎼 Tracker modules | `xm`, `mod`, `s3m`, `it`, `669` |
| 🔊 Common audio | `mp3`, `ogg`, `flac`, `wav`, `aac`, `aiff`, `voc`, `mid` |

## 🖥️ Runtime Requirements

This plugin depends on **SDL2** and **SDL2_mixer**.

On Linux, the plugin loads the versioned runtime SONAMEs (`libSDL2-2.0.so.0`
and `libSDL2_mixer-2.0.so.0`), so the development packages are not required.

### Windows 🪟

Windows native libraries are bundled with the plugin and extracted automatically at runtime. No extra installation needed.

### macOS 🍎

```bash
brew install sdl2_mixer
```

The `sdl2_mixer` formula installs the SDL2 compatibility runtime and codec dependencies. The plugin auto-detects standard Homebrew library locations (`/opt/homebrew/lib`, `/usr/local/lib`, `/opt/local/lib`). For a custom location, launch the JVM with:

```bash
-Djna.library.path=/path/to/libs
```

### Linux 🐧

```bash
sudo apt-get install libsdl2-2.0-0 libsdl2-mixer-2.0-0
```

For a custom location:

```bash
-Djna.library.path=/path/to/libs
```

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
quick-view-sdl2-music-<version>.zip
quick-view-sdl2-music-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

`MusicSDL2QuickViewPlugin` initialises SDL2 and SDL2_mixer via JNA bindings. On Windows, `NativeLibExtractor` unpacks the bundled `.dll` files to a temp directory before the JNA load. The `Mix_SetPostMix` callback runs on the SDL audio thread and writes S16 stereo PCM into `AudioRingBuffer` as mono float samples; `WaveformPanel` reads a snapshot on the EDT at ~60 fps, runs it through `Fft` where the effect needs frequency data, and delegates painting to the selected visualizer. Processing arrays are pre-allocated to avoid GC pressure in the render loop. Loading and SDL initialisation run on a virtual thread so the Swing EDT stays responsive.

## 🗂️ Source Layout

```text
src/main/java/
├── dev/nuclr/plugin/core/quick/viewer/music/sdl2/
│   ├── MusicSDL2QuickViewPlugin.java   plugin entry point
│   ├── MusicSDl2ViewPanel.java         player UI panel (transport, seek bar, volume)
│   ├── WaveformPanel.java              render loop, visualizer mode menu, dispatch
│   ├── Fft.java                        FFT used by the spectrum-driven effects
│   ├── TextModeScreen.java             shared text-mode cell grid (DOS/BBS/C64/ZX effects)
│   ├── ReactorBackground.java          layered backdrop for the Reactor effect
│   └── *Visualizer.java                the 18 effects (Reactor, Invasion '95, Hyperspace, …)
└── sdl2/
    ├── SDLMixerAudio.java              SDL2 / SDL2_mixer JNA bindings
    ├── AudioRingBuffer.java            lock-free SPSC ring buffer for PCM samples
    ├── Sdl2Support.java                platform capability / availability checks
    ├── Sdl2LibraryNames.java           per-OS library SONAMEs
    ├── NativeLibExtractor.java         Windows native library extractor
    └── SDLDiagnostic.java              standalone SDL environment diagnostics
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.1` | Nuclr platform interfaces |
| `jna` | `5.18.1` | Native SDL2 / SDL2_mixer bindings |

## 🌐 Links

- Website: <https://nuclr.dev>
- Plugin page: <https://nuclr.dev/plugins/core/music-sdl2-quick-viewer.html>

## 📄 License

Apache-2.0. SDL-related runtime notices are included in the repository where required.
