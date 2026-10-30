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
    var wav by rememberSaveable { mutableStateOf(false) }

    ScreenIntro("Extract audio", "Pull the sound out of any video, or convert an audio file to M4A or WAV.")
    MediaPickerCard("Import a video or audio file", s.media, { s.media = it; s.extractJob.clear() }, { s.media = null })

    SectionCard("Output format") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "M4A · AAC", true to "WAV · lossless").forEachIndexed { i, (isWav, label) ->
                SegmentedButton(
                    selected = wav == isWav, onClick = { wav = isWav },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)
                ) { Text(label) }
            }
        }
        Text(
            if (wav) "Uncompressed, large files. Best for editing."
            else "Small files, good quality. Plays everywhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrimaryButton("Extract audio", Icons.Rounded.GraphicEq, s.media != null && !s.extractJob.busy) {
        val uri = s.media ?: return@PrimaryButton
        val asWav = wav
        s.extractJob.start("Preparing…") {
            val base = baseName(displayName(ctx, uri))
            val stamp = System.nanoTime()

            suspend fun viaWav(): JobState {
                update("Decoding audio…")
                val f = File(ctx.cacheDir, "extract_$stamp.wav")
                try {
                    WavExtractor.extract(ctx, uri, f)
                    val saved = saveFile(ctx, f, "$base.wav", "audio/wav")
                    return JobState.Done("Saved $base.wav to Downloads/VoiceCut", saved, "audio/wav")
                } finally {
                    f.delete()
                }
            }

            if (asWav) return@start viaWav()
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
                viaWav()
            } finally {
                f.delete()
            }
        }
    }
    JobStatus(s.extractJob)
    Text(
        "Formats are limited by what your phone can decode. If a file fails here, the Transcribe tab still accepts almost any format.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
