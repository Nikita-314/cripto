package ru.criptobot.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ShadowShortPaperSimulatorTest {
    @TempDir lateinit var temp: Path

    @Test fun `opens and closes isolated short with trailing profit`() {
        val state = temp.resolve("shadow.json")
        val simulator = ShadowShortPaperSimulator(state)
        val features = mapOf("rsi" to 45.0, "return_5" to -0.01)

        assertEquals(
            "OPEN",
            simulator.observe(
                ShadowShortObservation("2026-08-10T10:00:00Z", "TEST", 100.0, 0.80, true, false, features),
                allowEntry = true,
            ),
        )
        simulator.observe(
            ShadowShortObservation("2026-08-10T11:00:00Z", "TEST", 90.0, 0.80, false, false, features),
            allowEntry = false,
        )
        assertEquals(
            "CLOSE:TRAILING_TAKE_PROFIT",
            simulator.observe(
                ShadowShortObservation("2026-08-10T12:00:00Z", "TEST", 92.0, 0.80, false, false, features),
                allowEntry = false,
            ),
        )

        val snapshot = simulator.snapshot()
        assertTrue(snapshot.positions.isEmpty())
        assertEquals(1, snapshot.closedTrades.size)
        assertTrue(snapshot.closedTrades.single().netPnlRub > 0.0)
        assertEquals("SHADOW_ONLY_NO_BROKER_ORDERS", snapshot.mode)
        assertEquals(1, ShadowShortPaperSimulator.loadLearningTrades(state).size)
    }

    @Test fun `does not open without explicit shadow permission`() {
        val simulator = ShadowShortPaperSimulator(temp.resolve("blocked.json"))
        val result = simulator.observe(
            ShadowShortObservation("2026-08-10T10:00:00Z", "TEST", 100.0, 0.80, true, false),
            allowEntry = false,
        )
        assertEquals(null, result)
        assertTrue(simulator.snapshot().positions.isEmpty())
    }
}
