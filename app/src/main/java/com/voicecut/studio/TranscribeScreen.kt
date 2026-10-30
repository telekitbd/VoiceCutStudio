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
    var detectGender by rememberSaveable { mutableStateOf(true) }
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
        if (speakers) SwitchRow(
            "Detect male / female", "Names each voice Male or Female by listening to its pitch instead of Speaker 1, 2. Free, done on your phone.",
            detectGender
        ) { detectGender = it }
        DropdownField("Spoken language", spokenLanguages, lang, { lang = it })
        SwitchRow("Extract audio first", "Smaller upload. Falls back to the original file if needed.", optimize) { optimize = it }
    }

    PrimaryButton(
        "Transcribe", Icons.Rounded.Subtitles,
        s.media != null && s.assemblyKey.isNotBlank() && !s.transcribeJob.busy
    ) {
        val uri = s.media ?: return@PrimaryButton
        val opt = optimize; val sp = speakers; val detect = detectGender
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
                var segs = Transcriber.transcribe(ctx, s.assemblyKey, source, sp, code, expected) { update(it) }
                if (sp && detect && segs.isNotEmpty()) segs = GenderDetector.detect(ctx, source, segs) { update(it) }
                s.clip.stop()
                s.transcript = segs
                s.transcriptMedia = uri
                s.transcriptName = baseName(displayName(ctx, uri))
                if (segs.isEmpty()) JobState.Failed("No speech was detected in this file.")
                else JobState.Done("Transcript ready · ${segs.size} segments")
            } finally {
                temp?.delete()
            }
        }
    }
    JobStatus(s.transcribeJob)

    if (s.transcript.isNotEmpty()) {
        val segs = s.transcript
        val base = s.transcriptName.ifBlank { "transcript" }
        val names = segs.map { it.speaker }.distinct()
        if (names.any { Gender.fromLabel(it) != null }) SectionCard("Who is who") {
            Text(
                "Voices were told apart by pitch. If one is wrong, switch it here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            names.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakerChip(n)
                    Spacer(Modifier.weight(1f))
                    // Listen to this person's longest turn to check whether it is a man or a woman.
                    val sample = segs.filter { it.speaker == n && it.endMs > it.startMs }.maxByOrNull { it.endMs - it.startMs }
                    val media = s.transcriptMedia
                    if (sample != null && media != null) {
                        val key = "spk:$n"
                        val playing = s.clip.playingKey == key
                        FilledTonalButton(onClick = { s.clip.toggle(ctx, media, sample.startMs, sample.endMs, key) }) {
                            Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (playing) "Stop" else "Play")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = { s.transcript = Gender.flip(s.transcript, n) }) {
                        Text(if (Gender.fromLabel(n) == Gender.FEMALE) "Switch to Male" else "Switch to Female")
                    }
                }
            }
        }
        SectionCard("Transcript") {
            segs.take(200).forEach {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeakerChip(it.speaker)
                        Text(Exporters.ts(it.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val media = s.transcriptMedia
                        if (media != null && it.endMs > it.startMs) {
                            val key = "seg:${it.startMs}:${it.speaker}"
                            val playing = s.clip.playingKey == key
                            IconButton(onClick = { s.clip.toggle(ctx, media, it.startMs, it.endMs, key) }, modifier = Modifier.size(28.dp)) {
                                Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (playing) "Stop" else "Play", Modifier.size(20.dp))
                            }
                        }
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
        PrimaryButton("Create audio from this transcript", Icons.Rounded.RecordVoiceOver) {
            s.segments = s.transcript
            s.originalSegments = null
            s.speakerGender.clear()
            s.scriptName = s.transcriptName
            s.tab = 2
        }
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
