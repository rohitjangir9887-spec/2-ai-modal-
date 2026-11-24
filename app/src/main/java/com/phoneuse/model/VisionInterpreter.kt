package com.phoneuse.model

import okhttp3.OkHttpClient

/**
 * Separate vision interpretation helper using available NVIDIA vision specialists
 * (e.g. `nvidia/nemotron-3.5-super-vl-120b-a12b` or `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`).
 *
 * When the active acting model is text-only (such as `nvidia/nemotron-3-ultra-550b-a55b`) or a vision
 * model lacks tool calling, this helper extracts a concise visual UI summary from screenshots so the
 * text-only model can act reliably without ever receiving multimodal `image_url` blocks.
 */
class VisionInterpreter(
    private val apiKey: String,
    private val baseOptions: ModelOptions,
    private val http: OkHttpClient,
    private val registry: NvidiaModelRegistry,
    private val health: ModelHealthMonitor,
    private val requestConfig: NimRequestConfig,
    private val baseUrl: String = NvidiaNimClient.DEFAULT_BASE_URL,
) {
    sealed interface Result {
        data class Summary(val text: String, val modelId: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend fun interpret(
        input: List<InputItem>,
        userGoal: String,
    ): Result {
        if (!requestConfig.visionEnabled || apiKey.isBlank()) {
            return Result.Unavailable("Vision interpretation disabled")
        }
        val latestImages = extractLatestImages(input)
        if (latestImages.isEmpty()) {
            return Result.Unavailable("No images to interpret")
        }
        val candidates = candidateModels()
        if (candidates.isEmpty()) {
            return Result.Unavailable("No vision-capable model available")
        }

        val promptText = buildString {
            append("Analyze the attached screenshot(s) for a phone automation agent.")
            if (userGoal.isNotBlank()) {
                append("\nUser goal: ").append(userGoal.take(300))
            }
            append("\nSummarize in 3-6 concise bullet points:")
            append("\n1. Active app/screen state, visible contact/chat header, dialogs, popups, or keyboard state.")
            append("\n2. Important visual content missing from accessibility text (unlabeled icons, checkmark/sent status, badge colors, thumbnails, charts).")
            append("\n3. Visible numbered tags [n] or exact controls matching the goal.")
        }
        val visionInput = listOf(InputItem.UserText(promptText, latestImages))

        var lastError = "No vision model succeeded"
        for (modelId in candidates) {
            if (!registry.capability(modelId).canAcceptImages(visionEnabled = true)) continue
            val client = NvidiaNimClient(
                apiKey = apiKey,
                options = baseOptions.copy(model = modelId, fast = false, webSearch = false),
                http = http,
                baseUrl = baseUrl,
                config = requestConfig.copy(streaming = false, maxTokens = 320, temperature = 0.1, visionEnabled = true),
                registry = registry,
            )
            try {
                val started = System.currentTimeMillis()
                val turn = client.next(
                    instructions = "You are a concise visual UI inspector. Describe only what is visible on screen; never invent UI elements.",
                    input = visionInput,
                    tools = emptyList(),
                    onTextDelta = {},
                )
                val summary = turn.text?.trim().orEmpty()
                if (summary.isNotEmpty()) {
                    val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                    health.recordSuccess(
                        modelId = modelId,
                        latencyMs = elapsed,
                        firstOutputMs = turn.firstOutputMs,
                        inputTokens = turn.usage?.inputTokens ?: 0,
                        outputTokens = turn.usage?.outputTokens ?: 0,
                    )
                    registry.markVisionVerified(modelId, true)
                    return Result.Summary(summary, modelId)
                }
            } catch (e: ModelException) {
                health.recordFailure(modelId, e.status, e.message, e.retryAfterMs, listOf(apiKey))
                when {
                    ModelErrorClassifier.isMultimodalRejection(e) -> {
                        registry.markVisionUnsupported(modelId)
                        ModelCapabilityRegistry.markVisionUnsupported(baseUrl, modelId)
                    }
                    ModelErrorClassifier.isModelUnavailable(e) -> {
                        registry.markUnavailable(modelId)
                    }
                    e.status == 401 || e.status == 403 -> {
                        return Result.Unavailable(e.message ?: "Authentication failed")
                    }
                }
                lastError = e.message ?: "HTTP ${e.status}"
            }
        }
        return Result.Unavailable(lastError)
    }

    internal fun candidateModels(): List<String> =
        registry.visionCandidates(requireTools = false)
            .filter { health.isAvailable(it) }
            .ifEmpty { registry.visionCandidates(requireTools = false) }

    companion object {
        internal fun extractLatestImages(input: List<InputItem>): List<InputItem.UserImage> {
            val recentObservation = input.indexOfLast {
                it is InputItem.FunctionOutput || it is InputItem.UserText
            }
            val recent = input.drop(recentObservation.coerceAtLeast(0))
            val out = ArrayList<InputItem.UserImage>()
            for (item in recent) {
                when (item) {
                    is InputItem.UserImage -> out += item
                    is InputItem.UserText -> out += item.images
                    else -> Unit
                }
            }
            return out
        }
    }
}
