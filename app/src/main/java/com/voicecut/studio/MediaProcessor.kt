@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.voicecut.studio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MediaProcessor {

    /** Video (or audio) -> AAC audio in an .m4a file. */
    suspend fun extractAudio(ctx: Context, src: Uri, out: File) {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src)).setRemoveVideo(true).build()
        run(ctx, out) { it.start(item, out.absolutePath) }
    }

    /** Video -> same video without sound. */
    suspend fun removeAudio(ctx: Context, video: Uri, out: File) {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(video)).setRemoveAudio(true).build()
        run(ctx, out) { it.start(item, out.absolutePath) }
    }

    /** Video + new audio track -> new MP4. Audio is trimmed to the video length. */
    suspend fun replaceAudio(ctx: Context, video: Uri, audio: Uri, out: File) {
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(video)).setRemoveAudio(true).build()
        val dur = durationMs(ctx, video)
        val audioMedia = MediaItem.Builder().setUri(audio).apply {
            if (dur > 0) setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(dur).build()
            )
        }.build()
        val audioItem = EditedMediaItem.Builder(audioMedia).setRemoveVideo(true).build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(videoItem).build(),
            EditedMediaItemSequence.Builder(audioItem).build()
        ).build()
        run(ctx, out) { it.start(composition, out.absolutePath) }
    }

    private fun durationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }

    private suspend fun run(ctx: Context, out: File, start: (Transformer) -> Unit) {
        if (out.exists()) out.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()
                cont.invokeOnCancellation { t.cancel() }
                start(t)
            }
        }
    }
}
