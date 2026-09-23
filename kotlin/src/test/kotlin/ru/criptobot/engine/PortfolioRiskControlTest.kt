package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioRiskControlTest {
    @Test
    fun `normal portfolio keeps full position size`() {
        val decision = PortfolioRiskControl.evaluate(50_000.0, 48_000.0)

        assertEquals(1.0, decision.positionMultiplier)
        assertFalse(decision.requireBarConfirmation)
    }

    @Test
    fun `five percent drawdown halves size and requires confirmation`() {
        val decision = PortfolioRiskControl.evaluate(50_000.0, 47_000.0)

        assertEquals(0.5, decision.positionMultiplier)
        assertTrue(decision.requireBarConfirmation)
    }

    @Test
    fun `ten percent drawdown limits position to quarter size`() {
        val decision = PortfolioRiskControl.evaluate(50_000.0, 44_000.0)

        assertEquals(0.25, decision.positionMultiplier)
        assertTrue(decision.requireBarConfirmation)
    }
}
