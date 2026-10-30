package com.voicecut.studio

import org.json.JSONArray
import org.json.JSONObject

/** Turns an imported transcript file (TXT / SRT / JSON / plain text) into segments. */
object ScriptParser {
    private val speakerRe = Regex("^(Speaker)\\s*(\\d+)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val txtTimeRe = Regex("^\\[(\\d{1,3}):(\\d{2})(?::(\\d{2}))?]\\s*(.*)$")
    private val srtTimeRe = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})")

    fun parse(fileName: String, raw: String): List<Segment> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".json") || text.startsWith("{") || text.startsWith("[{") ->
                runCatching { fromJson(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: fromTxt(text)
            lower.endsWith(".srt") || text.contains("-->") -> fromSrt(text).ifEmpty { fromTxt(text) }
            else -> fromTxt(text)
        }
    }

    private fun splitSpeaker(line: String): Pair<String, String> {
        val m = speakerRe.matchEntire(line.trim())
        return if (m != null) "Speaker ${m.groupValues[2]}" to m.groupValues[3].trim() else "Speaker 1" to line.trim()
    }

    private fun fromTxt(text: String): List<Segment> = text.lines().mapNotNull { l ->
        var line = l.trim()
        if (line.isEmpty()) return@mapNotNull null
        var start = 0L
        txtTimeRe.matchEntire(line)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong()
            val c = m.groupValues[3].toLongOrNull()
            start = (if (c != null) a * 3600 + b * 60 + c else a * 60 + b) * 1000
            line = m.groupValues[4]
        }
        val (spk, body) = splitSpeaker(line)
        if (body.isBlank()) null else Segment(spk, start, start, body)
    }

    private fun ms(h: String, m: String, s: String, f: String): Long =
        (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

    private fun fromSrt(text: String): List<Segment> =
        text.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { srtTimeRe.containsMatchIn(it) }
            if (ti < 0) return@mapNotNull null
            val g = srtTimeRe.find(lines[ti])!!.groupValues
            val body = lines.drop(ti + 1).joinToString(" ").trim()
            if (body.isEmpty()) return@mapNotNull null
            val (spk, t) = splitSpeaker(body)
            Segment(spk, ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), t)
        }

    private fun fromJson(text: String): List<Segment> {
        val arr: JSONArray = if (text.startsWith("[")) JSONArray(text) else {
            val o = JSONObject(text)
            o.optJSONArray("segments") ?: o.optJSONArray("utterances") ?: return emptyList()
        }
        val names = LinkedHashMap<String, String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val body = o.optString("text").trim()
            if (body.isEmpty()) return@mapNotNull null
            val label = o.optString("speaker", "Speaker 1")
            val spk = if (label.startsWith("Speaker", true)) label else names.getOrPut(label) { "Speaker ${names.size + 1}" }
            Segment(spk, o.optLong("start_ms", o.optLong("start", 0)), o.optLong("end_ms", o.optLong("end", 0)), body)
        }
    }
}
