# VoiceScribe

Offline AI voice recorder for Android: record a lecture, meeting, or memo and get a
full transcript plus a bullet summary — **entirely on the phone**. No cloud, no
account, no minute caps. Works in airplane mode.

## How it works

- **Recording** — a foreground microphone service captures mono 16 kHz PCM16 WAV,
  so lecture-length recordings survive the screen locking.
- **Transcription** — [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
  (vendored at `third_party/whisper.cpp`, built via the `:whisper` module's CMake)
  with quantized ggml models. Language auto-detect across 99 languages; the JNI
  layer is patched to pass `language` through and expose the detected language.
- **Models** — shipped as a **post-install download** (32–190 MB) to keep the APK
  small. Device RAM decides the recommended tier (tiny / base / small, all q5_1).
- **Summary** — extractive summarizer (frequency-scored sentence selection); zero
  model overhead, runs on any device. An on-device LLM summary is the planned
  upgrade path.
- **Storage** — plain files under `filesDir/recordings` (`<id>.wav` + `<id>.json`).

## Build

```bash
scripts/fetch-whisper.sh   # clones whisper.cpp v1.7.6 into third_party/ (gitignored)
./gradlew :app:assembleDebug
```

Requires NDK 27.1.12297006 (see `whisper/build.gradle.kts`) and CMake.

## Project layout

- `app/` — Compose UI (home/record, detail with Summary/Transcript tabs, model picker),
  `RecorderService`, `TranscriptionEngine` (serial queue, one whisper context reused),
  `ModelManager` (catalog + downloader), `Summarizer`.
- `whisper/` — Android library wrapping whisper.cpp: JNI (`jni.c`) + Kotlin bindings
  (`LibWhisper.kt`), CPU-tier `.so` selection (fp16 / vfpv4 / generic).

## Product notes

- Positioning: meetings, lectures, memos — not call recording (Android restrictions).
- Share/export adds the "Transcribed offline with VoiceScribe" footer.
- Monetization plan: transcription free forever; one-time unlock for PDF/DOCX export,
  speaker labels, folder sync.
