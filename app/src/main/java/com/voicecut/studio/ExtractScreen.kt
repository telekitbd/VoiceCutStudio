@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable
fun ExtractScreen(s: AppState) {
    val ctx = LocalContext.current
    var format by rememberSaveable { mutableStateOf("m4a") }

    ScreenIntro("Extract audio", "Pull the sound out of any video, or convert an audio file to MP3, M4A or WAV.")
    MediaPickerCard("Import a video or audio file", s.media, { s.media = it; s.extractJob.clear() }, { s.media = null })

    SectionCard("Output format") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("mp3" to "MP3", "m4a" to "M4A", "wav" to "WAV").forEachIndexed { i, (k, label) ->
                SegmentedButton(
                    selected = format == k, onClick = { format = k },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)
                ) { Text(label) }
            }
        }
        Text(
            when (format) {
                "mp3" -> "192 kbps. Plays on every device and app."
                "wav" -> "Uncompressed, large files. Best for editing."
                else -> "Small files, good quality (AAC)."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrimaryButton("Extract audio", Icons.Rounded.GraphicEq, s.media != null && !s.extractJob.busy) {
        val uri = s.media ?: return@PrimaryButton
        val fmt = format
        s.extractJob.start("Preparing…") {
            val base = baseName(displayName(ctx, uri))
            val stamp = System.nanoTime()

            // Decode to WAV (anything the phone can play), optionally encode to MP3.
            suspend fun viaWav(asMp3: Boolean): JobState {
                update("Decoding audio…")
                val wav = File(ctx.cacheDir, "extract_$stamp.wav")
                val mp3 = File(ctx.cacheDir, "extract_$stamp.mp3")
                try {
                    WavExtractor.extract(ctx, uri, wav)
                    return if (asMp3) {
                        update("Encoding MP3…")
                        Mp3.encodeWav(wav, mp3, 192) { update("Encoding MP3… ${(it * 100).toInt()}%", it) }
                        JobState.Done("Saved $base.mp3 to Downloads/VoiceCut", saveFile(ctx, mp3, "$base.mp3", "audio/mpeg"), "audio/mpeg")
                    } else {
                        JobState.Done("Saved $base.wav to Downloads/VoiceCut", saveFile(ctx, wav, "$base.wav", "audio/wav"), "audio/wav")
                    }
                } finally {
                    wav.delete(); mp3.delete()
                }
            }

            if (fmt == "wav") return@start viaWav(false)
            if (fmt == "mp3") return@start viaWav(true)
            val f = File(ctx.cacheDir, "extract_$stamp.m4a")
            try {
                update("Extracting audio…")
                MediaProcessor.extractAudio(ctx, uri, f) { update("Extracting audio… $it%", it / 100f) }
                val saved = saveFile(ctx, f, "$base.m4a", "audio/mp4")
                JobState.Done("Saved $base.m4a to Downloads/VoiceCut", saved, "audio/mp4")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update("M4A isn't possible for this file, switching to WAV…")
                viaWav(false)
            } finally {
                f.delete()
            }
        }
    }
    JobStatus(s.extractJob)
    CutSection(s, format)
    Text(
        "Formats are limited by what your phone can decode. If a file fails here, the Transcribe tab still accepts almost any format.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
