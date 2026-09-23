package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrailingTakeProfitTest {
    @Test
    fun `long activates at target rides peak and exits on pullback`() {
        val state = position("LONG", stop = 90.0, activation = 110.0)
        assertNull(TrailingTakeProfit.evaluate(state, 109.0).exitReason)
        val activated = TrailingTakeProfit.evaluate(state, 110.0)
        assertTrue(activated.activatedNow)
        assertNull(activated.exitReason)
        assertNull(TrailingTakeProfit.evaluate(state, 120.0).exitReason)
        val exit = TrailingTakeProfit.evaluate(state, 118.0)
        assertEquals("Trailing take-profit", exit.exitReason)
        assertEquals(118.2, exit.triggerPrice!!, 0.0001)
    }

    @Test
    fun `long trailing trigger never moves below activation`() {
        val state = position("LONG", stop = 90.0, activation = 110.0)
        TrailingTakeProfit.evaluate(state, 110.0)
        val exit = TrailingTakeProfit.evaluate(state, 109.9)
        assertEquals("Trailing take-profit", exit.exitReason)
        assertEquals(110.0, exit.triggerPrice)
    }

    @Test
    fun `short trailing is mirrored`() {
        val state = position("SHORT", stop = 110.0, activation = 90.0)
        assertTrue(TrailingTakeProfit.evaluate(state, 90.0).activatedNow)
        assertNull(TrailingTakeProfit.evaluate(state, 80.0).exitReason)
        val exit = TrailingTakeProfit.evaluate(state, 82.0)
        assertEquals("Trailing take-profit", exit.exitReason)
        assertEquals(81.2, exit.triggerPrice!!, 0.0001)
    }

    @Test
    fun `stop loss works before activation`() {
        val state = position("LONG", stop = 90.0, activation = 110.0)
        val exit = TrailingTakeProfit.evaluate(state, 89.0)
        assertEquals("Stop-loss", exit.exitReason)
        assertFalse(state.activated)
    }

    private fun position(direction: String, stop: Double, activation: Double) = TrailingPosition(
        "TEST", direction, 100.0, stop, activation, 0.015, activation, false, "2026-07-26T00:00:00Z",
    )
}
