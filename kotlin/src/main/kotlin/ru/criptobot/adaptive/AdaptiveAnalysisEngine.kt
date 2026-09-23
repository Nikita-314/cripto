package ru.criptobot.adaptive

import kotlinx.serialization.json.*
import ru.criptobot.analytics.AnalyticsRepository
import ru.criptobot.analytics.SignalLogger
import ru.criptobot.config.Settings
import ru.criptobot.util.isoNow
import java.time.Instant
import java.time.temporal.ChronoUnit

class AdaptiveAnalysisEngine(
    private val settings: Settings,
    private val repo: AnalyticsRepository,
    private val logger: SignalLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: JsonObject = loadState()

    private fun loadState(): JsonObject {
        val f = settings.adaptiveStateFile.toFile()
        if (!f.exists()) return buildJsonObject {
            put("mode", settings.adaptiveMode)
            put("volume_ratio_modifiers", buildJsonObject {})
            put("last_report", buildJsonObject {})
        }
        return json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun saveState() {
        val out = buildJsonObject {
            put("updated_at", isoNow())
            put("mode", settings.adaptiveMode)
            put("volume_ratio_modifiers", state["volume_ratio_modifiers"] ?: buildJsonObject {})
            put("shadow_models", state["shadow_models"] ?: buildJsonObject {})
            put("last_report", state["last_report"] ?: buildJsonObject {})
        }
        settings.adaptiveStateFile.toFile().writeText(json.encodeToString(out))
        state = out
    }

    fun describePolicy(): String {
        val mods = state["volume_ratio_modifiers"]?.jsonObject ?: return "adaptive=${settings.adaptiveMode} | no modifiers"
        if (mods.isEmpty()) return "adaptive=${settings.adaptiveMode} | no active regime modifiers"
        val parts = mods.entries.mapNotNull { (k, v) ->
            val eff = v.jsonObject["effective_threshold"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            "$k->${"%.3f".format(eff)}"
        }
        return "adaptive=${settings.adaptiveMode} | volume_ratio modifiers: ${parts.joinToString(", ")}"
    }

    fun refresh(cycle: Int) {
        if (settings.adaptiveMode == "off") return
        if (cycle > 1 && cycle % settings.adaptiveRefreshCycles != 0) return
        val since = Instant.now().minus(settings.adaptiveLookbackDays.toLong(), ChronoUnit.DAYS).toString()
        val rows = repo.fetchAll(
            """SELECT d.signal_id, d.decision_ts, d.decision_type,
                      s.side, s.confidence_score, s.entry_price,
                      s.feature_snapshot_json, s.model_snapshot_json,
                      json_extract(d.details_json, '$.qty') AS qty,
                      json_extract(d.details_json, '$.net_pnl') AS net_pnl
               FROM decision_logs d JOIN signals s ON s.signal_id=d.signal_id
               WHERE d.decision_type IN ('TRADE_CLOSE','TRADE_PARTIAL_CLOSE') AND d.decision_ts>=?
                 AND s.side IN ('BUY','SHORT') AND s.entry_price>0
                 AND json_extract(d.details_json, '$.net_pnl') IS NOT NULL
               ORDER BY d.decision_ts""",
            listOf(since),
        )
        // A partially closed position is one learning outcome, not several independent trades.
        val realClosedTrades = rows.groupBy { it["signal_id"]?.toString().orEmpty() }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (_, events) ->
                val first = events.first()
                val entry = (first["entry_price"] as? Number)?.toDouble() ?: return@mapNotNull null
                val qty = events.sumOf { kotlin.math.abs((it["qty"] as? Number)?.toDouble() ?: 0.0) }
                val pnl = events.sumOf { (it["net_pnl"] as? Number)?.toDouble() ?: 0.0 }
                val confidence = (first["confidence_score"] as? Number)?.toDouble() ?: return@mapNotNull null
                if (entry <= 0 || qty <= 0) return@mapNotNull null
                val features = numericJson(first["feature_snapshot_json"]?.toString()) +
                    numericJson(first["model_snapshot_json"]?.toString()) +
                    mapOf("confidence" to confidence)
                val capital = entry * qty
                val entryPositionRub = features["effective_position_size_rub"]?.coerceAtLeast(1.0) ?: capital
                val entryRiskRub = features["entry_monetary_risk_rub"]?.coerceAtLeast(1.0) ?: capital * 0.03
                val allocatedRisk = entryRiskRub * (capital / entryPositionRub).coerceIn(0.0, 1.0)
                ShadowClosedTrade(
                    events.maxOf { it["decision_ts"].toString() }, first["side"].toString(), confidence,
                    pnl / capital * 100.0, features, pnl, capital, allocatedRisk.coerceAtLeast(1.0),
                )
            }
        val shadowPaperTrades = ShadowShortPaperSimulator.loadLearningTrades(
            settings.projectRoot.resolve("shadow_short_paper_state.json")
        ).filter { trade ->
            runCatching { Instant.parse(trade.timestamp) >= Instant.parse(since) }.getOrDefault(false)
        }
        val closedTrades = realClosedTrades + shadowPaperTrades
        val recommendations = ClosedTradeShadowLearner.train(closedTrades)
        val shadowModels = buildJsonObject {
            recommendations.forEach { rec -> put(rec.side, buildJsonObject {
                put("confidence_floor", rec.confidenceFloor); put("accepted_on_test", rec.accepted)
                put("train_size", rec.trainSize); put("validation_size", rec.validationSize); put("test_size", rec.testSize)
                put("train_mean_pct", rec.trainMeanPct); put("validation_mean_pct", rec.validationMeanPct)
                put("test_mean_pct", rec.testMeanPct); put("test_profit_factor", rec.testProfitFactor)
                put("baseline_test_mean_pct", rec.baselineTestMeanPct)
                put("health", rec.health)
                put("reason", rec.reason)
                put("split", "60/20/20")
                put("partial_closes_aggregated", true)
                put("capital_and_risk_weighted", true)
                put("real_closed_samples", realClosedTrades.count { it.side == rec.side })
                put("shadow_paper_samples", shadowPaperTrades.count { it.side == rec.side })
                put("shadow_paper_isolated", true)
                put("ensemble", buildJsonObject {
                    put("accepted_on_test", rec.ensembleAccepted)
                    put("test_size", rec.ensembleTestSize)
                    put("test_mean_pct", rec.ensembleTestMeanPct)
                    put("test_profit_factor", rec.ensembleTestProfitFactor)
                })
                put("interventions", buildJsonArray {
                    rec.interventions.forEach { intervention -> add(buildJsonObject {
                        put("feature", intervention.feature)
                        put("operator", intervention.operator)
                        put("threshold", intervention.threshold)
                        put("action", intervention.action)
                        put("accepted_on_test", intervention.accepted)
                        put("train_size", intervention.trainSize)
                        put("validation_size", intervention.validationSize)
                        put("test_size", intervention.testSize)
                        put("test_coverage", intervention.testCoverage)
                        put("train_mean_pct", intervention.trainMeanPct)
                        put("validation_mean_pct", intervention.validationMeanPct)
                        put("test_mean_pct", intervention.testMeanPct)
                        put("baseline_test_mean_pct", intervention.baselineTestMeanPct)
                        put("test_profit_factor", intervention.testProfitFactor)
                        put("baseline_test_profit_factor", intervention.baselineTestProfitFactor)
                        put("test_mean_r", intervention.testMeanR)
                        put("reason", intervention.reason)
                    }) }
                })
                put("updated_at", isoNow())
            }) }
        }
        AdaptiveStateStore(settings.adaptiveRuntimeStateFile).save(
            AdaptiveRuntimeState.defaultForBase(settings.mlProbThreshold)
        )
        val previousModels = state["shadow_models"]?.jsonObject
        recommendations.forEach { rec ->
            val previousFloor = previousModels?.get(rec.side)?.jsonObject?.get("confidence_floor")?.jsonPrimitive?.doubleOrNull
            val previousAccepted = previousModels?.get(rec.side)?.jsonObject?.get("accepted_on_test")?.jsonPrimitive?.booleanOrNull
            if (previousFloor != rec.confidenceFloor || previousAccepted != rec.accepted) {
                logger.logAdaptationAction("shadow", "closed_trade_model", rec.side, "confidence_floor",
                    previousFloor ?: settings.mlProbThreshold, rec.confidenceFloor, rec.testProfitFactor.coerceAtMost(0.99),
                    rec.trainSize + rec.validationSize + rec.testSize, "SHADOW_RECOMMENDATION", rec.reason,
                    mapOf("train_mean_pct" to rec.trainMeanPct, "validation_mean_pct" to rec.validationMeanPct,
                        "test_mean_pct" to rec.testMeanPct, "test_pf" to rec.testProfitFactor,
                        "accepted_on_test" to rec.accepted),
                    mapOf("since" to since, "split" to "60/20/20", "test_size" to rec.testSize))
            }
            val previousHealth = previousModels?.get(rec.side)?.jsonObject?.get("health")?.jsonPrimitive?.contentOrNull
            if (previousHealth != rec.health) {
                logger.logAdaptationAction(
                    "shadow", "closed_trade_diagnosis", rec.side, "strategy_health",
                    null, null, rec.testProfitFactor.coerceAtMost(0.99), rec.trainSize + rec.validationSize + rec.testSize,
                    "SHADOW_RECOMMENDATION", "Strategy health changed to ${rec.health}",
                    mapOf("health" to rec.health, "test_mean_pct" to rec.testMeanPct,
                        "test_pf" to rec.testProfitFactor),
                    mapOf("since" to since, "split" to "60/20/20"),
                )
            }
            val previousRules = previousModels?.get(rec.side)?.jsonObject?.get("interventions")?.toString()
            val currentRules = shadowModels[rec.side]?.jsonObject?.get("interventions")?.toString()
            if (previousRules != currentRules) {
                rec.interventions.filter { it.accepted }.forEach { rule ->
                    logger.logAdaptationAction(
                        "shadow", "closed_trade_intervention", rec.side, rule.feature,
                        null, rule.threshold, rule.testProfitFactor.coerceAtMost(0.99),
                        rule.trainSize + rule.validationSize + rule.testSize, "SHADOW_RECOMMENDATION",
                        "${rule.action}: require ${rule.feature} ${rule.operator} ${rule.threshold}. ${rule.reason}",
                        mapOf("operator" to rule.operator, "action" to rule.action,
                            "train_mean_pct" to rule.trainMeanPct,
                            "validation_mean_pct" to rule.validationMeanPct,
                            "test_mean_pct" to rule.testMeanPct,
                            "baseline_test_mean_pct" to rule.baselineTestMeanPct,
                            "test_pf" to rule.testProfitFactor,
                            "baseline_test_pf" to rule.baselineTestProfitFactor,
                            "test_mean_r" to rule.testMeanR,
                            "accepted_on_test" to true),
                        mapOf("since" to since, "split" to "60/20/20",
                            "test_size" to rule.testSize,
                            "test_coverage" to rule.testCoverage),
                    )
                }
            }
        }
        state = buildJsonObject {
            state.forEach { (k, v) -> put(k, v) }
            put("volume_ratio_modifiers", buildJsonObject {})
            put("shadow_models", shadowModels)
        }
        saveState()
    }

    private fun parseJson(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return json.parseToJsonElement(raw).jsonObject.entries.associate { it.key to jsonPrimitiveToAny(it.value) }
    }

    private fun numericJson(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
                value.jsonPrimitive.doubleOrNull?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun jsonPrimitiveToAny(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonObject -> el.entries.associate { it.key to jsonPrimitiveToAny(it.value) }
        else -> el.jsonPrimitive.contentOrNull ?: el.jsonPrimitive.doubleOrNull ?: el.jsonPrimitive.content
    }
}
