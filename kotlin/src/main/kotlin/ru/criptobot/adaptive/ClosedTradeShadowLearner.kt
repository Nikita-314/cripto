package ru.criptobot.adaptive

import kotlin.math.ln

data class ShadowClosedTrade(
    val timestamp: String,
    val side: String,
    val confidence: Double,
    val netReturnPct: Double,
    val features: Map<String, Double> = emptyMap(),
    val netPnlRub: Double = netReturnPct,
    val capitalRub: Double = 100.0,
    val riskRub: Double = 3.0,
)

data class ShadowIntervention(
    val feature: String,
    val operator: String,
    val threshold: Double,
    val action: String,
    val accepted: Boolean,
    val trainSize: Int,
    val validationSize: Int,
    val testSize: Int,
    val testCoverage: Double,
    val trainMeanPct: Double,
    val validationMeanPct: Double,
    val testMeanPct: Double,
    val baselineTestMeanPct: Double,
    val testProfitFactor: Double,
    val baselineTestProfitFactor: Double,
    val testMeanR: Double,
    val reason: String,
)

data class ShadowModelRecommendation(
    val side: String,
    val confidenceFloor: Double,
    val accepted: Boolean,
    val trainSize: Int,
    val validationSize: Int,
    val testSize: Int,
    val trainMeanPct: Double,
    val validationMeanPct: Double,
    val testMeanPct: Double,
    val testProfitFactor: Double,
    val baselineTestMeanPct: Double,
    val reason: String,
    val health: String,
    val interventions: List<ShadowIntervention>,
    val ensembleAccepted: Boolean,
    val ensembleTestSize: Int,
    val ensembleTestMeanPct: Double,
    val ensembleTestProfitFactor: Double,
)

/** Shadow-only walk-forward learner. Final test data is never used to select a rule. */
object ClosedTradeShadowLearner {
    private val floors = listOf(0.55, 0.60, 0.65, 0.70, 0.75, 0.80)
    private val featureNames = listOf(
        "rsi", "macd_histogram", "atr_pct", "volume_ratio", "distance_to_supertrend_atr",
        "ema50_distance", "ema200_distance", "return_1", "return_3", "return_5", "return_10",
        "volatility_20", "price_vs_ema200_pct", "market_breadth_falling_share", "hour_utc",
    )

    fun train(trades: List<ShadowClosedTrade>): List<ShadowModelRecommendation> =
        listOf("BUY", "SHORT").mapNotNull { side -> trainSide(side, trades.filter { it.side == side }) }

    private fun trainSide(side: String, source: List<ShadowClosedTrade>): ShadowModelRecommendation? {
        val ordered = source.sortedBy { it.timestamp }
        if (ordered.size < 30) return null
        val trainEnd = (ordered.size * 0.60).toInt().coerceIn(18, ordered.size - 12)
        val validationEnd = (ordered.size * 0.80).toInt().coerceIn(trainEnd + 6, ordered.size - 6)
        val train = ordered.take(trainEnd)
        val validation = ordered.subList(trainEnd, validationEnd)
        val test = ordered.drop(validationEnd)
        val baselineTestMean = weightedMeanPct(test)
        val baselineTestPf = profitFactorRub(test)
        val health = when {
            baselineTestMean > 0.0 && baselineTestPf >= 1.10 -> "HEALTHY_STRENGTHEN"
            baselineTestMean < 0.0 || baselineTestPf < 0.90 -> "DEGRADING_CORRECT"
            else -> "MIXED_OBSERVE"
        }

        // Floor is selected on train, screened on validation, and judged once on untouched test.
        val selectedFloor = floors.mapNotNull { floor ->
            val sample = train.filter { it.confidence >= floor }
            if (sample.size < 15) null else floor to sample
        }.maxByOrNull { (_, sample) -> meanR(sample) * ln(sample.size + 1.0) }
        val floor = selectedFloor?.first ?: floors.first()
        val trainFloor = train.filter { it.confidence >= floor }
        val validationFloor = validation.filter { it.confidence >= floor }
        val testFloor = test.filter { it.confidence >= floor }
        val validationImproves = validationFloor.size >= 6 &&
            weightedMeanPct(validationFloor) >= weightedMeanPct(validation) + 0.02 &&
            profitFactorRub(validationFloor) >= profitFactorRub(validation)
        val testImproves = testFloor.size >= 6 && testFloor.size < test.size * 0.95 &&
            weightedMeanPct(testFloor) >= baselineTestMean + 0.02 &&
            profitFactorRub(testFloor) >= maxOf(1.10, baselineTestPf)
        val floorAccepted = validationImproves && testImproves
        val floorReason = if (floorAccepted) {
            "Improvement survived validation and untouched chronological test"
        } else {
            "Rejected: no material out-of-sample improvement over the unfiltered baseline"
        }

        val interventions = discoverInterventions(train, validation, test)
        val acceptedRules = interventions.filter { it.accepted }
        val ensembleTest = if (acceptedRules.isEmpty()) emptyList() else test.filter { row ->
            acceptedRules.all { rule -> matches(row, Candidate(rule.feature, rule.operator, rule.threshold)) }
        }
        val ensembleAccepted = ensembleTest.size >= 6 &&
            weightedMeanPct(ensembleTest) >= baselineTestMean + 0.02 &&
            profitFactorRub(ensembleTest) >= maxOf(1.10, baselineTestPf)

        return ShadowModelRecommendation(
            side, floor, floorAccepted, trainFloor.size, validationFloor.size, testFloor.size,
            weightedMeanPct(trainFloor), weightedMeanPct(validationFloor), weightedMeanPct(testFloor),
            profitFactorRub(testFloor), baselineTestMean, floorReason, health, interventions,
            ensembleAccepted, ensembleTest.size, weightedMeanPct(ensembleTest), profitFactorRub(ensembleTest),
        )
    }

    private data class Candidate(val feature: String, val operator: String, val threshold: Double)

    private fun discoverInterventions(
        train: List<ShadowClosedTrade>,
        validation: List<ShadowClosedTrade>,
        test: List<ShadowClosedTrade>,
    ): List<ShadowIntervention> {
        val trainBaselineMean = weightedMeanPct(train)
        val validationBaselineMean = weightedMeanPct(validation)
        val validationBaselinePf = profitFactorRub(validation)
        val testBaselineMean = weightedMeanPct(test)
        val testBaselinePf = profitFactorRub(test)
        val candidates = featureNames.flatMap { feature ->
            val values = train.mapNotNull { it.features[feature] }.sorted()
            if (values.size < 30) emptyList() else listOf(0.20, 0.35, 0.50, 0.65, 0.80).flatMap { q ->
                val threshold = values[((values.size - 1) * q).toInt()]
                listOf(Candidate(feature, ">=", threshold), Candidate(feature, "<=", threshold))
            }
        }
        // Selection is train-only. One candidate per feature proceeds to validation.
        val selected = candidates.groupBy { it.feature }.mapNotNull { (_, rows) ->
            rows.mapNotNull { candidate ->
                val kept = train.filter { matches(it, candidate) }
                if (kept.size < 15 || kept.size > train.size * 0.90) null
                else candidate to (meanR(kept) - meanR(train)) * ln(kept.size + 1.0)
            }.maxByOrNull { it.second }?.first
        }
        // Validation selects the strongest features; final acceptance uses untouched test only.
        val validated = selected.mapNotNull { candidate ->
            val kept = validation.filter { matches(it, candidate) }
            if (kept.size < 6) null else Triple(candidate, kept,
                (weightedMeanPct(kept) - validationBaselineMean) * ln(kept.size + 1.0))
        }.filter { (_, kept, _) ->
            weightedMeanPct(kept) >= validationBaselineMean + 0.02 &&
                profitFactorRub(kept) >= maxOf(1.10, validationBaselinePf)
        }.sortedByDescending { it.third }.take(5)

        return validated.map { (candidate, validationKept, _) ->
            val trainKept = train.filter { matches(it, candidate) }
            val testKnown = test.filter { it.features.containsKey(candidate.feature) }
            val testKept = testKnown.filter { matches(it, candidate) }
            val testMean = weightedMeanPct(testKept)
            val testPf = profitFactorRub(testKept)
            val accepted = testKept.size >= 6 && testMean >= testBaselineMean + 0.02 &&
                testPf >= maxOf(1.10, testBaselinePf)
            ShadowIntervention(
                candidate.feature, candidate.operator, candidate.threshold,
                if (testKept.size.toDouble() / testKnown.size.coerceAtLeast(1) <= 0.55)
                    "FAVOR_STRONG_SETUP" else "FILTER_BAD_SETUP",
                accepted, trainKept.size, validationKept.size, testKept.size,
                testKept.size.toDouble() / testKnown.size.coerceAtLeast(1),
                weightedMeanPct(trainKept), weightedMeanPct(validationKept), testMean,
                testBaselineMean, testPf, testBaselinePf, meanR(testKept),
                if (accepted) "Confirmed on untouched chronological test"
                else "Rejected on untouched chronological test",
            )
        }.sortedByDescending { if (it.accepted) 1_000.0 + it.testMeanR else it.testMeanR }
    }

    private fun matches(row: ShadowClosedTrade, candidate: Candidate): Boolean {
        val value = row.features[candidate.feature] ?: return false
        return if (candidate.operator == ">=") value >= candidate.threshold else value <= candidate.threshold
    }

    private fun weightedMeanPct(rows: List<ShadowClosedTrade>): Double {
        val capital = rows.sumOf { it.capitalRub.coerceAtLeast(0.0) }
        return if (capital <= 1e-9) 0.0 else rows.sumOf { it.netPnlRub } / capital * 100.0
    }

    private fun meanR(rows: List<ShadowClosedTrade>): Double =
        if (rows.isEmpty()) 0.0 else rows.map { it.netPnlRub / it.riskRub.coerceAtLeast(1.0) }.average()

    private fun profitFactorRub(rows: List<ShadowClosedTrade>): Double {
        val gains = rows.sumOf { it.netPnlRub.coerceAtLeast(0.0) }
        val losses = -rows.sumOf { it.netPnlRub.coerceAtMost(0.0) }
        return if (losses <= 1e-12) if (gains > 0) 99.0 else 0.0 else gains / losses
    }
}
