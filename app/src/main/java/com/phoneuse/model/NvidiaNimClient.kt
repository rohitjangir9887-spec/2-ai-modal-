package com.phoneuse.model

import com.phoneuse.i18n.tr
import com.phoneuse.security.SecretRedactor
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
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class NimRequestConfig(
    val streaming: Boolean = true,
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
    val topP: Double = 0.95,
    val timeoutSeconds: Int = 60,
    val visionEnabled: Boolean = true,
)

data class NimConnectionResult(
    val ok: Boolean,
    val statusCode: Int? = null,
    val discoveredCount: Int = 0,
    val sampleModels: List<String> = emptyList(),
    val latencyMs: Long = 0L,
    val message: String = "",
)

data class NimProbeReport(
    val modelId: String,
    val chatOk: Boolean,
    val toolsOk: Boolean? = null,
    val visionOk: Boolean? = null,
    val latencyMs: Long = 0L,
    val error: String? = null,
)

/**
 * Native NVIDIA NIM client (`https://integrate.api.nvidia.com/v1/`).
 * Supports streaming SSE + non-streaming JSON responses, partial `tool_calls` assembly,
 * malformed tool argument handling, image capability gating, `Retry-After` rate-limit parsing,
 * coroutine cancellation, model catalog listing, and model capability probing.
 */
class NvidiaNimClient(
    private val apiKey: String,
    private val options: ModelOptions,
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val config: NimRequestConfig = NimRequestConfig(),
    private val registry: NvidiaModelRegistry? = null,
) : ModelClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val normalizedBaseUrl = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
    private val callTurn = ConcurrentHashMap<String, Int>()
    private val turns = AtomicInteger()

    private fun scopedHttp(): OkHttpClient {
        val timeout = config.timeoutSeconds.coerceIn(10, 180).toLong()
        return if (http.readTimeoutMillis.toLong() == timeout * 1000L) {
            http
        } else {
            http.newBuilder()
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout((timeout + 15L).coerceAtMost(210L), TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        val cap = registry?.capability(options.model) ?: NvidiaModelRegistry.inferCapability(options.model)
        val allowImages = cap.canAcceptImages(config.visionEnabled)
        val effectiveTools = if (cap.supportsTools) tools else emptyList()
        val body = buildRequestBody(instructions, input, effectiveTools, allowImages, stream = config.streaming)
        return send(body, streaming = config.streaming, onTextDelta = onTextDelta)
    }

    internal fun buildRequestBody(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        allowImages: Boolean,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", options.model)
        put("messages", buildMessages(instructions, input, allowImages))
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray { tools.forEach { add(it.toJson()) } })
        }
        put("stream", stream)
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        put("temperature", config.temperature)
        put("top_p", config.topP)
        put("max_tokens", config.maxTokens)
    }.toString()

    private fun buildMessages(
        instructions: String,
        input: List<InputItem>,
        allowImages: Boolean,
    ): JsonArray = buildJsonArray {
        if (instructions.isNotBlank()) {
            add(buildJsonObject {
                put("role", "system")
                put("content", instructions)
            })
        }
        val grouped = Conversation.group(input, keepReasoning = { false }) { turn, call ->
            val id = turn.calls.last().callId
            callTurn[id] != null && callTurn[id] == callTurn[call.callId]
        }
        for (message in grouped) {
            when (message) {
                is Conversation.Assistant -> add(assistantMessage(message))
                is Conversation.User -> {
                    for (r in message.results) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.callId)
                            put("content", r.output)
                        })
                    }
                    if (message.parts.isNotEmpty()) {
                        add(userMessage(message.parts, allowImages))
                    }
                }
            }
        }
    }

    private fun assistantMessage(message: Conversation.Assistant) = buildJsonObject {
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
                            put("arguments", sanitizeArgumentsJson(call.argumentsJson))
                        })
                    })
                }
            })
        }
    }

    private fun userMessage(parts: List<Conversation.Part>, allowImages: Boolean) = buildJsonObject {
        put("role", "user")
        val hasUsableImages = allowImages && parts.any { it is Conversation.Part.Image }
        if (!hasUsableImages) {
            val combinedText = buildString {
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
                            append("[Screenshot omitted: active model ${options.model} is text-only or vision is disabled]")
                        }
                    }
                }
            }
            put("content", combinedText)
        } else {
            put("content", buildJsonArray {
                for (part in parts) {
                    when (part) {
                        is Conversation.Part.Text -> add(textPart(part.text))
                        is Conversation.Part.Context -> add(textPart(Conversation.context(part.text)))
                        is Conversation.Part.Image -> add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:${part.image.mime};base64,${part.image.base64}")
                            })
                        })
                    }
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

    private suspend fun send(
        body: String,
        streaming: Boolean,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        if (apiKey.isBlank()) {
            throw ModelException(
                status = 401,
                message = tr("Enter your NVIDIA NIM API key in Settings.", "설정에서 NVIDIA NIM API 키를 입력해 주세요."),
                retryable = false,
            )
        }
        val request = Request.Builder()
            .url("${normalizedBaseUrl}chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return withContext(Dispatchers.IO) {
            cancellable(request, streaming, onTextDelta)
        }
    }

    private suspend fun cancellable(
        request: Request,
        streaming: Boolean,
        onTextDelta: (String) -> Unit,
    ): ModelTurn = coroutineScope {
        val call = scopedHttp().newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            execute(call, streaming, Accumulator(onTextDelta))
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun execute(
        call: Call,
        streaming: Boolean,
        acc: Accumulator,
    ): ModelTurn {
        try {
            call.execute().use { response ->
                acc.firstByteNs = System.nanoTime()
                if (!response.isSuccessful) {
                    val rawBody = try { response.body?.string().orEmpty() } catch (_: IOException) { "" }
                    throw httpException(response, rawBody)
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!streaming || "application/json" in contentType) {
                    val bodyText = response.body?.string().orEmpty()
                    return acc.parseFullJson(bodyText)
                }
                val source = response.body?.source()
                    ?: throw ModelException(null, "stream ended early", failure = ModelFailure.STREAM_ENDED)
                val parser = SseParser()
                var done = false
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line()
                    val event = if (line == null) parser.feed("") else parser.feed(line)
                    if (event != null) {
                        done = acc.chunk(event.data)
                    }
                    if (line == null) break
                }
                if (!acc.finished && acc.hasAnyOutput()) {
                    // Some proxies close cleanly after choice without trailing [DONE].
                    return acc.turn()
                }
                if (!acc.finished) {
                    throw ModelException(null, "stream ended early", failure = ModelFailure.STREAM_ENDED)
                }
                return acc.turn()
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ModelException(null, "Network error: ${redact(e.message.orEmpty())}", failure = ModelFailure.NETWORK)
        }
    }

    private fun httpException(response: Response, rawBody: String): ModelException {
        val code = response.code
        val cleanMsg = errorMessage(rawBody)
        val retryAfter = retryAfterMs(response)
        val errCode = errorCode(rawBody)
        val nonRetryableAuth = code == 401 || code == 403
        val nonRetryableBadReq = code == 400 || code == 404 || code == 422
        val message = when (code) {
            401, 403 -> tr("Check your NVIDIA NIM API key (${cleanMsg.ifBlank { "HTTP $code" }})", "NVIDIA NIM API 키를 확인해 주세요 (${cleanMsg.ifBlank { "HTTP $code" }})")
            else -> cleanMsg.ifBlank { "HTTP $code" }
        }
        return ModelException(
            status = code,
            message = message,
            retryAfterMs = retryAfter,
            code = errCode,
            retryable = !nonRetryableAuth && !nonRetryableBadReq,
        )
    }

    private fun errorMessage(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val root = (parsed as? JsonArray)?.firstOrNull() as? JsonObject ?: parsed as? JsonObject
        val error = root?.get("error")
        val msg = (error as? JsonObject)?.string("message")
            ?: (error as? JsonPrimitive)?.contentOrNull
            ?: root?.string("detail")
            ?: root?.string("title")
        return redact((msg ?: body).take(ERROR_BODY_LIMIT))
    }

    private fun errorCode(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val error = root["error"] as? JsonObject
        return error?.string("code") ?: error?.string("type") ?: root.string("code")
    }

    private fun redact(value: String): String =
        SecretRedactor.redact(value, listOf(apiKey))

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

        fun hasAnyOutput(): Boolean = hasText || calls.isNotEmpty()

        fun parseFullJson(rawBody: String): ModelTurn {
            val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
                ?: throw ModelException(null, "Malformed JSON response", failure = ModelFailure.BAD_RESPONSE)
            (obj["error"] as? JsonObject)?.let { err ->
                throw ModelException(null, redact(err.string("message") ?: rawBody.take(ERROR_BODY_LIMIT)))
            }
            parseUsage(obj)
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw ModelException(null, "Response missing choices", failure = ModelFailure.BAD_RESPONSE)
            val msg = choice["message"] as? JsonObject ?: choice["delta"] as? JsonObject
            if (msg != null) {
                msg.string("content")?.takeIf { it.isNotEmpty() }?.let {
                    output()
                    text.append(it)
                    hasText = true
                    onTextDelta(it)
                }
                (msg["tool_calls"] as? JsonArray)?.forEachIndexed { pos, elem ->
                    output()
                    toolCall(elem as? JsonObject ?: return@forEachIndexed, pos)
                }
            }
            finished = true
            return turn()
        }

        fun chunk(data: String): Boolean {
            if (data.trim() == "[DONE]") {
                finished = true
                return true
            }
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return false
            (obj["error"] as? JsonObject)?.let { err ->
                throw ModelException(null, redact(err.string("message") ?: data.take(ERROR_BODY_LIMIT)))
            }
            parseUsage(obj)
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
            if (choice.string("finish_reason") != null) finished = true
            return false
        }

        private fun parseUsage(obj: JsonObject) {
            (obj["usage"] as? JsonObject)?.let { u ->
                val inTok = u.int("prompt_tokens")
                val outTok = u.int("completion_tokens")
                val cached = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0
                if (inTok != null && outTok != null) usage = Usage(inTok, outTok, cached)
            }
        }

        private fun toolCall(c: JsonObject, position: Int) {
            val id = c.string("id")?.takeIf { it.isNotEmpty() }
            val index = c.int("index") ?: when {
                id != null -> calls.entries.firstOrNull { it.value.id == id }?.key ?: (calls.keys.maxOrNull()?.plus(1) ?: position)
                else -> calls.keys.maxOrNull() ?: position
            }
            val partial = calls.getOrPut(index) { PartialCall() }
            if (id != null) partial.id = id
            (c["function"] as? JsonObject)?.let { f ->
                f.string("name")?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                val rawArgs = f["arguments"]
                when (rawArgs) {
                    is JsonPrimitive -> partial.arguments.append(rawArgs.content)
                    is JsonObject -> {
                        if (partial.arguments.isEmpty()) partial.arguments.append(rawArgs.toString())
                    }
                    else -> Unit
                }
            }
        }

        private fun output() {
            if (firstOutputNs == 0L) firstOutputNs = System.nanoTime()
        }

        private fun ms(ns: Long): Long? = if (ns == 0L) null else (ns - startedNs) / 1_000_000

        fun turn(): ModelTurn {
            val turn = turns.incrementAndGet()
            val out = calls.values.filter { !it.name.isNullOrBlank() }.map { p ->
                val id = p.id ?: "call_" + UUID.randomUUID().toString().replace("-", "").take(24)
                callTurn[id] = turn
                InputItem.FunctionCall(
                    callId = id,
                    name = p.name!!.trim(),
                    argumentsJson = sanitizeArgumentsJson(p.arguments.toString()),
                )
            }
            return ModelTurn(
                text = if (hasText) text.toString() else null,
                calls = out,
                usage = usage,
                firstByteMs = ms(firstByteNs),
                firstOutputMs = ms(firstOutputNs),
            )
        }
    }

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1/"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ERROR_BODY_LIMIT = 500

        /** 1x1 transparent PNG in base64 for lightweight vision endpoint verification. */
        private const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

        /**
         * Ensures streamed tool arguments remain valid JSON when possible; if the model emitted truncated or
         * malformed JSON without a valid object, converts cleanly into a valid `{}` or preserves object text so
         * ToolExecutor/ModelRetry can reject bad arguments predictably instead of crashing JSON parsers.
         */
        internal fun sanitizeArgumentsJson(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            if (parsed is JsonObject) return trimmed
            // Handle double-encoded JSON string arguments emitted by some providers.
            if (parsed is JsonPrimitive && parsed.isString) {
                val inner = runCatching { Json.parseToJsonElement(parsed.content) }.getOrNull()
                if (inner is JsonObject) return inner.toString()
            }
            // Attempt minimal closing brace recovery if truncated right before last brace.
            if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
                val candidate = "$trimmed}"
                if (runCatching { Json.parseToJsonElement(candidate) }.getOrNull() is JsonObject) {
                    return candidate
                }
            }
            return "{}"
        }

        /** Parses `retry-after-ms`, `Retry-After` (seconds or HTTP-date), and `x-ratelimit-reset-*`. */
        internal fun retryAfterMs(response: Response, nowMs: Long = System.currentTimeMillis()): Long? {
            response.header("retry-after-ms")?.toDoubleOrNull()?.let { ms ->
                if (ms > 0) return ms.toLong().coerceAtLeast(1L)
            }
            response.header("Retry-After")?.trim()?.let { raw ->
                raw.toDoubleOrNull()?.let { sec ->
                    if (sec >= 0) return (sec * 1000.0).toLong().coerceAtLeast(1L)
                }
                runCatching {
                    val millis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
                    if (millis > 0) return millis
                }
            }
            for (h in listOf("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens")) {
                response.header(h)?.trim()?.let { raw ->
                    parseDurationOrEpochMs(raw, nowMs)?.let { return it }
                }
            }
            return null
        }

        private fun parseDurationOrEpochMs(raw: String, nowMs: Long): Long? {
            raw.removeSuffix("ms").takeIf { raw.endsWith("ms") }?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(1L) }
            raw.removeSuffix("s").takeIf { raw.endsWith("s") }?.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(1L) }
            raw.toLongOrNull()?.let { n ->
                val ms = if (n > 1_000_000_000L) n * 1000L - nowMs else n * 1000L
                if (ms > 0) return ms
            }
            runCatching {
                val ms = Instant.parse(raw).toEpochMilli() - nowMs
                if (ms > 0) return ms
            }
            return null
        }

        fun listModels(
            apiKey: String,
            http: OkHttpClient,
            baseUrl: String = DEFAULT_BASE_URL,
        ): List<String> {
            val base = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
            if (apiKey.isBlank()) {
                throw ModelException(401, tr("Enter your NVIDIA NIM API key first.", "NVIDIA NIM API 키를 먼저 입력해 주세요."), retryable = false)
            }
            val request = Request.Builder()
                .url("${base}models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val clean = SecretRedactor.redact(body.take(ERROR_BODY_LIMIT), listOf(apiKey))
                        if (response.code == 401 || response.code == 403) {
                            throw ModelException(response.code, tr("Check your NVIDIA NIM API key", "NVIDIA NIM API 키를 확인해 주세요"), retryable = false)
                        }
                        throw ModelException(response.code, clean)
                    }
                    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
                    val data = root["data"] as? JsonArray ?: return emptyList()
                    return data.mapNotNull { (it as? JsonObject)?.get("id") as? JsonPrimitive }
                        .mapNotNull { it.contentOrNull?.takeIf(String::isNotBlank) }
                        .distinct()
                }
            } catch (e: IOException) {
                throw ModelException(null, "Network error: ${SecretRedactor.redact(e.message.orEmpty(), listOf(apiKey))}", failure = ModelFailure.NETWORK)
            }
        }

        suspend fun testConnection(
            apiKey: String,
            http: OkHttpClient,
            baseUrl: String = DEFAULT_BASE_URL,
            fallbackProbeModel: String = NvidiaModelRegistry.FAST_PRIMARY,
        ): NimConnectionResult = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            if (apiKey.isBlank()) {
                return@withContext NimConnectionResult(
                    ok = false,
                    statusCode = 401,
                    message = tr("API key is empty", "API 키가 비어 있어요"),
                )
            }
            try {
                val models = listModels(apiKey, http, baseUrl)
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (models.isNotEmpty()) {
                    NimConnectionResult(
                        ok = true,
                        statusCode = 200,
                        discoveredCount = models.size,
                        sampleModels = models.take(8),
                        latencyMs = elapsed,
                        message = tr("Connected · ${models.size} models discovered", "연결됨 · 모델 ${models.size}개 확인"),
                    )
                } else {
                    // Catalog empty on proxy -> verify directly with a lightweight completion probe.
                    val probe = probeModel(apiKey, fallbackProbeModel, http, baseUrl, testTools = false, testVision = false)
                    val totalMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    NimConnectionResult(
                        ok = probe.chatOk,
                        statusCode = if (probe.chatOk) 200 else null,
                        discoveredCount = 0,
                        latencyMs = totalMs,
                        message = if (probe.chatOk) {
                            tr("Connected via direct probe ($fallbackProbeModel)", "직접 테스트로 연결 확인 ($fallbackProbeModel)")
                        } else {
                            probe.error ?: tr("Connection failed", "연결 실패")
                        },
                    )
                }
            } catch (e: ModelException) {
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                NimConnectionResult(
                    ok = false,
                    statusCode = e.status,
                    latencyMs = elapsed,
                    message = SecretRedactor.redact(e.message ?: "HTTP ${e.status}", listOf(apiKey)),
                )
            }
        }

        suspend fun probeModel(
            apiKey: String,
            modelId: String,
            http: OkHttpClient,
            baseUrl: String = DEFAULT_BASE_URL,
            testTools: Boolean = true,
            testVision: Boolean = false,
        ): NimProbeReport = withContext(Dispatchers.IO) {
            val client = NvidiaNimClient(
                apiKey = apiKey,
                options = ModelOptions(model = modelId, fast = false, effort = "low"),
                http = http,
                baseUrl = baseUrl,
                config = NimRequestConfig(streaming = false, maxTokens = 32, temperature = 0.0, timeoutSeconds = 25, visionEnabled = testVision),
            )
            val start = System.currentTimeMillis()
            val chatOk = try {
                client.next(
                    instructions = "Reply with OK.",
                    input = listOf(InputItem.UserText("ping")),
                    tools = emptyList(),
                )
                true
            } catch (e: ModelException) {
                return@withContext NimProbeReport(
                    modelId = modelId,
                    chatOk = false,
                    latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                    error = SecretRedactor.redact(e.message ?: "HTTP ${e.status}", listOf(apiKey)),
                )
            }
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)

            val toolsOk = if (!testTools) null else {
                val pingTool = ToolSpec(
                    name = "ping_check",
                    description = "Diagnostic ping",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { })
                    },
                )
                try {
                    client.next(
                        instructions = "You may call ping_check or answer OK.",
                        input = listOf(InputItem.UserText("say ok")),
                        tools = listOf(pingTool),
                    )
                    true
                } catch (_: ModelException) {
                    false
                }
            }

            val visionOk = if (!testVision) null else {
                try {
                    val img = InputItem.UserImage(TINY_PNG_BASE64, "image/png")
                    val body = client.buildRequestBody(
                        instructions = "Answer with one word.",
                        input = listOf(InputItem.UserText("What is this?", images = listOf(img))),
                        tools = emptyList(),
                        allowImages = true,
                        stream = false,
                    )
                    client.send(body, streaming = false) {}
                    true
                } catch (_: ModelException) {
                    false
                }
            }

            NimProbeReport(
                modelId = modelId,
                chatOk = chatOk,
                toolsOk = toolsOk,
                visionOk = visionOk,
                latencyMs = latency,
            )
        }
    }
}
