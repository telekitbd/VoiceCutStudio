package com.voicecut.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copies a time range out of a 16-bit PCM WAV file into a new WAV file. */
object AudioCutter {
    suspend fun slice(src: File, startMs: Long, endMs: Long, out: File) = withContext(Dispatchers.IO) {
        RandomAccessFile(src, "r").use { raf ->
            val w = Mp3.info(raf)
            val frame = w.channels * 2
            val totalFrames = w.dataLen / frame
            val a = (startMs * w.rate / 1000).coerceIn(0, totalFrames)
            val b = (endMs * w.rate / 1000).coerceIn(a, totalFrames)
            val len = (b - a) * frame
            require(len > 0) { "The selected range is empty" }
            out.outputStream().buffered().use { o ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt((36 + len).toInt())
                h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
                h.putInt(16); h.putShort(1); h.putShort(w.channels.toShort())
                h.putInt(w.rate); h.putInt(w.rate * frame); h.putShort(frame.toShort()); h.putShort(16)
                h.put("data".toByteArray()); h.putInt(len.toInt())
                o.write(h.array())
                raf.seek(w.dataOffset + a * frame)
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    /** "90", "1:30", "1:02:03" or "1:30.5" -> milliseconds, or null if invalid. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for (p in parts) total = total * 60 + (p.trim().toDoubleOrNull()?.takeIf { it >= 0 } ?: return null)
        return (total * 1000).toLong()
    }
}
