# 🎵🐢 Music Quick Viewer (SDL2)

A rich audio quick viewer plugin for **Nuclr Commander**. It opens music files directly inside the quick view panel, plays them through **SDL2 / SDL2_mixer**, and renders a live waveform so tracker modules feel as lively as they sound 🎛️🐢

## ✨ What It Does

- Plays music and audio files from Nuclr's quick view panel
- Supports tracker formats like `xm`, `mod`, `s3m`, `it`, and `669`
- Supports common audio formats like `mp3`, `ogg`, `flac`, `wav`, `aac`, `aiff`, `voc`, and `mid`
- Shows a live oscilloscope-style waveform visualizer
- Includes play/pause, stop, seek, rewind, forward, and volume controls
- Displays current time and total duration when SDL_mixer exposes them

## 🖼️ Screenshots

### Main Player View

![Main player view](images/screenshot-1.jpg)

### Quick View In Action

![Quick view in action](images/screenshot-2.jpg)

## 🎚️ UI Notes

The viewer is designed for fast inspection rather than heavyweight library management:

- `⏮` jumps back 10 seconds
- `▶ / ⏸` toggles playback
- `■` stops playback
- `⏭` jumps forward 10 seconds
- The progress bar supports seeking
- The waveform is driven from the SDL post-mix audio callback

## 📦 Supported Extensions

`wav`, `flac`, `aac`, `voc`, `aiff`, `mid`, `ogg`, `mp3`, `xm`, `mod`, `s3m`, `it`, `669`

## 🧩 Runtime Requirements

This plugin depends on **SDL2** and **SDL2_mixer**.

### Windows 🪟

Windows native libraries are bundled with the plugin and extracted automatically at runtime.

### macOS 🍎

Install the runtime libraries first:

```bash
brew install sdl2 sdl2_mixer
```

The plugin now auto-detects standard Homebrew library locations such as:

- `/opt/homebrew/lib`
- `/usr/local/lib`
- `/opt/local/lib`

If your SDL libraries live somewhere unusual, launch the JVM with:

```bash
-Djna.library.path=/path/to/libs
```

### Linux 🐧

Install SDL runtime packages with your distro package manager. For Debian/Ubuntu-based systems:

```bash
sudo apt-get install libsdl2-2.0-0 libsdl2-mixer-2.0-0
```

If required, you can also point JNA at a custom location with:

```bash
-Djna.library.path=/path/to/libs
```

## 🛠️ Building

Build the plugin package with Maven:

```bash
mvn -DskipTests package
```

Artifacts are produced under `target/`, including the plugin ZIP package.

## 🧱 Project Notes

- Java target: `21`
- Native binding layer: `JNA`
- Plugin id: `dev.nuclr.plugin.core.quickviewer.music.sdl2`
- Plugin version: `1.0.0`

## 🌐 Links

- Website: <https://nuclr.dev>
- Plugin page: <https://nuclr.dev/plugins/core/music-sdl2-quick-viewer.html>

## 📄 License

This project is licensed under **Apache-2.0**. SDL-related runtime notices are included in the repository where required.
