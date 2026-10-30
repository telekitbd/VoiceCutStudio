package com.voicecut.studio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Listens to the audio and labels every speaker "Male" or "Female" (free, on-device, no API).
 * If anything goes wrong the original "Speaker N" labels are kept.
 */
object GenderDetector {
    /** Only the first minutes are decoded: enough to hear every speaker, and fast on long files. */
    private const val DECODE_MS = 15 * 60_000L

    suspend fun detect(ctx: Context, media: Uri, segs: List<Segment>, onStatus: (String) -> Unit): List<Segment> =
        withContext(Dispatchers.IO) {
            val wav = File(ctx.cacheDir, "gender_${System.nanoTime()}.wav")
            try {
                onStatus("Listening to each voice (male / female)…")
                WavExtractor.extract(ctx, media, wav, DECODE_MS)
                val genders = Gender.classify(Gender.pitchBySpeaker(wav, segs))
                if (genders.isEmpty()) segs
                else Gender.relabel(segs, segs.map { it.speaker }.distinct().associateWith { genders[it] })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                segs
            } finally {
                wav.delete()
            }
        }
}
