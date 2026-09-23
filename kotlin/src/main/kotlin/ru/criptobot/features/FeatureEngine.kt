package ru.criptobot.features

import ru.criptobot.data.CandleSeries
import kotlin.math.abs

data class FeatureRow(
    val close: Double?,
    val stDir: Double?,
    val stLine: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val rsi: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val atr: Double?,
    val volRatio: Double?,
    val atrPct: Double?,
    val distToSt: Double?,
    val closeAboveSt: Double?,
    val emaDist50: Double?,
    val emaDist200: Double?,
    val macdHist: Double?,
    val ret1: Double?,
    val ret3: Double?,
    val ret5: Double?,
    val ret10: Double?,
    val volatility: Double?,
    val hour: Double?,
    val dow: Double?,
)

object FeatureEngine {
    fun buildFeatures(series: CandleSeries): List<FeatureRow>? {
        val n = series.size
        if (n < 50) return null
        val closes = series.closes()
        val highs = series.highs()
        val lows = series.lows()
        val volumes = series.volumes()

        val atr = computeAtr(highs, lows, closes, 14)
        val (stLine, stDir) = computeSupertrend(highs, lows, closes, 10, 3.0)
        val ema50 = computeEma(closes, 50)
        val ema200 = computeEma(closes, 200)
        val rsi = computeRsi(closes, 14)
        val (macdLine, macdSignal, macdHist) = computeMacd(closes)

        val rows = mutableListOf<FeatureRow>()
        for (i in closes.indices) {
            val close = closes[i]
            val atrVal = atr[i]
            val st = stLine[i]
            val volMa = if (i >= 19) volumes.subList(i - 19, i + 1).average() else volumes.take(i + 1).average()
            val volRatio = if (volMa > 0) volumes[i] / volMa else 1.0
            val ret1 = if (i >= 1) (close / closes[i - 1]) - 1.0 else null
            val ret3 = if (i >= 3) (close / closes[i - 3]) - 1.0 else null
            val ret5 = if (i >= 5) (close / closes[i - 5]) - 1.0 else null
            val ret10 = if (i >= 10) (close / closes[i - 10]) - 1.0 else null
            val volSlice = (maxOf(0, i - 19)..i).mapNotNull { j ->
                if (j >= 1) (closes[j] / closes[j - 1]) - 1.0 else null
            }
            val volatility = if (volSlice.size >= 2) stdDev(volSlice) else 0.0
            val ema50v = ema50[i]
            val ema200v = ema200[i]
            rows += FeatureRow(
                close = close,
                stDir = stDir[i],
                stLine = st,
                ema50 = ema50v,
                ema200 = ema200v,
                rsi = rsi[i],
                macd = macdLine[i],
                macdSignal = macdSignal[i],
                atr = atrVal,
                volRatio = volRatio,
                atrPct = if (close != 0.0) atrVal / close else 0.0,
                distToSt = if (atrVal != 0.0) (close - st) / atrVal else 0.0,
                closeAboveSt = if (close > st) 1.0 else 0.0,
                emaDist50 = if (close != 0.0) (close - ema50v) / close else 0.0,
                emaDist200 = if (close != 0.0) (close - ema200v) / close else 0.0,
                macdHist = macdHist[i],
                ret1 = ret1,
                ret3 = ret3,
                ret5 = ret5,
                ret10 = ret10,
                volatility = volatility,
                hour = series.candles[i].time?.atOffset(java.time.ZoneOffset.UTC)?.hour?.toDouble() ?: 12.0,
                dow = series.candles[i].time?.atOffset(java.time.ZoneOffset.UTC)?.dayOfWeek?.value?.toDouble() ?: 2.0,
            )
        }
        return rows.drop(50)
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return kotlin.math.sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    private fun computeAtr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int): List<Double> {
        val tr = mutableListOf<Double>()
        for (i in closes.indices) {
            val h = highs[i]; val l = lows[i]
            val prev = if (i > 0) closes[i - 1] else closes[i]
            tr += maxOf(h - l, abs(h - prev), abs(l - prev))
        }
        return rollingMean(tr, period)
    }

    private fun computeSupertrend(
        highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int, multiplier: Double,
    ): Pair<List<Double>, List<Double>> {
        val atr = computeAtr(highs, lows, closes, period)
        val st = MutableList(closes.size) { 0.0 }
        val dir = MutableList(closes.size) { 0.0 }
        var supPrev = 0.0
        var dirPrev = 1.0
        for (i in closes.indices) {
            if (i < period) continue
            val hl2 = (highs[i] + lows[i]) / 2.0
            val upper = hl2 + multiplier * atr[i]
            val lower = hl2 - multiplier * atr[i]
            val c = closes[i]
            val (sup, dirVal) = if (i == period) {
                lower to 1.0
            } else if (dirPrev == 1.0) {
                when {
                    lower > supPrev -> lower to 1.0
                    c < supPrev -> upper to -1.0
                    else -> supPrev to 1.0
                }
            } else {
                when {
                    upper < supPrev -> upper to -1.0
                    c > supPrev -> lower to 1.0
                    else -> supPrev to -1.0
                }
            }
            st[i] = sup; dir[i] = dirVal
            supPrev = sup; dirPrev = dirVal
        }
        return st to dir
    }

    private fun computeEma(values: List<Double>, period: Int): List<Double> {
        val k = 2.0 / (period + 1)
        val out = MutableList(values.size) { values[0] }
        for (i in 1 until values.size) out[i] = values[i] * k + out[i - 1] * (1 - k)
        return out
    }

    private fun computeRsi(closes: List<Double>, period: Int): List<Double> {
        val out = MutableList(closes.size) { 50.0 }
        for (i in period until closes.size) {
            var gain = 0.0; var loss = 0.0
            for (j in i - period + 1..i) {
                val d = closes[j] - closes[j - 1]
                if (d > 0) gain += d else loss -= d
            }
            val rs = if (loss == 0.0) 100.0 else gain / loss
            out[i] = 100.0 - (100.0 / (1.0 + rs))
        }
        return out
    }

    private fun computeMacd(closes: List<Double>): Triple<List<Double>, List<Double>, List<Double>> {
        val ema12 = computeEma(closes, 12)
        val ema26 = computeEma(closes, 26)
        val macd = closes.indices.map { ema12[it] - ema26[it] }
        val signal = computeEma(macd, 9)
        val hist = macd.indices.map { macd[it] - signal[it] }
        return Triple(macd, signal, hist)
    }

    private fun rollingMean(values: List<Double>, period: Int): List<Double> {
        return values.indices.map { i ->
            val start = maxOf(0, i - period + 1)
            values.subList(start, i + 1).average()
        }
    }
}
