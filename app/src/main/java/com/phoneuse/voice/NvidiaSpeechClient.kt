package com.phoneuse.voice

import com.phoneuse.app.NvidiaVoiceGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Dedicated adapter for NVIDIA Parakeet ASR & Magpie TTS Multilingual speech endpoints.
 * Strictly separated from `/v1/chat/completions` so voice endpoints never leak into LLM transport.
 */
object NvidiaSpeechClient {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val OCTET_MEDIA = "audio/wav".toMediaType()

    sealed interface SpeechResult {
        data class Transcribed(val text: String) : SpeechResult
        data class Synthesized(val audioBytes: ByteArray, val contentType: String) : SpeechResult
        data object Unconfigured : SpeechResult
        data class Failed(val code: Int, val message: String) : SpeechResult
    }

    fun isAsrConfigured(endpointUrl: String, apiKey: String): Boolean =
        endpointUrl.trim().startsWith("https://") || endpointUrl.trim().startsWith("http://")
            .let { validUrl -> validUrl && apiKey.isNotBlank() }

    fun isTtsConfigured(endpointUrl: String, apiKey: String): Boolean =
        (endpointUrl.trim().startsWith("https://") || endpointUrl.trim().startsWith("http://")) &&
            apiKey.isNotBlank()

    suspend fun transcribeParakeet(
        endpointUrl: String,
        apiKey: String,
        audioBytes: ByteArray,
        languageTag: String,
        http: OkHttpClient,
    ): SpeechResult = withContext(Dispatchers.IO) {
        val url = endpointUrl.trim()
        if (!isAsrConfigured(url, apiKey)) return@withContext SpeechResult.Unconfigured
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", audioBytes.toRequestBody(OCTET_MEDIA))
            .addFormDataPart("model", "nvidia/parakeet-tdt-0.6b-v2")
            .addFormDataPart("language", languageTag)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body)
            .build()
        try {
            val (code, textBody) = executeText(http.newCall(request))
            if (code !in 200..299) {
                return@withContext SpeechResult.Failed(code, "Parakeet ASR HTTP $code")
            }
            val parsed = runCatching { JSONObject(textBody).optString("text", "") }.getOrDefault("")
            SpeechResult.Transcribed(parsed.trim())
        } catch (e: IOException) {
            SpeechResult.Failed(0, e.message ?: "Parakeet ASR I/O error")
        }
    }

    suspend fun synthesizeMagpie(
        endpointUrl: String,
        apiKey: String,
        text: String,
        languageTag: String,
        gender: NvidiaVoiceGender,
        speed: Float,
        http: OkHttpClient,
    ): SpeechResult = withContext(Dispatchers.IO) {
        val url = endpointUrl.trim()
        if (!isTtsConfigured(url, apiKey)) return@withContext SpeechResult.Unconfigured
        val payload = JSONObject().apply {
            put("model", "nvidia/magpie-tts-multilingual")
            put("input", text)
            put("language", languageTag)
            put(
                "voice",
                when (gender) {
                    NvidiaVoiceGender.FEMALE -> "Magpie-Multilingual.EN-US.Sofia"
                    NvidiaVoiceGender.MALE -> "Magpie-Multilingual.EN-US.Leo"
                    NvidiaVoiceGender.NEUTRAL -> "Magpie-Multilingual.EN-US.Aria"
                },
            )
            put("speed", speed.coerceIn(0.6f, 1.6f).toDouble())
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            val (code, bytes, contentType) = executeBytes(http.newCall(request))
            if (code !in 200..299) {
                return@withContext SpeechResult.Failed(code, "Magpie TTS HTTP $code")
            }
            SpeechResult.Synthesized(bytes, contentType ?: "audio/wav")
        } catch (e: IOException) {
            SpeechResult.Failed(0, e.message ?: "Magpie TTS I/O error")
        }
    }

    private suspend fun executeText(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (cont.isActive) cont.resume(r.code to txt)
                }
            }
        })
    }

    private suspend fun executeBytes(call: Call): Triple<Int, ByteArray, String?> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    val mime = r.header("Content-Type")
                    if (cont.isActive) cont.resume(Triple(r.code, bytes, mime))
                }
            }
        })
    }
}
