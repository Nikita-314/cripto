package ru.criptobot.engine

object TrendGate {
    data class Eval(
        val effectiveMode: String,
        val trendOk: Boolean,
        val trendSupertrendOk: Boolean,
        val trendCloseAboveEma200: Boolean,
        val blockDetail: String?,
    )

    fun effectiveMode(configuredMode: String, tradingMode: String): String {
        if (tradingMode != "paper") return "strict"
        return if (configuredMode == "supertrend") "supertrend" else "strict"
    }

    fun evaluate(stDir: Double, close: Double, ema200: Double, configuredMode: String, tradingMode: String): Eval {
        val mode = effectiveMode(configuredMode, tradingMode)
        val supertrendOk = stDir >= 1
        val closeAboveEma200 = close > ema200
        val trendOk = when (mode) {
            "supertrend" -> supertrendOk
            else -> supertrendOk && closeAboveEma200
        }
        val blockDetail = if (trendOk) {
            null
        } else {
            when {
                !supertrendOk && !closeAboveEma200 -> "both_failed"
                !supertrendOk -> "supertrend_failed"
                else -> "ema200_failed"
            }
        }
        return Eval(mode, trendOk, supertrendOk, closeAboveEma200, blockDetail)
    }
}
