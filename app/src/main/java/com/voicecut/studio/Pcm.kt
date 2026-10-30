package com.voicecut.studio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw 16-bit little-endian PCM. */
class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

object WavUtil {
    /** Reads a 16-bit PCM WAV file (as produced by Android TTS). */
    fun read(file: File): Pcm {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            require(len >= 12) { "The voice engine produced an empty file" }
            val head = ByteArray(12)
            raf.readFully(head)
            require(String(head, 0, 4) == "RIFF") { "Unexpected audio format from the voice engine" }
            var rate = 22050
            var ch = 1
            var bits = 16
            var pos = 12L
            while (pos + 8 <= len) {
                raf.seek(pos)
                val h = ByteArray(8)
                raf.readFully(h)
                val id = String(h, 0, 4)
                val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val f = ByteArray(16)
                    raf.readFully(f)
                    val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    b.short // format tag
                    ch = b.short.toInt()
                    rate = b.int
                    b.int; b.short
                    bits = b.short.toInt()
                } else if (id == "data") {
                    val avail = len - (pos + 8)
                    val n = if (size == 0L || size > avail) avail else size
                    require(bits == 16) { "Unsupported audio bit depth ($bits)" }
                    val data = ByteArray(n.toInt())
                    raf.readFully(data)
                    return Pcm(data, rate, ch.coerceAtLeast(1))
                }
                pos += 8 + size + (size and 1)
            }
            return Pcm(ByteArray(0), rate, ch.coerceAtLeast(1))
        }
    }
}

/** Streams PCM chunks into a mono 16-bit WAV file, resampling if needed. */
class PcmWriter(private val file: File) {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0); it.write(ByteArray(44)) }
    private var rate = 0
    private var total = 0L

    fun append(p: Pcm) {
        if (p.data.isEmpty()) return
        if (rate == 0) rate = p.sampleRate
        var s = toMono(p)
        if (p.sampleRate != rate) s = resample(s, p.sampleRate, rate)
        val out = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { out.putShort(it) }
        raf.write(out.array())
        total += s.size * 2L
    }

    /** Adds [p] so that it starts at [startMs] on the timeline (pads with silence; if already late, just continues). */
    fun appendAt(p: Pcm, startMs: Long) {
        if (p.data.isEmpty()) return
        if (rate == 0) rate = p.sampleRate
        val posMs = total * 1000 / (2L * rate)
        if (startMs > posMs) silence((startMs - posMs).toInt())
        append(p)
    }

    fun silence(ms: Int) {
        if (rate == 0 || ms <= 0) return
        val n = rate * ms / 1000
        raf.write(ByteArray(n * 2))
        total += n * 2L
    }

    fun finish() {
        require(total > 0) { "No audio was produced. Pick a voice that matches the language of your text." }
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + total).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(total.toInt())
        raf.seek(0)
        raf.write(b.array())
        raf.close()
    }

    fun abort() {
        runCatching { raf.close() }
        file.delete()
    }

    private fun toMono(p: Pcm): ShortArray {
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

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = pos - i0
            (src[i0] * (1 - f) + src[i1] * f).toInt().toShort()
        }
    }
}

fun chunkText(text: String, max: Int): List<String> {
    val clean = text.trim()
    if (clean.length <= max) return listOf(clean)
    val parts = mutableListOf<String>()
    var cur = StringBuilder()
    for (sentence in clean.split(Regex("(?<=[.!?।])\\s+"))) {
        if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) {
            parts += cur.toString(); cur = StringBuilder()
        }
        if (sentence.length > max) sentence.chunked(max).forEach { parts += it }
        else {
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(sentence)
        }
    }
    if (cur.isNotEmpty()) parts += cur.toString()
    return parts
}
