package com.phoneuse.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionStallGuardTest {
    @Test fun repeatedUnchangedScrollReplansThenStops() {
        val g = ActionStallGuard()
        assertEquals(ActionStallGuard.Decision.ALLOW, g.check("scroll", "{\"direction\":\"down\"}", 10))
        assertEquals(ActionStallGuard.Decision.ALLOW, g.check("scroll", "{\"direction\":\"down\"}", 10))
        assertEquals(ActionStallGuard.Decision.REPLAN, g.check("scroll", "{\"direction\":\"down\"}", 10))
        assertEquals(ActionStallGuard.Decision.STOP, g.check("scroll", "{\"direction\":\"down\"}", 10))
    }

    @Test fun meaningfulScreenChangeResetsTheCounter() {
        val g = ActionStallGuard()
        g.check("tap", "{\"index\":3}", 10)
        g.check("tap", "{\"index\":3}", 10)
        assertEquals(ActionStallGuard.Decision.ALLOW, g.check("tap", "{\"index\":3}", 11))
    }

    @Test fun submittedTextNeverRetriesBlindly() {
        val g = ActionStallGuard()
        val args = "{\"index\":1,\"text\":\"Hello\",\"submit\":true}"
        assertEquals(ActionStallGuard.Decision.ALLOW, g.check("type_text", args, 10))
        assertEquals(ActionStallGuard.Decision.REPLAN, g.check("type_text", args, 10))
        assertEquals(ActionStallGuard.Decision.STOP, g.check("type_text", args, 10))
    }
}
