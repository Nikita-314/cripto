package ru.criptobot.data

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.criptobot.features.FeatureEngine
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class DataLoader(private val binanceApiBaseUrl: String = "https://api.binance.com") {
    private val log = LoggerFactory.getLogger(DataLoader::class.java)
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 20_000 }
    }
    private data class CacheEntry(val series: CandleSeries, val expiresAt: Instant)
    private val candleCache = ConcurrentHashMap<String, CacheEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCandles(symbol: String, exchange: String, interval: String = "1h"): CandleSeries? {
        return when (exchange.uppercase()) {
            "BINANCE", "BINANCE_SPOT", "CRYPTO" -> loadBinanceCandles(symbol, interval)
            else -> loadBinanceCandles(symbol, interval)
        }
    }

    suspend fun loadBinanceCandles(symbol: String, interval: String = "1h", daysBack: Int = 90): CandleSeries? {
        val mapped = when (interval.lowercase()) {
            "10m" -> "15m" // Binance has no 10m; MTF filter currently ignores this series
            else -> interval
        }
        if (mapped.equals("4h", ignoreCase = true)) {
            loadBinanceKlines(symbol, "4h", klineLimit(daysBack, "4h"))?.let { return it }
            return loadBinanceKlines(symbol, "1h", klineLimit(daysBack, "1h"))?.let { aggregateFourHour(it) }
        }
        return loadBinanceKlines(symbol, mapped, klineLimit(daysBack, mapped))
    }

    private fun klineLimit(daysBack: Int, interval: String): Int {
        val bars = when (interval.lowercase()) {
            "1m" -> daysBack * 24 * 60
            "5m" -> daysBack * 24 * 12
            "15m" -> daysBack * 24 * 4
            "1h", "60m" -> daysBack * 24
            "4h" -> daysBack * 6
            "1d" -> daysBack
            else -> daysBack * 24
        }
        return bars.coerceIn(50, 1000)
    }

    private suspend fun loadBinanceKlines(symbol: String, interval: String, limit: Int): CandleSeries? {
        val now = Instant.now()
        val cacheKey = "${symbol.uppercase()}:${interval.lowercase()}:$limit"
        candleCache[cacheKey]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.series }

        val binanceInterval = toBinanceInterval(interval)
        val url = "$binanceApiBaseUrl/api/v3/klines?symbol=${symbol.uppercase()}&interval=$binanceInterval&limit=$limit"
        return try {
            val body = getTextWithRetry(url)
            val rows = json.parseToJsonElement(body).jsonArray
            if (rows.isEmpty()) return null
            val candles = rows.mapNotNull { el ->
                val row = el.jsonArray
                val openTime = row.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val open = row.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val high = row.getOrNull(2)?.jsonPrimitive?.content?.toDoubleOrNull() ?: open
                val low = row.getOrNull(3)?.jsonPrimitive?.content?.toDoubleOrNull() ?: open
                val close = row.getOrNull(4)?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val volume = row.getOrNull(5)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val closeTime = row.getOrNull(6)?.jsonPrimitive?.longOrNull ?: openTime
                if (closeTime >= now.toEpochMilli()) return@mapNotNull null
                Candle(
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    time = Instant.ofEpochMilli(openTime),
                )
            }
            if (candles.isEmpty()) return null
            CandleSeries(candles).also {
                candleCache[cacheKey] = CacheEntry(it, nextRefreshAt(now, interval))
            }
        } catch (e: Exception) {
            log.warn("Binance candles failed for {}: {}", symbol, e.message)
            null
        }
    }

    private suspend fun getTextWithRetry(url: String): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = client.get(url)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    error("HTTP ${response.status.value}: ${body.take(120)}")
                }
                return body
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(500L * (1L shl attempt))
            }
        }
        throw lastError ?: IllegalStateException("Binance request failed")
    }

    suspend fun snapshotFromMoex(symbol: String, exchange: String, interval: String): IndicatorSnapshot? =
        snapshotFromMarket(symbol, exchange, interval)

    suspend fun snapshotFromMarket(symbol: String, exchange: String, interval: String): IndicatorSnapshot? {
        val series = loadCandles(symbol, exchange, interval) ?: return null
        if (series.size < 50) return null
        val features = FeatureEngine.buildFeatures(series) ?: return null
        val last = features.last()
        val price = last.close ?: return null
        return IndicatorSnapshot(
            symbol = symbol,
            price = price,
            ema20 = last.ema50 ?: price,
            ema50 = last.ema50 ?: price,
            rsi = last.rsi ?: 50.0,
            adx = 25.0,
            macd = last.macd ?: 0.0,
            macdSignal = last.macdSignal ?: 0.0,
            supertrend = last.stLine ?: 0.0,
            supertrendDirection = last.stDir ?: 0.0,
        )
    }

    suspend fun loadMoexLastPrices(symbols: Set<String>): Map<String, Double> =
        loadBinanceLastPrices(symbols)

    suspend fun loadBinanceLastPrices(symbols: Set<String>): Map<String, Double> {
        if (symbols.isEmpty()) return emptyMap()
        val wanted = symbols.map { it.uppercase() }.toSet()
        val result = mutableMapOf<String, Double>()
        try {
            val body = getTextWithRetry("$binanceApiBaseUrl/api/v3/ticker/price")
            json.parseToJsonElement(body).jsonArray.forEach { el ->
                val obj = el.jsonObject
                val symbol = obj["symbol"]?.jsonPrimitive?.content?.uppercase() ?: return@forEach
                if (symbol !in wanted) return@forEach
                val price = obj["price"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (price != null && price > 0) result[symbol] = price
            }
        } catch (e: Exception) {
            log.warn("Binance last prices failed: {}", e.message)
            // Fallback: fetch individually for open positions
            for (symbol in wanted) {
                if (symbol in result) continue
                runCatching {
                    val body = getTextWithRetry("$binanceApiBaseUrl/api/v3/ticker/price?symbol=$symbol")
                    val price = json.parseToJsonElement(body).jsonObject["price"]
                        ?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (price != null && price > 0) result[symbol] = price
                }
            }
        }
        return result
    }

    fun close() = client.close()

    internal fun aggregateFourHour(series: CandleSeries, now: Instant = Instant.now()): CandleSeries {
        val zone = ZoneOffset.UTC
        val grouped = series.candles.groupBy { candle ->
            val local = candle.time?.atZone(zone)
            if (local == null) "unknown-${series.candles.indexOf(candle)}"
            else "${local.toLocalDate()}-${local.hour / 4}"
        }
        val bars = grouped.values.map { group ->
            Candle(
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                time = group.first().time,
            )
        }.sortedBy { it.time ?: Instant.EPOCH }
        val last = bars.lastOrNull() ?: return CandleSeries(emptyList())
        val lastLocal = last.time?.atZone(zone)
        val nowLocal = now.atZone(zone)
        val forming = lastLocal != null &&
            lastLocal.toLocalDate() == nowLocal.toLocalDate() &&
            lastLocal.hour / 4 == nowLocal.hour / 4
        return CandleSeries(if (forming) bars.dropLast(1) else bars)
    }

    companion object {
        internal fun toBinanceInterval(interval: String): String = when (interval.lowercase()) {
            "1m" -> "1m"
            "5m" -> "5m"
            "10m", "15m" -> "15m"
            "1h", "60m" -> "1h"
            "4h" -> "4h"
            "1d", "d" -> "1d"
            "1w", "w" -> "1w"
            else -> "1h"
        }

        internal fun nextRefreshAt(now: Instant, interval: String): Instant {
            val seconds = when (interval.lowercase()) {
                "1m" -> 60L
                "5m" -> 5 * 60L
                "10m", "15m" -> 15 * 60L
                "1h" -> 60 * 60L
                "4h" -> 4 * 60 * 60L
                "1d" -> 24 * 60 * 60L
                "1w" -> 7 * 24 * 60 * 60L
                else -> 60 * 60L
            }
            val nextBoundary = ((now.epochSecond / seconds) + 1) * seconds
            return Instant.ofEpochSecond(nextBoundary + 15)
        }

        internal fun isFreshForTrading(candleTime: Instant, interval: String, now: Instant = Instant.now()): Boolean {
            val maxAgeSeconds = when (interval.lowercase()) {
                "1m" -> 5 * 60L
                "5m" -> 15 * 60L
                "10m", "15m" -> 45 * 60L
                "1h" -> 3 * 60 * 60L
                "4h" -> 10 * 60 * 60L
                "1d" -> 3 * 24 * 60 * 60L
                "1w" -> 10 * 24 * 60 * 60L
                else -> 3 * 60 * 60L
            }
            val ageSeconds = now.epochSecond - candleTime.epochSecond
            return ageSeconds in 0..maxAgeSeconds
        }
    }
}
