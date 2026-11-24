package com.phoneuse.model

import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drop-in [ModelClient] that routes each turn through [ModelRouter], [ModelFallbackManager],
 * [VisionInterpreter], and [NvidiaNimClient], preserving conversation state across failover,
 * enforcing full-history image stripping for text-only models, and safely falling back to
 * text-only routing when multimodal models are unavailable on an account.
 */
class OrchestratedModelClient(
    private val apiKey: String,
    private val baseOptions: ModelOptions,
    private val http: OkHttpClient,
    private val registry: NvidiaModelRegistry,
    private val health: ModelHealthMonitor,
    private val router: ModelRouter,
    private val fallbackManager: ModelFallbackManager,
    private val autoOrchestration: Boolean,
    private val requestConfig: NimRequestConfig,
    private val baseUrl: String = NvidiaNimClient.DEFAULT_BASE_URL,
    private val onRouteSelected: ((ActiveModelStatus) -> Unit)? = null,
) : ModelClient {

    private val stepCounter = AtomicInteger(0)

    @Volatile
    private var sessionVisionBlocked = false

    private val visionInterpreter = VisionInterpreter(
        apiKey = apiKey,
        baseOptions = baseOptions,
        http = http,
        registry = registry,
        health = health,
        requestConfig = requestConfig,
        baseUrl = baseUrl,
    )

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        val step = stepCounter.getAndIncrement()
        val effectiveVisionEnabled = requestConfig.visionEnabled && !sessionVisionBlocked
        val plan = router.plan(
            RoutingContext(
                input = input,
                tools = tools,
                autoOrchestration = autoOrchestration,
                visionEnabled = effectiveVisionEnabled,
                manualModel = baseOptions.model,
                stepIndex = step,
            ),
        )

        var workingInput = input
        if (plan.needsVisionInterpretation && effectiveVisionEnabled) {
            workingInput = enrichWithVisionSummary(workingInput)
        }

        return try {
            executeCandidates(plan.role, plan.candidates, plan.reason, instructions, workingInput, tools, onTextDelta)
        } catch (e: ModelException) {
            if (e.status == 401 || e.status == 403 || !plan.requiresVision) throw e
            // All vision candidates failed (not enabled on API key or multimodal disabled):
            // attempt separate visual interpretation + text-only acting model, or pure text-only fallback.
            val summaryInput = enrichWithVisionSummary(input)
            val strippedInput = ModelCapabilityRegistry.stripImagesFromHistory(summaryInput)
            val hadVisualSummary = summaryInput !== input
            if (!hadVisualSummary) {
                sessionVisionBlocked = true
            }
            val textPlan = router.plan(
                RoutingContext(
                    input = strippedInput,
                    tools = tools,
                    autoOrchestration = autoOrchestration,
                    visionEnabled = false,
                    manualModel = baseOptions.model,
                    stepIndex = step,
                ),
            )
            val fallbackInput = if (hadVisualSummary) {
                strippedInput
            } else {
                strippedInput + InputItem.DeveloperText(
                    "Note: Vision model unavailable for this request (${e.message?.take(120)}); rely on the compressed Accessibility UI tree.",
                )
            }
            executeCandidates(
                role = textPlan.role,
                candidates = textPlan.candidates,
                reason = "text-fallback-from-vision",
                instructions = instructions,
                input = fallbackInput,
                tools = tools,
                onTextDelta = onTextDelta,
            )
        }
    }

    private suspend fun executeCandidates(
        role: AgentRole,
        candidates: List<String>,
        reason: String,
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn = fallbackManager.execute(
        role = role,
        candidates = candidates,
        routeReason = reason,
        knownSecrets = listOf(apiKey),
    ) { modelId ->
        onRouteSelected?.invoke(fallbackManager.activeStatus.value)
        val effectiveInput = prepareInputForModel(input, modelId)
        val client = NvidiaNimClient(
            apiKey = apiKey,
            options = baseOptions.copy(model = modelId),
            http = http,
            baseUrl = baseUrl,
            config = requestConfig.copy(visionEnabled = requestConfig.visionEnabled && !sessionVisionBlocked),
            registry = registry,
        )
        client.next(instructions, effectiveInput, tools, onTextDelta)
    }

    private suspend fun enrichWithVisionSummary(input: List<InputItem>): List<InputItem> {
        val goal = input.asReversed().firstNotNullOfOrNull { (it as? InputItem.UserText)?.text }.orEmpty()
        return when (val result = visionInterpreter.interpret(input, goal)) {
            is VisionInterpreter.Result.Summary -> {
                val stripped = ModelCapabilityRegistry.stripImagesFromHistory(input)
                stripped + InputItem.DeveloperText("[Visual Interpretation (${result.modelId})]\n${result.text}")
            }
            is VisionInterpreter.Result.Unavailable -> input
        }
    }

    internal fun prepareInputForModel(input: List<InputItem>, modelId: String): List<InputItem> {
        val cap = ModelCapabilityRegistry.profile(baseUrl, modelId, registry)
        if (cap.canAcceptImages(requestConfig.visionEnabled && !sessionVisionBlocked)) return input
        return ModelCapabilityRegistry.stripImagesFromHistory(
            input,
            omittedMarker = "[Screenshot omitted: active model $modelId is text-only or vision is disabled]",
        )
    }
}
