package ru.criptobot.filters

import ru.criptobot.config.Settings
import ru.criptobot.util.safeFloat
import kotlin.math.min

data class PaperHighVolumeGateResult(
    val triggered: Boolean,
    val blockBuy: Boolean,
    val reasonCode: String?,
    val volumeRatio: Double?,
    val baseMlThreshold: Double,
    val effectiveMlThreshold: Double,
    val details: Map<String, Any?>,
)

object PaperEntryVolumeFilter {
    const val BLOCK_REASON = "BLOCK_HIGH_VOLUME_RATIO"

    fun apply(
        settings: Settings,
        tradingMode: String,
        volumeRatio: Double?,
        mlProb: Double?,
        baseMlThreshold: Double,
    ): PaperHighVolumeGateResult {
        fun ok(vr: Double?) = PaperHighVolumeGateResult(false, false, null, vr, baseMlThreshold, baseMlThreshold, emptyMap())
        if (tradingMode != "paper" || !settings.paperHighVolumeFilterEnabled) return ok(volumeRatio)
        if (volumeRatio == null || volumeRatio <= settings.paperHighVolumeRatioLimit) return ok(volumeRatio)
        val eff = min(0.99, baseMlThreshold * (1.0 + settings.paperHighVolumeMlBump))
        if (settings.paperHighVolumeAction == "raise_ml_threshold") {
            val block = mlProb == null || mlProb < eff
            if (!block) {
                return PaperHighVolumeGateResult(true, false, null, volumeRatio, baseMlThreshold, eff, mapOf("note" to "ml clears raised threshold"))
            }
        }
        val details = mapOf(
            "volume_ratio" to volumeRatio, "filter" to "paper_high_volume",
            "base_ml_threshold" to baseMlThreshold, "effective_ml_threshold" to baseMlThreshold,
            "ml_prob" to mlProb, "reason_code" to BLOCK_REASON,
        )
        return PaperHighVolumeGateResult(true, true, BLOCK_REASON, volumeRatio, baseMlThreshold, baseMlThreshold, details)
    }

    fun extractVolumeRatio(snapshot: Map<String, Any?>?): Double? {
        if (snapshot == null) return null
        for (key in listOf("volume_ratio", "vol_ratio")) {
            val v = safeFloat(snapshot[key], Double.NaN)
            if (!v.isNaN()) return v
        }
        return null
    }
}
