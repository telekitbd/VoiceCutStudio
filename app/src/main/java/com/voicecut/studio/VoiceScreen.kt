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
    var engine by rememberSaveable { mutableStateOf("device") } // device | eleven | openai
    var outFmt by rememberSaveable { mutableStateOf("wav") }      // wav | m4a | mp3
    var elevenModel by rememberSaveable { mutableStateOf(ElevenLabs.MODEL_V3) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var gap by rememberSaveable { mutableFloatStateOf(400f) }
    var natural by rememberSaveable { mutableStateOf(true) }
    var target by rememberSaveable { mutableStateOf("bn") }        // voice language: bn | hi | en
    var trMethod by rememberSaveable { mutableStateOf("device") }  // translation: device | openai
    var importError by remember { mutableStateOf<String?>(null) }
    var deviceVoices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var elevenVoices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var elevenError by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val name = displayName(ctx, uri)
                val segs = ScriptParser.parse(name, readTextFile(ctx, uri))
                require(segs.isNotEmpty()) { "No text found in this file" }
                s.segments = segs
                s.originalSegments = null
                s.speakerGender.clear()
                s.scriptName = baseName(name)
                importError = null
            }.onFailure { importError = it.userMessage() }
        }
    }

    LaunchedEffect(engine) {
        if (engine == "device" && deviceVoices == null) {
            deviceVoices = try { DeviceTts.listVoices(ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
    }
    LaunchedEffect(engine, s.elevenKey) {
        if (engine == "eleven" && s.elevenKey.isNotBlank()) {
            elevenError = null
            elevenVoices = try { ElevenLabs.listVoices(s.elevenKey) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                elevenError = e.userMessage(); emptyList()
            }
        }
    }

    LaunchedEffect(target) { if (target == "bn") elevenModel = ElevenLabs.MODEL_V3 } // only v3 speaks Bengali

    val allDevice = deviceVoices.orEmpty()
    val deviceForLang = allDevice.filter { it.lang == target }
    val voices: List<VoiceOption> = when (engine) {
        "openai" -> CloudTts.voices
        "eleven" -> elevenVoices.orEmpty()
        else -> deviceForLang.ifEmpty { allDevice }
    }
    val noDeviceVoice = engine == "device" && deviceVoices != null && deviceForLang.isEmpty()
    val engineKey = engine
    val speakers = s.segments.map { it.speaker }.distinct()

    /** Male / female for a speaker: the manual choice, otherwise what the label says ("Male", "ছেলে", "Woman"…). */
    fun genderOf(speaker: String): String? = s.speakerGender[speaker] ?: Gender.fromLabel(speaker)

    fun voiceFor(speaker: String): String? {
        s.voiceChoices["$engineKey:$speaker"]?.takeIf { id -> voices.any { it.id == id } }?.let { return it }
        if (voices.isEmpty()) return null
        // A man gets a male voice and a woman a female one (when the voice list says which is which).
        val g = genderOf(speaker)
        if (g != null) {
            val same = voices.filter { it.gender == g }
            if (same.isNotEmpty()) {
                val idx = speakers.filter { genderOf(it) == g }.indexOf(speaker).coerceAtLeast(0)
                return same[idx % same.size].id
            }
        }
        val pool = voices
        return pool[speakers.indexOf(speaker).coerceAtLeast(0) % pool.size].id
    }

    /** Device voices often do not say male/female. If the voice does not fit, or all speakers share one voice, shift the pitch. */
    fun pitchFor(speaker: String): Float {
        if (engine != "device") return 1f
        val g = genderOf(speaker) ?: return 1f
        val voiceGender = voices.firstOrNull { it.id == voiceFor(speaker) }?.gender
        val needsShift = if (voiceGender != null) voiceGender != g
        else voices.map { it.id }.distinct().size < 2 && speakers.mapNotNull { genderOf(it) }.distinct().size > 1
        return if (!needsShift) 1f else if (g == Gender.MALE) 0.78f else 1.22f
    }

    ScreenIntro("Create audio", "Turn a transcript, or any text, into a Bengali, Hindi or English voice-over.")

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
                            listOf("Male", "Female").forEach { who ->
                                if (Gender.fromLabel(seg.speaker) != Gender.fromLabel(who)) {
                                    TextButton(onClick = { s.segments = s.segments.toMutableList().also { it[i] = seg.copy(speaker = who) } }) {
                                        Text("→ $who")
                                    }
                                }
                            }
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

    // 2. Language
    SectionCard("2 · Voice language") {
        Text(
            "Pick the language you want to hear. A script in another language is translated automatically before the voice is made.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Languages.targets.forEach { t ->
                FilterChip(selected = target == t.id, onClick = { target = t.id }, label = { Text(t.label) })
            }
        }
        if (s.segments.isNotEmpty()) {
            val detected = Languages.guess(s.segments)
            Text(
                if (detected == target) "Your script is already in ${Languages.name(target)}."
                else "Your script looks like ${Languages.name(detected)}. It will be translated to ${Languages.name(target)}.",
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text("Translation", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("device" to "On-device (free)", "openai" to "OpenAI (better)").forEachIndexed { i, (k, label) ->
                SegmentedButton(selected = trMethod == k, onClick = { trMethod = k }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        if (trMethod == "openai" && s.openAiKey.isBlank()) {
            NoticeCard("OpenAI translation needs an API key.", "Settings") { s.showSettings = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = s.segments.isNotEmpty() && !s.translateJob.busy && (trMethod == "device" || s.openAiKey.isNotBlank()),
                onClick = {
                    val src = s.segments; val tgt = target; val method = trMethod; val key = s.openAiKey
                    s.translateJob.start("Translating…") {
                        val result = ScriptTranslator.translate(method, key, src, tgt) { update(it) }
                        if (result === src) JobState.Done("The script is already in ${Languages.name(tgt)}.")
                        else {
                            if (s.originalSegments == null) s.originalSegments = src
                            s.segments = result
                            JobState.Done("Translated to ${Languages.name(tgt)}. Review the lines above and edit if needed.")
                        }
                    }
                }
            ) { Text("Translate now") }
            if (s.originalSegments != null) OutlinedButton(onClick = {
                s.segments = s.originalSegments ?: s.segments; s.originalSegments = null; s.translateJob.clear()
            }) { Text("Restore original") }
        }
        JobStatus(s.translateJob)
    }

    // 3. Voice
    SectionCard("3 · Voice") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("device" to "Device", "eleven" to "ElevenLabs", "openai" to "OpenAI").forEachIndexed { i, (k, label) ->
                SegmentedButton(selected = engine == k, onClick = { engine = k }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
            }
        }
        when (engine) {
            "device" -> Text(
                if (deviceVoices == null) "Loading voices…"
                else if (voices.isEmpty()) "No voices found. Install a text-to-speech engine in Android settings."
                else if (noDeviceVoice) "No ${Languages.name(target)} voice is installed on this phone. Open Android Settings → Text-to-speech → Google → install ${Languages.name(target)} voice data, or use ElevenLabs."
                else "Free and offline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            "eleven" -> {
                if (s.elevenKey.isBlank()) NoticeCard("ElevenLabs voices need an API key.", "Settings") { s.showSettings = true }
                else {
                    DropdownField("Model", ElevenLabs.models, elevenModel, { elevenModel = it })
                    Text(
                        when {
                            elevenError != null -> elevenError!!
                            elevenVoices == null -> "Loading your voices…"
                            voices.isEmpty() -> "No voices found in your ElevenLabs account."
                            else -> "Use v3 for Bengali. Voices come from your ElevenLabs account."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (elevenError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                if (s.openAiKey.isBlank()) NoticeCard("OpenAI voices need an API key.", "Settings") { s.showSettings = true }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to "Natural", false to "Standard").forEachIndexed { i, (n, label) ->
                        SegmentedButton(selected = natural == n, onClick = { natural = n }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
        }
        if (voices.isNotEmpty()) {
            Text(
                "Each speaker is matched to a male or female voice automatically. Tap Male / Female to change who is who.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            speakers.forEach { sp ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakerChip(sp)
                    Spacer(Modifier.weight(1f))
                    listOf(Gender.MALE to "Male", Gender.FEMALE to "Female").forEach { (g, label) ->
                        FilterChip(
                            selected = genderOf(sp) == g,
                            onClick = {
                                s.speakerGender[sp] = g
                                s.voiceChoices.remove("$engineKey:$sp")
                            },
                            label = { Text(label) }
                        )
                    }
                }
                DropdownField("Voice for $sp", voices, voiceFor(sp), { s.voiceChoices["$engineKey:$sp"] = it })
            }
        }
        if (engine == "device" || (engine == "openai" && !natural)) {
            Text("Speed ${String.format(Locale.US, "%.2f", speed)}×", style = MaterialTheme.typography.labelLarge)
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..1.5f)
        }
        Text("Pause between lines ${gap.toInt()} ms", style = MaterialTheme.typography.labelLarge)
        Slider(value = gap, onValueChange = { gap = it }, valueRange = 0f..2000f)
    }

    // 3. Output
    SectionCard("4 · Output") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("wav" to "WAV", "mp3" to "MP3", "m4a" to "M4A").forEachIndexed { i, (k, label) ->
                SegmentedButton(selected = outFmt == k, onClick = { outFmt = k }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
            }
        }
    }

    val keyOk = when (engine) { "eleven" -> s.elevenKey.isNotBlank(); "openai" -> s.openAiKey.isNotBlank(); else -> true }
    val canRun = s.segments.any { it.text.isNotBlank() } && voices.isNotEmpty() && !s.voiceJob.busy && keyOk
    PrimaryButton("Generate audio", Icons.Rounded.RecordVoiceOver, canRun) {
        val tgt = target; val method = trMethod
        val chosen = speakers.associateWith { voiceFor(it) }
        val pitches = speakers.associateWith { pitchFor(it) }
        val eng = engine; val sp = speed
        val openAiKey = s.openAiKey; val elKey = s.elevenKey
        val openAiModel = if (natural) CloudTts.MODEL_NATURAL else CloudTts.MODEL_STANDARD
        val elModel = elevenModel
        val pause = gap.toInt(); val fmt = outFmt
        s.voiceJob.start("Preparing voices…") {
            var segs = s.segments.filter { it.text.isNotBlank() }
            if (Languages.guess(segs) != tgt) {
                val translated = ScriptTranslator.translate(method, openAiKey, segs, tgt) { update(it) }
                if (s.originalSegments == null) s.originalSegments = s.segments
                s.segments = translated
                segs = translated
            }
            val stamp = System.nanoTime()
            val wav = File(ctx.cacheDir, "voice_$stamp.wav")
            val writer = PcmWriter(wav)
            var device: DeviceTts? = null
            try {
                if (eng == "device") device = DeviceTts.create(ctx)
                segs.forEachIndexed { i, seg ->
                    update("Speaking line ${i + 1} of ${segs.size}…", i / segs.size.toFloat())
                    val v = chosen[seg.speaker]
                    val pcm = when (eng) {
                        "openai" -> CloudTts.synth(openAiKey, openAiModel, v ?: "alloy", seg.text, sp)
                        "eleven" -> ElevenLabs.synth(elKey, elModel, v ?: error("Pick an ElevenLabs voice"), seg.text)
                        else -> device!!.synth(ctx, seg.text, v, sp, pitches[seg.speaker] ?: 1f)
                    }
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
            var encoded: File? = null
            try {
                val (src, name, mime) = when (fmt) {
                    "m4a" -> {
                        update("Encoding M4A…")
                        val out = File(ctx.cacheDir, "voice_$stamp.m4a")
                        encoded = out
                        MediaProcessor.extractAudio(ctx, Uri.fromFile(wav), out)
                        Triple(out, "$base.m4a", "audio/mp4")
                    }
                    "mp3" -> {
                        update("Encoding MP3…")
                        val out = File(ctx.cacheDir, "voice_$stamp.mp3")
                        encoded = out
                        Mp3.encodeWav(wav, out, 128) { update("Encoding MP3… ${(it * 100).toInt()}%", it) }
                        Triple(out, "$base.mp3", "audio/mpeg")
                    }
                    else -> Triple(wav, "$base.wav", "audio/wav")
                }
                val saved = saveFile(ctx, src, name, mime)
                s.generatedAudio = saved
                JobState.Done("Saved $name to Downloads/VoiceCut", saved, mime)
            } finally {
                wav.delete(); encoded?.delete()
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
