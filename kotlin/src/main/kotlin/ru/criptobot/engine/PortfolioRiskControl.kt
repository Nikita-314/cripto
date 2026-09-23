package ru.criptobot.engine

data class PortfolioRiskDecision(
    val drawdownPct: Double,
    val positionMultiplier: Double,
    val requireBarConfirmation: Boolean,
)

object PortfolioRiskControl {
    fun evaluate(initialEquity: Double, currentEquity: Double): PortfolioRiskDecision {
        if (initialEquity <= 0.0 || currentEquity <= 0.0) {
            return PortfolioRiskDecision(0.0, 1.0, false)
        }
        val drawdownPct = (currentEquity / initialEquity - 1.0) * 100.0
        val multiplier = when {
            drawdownPct <= -10.0 -> 0.25
            drawdownPct <= -5.0 -> 0.50
            else -> 1.0
        }
        return PortfolioRiskDecision(
            drawdownPct = drawdownPct,
            positionMultiplier = multiplier,
            requireBarConfirmation = drawdownPct <= -5.0,
        )
    }
}
