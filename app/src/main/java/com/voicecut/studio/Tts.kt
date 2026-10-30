package com.voicecut.studio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VoiceOption(val id: String, val label: String, val lang: String? = null, val gender: String? = null)

/** Android's built-in text-to-speech: free, offline, voices depend on the phone. */
class DeviceTts private constructor(private val tts: TextToSpeech) {

    fun shutdown() = runCatching { tts.shutdown() }

    suspend fun synth(ctx: Context, text: String, voiceId: String?, speed: Float, pitch: Float = 1f): Pcm = withContext(Dispatchers.IO) {
        if (voiceId != null) {
            runCatching { tts.voices?.firstOrNull { it.name == voiceId } }.getOrNull()?.let { tts.voice = it }
        }
        tts.setSpeechRate(speed)
        tts.setPitch(pitch)
        val bos = ByteArrayOutputStream()
        var rate = 22050
        var ch = 1
        val max = minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3500)
        for (part in chunkText(text, max)) {
            val f = File.createTempFile("tts", ".wav", ctx.cacheDir)
            try {
                toFile(part, f)
                val p = WavUtil.read(f)
                rate = p.sampleRate; ch = p.channels
                bos.write(p.data)
            } finally {
                f.delete()
            }
        }
        Pcm(bos.toByteArray(), rate, ch)
    }

    private suspend fun toFile(text: String, file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive)
                    cont.resumeWithException(IllegalStateException("The voice engine failed to speak this text"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id && cont.isActive)
                    cont.resumeWithException(
                        IllegalStateException("The voice engine failed (code $errorCode). Try another voice.")
                    )
            }
        })
        cont.invokeOnCancellation { tts.stop() }
        val r = tts.synthesizeToFile(text, Bundle(), file, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive)
            cont.resumeWithException(IllegalStateException("The voice engine could not start"))
    }

    companion object {
        private val knownFemale = setOf("sfg", "iob", "iog", "tpc", "tpf")
        private val knownMale = setOf("iol", "iom", "tpd")

        /** Best effort: Google voice names end in a code like "en-us-x-tpd-local"; some codes reveal the gender. */
        fun guessGender(name: String): String? {
            val n = name.lowercase()
            if ("female" in n) return Gender.FEMALE
            if ("male" in n) return Gender.MALE
            val code = Regex("-x-([a-z]{3})").find(n)?.groupValues?.get(1) ?: return null
            return when {
                code in knownFemale -> Gender.FEMALE
                code in knownMale -> Gender.MALE
                code.endsWith("f") -> Gender.FEMALE
                code.endsWith("m") -> Gender.MALE
                else -> null
            }
        }

        suspend fun create(ctx: Context): DeviceTts = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<DeviceTts> { cont ->
                var ref: TextToSpeech? = null
                ref = TextToSpeech(ctx.applicationContext) { status ->
                    val t = ref
                    if (status == TextToSpeech.SUCCESS && t != null) cont.resume(DeviceTts(t))
                    else if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("No text-to-speech engine is available on this phone")
                    )
                }
            }
        }

        suspend fun listVoices(ctx: Context): List<VoiceOption> {
            val d = create(ctx)
            try {
                val voices = runCatching { d.tts.voices }.getOrNull().orEmpty()
                val mine = Locale.getDefault().language
                return voices
                    .filter { !it.features.contains("notInstalled") }
                    .sortedWith(compareBy({ it.locale.language != "en" && it.locale.language != mine }, { it.locale.displayName }, { it.name }))
                    .map {
                        val tag = it.name.substringAfter("-x-", it.name)
                            .removeSuffix("-local").removeSuffix("-network").replace('#', ' ')
                        VoiceOption(it.name, "${it.locale.displayName} · $tag", it.locale.language, guessGender(it.name))
                    }
            } finally {
                d.shutdown()
            }
        }
    }
}

/** OpenAI text-to-speech (needs an API key). Returns 24 kHz mono PCM. */
object CloudTts {
    private val female = setOf("alloy", "coral", "nova", "sage", "shimmer")
    val voices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")
        .map {
            VoiceOption(
                it, it.replaceFirstChar { c -> c.uppercase() } + if (it in female) " (female)" else " (male)",
                gender = if (it in female) Gender.FEMALE else Gender.MALE
            )
        }

    const val MODEL_NATURAL = "gpt-4o-mini-tts"
    const val MODEL_STANDARD = "tts-1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun synth(key: String, model: String, voice: String, text: String, speed: Float): Pcm =
        withContext(Dispatchers.IO) {
            val bos = ByteArrayOutputStream()
            for (part in chunkText(text, 3800)) {
                val body = JSONObject().put("model", model).put("input", part).put("voice", voice)
                    .put("response_format", "pcm")
                if (model == MODEL_STANDARD) body.put("speed", speed.toDouble())
                val req = Request.Builder().url("https://api.openai.com/v1/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(req).await().use { r ->
                    if (!r.isSuccessful) {
                        val msg = runCatching {
                            JSONObject(r.body?.string().orEmpty()).getJSONObject("error").getString("message")
                        }.getOrNull()
                        error(
                            if (r.code == 401) "OpenAI rejected the API key. Check it in Settings."
                            else "OpenAI error (${r.code}): ${msg ?: "request failed"}"
                        )
                    }
                    bos.write(r.body!!.bytes())
                }
            }
            Pcm(bos.toByteArray(), 24000, 1)
        }
}

/** ElevenLabs text-to-speech (needs an API key). Returns 24 kHz mono PCM. */
object ElevenLabs {
    const val MODEL_V3 = "eleven_v3"
    const val MODEL_MULTI = "eleven_multilingual_v2"
    const val MODEL_FLASH = "eleven_flash_v2_5"

    val models = listOf(
        VoiceOption(MODEL_V3, "v3 · 70+ languages incl. Bengali"),
        VoiceOption(MODEL_MULTI, "Multilingual v2 · 29 languages"),
        VoiceOption(MODEL_FLASH, "Flash v2.5 · fast, 32 languages"),
    )

    private const val BASE = "https://api.elevenlabs.io/v1"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    private fun errorFor(code: Int, body: String): String {
        if (code == 401) return "ElevenLabs rejected the API key. Check it in Settings."
        val detail = runCatching {
            val d = JSONObject(body).opt("detail")
            if (d is JSONObject) d.optString("message") else d?.toString()
        }.getOrNull()
        return "ElevenLabs error ($code): ${detail?.takeIf { it.isNotBlank() } ?: "request failed"}"
    }

    suspend fun listVoices(key: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/voices").header("xi-api-key", key).build()
        client.newCall(req).await().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error(errorFor(r.code, body))
            val arr = JSONObject(body).optJSONArray("voices") ?: return@use emptyList<VoiceOption>()
            (0 until arr.length()).map {
                val v = arr.getJSONObject(it)
                val g = v.optJSONObject("labels")?.optString("gender", "")?.lowercase()
                val gender = when (g) { "male" -> Gender.MALE; "female" -> Gender.FEMALE; else -> null }
                val name = v.optString("name", "Voice ${it + 1}")
                VoiceOption(v.getString("voice_id"), if (gender != null) "$name ($gender)" else name, gender = gender)
            }
        }
    }

    suspend fun synth(key: String, model: String, voiceId: String, text: String): Pcm =
        withContext(Dispatchers.IO) {
            val bos = ByteArrayOutputStream()
            for (part in chunkText(text, 4500)) {
                val body = JSONObject().put("text", part).put("model_id", model)
                val req = Request.Builder()
                    .url("$BASE/text-to-speech/$voiceId?output_format=pcm_24000")
                    .header("xi-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(req).await().use { r ->
                    if (!r.isSuccessful) error(errorFor(r.code, r.body?.string().orEmpty()))
                    bos.write(r.body!!.bytes())
                }
            }
            Pcm(bos.toByteArray(), 24000, 1)
        }
}
