package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfitabilityEntryGuardTest {
    @Test fun `capital sizing boosts while at least half remains free`() {
        val result = CapitalAwarePositionSizer.chooseTarget(100_000.0, 50_000.0, 3_000.0, 10_000.0)
        assertTrue(result.boosted)
        assertEquals(10_000.0, result.configuredTargetRub)
    }

    @Test fun `capital sizing returns to normal after more than half is invested`() {
        val result = CapitalAwarePositionSizer.chooseTarget(100_000.0, 49_999.0, 3_000.0, 10_000.0)
        assertFalse(result.boosted)
        assertEquals(3_000.0, result.configuredTargetRub)
    }

    @Test fun `unavoidable single lot can exceed monetary risk budget`() {
        val result = ProfitabilityEntryGuard.evaluate(
            "BUY", 50.0, 0.0, 2.0, 10_000.0, 0.03, 11_000.0, false, 0.80,
            allowUnavoidableSingleLotRisk = true,
        )
        assertTrue(result.allowed)
    }

    @Test fun `allows historically healthy entry`() {
        val result = ProfitabilityEntryGuard.evaluate("BUY", 50.0, 0.01, 2.5, 1_000.0, 0.03, 90_000.0, false, 0.70)
        assertTrue(result.allowed)
    }

    @Test fun `blocks extended impulse`() {
        val result = ProfitabilityEntryGuard.evaluate("BUY", 60.0, 0.05, 2.5, 1_000.0, 0.03, 90_000.0, false, 0.70)
        assertFalse(result.allowed)
        assertEquals("ENTRY_CHASING_EXTENDED_MOVE", result.reasonCode)
    }

    @Test fun `blocks oversized minimum lot risk`() {
        val result = ProfitabilityEntryGuard.evaluate("BUY", 50.0, 0.0, 2.0, 20_000.0, 0.03, 90_000.0, false, 0.70)
        assertFalse(result.allowed)
        assertEquals("MINIMUM_LOT_RISK_TOO_HIGH", result.reasonCode)
    }

    @Test fun `requires selective long in bearish breadth`() {
        val result = ProfitabilityEntryGuard.evaluate("BUY", 61.0, 0.03, 3.2, 1_000.0, 0.03, 90_000.0, true, 0.70)
        assertFalse(result.allowed)
        assertEquals("BEARISH_REGIME_LONG_NOT_SELECTIVE", result.reasonCode)
    }

    @Test fun `blocks short when rsi is already oversold`() {
        val result = ProfitabilityEntryGuard.evaluate("SHORT", 28.0, -0.01, 1.0, 100.0, 0.05, 1_000.0, true, 0.70)
        assertFalse(result.allowed)
        assertEquals("SHORT_OVERSOLD_BOUNCE_RISK", result.reasonCode)
    }

    @Test fun `regime adaptive allows flip after damaged momentum`() {
        val result = ProfitabilityEntryGuard.evaluate("BUY", 42.0, -0.012, 0.4, 1_000.0, 0.03, 90_000.0, false, 0.55)
        assertTrue(result.allowed)
    }

    @Test fun `walk forward v3 still blocks weak confidence`() {
        val result = ProfitabilityEntryGuard.evaluate(
            "BUY", 55.0, 0.0, 1.0, 1_000.0, 0.03, 90_000.0, false, 0.64,
            filterSet = EntryFilterSet.WALK_FORWARD_V3,
        )
        assertFalse(result.allowed)
        assertEquals("LONG_CONFIDENCE_BELOW_WALK_FORWARD_FLOOR", result.reasonCode)
    }

    @Test fun `walk forward v3 still blocks damaged momentum`() {
        val result = ProfitabilityEntryGuard.evaluate(
            "BUY", 55.0, -0.009, 1.0, 1_000.0, 0.03, 90_000.0, false, 0.70,
            filterSet = EntryFilterSet.WALK_FORWARD_V3,
        )
        assertFalse(result.allowed)
        assertEquals("LONG_MOMENTUM_DAMAGED", result.reasonCode)
    }

    @Test fun `live sizing uses risk budget instead of minimum order`() {
        val result = LivePositionSizer.calculate(100.0, 1, 1_000.0, 10_000.0, 90_000.0, 0.03)
        assertEquals(90, result.quantity)
        assertEquals(9_000.0, result.notionalRub)
        assertEquals(270.0, result.monetaryRiskRub)
    }

    @Test fun `live sizing preserves exchange lot multiple`() {
        val result = LivePositionSizer.calculate(20.0, 10, 1_000.0, 10_000.0, 90_000.0, 0.03)
        assertEquals(450, result.quantity)
        assertEquals(9_000.0, result.notionalRub)
    }

    @Test fun `minimum lot remains visible for later risk rejection`() {
        val result = LivePositionSizer.calculate(18_000.0, 1, 1_000.0, 10_000.0, 90_000.0, 0.03)
        assertEquals(1, result.quantity)
        assertEquals(18_000.0, result.notionalRub)
        assertTrue(result.monetaryRiskRub > result.monetaryRiskLimitRub)
    }
}
