@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale

@Composable
fun VoiceScreen(s: AppState) {
    val ctx = LocalContext.current
    var cloud by rememberSaveable { mutableStateOf(false) }
    var m4a by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var gap by rememberSaveable { mutableFloatStateOf(400f) }
    var natural by rememberSaveable { mutableStateOf(true) }
    var importError by remember { mutableStateOf<String?>(null) }
    var deviceVoices by remember { mutableStateOf<List<VoiceOption>?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val name = displayName(ctx, uri)
                val segs = ScriptParser.parse(name, readTextFile(ctx, uri))
                require(segs.isNotEmpty()) { "No text found in this file" }
                s.segments = segs
                s.scriptName = baseName(name)
                importError = null
            }.onFailure { importError = it.userMessage() }
        }
    }

    LaunchedEffect(cloud) {
        if (!cloud && deviceVoices == null) {
            deviceVoices = try { DeviceTts.listVoices(ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }

    val voices: List<VoiceOption> = if (cloud) CloudTts.voices else deviceVoices.orEmpty()
    val engineKey = if (cloud) "cloud" else "device"
    val speakers = s.segments.map { it.speaker }.distinct()

    fun voiceFor(speaker: String): String? {
        s.voiceChoices["$engineKey:$speaker"]?.takeIf { id -> voices.any { it.id == id } }?.let { return it }
        if (voices.isEmpty()) return null
        val pool = if (cloud) voices else voices.filter { it.label.startsWith(Locale.getDefault().displayLanguage, true) }
            .ifEmpty { voices }
        return pool[speakers.indexOf(speaker).coerceAtLeast(0) % pool.size].id
    }

    ScreenIntro("Create audio", "Turn a transcript, or any text, into a voice-over audio file.")

    // 1. Script
    SectionCard("1 · Script") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import transcript")
            }
            OutlinedButton(onClick = {
                s.segments = s.segments + Segment(speakers.lastOrNull() ?: "Speaker 1", 0, 0, "")
                if (s.scriptName.isBlank()) s.scriptName = "voiceover"
            }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add line")
            }
        }
        Text(
            "TXT, SRT or JSON files exported from the Transcribe tab work, and so does any plain text file. " +
                "You can also use the transcript you just made.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (s.segments.isEmpty()) {
            Text("No script yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "${s.segments.size} lines" + if (s.scriptName.isNotBlank()) " · ${s.scriptName}" else "",
                style = MaterialTheme.typography.labelLarge
            )
            s.segments.forEachIndexed { i, seg ->
                key(i) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpeakerChip(seg.speaker)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { s.segments = s.segments.toMutableList().also { it.removeAt(i) } }) {
                                Icon(Icons.Rounded.Delete, "Delete line", Modifier.size(20.dp))
                            }
                        }
                        OutlinedTextField(
                            value = seg.text,
                            onValueChange = { t -> s.segments = s.segments.toMutableList().also { it[i] = seg.copy(text = t) } },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // 2. Voice
    SectionCard("2 · Voice") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "On-device (free)", true to "Cloud (OpenAI)").forEachIndexed { i, (c, label) ->
                SegmentedButton(selected = cloud == c, onClick = { cloud = c }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        if (cloud && s.openAiKey.isBlank()) {
            NoticeCard("Cloud voices need an OpenAI API key.", "Settings") { s.showSettings = true }
        }
        if (!cloud) {
            Text(
                if (deviceVoices == null) "Loading voices…"
                else if (voices.isEmpty()) "No voices found. Install a text-to-speech engine in Android settings."
                else "Pick voices that match the language of your text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "Natural", false to "Standard").forEachIndexed { i, (n, label) ->
                    SegmentedButton(selected = natural == n, onClick = { natural = n }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
        if (voices.isNotEmpty()) speakers.forEach { sp ->
            DropdownField("Voice for $sp", voices, voiceFor(sp), { s.voiceChoices["$engineKey:$sp"] = it })
        }
        if (!cloud || !natural) {
            Text("Speed ${String.format(Locale.US, "%.2f", speed)}×", style = MaterialTheme.typography.labelLarge)
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..1.5f)
        }
        Text("Pause between lines ${gap.toInt()} ms", style = MaterialTheme.typography.labelLarge)
        Slider(value = gap, onValueChange = { gap = it }, valueRange = 0f..2000f)
    }

    // 3. Output
    SectionCard("3 · Output") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "WAV", true to "M4A").forEachIndexed { i, (m, label) ->
                SegmentedButton(selected = m4a == m, onClick = { m4a = m }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
    }

    val canRun = s.segments.any { it.text.isNotBlank() } && voices.isNotEmpty() && !s.voiceJob.busy &&
        (!cloud || s.openAiKey.isNotBlank())
    PrimaryButton("Generate audio", Icons.Rounded.RecordVoiceOver, canRun) {
        val segs = s.segments.filter { it.text.isNotBlank() }
        val chosen = speakers.associateWith { voiceFor(it) }
        val isCloud = cloud; val key = s.openAiKey; val sp = speed
        val model = if (natural) CloudTts.MODEL_NATURAL else CloudTts.MODEL_STANDARD
        val pause = gap.toInt(); val asM4a = m4a
        s.voiceJob.start("Preparing voices…") {
            val stamp = System.nanoTime()
            val wav = File(ctx.cacheDir, "voice_$stamp.wav")
            val writer = PcmWriter(wav)
            var device: DeviceTts? = null
            try {
                if (!isCloud) device = DeviceTts.create(ctx)
                segs.forEachIndexed { i, seg ->
                    update("Speaking line ${i + 1} of ${segs.size}…", i / segs.size.toFloat())
                    val v = chosen[seg.speaker]
                    val pcm = if (isCloud) CloudTts.synth(key, model, v ?: "alloy", seg.text, sp)
                    else device!!.synth(ctx, seg.text, v, sp)
                    writer.append(pcm)
                    if (i < segs.size - 1) writer.silence(pause)
                }
                writer.finish()
            } catch (e: Throwable) {
                writer.abort(); throw e
            } finally {
                device?.shutdown()
            }

            val base = (s.scriptName.ifBlank { "voiceover" }) + "_voice"
            try {
                val (src, name, mime) = if (asM4a) {
                    update("Encoding M4A…")
                    val out = File(ctx.cacheDir, "voice_$stamp.m4a")
                    MediaProcessor.extractAudio(ctx, Uri.fromFile(wav), out)
                    Triple(out, "$base.m4a", "audio/mp4")
                } else Triple(wav, "$base.wav", "audio/wav")
                val saved = saveFile(ctx, src, name, mime)
                src.delete()
                s.generatedAudio = saved
                JobState.Done("Saved $name to Downloads/VoiceCut", saved, mime)
            } finally {
                wav.delete()
            }
        }
    }
    JobStatus(s.voiceJob)
    if (s.voiceJob.state is JobState.Done) {
        OutlinedButton(onClick = { s.tab = 3 }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.ContentCut, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Put this voice-over on a video")
        }
    }
}
