package ru.criptobot.adaptive

import kotlinx.serialization.json.*
import ru.criptobot.analytics.AnalyticsDb
import ru.criptobot.config.Settings
import ru.criptobot.util.isoNow
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

typealias BucketKey = String

fun volumeRatioToBucket(volumeRatio: Double?): BucketKey? {
    if (volumeRatio == null || volumeRatio.isNaN()) return null
    return when {
        volumeRatio < 1.0 -> "lt1"
        volumeRatio <= 2.0 -> "b12"
        else -> "gt2"
    }
}

data class AdaptiveRuntimeState(
    val version: Int = 1,
    val baseMlThreshold: Double = 0.63,
    val effectiveThreshold: Map<String, Double> = mapOf("lt1" to 0.63, "b12" to 0.63, "gt2" to 0.63),
    val blocked: Map<String, Boolean> = mapOf("lt1" to false, "b12" to false, "gt2" to false),
    val updatedAt: String = "",
) {
    companion object {
        fun defaultForBase(base: Double) = AdaptiveRuntimeState(
            baseMlThreshold = base,
            effectiveThreshold = mapOf("lt1" to base, "b12" to base, "gt2" to base),
            blocked = mapOf("lt1" to false, "b12" to false, "gt2" to false),
            updatedAt = isoNow(),
        )

        fun clampThreshold(base: Double, value: Double, maxRel: Double): Double {
            val lo = base * (1.0 - maxRel)
            val hi = base * (1.0 + maxRel)
            return max(lo, min(hi, value))
        }
    }
}

class AdaptiveStateStore(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(baseMlThreshold: Double): AdaptiveRuntimeState {
        if (!path.toFile().exists()) {
            val st = AdaptiveRuntimeState.defaultForBase(baseMlThreshold)
            save(st)
            return st
        }
        return try {
            val root = json.parseToJsonElement(path.toFile().readText()).jsonObject
            val eff = root["effective_threshold"]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.double }
                ?: emptyMap()
            val blocked = root["blocked"]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.boolean }
                ?: emptyMap()
            AdaptiveRuntimeState(
                version = root["version"]?.jsonPrimitive?.intOrNull ?: 1,
                baseMlThreshold = root["base_ml_threshold"]?.jsonPrimitive?.doubleOrNull ?: baseMlThreshold,
                effectiveThreshold = eff.ifEmpty { mapOf("lt1" to baseMlThreshold, "b12" to baseMlThreshold, "gt2" to baseMlThreshold) },
                blocked = blocked.ifEmpty { mapOf("lt1" to false, "b12" to false, "gt2" to false) },
                updatedAt = root["updated_at"]?.jsonPrimitive?.content.orEmpty(),
            ).also { st ->
                if (abs(st.baseMlThreshold - baseMlThreshold) > 1e-9) {
                    save(st.copy(baseMlThreshold = baseMlThreshold))
                }
            }
        } catch (_: Exception) {
            AdaptiveRuntimeState.defaultForBase(baseMlThreshold).also { save(it) }
        }
    }

    fun save(state: AdaptiveRuntimeState) {
        val obj = buildJsonObject {
            put("version", state.version)
            put("base_ml_threshold", state.baseMlThreshold)
            put("effective_threshold", buildJsonObject { state.effectiveThreshold.forEach { put(it.key, it.value) } })
            put("blocked", buildJsonObject { state.blocked.forEach { put(it.key, it.value) } })
            put("updated_at", isoNow())
        }
        path.parent?.toFile()?.mkdirs()
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        tmp.toFile().writeText(json.encodeToString(obj))
        tmp.toFile().copyTo(path.toFile(), overwrite = true)
        tmp.toFile().delete()
    }
}

data class AdaptiveResolution(
    val baseMlThreshold: Double,
    val effectiveMlThreshold: Double,
    val hardBlockBucket: Boolean,
    val bucket: BucketKey?,
    val meta: Map<String, Any?> = emptyMap(),
)

object AdaptiveIntegration {
    const val BLOCK_ADAPTIVE = "BLOCK_ADAPTIVE"
    const val BLOCK_ADAPTIVE_BUCKET = "BLOCK_ADAPTIVE_BUCKET"

    fun effectiveMlThresholdForEntry(
        settings: Settings,
        baseMlThreshold: Double,
        volumeRatio: Double?,
        store: AdaptiveStateStore,
    ): AdaptiveResolution {
        val bucket = volumeRatioToBucket(volumeRatio)
        val meta = mutableMapOf<String, Any?>(
            "adaptive_mode" to settings.adaptiveMode,
            "trading_mode" to settings.tradingMode,
            "volume_ratio" to volumeRatio,
            "bucket" to bucket,
        )
        if (bucket == null) {
            return AdaptiveResolution(baseMlThreshold, baseMlThreshold, false, null, meta)
        }
        val state = store.load(baseMlThreshold)
        if (settings.adaptiveMode == "off") {
            meta["note"] = "ADAPTIVE_MODE=off"
            return AdaptiveResolution(baseMlThreshold, baseMlThreshold, false, bucket, meta)
        }
        if (settings.adaptiveMode != "paper" || settings.tradingMode != "paper") {
            meta["note"] = "adaptive execution requires ADAPTIVE_MODE=paper and TRADING_MODE=paper"
            return AdaptiveResolution(baseMlThreshold, baseMlThreshold, false, bucket, meta)
        }
        if (state.blocked.getOrDefault(bucket, false)) {
            meta["hard_block_bucket"] = true
            return AdaptiveResolution(baseMlThreshold, 1.0, true, bucket, meta)
        }
        val eff = state.effectiveThreshold.getOrDefault(bucket, baseMlThreshold)
        meta["effective_ml_threshold"] = eff
        return AdaptiveResolution(baseMlThreshold, eff, false, bucket, meta)
    }

    fun evaluateMlBuyWithAdaptive(
        settings: Settings,
        db: AnalyticsDb,
        mlProb: Double?,
        volumeRatio: Double?,
        baseMlThreshold: Double,
        store: AdaptiveStateStore,
    ): Triple<Boolean, String?, AdaptiveResolution> {
        val res = effectiveMlThresholdForEntry(settings, baseMlThreshold, volumeRatio, store)
        if (settings.tradingMode != "paper" || settings.adaptiveMode != "paper") {
            if (mlProb == null) return Triple(false, BLOCK_ADAPTIVE, res)
            return Triple(mlProb >= res.effectiveMlThreshold, if (mlProb < res.effectiveMlThreshold) BLOCK_ADAPTIVE else null, res)
        }
        if (res.hardBlockBucket) return Triple(false, BLOCK_ADAPTIVE_BUCKET, res)
        if (mlProb == null) return Triple(false, BLOCK_ADAPTIVE, res)
        if (mlProb < res.effectiveMlThreshold) return Triple(false, BLOCK_ADAPTIVE, res)
        return Triple(true, null, res)
    }
}
