package com.phoneuse.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An OpenAI-compatible Chat Completions backend: xAI (Grok), Google AI Studio (Gemini) and any server the user
 * enters ([com.phoneuse.app.AuthMode.COMPATIBLE]); a blank API key sends no Authorization header (local servers).
 * Streams text, function tools and images; no hosted web search ([ModelOptions.webSearch] and [ModelOptions.fast]
 * are ignored). Gemini's thought signatures ride on each call's [InputItem.FunctionCall.extra].
 */
class ChatCompletionsClient(
    private val endpoint: Endpoint,
    private val apiKey: String,
    private val options: ModelOptions,
    private val http: OkHttpClient,
) : ModelClient {

    /** [baseUrl] ends with a slash; `chat/completions` and `models` are relative to it. */
    data class Endpoint(val baseUrl: String, val unauthorizedMessage: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** Which response each call came from, so a turn's calls go back as one assistant message. */
    private val callTurn = ConcurrentHashMap<String, Int>()
    private val turns = AtomicInteger()

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        val droppedThisCall = HashSet<String>()
        while (true) {
            val profile = ModelCapabilityRegistry.profile(endpoint.baseUrl, options.model, optimisticVision = true)
            val allowImages = profile.canAcceptImages(visionEnabled = true)
            val cleanInput = if (allowImages) {
                input
            } else {
                ModelCapabilityRegistry.stripImagesFromHistory(
                    input,
                    omittedMarker = "[Screenshot omitted: model ${options.model} is text-only]",
                )
            }
            val effort = options.effort.takeIf {
                effortKey() !in noEffort && profile.supportsParam("reasoning_effort")
            }
            val stream = profile.supportsStreaming
            val body = body(instructions, cleanInput, tools, effort, allowImages, stream, profile)
            try {
                return send(body, stream, onTextDelta)
            } catch (e: ModelException) {
                when {
                    ModelErrorClassifier.isMultimodalRejection(e) -> {
                        ModelCapabilityRegistry.markVisionUnsupported(endpoint.baseUrl, options.model)
                        throw ModelException(
                            status = e.status ?: 400,
                            message = e.message ?: "Model rejected multimodal content",
                            retryAfterMs = e.retryAfterMs,
                            code = ModelErrorClassifier.CODE_MULTIMODAL_UNSUPPORTED,
                            afterOutput = e.afterOutput,
                            retryable = false,
                            failure = e.failure,
                        )
                    }
                    effort != null && (rejectsEffort(e.message) || ModelErrorClassifier.rejectedParam(e) == "reasoning_effort") -> {
                        noEffort += effortKey()
                        ModelCapabilityRegistry.markParamUnsupported(endpoint.baseUrl, options.model, "reasoning_effort")
                        continue
                    }
                    ModelErrorClassifier.isStreamingRejection(e) && stream && droppedThisCall.add("stream") -> {
                        ModelCapabilityRegistry.markStreamingUnsupported(endpoint.baseUrl, options.model)
                        continue
                    }
                    ModelErrorClassifier.isToolsRejection(e) -> {
                        ModelCapabilityRegistry.markToolsUnsupported(endpoint.baseUrl, options.model)
                        throw e
                    }
                    else -> {
                        val badParam = ModelErrorClassifier.rejectedParam(e)
                        if (badParam != null && badParam != "tools" && droppedThisCall.add(badParam)) {
                            ModelCapabilityRegistry.markParamUnsupported(endpoint.baseUrl, options.model, badParam)
                            continue
                        }
                        throw e
                    }
                }
            }
        }
    }

    internal fun body(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        effort: String?,
        allowImages: Boolean = !ModelCapabilityRegistry.isPinnedTextOnly(options.model),
        stream: Boolean = true,
        profile: ModelCapabilityProfile = ModelCapabilityRegistry.profile(endpoint.baseUrl, options.model, optimisticVision = true),
    ): String =
        buildJsonObject {
            val effectiveAllowImages = allowImages && !ModelCapabilityRegistry.isPinnedTextOnly(options.model)
            val sanitizedInput = if (effectiveAllowImages) input else ModelCapabilityRegistry.stripImagesFromHistory(input)
            put("model", options.model)
            put("messages", messages(instructions, sanitizedInput, effectiveAllowImages))
            if (tools.isNotEmpty() && profile.supportsTools && profile.supportsParam("tools")) {
                put("tools", buildJsonArray { tools.forEach { add(it.toJson()) } })
            }
            put("stream", stream)
            if (stream && profile.supportsParam("stream_options")) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            effort?.let { put("reasoning_effort", it) }
        }.toString()

    private fun messages(instructions: String, input: List<InputItem>, allowImages: Boolean): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", instructions)
        })
        val grouped = Conversation.group(input, keepReasoning = { false }) { turn, call ->
            val id = turn.calls.last().callId
            callTurn[id] != null && callTurn[id] == callTurn[call.callId]
        }
        for (message in grouped) {
            when (message) {
                is Conversation.Assistant -> add(assistant(message))
                is Conversation.User -> {
                    // Tool messages answer the calls first; images cannot go in them, so they follow as a user message.
                    for (r in message.results) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.callId)
                            put("content", r.output)
                        })
                    }
                    if (message.parts.isNotEmpty()) add(user(message.parts, allowImages))
                }
            }
        }
    }

    private fun assistant(message: Conversation.Assistant) = buildJsonObject {
        put("role", "assistant")
        val text = message.texts.joinToString("\n\n")
        if (text.isEmpty()) put("content", null as String?) else put("content", text)
        if (message.calls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                for (call in message.calls) {
                    add(buildJsonObject {
                        put("id", call.callId)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        })
                        // Only Gemini's own data; another provider's (a Responses namespace) means nothing here.
                        call.extra?.takeIf { "google" in it }?.let { put("extra_content", it) }
                    })
                }
            })
        }
    }

    private fun user(parts: List<Conversation.Part>, allowImages: Boolean) = buildJsonObject {
        put("role", "user")
        val hasImages = allowImages && parts.any { it is Conversation.Part.Image }
        if (!hasImages) {
            val combined = buildString {
                for (part in parts) {
                    when (part) {
                        is Conversation.Part.Text -> {
                            if (isNotEmpty()) append("\n\n")
                            append(part.text)
                        }
                        is Conversation.Part.Context -> {
                            if (isNotEmpty()) append("\n\n")
                            append(Conversation.context(part.text))
                        }
                        is Conversation.Part.Image -> {
                            if (isNotEmpty()) append("\n")
                            append("[Screenshot omitted: model ${options.model} is text-only]")
                        }
                    }
                }
            }
            put("content", combined)
        } else {
            put("content", buildJsonArray {
                for (part in parts) {
                    add(when (part) {
                        is Conversation.Part.Text -> textPart(part.text)
                        is Conversation.Part.Context -> textPart(Conversation.context(part.text))
                        is Conversation.Part.Image -> buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:${part.image.mime};base64,${part.image.base64}") })
                        }
                    })
                }
            })
        }
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun ToolSpec.toJson() = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }

    private fun effortKey() = endpoint.baseUrl + "|" + options.model

    private fun rejectsEffort(message: String?): Boolean =
        message != null && (message.contains("reasoning_effort", ignoreCase = true) ||
            message.contains("reasoning effort", ignoreCase = true) || message.contains("reasoningEffort", ignoreCase = true))

    private suspend fun send(body: String, streaming: Boolean, onTextDelta: (String) -> Unit): ModelTurn {
        val normalizedBase = if (endpoint.baseUrl.endsWith('/')) endpoint.baseUrl else "${endpoint.baseUrl}/"
        val request = Request.Builder()
            .url(normalizedBase + "chat/completions")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            return withContext(Dispatchers.IO) { cancellable(request, streaming, onTextDelta) }
        } catch (e: ModelException) {
            if (e.status == 401 || e.status == 403) throw ModelException(e.status, endpoint.unauthorizedMessage, retryable = false)
            throw e
        }
    }

    private suspend fun cancellable(request: Request, streaming: Boolean, onTextDelta: (String) -> Unit): ModelTurn = coroutineScope {
        val call = http.newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            stream(call, streaming, Accumulator(onTextDelta))
        } finally {
            watcher.cancel()
        }
    }

    /** Blocking; run on an IO dispatcher. Cancelling the coroutine cancels the HTTP call. */
    private suspend fun stream(call: Call, streaming: Boolean, acc: Accumulator): ModelTurn {
        try {
            call.execute().use { response ->
                acc.firstByteNs = System.nanoTime()
                if (!response.isSuccessful) {
                    val text = try { response.body?.string().orEmpty() } catch (e: IOException) { "" }
                    val code = response.code
                    val retryAfter = NvidiaNimClient.retryAfterMs(response)
                    val nonRetryable = code == 400 || code == 401 || code == 403 || code == 404 || code == 422
                    throw ModelException(code, errorMessage(text), retryAfterMs = retryAfter, retryable = !nonRetryable)
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!streaming || "application/json" in contentType) {
                    val bodyText = response.body?.string().orEmpty()
                    return parseNonStreamingJson(bodyText, acc)
                }
                val source = response.body?.source() ?: throw ModelException(null, "stream ended early", failure = ModelFailure.STREAM_ENDED)
                val parser = SseParser()
                var done = false
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line()
                    val event = if (line == null) parser.feed("") else parser.feed(line)
                    if (event != null) done = acc.chunk(event.data)
                    if (line == null) break
                }
                if (!acc.finished) throw ModelException(null, "stream ended early", failure = ModelFailure.STREAM_ENDED)
                return acc.turn()
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a cancelled call surfaces as IOException
            throw ModelException(null, "Network error: ${e.message}", failure = ModelFailure.NETWORK)
        }
    }

    private fun parseNonStreamingJson(rawBody: String, acc: Accumulator): ModelTurn {
        val ok = acc.chunk(rawBody)
        if (acc.finished || ok) return acc.turn()
        // Treat complete JSON object with choices even without finish_reason as valid non-streaming answer.
        val parsed = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        if (parsed?.get("choices") != null) return acc.turn()
        throw ModelException(null, "Response missing choices", failure = ModelFailure.BAD_RESPONSE)
    }

    private fun errorMessage(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        // Google answers with a list of errors, xAI and OpenAI with one object.
        val root = (parsed as? JsonArray)?.firstOrNull() as? JsonObject ?: parsed as? JsonObject
        val error = root?.get("error")
        val message = (error as? JsonObject)?.string("message") ?: (error as? JsonPrimitive)?.contentOrNull
        return (message ?: body).take(ERROR_BODY_LIMIT)
    }

    /** One streamed response: text, calls accumulated by index, usage, timings. */
    private inner class Accumulator(private val onTextDelta: (String) -> Unit) {
        private val text = StringBuilder()
        private var hasText = false
        private val calls = sortedMapOf<Int, PartialCall>()
        private var usage: Usage? = null
        val startedNs = System.nanoTime()
        var firstByteNs = 0L
        private var firstOutputNs = 0L
        var finished = false
            private set

        /** Returns true on `[DONE]`. */
        fun chunk(data: String): Boolean {
            if (data.trim() == "[DONE]") {
                finished = true
                return true
            }
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return false
            (obj["error"] as? JsonObject)?.let { throw ModelException(null, it.string("message") ?: data.take(ERROR_BODY_LIMIT)) }
            (obj["usage"] as? JsonObject)?.let { u ->
                val inTok = u.int("prompt_tokens")
                val outTok = u.int("completion_tokens")
                val cached = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0
                if (inTok != null && outTok != null) usage = Usage(inTok, outTok, cached)
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
            val delta = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject
            if (delta != null) {
                delta.string("content")?.takeIf { it.isNotEmpty() }?.let {
                    output()
                    text.append(it)
                    hasText = true
                    onTextDelta(it)
                }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { position, element ->
                    output()
                    toolCall(element as? JsonObject ?: return@forEachIndexed, position)
                }
            }
            // Some backends end with finish_reason and usage but no [DONE].
            if (choice.string("finish_reason") != null) finished = true
            return false
        }

        private fun toolCall(c: JsonObject, position: Int) {
            val id = c.string("id")?.takeIf { it.isNotEmpty() }
            // Gemini may send whole calls without an index; a new id then starts a new call.
            val index = c.int("index") ?: when {
                id != null -> calls.entries.firstOrNull { it.value.id == id }?.key ?: (calls.keys.maxOrNull()?.plus(1) ?: position)
                else -> calls.keys.maxOrNull() ?: position
            }
            val partial = calls.getOrPut(index) { PartialCall() }
            if (id != null) partial.id = id
            (c["function"] as? JsonObject)?.let { f ->
                f.string("name")?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                f.string("arguments")?.let { partial.arguments.append(it) }
            }
            (c["extra_content"] as? JsonObject)?.let { partial.extra = it }
        }

        private fun output() {
            if (firstOutputNs == 0L) firstOutputNs = System.nanoTime()
        }

        private fun ms(ns: Long): Long? = if (ns == 0L) null else (ns - startedNs) / 1_000_000

        fun turn(): ModelTurn {
            val turn = turns.incrementAndGet()
            val out = calls.values.filter { it.name != null }.map { p ->
                val id = p.id ?: "call_" + UUID.randomUUID().toString().replace("-", "").take(24)
                callTurn[id] = turn
                InputItem.FunctionCall(id, p.name!!, p.arguments.toString().ifBlank { "{}" }, p.extra)
            }
            return ModelTurn(
                if (hasText) text.toString() else null, out, usage,
                firstByteMs = ms(firstByteNs), firstOutputMs = ms(firstOutputNs),
            )
        }
    }

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var extra: JsonObject? = null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ERROR_BODY_LIMIT = 500

        /** baseUrl|model pairs that rejected reasoning_effort; it is not sent to them again this session. */
        private val noEffort: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The ids a [Endpoint] lists at `GET models`, "models/" prefix removed. Blocking. */
        fun listModels(endpoint: Endpoint, apiKey: String, http: OkHttpClient): List<String> {
            val request = Request.Builder()
                .url(endpoint.baseUrl + "models")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code == 401 || response.code == 403) throw ModelException(response.code, endpoint.unauthorizedMessage)
                        throw ModelException(response.code, body.take(ERROR_BODY_LIMIT))
                    }
                    val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
                    return data.mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
                        .mapNotNull { it.contentOrNull?.removePrefix("models/")?.takeIf(String::isNotBlank) }
                        .distinct()
                }
            } catch (e: IOException) {
                throw ModelException(null, "Network error: ${e.message}", failure = ModelFailure.NETWORK)
            }
        }
    }
}
