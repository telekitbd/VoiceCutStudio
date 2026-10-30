package com.voicecut.studio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Male / female helpers: understanding speaker labels, relabelling, and estimating gender from pitch. */
object Gender {
    const val MALE = "male"
    const val FEMALE = "female"

    private val maleWords = setOf(
        "male", "man", "boy", "guy", "gentleman", "mr", "dad", "father", "husband", "brother",
        "ছেলে", "ছেলেটি", "পুরুষ", "লোক", "লোকটি", "বাবা", "ভাই", "স্বামী",
        "लड़का", "आदमी", "पुरुष", "पापा", "भाई",
    )
    private val femaleWords = setOf(
        "female", "woman", "girl", "lady", "mrs", "ms", "miss", "mom", "mum", "mother", "wife", "sister",
        "মেয়ে", "মেয়েটি", "নারী", "মহিলা", "মা", "বোন", "স্ত্রী", "আপু",
        "लड़की", "औरत", "महिला", "मम्मी", "माँ", "बहन",
    )
    private val fillerWords = setOf("speaker", "voice", "কণ্ঠ", "ভয়েস", "স্পিকার", "आवाज़")
    private val tokenSplit = Regex("[^\\p{L}\\p{M}\\d]+")

    private fun tokens(label: String) =
        label.lowercase().split(tokenSplit).filter { it.isNotEmpty() }

    /** "male" / "female" if the label says so ("Male", "ছেলে", "Woman 2", "Mr Rahim"), otherwise null. */
    fun fromLabel(label: String): String? {
        val t = tokens(label).filter { !it.all(Char::isDigit) }
        if (t.isEmpty()) return null
        if (t.size == 1 && t[0] == "f") return FEMALE
        if (t.size == 1 && t[0] == "m") return MALE
        if (t.any { it in femaleWords }) return FEMALE
        if (t.any { it in maleWords }) return MALE
        return null
    }

    /** True when the label is only a gender word (plus an optional number / "voice"), e.g. "ছেলে", "Male 2". */
    private fun isPureGender(label: String): Boolean {
        val t = tokens(label).filter { !it.all(Char::isDigit) && it !in fillerWords }
        return t.isNotEmpty() && (t.all { it in maleWords || it in femaleWords || it == "m" || it == "f" })
    }

    /** True for labels the parser should always trust: gender words and "Speaker 3". */
    fun isKnownLabel(label: String): Boolean =
        fromLabel(label) != null || Regex("^(speaker|স্পিকার)\\s*\\d*$", RegexOption.IGNORE_CASE).matches(label.trim())

    /** Nicely written label: "ছেলে" -> "Male", "woman 2" -> "Female 2", "speaker 3" -> "Speaker 3", others unchanged. */
    fun canonical(label: String): String {
        val clean = label.trim()
        val num = Regex("\\d+").find(clean)?.value
        if (isPureGender(clean)) {
            val name = if (fromLabel(clean) == FEMALE) "Female" else "Male"
            return if (num != null) "$name $num" else name
        }
        if (Regex("^(speaker|স্পিকার)\\s*\\d*$", RegexOption.IGNORE_CASE).matches(clean)) return "Speaker ${num ?: "1"}"
        return clean
    }

    /**
     * Renames speakers by gender: "Male" / "Female" (numbered when there are several of one kind).
     * Speakers whose gender is unknown keep their label.
     */
    fun relabel(segs: List<Segment>, genders: Map<String, String?>): List<Segment> {
        val order = segs.map { it.speaker }.distinct()
        val maleCount = order.count { genders[it] == MALE }
        val femaleCount = order.count { genders[it] == FEMALE }
        var m = 0
        var f = 0
        val names = HashMap<String, String>()
        for (sp in order) {
            names[sp] = when (genders[sp]) {
                MALE -> if (maleCount > 1) "Male ${++m}" else "Male"
                FEMALE -> if (femaleCount > 1) "Female ${++f}" else "Female"
                else -> sp
            }
        }
        return segs.map { it.copy(speaker = names.getValue(it.speaker)) }
    }

    /** Switches one speaker between male and female (used when the automatic guess is wrong). */
    fun flip(segs: List<Segment>, speaker: String): List<Segment> {
        val genders = HashMap<String, String?>()
        for (sp in segs.map { it.speaker }.distinct()) genders[sp] = fromLabel(sp)
        genders[speaker] = if (genders[speaker] == MALE) FEMALE else MALE
        return relabel(segs, genders)
    }

    // ---------------------------------------------------------------- pitch

    private const val SPLIT_HZ = 165f

    /** Decides male / female for each speaker from the median pitch (Hz) of their voice. */
    fun classify(f0: Map<String, Float>): Map<String, String> {
        val res = HashMap<String, String>()
        for ((sp, f) in f0) res[sp] = if (f < SPLIT_HZ) MALE else FEMALE
        if (f0.size == 2) {
            // Two people landing on the same side but clearly different in pitch: the lower one is the man.
            val (lo, hi) = f0.entries.sortedBy { it.value }
            if (res[lo.key] == res[hi.key] && hi.value / lo.value >= 1.25f &&
                lo.value in 110f..185f && hi.value in 135f..230f
            ) {
                res[lo.key] = MALE
                res[hi.key] = FEMALE
            }
        }
        return res
    }

    /**
     * Fundamental frequencies (Hz) of voiced 40 ms frames, found with normalised autocorrelation.
     * [x] is mono audio scaled to -1..1.
     */
    fun collectF0(x: FloatArray, rate: Int, out: MutableList<Float>) {
        val minLag = rate / 400
        val maxLag = rate / 70
        val frame = rate * 40 / 1000
        val hop = rate * 20 / 1000
        val len = frame + maxLag + 2
        if (minLag < 2 || x.size < len) return

        // Energy gate relative to the loudest speech in this clip, so hum and silence are ignored.
        val starts = ArrayList<Int>()
        val rmsList = ArrayList<Float>()
        var p = 0
        while (p + len <= x.size) {
            var e = 0.0
            for (i in 0 until frame) e += x[p + i] * x[p + i].toDouble()
            starts += p
            rmsList += sqrt(e / frame).toFloat()
            p += hop
        }
        if (rmsList.isEmpty()) return
        val sorted = rmsList.sorted()
        val gate = maxOf(0.004f, 0.3f * sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)])

        val w = FloatArray(len)
        val r = FloatArray(maxLag + 2)
        for ((k, s) in starts.withIndex()) {
            if (rmsList[k] < gate) continue
            var mean = 0f
            for (i in 0 until len) mean += x[s + i]
            mean /= len
            for (i in 0 until len) w[i] = x[s + i] - mean

            var e0 = 0.0
            for (i in 0 until frame) e0 += w[i] * w[i].toDouble()
            if (e0 <= 1e-9) continue

            var eLag = 0.0
            for (i in 0 until frame) eLag += w[minLag - 1 + i] * w[minLag - 1 + i].toDouble()
            var best = 0f
            for (lag in (minLag - 1)..(maxLag + 1)) {
                var num = 0.0
                for (i in 0 until frame) num += w[i] * w[i + lag].toDouble()
                val den = sqrt(e0 * eLag)
                r[lag] = if (den > 1e-12) (num / den).toFloat() else 0f
                if (lag in minLag..maxLag && r[lag] > best) best = r[lag]
                // slide the energy window one sample
                eLag += w[lag + frame] * w[lag + frame].toDouble() - w[lag] * w[lag].toDouble()
                if (eLag < 0) eLag = 0.0
            }
            if (best < 0.55f) continue // not a clearly voiced frame

            // Shortest strong peak: avoids picking twice the real period (an octave too low).
            var pick = -1
            for (lag in minLag..maxLag) {
                if (r[lag] >= 0.85f * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) { pick = lag; break }
            }
            if (pick < 0) continue
            val a = r[pick - 1]; val b = r[pick]; val c = r[pick + 1]
            val denom = a - 2 * b + c
            val shift = if (abs(denom) > 1e-6f) (0.5f * (a - c) / denom).coerceIn(-1f, 1f) else 0f
            val f0 = rate / (pick + shift)
            if (f0 in 70f..400f) out += f0
        }
    }

    fun median(v: List<Float>): Float {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /**
     * Median pitch per speaker, measured on their longest turns inside a 16-bit PCM WAV file.
     * Only reads the parts of the file it needs.
     */
    fun pitchBySpeaker(wav: File, segs: List<Segment>, wantMs: Long = 45_000L): Map<String, Float> {
        RandomAccessFile(wav, "r").use { raf ->
            if (raf.length() < 44) return emptyMap()
            val hdr = ByteArray(44)
            raf.readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val ch = bb.getShort(22).toInt().coerceAtLeast(1)
            val sr = bb.getInt(24)
            if (sr < 8000) return emptyMap()
            val frameBytes = 2 * ch
            val totalFrames = (raf.length() - 44) / frameBytes
            val factor = maxOf(1, sr / 8000)
            val outRate = sr / factor
            val result = HashMap<String, Float>()

            for ((sp, list) in segs.groupBy { it.speaker }) {
                var gathered = 0L
                val f0s = ArrayList<Float>()
                for (seg in list.sortedByDescending { it.endMs - it.startMs }) {
                    if (gathered >= wantMs) break
                    val s0 = seg.startMs * sr / 1000
                    val s1 = minOf(seg.endMs * sr / 1000, totalFrames)
                    if (s1 - s0 < sr / 5) continue // too short or past the decoded part
                    val n = minOf(s1 - s0, 20L * sr).toInt()
                    val buf = ByteArray(n * frameBytes)
                    raf.seek(44 + s0 * frameBytes)
                    raf.readFully(buf)
                    val sb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val outLen = n / factor
                    val x = FloatArray(outLen)
                    for (i in 0 until outLen) {
                        var sum = 0f
                        for (j in 0 until factor) for (c in 0 until ch) sum += sb.get(((i * factor + j) * ch) + c)
                        x[i] = sum / (factor * ch) / 32768f
                    }
                    collectF0(x, outRate, f0s)
                    gathered += (s1 - s0) * 1000 / sr
                }
                if (f0s.size >= 20) result[sp] = median(f0s)
            }
            return result
        }
    }
}
