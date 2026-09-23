package ru.criptobot.position

import ru.criptobot.data.CandleSeries
import kotlin.math.max

enum class VolatilityBucket { CALM, MEDIUM, WILD }

data class InstrumentProfile(val atr: Double, val bucket: VolatilityBucket)

data class ManagedPositionState(
    var entryPrice: Double,
    var remainingQty: Double,
    var hardStop: Double,
    var softStop: Double,
    var tpActivation: Double,
    var trailingActive: Boolean = false,
    var trailingPeak: Double,
    var partialStage: Int = 0,
    var entryBarKey: String,
)

data class ManagementEvent(
    val code: String,
    val message: String,
    val closeFraction: Double = 0.0,
    val fullExit: Boolean = false,
    val details: Map<String, Any?> = emptyMap(),
)

object PositionManagement {
    fun computeProfile(series: CandleSeries?): InstrumentProfile {
        if (series == null || series.size < 20) return InstrumentProfile(0.0, VolatilityBucket.MEDIUM)
        val closes = series.closes()
        val highs = series.highs()
        val lows = series.lows()
        val n = minOf(60, closes.size)
        val start = closes.size - n
        var atrSum = 0.0
        for (i in start + 1 until closes.size) {
            val tr = maxOf(highs[i] - lows[i], kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1]))
            atrSum += tr
        }
        val atr = atrSum / max(1, n - 1)
        val rets = (start + 1 until closes.size).map { (closes[it] / closes[it - 1]) - 1 }
        val vol = if (rets.size > 1) kotlin.math.sqrt(rets.map { it * it }.average()) else 0.015
        val bucket = when {
            vol < 0.012 -> VolatilityBucket.CALM
            vol > 0.022 -> VolatilityBucket.WILD
            else -> VolatilityBucket.MEDIUM
        }
        return InstrumentProfile(atr, bucket)
    }

    fun buildInitialState(entryPrice: Double, qty: Double, series: CandleSeries?, slPct: Double, tpPct: Double, barKey: String): ManagedPositionState {
        val prof = computeProfile(series)
        val atr = max(prof.atr, entryPrice * 1e-6)
        val k = when (prof.bucket) { VolatilityBucket.CALM -> 1.6; VolatilityBucket.MEDIUM -> 2.0; VolatilityBucket.WILD -> 2.6 }
        val hard = entryPrice - max(k * atr, entryPrice * slPct)
        val risk = entryPrice - hard
        return ManagedPositionState(
            entryPrice = entryPrice, remainingQty = qty, hardStop = hard,
            softStop = entryPrice - 0.42 * risk,
            tpActivation = entryPrice + 1.0 * risk,
            trailingPeak = entryPrice, entryBarKey = barKey,
        )
    }

    fun evaluateTick(state: ManagedPositionState, price: Double, barKey: String, profile: InstrumentProfile): Pair<ManagedPositionState, List<ManagementEvent>> {
        val events = mutableListOf<ManagementEvent>()
        if (price <= state.hardStop) {
            events += ManagementEvent("HARD_STOP_HIT", "Hard stop", fullExit = true, details = mapOf("price" to price))
            return state to events
        }
        if (price <= state.softStop && state.partialStage < 1) {
            state.partialStage = 1
            val frac = when (profile.bucket) { VolatilityBucket.CALM -> 0.28; VolatilityBucket.MEDIUM -> 0.38; VolatilityBucket.WILD -> 0.48 }
            events += ManagementEvent("SOFT_STOP_HIT", "Partial exit", closeFraction = frac, details = mapOf("price" to price))
        }
        if (!state.trailingActive && price >= state.tpActivation) {
            state.trailingActive = true
            state.trailingPeak = price
            events += ManagementEvent("TP_ZONE_ENTERED", "Profit zone", details = mapOf("price" to price))
            events += ManagementEvent("TRAILING_ACTIVATED", "Trailing on", closeFraction = 0.28)
            state.partialStage = max(state.partialStage, 2)
        }
        if (state.trailingActive) {
            if (price > state.trailingPeak) state.trailingPeak = price
            val peakMove = state.trailingPeak - state.entryPrice
            val giveback = when (profile.bucket) { VolatilityBucket.CALM -> 0.32; VolatilityBucket.MEDIUM -> 0.38; VolatilityBucket.WILD -> 0.48 } * peakMove
            val minGive = 0.55 * max(profile.atr, state.entryPrice * 1e-6)
            if (peakMove > 0 && price <= state.trailingPeak - max(giveback, minGive)) {
                events += ManagementEvent("TRAILING_EXIT", "Trailing exit", fullExit = true, details = mapOf("price" to price, "peak" to state.trailingPeak))
            }
        }
        return state to events
    }
}
