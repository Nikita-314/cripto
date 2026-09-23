package ru.criptobot.engine

import ru.criptobot.features.FeatureRow

data class MultiTimeframeDecision(val allowed: Boolean, val reasonCode: String)

/**
 * Live longs need 4h Supertrend up. Missing 4h data is allowed here because
 * [RegimeAdaptiveStrategy] already hard-requires higherTfDir on the 1h flip.
 */
object MultiTimeframeEntryFilter {
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        side: String,
        confidence: Double,
        fourHour: FeatureRow?,
        tenMinute: FeatureRow?,
        fourHourUpShare: Double,
        bullishRegimeConfirmed: Boolean = true,
    ): MultiTimeframeDecision {
        val normalized = side.uppercase()
        if (fourHour == null) {
            return MultiTimeframeDecision(true, "MTF_4H_UNAVAILABLE_ALLOW")
        }
        return if (normalized == "BUY") {
            if (fourHour.stDir != 1.0) MultiTimeframeDecision(false, "MTF_4H_AGAINST_LONG")
            else MultiTimeframeDecision(true, "MTF_LONG_ALLOWED")
        } else {
            val structureDown = fourHour.stDir == -1.0 &&
                (fourHour.close ?: Double.MAX_VALUE) < (fourHour.ema200 ?: 0.0)
            if (!structureDown) MultiTimeframeDecision(false, "MTF_4H_AGAINST_SHORT")
            else MultiTimeframeDecision(true, "MTF_SHORT_ALLOWED")
        }
    }
}
