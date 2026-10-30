@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import android.net.Uri
import java.io.File
import java.util.Locale

/** Cut a range out of a long audio/video, or split it into equal parts. Uses the format chosen on the Extract tab. */
@Composable
fun CutSection(s: AppState, format: String) {
    val ctx = LocalContext.current
    val info by rememberMediaInfo(s.media)
    val duration = info?.durationMs ?: 0L

    var mode by rememberSaveable { mutableStateOf("trim") } // trim | split
    var startMs by rememberSaveable { mutableLongStateOf(0L) }
    var endMs by rememberSaveable { mutableLongStateOf(0L) }
    var startText by rememberSaveable { mutableStateOf("0:00") }
    var endText by rememberSaveable { mutableStateOf("0:00") }
    var partMin by rememberSaveable { mutableIntStateOf(10) }
    var forMedia by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(s.media, duration) {
        val id = s.media?.toString() ?: ""
        if (duration > 0 && forMedia != id) {
            forMedia = id
            startMs = 0; endMs = duration
            startText = formatDuration(0); endText = formatDuration(duration)
        }
    }

    val parts = if (duration > 0) ((duration + partMin * 60_000L - 1) / (partMin * 60_000L)).toInt() else 0

    SectionCard("Cut or split audio") {
        Text(
            "Make a long recording shorter. Works on audio and video files, and uses the output format chosen above.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("trim" to "Cut a section", "split" to "Split into parts").forEachIndexed { i, (k, label) ->
                SegmentedButton(selected = mode == k, onClick = { mode = k }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }

        if (mode == "trim") {
            if (duration > 0) {
                RangeSlider(
                    value = (startMs / 1000f)..(endMs / 1000f),
                    onValueChange = { r ->
                        startMs = (r.start * 1000).toLong(); endMs = (r.endInclusive * 1000).toLong()
                        startText = formatDuration(startMs); endText = formatDuration(endMs)
                    },
                    valueRange = 0f..(duration / 1000f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = startText, singleLine = true, label = { Text("Start (m:ss)") },
                    onValueChange = { startText = it; AudioCutter.parseTime(it)?.let { ms -> startMs = ms } },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endText, singleLine = true, label = { Text("End (m:ss)") },
                    onValueChange = { endText = it; AudioCutter.parseTime(it)?.let { ms -> endMs = ms } },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                )
            }
            val len = (endMs - startMs).coerceAtLeast(0)
            Text(
                "Selected length: ${formatDuration(len)}" + if (duration > 0) " of ${formatDuration(duration)}" else "",
                style = MaterialTheme.typography.labelLarge
            )
        } else {
            Text("Length of each part", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 15, 30, 60).forEach { m ->
                    FilterChip(selected = partMin == m, onClick = { partMin = m }, label = { Text("$m min") })
                }
            }
            Text(
                if (duration > 0) "$parts part${if (parts == 1) "" else "s"} will be created."
                else "Couldn't read this file's length, so it can't be split.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val valid = s.media != null && duration > 0 && !s.cutJob.busy &&
            (mode == "split" || (endMs > startMs && endMs <= duration + 500))
        PrimaryButton(if (mode == "trim") "Cut audio" else "Split audio", Icons.Rounded.ContentCut, valid) {
            val uri = s.media ?: return@PrimaryButton
            val fmt = format
            val ranges: List<Pair<Long, Long>> =
                if (mode == "trim") listOf(startMs to minOf(endMs, duration))
                else (0 until parts).map { i -> i * partMin * 60_000L to minOf((i + 1) * partMin * 60_000L, duration) }
            val single = mode == "trim"
            s.cutJob.start("Preparing…") {
                val base = baseName(displayName(ctx, uri))
                val stamp = System.nanoTime()
                var fullWav: File? = null

                suspend fun wavOfWholeFile(): File {
                    fullWav?.let { return it }
                    update("Decoding audio…")
                    val f = File(ctx.cacheDir, "cut_${stamp}_full.wav")
                    WavExtractor.extract(ctx, uri, f)
                    fullWav = f
                    return f
                }

                val saved = mutableListOf<Uri>()
                var cur = fmt
                var lastName = ""
                var lastMime = ""
                try {
                    for ((i, r) in ranges.withIndex()) {
                        update("Cutting ${if (single) "audio" else "part ${i + 1} of ${ranges.size}"}…", i / ranges.size.toFloat())
                        val stem = if (single) "${base}_cut_${fileTag(r.first)}-${fileTag(r.second)}"
                        else "${base}_part" + String.format(Locale.US, "%02d", i + 1)
                        var out: Uri? = null

                        if (cur == "m4a") {
                            val f = File(ctx.cacheDir, "cut_${stamp}_$i.m4a")
                            try {
                                MediaProcessor.cutAudio(ctx, uri, f, r.first, r.second)
                                out = saveFile(ctx, f, "$stem.m4a", "audio/mp4")
                                lastName = "$stem.m4a"; lastMime = "audio/mp4"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                cur = "wav" // this file can't be clipped directly: decode it once and slice
                            } finally {
                                f.delete()
                            }
                        }
                        if (out == null) {
                            val whole = wavOfWholeFile()
                            val part = File(ctx.cacheDir, "cut_${stamp}_$i.wav")
                            try {
                                AudioCutter.slice(whole, r.first, r.second, part)
                                if (cur == "mp3") {
                                    val m = File(ctx.cacheDir, "cut_${stamp}_$i.mp3")
                                    try {
                                        Mp3.encodeWav(part, m, 192)
                                        out = saveFile(ctx, m, "$stem.mp3", "audio/mpeg")
                                        lastName = "$stem.mp3"; lastMime = "audio/mpeg"
                                    } finally {
                                        m.delete()
                                    }
                                } else {
                                    out = saveFile(ctx, part, "$stem.wav", "audio/wav")
                                    lastName = "$stem.wav"; lastMime = "audio/wav"
                                }
                            } finally {
                                part.delete()
                            }
                        }
                        saved += out!!
                    }
                } finally {
                    fullWav?.delete()
                }
                if (single) JobState.Done("Saved $lastName to Downloads/VoiceCut", saved.first(), lastMime)
                else JobState.Done("Saved ${saved.size} parts to Downloads/VoiceCut", null, null)
            }
        }
        JobStatus(s.cutJob)
    }
}

private fun fileTag(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d-%02d", s / 60, s % 60)
}
