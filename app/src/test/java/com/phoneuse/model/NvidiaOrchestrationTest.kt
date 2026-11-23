package com.phoneuse.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NvidiaOrchestrationTest {
    private val phoneTools = listOf(
        ToolSpec("tap", "Tap UI element", buildJsonObject { put("type", "object") }),
        ToolSpec("scroll", "Scroll screen", buildJsonObject { put("type", "object") }),
    )

    @Test
    fun defaultRoleAssignmentsMatchRequiredModels() {
        val reg = NvidiaModelRegistry()
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", reg.assignment(AgentRole.MANAGER).primaryModel)
        assertEquals("nvidia/nemotron-3-super-120b-a12b", reg.assignment(AgentRole.MANAGER).backupModel)

        assertEquals("nvidia/nemotron-3.5-super-vl-120b-a12b", reg.assignment(AgentRole.VISION).primaryModel)
        assertEquals("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", reg.assignment(AgentRole.VISION).backupModel)

        assertEquals("nvidia/nemotron-3-super-120b-a12b", reg.assignment(AgentRole.PHONE_ACTION).primaryModel)
        assertEquals("nvidia/nemotron-3-nano-30b-a3b", reg.assignment(AgentRole.PHONE_ACTION).backupModel)

        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", reg.assignment(AgentRole.CODING).primaryModel)
        assertEquals("nvidia/nemotron-3-super-120b-a12b", reg.assignment(AgentRole.CODING).backupModel)

        assertEquals("nvidia/nemotron-3-nano-30b-a3b", reg.assignment(AgentRole.FAST).primaryModel)
        assertEquals("nvidia/nemotron-3-super-120b-a12b", reg.assignment(AgentRole.FAST).backupModel)
    }

    @Test
    fun routerSelectsSpecialistsWithoutSendingImagesToTextModels() {
        val reg = NvidiaModelRegistry()
        val health = ModelHealthMonitor()
        val router = ModelRouter(reg, health)

        // Simple prompt -> FAST
        val fastPlan = router.plan(
            RoutingContext(
                input = listOf(InputItem.UserText("What is 2+2?")),
                tools = emptyList(),
                autoOrchestration = true,
                visionEnabled = true,
                manualModel = NvidiaModelRegistry.MANAGER_PRIMARY,
            ),
        )
        assertEquals(AgentRole.FAST, fastPlan.role)
        assertEquals(NvidiaModelRegistry.FAST_PRIMARY, fastPlan.candidates.first())

        // Coding prompt -> CODING
        val codingPlan = router.plan(
            RoutingContext(
                input = listOf(InputItem.UserText("Debug this Kotlin stacktrace and compile error")),
                tools = emptyList(),
                autoOrchestration = true,
                visionEnabled = true,
                manualModel = NvidiaModelRegistry.FAST_PRIMARY,
            ),
        )
        assertEquals(AgentRole.CODING, codingPlan.role)
        assertEquals(NvidiaModelRegistry.CODING_PRIMARY, codingPlan.candidates.first())

        // Screenshot/image present + visionEnabled -> VISION only (no text-only candidates)
        val visionPlan = router.plan(
            RoutingContext(
                input = listOf(InputItem.UserText("Tap the cart icon", listOf(InputItem.UserImage("AQID")))),
                tools = phoneTools,
                autoOrchestration = true,
                visionEnabled = true,
                manualModel = NvidiaModelRegistry.FAST_PRIMARY,
            ),
        )
        assertEquals(AgentRole.VISION, visionPlan.role)
        assertTrue(visionPlan.requiresVision)
        assertEquals(NvidiaModelRegistry.VISION_PRIMARY, visionPlan.candidates.first())
        assertFalse(visionPlan.candidates.contains(NvidiaModelRegistry.FAST_PRIMARY))
        assertFalse(visionPlan.candidates.contains(NvidiaModelRegistry.PHONE_PRIMARY))

        // Mid-task simple phone control -> PHONE_ACTION
        val actionPlan = router.plan(
            RoutingContext(
                input = listOf(InputItem.UserText("Open Settings")),
                tools = phoneTools,
                autoOrchestration = true,
                visionEnabled = true,
                manualModel = NvidiaModelRegistry.FAST_PRIMARY,
                stepIndex = 1,
            ),
        )
        assertEquals(AgentRole.PHONE_ACTION, actionPlan.role)

        // Repeated tool error triggers recovery planning on MANAGER
        val recoveryPlan = router.plan(
            RoutingContext(
                input = listOf(
                    InputItem.UserText("Book ride"),
                    InputItem.FunctionOutput("call_1", "{\"ok\":false,\"error\":\"failed\"}"),
                ),
                tools = phoneTools,
                autoOrchestration = true,
                visionEnabled = true,
                manualModel = NvidiaModelRegistry.PHONE_PRIMARY,
                stepIndex = 3,
            ),
        )
        assertEquals(AgentRole.MANAGER, recoveryPlan.role)
    }

    @Test
    fun circuitBreakerOpensAfterRepeatedFailuresAndRecoversAfterCooldown() {
        var now = 1_000L
        val health = ModelHealthMonitor(
            clock = { now },
            failureThreshold = 2,
            baseCooldownMs = 10_000L,
            maxCooldownMs = 60_000L,
        )
        val model = NvidiaModelRegistry.PHONE_PRIMARY
        assertTrue(health.isAvailable(model))

        health.recordFailure(model, 503, "Service unavailable")
        assertTrue(health.isAvailable(model))
        assertEquals(ModelHealthState.DEGRADED, health.snapshot(model).state)

        health.recordFailure(model, 503, "Still down")
        assertFalse(health.isAvailable(model))
        assertEquals(ModelHealthState.UNAVAILABLE, health.snapshot(model).state)

        // Advance past 10s cooldown -> half-open eligible again
        now += 10_001L
        assertTrue(health.isAvailable(model))

        health.recordSuccess(model, latencyMs = 350L, firstOutputMs = 120L, inputTokens = 50, outputTokens = 20)
        assertEquals(ModelHealthState.READY, health.snapshot(model).state)
        assertEquals(350L, health.snapshot(model).ewmaLatencyMs)
        assertEquals(50L, health.snapshot(model).inputTokens)
    }

    @Test
    fun fallbackManagerSwitchesFromPrimaryToBackupAndPreservesActiveStatus() = runTest {
        val sleeps = mutableListOf<Long>()
        val health = ModelHealthMonitor()
        val mgr = ModelFallbackManager(
            health = health,
            maxRetriesPerCandidate = 1,
            baseBackoffMs = 200L,
            sleeper = { sleeps += it },
            random = Random(42),
        )
        val called = mutableListOf<String>()
        val turn = mgr.execute(
            role = AgentRole.PHONE_ACTION,
            candidates = listOf(NvidiaModelRegistry.PHONE_PRIMARY, NvidiaModelRegistry.PHONE_BACKUP),
            routeReason = "phone-control",
        ) { candidate ->
            called += candidate
            if (candidate == NvidiaModelRegistry.PHONE_PRIMARY) {
                throw ModelException(404, "Model not enabled on account", retryable = false)
            }
            ModelTurn(text = "Done via backup", calls = emptyList(), usage = Usage(10, 5))
        }
        assertEquals("Done via backup", turn.text)
        assertEquals(listOf(NvidiaModelRegistry.PHONE_PRIMARY, NvidiaModelRegistry.PHONE_BACKUP), called)
        assertEquals(NvidiaModelRegistry.PHONE_BACKUP, mgr.activeStatus.value.modelId)
        assertEquals(NvidiaModelRegistry.PHONE_PRIMARY, mgr.activeStatus.value.fallbackFrom)
        // 404 skips immediate backup without sleeping
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun fallbackManagerFailsFastOnAuthErrorWithoutRetrying() = runTest {
        val health = ModelHealthMonitor()
        val sleeps = mutableListOf<Long>()
        val mgr = ModelFallbackManager(health, maxRetriesPerCandidate = 2, sleeper = { sleeps += it })
        val called = mutableListOf<String>()
        var threw = false
        try {
            mgr.execute(
                role = AgentRole.FAST,
                candidates = listOf(NvidiaModelRegistry.FAST_PRIMARY, NvidiaModelRegistry.FAST_BACKUP),
                routeReason = "quick-response",
            ) { model ->
                called += model
                throw ModelException(401, "Unauthorized nvapi-secret-leak", retryable = false)
            }
        } catch (e: ModelException) {
            threw = true
            assertEquals(401, e.status)
        }
        assertTrue(threw)
        assertEquals(listOf(NvidiaModelRegistry.FAST_PRIMARY), called)
        assertTrue(sleeps.isEmpty())
        assertEquals(ModelHealthState.AUTH_ERROR, health.snapshot(NvidiaModelRegistry.FAST_PRIMARY).state)
    }

    @Test
    fun fallbackManagerRespectsRetryAfterBeforeRetrying() = runTest {
        val sleeps = mutableListOf<Long>()
        val health = ModelHealthMonitor()
        val mgr = ModelFallbackManager(health, maxRetriesPerCandidate = 1, sleeper = { sleeps += it })
        var attempts = 0
        val res = mgr.execute(
            role = AgentRole.FAST,
            candidates = listOf(NvidiaModelRegistry.FAST_PRIMARY),
            routeReason = "quick-response",
        ) {
            attempts++
            if (attempts == 1) throw ModelException(429, "slow down", retryAfterMs = 1_500L, retryable = true)
            ModelTurn(text = "Recovered", calls = emptyList(), usage = null)
        }
        assertEquals("Recovered", res.text)
        assertEquals(listOf(1_500L), sleeps)
    }
}
