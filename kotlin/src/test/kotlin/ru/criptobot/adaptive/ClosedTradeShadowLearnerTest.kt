package ru.criptobot.adaptive

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClosedTradeShadowLearnerTest {
    @Test fun `accepts threshold that survives later holdout`() {
        val rows = (0 until 60).map { i ->
            val confidence = if (i % 2 == 0) .75 else .58
            val result = if (confidence >= .70) .8 else -.5
            ShadowClosedTrade("2026-01-${"%02d".format(i / 3 + 1)}-$i", "BUY", confidence, result)
        }
        assertTrue(ClosedTradeShadowLearner.train(rows).single().accepted)
    }

    @Test fun `rejects overfit when later trades lose`() {
        val rows = (0 until 60).map { i ->
            ShadowClosedTrade("2026-01-${"%02d".format(i / 3 + 1)}-$i", "SHORT", .75, if (i < 42) .8 else -.8)
        }
        assertFalse(ClosedTradeShadowLearner.train(rows).single().accepted)
    }

    @Test fun `discovers a correction only when feature effect survives later trades`() {
        val rows = (0 until 100).map { i ->
            val weakVolume = i % 4 == 0
            ShadowClosedTrade(
                "2026-02-${"%03d".format(i)}", "BUY", .72,
                if (weakVolume) -1.2 else .55,
                mapOf("volume_ratio" to if (weakVolume) .20 else 1.40),
            )
        }
        val model = ClosedTradeShadowLearner.train(rows).single()
        assertTrue(model.interventions.any {
            it.accepted && it.feature == "volume_ratio" && it.operator == ">="
        })
    }

    @Test fun `does not accept a feature rule that reverses on holdout`() {
        val rows = (0 until 100).map { i ->
            val highRsi = i % 2 == 0
            val result = if (i < 70) {
                if (highRsi) .8 else -.4
            } else {
                if (highRsi) -.8 else .4
            }
            ShadowClosedTrade(
                "2026-03-${"%03d".format(i)}", "BUY", .72, result,
                mapOf("rsi" to if (highRsi) 72.0 else 45.0),
            )
        }
        val model = ClosedTradeShadowLearner.train(rows).single()
        assertFalse(model.interventions.any { it.accepted && it.feature == "rsi" })
    }
}
