package com.phoneuse.model

import com.phoneuse.security.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class ModelHealthState {
    UNKNOWN,
    READY,
    DEGRADED,
    UNAVAILABLE,
    AUTH_ERROR,
}

data class ModelHealthSnapshot(
    val modelId: String,
    val state: ModelHealthState = ModelHealthState.UNKNOWN,
    val consecutiveFailures: Int = 0,
    val totalCalls: Int = 0,
    val failedCalls: Int = 0,
    val ewmaLatencyMs: Long = 0L,
    val lastFirstOutputMs: Long? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val openUntilMs: Long = 0L,
    val lastError: String? = null,
    val lastUpdatedMs: Long = 0L,
) {
    fun isCircuitOpen(nowMs: Long): Boolean = openUntilMs > nowMs
}

/**
 * Thread-safe health & circuit-breaker tracker across NVIDIA NIM models.
 * Ensures temporarily broken or rate-limited models back off cleanly instead of stalling every step.
 */
class ModelHealthMonitor(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val baseCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
) {
    private val states = ConcurrentHashMap<String, ModelHealthSnapshot>()
    private val _snapshots = MutableStateFlow<Map<String, ModelHealthSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ModelHealthSnapshot>> = _snapshots.asStateFlow()

    fun snapshot(modelId: String): ModelHealthSnapshot =
        states[modelId] ?: ModelHealthSnapshot(modelId = modelId)

    fun allSnapshots(): List<ModelHealthSnapshot> =
        states.values.sortedBy { it.modelId }

    fun isAvailable(modelId: String): Boolean {
        val snap = states[modelId] ?: return true
        if (snap.state == ModelHealthState.AUTH_ERROR) return false
        return !snap.isCircuitOpen(clock())
    }

    fun recordSuccess(
        modelId: String,
        latencyMs: Long,
        firstOutputMs: Long? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ) {
        val now = clock()
        states.compute(modelId) { _, cur ->
            val prev = cur ?: ModelHealthSnapshot(modelId)
            val newEwma = if (prev.ewmaLatencyMs <= 0L) {
                latencyMs.coerceAtLeast(1L)
            } else {
                ((prev.ewmaLatencyMs * 7 + latencyMs.coerceAtLeast(1L) * 3) / 10)
            }
            prev.copy(
                state = ModelHealthState.READY,
                consecutiveFailures = 0,
                totalCalls = prev.totalCalls + 1,
                ewmaLatencyMs = newEwma,
                lastFirstOutputMs = firstOutputMs ?: prev.lastFirstOutputMs,
                inputTokens = prev.inputTokens + inputTokens.coerceAtLeast(0),
                outputTokens = prev.outputTokens + outputTokens.coerceAtLeast(0),
                openUntilMs = 0L,
                lastError = null,
                lastUpdatedMs = now,
            )
        }
        publish()
    }

    fun recordFailure(
        modelId: String,
        status: Int?,
        rawError: String?,
        retryAfterMs: Long? = null,
        knownSecrets: Collection<String> = emptyList(),
    ) {
        val now = clock()
        val cleanError = rawError?.let { SecretRedactor.redact(it, knownSecrets).take(240) }
        states.compute(modelId) { _, cur ->
            val prev = cur ?: ModelHealthSnapshot(modelId)
            val fails = prev.consecutiveFailures + 1
            val authErr = status == 401 || status == 403
            val cooldown = when {
                authErr -> now + maxCooldownMs
                retryAfterMs != null && retryAfterMs > 0L -> now + retryAfterMs.coerceAtMost(maxCooldownMs)
                fails >= failureThreshold -> {
                    val shift = (fails - failureThreshold).coerceIn(0, 4)
                    val span = (baseCooldownMs shl shift).coerceAtMost(maxCooldownMs)
                    now + span
                }
                else -> 0L
            }
            val nextState = when {
                authErr -> ModelHealthState.AUTH_ERROR
                cooldown > now || status == 404 || status == 422 -> ModelHealthState.UNAVAILABLE
                else -> ModelHealthState.DEGRADED
            }
            prev.copy(
                state = nextState,
                consecutiveFailures = fails,
                totalCalls = prev.totalCalls + 1,
                failedCalls = prev.failedCalls + 1,
                openUntilMs = cooldown,
                lastError = cleanError,
                lastUpdatedMs = now,
            )
        }
        publish()
    }

    fun markProbeResult(modelId: String, ok: Boolean, error: String? = null, latencyMs: Long = 0L) {
        if (ok) {
            recordSuccess(modelId, latencyMs = latencyMs)
        } else {
            val now = clock()
            states.compute(modelId) { _, cur ->
                val prev = cur ?: ModelHealthSnapshot(modelId)
                prev.copy(
                    state = ModelHealthState.UNAVAILABLE,
                    openUntilMs = now + baseCooldownMs,
                    lastError = error?.let { SecretRedactor.redact(it).take(240) },
                    lastUpdatedMs = now,
                )
            }
            publish()
        }
    }

    fun reset() {
        states.clear()
        publish()
    }

    fun resetAuthErrors() {
        val now = clock()
        for ((id, snap) in states) {
            if (snap.state == ModelHealthState.AUTH_ERROR) {
                states[id] = snap.copy(
                    state = ModelHealthState.UNKNOWN,
                    consecutiveFailures = 0,
                    openUntilMs = 0L,
                    lastError = null,
                    lastUpdatedMs = now,
                )
            }
        }
        publish()
    }

    private fun publish() {
        _snapshots.update { HashMap(states) }
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
    }
}
