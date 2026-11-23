package com.phoneuse.model

import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drop-in [ModelClient] that routes each turn through [ModelRouter], [ModelFallbackManager],
 * and [NvidiaNimClient], preserving conversation state across failover and gating vision inputs safely.
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

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        val step = stepCounter.getAndIncrement()
        val plan = router.plan(
            RoutingContext(
                input = input,
                tools = tools,
                autoOrchestration = autoOrchestration,
                visionEnabled = requestConfig.visionEnabled,
                manualModel = baseOptions.model,
                stepIndex = step,
            ),
        )
        return fallbackManager.execute(
            role = plan.role,
            candidates = plan.candidates,
            routeReason = plan.reason,
            knownSecrets = listOf(apiKey),
        ) { modelId ->
            onRouteSelected?.invoke(fallbackManager.activeStatus.value)
            val effectiveInput = prepareInputForModel(input, modelId)
            val client = NvidiaNimClient(
                apiKey = apiKey,
                options = baseOptions.copy(model = modelId),
                http = http,
                baseUrl = baseUrl,
                config = requestConfig,
                registry = registry,
            )
            client.next(instructions, effectiveInput, tools, onTextDelta)
        }
    }

    internal fun prepareInputForModel(input: List<InputItem>, modelId: String): List<InputItem> {
        val cap = registry.capability(modelId)
        if (cap.canAcceptImages(requestConfig.visionEnabled)) return input
        return input.mapNotNull { item ->
            when (item) {
                is InputItem.UserImage -> null
                is InputItem.UserText -> if (item.images.isEmpty()) item else item.copy(images = emptyList())
                else -> item
            }
        }
    }
}
