package ru.criptobot.broker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TradingSessionTest {
    @Test
    fun `session starts at UTC midnight`() {
        val midday = Instant.parse("2026-07-27T12:00:00Z")
        assertEquals(Instant.parse("2026-07-27T00:00:00Z"), TradingSession.currentDayStart(midday))
    }

    @Test
    fun `new period starts once after UTC midnight`() {
        val before = Instant.parse("2026-07-26T23:59:59Z")
        val after = Instant.parse("2026-07-27T00:00:01Z")

        assertFalse(TradingSession.shouldStartNewPeriod("2026-07-26T00:00:00Z", before))
        assertTrue(TradingSession.shouldStartNewPeriod("2026-07-26T00:00:00Z", after))
        assertFalse(TradingSession.shouldStartNewPeriod("2026-07-27T00:00:00Z", after))
    }
}
