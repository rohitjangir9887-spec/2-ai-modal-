package com.phoneuse.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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

class NvidiaNimClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsSseDeltasAndAssemblesPartialToolCalls() = runTest {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Opening \"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"app.\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"tap\",\"arguments\":\"{\\\"x\\\":120,\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"y\\\":340}\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":18}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val client = NvidiaNimClient(
            apiKey = "nvapi-test-key-1234567890",
            options = ModelOptions("nvidia/nemotron-3-super-120b-a12b", fast = false, effort = "medium"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = true, visionEnabled = false),
        )
        val deltas = StringBuilder()
        val turn = client.next(
            instructions = "Be concise",
            input = listOf(InputItem.UserText("Tap button")),
            tools = listOf(ToolSpec("tap", "Tap screen", buildJsonObject { put("type", "object") })),
            onTextDelta = { deltas.append(it) },
        )

        val recorded = server.takeRequest()
        assertEquals("Bearer nvapi-test-key-1234567890", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.endsWith("/chat/completions"))
        assertEquals("Opening app.", deltas.toString())
        assertEquals("Opening app.", turn.text)
        assertEquals(1, turn.calls.size)
        assertEquals("tap", turn.calls[0].name)
        assertEquals("{\"x\":120,\"y\":340}", turn.calls[0].argumentsJson)
        assertEquals(42, turn.usage?.inputTokens)
        assertEquals(18, turn.usage?.outputTokens)
    }

    @Test
    fun fallsBackToJsonWhenStreamingFalseOrNonSseResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Ready to help.",
                            "tool_calls": [
                              {"id":"tc_9","type":"function","function":{"name":"scroll","arguments":"not valid json"}}
                            ]
                          }
                        }
                      ],
                      "usage": {"prompt_tokens": 11, "completion_tokens": 5}
                    }
                    """.trimIndent(),
                ),
        )
        val client = NvidiaNimClient(
            apiKey = "nvapi-test-json",
            options = ModelOptions("nvidia/nemotron-3-nano-30b-a3b", fast = false, effort = "medium"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = false),
        )
        val turn = client.next("sys", listOf(InputItem.UserText("Scroll")), emptyList()) {}
        assertEquals("Ready to help.", turn.text)
        assertEquals(1, turn.calls.size)
        // Malformed arguments are repaired safely into valid JSON object instead of crashing.
        assertEquals("{}", turn.calls[0].argumentsJson)
    }

    @Test
    fun parsesRetryAfterAndMarksAuthErrorsNonRetryable() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "3")
                .setBody("{\"error\":{\"message\":\"rate limited\"}}"),
        )
        val client = NvidiaNimClient(
            apiKey = "nvapi-secret-abc-999",
            options = ModelOptions("nvidia/nemotron-3-ultra-550b-a55b", fast = false, effort = "medium"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = false),
        )
        var rateEx: ModelException? = null
        try {
            client.next("sys", listOf(InputItem.UserText("hi")), emptyList()) {}
        } catch (e: ModelException) {
            rateEx = e
        }
        assertNotNull(rateEx)
        assertEquals(429, rateEx?.status)
        assertEquals(3000L, rateEx?.retryAfterMs)
        assertTrue(rateEx?.retryable == true)

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid key nvapi-secret-abc-999\"}}"),
        )
        var authEx: ModelException? = null
        try {
            client.next("sys", listOf(InputItem.UserText("hi")), emptyList()) {}
        } catch (e: ModelException) {
            authEx = e
        }
        assertEquals(401, authEx?.status)
        assertFalse(authEx?.retryable ?: true)
        assertFalse(authEx?.message?.contains("nvapi-secret-abc-999") ?: false)
    }

    @Test
    fun stripsImagesFromPayloadForTextOnlyModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"Text only reply"}}]}"""),
        )
        val registry = NvidiaModelRegistry()
        val client = NvidiaNimClient(
            apiKey = "nvapi-test",
            options = ModelOptions("nvidia/nemotron-3-nano-30b-a3b", fast = false, effort = "medium"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = false, visionEnabled = true),
            registry = registry,
        )
        client.next(
            "sys",
            listOf(InputItem.UserText("Look", listOf(InputItem.UserImage("AQID")))),
            emptyList(),
        ) {}
        val reqBody = server.takeRequest().body.readUtf8()
        assertFalse(reqBody.contains("image_url"))
    }

    @Test
    fun listsModelsAndProbesCapabilities() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"nvidia/nemotron-3-ultra-550b-a55b"},{"id":"nvidia/nemotron-3.5-super-vl-120b-a12b"}]}"""),
        )
        val listed = NvidiaNimClient.listModels("nvapi-key", http, server.url("/v1/").toString())
        assertEquals(
            listOf("nvidia/nemotron-3-ultra-550b-a55b", "nvidia/nemotron-3.5-super-vl-120b-a12b"),
            listed,
        )

        // Probe: 1) chat ok, 2) tools ok, 3) vision ok
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"choices":[{"message":{"content":"OK"}}]}"""),
            )
        }
        val report = NvidiaNimClient.probeModel(
            apiKey = "nvapi-key",
            modelId = "nvidia/nemotron-3.5-super-vl-120b-a12b",
            http = http,
            baseUrl = server.url("/v1/").toString(),
            testTools = true,
            testVision = true,
        )
        assertTrue(report.chatOk)
        assertEquals(true, report.toolsOk)
        assertEquals(true, report.visionOk)
    }

    @Test
    fun cancelsInFlightHttpCallWhenCoroutineCancelled() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody("""{"choices":[{"message":{"content":"late"}}]}"""),
        )
        val client = NvidiaNimClient(
            apiKey = "nvapi-cancel",
            options = ModelOptions("nvidia/nemotron-3-nano-30b-a3b", fast = false, effort = "medium"),
            http = http,
            baseUrl = server.url("/v1/").toString(),
            config = NimRequestConfig(streaming = false),
        )
        val job = async {
            client.next("sys", listOf(InputItem.UserText("wait")), emptyList()) {}
        }
        delay(80)
        job.cancel(CancellationException("user stopped"))
        var cancelled = false
        try {
            job.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
