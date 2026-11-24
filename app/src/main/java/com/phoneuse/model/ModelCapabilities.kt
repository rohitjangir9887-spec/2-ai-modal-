package com.phoneuse.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime-updated capability profile for a model at a given endpoint.
 * Covers Text, Vision, Tool calling, Streaming, and supported/unsupported request parameters.
 */
data class ModelCapabilityProfile(
    val modelId: String,
    val supportsText: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportedParams: Set<String> = DEFAULT_REQUEST_PARAMS,
    val unsupportedParams: Set<String> = emptySet(),
    val listedInAccount: Boolean? = null,
    val visionPinnedOff: Boolean = false,
) {
    fun canAcceptImages(visionEnabled: Boolean): Boolean {
        if (!visionEnabled || visionPinnedOff || ModelCapabilityRegistry.isPinnedTextOnly(modelId)) return false
        return supportsVision
    }

    fun supportsParam(param: String): Boolean = param !in unsupportedParams

    companion object {
        val DEFAULT_REQUEST_PARAMS: Set<String> = setOf(
            "temperature",
            "top_p",
            "max_tokens",
            "stream_options",
            "reasoning_effort",
            "tools",
        )
    }
}

/**
 * Shared thread-safe per-(endpoint, modelId) capability registry + full-history image sanitization helpers.
 * Guarantees `"nvidia/nemotron-3-ultra-550b-a55b"` and text-only models are always treated as TEXT ONLY
 * across every backend (`NvidiaNimClient`, `OrchestratedModelClient`, `ChatCompletionsClient`).
 */
object ModelCapabilityRegistry {
    const val OMITTED_IMAGE_MARKER = "[Screenshot omitted — using Accessibility UI tree]"

    private val PINNED_TEXT_ONLY_MODELS = setOf(
        "nvidia/nemotron-3-ultra-550b-a55b",
        "nvidia/nemotron-3-super-120b-a12b",
        "nvidia/nemotron-3-nano-30b-a3b",
    )

    private val KNOWN_VISION_MODELS = setOf(
        "nvidia/nemotron-3.5-super-vl-120b-a12b",
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
    )

    private val VISION_NAME_MARKERS = listOf(
        "-vl", "vl-", "vision", "omni", "multimodal", "neva", "kosmos", "paligemma", "pixtral",
        "gpt-4o", "gpt-4.1", "gpt-5", "o1", "o3", "o4", "gemini", "claude-3", "claude-sonnet-4", "claude-opus-4", "grok-2-vision", "grok-4",
    )

    private val profiles = ConcurrentHashMap<String, ModelCapabilityProfile>()

    fun isPinnedTextOnly(modelId: String): Boolean {
        val norm = modelId.trim().lowercase()
        if (norm in PINNED_TEXT_ONLY_MODELS) return true
        // Ultra 550B / Super 120B / Nano 30B non-VL variants are always text-only.
        return norm == "nvidia/nemotron-3-ultra-550b-a55b" ||
            (norm.contains("nemotron-3-ultra-550b") && !norm.contains("-vl") && !norm.contains("omni"))
    }

    fun isKnownTextOnlyPattern(modelId: String): Boolean {
        if (isPinnedTextOnly(modelId)) return true
        val norm = modelId.trim().lowercase()
        val hasVisionMarker = VISION_NAME_MARKERS.any { it in norm }
        if (hasVisionMarker) return false
        return listOf("nemotron", "deepseek", "qwen", "llama", "mistral", "mixtral")
            .any { it in norm }
    }

    fun inferDefaultVisionSupport(modelId: String): Boolean {
        if (isPinnedTextOnly(modelId)) return false
        val lower = modelId.trim().lowercase()
        if (lower in KNOWN_VISION_MODELS) return true
        return VISION_NAME_MARKERS.any { it in lower }
    }

    private fun key(baseUrl: String, modelId: String): String =
        "${baseUrl.trimEnd('/').lowercase()}|${modelId.trim().lowercase()}"

    fun profile(
        baseUrl: String,
        modelId: String,
        nimRegistry: NvidiaModelRegistry? = null,
        optimisticVision: Boolean = false,
    ): ModelCapabilityProfile {
        val k = key(baseUrl, modelId)
        val cached = profiles[k]
        val nimCap = nimRegistry?.capability(modelId)
        val pinnedTextOnly = isPinnedTextOnly(modelId) || (nimCap?.visionPinnedOff == true)
        val defaultVision = when {
            pinnedTextOnly -> false
            nimCap != null -> nimCap.supportsVision
            optimisticVision -> !isKnownTextOnlyPattern(modelId)
            else -> inferDefaultVisionSupport(modelId)
        }

        val base = cached ?: ModelCapabilityProfile(
            modelId = modelId,
            supportsText = nimCap?.supportsText ?: true,
            supportsVision = defaultVision,
            supportsTools = nimCap?.supportsTools ?: true,
            supportsStreaming = nimCap?.supportsStreaming ?: true,
            unsupportedParams = nimCap?.unsupportedParams ?: emptySet(),
            listedInAccount = nimCap?.listedInAccount,
            visionPinnedOff = pinnedTextOnly,
        )
        val probedVision = nimCap?.probedVision
        val mergedVision = when {
            pinnedTextOnly -> false
            probedVision != null -> probedVision && base.supportsVision
            else -> base.supportsVision
        }
        val probedTools = nimCap?.probedTools
        val mergedTools = when {
            probedTools != null -> probedTools && base.supportsTools
            else -> base.supportsTools
        }
        val probedStreaming = nimCap?.probedStreaming
        val mergedStreaming = when {
            probedStreaming != null -> probedStreaming && base.supportsStreaming
            else -> base.supportsStreaming
        }
        val mergedUnsupported = base.unsupportedParams + (nimCap?.unsupportedParams ?: emptySet())
        return base.copy(
            supportsVision = mergedVision,
            supportsTools = mergedTools,
            supportsStreaming = mergedStreaming,
            unsupportedParams = mergedUnsupported,
            supportedParams = ModelCapabilityProfile.DEFAULT_REQUEST_PARAMS - mergedUnsupported,
            listedInAccount = nimCap?.listedInAccount ?: base.listedInAccount,
            visionPinnedOff = pinnedTextOnly,
        )
    }

    fun markVisionUnsupported(baseUrl: String, modelId: String) {
        update(baseUrl, modelId) {
            it.copy(supportsVision = false)
        }
    }

    fun markVisionSupported(baseUrl: String, modelId: String, supported: Boolean) {
        if (isPinnedTextOnly(modelId)) return
        update(baseUrl, modelId) {
            it.copy(supportsVision = supported)
        }
    }

    fun markToolsUnsupported(baseUrl: String, modelId: String) {
        update(baseUrl, modelId) {
            it.copy(supportsTools = false, unsupportedParams = it.unsupportedParams + "tools")
        }
    }

    fun markStreamingUnsupported(baseUrl: String, modelId: String) {
        update(baseUrl, modelId) {
            it.copy(supportsStreaming = false)
        }
    }

    fun markParamUnsupported(baseUrl: String, modelId: String, param: String) {
        update(baseUrl, modelId) {
            val next = it.unsupportedParams + param
            it.copy(
                unsupportedParams = next,
                supportedParams = ModelCapabilityProfile.DEFAULT_REQUEST_PARAMS - next,
            )
        }
    }

    fun clear() {
        profiles.clear()
    }

    private fun update(baseUrl: String, modelId: String, transform: (ModelCapabilityProfile) -> ModelCapabilityProfile) {
        val k = key(baseUrl, modelId)
        profiles.compute(k) { _, existing ->
            val base = existing ?: ModelCapabilityProfile(
                modelId = modelId,
                supportsVision = inferDefaultVisionSupport(modelId),
                visionPinnedOff = isPinnedTextOnly(modelId),
            )
            val updated = transform(base)
            if (isPinnedTextOnly(modelId)) {
                updated.copy(supportsVision = false, visionPinnedOff = true)
            } else {
                updated
            }
        }
    }

    /** True if ANY message anywhere in [input] contains an image item or attached photo. */
    fun containsAnyImages(input: List<InputItem>): Boolean = input.any { item ->
        when (item) {
            is InputItem.UserImage -> true
            is InputItem.UserText -> item.images.isNotEmpty()
            else -> false
        }
    }

    /**
     * True if the latest observation turn (after the last FunctionOutput or UserText) carries images.
     * Old screenshots earlier in history do not require a vision model on later text turns.
     */
    fun hasRecentImages(input: List<InputItem>): Boolean {
        val recentObservation = input.indexOfLast {
            it is InputItem.FunctionOutput || it is InputItem.UserText
        }
        return input.drop(recentObservation.coerceAtLeast(0)).any { item ->
            when (item) {
                is InputItem.UserImage -> true
                is InputItem.UserText -> item.images.isNotEmpty()
                else -> false
            }
        }
    }

    /**
     * Strips every [InputItem.UserImage] AND every [InputItem.UserText.images] from the ENTIRE conversation
     * history so text-only models never receive multimodal `image_url` blocks from current or prior turns.
     */
    fun stripImagesFromHistory(
        input: List<InputItem>,
        omittedMarker: String = OMITTED_IMAGE_MARKER,
    ): List<InputItem> {
        if (!containsAnyImages(input)) return input
        val out = ArrayList<InputItem>(input.size)
        for (item in input) {
            when (item) {
                is InputItem.UserImage -> {
                    val prev = out.lastOrNull() as? InputItem.UserText
                    if (prev != null && omittedMarker in prev.text) {
                        // Already annotated an omitted image at this turn.
                    } else if (omittedMarker.isNotBlank()) {
                        out += InputItem.UserText(omittedMarker)
                    }
                }
                is InputItem.UserText -> {
                    if (item.images.isEmpty()) {
                        out += item
                    } else {
                        val cleaned = if (item.text.isBlank()) omittedMarker else item.text
                        out += item.copy(text = cleaned, images = emptyList())
                    }
                }
                else -> out += item
            }
        }
        return out
    }
}

/**
 * Classifies HTTP and model errors to drive safe capability adaptation, backup routing,
 * unsupported-parameter fallback, and multimodal-to-text recovery.
 * Never attempts `--enable-multimodal` parameters.
 */
object ModelErrorClassifier {
    const val CODE_MULTIMODAL_UNSUPPORTED = "multimodal_unsupported"

    private val MULTIMODAL_PHRASES = listOf(
        "multimodal processing is not enabled",
        "received multimodal data",
        "multimodal is not enabled",
        "multimodal not enabled",
        "does not support multimodal",
        "does not support image",
        "does not support images",
        "does not support vision",
        "vision is not enabled",
        "image_url is not supported",
        "unknown variant `image_url`",
        "invalid content type: image_url",
        "cannot process image",
        "input_image is not supported",
        "image inputs are not supported",
        "model is not multimodal",
        "expected string, got array",
    )

    private val UNAVAILABLE_PHRASES = listOf(
        "model not found",
        "does not exist",
        "unknown model",
        "not enabled",
        "model is currently unavailable",
        "no endpoints found",
        "permission denied for model",
    )

    fun isMultimodalRejection(e: ModelException): Boolean {
        if (e.status == 401 || e.status == 403) return false
        if (e.code == CODE_MULTIMODAL_UNSUPPORTED) return true
        if (e.status != null && e.status != 400 && e.status != 422) return false
        val msg = e.message?.lowercase().orEmpty()
        return MULTIMODAL_PHRASES.any { it in msg } ||
            ("multimodal" in msg && ("not enabled" in msg || "unsupported" in msg || "disabled" in msg || "error" in msg)) ||
            ("image_url" in msg && ("unsupported" in msg || "invalid" in msg || "unknown" in msg || "not allowed" in msg))
    }

    fun isStreamingRejection(e: ModelException): Boolean {
        if (e.status != 400 && e.status != 422) return false
        val msg = e.message?.lowercase().orEmpty()
        return ("stream" in msg && ("unsupported" in msg || "not supported" in msg || "must be false" in msg || "invalid" in msg)) ||
            "text/event-stream" in msg
    }

    fun isToolsRejection(e: ModelException): Boolean {
        if (e.status != 400 && e.status != 422) return false
        val msg = e.message?.lowercase().orEmpty()
        return ("tools" in msg || "tool_choice" in msg || "function calling" in msg) &&
            ("not supported" in msg || "unsupported" in msg || "not enabled" in msg || "unknown parameter" in msg)
    }

    fun rejectedParam(e: ModelException): String? {
        if (e.status != 400 && e.status != 422) return null
        val msg = e.message?.lowercase().orEmpty()
        val looksLikeParamRejection = listOf("unknown parameter", "extra inputs", "unsupported parameter", "unrecognized", "invalid parameter", "unexpected keyword")
            .any { it in msg } || ("parameter" in msg && ("not supported" in msg || "invalid" in msg))
        val paramOrder = listOf("stream_options", "reasoning_effort", "top_p", "temperature", "max_tokens", "tool_choice", "tools")
        for (param in paramOrder) {
            if (param in msg && (looksLikeParamRejection || param == "reasoning_effort" || param == "stream_options")) {
                return param
            }
        }
        if ("reasoning effort" in msg || "reasoningeffort" in msg) return "reasoning_effort"
        return null
    }

    fun isModelUnavailable(e: ModelException): Boolean {
        if (e.status == 404 || e.status == 410) return true
        if (e.status == 422) return !isMultimodalRejection(e)
        if (e.status == 400) {
            val msg = e.message?.lowercase().orEmpty()
            return UNAVAILABLE_PHRASES.any { it in msg } && !isMultimodalRejection(e)
        }
        return false
    }
}
