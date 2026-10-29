package com.voicecut.studio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class Segment(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

/** AssemblyAI: upload -> transcript with speaker labels -> poll. */
object Transcriber {
    private const val BASE = "https://api.assemblyai.com/v2"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(apiKey: String, audio: File, onStatus: (String) -> Unit): List<Segment> =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "API key দিন" }
            onStatus("আপলোড হচ্ছে…")
            val up = Request.Builder().url("$BASE/upload").header("authorization", apiKey)
                .post(audio.asRequestBody("application/octet-stream".toMediaType())).build()
            val uploadUrl = client.newCall(up).execute().use { r ->
                check(r.isSuccessful) { "আপলোড ব্যর্থ (${r.code}) — API key ঠিক আছে কি?" }
                JSONObject(r.body!!.string()).getString("upload_url")
            }

            val payload = JSONObject()
                .put("audio_url", uploadUrl)
                .put("speaker_labels", true)
                .put("language_detection", true)
                .toString().toRequestBody("application/json".toMediaType())
            val create = Request.Builder().url("$BASE/transcript").header("authorization", apiKey)
                .post(payload).build()
            val id = client.newCall(create).execute().use { r ->
                val body = r.body!!.string()
                check(r.isSuccessful) { "অনুরোধ ব্যর্থ (${r.code}): $body" }
                JSONObject(body).getString("id")
            }

            while (true) {
                onStatus("Transcribe হচ্ছে…")
                delay(3000)
                val poll = Request.Builder().url("$BASE/transcript/$id").header("authorization", apiKey).build()
                val json = client.newCall(poll).execute().use { JSONObject(it.body!!.string()) }
                when (json.getString("status")) {
                    "completed" -> return@withContext parse(json)
                    "error" -> error(json.optString("error", "unknown error"))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

    private fun parse(json: JSONObject): List<Segment> {
        val utt = json.optJSONArray("utterances")
        if (utt == null || utt.length() == 0) {
            val text = json.optString("text", "")
            return if (text.isBlank()) emptyList()
            else listOf(Segment("Speaker 1", 0, json.optLong("audio_duration") * 1000, text))
        }
        val names = LinkedHashMap<String, String>()
        return (0 until utt.length()).map { i ->
            val u = utt.getJSONObject(i)
            val label = u.optString("speaker", "A")
            val name = names.getOrPut(label) { "Speaker ${names.size + 1}" }
            Segment(name, u.getLong("start"), u.getLong("end"), u.getString("text"))
        }
    }
}
