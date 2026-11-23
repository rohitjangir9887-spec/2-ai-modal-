package com.phoneuse.model

import com.phoneuse.security.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class ActiveModelStatus(
    val role: AgentRole = AgentRole.MANAGER,
    val modelId: String = "",
    val fallbackFrom: String? = null,
    val reason: String = "",
    val updatedAtMs: Long = 0L,
)

/**
 * Executes a model turn with bounded exponential backoff + jitter, `Retry-After` respect,
 * and automatic primary-to-backup / cross-specialist failover while keeping conversation state intact.
 */
class ModelFallbackManager(
    private val health: ModelHealthMonitor,
    private val maxRetriesPerCandidate: Int = 2,
    private val baseBackoffMs: Long = 450L,
    private val maxBackoffMs: Long = 12_000L,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _activeStatus = MutableStateFlow(ActiveModelStatus())
    val activeStatus: StateFlow<ActiveModelStatus> = _activeStatus.asStateFlow()

    suspend fun execute(
        role: AgentRole,
        candidates: List<String>,
        routeReason: String,
        knownSecrets: Collection<String> = emptyList(),
        invoker: suspend (modelId: String) -> ModelTurn,
    ): ModelTurn {
        val uniqueCandidates = candidates.distinct()
        require(uniqueCandidates.isNotEmpty()) { "Candidate list must not be empty" }

        var lastException: ModelException? = null
        val primary = uniqueCandidates.first()

        for (candidate in uniqueCandidates) {
            val fallbackFrom = if (candidate == primary) null else primary
            _activeStatus.value = ActiveModelStatus(
                role = role,
                modelId = candidate,
                fallbackFrom = fallbackFrom,
                reason = if (fallbackFrom == null) routeReason else "fallback-from-$fallbackFrom",
                updatedAtMs = clock(),
            )

            var attempt = 0
            while (attempt <= maxRetriesPerCandidate) {
                val start = clock()
                try {
                    val turn = invoker(candidate)
                    val elapsed = (clock() - start).coerceAtLeast(1L)
                    health.recordSuccess(
                        modelId = candidate,
                        latencyMs = elapsed,
                        firstOutputMs = turn.firstOutputMs,
                        inputTokens = turn.usage?.inputTokens ?: 0,
                        outputTokens = turn.usage?.outputTokens ?: 0,
                    )
                    return turn
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelException) {
                    health.recordFailure(
                        modelId = candidate,
                        status = e.status,
                        rawError = e.message,
                        retryAfterMs = e.retryAfterMs,
                        knownSecrets = knownSecrets,
                    )
                    lastException = sanitizeException(e, knownSecrets)

                    // Authentication failures (401/403) apply to the account/key — fail fast, never retry or loop.
                    if (e.status == 401 || e.status == 403) {
                        throw lastException
                    }
                    // Context length overflow: do not burn quota retrying the exact same payload on same model.
                    if (isContextOverflow(e)) {
                        break
                    }
                    // Stream already sent partial tokens to the user: do not silently replay or double-act on same candidate.
                    if (e.afterOutput) {
                        throw lastException
                    }
                    if (!e.retryable && e.status != 404 && e.status != 429 && e.status !in 500..599) {
                        // Bad model/request for this specific model -> fail over to backup immediately.
                        break
                    }
                    if (e.status == 404 || e.status == 422) {
                        // Model missing or disabled on this account -> skip straight to next candidate.
                        break
                    }

                    val canRetrySame = attempt < maxRetriesPerCandidate && (e.status == null || e.status == 429 || e.status in 500..599)
                    if (!canRetrySame) break

                    // On 429 with huge Retry-After when a backup exists, fail over right away instead of stalling the user.
                    val requestedWait = e.retryAfterMs
                    if (e.status == 429 && uniqueCandidates.size > 1 && (requestedWait == null || requestedWait > 4_000L)) {
                        break
                    }

                    val waitMs = computeDelayMs(attempt, requestedWait)
                    sleeper(waitMs)
                    attempt++
                }
            }
        }
        throw lastException ?: ModelException(null, "All NVIDIA NIM candidate models failed")
    }

    internal fun computeDelayMs(attempt: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null && retryAfterMs > 0L) {
            return retryAfterMs.coerceIn(50L, maxBackoffMs)
        }
        val exp = (baseBackoffMs shl attempt.coerceIn(0, 5)).coerceAtMost(maxBackoffMs)
        val jitter = random.nextLong(0L, (exp / 3L).coerceAtLeast(1L) + 1L)
        return (exp + jitter).coerceAtMost(maxBackoffMs)
    }

    private fun isContextOverflow(e: ModelException): Boolean {
        val c = e.code?.lowercase().orEmpty()
        val m = e.message?.lowercase().orEmpty()
        return "context_length" in c || "context length" in m || "maximum context" in m
    }

    private fun sanitizeException(e: ModelException, secrets: Collection<String>): ModelException {
        val clean = SecretRedactor.redact(e.message ?: "Model error", secrets)
        return if (clean == e.message) e else ModelException(
            status = e.status,
            message = clean,
            retryAfterMs = e.retryAfterMs,
            code = e.code,
            afterOutput = e.afterOutput,
            retryable = e.retryable,
            failure = e.failure,
        )
    }
}
