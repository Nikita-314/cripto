package ru.criptobot.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DataLoaderTest {
    @Test
    fun `maps common intervals to Binance intervals`() {
        assertEquals("1h", DataLoader.toBinanceInterval("1h"))
        assertEquals("15m", DataLoader.toBinanceInterval("15m"))
        assertEquals("1d", DataLoader.toBinanceInterval("1d"))
    }

    @Test
    fun `hourly cache expires shortly after next bar boundary`() {
        val now = Instant.parse("2026-07-17T10:37:00Z")
        val refreshAt = DataLoader.nextRefreshAt(now, "1h")

        assertEquals(Instant.parse("2026-07-17T11:00:15Z"), refreshAt)
        assertTrue(refreshAt.isAfter(now))
    }

    @Test
    fun `old hourly candle is stale`() {
        val fridayCandle = Instant.parse("2026-07-17T20:00:00Z")
        val later = Instant.parse("2026-07-18T12:00:00Z")

        assertTrue(!DataLoader.isFreshForTrading(fridayCandle, "1h", later))
    }

    @Test
    fun `recent closed hourly candle is tradable`() {
        val candle = Instant.parse("2026-07-17T10:00:00Z")
        val now = Instant.parse("2026-07-17T11:05:00Z")

        assertTrue(DataLoader.isFreshForTrading(candle, "1h", now))
    }
}
