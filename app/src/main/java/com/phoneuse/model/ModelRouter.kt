package com.phoneuse.model

data class RoutingContext(
    val input: List<InputItem>,
    val tools: List<ToolSpec>,
    val autoOrchestration: Boolean,
    val visionEnabled: Boolean,
    val manualModel: String,
    val stepIndex: Int = 0,
)

data class RoutePlan(
    val role: AgentRole,
    val reason: String,
    val requiresVision: Boolean,
    val candidates: List<String>,
    val needsVisionInterpretation: Boolean = false,
)

/**
 * Selects the single appropriate specialist role ([AgentRole]) and ordered candidate models for each turn.
 * Never lets multiple models act simultaneously on the phone or route images to text-only models.
 */
class ModelRouter(
    private val registry: NvidiaModelRegistry,
    private val health: ModelHealthMonitor,
) {
    fun plan(ctx: RoutingContext): RoutePlan {
        val hasImages = containsImages(ctx.input)
        val userText = latestUserText(ctx.input)
        val hasToolFailures = hasRecentToolError(ctx.input)

        val manualIsTextOnly = !ctx.autoOrchestration &&
            ctx.manualModel.isNotBlank() &&
            !registry.capability(ctx.manualModel).canAcceptImages(ctx.visionEnabled)

        if (manualIsTextOnly) {
            val candidates = buildCandidates(
                role = AgentRole.PHONE_ACTION,
                requiresVision = false,
                autoOrchestration = false,
                manualModel = ctx.manualModel,
            )
            val wantsVisualSummary = hasImages && ctx.visionEnabled &&
                registry.visionCandidates(requireTools = false).isNotEmpty()
            return RoutePlan(
                role = AgentRole.PHONE_ACTION,
                reason = if (wantsVisualSummary) "manual-text-with-vision-summary" else "manual-pinned",
                requiresVision = false,
                candidates = candidates,
                needsVisionInterpretation = wantsVisualSummary,
            )
        }

        val hasToolVisionModel = if (hasImages && ctx.visionEnabled) {
            registry.visionCandidates(requireTools = ctx.tools.isNotEmpty()).isNotEmpty()
        } else {
            false
        }

        val (role, reason) = chooseRole(
            autoOrchestration = ctx.autoOrchestration,
            visionEnabled = ctx.visionEnabled,
            hasImages = hasImages && hasToolVisionModel,
            userText = userText,
            tools = ctx.tools,
            hasToolFailures = hasToolFailures,
            stepIndex = ctx.stepIndex,
        )

        val requiresVision = hasImages && ctx.visionEnabled && role == AgentRole.VISION && hasToolVisionModel
        val needsInterpretation = hasImages && ctx.visionEnabled && !requiresVision &&
            registry.visionCandidates(requireTools = false).isNotEmpty()

        val candidates = buildCandidates(
            role = role,
            requiresVision = requiresVision,
            autoOrchestration = ctx.autoOrchestration,
            manualModel = ctx.manualModel,
        )
        return RoutePlan(
            role = role,
            reason = reason,
            requiresVision = requiresVision,
            candidates = candidates,
            needsVisionInterpretation = needsInterpretation,
        )
    }

    private fun chooseRole(
        autoOrchestration: Boolean,
        visionEnabled: Boolean,
        hasImages: Boolean,
        userText: String,
        tools: List<ToolSpec>,
        hasToolFailures: Boolean,
        stepIndex: Int,
    ): Pair<AgentRole, String> {
        if (!autoOrchestration) {
            return if (hasImages && visionEnabled) {
                AgentRole.VISION to "manual-vision"
            } else {
                AgentRole.PHONE_ACTION to "manual-pinned"
            }
        }
        // Images present and vision allowed: always prefer verified vision specialists.
        if (hasImages && visionEnabled) {
            return AgentRole.VISION to "screenshot-or-image"
        }
        val lower = userText.lowercase()
        val hasPhoneTools = tools.any { it.name in PHONE_TOOL_NAMES }
        val isCoding = CODING_KEYWORDS.any { it in lower }
        if (isCoding && stepIndex == 0 && !hasPhoneTools) {
            return AgentRole.CODING to "coding-or-technical"
        }
        if (isCoding && hasCodeIntent(lower)) {
            return AgentRole.CODING to "coding-or-technical"
        }
        if (hasToolFailures && stepIndex >= 2) {
            return AgentRole.MANAGER to "recovery-replan"
        }
        if (tools.isEmpty() || (!hasPhoneTools && isSimpleFastPrompt(lower))) {
            return AgentRole.FAST to "quick-response"
        }
        if (hasPhoneTools) {
            // Complex initial planning uses Manager on turn 0 if explicitly multi-step, while ongoing taps stay on Phone Action.
            return if (stepIndex == 0 && isComplexPlan(lower)) {
                AgentRole.MANAGER to "multi-step-plan"
            } else {
                AgentRole.PHONE_ACTION to "phone-control"
            }
        }
        if (isComplexPlan(lower)) {
            return AgentRole.MANAGER to "complex-reasoning"
        }
        return AgentRole.FAST to "general-fast"
    }

    internal fun buildCandidates(
        role: AgentRole,
        requiresVision: Boolean,
        autoOrchestration: Boolean,
        manualModel: String,
    ): List<String> {
        val raw = LinkedHashSet<String>()
        if (!autoOrchestration && manualModel.isNotBlank()) {
            raw.add(manualModel)
        }
        val assignment = registry.assignment(role)
        raw.add(assignment.primaryModel)
        raw.add(assignment.backupModel)

        if (!requiresVision) {
            val phone = registry.assignment(AgentRole.PHONE_ACTION)
            val fast = registry.assignment(AgentRole.FAST)
            val manager = registry.assignment(AgentRole.MANAGER)
            raw.add(phone.primaryModel)
            raw.add(phone.backupModel)
            raw.add(fast.primaryModel)
            raw.add(manager.primaryModel)
        } else {
            val vision = registry.assignment(AgentRole.VISION)
            raw.add(vision.primaryModel)
            raw.add(vision.backupModel)
            registry.visionCandidates(requireTools = false).forEach { raw.add(it) }
        }

        val ordered = raw.filter { it.isNotBlank() }
        val filtered = if (requiresVision) {
            ordered.filter { id ->
                val cap = registry.capability(id)
                cap.canAcceptImages(visionEnabled = true) && cap.isEligible()
            }.ifEmpty {
                ordered.filter { id -> registry.capability(id).canAcceptImages(visionEnabled = true) }
            }.sortedByDescending { id ->
                val cap = registry.capability(id)
                var score = 0
                if (cap.probedVision == true) score += 4
                if (cap.listedInAccount == true) score += 2
                if (cap.probedTools == true) score += 1
                score
            }
        } else {
            ordered.filter { id -> registry.capability(id).isEligible() }
                .ifEmpty { ordered }
        }

        // Partition into healthy-now first, cooling-down later (never exclude all candidates if every circuit is open).
        val availableNow = filtered.filter { health.isAvailable(it) }
        val coolingDown = filtered.filterNot { health.isAvailable(it) }
        return (availableNow + coolingDown).ifEmpty {
            listOf(assignment.primaryModel, assignment.backupModel).distinct()
        }
    }

    /**
     * Only the most recent observation determines whether the NEXT model call needs vision.
     * Old screenshot attachments remain in the conversation for context, but must not force
     * every subsequent phone action back onto an expensive vision model. A new image that
     * follows a tool result still selects vision; a later text-only result returns to phone action.
     */
    private fun containsImages(input: List<InputItem>): Boolean =
        ModelCapabilityRegistry.hasRecentImages(input)

    private fun latestUserText(input: List<InputItem>): String =
        input.asReversed().firstNotNullOfOrNull { item ->
            when (item) {
                is InputItem.UserText -> item.text
                else -> null
            }
        }.orEmpty()

    private fun hasRecentToolError(input: List<InputItem>): Boolean =
        input.takeLast(4).any { item ->
            item is InputItem.FunctionOutput &&
                (item.output.contains("\"ok\":false") ||
                    item.output.contains("ERROR:", ignoreCase = true) ||
                    item.output.contains("failed", ignoreCase = true) ||
                    item.output.contains("stale", ignoreCase = true))
        }

    private fun isComplexPlan(lower: String): Boolean =
        lower.length >= 140 || COMPLEX_KEYWORDS.any { it in lower }

    private fun isSimpleFastPrompt(lower: String): Boolean =
        lower.length < 140 && !COMPLEX_KEYWORDS.any { it in lower } && !CODING_KEYWORDS.any { it in lower }

    private fun hasCodeIntent(lower: String): Boolean =
        lower.contains("```") || CODE_STRONG_KEYWORDS.any { it in lower }

    companion object {
        private val PHONE_TOOL_NAMES = setOf(
            "tap", "click", "long_press", "type_text", "type", "swipe", "scroll",
            "pinch", "rotate", "two_finger_swipe", "open_app", "open_uri",
            "get_screen", "screen_read", "screenshot", "batch", "press_key", "wait",
        )
        private val CODING_KEYWORDS = listOf("kotlin", "java", "python", "gradle", "debug", "stacktrace", "stack trace", "compile", "syntax", "github", "git ", "code", "bug", "refactor", "regex", "sql", "json", "script")
        private val CODE_STRONG_KEYWORDS = listOf("debug", "stacktrace", "compile", "refactor", "write code", "fix bug", "kotlin", "python", "gradle", "github")
        private val COMPLEX_KEYWORDS = listOf("compare", "analyze", "research", "plan", "workflow", "multiple", "step by step", "strategy", "architecture", "summarize", "explain why", "and then", "after that")
    }
}
