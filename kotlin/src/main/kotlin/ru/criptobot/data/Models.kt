package ru.criptobot.data

import java.time.Instant

data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val time: Instant? = null,
)

data class CandleSeries(
    val candles: List<Candle>,
) {
    val size get() = candles.size
    fun closes() = candles.map { it.close }
    fun highs() = candles.map { it.high }
    fun lows() = candles.map { it.low }
    fun volumes() = candles.map { it.volume }
    fun lastClose(): Double? = candles.lastOrNull()?.close
}

data class IndicatorSnapshot(
    val symbol: String,
    val price: Double,
    val ema20: Double,
    val ema50: Double,
    val rsi: Double,
    val adx: Double,
    val macd: Double,
    val macdSignal: Double,
    val supertrend: Double = 0.0,
    val supertrendDirection: Double = 0.0,
)

data class StockInstrument(
    val symbol: String,
    val name: String = "",
    val exchange: String = "",
    val tradable: Boolean = true,
    val status: String = "",
    val lotSize: Int = 1,
    val quoteVolume24h: Double = 0.0,
)
