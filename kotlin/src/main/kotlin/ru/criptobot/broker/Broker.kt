package ru.criptobot.broker

interface Broker {
    fun getPosition(symbol: String): Pair<Double, Double>
    fun placeOrder(symbol: String, side: String, amountRub: Double, marketPrice: Double): OrderExecution?
    fun getBalanceSnapshot(latestPrices: Map<String, Double>): Map<String, Any>
    fun performance(latestPrices: Map<String, Double>): Map<String, Any>
    fun getOpenPositions(latestPrices: Map<String, Double>): List<Map<String, Any>>
    fun canOpenShort(symbol: String): Boolean = false
    fun hasTwoSidedLiquidity(symbol: String): Boolean = true
    fun isTradingOpen(symbol: String): Boolean = true
    fun resetBalance(initial: Double? = null): Map<String, Any>
    fun resetPerformanceBaseline(latestPrices: Map<String, Double>): Map<String, Any>
    fun close() {}
}
