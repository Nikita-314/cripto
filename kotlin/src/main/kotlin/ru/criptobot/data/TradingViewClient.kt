package ru.criptobot.data

import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings

/** Market snapshot from Binance klines / indicators. */
class TradingViewClient(
    private val settings: Settings,
    private val dataLoader: DataLoader,
) {
    private val log = LoggerFactory.getLogger(TradingViewClient::class.java)

    suspend fun getSnapshot(symbol: String): IndicatorSnapshot {
        val snap = dataLoader.snapshotFromMarket(symbol, settings.exchange, settings.interval)
        if (snap != null && snap.price > 0) return snap
        throw RuntimeException("Cannot get market snapshot for $symbol")
    }
}
