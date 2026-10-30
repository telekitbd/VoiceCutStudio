package com.voicecut.studio

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.util.Locale

fun displayName(ctx: Context, uri: Uri): String {
    if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
    }
    return uri.lastPathSegment ?: "file"
}

fun baseName(name: String) = name.substringBeforeLast('.', name).ifBlank { "file" }

data class MediaInfo(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val hasVideo: Boolean?,
    val hasAudio: Boolean?
)

fun mediaInfo(ctx: Context, uri: Uri): MediaInfo {
    val name = displayName(ctx, uri)
    var size = -1L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) size = c.getLong(i)
        }
    }
    var dur = 0L
    var video: Boolean? = null
    var audio: Boolean? = null
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        video = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
    } catch (_: Exception) {
        // Unknown container for the platform retriever; everything stays "unknown".
    } finally {
        runCatching { r.release() }
    }
    return MediaInfo(name, size, dur, video, audio)
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s / 60 % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
    else String.format(Locale.US, "%d:%02d", m, s % 60)
}

private fun saveToDownloads(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceCut")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create the output file")
    ctx.contentResolver.openOutputStream(uri)?.use(write) ?: error("Could not write the output file")
    return uri
}

fun saveFile(ctx: Context, src: File, name: String, mime: String): Uri =
    saveToDownloads(ctx, name, mime) { o -> src.inputStream().use { it.copyTo(o) } }

fun saveText(ctx: Context, text: String, name: String, mime: String): Uri =
    saveToDownloads(ctx, name, mime) { o -> o.write(text.toByteArray(Charsets.UTF_8)) }

fun openUri(ctx: Context, uri: Uri, mime: String?) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime ?: ctx.contentResolver.getType(uri) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

fun shareUri(ctx: Context, uri: Uri, mime: String?) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime ?: ctx.contentResolver.getType(uri) ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openWeb(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun readTextFile(ctx: Context, uri: Uri, maxBytes: Int = 8_000_000): String {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not read this file")
    require(bytes.size <= maxBytes) { "This file is too large to be a transcript" }
    return bytes.toString(Charsets.UTF_8).removePrefix("﻿")
}
