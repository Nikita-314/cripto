package ru.criptobot.engine

import kotlinx.serialization.json.*
import ru.criptobot.config.Settings
import java.nio.file.Path

data class StrategyConfig(
    val buyThreshold: Double,
    val sellThreshold: Double,
    val emaWeight: Double = 0.30,
    val macdWeight: Double = 0.25,
    val rsiWeight: Double = 0.15,
    val adxWeight: Double = 0.20,
    val supertrendWeight: Double = 0.15,
    val rsiOverbought: Double = 70.0,
    val rsiOversold: Double = 30.0,
    val stopLossPct: Double,
    val takeProfitPct: Double,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaults(settings: Settings) = StrategyConfig(
            buyThreshold = settings.buyThreshold,
            sellThreshold = settings.sellThreshold,
            stopLossPct = settings.stopLossPct,
            takeProfitPct = settings.takeProfitPct,
        )

        fun load(path: Path): StrategyConfig? {
            if (!path.toFile().exists()) return null
            return try {
                val p = json.parseToJsonElement(path.toFile().readText()).jsonObject
                StrategyConfig(
                    buyThreshold = p["buy_threshold"]?.jsonPrimitive?.double ?: 0.55,
                    sellThreshold = p["sell_threshold"]?.jsonPrimitive?.double ?: -0.55,
                    emaWeight = p["ema_weight"]?.jsonPrimitive?.double ?: 0.30,
                    macdWeight = p["macd_weight"]?.jsonPrimitive?.double ?: 0.25,
                    rsiWeight = p["rsi_weight"]?.jsonPrimitive?.double ?: 0.15,
                    adxWeight = p["adx_weight"]?.jsonPrimitive?.double ?: 0.20,
                    supertrendWeight = p["supertrend_weight"]?.jsonPrimitive?.double ?: 0.15,
                    rsiOverbought = p["rsi_overbought"]?.jsonPrimitive?.double ?: 70.0,
                    rsiOversold = p["rsi_oversold"]?.jsonPrimitive?.double ?: 30.0,
                    stopLossPct = p["stop_loss_pct"]?.jsonPrimitive?.double ?: 0.03,
                    takeProfitPct = p["take_profit_pct"]?.jsonPrimitive?.double ?: 0.06,
                )
            } catch (_: Exception) { null }
        }
    }

    fun save(path: Path, lastRebalanceTradeCount: Int = 0) {
        val obj = buildJsonObject {
            put("buy_threshold", buyThreshold); put("sell_threshold", sellThreshold)
            put("ema_weight", emaWeight); put("macd_weight", macdWeight); put("rsi_weight", rsiWeight)
            put("adx_weight", adxWeight); put("supertrend_weight", supertrendWeight)
            put("rsi_overbought", rsiOverbought); put("rsi_oversold", rsiOversold)
            put("stop_loss_pct", stopLossPct); put("take_profit_pct", takeProfitPct)
            put("last_rebalance_trade_count", lastRebalanceTradeCount)
        }
        path.toFile().writeText(json.encodeToString(obj))
    }
}
