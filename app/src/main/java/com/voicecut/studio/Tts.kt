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

data class VoiceOption(val id: String, val label: String)

/** Android's built-in text-to-speech: free, offline, voices depend on the phone. */
class DeviceTts private constructor(private val tts: TextToSpeech) {

    fun shutdown() = runCatching { tts.shutdown() }

    suspend fun synth(ctx: Context, text: String, voiceId: String?, speed: Float): Pcm = withContext(Dispatchers.IO) {
        if (voiceId != null) {
            runCatching { tts.voices?.firstOrNull { it.name == voiceId } }.getOrNull()?.let { tts.voice = it }
        }
        tts.setSpeechRate(speed)
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
                        VoiceOption(it.name, "${it.locale.displayName} · $tag")
                    }
            } finally {
                d.shutdown()
            }
        }
    }
}

/** OpenAI text-to-speech (needs an API key). Returns 24 kHz mono PCM. */
object CloudTts {
    val voices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")
        .map { VoiceOption(it, it.replaceFirstChar { c -> c.uppercase() }) }

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
