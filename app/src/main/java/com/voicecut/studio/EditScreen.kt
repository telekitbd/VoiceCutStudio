@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
fun EditScreen(s: AppState) {
    val ctx = LocalContext.current
    var replace by rememberSaveable { mutableStateOf(false) }
    var audio by rememberSaveable { mutableStateOf<Uri?>(null) }
    val info by rememberMediaInfo(s.media)
    val notVideo = info?.hasVideo == false

    ScreenIntro("Video audio", "Remove the sound from a video, or replace it with another track or your generated voice.")
    MediaPickerCard("Import a video", s.media, { s.media = it; s.editJob.clear() }, { s.media = null })
    if (notVideo) Text(
        "This file has no video track. Import a video to use this tool.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
    )

    SectionCard("What do you want to do?") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Remove audio", true to "Replace audio").forEachIndexed { i, (r, label) ->
                SegmentedButton(
                    selected = replace == r, onClick = { replace = r },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)
                ) { Text(label) }
            }
        }
    }
    if (replace) {
        MediaPickerCard("Import the new audio", audio, { audio = it }, { audio = null },
            hint = "Any audio file, or a video to borrow its sound from")
        s.generatedAudio?.let { gen ->
            if (audio != gen) OutlinedButton(onClick = { audio = gen }, modifier = Modifier.fillMaxWidth()) {
                Text("Use the voice-over I generated")
            }
        }
    }

    val ready = s.media != null && !notVideo && (!replace || audio != null) && !s.editJob.busy
    PrimaryButton(if (replace) "Replace audio" else "Remove audio", Icons.Rounded.ContentCut, ready) {
        val v = s.media ?: return@PrimaryButton
        val a = audio
        val doReplace = replace
        s.editJob.start("Rendering video…") {
            val base = baseName(displayName(ctx, v))
            val f = File(ctx.cacheDir, "edit_${System.nanoTime()}.mp4")
            try {
                val cb = { p: Int -> update("Rendering video… $p%", p / 100f) }
                if (doReplace && a != null) MediaProcessor.replaceAudio(ctx, v, a, f, cb)
                else MediaProcessor.removeAudio(ctx, v, f, cb)
                val name = "${base}_voicecut.mp4"
                JobState.Done("Saved $name to Downloads/VoiceCut", saveFile(ctx, f, name, "video/mp4"), "video/mp4")
            } finally {
                f.delete()
            }
        }
    }
    JobStatus(s.editJob)
}
