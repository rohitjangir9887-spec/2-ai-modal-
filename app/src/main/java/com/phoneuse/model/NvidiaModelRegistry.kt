package com.phoneuse.model

import com.phoneuse.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class AgentRole {
    MANAGER,
    VISION,
    PHONE_ACTION,
    CODING,
    FAST;

    val displayName: String
        get() = when (this) {
            MANAGER -> tr("Manager", "매니저")
            VISION -> tr("Vision", "비전")
            PHONE_ACTION -> tr("Phone Action", "폰 조작")
            CODING -> tr("Coding", "코딩")
            FAST -> tr("Fast", "빠른 응답")
        }
}

data class RoleAssignment(
    val role: AgentRole,
    val primaryModel: String,
    val backupModel: String,
)

data class NvidiaModelCapability(
    val id: String,
    val displayName: String,
    val supportsText: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val isReasoning: Boolean = false,
    val isFastTier: Boolean = false,
    val contextWindow: Int = 131_072,
    val listedInAccount: Boolean? = null,
    val probedChat: Boolean? = null,
    val probedTools: Boolean? = null,
    val probedVision: Boolean? = null,
    val probedStreaming: Boolean? = null,
    val unsupportedParams: Set<String> = emptySet(),
    val visionPinnedOff: Boolean = ModelCapabilityRegistry.isPinnedTextOnly(id),
) {
    val supportedParams: Set<String>
        get() = ModelCapabilityProfile.DEFAULT_REQUEST_PARAMS - unsupportedParams

    fun supportsParam(param: String): Boolean = param !in unsupportedParams

    /** True when vision is allowed, not text-pinned, and configured or verified via probe. */
    fun canAcceptImages(visionEnabled: Boolean): Boolean {
        if (!visionEnabled || visionPinnedOff || ModelCapabilityRegistry.isPinnedTextOnly(id)) return false
        if (probedVision != null) return probedVision
        return supportsVision
    }

    /** True if account listing doesn't explicitly exclude it and basic chat probe didn't fail. */
    fun isEligible(): Boolean {
        if (listedInAccount == false && probedChat != true) return false
        if (probedChat == false) return false
        return true
    }
}

/**
 * Central registry for NVIDIA NIM multi-agent specialists, runtime catalog discovery,
 * per-model capability verification (text, vision, tools, streaming, request parameters),
 * and user overrides.
 */
class NvidiaModelRegistry {
    private val capabilities = ConcurrentHashMap<String, NvidiaModelCapability>()
    private val roleOverrides = ConcurrentHashMap<AgentRole, RoleAssignment>()

    private val _catalogVersion = MutableStateFlow(0L)
    val catalogVersion: StateFlow<Long> = _catalogVersion.asStateFlow()

    @Volatile
    var discoveredModels: List<String> = emptyList()
        private set

    init {
        DEFAULT_CAPABILITIES.forEach { capabilities[it.id] = it }
    }

    fun assignment(role: AgentRole): RoleAssignment =
        roleOverrides[role] ?: DEFAULT_ASSIGNMENTS.getValue(role)

    fun allAssignments(): List<RoleAssignment> = AgentRole.entries.map { assignment(it) }

    fun setRoleAssignment(role: AgentRole, primary: String, backup: String) {
        val def = DEFAULT_ASSIGNMENTS.getValue(role)
        val p = primary.trim().ifEmpty { def.primaryModel }
        val b = backup.trim().ifEmpty { def.backupModel }
        roleOverrides[role] = RoleAssignment(role, p, b)
        ensureRegistered(p)
        ensureRegistered(b)
        _catalogVersion.update { it + 1 }
    }

    fun resetRoleAssignments() {
        roleOverrides.clear()
        _catalogVersion.update { it + 1 }
    }

    fun capability(modelId: String): NvidiaModelCapability {
        val existing = capabilities[modelId]
        val base = existing ?: inferCapability(modelId)
        return if (ModelCapabilityRegistry.isPinnedTextOnly(modelId)) {
            base.copy(supportsVision = false, visionPinnedOff = true)
        } else {
            base
        }
    }

    fun knownModelIds(): List<String> =
        (DEFAULT_CAPABILITIES.map { it.id } + discoveredModels + roleOverrides.values.flatMap { listOf(it.primaryModel, it.backupModel) })
            .distinct()

    fun updateDiscoveredCatalog(listedIds: List<String>) {
        discoveredModels = listedIds.distinct()
        val listedSet = discoveredModels.toSet()
        for ((id, cur) in capabilities) {
            capabilities[id] = cur.copy(listedInAccount = id in listedSet)
        }
        for (id in discoveredModels) {
            val existing = capabilities[id]
            if (existing == null) {
                capabilities[id] = inferCapability(id).copy(listedInAccount = true)
            } else {
                capabilities[id] = existing.copy(listedInAccount = true)
            }
        }
        _catalogVersion.update { it + 1 }
    }

    fun recordProbe(
        modelId: String,
        chatOk: Boolean,
        toolsOk: Boolean?,
        visionOk: Boolean?,
        streamingOk: Boolean? = null,
        unsupportedParams: Set<String> = emptySet(),
    ) {
        val current = capability(modelId)
        val pinnedOff = current.visionPinnedOff || ModelCapabilityRegistry.isPinnedTextOnly(modelId)
        val effectiveVision = if (pinnedOff) false else (visionOk ?: current.supportsVision)
        capabilities[modelId] = current.copy(
            probedChat = chatOk,
            probedTools = toolsOk ?: current.probedTools,
            probedVision = if (pinnedOff) false else (visionOk ?: current.probedVision),
            probedStreaming = streamingOk ?: current.probedStreaming,
            supportsVision = effectiveVision,
            supportsTools = toolsOk ?: current.supportsTools,
            supportsStreaming = streamingOk ?: current.supportsStreaming,
            unsupportedParams = current.unsupportedParams + unsupportedParams,
            visionPinnedOff = pinnedOff,
        )
        _catalogVersion.update { it + 1 }
    }

    fun markVisionUnsupported(modelId: String) {
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            probedVision = false,
            supportsVision = false,
        )
        _catalogVersion.update { it + 1 }
    }

    fun markVisionVerified(modelId: String, supported: Boolean) {
        if (ModelCapabilityRegistry.isPinnedTextOnly(modelId)) return
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            probedVision = supported,
            supportsVision = supported,
        )
        _catalogVersion.update { it + 1 }
    }

    fun markToolsUnsupported(modelId: String) {
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            probedTools = false,
            supportsTools = false,
            unsupportedParams = current.unsupportedParams + "tools",
        )
        _catalogVersion.update { it + 1 }
    }

    fun markStreamingUnsupported(modelId: String) {
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            probedStreaming = false,
            supportsStreaming = false,
        )
        _catalogVersion.update { it + 1 }
    }

    fun markParamUnsupported(modelId: String, param: String) {
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            unsupportedParams = current.unsupportedParams + param,
        )
        _catalogVersion.update { it + 1 }
    }

    fun markUnavailable(modelId: String) {
        val current = capability(modelId)
        capabilities[modelId] = current.copy(
            probedChat = false,
            listedInAccount = false,
        )
        _catalogVersion.update { it + 1 }
    }

    /**
     * Ordered vision candidates filtered by real availability & capabilities, preferring verified/listed
     * vision models (including [VISION_BACKUP] `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`).
     */
    fun visionCandidates(requireTools: Boolean = false): List<String> {
        val visionRole = assignment(AgentRole.VISION)
        val raw = (listOf(visionRole.primaryModel, visionRole.backupModel, VISION_PRIMARY, VISION_BACKUP) + knownModelIds())
            .filter { it.isNotBlank() }
            .distinct()
        return raw.filter { id ->
            val cap = capability(id)
            cap.canAcceptImages(visionEnabled = true) &&
                cap.isEligible() &&
                (!requireTools || (cap.supportsTools && cap.probedTools != false))
        }.sortedByDescending { id ->
            val cap = capability(id)
            var score = 0
            if (cap.probedVision == true) score += 8
            if (cap.listedInAccount == true) score += 4
            if (cap.probedChat == true) score += 2
            if (!requireTools || cap.probedTools == true) score += 1
            score
        }
    }

    fun clearRuntimeState() {
        discoveredModels = emptyList()
        capabilities.clear()
        DEFAULT_CAPABILITIES.forEach { capabilities[it.id] = it }
        _catalogVersion.update { it + 1 }
    }

    private fun ensureRegistered(modelId: String) {
        if (modelId.isNotBlank() && !capabilities.containsKey(modelId)) {
            capabilities[modelId] = inferCapability(modelId)
        }
    }

    companion object {
        const val MANAGER_PRIMARY = "nvidia/nemotron-3-ultra-550b-a55b"
        const val MANAGER_BACKUP = "nvidia/nemotron-3-super-120b-a12b"

        const val VISION_PRIMARY = "nvidia/nemotron-3.5-super-vl-120b-a12b"
        const val VISION_BACKUP = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"

        const val PHONE_PRIMARY = "nvidia/nemotron-3-super-120b-a12b"
        const val PHONE_BACKUP = "nvidia/nemotron-3-nano-30b-a3b"

        const val CODING_PRIMARY = "nvidia/nemotron-3-ultra-550b-a55b"
        const val CODING_BACKUP = "nvidia/nemotron-3-super-120b-a12b"

        const val FAST_PRIMARY = "nvidia/nemotron-3-nano-30b-a3b"
        const val FAST_BACKUP = "nvidia/nemotron-3-super-120b-a12b"

        val DEFAULT_ASSIGNMENTS: Map<AgentRole, RoleAssignment> = mapOf(
            AgentRole.MANAGER to RoleAssignment(AgentRole.MANAGER, MANAGER_PRIMARY, MANAGER_BACKUP),
            AgentRole.VISION to RoleAssignment(AgentRole.VISION, VISION_PRIMARY, VISION_BACKUP),
            AgentRole.PHONE_ACTION to RoleAssignment(AgentRole.PHONE_ACTION, PHONE_PRIMARY, PHONE_BACKUP),
            AgentRole.CODING to RoleAssignment(AgentRole.CODING, CODING_PRIMARY, CODING_BACKUP),
            AgentRole.FAST to RoleAssignment(AgentRole.FAST, FAST_PRIMARY, FAST_BACKUP),
        )

        val DEFAULT_CAPABILITIES: List<NvidiaModelCapability> = listOf(
            NvidiaModelCapability(
                id = MANAGER_PRIMARY,
                displayName = "Nemotron 3 Ultra 550B",
                supportsText = true,
                supportsVision = false,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = true,
                isFastTier = false,
                contextWindow = 262_144,
                visionPinnedOff = true,
            ),
            NvidiaModelCapability(
                id = VISION_PRIMARY,
                displayName = "Nemotron 3.5 Super VL 120B",
                supportsText = true,
                supportsVision = true,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = true,
                isFastTier = false,
                contextWindow = 131_072,
            ),
            NvidiaModelCapability(
                id = VISION_BACKUP,
                displayName = "Nemotron 3 Nano Omni 30B",
                supportsText = true,
                supportsVision = true,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = true,
                isFastTier = true,
                contextWindow = 131_072,
            ),
            NvidiaModelCapability(
                id = PHONE_PRIMARY,
                displayName = "Nemotron 3 Super 120B",
                supportsText = true,
                supportsVision = false,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = true,
                isFastTier = false,
                contextWindow = 131_072,
                visionPinnedOff = true,
            ),
            NvidiaModelCapability(
                id = FAST_PRIMARY,
                displayName = "Nemotron 3 Nano 30B",
                supportsText = true,
                supportsVision = false,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = false,
                isFastTier = true,
                contextWindow = 131_072,
                visionPinnedOff = true,
            ),
        )

        val PRESET_IDS: List<String> = DEFAULT_CAPABILITIES.map { it.id }

        fun inferCapability(id: String): NvidiaModelCapability {
            val lower = id.lowercase()
            val pinnedOff = ModelCapabilityRegistry.isPinnedTextOnly(id)
            val vision = !pinnedOff && listOf("-vl", "vl-", "vision", "omni", "multimodal", "neva", "kosmos", "paligemma", "pixtral", "4o", "gemini")
                .any { it in lower }
            val fast = listOf("nano", "mini", "flash", "8b", "9b", "7b", "30b", "lite", "instant")
                .any { it in lower }
            val reasoning = listOf("ultra", "reasoning", "think", "r1", "super")
                .any { it in lower }
            return NvidiaModelCapability(
                id = id,
                displayName = id.substringAfterLast('/'),
                supportsText = true,
                supportsVision = vision,
                supportsTools = true,
                supportsStreaming = true,
                isReasoning = reasoning,
                isFastTier = fast,
                visionPinnedOff = pinnedOff,
            )
        }
    }
}
