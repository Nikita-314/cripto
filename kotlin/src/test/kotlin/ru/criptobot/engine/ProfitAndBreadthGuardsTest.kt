package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfitAndBreadthGuardsTest {
    @Test
    fun `profit guard activates after a thirty five percent giveback`() {
        val decision = DailyProfitGuard.evaluate(
            equityRub = 100_000.0,
            currentRealizedRub = 640.0,
            peakRealizedRub = 1_000.0,
        )

        assertTrue(decision.active)
    }

    @Test
    fun `profit guard stays inactive before meaningful daily profit`() {
        val decision = DailyProfitGuard.evaluate(
            equityRub = 100_000.0,
            currentRealizedRub = 200.0,
            peakRealizedRub = 400.0,
        )

        assertFalse(decision.active)
    }

    @Test
    fun `breadth guard detects broad decline`() {
        val sample = (1..30).map {
            mapOf<String, Any>("supertrend_direction" to if (it <= 19) -1.0 else 1.0)
        }

        assertTrue(MarketBreadthGuard.evaluate(sample).bearish)
    }

    @Test
    fun `ordinary minimum order keeps base confidence threshold`() {
        val decision = ExpensiveEntryConfidence.evaluate(
            confidence = 0.20,
            minimumOrderRub = 1_200.0,
            standardOrderRub = 1_000.0,
            baseThreshold = 0.10,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun `expensive indivisible share requires very high confidence`() {
        val decision = ExpensiveEntryConfidence.evaluate(
            confidence = 0.63,
            minimumOrderRub = 11_630.0,
            standardOrderRub = 1_000.0,
            baseThreshold = 0.10,
        )

        assertFalse(decision.allowed)
    }
}
