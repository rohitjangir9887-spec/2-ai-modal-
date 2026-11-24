package com.phoneuse.overlay

import com.phoneuse.risk.RiskCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentUiStateTest {
    @Test
    fun koreanNamesCoverEveryCategory() {
        val names = RiskCategory.entries.associateWith { it.koreanName() }
        assertEquals(
            mapOf(
                RiskCategory.MONEY to "결제",
                RiskCategory.SECURITY to "보안",
                RiskCategory.DELETE to "삭제",
                RiskCategory.SEND to "전송",
                RiskCategory.CHANGE to "변경",
            ),
            names,
        )
    }

    @Test
    fun dockKeepsTextWhenAQuestionExpiresOrTheRunFinishes() {
        val card = DockCard.Ask("어느 쪽?", emptyList(), 7)
        assertEquals(false, submitDockText(card, "왼쪽", { _, _ -> false }, { error("stale answer steered the run") }))
        assertEquals(false, submitDockText(null, "더 빨리", { _, _ -> error("unexpected card") }, { false }))
    }

    @Test
    fun dockInputAnswersAskCardWithItsIdEvenWithoutOptions() {
        val answers = mutableListOf<Pair<Long, String>>()
        val steers = mutableListOf<String>()
        // A free-form question has no option buttons: the typed text is the only way to answer it.
        val taken = submitDockText(DockCard.Ask("받는 사람 이름은?", emptyList(), 3), "  엄마 ", { id, t -> answers.add(id to t) }, steers::add)
        assertEquals(true, taken)
        assertEquals(listOf(3L to "엄마"), answers)
        assertEquals(emptyList<String>(), steers)
    }

    @Test
    fun dockInputSteersOtherwiseAndIgnoresBlank() {
        val answers = mutableListOf<Pair<Long, String>>()
        val steers = mutableListOf<String>()
        val answer = { id: Long, t: String -> answers.add(id to t) }
        assertEquals(false, submitDockText(null, "   ", answer, steers::add))
        assertEquals(true, submitDockText(DockCard.HandOff("로그인", 4), "됐어", answer, steers::add))
        assertEquals(true, submitDockText(DockCard.Confirm(RiskCategory.MONEY, "결제", 8), "그만", answer, steers::add))
        assertEquals(true, submitDockText(null, " 더 빨리", answer, steers::add))
        assertEquals(emptyList<Pair<Long, String>>(), answers)
        assertEquals(listOf("됐어", "그만", "더 빨리"), steers)
    }

    @Test
    fun overlayPanelStateMinimizesExpandsTogglesLogsAndNeverHidesUrgentCards() {
        val ui = AgentUiState(
            phase = AgentPhase.ACTING,
            lines = listOf("step 2", "step 3", "step 4"),
            allLines = listOf("step 1", "step 2", "step 3", "step 4"),
        )
        var panel = OverlayPanelState()
        assertEquals(listOf("step 2", "step 3", "step 4"), panel.visibleLogs(ui))
        panel = panel.toggleLogsVisible()
        assertEquals(emptyList<String>(), panel.visibleLogs(ui))
        panel = panel.toggleLogsVisible()
        assertEquals(listOf("step 2", "step 3", "step 4"), panel.visibleLogs(ui))
        panel = panel.toggleLogsExpanded()
        assertEquals(listOf("step 1", "step 2", "step 3", "step 4"), panel.visibleLogs(ui))

        panel = panel.toggleMinimized()
        assertEquals(true, panel.isEffectivelyMinimized(null))
        assertEquals(false, panel.isEffectivelyMinimized(DockCard.Confirm(RiskCategory.MONEY, "Pay ₹500", 1)))
        assertEquals(false, panel.isEffectivelyMinimized(DockCard.Ask("Which contact?", listOf("A", "B"), 2)))
        assertEquals(false, panel.isEffectivelyMinimized(DockCard.HandOff("Enter UPI PIN", 3)))
    }
}
