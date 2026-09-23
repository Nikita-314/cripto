package ru.criptobot.adaptive

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.math.floor
import kotlin.math.min

@Serializable
data class ShadowShortObservation(
    val timestamp: String,
    val symbol: String,
    val price: Double,
    val confidence: Double,
    val shortCandidate: Boolean,
    val trendReversedUp: Boolean,
    val features: Map<String, Double> = emptyMap(),
)

@Serializable
data class ShadowShortPosition(
    val symbol: String,
    val openedAt: String,
    val entryPrice: Double,
    val quantity: Double,
    val capitalRub: Double,
    val confidence: Double,
    val stopPrice: Double,
    val activationPrice: Double,
    val features: Map<String, Double>,
    val entryCommissionRub: Double,
    var bestPrice: Double,
    var trailingActive: Boolean = false,
)

@Serializable
data class ShadowShortClosedTrade(
    val closedAt: String,
    val symbol: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val capitalRub: Double,
    val confidence: Double,
    val netPnlRub: Double,
    val netReturnPct: Double,
    val riskRub: Double,
    val reason: String,
    val features: Map<String, Double>,
)

@Serializable
data class ShadowShortPaperState(
    val version: Int = 1,
    val mode: String = "SHADOW_ONLY_NO_BROKER_ORDERS",
    val initialBalanceRub: Double = 100_000.0,
    var realizedPnlRub: Double = 0.0,
    val positions: MutableMap<String, ShadowShortPosition> = mutableMapOf(),
    val closedTrades: MutableList<ShadowShortClosedTrade> = mutableListOf(),
    var updatedAt: String = Instant.EPOCH.toString(),
)

class ShadowShortPaperSimulator(
    private val stateFile: Path,
    private val initialBalanceRub: Double = 100_000.0,
    private val positionSizeRub: Double = 10_000.0,
    private val commissionRate: Double = 0.0005,
    private val stopLossPct: Double = 0.03,
    private val takeProfitPct: Double = 0.06,
    private val trailingCallbackPct: Double = 0.015,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private var state = load()

    init {
        // Re-save even an existing state so all isolation markers and balance fields
        // remain explicit on disk after schema/configuration changes.
        save()
    }

    @Synchronized
    fun hasPosition(symbol: String): Boolean = state.positions.containsKey(symbol.uppercase())

    @Synchronized
    fun observe(observation: ShadowShortObservation, allowEntry: Boolean): String? {
        val symbol = observation.symbol.uppercase()
        val price = observation.price
        if (price <= 0.0) return null
        val current = state.positions[symbol]
        if (current != null) {
            current.bestPrice = min(current.bestPrice, price)
            if (price <= current.activationPrice) current.trailingActive = true
            val trailingStop = min(current.activationPrice, current.bestPrice * (1.0 + trailingCallbackPct))
            val openedAt = runCatching { Instant.parse(current.openedAt) }.getOrDefault(Instant.now())
            val reason = when {
                price >= current.stopPrice -> "STOP_LOSS"
                current.trailingActive && price >= trailingStop -> "TRAILING_TAKE_PROFIT"
                observation.trendReversedUp -> "TREND_REVERSAL"
                Duration.between(openedAt, Instant.parse(observation.timestamp)).toHours() >= 120 -> "MAX_HOLD_5D"
                else -> null
            }
            if (reason != null) {
                close(current, observation.timestamp, price, reason)
                state.positions.remove(symbol)
                save()
                return "CLOSE:$reason"
            }
            save()
            return null
        }
        if (!allowEntry || !observation.shortCandidate) return null
        val usedCapital = state.positions.values.sumOf { it.capitalRub }
        val availableCapital = (state.initialBalanceRub + state.realizedPnlRub - usedCapital).coerceAtLeast(0.0)
        val allocation = min(positionSizeRub, availableCapital)
        val quantity = floor(allocation / price)
        if (quantity < 1.0) return null
        val capital = quantity * price
        val entryCommission = capital * commissionRate
        state.positions[symbol] = ShadowShortPosition(
            symbol = symbol,
            openedAt = observation.timestamp,
            entryPrice = price,
            quantity = quantity,
            capitalRub = capital,
            confidence = observation.confidence,
            stopPrice = price * (1.0 + stopLossPct),
            activationPrice = price * (1.0 - takeProfitPct),
            features = observation.features + mapOf(
                "confidence" to observation.confidence,
                "effective_position_size_rub" to capital,
                "entry_monetary_risk_rub" to capital * stopLossPct,
                "shadow_paper" to 1.0,
            ),
            entryCommissionRub = entryCommission,
            bestPrice = price,
        )
        save()
        return "OPEN"
    }

    @Synchronized
    fun snapshot(): ShadowShortPaperState = state.copy(
        positions = state.positions.toMutableMap(),
        closedTrades = state.closedTrades.toMutableList(),
    )

    private fun close(position: ShadowShortPosition, closedAt: String, exitPrice: Double, reason: String) {
        val exitNotional = position.quantity * exitPrice
        val exitCommission = exitNotional * commissionRate
        val gross = (position.entryPrice - exitPrice) * position.quantity
        val net = gross - position.entryCommissionRub - exitCommission
        state.realizedPnlRub += net
        state.closedTrades += ShadowShortClosedTrade(
            closedAt, position.symbol, position.entryPrice, exitPrice, position.quantity,
            position.capitalRub, position.confidence, net,
            if (position.capitalRub > 0) net / position.capitalRub * 100.0 else 0.0,
            position.capitalRub * stopLossPct, reason, position.features,
        )
        if (state.closedTrades.size > 2_000) {
            state.closedTrades.subList(0, state.closedTrades.size - 2_000).clear()
        }
    }

    private fun load(): ShadowShortPaperState = runCatching {
        if (!Files.exists(stateFile)) ShadowShortPaperState(initialBalanceRub = initialBalanceRub)
        else json.decodeFromString<ShadowShortPaperState>(Files.readString(stateFile))
    }.getOrElse { ShadowShortPaperState(initialBalanceRub = initialBalanceRub) }

    private fun save() {
        state.updatedAt = Instant.now().toString()
        stateFile.parent?.let(Files::createDirectories)
        val temp = stateFile.resolveSibling("${stateFile.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(state))
        runCatching {
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun loadLearningTrades(stateFile: Path): List<ShadowClosedTrade> = runCatching {
            if (!Files.exists(stateFile)) return emptyList()
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<ShadowShortPaperState>(Files.readString(stateFile)).closedTrades.map {
                ShadowClosedTrade(
                    timestamp = it.closedAt,
                    side = "SHORT",
                    confidence = it.confidence,
                    netReturnPct = it.netReturnPct,
                    features = it.features,
                    netPnlRub = it.netPnlRub,
                    capitalRub = it.capitalRub,
                    riskRub = it.riskRub,
                )
            }
        }.getOrDefault(emptyList())
    }
}
