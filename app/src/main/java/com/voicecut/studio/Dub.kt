package com.voicecut.studio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Speeds speech up without raising its pitch (WSOLA), so a translated line can fit the original timing. */
object TimeStretch {
    fun toMono(p: Pcm): ShortArray {
        val sb = ByteBuffer.wrap(p.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val all = ShortArray(sb.remaining()).also { sb.get(it) }
        if (p.channels == 1) return all
        val frames = all.size / p.channels
        return ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until p.channels) sum += all[i * p.channels + c]
            (sum / p.channels).toShort()
        }
    }

    fun toPcm(x: ShortArray, rate: Int): Pcm {
        val bb = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { bb.putShort(it) }
        return Pcm(bb.array(), rate, 1)
    }

    /** [speed] > 1 makes the audio shorter (faster). Returns mono audio. */
    fun speedUp(x: ShortArray, rate: Int, speed: Double): ShortArray {
        if (speed <= 1.01 || x.size < rate / 10) return x
        val win = (rate * 30 / 1000) / 2 * 2
        val hopOut = win / 2
        val hopIn = (hopOut * speed).roundToInt()
        val seek = rate * 10 / 1000
        val outLen = (x.size / speed).toInt()
        val out = FloatArray(outLen + 2 * win)
        val w = FloatArray(win) { (0.5 - 0.5 * cos(2 * PI * it / win)).toFloat() }

        // first frame
        for (i in 0 until win) out[i] += (if (i < x.size) x[i] else 0) * w[i]
        var prev = 0
        var k = 1
        while (true) {
            val outPos = k * hopOut
            if (outPos >= outLen) break
            val nominal = k * hopIn
            if (nominal + win >= x.size) break
            val target = prev + hopOut // where the previous frame would naturally continue
            var best = nominal
            if (target + win < x.size) {
                var bestScore = Double.NEGATIVE_INFINITY
                val lo = max(0, nominal - seek)
                val hi = min(x.size - win - 1, nominal + seek)
                var cand = lo
                while (cand <= hi) {
                    var s = 0.0
                    var i = 0
                    while (i < win) { s += x[cand + i].toDouble() * x[target + i]; i += 4 }
                    if (s > bestScore) { bestScore = s; best = cand }
                    cand += 2
                }
            }
            for (i in 0 until win) out[outPos + i] += x[best + i] * w[i]
            prev = best
            k++
        }
        return ShortArray(outLen) { out[it].roundToInt().coerceIn(-32768, 32767).toShort() }
    }
}

/** Gives the script lines the timing of the original audio. */
object Aligner {
    /** Returns the lines with start/end times, and whether every line matched one spoken turn exactly. */
    fun align(script: List<Segment>, heard: List<Segment>): Pair<List<Segment>, Boolean> {
        val lines = script.filter { it.text.isNotBlank() }
        if (lines.isEmpty() || heard.isEmpty()) return script to false
        if (lines.size == heard.size) {
            val unlabeled = lines.map { it.speaker }.distinct().size == 1 && lines[0].speaker.startsWith("Speaker")
            return lines.mapIndexed { i, l ->
                l.copy(speaker = if (unlabeled) heard[i].speaker else l.speaker, startMs = heard[i].startMs, endMs = heard[i].endMs)
            } to true
        }
        // Different counts: spread the lines over the spoken part, in proportion to their length.
        val from = heard.first().startMs
        val to = heard.last().endMs
        val total = lines.sumOf { it.text.length.coerceAtLeast(1) }.toDouble()
        var acc = 0.0
        val out = lines.map { l ->
            val a = from + ((to - from) * acc / total).toLong()
            acc += l.text.length.coerceAtLeast(1)
            val b = from + ((to - from) * acc / total).toLong()
            l.copy(startMs = a, endMs = b)
        }
        return out to false
    }
}
