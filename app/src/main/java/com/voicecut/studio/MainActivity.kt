package com.voicecut.studio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoiceCutTheme { App() } }
    }
}

private fun CoroutineScope.job(
    setBusy: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    block: suspend () -> Unit
) = launch {
    setBusy(true)
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        setStatus("ত্রুটি: ${e.message}")
    } finally {
        setBusy(false)
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Extract", "Transcript", "Audio")
    val icons = listOf("🎵", "📝", "🎬")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(icons[i]) }, label = { Text(t) }
                    )
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("VoiceCut Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            when (tab) {
                0 -> ExtractScreen()
                1 -> TranscriptScreen()
                else -> AudioScreen()
            }
        }
    }
}

@Composable
fun FilePick(label: String, mimes: Array<String>, uri: Uri?, onPick: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPick) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    uri?.let { displayName(ctx, it) } ?: "কোনো ফাইল নির্বাচন করা হয়নি",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = { launcher.launch(mimes) }) { Text("Choose") }
        }
    }
}

@Composable
fun Status(busy: Boolean, text: String) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun ExtractScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var wav by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    FilePick("Video", arrayOf("video/*"), video) { video = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !wav, onClick = { wav = false }, label = { Text("M4A (AAC)") })
        FilterChip(selected = wav, onClick = { wav = true }, label = { Text("WAV") })
    }
    Button(
        enabled = video != null && !busy,
        onClick = {
            scope.job({ busy = it }, { status = it }) {
                val v = video!!
                val base = displayName(ctx, v).substringBeforeLast('.')
                status = "অডিও বের হচ্ছে…"
                if (wav) {
                    val f = File(ctx.cacheDir, "out.wav")
                    WavExtractor.extract(ctx, v, f)
                    saveFile(ctx, f, "$base.wav", "audio/wav")
                } else {
                    val f = File(ctx.cacheDir, "out.m4a")
                    MediaProcessor.extractAudio(ctx, v, f)
                    saveFile(ctx, f, "$base.m4a", "audio/mp4")
                }
                status = "সংরক্ষিত হয়েছে: Downloads/VoiceCut"
            }
        }
    ) { Text("Extract Audio") }
    Status(busy, status)
}

@Composable
fun TranscriptScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("voicecut", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("aai_key", "") ?: "") }
    var media by remember { mutableStateOf<Uri?>(null) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it; prefs.edit().putString("aai_key", it).apply() },
        label = { Text("AssemblyAI API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    FilePick("Video / Audio", arrayOf("video/*", "audio/*"), media) { media = it }
    Button(
        enabled = media != null && !busy,
        onClick = {
            scope.job({ busy = it }, { status = it }) {
                status = "অডিও আলাদা হচ্ছে…"
                val f = File(ctx.cacheDir, "transcribe.m4a")
                MediaProcessor.extractAudio(ctx, media!!, f)
                segments = Transcriber.transcribe(key.trim(), f) { status = it }
                status = "সম্পন্ন: ${segments.size}টি অংশ"
            }
        }
    ) { Text("Transcribe + Speaker Separation") }
    Status(busy, status)

    segments.forEach {
        Column {
            Text("${it.speaker} · ${Exporters.ts(it.startMs)}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            Text(it.text)
        }
    }
    if (segments.isNotEmpty()) {
        val base = media?.let { displayName(ctx, it).substringBeforeLast('.') } ?: "transcript"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                saveText(ctx, Exporters.txt(segments), "$base.txt", "text/plain"); status = "TXT সংরক্ষিত: Downloads/VoiceCut"
            }) { Text("TXT") }
            OutlinedButton(onClick = {
                saveText(ctx, Exporters.srt(segments), "$base.srt", "application/x-subrip"); status = "SRT সংরক্ষিত: Downloads/VoiceCut"
            }) { Text("SRT") }
            OutlinedButton(onClick = {
                saveText(ctx, Exporters.json(segments), "$base.json", "application/json"); status = "JSON সংরক্ষিত: Downloads/VoiceCut"
            }) { Text("JSON") }
        }
    }
}

@Composable
fun AudioScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var audio by remember { mutableStateOf<Uri?>(null) }
    var replace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    FilePick("Video", arrayOf("video/*"), video) { video = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !replace, onClick = { replace = false }, label = { Text("Remove audio") })
        FilterChip(selected = replace, onClick = { replace = true }, label = { Text("Replace audio") })
    }
    if (replace) FilePick("New audio", arrayOf("audio/*"), audio) { audio = it }
    Button(
        enabled = video != null && (!replace || audio != null) && !busy,
        onClick = {
            scope.job({ busy = it }, { status = it }) {
                val v = video!!
                val base = displayName(ctx, v).substringBeforeLast('.')
                val f = File(ctx.cacheDir, "out.mp4")
                status = "ভিডিও তৈরি হচ্ছে…"
                if (replace) MediaProcessor.replaceAudio(ctx, v, audio!!, f)
                else MediaProcessor.removeAudio(ctx, v, f)
                saveFile(ctx, f, "${base}_voicecut.mp4", "video/mp4")
                status = "সংরক্ষিত হয়েছে: Downloads/VoiceCut"
            }
        }
    ) { Text(if (replace) "Replace Audio" else "Remove Audio") }
    Status(busy, status)
}
