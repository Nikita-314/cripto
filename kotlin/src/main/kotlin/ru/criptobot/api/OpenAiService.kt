package ru.criptobot.api

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import ru.criptobot.data.StockInstrument
import ru.criptobot.engine.StrategyConfig
import ru.criptobot.util.clamp
import ru.criptobot.util.extractJsonObject
import ru.criptobot.util.safeFloat

class OpenAiService(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(OpenAiService::class.java)
    private val client = HttpClient(CIO)
    private val enabled get() = settings.openaiApiKey.isNotBlank()

    suspend fun aiFilterInstruments(instruments: List<StockInstrument>): Pair<List<StockInstrument>, List<Map<String, String>>> {
        if (!settings.enableAiRiskFilter || !enabled || instruments.isEmpty()) return instruments to emptyList()
        val blocked = mutableListOf<Map<String, String>>()
        instruments.chunked(100).forEachIndexed { batchIndex, batch ->
            val candidates = batch.map { mapOf("symbol" to it.symbol, "name" to it.name, "status" to it.status) }
            val prompt = """
                You are a conservative risk filter for Binance spot crypto pairs.
                Return strict JSON with one key: block (array of {symbol, reason}).
                Block only assets with a clear reason not to trade: scam tokens, imminent delisting, halted trading,
                known fraud, or non-spot / leveraged products.
                Do not block a coin merely because it is small, volatile, unfamiliar, or lacks data.
                Instruments: $candidates
            """.trimIndent()
            try {
                val raw = chatJson(prompt) ?: return@forEachIndexed
                val parsed = Json.parseToJsonElement(extractJsonObject(raw)).jsonObject
                val batchSymbols = batch.map { it.symbol }.toSet()
                parsed["block"]?.jsonArray?.mapNotNullTo(blocked) { el ->
                    val o = el.jsonObject
                    val symbol = o["symbol"]?.jsonPrimitive?.content.orEmpty().uppercase()
                    if (symbol !in batchSymbols) return@mapNotNullTo null
                    mapOf(
                        "symbol" to symbol,
                        "reason" to o["reason"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
            } catch (e: Exception) {
                log.warn("AI risk filter batch {} failed: {}", batchIndex + 1, e.message)
            }
        }
        val blockedSymbols = blocked.mapNotNull { it["symbol"] }.toSet()
        return instruments.filterNot { it.symbol in blockedSymbols } to blocked.distinctBy { it["symbol"] }
    }

    suspend fun maybeUpdateStrategy(
        cycle: Int, strategy: StrategyConfig, performance: Map<String, Any>, marketSample: List<Map<String, Any>>,
        lastRebalanceTradeCount: Int,
    ): Pair<StrategyConfig, String?> {
        if (!enabled || settings.openaiRebalanceCycles <= 0) return strategy to null
        if (cycle % settings.openaiRebalanceCycles != 0) return strategy to null
        val tradeCount = safeFloat(performance["trade_count"]).toInt()
        if (tradeCount <= 0 || tradeCount <= lastRebalanceTradeCount) return strategy to null
        val prompt = """You are a quant assistant. Return strict JSON.
Include only parameters that you actually recommend changing. Omit every unchanged parameter.
Allowed parameters: buy_threshold, sell_threshold, ema_weight, macd_weight, rsi_weight, adx_weight,
supertrend_weight, rsi_overbought, rsi_oversold, stop_loss_pct, take_profit_pct.
Always include reason. Never assume that a proposal will be applied automatically: it requires explicit operator approval.
current_strategy=${Json.encodeToString(strategy.toJson())}
performance=$performance
market_sample=$marketSample"""
        return try {
            val raw = chatJson(prompt) ?: return strategy to null
            val p = Json.parseToJsonElement(extractJsonObject(raw)).jsonObject
            val proposed = p["changes"]?.let { it as? JsonObject } ?: p
            val updated = strategy.copy(
                buyThreshold = clamp(proposed.number("buy_threshold") ?: strategy.buyThreshold, 0.3, 0.9),
                sellThreshold = clamp(proposed.number("sell_threshold") ?: strategy.sellThreshold, -0.9, -0.3),
                emaWeight = clamp(proposed.number("ema_weight") ?: strategy.emaWeight, 0.0, 2.0),
                macdWeight = clamp(proposed.number("macd_weight") ?: strategy.macdWeight, 0.0, 2.0),
                rsiWeight = clamp(proposed.number("rsi_weight") ?: strategy.rsiWeight, 0.0, 2.0),
                adxWeight = clamp(proposed.number("adx_weight") ?: strategy.adxWeight, 0.0, 2.0),
                supertrendWeight = clamp(proposed.number("supertrend_weight") ?: strategy.supertrendWeight, 0.0, 2.0),
                rsiOverbought = clamp(proposed.number("rsi_overbought") ?: strategy.rsiOverbought, 55.0, 90.0),
                rsiOversold = clamp(proposed.number("rsi_oversold") ?: strategy.rsiOversold, 10.0, 45.0),
                stopLossPct = clamp(proposed.number("stop_loss_pct") ?: strategy.stopLossPct, 0.005, 0.20),
                takeProfitPct = clamp(proposed.number("take_profit_pct") ?: strategy.takeProfitPct, 0.01, 0.50),
            )
            val reason = p["reason"].displayText()
            val changes = buildList {
                addChange("Порог покупки", strategy.buyThreshold, updated.buyThreshold)
                addChange("Порог продажи", strategy.sellThreshold, updated.sellThreshold)
                addChange("Вес EMA", strategy.emaWeight, updated.emaWeight)
                addChange("Вес MACD", strategy.macdWeight, updated.macdWeight)
                addChange("Вес RSI", strategy.rsiWeight, updated.rsiWeight)
                addChange("Вес ADX", strategy.adxWeight, updated.adxWeight)
                addChange("Вес Supertrend", strategy.supertrendWeight, updated.supertrendWeight)
                addChange("RSI перекупленности", strategy.rsiOverbought, updated.rsiOverbought)
                addChange("RSI перепроданности", strategy.rsiOversold, updated.rsiOversold)
                addChange("Стоп-лосс", strategy.stopLossPct, updated.stopLossPct)
                addChange("Тейк-профит", strategy.takeProfitPct, updated.takeProfitPct)
            }
            if (changes.isEmpty()) return strategy to null
            updated to buildString {
                appendLine("🤖 ИИ ПРЕДЛОЖИЛ ИЗМЕНЕНИЕ СТРАТЕГИИ")
                appendLine("━━━━━━━━━━━━━━━━━━")
                appendLine("⚠️ Пока ничего не изменено.")
                appendLine()
                changes.forEach { appendLine(it) }
                if (reason.isNotBlank()) appendLine("\n📝 $reason")
                append("\nИзменения вступят в силу только после нажатия «✅ Принять».")
            }
        } catch (e: Exception) {
            log.warn("OpenAI strategy update failed: {}", e.message)
            strategy to null
        }
    }

    private suspend fun chatJson(prompt: String): String? {
        val resp = client.post("https://api.openai.com/v1/chat/completions") {
            header("Authorization", "Bearer ${settings.openaiApiKey}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", settings.openaiModel)
                put("temperature", 0.1)
                putJsonObject("response_format") { put("type", "json_object") }
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", "Return only valid JSON.") }
                    addJsonObject { put("role", "user"); put("content", prompt) }
                }
            }.toString())
        }.bodyAsText()
        val content = Json.parseToJsonElement(resp).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonObject, is JsonArray -> content.toString()
            else -> null
        }
    }

    fun close() = client.close()
}

private fun JsonObject.number(key: String): Double? {
    val value = this[key] ?: return null
    if (value is JsonPrimitive) return value.doubleOrNull
    if (value is JsonObject) {
        return sequenceOf("new_value", "value", "proposed_value")
            .mapNotNull { field -> (value[field] as? JsonPrimitive)?.doubleOrNull }
            .firstOrNull()
    }
    return null
}

private fun JsonElement?.displayText(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("; ") { it.displayText() }.trim()
    is JsonObject -> entries.joinToString("; ") { (key, value) -> "$key: ${value.displayText()}" }.trim()
}

private fun MutableList<String>.addChange(label: String, old: Double, new: Double) {
    if (kotlin.math.abs(old - new) > 1e-9) add("$label: $old → $new")
}

private fun StrategyConfig.toJson() = buildJsonObject {
    put("buy_threshold", buyThreshold); put("sell_threshold", sellThreshold)
    put("ema_weight", emaWeight); put("macd_weight", macdWeight); put("rsi_weight", rsiWeight)
    put("adx_weight", adxWeight); put("supertrend_weight", supertrendWeight)
    put("rsi_overbought", rsiOverbought); put("rsi_oversold", rsiOversold)
    put("stop_loss_pct", stopLossPct); put("take_profit_pct", takeProfitPct)
}
