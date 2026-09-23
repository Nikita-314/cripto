package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.criptobot.features.FeatureRow

class MultiTimeframeEntryFilterTest {
    private fun row(st: Double, close: Double, ema: Double, ret5: Double) = FeatureRow(
        close, st, close, close, ema, 55.0, 0.0, 0.0, 1.0, 1.0, 0.01, 1.0, 1.0,
        0.0, 0.0, 0.0, 0.0, 0.0, ret5, 0.0, 0.0, 12.0, 2.0,
    )

    @Test fun `allows long when 4h is not against even if breadth is weak`() {
        val fourHour = row(1.0, 110.0, 100.0, .01)
        assertTrue(MultiTimeframeEntryFilter.evaluate("BUY", .55, fourHour, null, .40, false).allowed)
        assertTrue(MultiTimeframeEntryFilter.evaluate("BUY", .55, null, null, .40, false).allowed)
    }

    @Test fun `blocks long when 4h supertrend is not up`() {
        val against = MultiTimeframeEntryFilter.evaluate(
            "BUY", .80, row(-1.0, 110.0, 100.0, .01), row(1.0, 110.0, 100.0, .01), .80, true,
        )
        assertFalse(against.allowed)
        assertEquals("MTF_4H_AGAINST_LONG", against.reasonCode)
    }

    @Test fun `blocks short unless 4h is down below ema200`() {
        val against = MultiTimeframeEntryFilter.evaluate(
            "SHORT", .70,
            row(-1.0, 110.0, 100.0, -.01),
            row(-1.0, 90.0, 100.0, -.01),
            .70,
            false,
        )
        assertFalse(against.allowed)
        assertEquals("MTF_4H_AGAINST_SHORT", against.reasonCode)
        val allowed = MultiTimeframeEntryFilter.evaluate(
            "SHORT", .70,
            row(-1.0, 90.0, 100.0, -.01),
            null,
            .70,
            false,
        )
        assertTrue(allowed.allowed)
    }
}
