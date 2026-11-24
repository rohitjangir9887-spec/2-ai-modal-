package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelErrorClassifier
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingModelClient(
    private val handler: suspend (callIndex: Int, input: List<InputItem>) -> ModelTurn,
) : ModelClient {
    val inputs = mutableListOf<List<InputItem>>()
    val calls get() = inputs.size

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        inputs += input.toList()
        return handler(inputs.size, input)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppAndRecoveryAgentTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = InputItem.FunctionCall("wa_${seq++}", name, args)
    private fun turn(vararg calls: InputItem.FunctionCall, text: String? = null) = ModelTurn(text, calls.toList(), null)

    private fun node(
        text: String? = null,
        hint: String? = null,
        desc: String? = null,
        viewId: String? = null,
        y: Int = 100,
        height: Int = 60,
        clickable: Boolean = true,
        editable: Boolean = false,
        className: String = if (editable) "android.widget.EditText" else "android.widget.Button",
    ) = UiNode(
        windowId = 0,
        path = listOf(y),
        className = className,
        text = text,
        contentDescription = desc,
        hint = hint,
        viewId = viewId,
        bounds = Bounds(50, y, 1000, y + height),
        clickable = clickable,
        editable = editable,
    )

    private fun waHome() = ScreenSnapshot(
        "com.whatsapp",
        1080,
        2400,
        listOf(
            node(desc = "Search", viewId = "com.whatsapp:id/menuitem_search", y = 120),
            node(text = "Chats", y = 260),
        ),
    )

    private fun waSearchResults() = ScreenSnapshot(
        "com.whatsapp",
        1080,
        2400,
        listOf(
            node(text = "Alice", hint = "Search...", viewId = "com.whatsapp:id/search_src_text", y = 120, editable = true),
            node(text = "Alice", desc = "Alice +1-555-0101", y = 300),
        ),
    )

    private fun waChat(draft: String = "", sentMessages: List<String> = emptyList()): ScreenSnapshot {
        val items = mutableListOf<UiNode>()
        items += node(text = "Alice", viewId = "com.whatsapp:id/conversation_contact_name", y = 100, clickable = false, className = "android.widget.TextView")
        sentMessages.forEachIndexed { idx, msg ->
            items += node(text = msg, y = 400 + idx * 100, clickable = false, className = "android.widget.TextView")
        }
        items += node(
            text = draft,
            hint = "Message",
            viewId = "com.whatsapp:id/entry",
            y = 1900,
            height = if (draft.length > 20) 120 else 70,
            editable = true,
        )
        items += node(text = "Send", desc = "Send", viewId = "com.whatsapp:id/send", y = 1900)
        return ScreenSnapshot("com.whatsapp", 1080, 2400, items)
    }

    @Test
    fun multimodalErrorSwitchesToTextOnlyStripsEntireHistoryRefreshesTreeAndResumesWithoutDuplicatingOpenApp() = runTest {
        val homeScreen = ScreenSnapshot("com.launcher", 1080, 2400, listOf(node(text = "Home", y = 200)))
        val whatsappScreen = waHome()
        val dev = FakeDeviceController(listOf(homeScreen, whatsappScreen, whatsappScreen)).apply {
            apps = listOf(AppInfo("com.whatsapp", "WhatsApp"))
        }
        val gate = FakeUserGate()
        val executor = ToolExecutor(dev, gate, screenshots = true)

        val model = RecordingModelClient { n, input ->
            when (n) {
                // Step 1: opens WhatsApp (screenshot will be captured and added to history)
                1 -> turn(call("open_app", """{"name":"WhatsApp"}"""))
                // Step 2: model rejects multimodal input
                2 -> {
                    assertTrue(input.any { it is InputItem.UserImage || (it is InputItem.UserText && it.images.isNotEmpty()) })
                    throw ModelException(400, "Received multimodal data but multimodal processing is not enabled.")
                }
                // Step 2 recovery retry: images stripped from ENTIRE history, refreshed Accessibility tree present
                3 -> {
                    assertTrue("All UserImage items must be stripped after multimodal error", input.none { it is InputItem.UserImage })
                    assertTrue("All attached UserText.images must be stripped", input.none { it is InputItem.UserText && it.images.isNotEmpty() })
                    val lastUser = input.last { it is InputItem.UserText } as InputItem.UserText
                    assertTrue("Expected refreshed tree note, got: ${lastUser.text}", lastUser.text.contains("com.whatsapp") && lastUser.text.contains("Search"))
                    turn(call("tap", """{"index":1}"""))
                }
                else -> turn(call("finish", """{"summary":"Opened WhatsApp and tapped Search."}"""))
            }
        }

        val events = AgentLoop(model, executor, SteeringQueue()).run(
            history = listOf(InputItem.UserText("Look at old screenshot", listOf(InputItem.UserImage("T0xE")))),
            message = "Open WhatsApp and search for Alice",
            observeFirst = false,
            images = listOf(InputItem.UserImage("QVRUQUNI")),
        ).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        // open_app MUST execute exactly once (not duplicated during multimodal recovery!)
        assertEquals(1, dev.calls.count { it == "launchApp(com.whatsapp)" })
        assertEquals(1, dev.calls.count { it.startsWith("tap(") })
        assertFalse("Executor screenshots must remain disabled after multimodal fallback", executor.screenshotsEnabled)
        // Step 3+ must contain zero screenshots
        assertTrue(model.inputs[3].none { it is InputItem.UserImage })
    }

    @Test
    fun whatsappFullFlowSearchesTypesVerifiesDraftConfirmsSendAndVerifiesSentMessage() = runTest {
        val launcher = ScreenSnapshot("com.launcher", 1080, 2400, listOf(node(text = "Launcher", y = 200)))
        val home = waHome()
        val searchBoxScreen = ScreenSnapshot(
            "com.whatsapp",
            1080,
            2400,
            listOf(node(text = "", hint = "Search...", viewId = "com.whatsapp:id/search_src_text", y = 120, editable = true)),
        )
        val searchResults = waSearchResults()
        val emptyChat = waChat(draft = "")
        val typedChat = waChat(draft = "On my way")
        val sentChat = waChat(draft = "", sentMessages = listOf("On my way"))

        // Drive dynamic screen state transitions cleanly after each device action executes:
        lateinit var smartDev: FakeDeviceController
        smartDev = FakeDeviceController(listOf(launcher)).apply {
            apps = listOf(AppInfo("com.whatsapp", "WhatsApp"))
            snapshotHook = {
                val hasSent = smartDev.calls.count { it.startsWith("tap(") } >= 3
                val hasTypedDraft = smartDev.calls.contains("setText(On my way)")
                val hasOpenedChat = smartDev.calls.count { it.startsWith("tap(") } >= 2
                val hasTypedSearch = smartDev.calls.contains("setText(Alice)")
                val hasTappedSearch = smartDev.calls.count { it.startsWith("tap(") } >= 1
                val hasLaunched = smartDev.calls.contains("launchApp(com.whatsapp)")
                when {
                    hasSent -> sentChat
                    hasTypedDraft -> typedChat
                    hasOpenedChat -> emptyChat
                    hasTypedSearch -> searchResults
                    hasTappedSearch -> searchBoxScreen
                    hasLaunched -> home
                    else -> launcher
                }
            }
        }
        val gate = FakeUserGate()
        val executor = ToolExecutor(smartDev, gate, screenshots = false)

        val model = RecordingModelClient { n, input ->
            assertTrue("Text-only run must never contain images", input.none { it is InputItem.UserImage })
            when (n) {
                1 -> turn(call("open_app", """{"name":"WhatsApp"}"""))
                2 -> turn(call("tap", """{"index":1}"""))
                3 -> turn(call("type_text", """{"index":1,"text":"Alice","submit":true}"""))
                4 -> {
                    // Search confirmation must NOT have triggered RiskCategory.SEND
                    assertTrue("Search submit should not show SEND confirmation", gate.confirms.isEmpty())
                    turn(call("tap", """{"index":2}"""))
                }
                5 -> turn(call("type_text", """{"index":2,"text":"On my way","submit":false}"""))
                6 -> {
                    val out = input.filterIsInstance<InputItem.FunctionOutput>().last().output
                    assertTrue("Expected composer verification note, got:\n$out", out.contains("Verified: field [2] displays \"On my way\""))
                    turn(call("tap", """{"index":3}"""))
                }
                else -> {
                    val out = input.filterIsInstance<InputItem.FunctionOutput>().last().output
                    assertTrue("Sent bubble should be on verified screen before finishing, got:\n$out", out.contains("On my way"))
                    turn(call("finish", """{"summary":"Sent 'On my way' to Alice on WhatsApp and verified it appears in chat."}"""))
                }
            }
        }

        val events = AgentLoop(model, executor, SteeringQueue()).run(
            emptyList(),
            "Message Alice on WhatsApp saying On my way",
            observeFirst = false,
        ).toList()

        val finished = events.last() as AgentEvent.Finished
        assertTrue(finished.summary.contains("verified"))
        // Send confirmation policy ran for tapping "Send" (since user_authorized was not set)
        assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
        assertEquals(1, smartDev.calls.count { it == "launchApp(com.whatsapp)" })
        assertEquals(listOf("Alice", "On my way"), smartDev.calls.filter { it.startsWith("setText(") }.map { it.removePrefix("setText(").removeSuffix(")") })
    }

    @Test
    fun whatsappAmbiguousContactUsesAskUserAndMissingMessageTextAsksUserBeforeTyping() = runTest {
        val ambiguous = ScreenSnapshot(
            "com.whatsapp",
            1080,
            2400,
            listOf(
                node(text = "Alice", hint = "Search...", y = 120, editable = true),
                node(text = "Alice Work", y = 300),
                node(text = "Alice Home", y = 420),
            ),
        )
        val aliceWorkChat = waChat(draft = "")
        val gate = FakeUserGate()
        gate.askResult = "Alice Work"
        val dev = FakeDeviceController(listOf(ambiguous, aliceWorkChat))
        val executor = ToolExecutor(dev, gate, screenshots = false)

        val model = RecordingModelClient { n, input ->
            when (n) {
                1 -> turn(call("ask_user", """{"question":"Which Alice?","options":["Alice Work","Alice Home"]}"""))
                2 -> {
                    val answer = (input.filterIsInstance<InputItem.FunctionOutput>().last()).output
                    assertTrue(answer.contains("Alice Work"))
                    gate.askResult = "Running 10 minutes late"
                    turn(call("ask_user", """{"question":"What message would you like to send to Alice Work?"}"""))
                }
                3 -> {
                    val msg = (input.filterIsInstance<InputItem.FunctionOutput>().last()).output
                    assertTrue(msg.contains("Running 10 minutes late"))
                    turn(call("type_text", """{"index":2,"text":"Running 10 minutes late"}"""))
                }
                else -> turn(call("finish", """{"summary":"Typed message after clarifying contact and content."}"""))
            }
        }

        val events = AgentLoop(model, executor, SteeringQueue()).run(
            emptyList(),
            "Message Alice on WhatsApp",
            observeFirst = true,
        ).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(2, gate.asks.size)
        assertEquals(listOf("Alice Work", "Alice Home"), gate.asks[0].second)
    }

    @Test
    fun duplicateSendIsBlockedAcrossIntermediateWaitOrGetScreenCallsAndRetries() = runTest {
        // Screen stays unchanged after tapping Send (simulating delayed WhatsApp UI update / retry)
        val stuckChat = waChat(draft = "Confirm this order")
        val dev = FakeDeviceController(listOf(stuckChat))
        val gate = FakeUserGate() // allows SEND once
        val executor = ToolExecutor(dev, gate, screenshots = false)

        val model = RecordingModelClient { n, input ->
            when (n) {
                1 -> turn(call("tap", """{"index":3}""")) // taps "Send" [3]
                2 -> turn(call("wait", """{"ms":500}""")) // intermediate wait on SAME screenFingerprint
                3 -> turn(call("tap", """{"index":3}""")) // model tries to tap "Send" [3] again on unchanged screen!
                else -> {
                    val lastOut = input.filterIsInstance<InputItem.FunctionOutput>().last().output
                    assertTrue("Duplicate Send must be blocked, got: $lastOut", lastOut.startsWith("ERROR: duplicate send blocked"))
                    turn(call("finish", """{"summary":"Checked chat after initial Send; duplicate send prevented."}"""))
                }
            }
        }

        val events = AgentLoop(model, executor, SteeringQueue()).run(
            emptyList(),
            "Send it",
            observeFirst = true,
        ).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        // Send was confirmed and tapped ONLY ONCE!
        assertEquals(1, gate.confirms.size)
        assertEquals(1, dev.calls.count { it.startsWith("tap(") })
    }

    @Test
    fun staleEditableNodeWithImeResizeRecoversByViewIdWhileButtonResizeStops() = runTest {
        val beforeIme = ScreenSnapshot(
            "com.whatsapp",
            1080,
            2400,
            listOf(
                node(text = "", hint = "Message", viewId = "com.whatsapp:id/entry", y = 2100, height = 70, editable = true),
                node(text = "Pay Now", viewId = null, y = 1800, height = 60, editable = false),
            ),
        )
        // Keyboard opened: composer moved up to y=1300 and grew taller (height 120 vs 70); Pay Now button widened by +60px
        val afterIme = ScreenSnapshot(
            "com.whatsapp",
            1080,
            2400,
            listOf(
                UiNode(
                    windowId = 0, path = listOf(1300), className = "android.widget.EditText",
                    text = "", contentDescription = null, hint = "Message", viewId = "com.whatsapp:id/entry",
                    bounds = Bounds(50, 1300, 1000, 1420), clickable = true, editable = true,
                ),
                UiNode(
                    windowId = 0, path = listOf(1100), className = "android.widget.Button",
                    text = "Pay Now", contentDescription = null, hint = null, viewId = null,
                    bounds = Bounds(50, 1100, 1060, 1160), clickable = true, editable = false,
                ),
            ),
        )
        val dev = FakeDeviceController(listOf(afterIme))
        val gate = FakeUserGate()
        val ex = ToolExecutor(dev, gate, screenshots = false)

        // Editable composer with matching viewId & hint succeeds despite IME shift & height resize
        val typed = ex.execute(call("type_text", """{"index":1,"text":"Hello"}"""), TreeCompressor.compress(beforeIme))
        assertTrue(typed.output, typed.output.startsWith("OK"))
        assertEquals(1, dev.setTextNodes.size)

        // Non-editable button that resized still triggers STOPPED safety guard
        val blockedTap = ex.execute(call("tap", """{"index":2}"""), TreeCompressor.compress(beforeIme))
        assertTrue(blockedTap.output, blockedTap.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("tap(") })
    }

    @Test
    fun recoversFromRateLimit429AndTransientNetworkFailureWithoutDuplicatingActions() = runTest {
        val dev = FakeDeviceController(listOf(waHome()))
        val gate = FakeUserGate()
        val executor = ToolExecutor(dev, gate, screenshots = false)
        var calls = 0
        val flakyModel = object : ModelClient {
            override suspend fun next(
                instructions: String,
                input: List<InputItem>,
                tools: List<ToolSpec>,
                onTextDelta: (String) -> Unit,
            ): ModelTurn {
                calls++
                return when (calls) {
                    1 -> throw ModelException(null, "Network timeout", retryable = true)
                    2 -> turn(call("tap", """{"index":1}"""))
                    3 -> throw ModelException(429, "Rate limit exceeded", retryAfterMs = 2_500L, retryable = true)
                    else -> turn(call("finish", """{"summary":"Recovered cleanly"}"""))
                }
            }
        }

        val events = AgentLoop(flakyModel, executor, SteeringQueue()).run(
            emptyList(),
            "Tap search",
            observeFirst = true,
        ).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(4, calls)
        assertEquals(1, dev.calls.count { it.startsWith("tap(") })
        assertTrue("Expected rate-limit + network backoff delay >= 2500ms, got $currentTime", currentTime >= 2_500L)
    }

    @Test
    fun getScreenWithScreenshotFalseOmitsScreenshotEvenWhenScreenshotsEnabled() = runTest {
        val dev = FakeDeviceController(listOf(waHome()))
        val executor = ToolExecutor(dev, FakeUserGate(), screenshots = true)
        val res = executor.execute(call("get_screen", """{"screenshot":false}"""), null)
        assertNull("get_screen(screenshot=false) must not attach bitmap", res.screenshot)
        assertTrue(res.output.contains("com.whatsapp"))
    }
}
