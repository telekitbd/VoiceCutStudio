# VoiceCut Studio

Android app (Kotlin + Jetpack Compose, light/dark) for working with speech in video and audio.

| Tab | What it does |
|---|---|
| **Extract** | Pull audio out of a video (MP3, M4A/AAC or WAV), convert audio, and cut a section or split a long recording into parts |
| **Transcribe** | Speech to text with timestamps and speaker separation (AssemblyAI). Export TXT / SRT / JSON |
| **Voice** | Create an audio file from a transcript (TXT / SRT / JSON / plain text) or typed text. On-device voices (free), ElevenLabs (incl. Bengali) or OpenAI voices. Output WAV / MP3 / M4A |
| **Video** | Remove a video's audio, or replace it with another track or your generated voice-over |

All output is saved to `Downloads/VoiceCut`, with Open and Share buttons.

## Importing files
- Import once on any tab; the file is shared across tabs.
- The picker accepts any file. You can also **Share** or **Open with** VoiceCut from other apps.
- Local tools (Extract, Video) support whatever your phone can decode (MP4, MKV, WEBM, MOV, 3GP, MP3, WAV, M4A, AAC, FLAC, OGG, OPUS…).
- Transcribe falls back to uploading the original file, and AssemblyAI accepts nearly any format.

## API keys
Open **Settings** (gear icon). Enter a key and press **Save**; it stays on the device and is excluded from backups.
- AssemblyAI: required for Transcribe
- ElevenLabs: optional cloud voices (use model *v3* for Bengali)
- OpenAI: optional cloud voices

## Build the APK (GitHub)
1. Push this folder to a GitHub repo
2. **Actions → Build APK → Run workflow** (also runs on push)
3. Download `VoiceCutStudio-debug-apk` from **Artifacts** and install it

## Limitations
- MP3 uses the bundled LAME encoder (`app/src/main/cpp`, built with the NDK; LGPL/GPL licensed code)
- Transcription and cloud voices need internet
- Device voices must match the text language
