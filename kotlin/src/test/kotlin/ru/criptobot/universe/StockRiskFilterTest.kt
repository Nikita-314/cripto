package ru.criptobot.universe

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.criptobot.data.StockInstrument

class StockRiskFilterTest {
    private val filter = StockRiskFilter()

    private fun inst(symbol: String, name: String = symbol.removeSuffix("USDT")) =
        StockInstrument(symbol, name, "BINANCE", tradable = true, status = "TRADING")

    @Test
    fun `keeps liquid crypto and drops stables and xstocks`() {
        val kept = filter.deterministicFilter(
            listOf(
                inst("BTCUSDT", "BTC"),
                inst("BNBUSDT", "BNB"),
                inst("BCHUSDT", "BCH"),
                inst("CKBUSDT", "CKB"),
                inst("FDUSDUSDT", "FDUSD"),
                inst("USDCUSDT", "USDC"),
                inst("EURUSDT", "EUR"),
                inst("SNDKBUSDT", "SNDKB"),
                inst("CRCLBUSDT", "CRCLB"),
                inst("MUBUSDT", "MUB"),
                inst("UUSDT", "U"),
            ),
        ).map { it.symbol }.toSet()

        assertTrue(kept.containsAll(listOf("BTCUSDT", "BNBUSDT", "BCHUSDT", "CKBUSDT")))
        assertFalse(kept.contains("FDUSDUSDT"))
        assertFalse(kept.contains("USDCUSDT"))
        assertFalse(kept.contains("EURUSDT"))
        assertFalse(kept.contains("SNDKBUSDT"))
        assertFalse(kept.contains("CRCLBUSDT"))
        assertFalse(kept.contains("MUBUSDT"))
        assertFalse(kept.contains("UUSDT"))
    }

    @Test
    fun `tokenized stock permission group is excluded even for unknown tickers`() {
        assertTrue(
            CryptoUniverseExclusions.shouldExclude(
                "NEWSTOCK",
                "NEWSTOCKUSDT",
                setOf(CryptoUniverseExclusions.TOKENIZED_STOCK_PERMISSION),
            ),
        )
        assertFalse(CryptoUniverseExclusions.shouldExclude("BTC", "BTCUSDT"))
    }
}
