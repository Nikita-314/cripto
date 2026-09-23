package ru.criptobot.broker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.criptobot.config.Settings
import java.nio.file.Path

class PaperBrokerTest {
    @TempDir
    lateinit var projectRoot: Path

    @Test
    fun `restart keeps market marks used by performance baseline`() {
        val settings = Settings.load(projectRoot)
        val broker = PaperBroker(settings)
        broker.placeOrder("TEST", "BUY", 10_000.0, 100.0)

        broker.resetPerformanceBaseline(mapOf("TEST" to 120.0))
        assertEquals(0.0, broker.getBalanceSnapshot(emptyMap()).double("total_pnl_rub"), 1e-9)

        val restarted = PaperBroker(settings)
        val snapshot = restarted.getBalanceSnapshot(emptyMap())

        assertEquals(0.0, snapshot.double("total_pnl_rub"), 1e-9)
        assertEquals(0.0, snapshot.double("unrealized_pnl_rub"), 1e-9)
    }

    private fun Map<String, Any>.double(key: String) = (getValue(key) as Number).toDouble()
}
