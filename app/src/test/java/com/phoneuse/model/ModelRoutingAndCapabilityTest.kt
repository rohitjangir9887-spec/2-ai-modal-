package com.phoneuse.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelRoutingAndCapabilityTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    private val phoneTools = listOf(
        ToolSpec("tap", "Tap UI element", buildJsonObject { put("type", "object") }),
        ToolSpec("type_text", "Type text", buildJsonObject { put("type", "object") }),
        ToolSpec("scroll", "Scroll", buildJsonObject { put("type", "object") }),
        ToolSpec("open_app", "Open app", buildJsonObject { put("type", "object") }),
    )

    @Before
    fun setUp() {
        ModelCapabilityRegistry.clear()
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        ModelCapabilityRegistry.clear()
        server.shutdown()
    }

    @Test
    fun ultra550bIsStrictlyTextOnlyAndStripsImagesAcrossEntireHistory() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"Opened WhatsApp, tapping Search now."}}]}"""),
        )
        val registry = NvidiaModelRegistry()
        val cap = registry.capability("nvidia/nemotron-3-ultra-550b-a55b")
        assertTrue(cap.visionPinnedOff)
        assertFalse(cap.supportsVision)
        assertFalse(cap.canAcceptImages(visionEnabled = true))

        // Even if probeModel attempts to mark vision true, ultra-550b must remain pinned text-only.
        registry.recordProbe("nvidia/nemotron-3-ultra-550b-a55b", chatOk = true, toolsOk = true, visionOk = true)
        assertFalse(registry.capability("nvidia/nemotron-3-ultra-550b-a55b").canAcceptImages(visionEnabled = true))

        val baseUrl = server.url("/v1/").toString()
        val client = NvidiaNimClient(
            apiKey = "nvapi-test-ultra",
            options = ModelOptions("nvidia/nemotron-3-ultra-550b-a55b", fast = false, effort = "medium"),
            http = http,
            baseUrl = baseUrl,
            config = NimRequestConfig(streaming = false, visionEnabled = true),
            registry = registry,
        )

        val historyWithOlderAndNewImages = listOf(
            InputItem.UserText("Open WhatsApp and check this photo", listOf(InputItem.UserImage("T0xE"))),
            InputItem.UserImage("U0NSRUVOMQ=="),
            InputItem.FunctionCall("c1", "open_app", """{"name":"WhatsApp"}"""),
            InputItem.FunctionOutput("c1", "OK\n\n# Screen\napp: com.whatsapp\n[1] Search"),
            InputItem.UserImage("U0NSRUVOMg=="),
        )

        val turn = client.next("System rules", historyWithOlderAndNewImages, phoneTools) {}
        assertEquals("Opened WhatsApp, tapping Search now.", turn.text)

        val recorded = server.takeRequest().body.readUtf8()
        assertFalse("Must never send image_url to ultra-550b: $recorded", recorded.contains("image_url"))
        assertFalse("Must never pass --enable-multimodal: $recorded", recorded.contains("--enable-multimodal"))
        assertTrue("Accessibility screen tree must still be sent", recorded.contains("com.whatsapp"))
    }

    @Test
    fun perModelCapabilityDetectionTracksTextVisionToolsStreamingAndUnsupportedParams() = runTest {
        // 1) Probe with streaming=true: chat ok, tools ok, vision fails (400 multimodal), streaming fails (400)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Received multimodal data but multimodal processing is not enabled."}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"stream must be false for this endpoint"}}"""))

        val baseUrl = server.url("/v1/").toString()
        val report = NvidiaNimClient.probeModel(
            apiKey = "nvapi-probe",
            modelId = "custom/model-check",
            http = http,
            baseUrl = baseUrl,
            testTools = true,
            testVision = true,
            testStreaming = true,
        )
        assertTrue(report.chatOk)
        assertEquals(true, report.toolsOk)
        assertEquals(false, report.visionOk)
        assertEquals(false, report.streamingOk)

        // 2) Unsupported parameter fallback: 400 rejecting top_p strips top_p and retries cleanly
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown parameter: top_p"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"Param recovered"}}]}"""))

        val registry = NvidiaModelRegistry()
        registry.recordProbe(
            "custom/model-check",
            chatOk = report.chatOk,
            toolsOk = report.toolsOk,
            visionOk = report.visionOk,
            streamingOk = report.streamingOk,
        )
        val client = NvidiaNimClient(
            apiKey = "nvapi-probe",
            options = ModelOptions("custom/model-check", fast = false, effort = "medium"),
            http = http,
            baseUrl = baseUrl,
            config = NimRequestConfig(streaming = true, visionEnabled = true),
            registry = registry,
        )
        val turn = client.next("sys", listOf(InputItem.UserText("hi")), phoneTools) {}
        assertEquals("Param recovered", turn.text)

        val profile = ModelCapabilityRegistry.profile(baseUrl, "custom/model-check", registry)
        assertTrue(profile.supportsText)
        assertFalse(profile.supportsVision)
        assertTrue(profile.supportsTools)
        assertFalse(profile.supportsStreaming)
        assertFalse(profile.supportsParam("top_p"))
        assertTrue(profile.supportsParam("temperature"))
    }

    @Test
    fun manualUltra550bUsesNanoOmniVisionInterpreterAndNeverReceivesImages() = runTest {
        val baseUrl = server.url("/v1/").toString()
        val registry = NvidiaModelRegistry()
        val health = ModelHealthMonitor()
        val router = ModelRouter(registry, health)
        val fallbackManager = ModelFallbackManager(health, sleeper = {})
        // Mark PRIMARY vision unavailable on account, so BACKUP nano-omni-30b must handle visual interpretation
        registry.updateDiscoveredCatalog(
            listOf(
                NvidiaModelRegistry.MANAGER_PRIMARY,
                NvidiaModelRegistry.VISION_BACKUP,
            ),
        )
        registry.recordProbe(
            NvidiaModelRegistry.VISION_BACKUP,
            chatOk = true,
            toolsOk = true,
            visionOk = true,
            streamingOk = false,
        )

        // Request 1 -> VISION_BACKUP (nvidia/nemotron-3-nano-omni-30b-a3b-reasoning) interprets the screenshot
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"WhatsApp home screen showing Search icon [1] at top right and chat with Alice [2]."}}]}"""),
        )
        // Request 2 -> ultra-550b receives visual summary + compressed UI tree with NO image_url
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"","tool_calls":[{"id":"call_tap","type":"function","function":{"name":"tap","arguments":"{\"index\":1}"}}]}}]}"""),
        )

        val client = OrchestratedModelClient(
            apiKey = "nvapi-test",
            baseOptions = ModelOptions(NvidiaModelRegistry.MANAGER_PRIMARY, fast = false, effort = "medium"),
            http = http,
            registry = registry,
            health = health,
            router = router,
            fallbackManager = fallbackManager,
            autoOrchestration = false,
            requestConfig = NimRequestConfig(streaming = false, visionEnabled = true),
            baseUrl = baseUrl,
        )

        val input = listOf(
            InputItem.UserText("Send WhatsApp to Alice"),
            InputItem.FunctionOutput("c0", "OK\n\n# Screen\napp: com.whatsapp\n[1] Search\n[2] Alice"),
            InputItem.UserImage("AQID"),
        )
        val turn = client.next("sys", input, phoneTools) {}
        assertEquals(1, turn.calls.size)
        assertEquals("tap", turn.calls[0].name)

        val visionReq = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val actingReq = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(NvidiaModelRegistry.VISION_BACKUP, visionReq["model"]?.jsonPrimitive?.content)
        assertTrue("Vision request should include image_url", visionReq.toString().contains("image_url"))
        assertEquals(NvidiaModelRegistry.MANAGER_PRIMARY, actingReq["model"]?.jsonPrimitive?.content)
        assertFalse("ultra-550b request must NOT contain image_url", actingReq.toString().contains("image_url"))
        assertTrue(
            "ultra-550b should receive visual interpretation summary, got: $actingReq",
            actingReq.toString().contains("[Visual Interpretation (") && actingReq.toString().contains("WhatsApp home screen showing Search icon"),
        )
    }

    @Test
    fun autoVisionRoutingValidatesNanoOmni30bAndFallsBackFromUnavailablePrimary() = runTest {
        val baseUrl = server.url("/v1/").toString()
        val registry = NvidiaModelRegistry()
        val health = ModelHealthMonitor()
        val router = ModelRouter(registry, health)
        val fallbackManager = ModelFallbackManager(health, sleeper = {})

        // Primary vision model returns 404 (not enabled on this API key), backup nano-omni-30b succeeds
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Model nvidia/nemotron-3.5-super-vl-120b-a12b not found"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"tap","arguments":"{\"index\":1}"}}]}}]}"""),
        )

        val client = OrchestratedModelClient(
            apiKey = "nvapi-key",
            baseOptions = ModelOptions(NvidiaModelRegistry.MANAGER_PRIMARY, fast = false, effort = "medium"),
            http = http,
            registry = registry,
            health = health,
            router = router,
            fallbackManager = fallbackManager,
            autoOrchestration = true,
            requestConfig = NimRequestConfig(streaming = false, visionEnabled = true),
            baseUrl = baseUrl,
        )

        val turn = client.next(
            "sys",
            listOf(InputItem.UserText("Tap button"), InputItem.UserImage("AQID")),
            phoneTools,
        ) {}
        assertEquals("tap", turn.calls.single().name)

        val firstModel = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]?.jsonPrimitive?.content
        val secondModel = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]?.jsonPrimitive?.content
        assertEquals(NvidiaModelRegistry.VISION_PRIMARY, firstModel)
        assertEquals(NvidiaModelRegistry.VISION_BACKUP, secondModel)
        assertFalse(registry.capability(NvidiaModelRegistry.VISION_PRIMARY).listedInAccount == true)
    }

    @Test
    fun orchestratedClientFallsBackToTextOnlyWhenAllVisionModelsFailOnAccount() = runTest {
        val baseUrl = server.url("/v1/").toString()
        val registry = NvidiaModelRegistry()
        val health = ModelHealthMonitor()
        val router = ModelRouter(registry, health)
        val fallbackManager = ModelFallbackManager(health, sleeper = {})

        // Both VISION_PRIMARY and VISION_BACKUP fail (not enabled on key)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Unknown model"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Received multimodal data but multimodal processing is not enabled."}}"""))
        // Router falls back cleanly to text-only routing (PHONE_ACTION / MANAGER) stripping images
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"tap","arguments":"{\"index\":1}"}}]}}]}"""),
        )

        val client = OrchestratedModelClient(
            apiKey = "nvapi-key",
            baseOptions = ModelOptions(NvidiaModelRegistry.MANAGER_PRIMARY, fast = false, effort = "medium"),
            http = http,
            registry = registry,
            health = health,
            router = router,
            fallbackManager = fallbackManager,
            autoOrchestration = true,
            requestConfig = NimRequestConfig(streaming = false, visionEnabled = true),
            baseUrl = baseUrl,
        )

        val turn = client.next(
            "sys",
            listOf(
                InputItem.UserText("Open WhatsApp chat"),
                InputItem.FunctionOutput("c0", "OK\n\n# Screen\napp: com.whatsapp\n[1] Alice"),
                InputItem.UserImage("AQID"),
            ),
            phoneTools,
        ) {}
        assertEquals("tap", turn.calls.single().name)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        val textFallbackReq = server.takeRequest().body.readUtf8()
        assertFalse("Fallback text request must strip all image_url blocks", textFallbackReq.contains("image_url"))
    }

    @Test
    fun chatCompletionsClientDetectsMultimodalRejectionAndStripsEntireHistoryNextCall() = runTest {
        val baseUrl = server.url("/v1/").toString()
        val endpoint = ChatCompletionsClient.Endpoint(
            baseUrl = baseUrl,
            unauthorizedMessage = "Unauthorized",
        )
        val client = ChatCompletionsClient(
            endpoint = endpoint,
            apiKey = "test-key",
            options = ModelOptions("custom-vision-claim", fast = false, effort = "low"),
            http = http,
        )

        // Call 1: returns NVIDIA multimodal-disabled 400 error
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Received multimodal data but multimodal processing is not enabled."}}"""),
        )
        var caught: ModelException? = null
        try {
            client.next(
                "sys",
                listOf(
                    InputItem.UserText("Check this", listOf(InputItem.UserImage("AQID"))),
                    InputItem.UserImage("BAUG"),
                ),
                phoneTools,
            ) {}
        } catch (e: ModelException) {
            caught = e
        }
        assertNotNull(caught)
        assertEquals(ModelErrorClassifier.CODE_MULTIMODAL_UNSUPPORTED, caught?.code)
        assertFalse(caught?.retryable ?: true)

        // Call 2: model is now cached as text-only, so ALL images across history are automatically stripped
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"Resumed without images"}}]}"""),
        )
        val res = client.next(
            "sys",
            listOf(
                InputItem.UserText("Old image", listOf(InputItem.UserImage("AQID"))),
                InputItem.UserImage("BAUG"),
                InputItem.FunctionOutput("c1", "OK\n\n# Screen\n[1] Message"),
            ),
            phoneTools,
        ) {}
        assertEquals("Resumed without images", res.text)
        server.takeRequest() // discard 1st failed request
        val req2 = server.takeRequest().body.readUtf8()
        assertFalse("Second request must strip every image_url", req2.contains("image_url"))
        assertFalse("Must never inject --enable-multimodal", req2.contains("--enable-multimodal"))
    }

    @Test
    fun neverInjectsEnableMultimodalFlagInSerializedRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val client = NvidiaNimClient(
            apiKey = "nvapi-key",
            options = ModelOptions("nvidia/nemotron-3-ultra-550b-a55b", fast = false, effort = "high"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = false, visionEnabled = true),
        )
        client.next("sys", listOf(InputItem.UserText("hi"), InputItem.UserImage("AQID")), phoneTools) {}
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("--enable-multimodal"))
        assertFalse(body.contains("enable_multimodal"))
        assertFalse(body.contains("image_url"))
    }
}
