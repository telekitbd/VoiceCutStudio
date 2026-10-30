package com.voicecut.studio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Segment(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

/** Streams a content:// or file:// Uri to the server without loading it into memory. */
private class UriBody(private val ctx: Context, private val uri: Uri) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = -1 // chunked transfer
    override fun writeTo(sink: BufferedSink) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
        input.source().use { sink.writeAll(it) }
    }
}

/** AssemblyAI: upload -> transcript (optional speaker labels) -> poll. */
object Transcriber {
    private const val BASE = "https://api.assemblyai.com/v2"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(
        ctx: Context,
        apiKey: String,
        media: Uri,
        speakerLabels: Boolean,
        detectLanguage: Boolean,
        onStatus: (String) -> Unit
    ): List<Segment> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add your AssemblyAI API key in Settings first" }

        onStatus("Uploading…")
        val up = Request.Builder().url("$BASE/upload").header("authorization", apiKey)
            .post(UriBody(ctx, media)).build()
        val uploadUrl = client.newCall(up).await().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 401 -> error("AssemblyAI rejected the API key. Check it in Settings.")
                !r.isSuccessful -> error("Upload failed (${r.code}): ${body.take(200)}")
            }
            JSONObject(body).getString("upload_url")
        }

        val payload = JSONObject()
            .put("audio_url", uploadUrl)
            .put("speaker_labels", speakerLabels)
        if (detectLanguage) payload.put("language_detection", true) else payload.put("language_code", "en")
        val create = Request.Builder().url("$BASE/transcript").header("authorization", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val id = client.newCall(create).await().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "Request failed (${r.code}): ${body.take(300)}" }
            JSONObject(body).getString("id")
        }

        var failures = 0
        while (true) {
            onStatus("Transcribing… this can take a few minutes")
            delay(3000)
            val poll = Request.Builder().url("$BASE/transcript/$id").header("authorization", apiKey).build()
            val json = try {
                client.newCall(poll).await().use { JSONObject(it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                if (++failures > 5) throw e
                continue
            }
            when (json.optString("status")) {
                "completed" -> return@withContext parse(apiKey, id, json)
                "error" -> error(json.optString("error", "Transcription failed"))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList()
    }

    private suspend fun parse(apiKey: String, id: String, json: JSONObject): List<Segment> {
        val utt = json.optJSONArray("utterances")
        if (utt != null && utt.length() > 0) {
            val names = LinkedHashMap<String, String>()
            return (0 until utt.length()).map { i ->
                val u = utt.getJSONObject(i)
                val label = u.optString("speaker", "A")
                val name = names.getOrPut(label) { "Speaker ${names.size + 1}" }
                Segment(name, u.getLong("start"), u.getLong("end"), u.getString("text"))
            }
        }
        // No speaker labels: use paragraphs so the result is still split into readable chunks.
        runCatching {
            val req = Request.Builder().url("$BASE/transcript/$id/paragraphs").header("authorization", apiKey).build()
            val p = client.newCall(req).await().use { JSONObject(it.body?.string().orEmpty()) }
                .optJSONArray("paragraphs")
            if (p != null && p.length() > 0) {
                return (0 until p.length()).map { i ->
                    val o = p.getJSONObject(i)
                    Segment("Speaker 1", o.getLong("start"), o.getLong("end"), o.getString("text"))
                }
            }
        }
        val text = json.optString("text", "")
        return if (text.isBlank()) emptyList()
        else listOf(Segment("Speaker 1", 0, (json.optDouble("audio_duration", 0.0) * 1000).toLong(), text))
    }
}
