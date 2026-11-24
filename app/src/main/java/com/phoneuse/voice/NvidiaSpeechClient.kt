package com.phoneuse.voice

import com.phoneuse.app.CompatibleEndpoints
import com.phoneuse.app.NvidiaVoiceGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    const val PARAKEET_MULTILINGUAL = "nvidia/parakeet-1_1b-rnnt-multilingual-asr"
    const val PARAKEET_ENGLISH = "nvidia/parakeet-tdt-0.6b-v2"
    const val MAGPIE_MODEL = "nvidia/magpie-tts-multilingual"

    sealed interface SpeechResult {
        data class Transcribed(val text: String) : SpeechResult
        data class Synthesized(val audioBytes: ByteArray, val contentType: String) : SpeechResult
        data class Voices(val voices: List<String>) : SpeechResult
        data object Unconfigured : SpeechResult
        data class Failed(val code: Int, val message: String) : SpeechResult
    }

    private fun isValidEndpoint(endpointUrl: String, apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        val url = endpointUrl.trim().toHttpUrlOrNull() ?: return false
        return when (url.scheme.lowercase()) {
            "https" -> true
            "http" -> url.host.lowercase() in CompatibleEndpoints.CLEARTEXT_HOSTS
            else -> false
        }
    }

    fun isAsrConfigured(endpointUrl: String, apiKey: String): Boolean = isValidEndpoint(endpointUrl, apiKey)

    fun isTtsConfigured(endpointUrl: String, apiKey: String): Boolean = isValidEndpoint(endpointUrl, apiKey)

    fun selectAsrModel(languageTag: String, requestedModel: String? = null): String {
        if (!requestedModel.isNullOrBlank()) return requestedModel.trim()
        val tag = languageTag.lowercase().replace('_', '-')
        return if (tag.startsWith("hi") || tag.contains("hinglish") || tag == "hi-in") {
            PARAKEET_MULTILINGUAL
        } else {
            PARAKEET_ENGLISH
        }
    }

    fun selectMagpieVoice(languageTag: String, gender: NvidiaVoiceGender): String {
        val tag = languageTag.lowercase().replace('_', '-')
        val isHindi = tag.startsWith("hi") || tag.contains("hinglish")
        return when (gender) {
            NvidiaVoiceGender.FEMALE -> if (isHindi) "Magpie-Multilingual.HI-IN.Radhika" else "Magpie-Multilingual.EN-US.Sofia"
            NvidiaVoiceGender.MALE -> if (isHindi) "Magpie-Multilingual.HI-IN.Amit" else "Magpie-Multilingual.EN-US.Leo"
            NvidiaVoiceGender.NEUTRAL -> if (isHindi) "Magpie-Multilingual.HI-IN.Radhika" else "Magpie-Multilingual.EN-US.Aria"
        }
    }

    suspend fun transcribeParakeet(
        endpointUrl: String,
        apiKey: String,
        audioBytes: ByteArray,
        languageTag: String,
        http: OkHttpClient,
        modelId: String? = null,
    ): SpeechResult = withContext(Dispatchers.IO) {
        val url = endpointUrl.trim()
        if (!isAsrConfigured(url, apiKey)) return@withContext SpeechResult.Unconfigured
        val effectiveModel = selectAsrModel(languageTag, modelId)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", audioBytes.toRequestBody(OCTET_MEDIA))
            .addFormDataPart("model", effectiveModel)
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
                return@withContext SpeechResult.Failed(code, "Parakeet ASR HTTP $code: $textBody")
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
        val voiceName = selectMagpieVoice(languageTag, gender)

        // 1. Try official multipart/form-data schema for NVIDIA /v1/audio/synthesize
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("language", languageTag)
            .addFormDataPart("voice", voiceName)
            .addFormDataPart("encoding", "LINEAR_PCM")
            .addFormDataPart("sample_rate_hz", "22050")
            .addFormDataPart("speed", speed.coerceIn(0.6f, 1.6f).toString())
            .build()
        val multiReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(multipart)
            .build()

        try {
            val (code, bytes, contentType) = executeBytes(http.newCall(multiReq))
            if (code in 200..299 && bytes.isNotEmpty()) {
                return@withContext SpeechResult.Synthesized(bytes, contentType ?: "audio/wav")
            }
        } catch (_: Exception) {
            // Fall through to JSON payload fallback
        }

        // 2. Fallback to JSON schema for endpoints accepting standard JSON body
        val payload = JSONObject().apply {
            put("model", MAGPIE_MODEL)
            put("input", text)
            put("language", languageTag)
            put("voice", voiceName)
            put("speed", speed.coerceIn(0.6f, 1.6f).toDouble())
        }
        val jsonReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            val (code, bytes, contentType) = executeBytes(http.newCall(jsonReq))
            if (code !in 200..299) {
                return@withContext SpeechResult.Failed(code, "Magpie TTS HTTP $code")
            }
            SpeechResult.Synthesized(bytes, contentType ?: "audio/wav")
        } catch (e: IOException) {
            SpeechResult.Failed(0, e.message ?: "Magpie TTS I/O error")
        }
    }

    suspend fun listVoices(
        endpointUrl: String,
        apiKey: String,
        http: OkHttpClient,
    ): SpeechResult = withContext(Dispatchers.IO) {
        val base = endpointUrl.trim().removeSuffix("/synthesize").removeSuffix("/transcriptions").removeSuffix("/")
        val url = "$base/list_voices"
        if (!isValidEndpoint(url, apiKey)) return@withContext SpeechResult.Unconfigured
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()
        try {
            val (code, textBody) = executeText(http.newCall(req))
            if (code !in 200..299) return@withContext SpeechResult.Failed(code, "list_voices HTTP $code")
            val voicesList = mutableListOf<String>()
            runCatching {
                val json = JSONObject(textBody)
                val arr = json.optJSONArray("voices")
                if (arr != null) {
                    for (i in 0 until arr.length()) voicesList += arr.getString(i)
                }
            }
            SpeechResult.Voices(voicesList)
        } catch (e: IOException) {
            SpeechResult.Failed(0, e.message ?: "list_voices I/O error")
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
