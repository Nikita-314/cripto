package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrendGateTest {
    @Test
    fun `strict mode requires supertrend and ema200 in paper`() {
        val eval = TrendGate.evaluate(stDir = 1.0, close = 100.0, ema200 = 110.0, configuredMode = "strict", tradingMode = "paper")
        assertEquals("strict", eval.effectiveMode)
        assertFalse(eval.trendOk)
        assertTrue(eval.trendSupertrendOk)
        assertFalse(eval.trendCloseAboveEma200)
        assertEquals("ema200_failed", eval.blockDetail)
    }

    @Test
    fun `strict default when mode unset uses strict semantics`() {
        val eval = TrendGate.evaluate(stDir = 1.0, close = 120.0, ema200 = 110.0, configuredMode = "strict", tradingMode = "paper")
        assertTrue(eval.trendOk)
        assertNull(eval.blockDetail)
    }

    @Test
    fun `supertrend mode ignores ema200 in paper`() {
        val eval = TrendGate.evaluate(stDir = 1.0, close = 100.0, ema200 = 110.0, configuredMode = "supertrend", tradingMode = "paper")
        assertEquals("supertrend", eval.effectiveMode)
        assertTrue(eval.trendOk)
        assertFalse(eval.trendCloseAboveEma200)
    }

    @Test
    fun `supertrend mode forced to strict outside paper`() {
        val eval = TrendGate.evaluate(stDir = 1.0, close = 120.0, ema200 = 110.0, configuredMode = "supertrend", tradingMode = "binance")
        assertEquals("strict", eval.effectiveMode)
        assertTrue(eval.trendOk)
    }

    @Test
    fun `both_failed when supertrend down and close below ema200 in strict mode`() {
        val eval = TrendGate.evaluate(stDir = -1.0, close = 100.0, ema200 = 110.0, configuredMode = "strict", tradingMode = "paper")
        assertEquals("both_failed", eval.blockDetail)
    }
}
