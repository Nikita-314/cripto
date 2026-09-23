package ru.criptobot.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

data class TrailingPosition(
    val symbol: String,
    val direction: String,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val activationPrice: Double,
    val callbackPct: Double,
    var extremePrice: Double,
    var activated: Boolean,
    val createdAt: String,
)

data class TrailingEvaluation(
    val exitReason: String? = null,
    val triggerPrice: Double? = null,
    val activatedNow: Boolean = false,
)

object TrailingTakeProfit {
    fun evaluate(state: TrailingPosition, price: Double): TrailingEvaluation {
        if (price <= 0) return TrailingEvaluation()
        val isShort = state.direction == "SHORT"
        val stopTriggered = if (isShort) price >= state.stopLossPrice else price <= state.stopLossPrice
        if (stopTriggered) return TrailingEvaluation("Stop-loss", state.stopLossPrice)

        if (!state.activated) {
            val activationReached = if (isShort) price <= state.activationPrice else price >= state.activationPrice
            if (!activationReached) return TrailingEvaluation()
            state.activated = true
            state.extremePrice = price
            return TrailingEvaluation(activatedNow = true)
        }

        if (isShort) {
            if (price < state.extremePrice) {
                state.extremePrice = price
                return TrailingEvaluation()
            }
            val trigger = minOf(state.activationPrice, state.extremePrice * (1.0 + state.callbackPct))
            if (price >= trigger) return TrailingEvaluation("Trailing take-profit", trigger)
        } else {
            if (price > state.extremePrice) {
                state.extremePrice = price
                return TrailingEvaluation()
            }
            val trigger = maxOf(state.activationPrice, state.extremePrice * (1.0 - state.callbackPct))
            if (price <= trigger) return TrailingEvaluation("Trailing take-profit", trigger)
        }
        return TrailingEvaluation()
    }
}

class TrailingPositionStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states = mutableMapOf<String, TrailingPosition>()

    init {
        load()
    }

    @Synchronized
    fun symbols(): Set<String> = states.keys.toSet()

    @Synchronized
    fun contains(symbol: String): Boolean = symbol.uppercase() in states

    @Synchronized
    fun register(
        symbol: String,
        direction: String,
        entryPrice: Double,
        stopLossPrice: Double,
        activationPrice: Double,
        callbackPct: Double,
    ) {
        val key = symbol.uppercase()
        states[key] = newState(key, direction, entryPrice, stopLossPrice, activationPrice, callbackPct)
        save()
    }

    @Synchronized
    fun registerIfAbsent(
        symbol: String,
        direction: String,
        entryPrice: Double,
        stopLossPrice: Double,
        activationPrice: Double,
        callbackPct: Double,
    ): Boolean {
        val key = symbol.uppercase()
        if (key in states) return false
        states[key] = newState(key, direction, entryPrice, stopLossPrice, activationPrice, callbackPct)
        save()
        return true
    }

    @Synchronized
    fun evaluate(symbol: String, price: Double): TrailingEvaluation? {
        val state = states[symbol.uppercase()] ?: return null
        val oldExtreme = state.extremePrice
        val oldActivated = state.activated
        val result = TrailingTakeProfit.evaluate(state, price)
        if (oldExtreme != state.extremePrice || oldActivated != state.activated) save()
        return result
    }

    @Synchronized
    fun remove(symbol: String) {
        if (states.remove(symbol.uppercase()) != null) save()
    }

    @Synchronized
    fun state(symbol: String): TrailingPosition? = states[symbol.uppercase()]?.copy()

    private fun newState(
        symbol: String,
        direction: String,
        entryPrice: Double,
        stopLossPrice: Double,
        activationPrice: Double,
        callbackPct: Double,
    ) = TrailingPosition(
        symbol = symbol,
        direction = direction,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        activationPrice = activationPrice,
        callbackPct = callbackPct,
        extremePrice = activationPrice,
        activated = false,
        createdAt = Instant.now().toString(),
    )

    private fun load() {
        if (!Files.exists(file)) return
        runCatching {
            val root = json.parseToJsonElement(Files.readString(file)).jsonArray
            root.forEach { element ->
                val item = element.jsonObject
                val symbol = item["symbol"]?.jsonPrimitive?.content?.uppercase().orEmpty()
                val direction = item["direction"]?.jsonPrimitive?.content.orEmpty()
                val entry = item["entryPrice"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val sl = item["stopLossPrice"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val activation = item["activationPrice"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val callback = item["callbackPct"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                if (symbol.isEmpty() || direction !in setOf("LONG", "SHORT")) return@forEach
                states[symbol] = TrailingPosition(
                    symbol, direction, entry, sl, activation, callback,
                    item["extremePrice"]?.jsonPrimitive?.doubleOrNull ?: activation,
                    item["activated"]?.jsonPrimitive?.booleanOrNull ?: false,
                    item["createdAt"]?.jsonPrimitive?.content ?: Instant.now().toString(),
                )
            }
        }
    }

    private fun save() {
        file.parent?.let { Files.createDirectories(it) }
        val payload = buildJsonArray {
            states.values.sortedBy { it.symbol }.forEach { state ->
                add(buildJsonObject {
                    put("symbol", state.symbol)
                    put("direction", state.direction)
                    put("entryPrice", state.entryPrice)
                    put("stopLossPrice", state.stopLossPrice)
                    put("activationPrice", state.activationPrice)
                    put("callbackPct", state.callbackPct)
                    put("extremePrice", state.extremePrice)
                    put("activated", state.activated)
                    put("createdAt", state.createdAt)
                })
            }
        }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(payload))
        runCatching {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
