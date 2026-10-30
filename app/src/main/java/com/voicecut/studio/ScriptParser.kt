package com.voicecut.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns an imported script (TXT / SRT / JSON / plain text) into segments.
 *
 * Only the spoken words are kept. Speaker labels such as "ছেলে:", "Male:", "Speaker 2:" or "Rahim:" are
 * understood (and not read aloud), and stage directions like "(laughs)", "[pause]" or "*sigh*" are dropped.
 */
object ScriptParser {
    private val txtTimeRe = Regex("^\\[(\\d{1,3}):(\\d{2})(?::(\\d{2}))?]\\s*(.*)$")
    private val srtTimeRe = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})")

    /** "Name: text" or "Name：text" */
    private val colonRe = Regex("^([^:：\\[\\](){}（）]{1,30}?)\\s*[:：]\\s*(.*)$")

    /** "[Male] text", "(Female) text" or "[Male]: text" */
    private val bracketRe = Regex("^[\\[(（]\\s*([^\\[\\]()（）]{1,30}?)\\s*[\\])）]\\s*[:：-]?\\s*(.*)$")

    /** Stage directions: (laughs) [pause] （হাসি） *sigh* */
    private val directionRe = Regex("\\s*[\\[(（][^\\[\\]()（）]{0,60}[\\])）]\\s*|\\s*\\*[^*\\n]{1,60}\\*\\s*")
    private val bulletRe = Regex("^[-–—•*>]+\\s*")

    private class Raw(val start: Long, val end: Long, val text: String)

    fun parse(fileName: String, raw: String): List<Segment> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".json") || text.startsWith("{") || text.startsWith("[{") ->
                runCatching { fromJson(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: labelize(rawLines(text))
            lower.endsWith(".srt") || text.contains("-->") -> labelize(rawSrt(text)).ifEmpty { labelize(rawLines(text)) }
            else -> labelize(rawLines(text))
        }
    }

    private fun rawLines(text: String): List<Raw> = text.lines().mapNotNull { l ->
        var line = l.trim()
        if (line.isEmpty()) return@mapNotNull null
        var start = 0L
        txtTimeRe.matchEntire(line)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong()
            val c = m.groupValues[3].toLongOrNull()
            start = (if (c != null) a * 3600 + b * 60 + c else a * 60 + b) * 1000
            line = m.groupValues[4]
        }
        Raw(start, start, line)
    }

    private fun ms(h: String, m: String, s: String, f: String): Long =
        (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

    private fun rawSrt(text: String): List<Raw> =
        text.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { srtTimeRe.containsMatchIn(it) }
            if (ti < 0) return@mapNotNull null
            val g = srtTimeRe.find(lines[ti])!!.groupValues
            val body = lines.drop(ti + 1).joinToString(" ").trim()
            if (body.isEmpty()) null else Raw(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), body)
        }

    private class Parsed(val label: String?, val body: String, val raw: Raw)

    /** Splits each line into speaker label + spoken text, then drops everything that is not speech. */
    private fun labelize(items: List<Raw>): List<Segment> {
        val parsed = items.map { item ->
            val line = item.text.replace(bulletRe, "").trim()
            // "[Male] text": only a known gender / speaker word counts as a label inside brackets.
            val br = bracketRe.matchEntire(line)
            if (br != null && Gender.isKnownLabel(br.groupValues[1])) {
                return@map Parsed(br.groupValues[1].trim(), br.groupValues[2], item)
            }
            val noDir = line.replace(directionRe, " ").trim()
            val m = colonRe.matchEntire(noDir)
            if (m != null && !looksLikeTime(m.groupValues[1])) Parsed(m.groupValues[1].trim(), m.groupValues[2], item)
            else Parsed(null, noDir, item)
        }

        // Which labels are real? Known words always; names only if they clearly work as a speaker list.
        val labeled = parsed.filter { it.label != null }
        val counts = labeled.groupingBy { it.label!!.lowercase() }.eachCount()
        val mostlyLabeled = labeled.size >= 2 && labeled.size >= 0.6 * parsed.size
        fun accepted(label: String): Boolean {
            if (Gender.isKnownLabel(label)) return true
            if ((counts[label.lowercase()] ?: 0) >= 2) return true
            val words = label.trim().split(Regex("\\s+")).size
            return mostlyLabeled && words <= 3 && label.none { it in ".?!।,;" }
        }

        val out = ArrayList<Segment>()
        var last: String? = null
        for (p in parsed) {
            val label = p.label?.takeIf { accepted(it) }
            val speaker: String
            var body: String
            if (label != null) {
                speaker = Gender.canonical(label)
                last = speaker
                body = p.body
            } else {
                speaker = last ?: "Speaker 1"
                // The colon was not a label after all: keep the original wording.
                body = if (p.label != null) p.raw.text.replace(bulletRe, "").replace(directionRe, " ") else p.body
            }
            body = tidy(body)
            if (body.isNotEmpty()) out += Segment(speaker, p.raw.start, p.raw.end, body)
        }
        return out
    }

    private fun looksLikeTime(s: String) = s.trim().all { it.isDigit() }

    /** Removes directions and wrapping quotes, leaving just what is said. */
    private fun tidy(s: String): String {
        var t = s.replace(directionRe, " ").replace(Regex("\\s+"), " ").trim()
        val quotes = listOf("\"" to "\"", "“" to "”", "‘" to "’", "«" to "»")
        for ((a, b) in quotes) if (t.length > 1 && t.startsWith(a) && t.endsWith(b)) { t = t.substring(1, t.length - 1).trim(); break }
        return t
    }

    private fun fromJson(text: String): List<Segment> {
        val arr: JSONArray = if (text.startsWith("[")) JSONArray(text) else {
            val o = JSONObject(text)
            o.optJSONArray("segments") ?: o.optJSONArray("utterances") ?: return emptyList()
        }
        val names = LinkedHashMap<String, String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val body = tidy(o.optString("text"))
            if (body.isEmpty()) return@mapNotNull null
            val label = o.optString("speaker", "Speaker 1").trim()
            val spk = when {
                Gender.isKnownLabel(label) -> Gender.canonical(label)
                label.length <= 1 -> names.getOrPut(label) { "Speaker ${names.size + 1}" } // raw ids like "A", "B"
                else -> label
            }
            Segment(spk, o.optLong("start_ms", o.optLong("start", 0)), o.optLong("end_ms", o.optLong("end", 0)), body)
        }
    }
}
