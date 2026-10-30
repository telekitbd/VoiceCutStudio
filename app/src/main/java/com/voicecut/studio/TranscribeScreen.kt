@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import android.net.Uri
import java.io.File

@Composable
fun TranscribeScreen(s: AppState) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var speakers by rememberSaveable { mutableStateOf(true) }
    var lang by rememberSaveable { mutableStateOf("auto") }
    var count by rememberSaveable { mutableStateOf("0") }
    var optimize by rememberSaveable { mutableStateOf(true) }
    var exported by remember { mutableStateOf<Triple<String, Uri, String>?>(null) }

    ScreenIntro("Transcribe", "Turn speech into text with timestamps and separate speakers.")
    if (s.assemblyKey.isBlank()) {
        NoticeCard("Add your AssemblyAI API key to enable transcription.", "Settings") { s.showSettings = true }
    }
    MediaPickerCard("Import a video or audio file", s.media, { s.media = it; s.transcribeJob.clear() }, { s.media = null })

    SectionCard("Options") {
        SwitchRow("Separate speakers", "Label each person (Speaker 1, 2, 3…). Set the exact number for best results.", speakers) { speakers = it }
        if (speakers) DropdownField(
            "Number of speakers", speakerCounts, count, { count = it }
        )
        DropdownField("Spoken language", spokenLanguages, lang, { lang = it })
        SwitchRow("Extract audio first", "Smaller upload. Falls back to the original file if needed.", optimize) { optimize = it }
    }

    PrimaryButton(
        "Transcribe", Icons.Rounded.Subtitles,
        s.media != null && s.assemblyKey.isNotBlank() && !s.transcribeJob.busy
    ) {
        val uri = s.media ?: return@PrimaryButton
        val opt = optimize; val sp = speakers
        val code = lang.takeIf { it != "auto" }; val expected = count.toIntOrNull()?.takeIf { it > 0 }
        exported = null
        s.transcribeJob.start("Preparing…") {
            var temp: File? = null
            try {
                var source: Uri = uri
                if (opt) {
                    val f = File(ctx.cacheDir, "tr_${System.nanoTime()}.m4a")
                    try {
                        update("Extracting audio…")
                        MediaProcessor.extractAudio(ctx, uri, f) { update("Extracting audio… $it%", it / 100f) }
                        temp = f
                        source = Uri.fromFile(f)
                    } catch (e: CancellationException) {
                        f.delete(); throw e
                    } catch (e: Exception) {
                        f.delete() // unsupported locally: upload the original, the server accepts most formats
                    }
                }
                val segs = Transcriber.transcribe(ctx, s.assemblyKey, source, sp, code, expected) { update(it) }
                s.segments = segs
                s.originalSegments = null
                s.scriptName = baseName(displayName(ctx, uri))
                if (segs.isEmpty()) JobState.Failed("No speech was detected in this file.")
                else JobState.Done("Transcript ready · ${segs.size} segments")
            } finally {
                temp?.delete()
            }
        }
    }
    JobStatus(s.transcribeJob)

    if (s.segments.isNotEmpty()) {
        val segs = s.segments
        val base = s.scriptName.ifBlank { "transcript" }
        SectionCard("Transcript") {
            segs.take(200).forEach {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeakerChip(it.speaker)
                        Text(Exporters.ts(it.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(it.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (segs.size > 200) Text(
                "Showing the first 200 of ${segs.size} segments. Export to get everything.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard("Export") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("TXT", "text/plain") { Exporters.txt(segs) },
                    Triple("SRT", "application/x-subrip") { Exporters.srt(segs) },
                    Triple("JSON", "application/json") { Exporters.json(segs) },
                ).forEach { (label, mime, make) ->
                    FilledTonalButton(onClick = {
                        runCatching {
                            val name = "$base.${label.lowercase()}"
                            exported = Triple("Saved $name to Downloads/VoiceCut", saveText(ctx, make(), name, mime), mime)
                        }.onFailure { exported = null }
                    }) { Text(label) }
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(Exporters.txt(segs))) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
            }
            exported?.let { (msg, uri, mime) -> ResultCard(msg, uri, mime) }
        }
        PrimaryButton("Create audio from this transcript", Icons.Rounded.RecordVoiceOver) { s.tab = 2 }
    }
}

private val spokenLanguages = listOf(
    VoiceOption("auto", "Auto-detect"),
    VoiceOption("bn", "Bengali (বাংলা)"),
    VoiceOption("hi", "Hindi (हिन्दी)"),
    VoiceOption("en", "English"),
)

private val speakerCounts = listOf(VoiceOption("0", "Auto-detect")) +
    (2..10).map { VoiceOption("$it", "$it speakers") }
