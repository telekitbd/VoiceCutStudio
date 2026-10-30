package com.voicecut.studio

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Languages {
    val targets = listOf(
        VoiceOption("bn", "Bengali (বাংলা)"),
        VoiceOption("hi", "Hindi (हिन्दी)"),
        VoiceOption("en", "English"),
    )

    fun name(code: String) = when (code) {
        "bn" -> "Bengali"; "hi" -> "Hindi"; "en" -> "English"; else -> code
    }

    /** Guesses the language of a script from the Unicode blocks it uses: "bn", "hi" or "en" (anything Latin/other). */
    fun guess(segments: List<Segment>): String {
        var bn = 0; var hi = 0; var other = 0
        for (seg in segments) for (c in seg.text) {
            when {
                c in 'ঀ'..'৿' -> bn++
                c in 'ऀ'..'ॿ' -> hi++
                c.isLetter() -> other++
            }
        }
        return when {
            bn > hi && bn >= other -> "bn"
            hi > bn && hi >= other -> "hi"
            else -> "en"
        }
    }
}

/** Translates script lines so a Bengali or Hindi voice-over can be made from any source language. */
object ScriptTranslator {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    /** Translates [segments] into [target] unless they are already in that language. */
    suspend fun translate(
        method: String, openAiKey: String, segments: List<Segment>, target: String, onStatus: (String) -> Unit
    ): List<Segment> {
        if (segments.isEmpty() || Languages.guess(segments) == target) return segments
        return if (method == "openai") openAi(openAiKey, segments, target, onStatus)
        else onDevice(segments, target, onStatus)
    }

    /** Free, on-device (Google ML Kit). Downloads a small language pack the first time. */
    suspend fun onDevice(segments: List<Segment>, target: String, onStatus: (String) -> Unit): List<Segment> {
        val sample = segments.joinToString(" ") { it.text }.take(3000)
        onStatus("Detecting language…")
        var detected = LanguageIdentification.getClient().use { it.identifyLanguage(sample).await() }
        if (detected == "und") detected = Languages.guess(segments)
        val source = TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: error("Unsupported target language")
        if (source == tgt) return segments

        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(tgt).build()
        )
        try {
            onStatus("Preparing ${Languages.name(target)} language pack (first time only, needs internet)…")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return segments.mapIndexed { i, seg ->
                onStatus("Translating line ${i + 1} of ${segments.size}…")
                if (seg.text.isBlank()) return@mapIndexed seg
                val pieces = ArrayList<String>()
                for (part in chunkText(seg.text, 800)) pieces += translator.translate(part).await()
                seg.copy(text = pieces.joinToString(" "))
            }
        } finally {
            translator.close()
        }
    }

    /** Higher quality, uses the whole conversation as context. Needs an OpenAI key. */
    suspend fun openAi(
        key: String, segments: List<Segment>, target: String, onStatus: (String) -> Unit
    ): List<Segment> = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Add your OpenAI API key in Settings first" }
        val lang = Languages.name(target)
        val script = when (target) { "bn" -> "Bengali script"; "hi" -> "Devanagari script"; else -> "Latin script" }
        val out = ArrayList<Segment>(segments.size)
        // Batches keep requests small while still giving the model conversational context.
        val batches = mutableListOf<List<Segment>>()
        var cur = mutableListOf<Segment>(); var chars = 0
        for (seg in segments) {
            if (cur.isNotEmpty() && (cur.size >= 30 || chars + seg.text.length > 6000)) {
                batches += cur; cur = mutableListOf(); chars = 0
            }
            cur += seg; chars += seg.text.length
        }
        if (cur.isNotEmpty()) batches += cur

        batches.forEachIndexed { bi, batch ->
            onStatus("Translating part ${bi + 1} of ${batches.size}…")
            val lines = JSONArray().also { a -> batch.forEach { a.put(it.text) } }
            val system = "You translate dialogue into natural, fluent spoken $lang written in $script. " +
                "Keep the meaning, tone and names; do not add or remove information. " +
                "Reply with JSON only: {\"lines\": [...]} containing exactly ${batch.size} strings, in the same order."
            val body = JSONObject()
                .put("model", "gpt-4o-mini")
                .put("temperature", 0.2)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", lines.toString())))
            val req = Request.Builder().url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val translated = http.newCall(req).await().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                    error(if (r.code == 401) "OpenAI rejected the API key. Check it in Settings." else "OpenAI error (${r.code}): ${msg ?: "request failed"}")
                }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                JSONObject(content).getJSONArray("lines")
            }
            check(translated.length() == batch.size) { "The translation came back in an unexpected format. Please try again." }
            batch.forEachIndexed { i, seg -> out += seg.copy(text = translated.getString(i)) }
        }
        out
    }
}
