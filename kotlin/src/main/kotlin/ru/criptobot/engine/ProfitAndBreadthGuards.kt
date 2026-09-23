package ru.criptobot.engine

import ru.criptobot.util.safeFloat

private const val LIVE_ENTRY_RISK_FRACTION = 0.003

data class DailyProfitGuardDecision(
    val active: Boolean,
    val currentRealizedRub: Double,
    val peakRealizedRub: Double,
    val activationRub: Double,
    val protectedFloorRub: Double,
)

object DailyProfitGuard {
    fun evaluate(
        equityRub: Double,
        currentRealizedRub: Double,
        peakRealizedRub: Double,
        activationPct: Double = 0.0075,
        allowedGivebackFraction: Double = 0.35,
    ): DailyProfitGuardDecision {
        val activationRub = (equityRub.coerceAtLeast(0.0) * activationPct)
            .coerceAtLeast(minOf(500.0, equityRub.coerceAtLeast(0.0) * 0.05))
        val peak = peakRealizedRub.coerceAtLeast(currentRealizedRub).coerceAtLeast(0.0)
        val floor = peak * (1.0 - allowedGivebackFraction.coerceIn(0.0, 0.95))
        return DailyProfitGuardDecision(
            active = peak >= activationRub && currentRealizedRub <= floor,
            currentRealizedRub = currentRealizedRub,
            peakRealizedRub = peak,
            activationRub = activationRub,
            protectedFloorRub = floor,
        )
    }
}

data class MarketBreadthDecision(
    val bearish: Boolean,
    val sampleSize: Int,
    val fallingShare: Double,
)

object MarketBreadthGuard {
    fun evaluate(sample: List<Map<String, Any>>, minimumSample: Int = 30): MarketBreadthDecision {
        val directions = sample.mapNotNull {
            safeFloat(it["supertrend_direction"], Double.NaN).takeUnless(Double::isNaN)
        }
        val falling = directions.count { it < 0.0 }
        val share = if (directions.isEmpty()) 0.5 else falling.toDouble() / directions.size
        return MarketBreadthDecision(
            bearish = directions.size >= minimumSample && share >= 0.60,
            sampleSize = directions.size,
            fallingShare = share,
        )
    }
}

data class ExpensiveEntryDecision(
    val allowed: Boolean,
    val amountMultiple: Double,
    val requiredConfidence: Double,
)

object ExpensiveEntryConfidence {
    fun evaluate(
        confidence: Double,
        minimumOrderRub: Double,
        standardOrderRub: Double,
        baseThreshold: Double,
    ): ExpensiveEntryDecision {
        val multiple = if (standardOrderRub > 0.0) {
            minimumOrderRub.coerceAtLeast(0.0) / standardOrderRub
        } else {
            1.0
        }
        val required = when {
            multiple <= 1.5 -> baseThreshold
            multiple <= 2.0 -> maxOf(baseThreshold, 0.35)
            multiple <= 4.0 -> maxOf(baseThreshold, 0.50)
            multiple <= 8.0 -> maxOf(baseThreshold, 0.65)
            else -> maxOf(baseThreshold, 0.80)
        }
        return ExpensiveEntryDecision(
            allowed = confidence >= required,
            amountMultiple = multiple,
            requiredConfidence = required,
        )
    }
}

data class ProfitabilityEntryDecision(
    val allowed: Boolean,
    val reasonCode: String? = null,
    val monetaryRiskRub: Double = 0.0,
    val monetaryRiskLimitRub: Double = 0.0,
)

enum class EntryFilterSet {
    WALK_FORWARD_V3,
    REGIME_ADAPTIVE_V4,
}

object ProfitabilityEntryGuard {
    fun evaluate(
        side: String,
        rsi: Double?,
        return5: Double?,
        distanceToSupertrendAtr: Double?,
        minimumOrderRub: Double,
        stopLossPct: Double,
        equityRub: Double,
        bearishMarket: Boolean,
        confidence: Double? = null,
        allowUnavoidableSingleLotRisk: Boolean = false,
        filterSet: EntryFilterSet = EntryFilterSet.REGIME_ADAPTIVE_V4,
    ): ProfitabilityEntryDecision {
        val normalizedSide = side.uppercase()
        val sideAdjustedReturn5 = (return5 ?: 0.0) * if (normalizedSide == "SHORT") -1.0 else 1.0
        val sideAdjustedRsi = if (normalizedSide == "SHORT") 100.0 - (rsi ?: 50.0) else (rsi ?: 50.0)
        val distance = kotlin.math.abs(distanceToSupertrendAtr ?: 0.0)
        val monetaryRisk = minimumOrderRub.coerceAtLeast(0.0) * stopLossPct.coerceAtLeast(0.0)
        val monetaryRiskLimit = maxOf(100.0, equityRub.coerceAtLeast(0.0) * LIVE_ENTRY_RISK_FRACTION)
        if (monetaryRisk > monetaryRiskLimit && !allowUnavoidableSingleLotRisk) {
            return ProfitabilityEntryDecision(false, "MINIMUM_LOT_RISK_TOO_HIGH", monetaryRisk, monetaryRiskLimit)
        }
        if (filterSet == EntryFilterSet.WALK_FORWARD_V3) {
            return when {
                normalizedSide == "BUY" && (confidence == null || confidence < 0.65) ->
                    ProfitabilityEntryDecision(false, "LONG_CONFIDENCE_BELOW_WALK_FORWARD_FLOOR", monetaryRisk, monetaryRiskLimit)
                normalizedSide == "BUY" && (rsi == null || rsi !in 45.0..67.0) ->
                    ProfitabilityEntryDecision(false, "LONG_RSI_OUTSIDE_WALK_FORWARD_RANGE", monetaryRisk, monetaryRiskLimit)
                normalizedSide == "BUY" && (return5 == null || return5 < -0.008) ->
                    ProfitabilityEntryDecision(false, "LONG_MOMENTUM_DAMAGED", monetaryRisk, monetaryRiskLimit)
                normalizedSide == "BUY" && (distanceToSupertrendAtr == null || distance !in 0.2..2.5) ->
                    ProfitabilityEntryDecision(false, "LONG_SUPERTREND_DISTANCE_OUTSIDE_RANGE", monetaryRisk, monetaryRiskLimit)
                distance >= 4.0 -> ProfitabilityEntryDecision(false, "ENTRY_TOO_FAR_FROM_SUPERTREND", monetaryRisk, monetaryRiskLimit)
                sideAdjustedReturn5 >= 0.04 -> ProfitabilityEntryDecision(false, "ENTRY_CHASING_EXTENDED_MOVE", monetaryRisk, monetaryRiskLimit)
                sideAdjustedRsi >= 75.0 -> ProfitabilityEntryDecision(false, "ENTRY_OVERBOUGHT_OR_OVERSOLD", monetaryRisk, monetaryRiskLimit)
                normalizedSide == "BUY" && bearishMarket && (
                    sideAdjustedRsi !in 40.0..60.0 || sideAdjustedReturn5 > 0.02 || distance >= 3.0
                ) -> ProfitabilityEntryDecision(false, "BEARISH_REGIME_LONG_NOT_SELECTIVE", monetaryRisk, monetaryRiskLimit)
                else -> ProfitabilityEntryDecision(true, monetaryRiskRub = monetaryRisk, monetaryRiskLimitRub = monetaryRiskLimit)
            }
        }
        return when {
            normalizedSide == "SHORT" && rsi != null && rsi < 35.0 ->
                ProfitabilityEntryDecision(false, "SHORT_OVERSOLD_BOUNCE_RISK", monetaryRisk, monetaryRiskLimit)
            distance >= 4.0 -> ProfitabilityEntryDecision(false, "ENTRY_TOO_FAR_FROM_SUPERTREND", monetaryRisk, monetaryRiskLimit)
            sideAdjustedReturn5 >= 0.04 -> ProfitabilityEntryDecision(false, "ENTRY_CHASING_EXTENDED_MOVE", monetaryRisk, monetaryRiskLimit)
            sideAdjustedRsi >= 75.0 -> ProfitabilityEntryDecision(false, "ENTRY_OVERBOUGHT_OR_OVERSOLD", monetaryRisk, monetaryRiskLimit)
            normalizedSide == "BUY" && bearishMarket && (sideAdjustedReturn5 > 0.02 || distance >= 3.0) ->
                ProfitabilityEntryDecision(false, "BEARISH_REGIME_LONG_NOT_SELECTIVE", monetaryRisk, monetaryRiskLimit)
            else -> ProfitabilityEntryDecision(true, monetaryRiskRub = monetaryRisk, monetaryRiskLimitRub = monetaryRiskLimit)
        }
    }
}

data class LivePositionSize(
    val quantity: Int,
    val lots: Int,
    val notionalRub: Double,
    val monetaryRiskRub: Double,
    val monetaryRiskLimitRub: Double,
)

data class CapitalAwareEntrySize(
    val configuredTargetRub: Double,
    val freeCapitalFraction: Double,
    val boosted: Boolean,
)

object CapitalAwarePositionSizer {
    fun chooseTarget(
        equityRub: Double,
        cashRub: Double,
        normalPositionRub: Double,
        boostedPositionRub: Double,
        boostWhileFreeFractionAtLeast: Double = 0.50,
    ): CapitalAwareEntrySize {
        val equity = equityRub.coerceAtLeast(0.0)
        val freeFraction = if (equity > 0.0) (cashRub / equity).coerceIn(0.0, 1.0) else 0.0
        val boosted = freeFraction >= boostWhileFreeFractionAtLeast.coerceIn(0.0, 1.0)
        return CapitalAwareEntrySize(
            configuredTargetRub = if (boosted) {
                boostedPositionRub.coerceAtLeast(normalPositionRub)
            } else {
                normalPositionRub.coerceAtLeast(0.0)
            },
            freeCapitalFraction = freeFraction,
            boosted = boosted,
        )
    }
}

object LivePositionSizer {
    fun calculate(
        price: Double,
        lotSize: Int,
        minimumOrderRub: Double,
        configuredPositionRub: Double,
        equityRub: Double,
        stopLossPct: Double,
    ): LivePositionSize {
        val safeLotSize = lotSize.coerceAtLeast(1)
        val lotNotional = price.coerceAtLeast(0.0) * safeLotSize
        val minimumLots = if (lotNotional > 0.0) {
            kotlin.math.ceil(minimumOrderRub.coerceAtLeast(0.0) / lotNotional).toInt().coerceAtLeast(1)
        } else 1
        val riskLimit = maxOf(100.0, equityRub.coerceAtLeast(0.0) * LIVE_ENTRY_RISK_FRACTION)
        val riskSizedNotional = if (stopLossPct > 0.0) riskLimit / stopLossPct else configuredPositionRub
        val targetNotional = minOf(configuredPositionRub.coerceAtLeast(minimumOrderRub), riskSizedNotional)
        val targetLots = if (lotNotional > 0.0) {
            kotlin.math.floor(targetNotional / lotNotional).toInt().coerceAtLeast(minimumLots)
        } else minimumLots
        val quantity = safeLotSize * targetLots
        val notional = price.coerceAtLeast(0.0) * quantity
        return LivePositionSize(
            quantity = quantity,
            lots = targetLots,
            notionalRub = notional,
            monetaryRiskRub = notional * stopLossPct.coerceAtLeast(0.0),
            monetaryRiskLimitRub = riskLimit,
        )
    }
}
