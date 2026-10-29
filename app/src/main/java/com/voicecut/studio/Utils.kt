package com.voicecut.studio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.OutputStream

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: "file"
}

private fun saveToDownloads(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceCut")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("ফাইল সংরক্ষণ করা যায়নি")
    ctx.contentResolver.openOutputStream(uri)?.use(write) ?: error("ফাইল সংরক্ষণ করা যায়নি")
}

fun saveFile(ctx: Context, src: File, name: String, mime: String) =
    saveToDownloads(ctx, name, mime) { o -> src.inputStream().use { it.copyTo(o) } }

fun saveText(ctx: Context, text: String, name: String, mime: String) =
    saveToDownloads(ctx, name, mime) { o -> o.write(text.toByteArray(Charsets.UTF_8)) }
