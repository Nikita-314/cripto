package ru.criptobot.broker

import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings

/**
 * Live Binance broker placeholder.
 *
 * Current production path is paper trading against Binance market data.
 * Live order execution will be wired later; until then TRADING_MODE=binance is rejected.
 */
class BinanceClient(private val settings: Settings) : Broker {
    private val log = LoggerFactory.getLogger(BinanceClient::class.java)

    init {
        require(settings.binanceApiKey.isNotBlank() && settings.binanceApiSecret.isNotBlank()) {
            "BINANCE_API_KEY and BINANCE_API_SECRET are required for live Binance trading"
        }
        error(
            "Live Binance trading is not enabled yet. Use TRADING_MODE=paper " +
                "(virtual USDT balance with real Binance market data).",
        )
    }

    override fun getPosition(symbol: String): Pair<Double, Double> = 0.0 to 0.0

    override fun placeOrder(symbol: String, side: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        log.warn("[BINANCE] live orders are disabled; refusing {} {}", side, symbol)
        return null
    }

    override fun getBalanceSnapshot(latestPrices: Map<String, Double>): Map<String, Any> = emptyMap()

    override fun performance(latestPrices: Map<String, Double>): Map<String, Any> = emptyMap()

    override fun getOpenPositions(latestPrices: Map<String, Double>): List<Map<String, Any>> = emptyList()

    override fun resetBalance(initial: Double?): Map<String, Any> = emptyMap()

    override fun resetPerformanceBaseline(latestPrices: Map<String, Double>): Map<String, Any> = emptyMap()
}
