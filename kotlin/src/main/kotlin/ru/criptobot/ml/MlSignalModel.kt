package ru.criptobot.ml

import ru.criptobot.data.CandleSeries
import ru.criptobot.features.FeatureEngine
import ru.criptobot.features.FeatureRow
import ru.criptobot.util.clamp

/**
 * Pure-Kotlin ML gate: feature-based P(up) estimator.
 * Kept intentionally lightweight so production has no external model runtime.
 */
class MlSignalModel {
    fun predictFromSeries(series: CandleSeries): Double? {
        val features = FeatureEngine.buildFeatures(series) ?: return null
        if (features.isEmpty()) return null
        return predictProbaUp(features.last())
    }

    fun predictProbaUp(last: FeatureRow): Double? {
        val rsi = last.rsi ?: 50.0
        val macdHist = last.macdHist ?: 0.0
        val stDir = last.stDir ?: 0.0
        val closeAboveSt = last.closeAboveSt ?: 0.0
        val volRatio = last.volRatio ?: 1.0
        val ret5 = last.ret5 ?: 0.0
        val volatility = last.volatility ?: 0.0
        val distSt = last.distToSt ?: 0.0
        val emaDist200 = last.emaDist200 ?: 0.0

        var score = 0.5
        score += 0.12 * stDir
        score += 0.08 * closeAboveSt
        score += 0.06 * clamp((rsi - 45) / 30, -1.0, 1.0)
        score += 0.10 * clamp(macdHist * 50, -1.0, 1.0)
        score += 0.05 * clamp(ret5 * 20, -1.0, 1.0)
        score += 0.04 * clamp(distSt * 0.15, -1.0, 1.0)
        score += 0.05 * clamp(emaDist200 * 10, -1.0, 1.0)
        score -= 0.06 * clamp((volRatio - 1.5) / 3, 0.0, 1.0)
        score -= 0.04 * clamp(volatility * 30, 0.0, 1.0)
        return clamp(score, 0.01, 0.99)
    }
}
