package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.criptobot.features.FeatureRow

class RegimeAdaptiveStrategyTest {
    private fun row(
        close: Double,
        stDir: Double,
        ema200: Double,
        rsi: Double = 52.0,
        ret5: Double = 0.0,
        atr: Double = 1.5,
        atrPct: Double = 0.015,
        distToSt: Double = 0.8,
    ) = FeatureRow(
        close, stDir, close - 1, if (stDir >= 0) close * 0.98 else close * 1.02, ema200, rsi, 0.0, 0.0, atr, 1.0, atrPct, distToSt, 1.0,
        0.0, 0.0, 0.0, 0.0, 0.0, ret5, 0.0, 0.0, 12.0, 2.0,
    )

    @Test
    fun `long only on supertrend flip above ema200`() {
        val flip = RegimeAdaptiveStrategy.evaluate(-1.0, row(110.0, 1.0, 100.0), 0.60, 0.40, false, false, false)
        assertEquals("BUY", flip.action)
        val continuation = RegimeAdaptiveStrategy.evaluate(1.0, row(110.0, 1.0, 100.0), 0.60, 0.40, false, false, false)
        assertEquals("HOLD", continuation.action)
        assertEquals("NO_FLIP_OR_REGIME", continuation.reasonCode)
    }

    @Test
    fun `1h supertrend flip does not dump an open long while price holds ema50`() {
        val stillAboveEma50 = FeatureRow(
            105.0, -1.0, 104.0, 103.0, 100.0, 52.0, 0.0, 0.0, 1.5, 1.0, 0.015, 0.8, 1.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0, 2.0,
        )
        val hold = RegimeAdaptiveStrategy.evaluate(1.0, stillAboveEma50, 0.40, 0.50, false, true, false, higherTfDir = 1.0)
        assertEquals("HOLD", hold.action)
        assertEquals("HOLD_OPEN_POSITION", hold.reasonCode)
    }

    @Test
    fun `open long exits when 4h supertrend turns down`() {
        val sell = RegimeAdaptiveStrategy.evaluate(
            1.0, row(105.0, 1.0, 100.0), 0.60, 0.50, false, true, false,
            higherTfDir = -1.0,
            requireHigherTf = true,
        )
        assertEquals("SELL", sell.action)
        assertEquals("EXIT_ON_HIGHER_TF_FLIP", sell.reasonCode)
    }

    @Test
    fun `open long exits below ema200`() {
        val sell = RegimeAdaptiveStrategy.evaluate(1.0, row(95.0, 1.0, 100.0), 0.40, 0.50, false, true, false, higherTfDir = 1.0)
        assertEquals("SELL", sell.action)
        assertEquals("EXIT_BELOW_EMA200", sell.reasonCode)
    }

    @Test
    fun `disabled shorts flatten existing short`() {
        val cover = RegimeAdaptiveStrategy.evaluate(-1.0, row(90.0, -1.0, 100.0), 0.40, 0.55, false, false, true)
        assertEquals("COVER", cover.action)
        assertEquals("SHORTS_DISABLED_FLAT", cover.reasonCode)
    }

    @Test
    fun `short only on supertrend flip when dump conditions hold`() {
        val flip = RegimeAdaptiveStrategy.evaluate(1.0, row(90.0, -1.0, 100.0), 0.35, 0.60, true, false, false)
        assertEquals("SHORT", flip.action)
        val disabled = RegimeAdaptiveStrategy.evaluate(1.0, row(90.0, -1.0, 100.0), 0.35, 0.60, false, false, false)
        assertEquals("HOLD", disabled.action)
    }

    @Test
    fun `1h supertrend flip does not cover an open short while price holds below ema50`() {
        val stillBelowEma50 = FeatureRow(
            90.0, 1.0, 91.0, 92.0, 100.0, 52.0, 0.0, 0.0, 1.5, 1.0, 0.015, 0.8, 1.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0, 2.0,
        )
        val hold = RegimeAdaptiveStrategy.evaluate(
            -1.0, stillBelowEma50, 0.60, 0.60, true, false, true,
            higherTfDir = -1.0,
            requireHigherTf = true,
        )
        assertEquals("HOLD", hold.action)
        assertEquals("HOLD_SHORT_POSITION", hold.reasonCode)
    }

    @Test
    fun `open short covers when 4h supertrend turns up`() {
        val cover = RegimeAdaptiveStrategy.evaluate(
            -1.0, row(90.0, -1.0, 100.0), 0.35, 0.60, true, false, true,
            higherTfDir = 1.0,
            requireHigherTf = true,
        )
        assertEquals("COVER", cover.action)
        assertEquals("COVER_ON_HIGHER_TF_FLIP", cover.reasonCode)
    }

    @Test
    fun `weak market breadth blocks shorts`() {
        val blocked = RegimeAdaptiveStrategy.evaluate(1.0, row(90.0, -1.0, 100.0), 0.35, 0.40, true, false, false)
        assertEquals("BREADTH_BLOCKS_SHORT", blocked.reasonCode)
    }

    @Test
    fun `oversold rsi blocks shorts`() {
        val blocked = RegimeAdaptiveStrategy.evaluate(
            1.0, row(90.0, -1.0, 100.0, rsi = 28.0), 0.35, 0.60, true, false, false,
        )
        assertEquals("SHORT_OVERSOLD", blocked.reasonCode)
    }

    @Test
    fun `ml above short ceiling blocks short`() {
        val blocked = RegimeAdaptiveStrategy.evaluate(1.0, row(90.0, -1.0, 100.0), 0.50, 0.60, true, false, false)
        assertEquals("ML_AGAINST_SHORT", blocked.reasonCode)
    }

    @Test
    fun `breadth and panic atr block new entries`() {
        val longBlocked = RegimeAdaptiveStrategy.evaluate(-1.0, row(110.0, 1.0, 100.0), 0.60, 0.75, false, false, false)
        assertEquals("BREADTH_BLOCKS_LONG", longBlocked.reasonCode)
        val panic = RegimeAdaptiveStrategy.evaluate(-1.0, row(110.0, 1.0, 100.0, atrPct = 0.09), 0.60, 0.40, false, false, false)
        assertEquals("CHOP_OR_PANIC_ATR", panic.reasonCode)
    }

    @Test
    fun `atr stop is at least the configured percent`() {
        val sl = RegimeAdaptiveStrategy.stopLossPrice(100.0, 1.0, 0.05, false)
        assertEquals(95.0, sl, 0.0001)
        val wide = RegimeAdaptiveStrategy.stopLossPrice(100.0, 3.0, 0.05, false)
        assertEquals(92.5, wide, 0.0001)
        assertTrue(RegimeAdaptiveStrategy.HARD_REVERSAL_EXITS.contains("COVER_ON_HIGHER_TF_FLIP"))
        assertFalse(RegimeAdaptiveStrategy.HARD_REVERSAL_EXITS.contains("COVER_ON_SUPERTREND_FLIP"))
        assertFalse(RegimeAdaptiveStrategy.flipped(1.0, 1.0))
        assertTrue(RegimeAdaptiveStrategy.flipped(-1.0, 1.0))
    }

    @Test
    fun `short flip after a short-lived trend is ignored`() {
        val rows = listOf(
            row(110.0, 1.0, 100.0),
            row(109.0, 1.0, 100.0),
            row(90.0, -1.0, 100.0),
        )
        val blocked = RegimeAdaptiveStrategy.evaluate(
            1.0, rows.last(), 0.35, 0.60, true, false, false,
            minPreviousTrendBars = 3,
            previousTrendAge = RegimeAdaptiveStrategy.previousTrendAge(rows, 2),
        )
        assertEquals("TREND_TOO_SHORT", blocked.reasonCode)
    }

    @Test
    fun `1h long flip is blocked when 4h trend is down`() {
        val blocked = RegimeAdaptiveStrategy.evaluate(
            -1.0, row(110.0, 1.0, 100.0), 0.60, 0.40, false, false, false,
            higherTfDir = -1.0,
            requireHigherTf = true,
        )
        assertEquals("HIGHER_TF_AGAINST_LONG", blocked.reasonCode)
        val allowed = RegimeAdaptiveStrategy.evaluate(
            -1.0, row(110.0, 1.0, 100.0), 0.60, 0.40, false, false, false,
            higherTfDir = 1.0,
            requireHigherTf = true,
        )
        assertEquals("BUY", allowed.action)
    }

    @Test
    fun `ml below floor blocks long`() {
        val blocked = RegimeAdaptiveStrategy.evaluate(
            -1.0, row(110.0, 1.0, 100.0), 0.50, 0.40, false, false, false,
            higherTfDir = 1.0,
        )
        assertEquals("ML_AGAINST_LONG", blocked.reasonCode)
    }

    @Test
    fun `long pullback after a recent flip is an entry`() {
        val pullback = RegimeAdaptiveStrategy.evaluate(
            1.0, row(106.0, 1.0, 100.0, rsi = 58.0, ret5 = 0.01, distToSt = 1.2),
            0.60, 0.40, true, false, false,
            previousTrendAge = 3,
            higherTfDir = 1.0,
            requireHigherTf = true,
        )
        assertEquals("BUY", pullback.action)
        assertEquals("ENTRY_ON_PULLBACK", pullback.reasonCode)
    }

    @Test
    fun `extended continuation after flip is not a pullback entry`() {
        val chased = RegimeAdaptiveStrategy.evaluate(
            1.0, row(110.0, 1.0, 100.0, rsi = 72.0, ret5 = 0.05, distToSt = 3.5),
            0.60, 0.40, true, false, false,
            previousTrendAge = 3,
            higherTfDir = 1.0,
            requireHigherTf = true,
        )
        assertEquals("HOLD", chased.action)
        assertEquals("NO_FLIP_OR_REGIME", chased.reasonCode)
    }

    @Test
    fun `short pullback bounce after a dump flip is an entry`() {
        val bounce = RegimeAdaptiveStrategy.evaluate(
            -1.0, row(90.0, -1.0, 100.0, rsi = 42.0, ret5 = -0.01, distToSt = 1.1),
            0.35, 0.60, true, false, false,
            previousTrendAge = 3,
            higherTfDir = -1.0,
            requireHigherTf = true,
        )
        assertEquals("SHORT", bounce.action)
        assertEquals("ENTRY_ON_PULLBACK", bounce.reasonCode)
    }

    @Test
    fun `open long exits when 1h structure breaks below ema50`() {
        val broken = row(102.0, -1.0, 100.0)
        val sell = RegimeAdaptiveStrategy.evaluate(
            1.0,
            FeatureRow(
                102.0, -1.0, 103.0, 105.0, 100.0, 45.0, 0.0, 0.0, 1.5, 1.0, 0.015, 0.8, 1.0,
                0.0, 0.0, 0.0, 0.0, 0.0, -0.01, 0.0, 0.0, 12.0, 2.0,
            ),
            0.40, 0.50, true, true, false,
            higherTfDir = 1.0,
            requireHigherTf = true,
        )
        assertEquals("SELL", sell.action)
        assertEquals("EXIT_1H_STRUCTURE", sell.reasonCode)
        assertTrue(RegimeAdaptiveStrategy.HARD_REVERSAL_EXITS.contains("EXIT_1H_STRUCTURE"))
        assertEquals(102.0, broken.close)
    }

    @Test
    fun `next bar may pull back a little and still confirm`() {
        assertTrue(RegimeAdaptiveStrategy.nextBarConfirmsLong(100.0, 99.0))
        assertTrue(RegimeAdaptiveStrategy.nextBarConfirmsLong(100.0, 102.0))
        assertFalse(RegimeAdaptiveStrategy.nextBarConfirmsLong(100.0, 97.0))
        assertFalse(RegimeAdaptiveStrategy.nextBarConfirmsLong(100.0, 104.0))
        assertTrue(RegimeAdaptiveStrategy.nextBarConfirmsShort(100.0, 101.0))
        assertTrue(RegimeAdaptiveStrategy.nextBarConfirmsShort(100.0, 98.0))
        assertFalse(RegimeAdaptiveStrategy.nextBarConfirmsShort(100.0, 103.0))
        assertFalse(RegimeAdaptiveStrategy.nextBarConfirmsShort(100.0, 96.0))
    }
}
