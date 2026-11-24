package com.phoneuse.app

import com.phoneuse.model.ActiveModelStatus
import com.phoneuse.model.AgentRole
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelFallbackManager
import com.phoneuse.model.ModelHealthMonitor
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.ModelRouter
import com.phoneuse.model.NimConnectionResult
import com.phoneuse.model.NimProbeReport
import com.phoneuse.model.NimRequestConfig
import com.phoneuse.model.NvidiaModelRegistry
import com.phoneuse.model.NvidiaNimClient
import com.phoneuse.model.OrchestratedModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Central NVIDIA NIM multi-agent coordinator holding the shared [NvidiaModelRegistry],
 * [ModelHealthMonitor], [ModelRouter], and [ModelFallbackManager].
 */
object NvidiaOrchestrator {
    val registry = NvidiaModelRegistry()
    val health = ModelHealthMonitor()
    val router = ModelRouter(registry, health)

    @Volatile
    private var maxRetries = 2

    val fallbackManager = ModelFallbackManager(health)

    val activeStatus: StateFlow<ActiveModelStatus> get() = fallbackManager.activeStatus

    @Volatile
    var pointerEnabled: Boolean = true
        private set

    @Volatile
    var pointerLabels: Boolean = true
        private set

    suspend fun syncFromSettings(settings: SettingsStore) {
        maxRetries = settings.nvidiaMaxRetries.first()
        pointerEnabled = settings.nvidiaPointerEnabled.first()
        pointerLabels = settings.nvidiaPointerLabels.first()
        val overrides = settings.nvidiaRoleOverrides.first()
        registry.resetRoleAssignments()
        for ((roleName, pair) in overrides) {
            val role = AgentRole.entries.firstOrNull { it.name == roleName } ?: continue
            registry.setRoleAssignment(role, pair.first, pair.second)
        }
    }

    fun updatePointerConfig(enabled: Boolean, labels: Boolean) {
        pointerEnabled = enabled
        pointerLabels = labels
    }

    fun createClient(
        apiKey: String,
        options: ModelOptions,
        http: OkHttpClient,
        autoOrchestration: Boolean,
        requestConfig: NimRequestConfig,
    ): ModelClient {
        val manager = if (maxRetries == 2) fallbackManager else ModelFallbackManager(
            health = health,
            maxRetriesPerCandidate = maxRetries,
        )
        return OrchestratedModelClient(
            apiKey = apiKey,
            baseOptions = options,
            http = http,
            registry = registry,
            health = health,
            router = router,
            fallbackManager = manager,
            autoOrchestration = autoOrchestration,
            requestConfig = requestConfig,
        )
    }

    suspend fun testConnection(apiKey: String, http: OkHttpClient): NimConnectionResult {
        health.resetAuthErrors()
        val res = NvidiaNimClient.testConnection(apiKey, http)
        if (res.ok && res.sampleModels.isNotEmpty()) {
            // Also refresh full catalog if /models responded.
            runCatching { discoverAndProbe(apiKey, http, probeDefaultRoles = false) }
        }
        return res
    }

    suspend fun discoverAndProbe(
        apiKey: String,
        http: OkHttpClient,
        probeDefaultRoles: Boolean = true,
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val listed = NvidiaNimClient.listModels(apiKey, http).filter(ModelProviders::chatCapable)
        registry.updateDiscoveredCatalog(listed)
        val probeReports = mutableListOf<NimProbeReport>()
        if (probeDefaultRoles) {
            val targets = (registry.allAssignments()
                .flatMap { listOf(it.primaryModel, it.backupModel) } + NvidiaModelRegistry.VISION_BACKUP)
                .distinct()
                .filter { id -> listed.isEmpty() || id in listed }
            for (modelId in targets) {
                val cap = registry.capability(modelId)
                val report = NvidiaNimClient.probeModel(
                    apiKey = apiKey,
                    modelId = modelId,
                    http = http,
                    testTools = true,
                    testVision = cap.supportsVision && !cap.visionPinnedOff,
                    testStreaming = true,
                )
                registry.recordProbe(
                    modelId = modelId,
                    chatOk = report.chatOk,
                    toolsOk = report.toolsOk,
                    visionOk = report.visionOk,
                    streamingOk = report.streamingOk,
                    unsupportedParams = report.unsupportedParams,
                )
                health.markProbeResult(
                    modelId = modelId,
                    ok = report.chatOk,
                    error = report.error,
                    latencyMs = report.latencyMs,
                )
                probeReports += report
            }
        }
        DiscoveryResult(listedModels = listed, probes = probeReports)
    }

    fun reset() {
        registry.clearRuntimeState()
        health.reset()
    }

    data class DiscoveryResult(
        val listedModels: List<String>,
        val probes: List<NimProbeReport>,
    )
}
