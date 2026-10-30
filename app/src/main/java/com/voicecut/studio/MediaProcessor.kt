@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.voicecut.studio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Throwable.userMessage(): String = when (this) {
    is ExportException ->
        "This file's format or codec isn't supported on this device (${errorCodeName}). " +
            "Try another file, or use the Transcribe tab which accepts almost any format."
    is UnknownHostException, is SocketTimeoutException, is ConnectException ->
        "Network problem. Check your internet connection and try again."
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

object MediaProcessor {

    /** Video (or audio) -> AAC audio in an .m4a file. */
    suspend fun extractAudio(ctx: Context, src: Uri, out: File, onProgress: (Int) -> Unit = {}) {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src)).setRemoveVideo(true).build()
        run(ctx, out, onProgress) { it.start(item, out.absolutePath) }
    }

    /** Cuts [startMs, endMs) out of any audio/video file into an .m4a file. */
    suspend fun cutAudio(ctx: Context, src: Uri, out: File, startMs: Long, endMs: Long, onProgress: (Int) -> Unit = {}) {
        val media = MediaItem.Builder().setUri(src).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        ).build()
        val item = EditedMediaItem.Builder(media).setRemoveVideo(true).build()
        run(ctx, out, onProgress) { it.start(item, out.absolutePath) }
    }

    /** Video -> same video without sound. */
    suspend fun removeAudio(ctx: Context, video: Uri, out: File, onProgress: (Int) -> Unit = {}) {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(video)).setRemoveAudio(true).build()
        run(ctx, out, onProgress) { it.start(item, out.absolutePath) }
    }

    /** Video + new audio track -> new MP4. Audio is trimmed to the video length. */
    suspend fun replaceAudio(ctx: Context, video: Uri, audio: Uri, out: File, onProgress: (Int) -> Unit = {}) {
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
        run(ctx, out, onProgress) { it.start(composition, out.absolutePath) }
    }

    private fun durationMs(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private suspend fun run(ctx: Context, out: File, onProgress: (Int) -> Unit, start: (Transformer) -> Unit) {
        if (out.exists()) out.delete()
        withContext(Dispatchers.Main) {
            coroutineScope {
                var transformer: Transformer? = null
                val holder = ProgressHolder()
                val poller = launch {
                    while (true) {
                        delay(400)
                        val t = transformer ?: continue
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    }
                }
                try {
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
                        transformer = t
                        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { t.cancel() } }
                        start(t)
                    }
                } finally {
                    poller.cancel()
                }
            }
        }
    }
}
