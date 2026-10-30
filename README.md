# VoiceCut Studio

Android app (Kotlin + Jetpack Compose, light/dark) for working with speech in video and audio.

| Tab | What it does |
|---|---|
| **Extract** | Pull audio out of a video (MP3, M4A/AAC or WAV), convert audio, and cut a section or split a long recording into parts |
| **Transcribe** | Speech to text with timestamps and separation of any number of speakers (AssemblyAI). Each voice is labelled **Male** / **Female** by listening to its pitch (free, on-device; switch it if a guess is wrong). Choose the spoken language (Bengali / Hindi / English / auto) and the exact number of speakers. Export TXT / SRT / JSON |
| **Voice** | Create an audio file in **Bengali, Hindi or English** from a transcript in any language (translated automatically, on-device for free or with OpenAI) (TXT / SRT / JSON / plain text) or typed text. On-device voices (free), ElevenLabs (incl. Bengali) or OpenAI voices. Output WAV / MP3 / M4A |
| **Video** | Remove a video's audio, or replace it with another track or your generated voice-over |

All output is saved to `Downloads/VoiceCut`, with Open and Share buttons.

## Writing a script for the Voice tab
Only the spoken words are read. Put the speaker before a colon and it is used as the voice, not spoken:

```
ছেলে: তুমি কেমন আছো?
মেয়ে: (হেসে) ভালো আছি, তুমি?
```
- `Male` / `Female` / `Man` / `Woman` / `ছেলে` / `মেয়ে` / `लड़का` / `लड़की`, `Speaker 2` and names like `Rahim:` are understood.
- `(laughs)`, `[pause]`, `*sigh*` and wrapping quotes are removed.
- A line without a label continues the previous speaker. Use the **→ Male / → Female** buttons on a line to change who says it.
- Voices are picked by gender automatically (ElevenLabs and OpenAI know each voice's gender). Device voices: best-effort guess, and the pitch is shifted if both speakers would otherwise sound the same.

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

## Updating without uninstalling
Every build is signed with the same key (`app/keystore/voicecut.jks`) and its version number grows with each GitHub run, so a new APK installs over the old one and keeps your settings and API keys.
- Keep the `app/keystore` folder in the repo.
- Only the first install after switching to this key needs one uninstall of the old app (it was signed with a different key).
- This key is for personal use. If you ever publish the app, create a private key and keep it out of the repo.

## Limitations
- MP3 uses the bundled LAME encoder (`app/src/main/cpp`, built with the NDK; LGPL/GPL licensed code)
- Transcription and cloud voices need internet
- Device voices must match the text language
