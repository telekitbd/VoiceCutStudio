package com.voicecut.studio

import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MP3 encoding with the bundled LAME library. Input: 16-bit PCM WAV. */
object Mp3 {
    // The native encoder keeps global state, so only one encode may run at a time.
    private val lock = Mutex()

    internal class WavInfo(val rate: Int, val channels: Int, val dataOffset: Long, val dataLen: Long)

    internal fun info(raf: RandomAccessFile): WavInfo {
        val len = raf.length()
        val head = ByteArray(12)
        raf.seek(0); raf.readFully(head)
        require(String(head, 0, 4) == "RIFF") { "Not a WAV file" }
        var rate = 44100; var ch = 2; var bits = 16
        var pos = 12L
        while (pos + 8 <= len) {
            raf.seek(pos)
            val h = ByteArray(8); raf.readFully(h)
            val id = String(h, 0, 4)
            val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val f = ByteArray(16); raf.readFully(f)
                val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                b.short; ch = b.short.toInt(); rate = b.int; b.int; b.short; bits = b.short.toInt()
            } else if (id == "data") {
                require(bits == 16) { "Unsupported audio bit depth ($bits)" }
                val avail = len - (pos + 8)
                return WavInfo(rate, ch.coerceAtLeast(1), pos + 8, if (size == 0L || size > avail) avail else size)
            }
            pos += 8 + size + (size and 1)
        }
        error("No audio data found")
    }

    suspend fun encodeWav(wav: File, out: File, kbps: Int, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            try {
                lock.withLock { encode(wav, out, kbps, onProgress) }
            } catch (e: LinkageError) {
                out.delete()
                throw IllegalStateException("The MP3 encoder isn't available on this device. Use M4A or WAV instead.")
            }
        }

    private suspend fun encode(wav: File, out: File, kbps: Int, onProgress: (Float) -> Unit) {
        RandomAccessFile(wav, "r").use { raf ->
            val w = info(raf)
            val srcCh = w.channels
            val outCh = if (srcCh == 2) 2 else 1
            val lame = LameBuilder()
                .setInSampleRate(w.rate)
                .setOutChannels(outCh)
                .setOutBitrate(kbps)
                .setQuality(5)
                .build()
            try {
                FileOutputStream(out).use { fos ->
                    val framesPerChunk = 8192
                    val bytes = ByteArray(framesPerChunk * srcCh * 2)
                    val mp3 = ByteArray((framesPerChunk * 1.25 + 7200).toInt())
                    var remaining = w.dataLen
                    raf.seek(w.dataOffset)
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val want = minOf(bytes.size.toLong(), remaining).toInt()
                        val got = raf.read(bytes, 0, want)
                        if (got <= 0) break
                        remaining -= got
                        val frames = got / (srcCh * 2)
                        if (frames == 0) break
                        val sb = ByteBuffer.wrap(bytes, 0, frames * srcCh * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val all = ShortArray(frames * srcCh).also { sb.get(it) }
                        val n = if (srcCh == 2) {
                            lame.encodeBufferInterLeaved(all, frames, mp3)
                        } else {
                            val mono = if (srcCh == 1) all else ShortArray(frames) { i ->
                                var sum = 0
                                for (c in 0 until srcCh) sum += all[i * srcCh + c]
                                (sum / srcCh).toShort()
                            }
                            lame.encode(mono, mono, frames, mp3)
                        }
                        check(n >= 0) { "MP3 encoding failed (code $n)" }
                        fos.write(mp3, 0, n)
                        onProgress(1f - remaining.toFloat() / w.dataLen)
                    }
                    val n = lame.flush(mp3)
                    if (n > 0) fos.write(mp3, 0, n)
                }
            } finally {
                runCatching { lame.close() }
            }
        }
    }
}
