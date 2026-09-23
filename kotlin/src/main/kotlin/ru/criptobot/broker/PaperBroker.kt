package ru.criptobot.broker

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import ru.criptobot.util.isoNow
import ru.criptobot.util.safeFloat
import java.nio.file.Path
import kotlin.math.max

data class OrderExecution(
    val side: String,
    val qty: Double,
    val filledNotionalRub: Double,
    val commissionRub: Double,
    val grossPnl: Double,
    val grossPnlPct: Double,
    val netPnl: Double,
    val netPnlPct: Double,
    val entryCommissionAllocatedRub: Double = 0.0,
    val exitCommissionRub: Double = 0.0,
    val notionalRub: Double = 0.0,
    val averageFillPrice: Double = 0.0,
)

class PaperBroker(private val settings: Settings) : Broker {
    private val log = LoggerFactory.getLogger(PaperBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    var cashRub = settings.paperInitialBalanceRub
    var initialBalanceRub = settings.paperInitialBalanceRub
    var lastResetAt = isoNow()
    val positions = mutableMapOf<String, Double>()
    val avgPrice = mutableMapOf<String, Double>()
    private val lastKnownPrices = mutableMapOf<String, Double>()
    private val openEntryCommission = mutableMapOf<String, Double>()
    var realizedGrossPnlRub = 0.0
    var realizedCommissionRub = 0.0
    var tradeCount = 0

    init { load() }

    val realizedNetPnlRub get() = realizedGrossPnlRub - realizedCommissionRub

    private fun load() {
        val statePath = settings.paperStateFile
        if (statePath.toFile().exists()) {
            val root = json.parseToJsonElement(statePath.toFile().readText()).jsonObject
            cashRub = safeFloat(root["cash_rub"], settings.paperInitialBalanceRub)
            root["positions"]?.jsonObject?.forEach { (k, v) -> positions[k] = safeFloat(v) }
            root["avg_price"]?.jsonObject?.forEach { (k, v) -> avgPrice[k] = safeFloat(v) }
            root["last_prices"]?.jsonObject?.forEach { (k, v) ->
                safeFloat(v).takeIf { it > 0 }?.let { lastKnownPrices[k] = it }
            }
            root["open_entry_commission_rub"]?.jsonObject?.forEach { (k, v) -> openEntryCommission[k] = safeFloat(v) }
            realizedGrossPnlRub = safeFloat(root["realized_gross_pnl_rub"], safeFloat(root["realized_pnl_rub"]))
            realizedCommissionRub = safeFloat(root["realized_commission_rub"])
            tradeCount = root["trade_count"]?.jsonPrimitive?.intOrNull ?: 0
            scrubDustPositions()
        }
        val balPath = settings.paperBalanceStateFile
        if (balPath.toFile().exists()) {
            val root = json.parseToJsonElement(balPath.toFile().readText()).jsonObject
            initialBalanceRub = safeFloat(root["initial_balance_rub"], settings.paperInitialBalanceRub)
            lastResetAt = root["last_reset_at"]?.jsonPrimitive?.content ?: lastResetAt
        } else syncBalanceSnapshot(emptyMap())
        if (!statePath.toFile().exists()) saveTradingState()
    }

    private fun commissionForNotional(notional: Double): Double {
        if (!settings.paperIncludeCommission || notional <= 0) return 0.0
        return max(notional * settings.paperCommissionRate, settings.paperCommissionMinRub)
    }

    private fun maxAffordableBuyNotional(cash: Double): Double {
        if (!settings.paperIncludeCommission) return max(0.0, cash)
        val rate = settings.paperCommissionRate
        val minC = settings.paperCommissionMinRub
        val candidates = mutableListOf(0.0)
        val caseB = cash - minC
        if (caseB > 0 && (rate <= 0 || caseB * rate <= minC + 1e-12)) candidates += caseB
        if (rate >= 0) {
            val caseA = cash / (1.0 + rate)
            if (rate <= 0 || caseA * rate + 1e-12 >= minC) candidates += caseA
        }
        return candidates.maxOrNull() ?: 0.0
    }

    override fun getPosition(symbol: String): Pair<Double, Double> =
        positions.getOrDefault(symbol, 0.0) to avgPrice.getOrDefault(symbol, 0.0)

    override fun placeOrder(symbol: String, side: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        if (marketPrice <= 0 || amountRub <= 0) return null
        return when (side) {
            "BUY" -> buy(symbol, amountRub, marketPrice)
            "SELL" -> sell(symbol, amountRub, marketPrice)
            "OPEN_SHORT" -> openShort(symbol, amountRub, marketPrice)
            "CLOSE_SHORT" -> closeShort(symbol, amountRub, marketPrice)
            else -> null
        }
    }

    override fun canOpenShort(symbol: String) = true

    private fun buy(symbol: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        val spend = minOf(amountRub, maxAffordableBuyNotional(cashRub))
        if (spend <= 0) return null
        val commission = commissionForNotional(spend)
        val qty = spend / marketPrice
        val oldQty = positions.getOrDefault(symbol, 0.0)
        val oldAvg = avgPrice.getOrDefault(symbol, 0.0)
        val totalQty = oldQty + qty
        avgPrice[symbol] = ((oldQty * oldAvg) + (qty * marketPrice)) / totalQty
        positions[symbol] = totalQty
        openEntryCommission[symbol] = openEntryCommission.getOrDefault(symbol, 0.0) + commission
        cashRub -= spend + commission
        realizedCommissionRub += commission
        tradeCount++
        log.info("[PAPER] BUY {} qty={} price={} commission={}", symbol, qty, marketPrice, commission)
        val exec = OrderExecution("BUY", qty, spend, commission, 0.0, 0.0, -commission,
            if (spend > 0) -commission / spend * 100 else 0.0, commission, 0.0, spend)
        save(mapOf(symbol to marketPrice))
        return exec
    }

    private fun sell(symbol: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        val held = positions.getOrDefault(symbol, 0.0)
        if (held <= 0) return null
        val qty = minOf(held, amountRub / marketPrice)
        if (qty <= 0) return null
        val notional = qty * marketPrice
        val commission = commissionForNotional(notional)
        val entry = avgPrice.getOrDefault(symbol, marketPrice)
        val gross = (marketPrice - entry) * qty
        val grossPct = if (entry > 0) ((marketPrice / entry) - 1.0) * 100 else 0.0
        val entryPool = openEntryCommission.getOrDefault(symbol, 0.0)
        val entryAlloc = if (held > 0) entryPool * (qty / held) else 0.0
        val totalComm = entryAlloc + commission
        val net = gross - totalComm
        val netPct = if (entry * qty > 0) net / (entry * qty) * 100 else 0.0
        realizedGrossPnlRub += gross
        realizedCommissionRub += commission
        val left = held - qty
        if (left <= 1e-8) {
            positions.remove(symbol); avgPrice.remove(symbol); openEntryCommission.remove(symbol)
        } else {
            positions[symbol] = left
            openEntryCommission[symbol] = max(0.0, entryPool - entryAlloc)
        }
        cashRub += notional - commission
        tradeCount++
        log.info("[PAPER] SELL {} qty={} price={} net={}", symbol, qty, marketPrice, net)
        val exec = OrderExecution("SELL", qty, notional, totalComm, gross, grossPct, net, netPct, entryAlloc, commission, notional)
        save(mapOf(symbol to marketPrice))
        return exec
    }

    private fun openShort(symbol: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        val qty = amountRub / marketPrice
        if (qty <= 0) return null
        val notional = qty * marketPrice
        val commission = commissionForNotional(notional)
        val oldQty = (-positions.getOrDefault(symbol, 0.0)).coerceAtLeast(0.0)
        val oldAvg = avgPrice.getOrDefault(symbol, 0.0)
        val totalQty = oldQty + qty
        avgPrice[symbol] = ((oldQty * oldAvg) + (qty * marketPrice)) / totalQty
        positions[symbol] = -totalQty
        openEntryCommission[symbol] = openEntryCommission.getOrDefault(symbol, 0.0) + commission
        cashRub += notional - commission
        realizedCommissionRub += commission
        tradeCount++
        log.info("[PAPER] SHORT {} qty={} price={} commission={}", symbol, qty, marketPrice, commission)
        val exec = OrderExecution("OPEN_SHORT", qty, notional, commission, 0.0, 0.0, -commission,
            if (notional > 0) -commission / notional * 100 else 0.0, commission, 0.0, notional)
        save(mapOf(symbol to marketPrice))
        return exec
    }

    private fun closeShort(symbol: String, amountRub: Double, marketPrice: Double): OrderExecution? {
        val held = (-positions.getOrDefault(symbol, 0.0)).coerceAtLeast(0.0)
        if (held <= 0) return null
        val qty = minOf(held, amountRub / marketPrice)
        if (qty <= 0) return null
        val notional = qty * marketPrice
        val commission = commissionForNotional(notional)
        val entry = avgPrice.getOrDefault(symbol, marketPrice)
        val gross = (entry - marketPrice) * qty
        val entryPool = openEntryCommission.getOrDefault(symbol, 0.0)
        val entryAlloc = entryPool * (qty / held)
        val totalComm = entryAlloc + commission
        val net = gross - totalComm
        val netPct = if (entry * qty > 0) net / (entry * qty) * 100 else 0.0
        realizedGrossPnlRub += gross
        realizedCommissionRub += commission
        val left = held - qty
        if (left <= 1e-8) {
            positions.remove(symbol); avgPrice.remove(symbol); openEntryCommission.remove(symbol)
        } else {
            positions[symbol] = -left
            openEntryCommission[symbol] = max(0.0, entryPool - entryAlloc)
        }
        cashRub -= notional + commission
        tradeCount++
        log.info("[PAPER] COVER {} qty={} price={} net={}", symbol, qty, marketPrice, net)
        val exec = OrderExecution("CLOSE_SHORT", qty, notional, totalComm, gross,
            if (entry > 0) (entry / marketPrice - 1.0) * 100 else 0.0, net, netPct,
            entryAlloc, commission, notional)
        save(mapOf(symbol to marketPrice))
        return exec
    }

    override fun getBalanceSnapshot(latestPrices: Map<String, Double>): Map<String, Any> {
        val merged = avgPrice.filterValues { it > 0 }.toMutableMap()
        merged.putAll(lastKnownPrices)
        latestPrices.filterValues { it > 0 }.forEach { (symbol, price) -> merged[symbol] = price }
        val marketValue = positions.entries.sumOf { (sym, qty) -> qty * merged.getOrDefault(sym, 0.0) }
        val equity = cashRub + marketValue
        if (TradingSession.shouldStartNewPeriod(lastResetAt)) {
            initialBalanceRub = equity
            lastResetAt = TradingSession.currentDayStart().toString()
            realizedGrossPnlRub = 0.0
            realizedCommissionRub = 0.0
            tradeCount = 0
            log.info("Started a new daily P&L period at {}", lastResetAt)
        }
        val totalPnl = equity - initialBalanceRub
        val unrealized = totalPnl - realizedNetPnlRub
        return mapOf(
            "initial_balance_rub" to initialBalanceRub,
            "current_balance_rub" to equity,
            "cash_rub" to cashRub,
            "market_value_rub" to marketValue,
            "equity_rub" to equity,
            "realized_gross_pnl_rub" to realizedGrossPnlRub,
            "realized_commission_rub" to realizedCommissionRub,
            "realized_net_pnl_rub" to realizedNetPnlRub,
            "realized_pnl_rub" to realizedNetPnlRub,
            "unrealized_pnl_rub" to unrealized,
            "total_pnl_rub" to totalPnl,
            "trade_count" to tradeCount,
            "positions_count" to positions.size,
            "last_reset_at" to lastResetAt,
        )
    }

    fun syncBalanceSnapshot(latestPrices: Map<String, Double>): Map<String, Any> {
        latestPrices.filterValues { it > 0 }.forEach { (symbol, price) -> lastKnownPrices[symbol] = price }
        val snap = getBalanceSnapshot(latestPrices)
        saveTradingState()
        settings.paperBalanceStateFile.toFile().writeText(json.encodeToString(
            buildJsonObject {
                snap.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, JsonPrimitive(v))
                        is String -> put(k, JsonPrimitive(v))
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            }
        ))
        return snap
    }

    override fun performance(latestPrices: Map<String, Double>) = syncBalanceSnapshot(latestPrices)

    override fun getOpenPositions(latestPrices: Map<String, Double>): List<Map<String, Any>> =
        positions.keys.sorted().mapNotNull { sym ->
            val qty = positions[sym] ?: return@mapNotNull null
            if (qty == 0.0) return@mapNotNull null
            val avg = avgPrice.getOrDefault(sym, 0.0)
            val last = latestPrices.getOrDefault(sym, avg)
            mapOf(
                "symbol" to sym, "qty" to qty, "avg_price" to avg, "last_price" to last,
                "market_value_rub" to qty * last, "unrealized_pnl_rub" to (last - avg) * qty,
                "direction" to if (qty > 0) "LONG" else "SHORT",
            )
        }

    override fun resetBalance(initial: Double?): Map<String, Any> {
        val amount = initial?.takeIf { it > 0 } ?: settings.paperInitialBalanceRub
        initialBalanceRub = amount; lastResetAt = isoNow(); cashRub = amount
        positions.clear(); avgPrice.clear(); lastKnownPrices.clear(); openEntryCommission.clear()
        realizedGrossPnlRub = 0.0; realizedCommissionRub = 0.0; tradeCount = 0
        save(emptyMap())
        return getBalanceSnapshot(emptyMap())
    }

    override fun resetPerformanceBaseline(latestPrices: Map<String, Double>): Map<String, Any> {
        val current = getBalanceSnapshot(latestPrices)
        initialBalanceRub = safeFloat(current["equity_rub"] ?: current["current_balance_rub"])
        lastResetAt = isoNow()
        realizedGrossPnlRub = 0.0
        realizedCommissionRub = 0.0
        tradeCount = 0
        save(latestPrices)
        return getBalanceSnapshot(latestPrices)
    }

    private fun save(latestPrices: Map<String, Double>) {
        syncBalanceSnapshot(latestPrices)
    }

    private fun scrubDustPositions() {
        val dust = positions.filter { kotlin.math.abs(it.value) <= 1e-8 }.keys
        dust.forEach { symbol ->
            positions.remove(symbol)
            avgPrice.remove(symbol)
            openEntryCommission.remove(symbol)
            lastKnownPrices.remove(symbol)
        }
    }

    private fun saveTradingState() {
        scrubDustPositions()
        val obj = buildJsonObject {
            put("cash_rub", cashRub)
            put("positions", buildJsonObject { positions.forEach { put(it.key, it.value) } })
            put("avg_price", buildJsonObject { avgPrice.forEach { put(it.key, it.value) } })
            put("last_prices", buildJsonObject {
                lastKnownPrices.filterKeys { it in positions }.forEach { put(it.key, it.value) }
            })
            put("open_entry_commission_rub", buildJsonObject { openEntryCommission.forEach { put(it.key, it.value) } })
            put("realized_gross_pnl_rub", realizedGrossPnlRub)
            put("realized_commission_rub", realizedCommissionRub)
            put("realized_net_pnl_rub", realizedNetPnlRub)
            put("realized_pnl_rub", realizedNetPnlRub)
            put("trade_count", tradeCount)
        }
        settings.paperStateFile.toFile().writeText(json.encodeToString(obj))
    }
}
