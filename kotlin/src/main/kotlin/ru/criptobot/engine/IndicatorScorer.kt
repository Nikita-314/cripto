package ru.criptobot.engine

import ru.criptobot.data.IndicatorSnapshot
import ru.criptobot.util.clamp

class IndicatorScorer {
    fun score(snapshot: IndicatorSnapshot, strategy: StrategyConfig): Double {
        val emaSignal = if (snapshot.ema20 >= snapshot.ema50) 1.0 else -1.0
        val macdSignal = if (snapshot.macd >= snapshot.macdSignal) 1.0 else -1.0
        var rsiSignal = 0.0
        if (snapshot.rsi > 0) {
            rsiSignal = when {
                snapshot.rsi <= strategy.rsiOversold -> 1.0
                snapshot.rsi >= strategy.rsiOverbought -> -1.0
                else -> 0.0
            }
        }
        val stSignal = if (snapshot.supertrendDirection != 0.0) snapshot.supertrendDirection else 0.0
        val totalWeight = strategy.emaWeight + strategy.macdWeight + strategy.rsiWeight +
            if (stSignal != 0.0) strategy.supertrendWeight else 0.0
        if (totalWeight <= 0) return 0.0
        var raw = (emaSignal * strategy.emaWeight + macdSignal * strategy.macdWeight +
            rsiSignal * strategy.rsiWeight + stSignal * strategy.supertrendWeight) / totalWeight
        if (snapshot.adx > 20) {
            val adxBoost = clamp((snapshot.adx - 20) / 30, 0.0, 1.0)
            raw *= 1 + strategy.adxWeight * adxBoost
        }
        return clamp(raw, -1.0, 1.0)
    }
}
