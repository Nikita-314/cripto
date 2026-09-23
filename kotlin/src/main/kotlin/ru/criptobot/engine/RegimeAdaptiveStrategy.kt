package ru.criptobot.engine

import ru.criptobot.features.FeatureRow
import kotlin.math.abs
import kotlin.math.max

data class RegimeSignal(
    val action: String,
    val reasonCode: String,
    val confidence: Double,
)

/**
 * Crypto v7: longs and shorts stay on, but entries wait for a pullback
 * after the 1h Supertrend flip instead of chasing the breakout bar.
 *
 * v6 filled late continuations, then every closed trade hit the 5% stop
 * before the 12% target could arm trailing. v7 buys/shorts the first
 * pullback while 4h structure still agrees, cuts on 1h structure break,
 * and lets trailing lock a smaller move.
 */
object RegimeAdaptiveStrategy {
    const val VERSION = "crypto-trend-v7-20260903"
    const val DECISION_INTERVAL = "1h"
    const val MIN_TREND_BARS = 3
    const val SHORT_MIN_TREND_BARS = 6
    const val HISTORY_DAYS = 400
    const val REQUIRE_HIGHER_TF = true
    const val MAX_ATR_PCT = 0.08
    const val LONG_BREADTH_BLOCK = 0.70
    const val SHORT_BREADTH_REQUIRE = 0.55
    const val LONG_ML_FLOOR = 0.55
    const val SHORT_ML_CEILING = 0.40
    const val SHORT_RSI_FLOOR = 35.0
    const val LONG_RSI_CEILING = 68.0
    const val SHORT_RSI_CEILING = 65.0
    const val PULLBACK_WINDOW_BARS = 6
    const val PULLBACK_MAX_DIST_ATR = 2.2
    const val PULLBACK_MAX_RET5 = 0.025
    const val CONFIRM_ADVERSE_PCT = 0.02
    const val CONFIRM_CHASE_PCT = 0.03
    const val ATR_STOP_MULT = 2.5
    const val ATR_ACTIVATION_MULT = 2.0

    val HARD_REVERSAL_EXITS = setOf(
        "SHORTS_DISABLED_FLAT",
        "EXIT_ON_HIGHER_TF_FLIP",
        "COVER_ON_HIGHER_TF_FLIP",
        "EXIT_1H_STRUCTURE",
        "COVER_1H_STRUCTURE",
    )

    fun flipped(previousStDir: Double?, currentStDir: Double?): Boolean {
        val prev = previousStDir ?: return false
        val curr = currentStDir ?: return false
        return prev != 0.0 && curr != 0.0 && prev != curr
    }

    fun previousTrendAge(features: List<FeatureRow>, index: Int): Int {
        if (index <= 0) return 0
        val dir = features[index - 1].stDir ?: return 0
        var age = 0
        var i = index - 1
        while (i >= 0 && features[i].stDir == dir) {
            age++
            i--
        }
        return age
    }

    fun nextBarConfirmsLong(signalClose: Double, nextClose: Double): Boolean {
        if (signalClose <= 0.0 || nextClose <= 0.0) return false
        val change = (nextClose / signalClose) - 1.0
        return change >= -CONFIRM_ADVERSE_PCT && change <= CONFIRM_CHASE_PCT
    }

    fun nextBarConfirmsShort(signalClose: Double, nextClose: Double): Boolean {
        if (signalClose <= 0.0 || nextClose <= 0.0) return false
        val change = (nextClose / signalClose) - 1.0
        return change <= CONFIRM_ADVERSE_PCT && change >= -CONFIRM_CHASE_PCT
    }

    fun evaluate(
        previousStDir: Double?,
        current: FeatureRow,
        mlProbUp: Double?,
        fallingShare: Double,
        shortsEnabled: Boolean,
        hasLong: Boolean,
        hasShort: Boolean,
        minPreviousTrendBars: Int = 0,
        previousTrendAge: Int = 0,
        higherTfDir: Double? = null,
        requireHigherTf: Boolean = false,
    ): RegimeSignal {
        val close = current.close ?: return hold("NO_CLOSE")
        val stDir = current.stDir ?: 0.0
        val ema50 = current.ema50 ?: current.ema200 ?: close
        val ema200 = current.ema200 ?: ema50
        val atrPct = current.atrPct ?: 0.0
        val rsi = current.rsi ?: 50.0
        val ret5 = current.ret5
        val distToSt = current.distToSt?.let(::abs)

        if (hasShort) {
            if (!shortsEnabled) return RegimeSignal("COVER", "SHORTS_DISABLED_FLAT", 0.8)
            if (requireHigherTf && higherTfDir != null && higherTfDir >= 1.0) {
                return RegimeSignal("COVER", "COVER_ON_HIGHER_TF_FLIP", 0.8)
            }
            if (close > ema200) return RegimeSignal("COVER", "COVER_ABOVE_EMA200", 0.6)
            if (stDir >= 1.0 && close > ema50) return RegimeSignal("COVER", "COVER_1H_STRUCTURE", 0.7)
            return hold("HOLD_SHORT_POSITION")
        }
        if (hasLong) {
            if (requireHigherTf && higherTfDir != null && higherTfDir <= -1.0) {
                return RegimeSignal("SELL", "EXIT_ON_HIGHER_TF_FLIP", -0.8)
            }
            if (close < ema200) return RegimeSignal("SELL", "EXIT_BELOW_EMA200", -0.6)
            if (stDir <= -1.0 && close < ema50) return RegimeSignal("SELL", "EXIT_1H_STRUCTURE", -0.7)
            return hold("HOLD_OPEN_POSITION")
        }

        if (atrPct > MAX_ATR_PCT) return hold("CHOP_OR_PANIC_ATR")

        val justFlipped = flipped(previousStDir, stDir)
        val requiredAge = if (justFlipped && stDir <= -1.0 && minPreviousTrendBars > 0) {
            max(minPreviousTrendBars, SHORT_MIN_TREND_BARS)
        } else {
            minPreviousTrendBars
        }
        if (justFlipped && requiredAge > 0 && previousTrendAge < requiredAge) {
            return hold("TREND_TOO_SHORT")
        }

        val longStructure = close > ema50 && close > ema200 &&
            (!requireHigherTf || (higherTfDir != null && higherTfDir >= 1.0)) &&
            fallingShare < LONG_BREADTH_BLOCK &&
            (mlProbUp == null || mlProbUp >= LONG_ML_FLOOR)
        val shortStructure = shortsEnabled &&
            close < ema50 && close < ema200 &&
            rsi >= SHORT_RSI_FLOOR &&
            (!requireHigherTf || (higherTfDir != null && higherTfDir <= -1.0)) &&
            fallingShare >= SHORT_BREADTH_REQUIRE &&
            (mlProbUp == null || mlProbUp <= SHORT_ML_CEILING)

        if (justFlipped && stDir >= 1.0) {
            if (close < ema50 || close < ema200) return hold("LONG_BELOW_EMA")
            if (requireHigherTf && (higherTfDir == null || higherTfDir < 1.0)) return hold("HIGHER_TF_AGAINST_LONG")
            if (fallingShare >= LONG_BREADTH_BLOCK) return hold("BREADTH_BLOCKS_LONG")
            if (mlProbUp != null && mlProbUp < LONG_ML_FLOOR) return hold("ML_AGAINST_LONG")
            return RegimeSignal("BUY", "ENTRY_ON_SUPERTREND_FLIP", mlProbUp ?: 0.62)
        }
        if (justFlipped && stDir <= -1.0) {
            if (!shortsEnabled) return hold("NO_FLIP_OR_REGIME")
            if (close >= ema50 || close >= ema200) return hold("SHORT_ABOVE_EMA")
            if (rsi < SHORT_RSI_FLOOR) return hold("SHORT_OVERSOLD")
            if (requireHigherTf && (higherTfDir == null || higherTfDir > -1.0)) return hold("HIGHER_TF_AGAINST_SHORT")
            if (fallingShare < SHORT_BREADTH_REQUIRE) return hold("BREADTH_BLOCKS_SHORT")
            if (mlProbUp != null && mlProbUp > SHORT_ML_CEILING) return hold("ML_AGAINST_SHORT")
            return RegimeSignal("SHORT", "ENTRY_ON_SUPERTREND_FLIP", 1.0 - (mlProbUp ?: 0.38))
        }

        val inPullbackWindow = previousTrendAge in 1..PULLBACK_WINDOW_BARS
        if (stDir >= 1.0 && inPullbackWindow && longStructure && isLongPullback(rsi, ret5, distToSt)) {
            return RegimeSignal("BUY", "ENTRY_ON_PULLBACK", mlProbUp ?: 0.60)
        }
        if (stDir <= -1.0 && inPullbackWindow && shortStructure && isShortPullback(rsi, ret5, distToSt)) {
            return RegimeSignal("SHORT", "ENTRY_ON_PULLBACK", 1.0 - (mlProbUp ?: 0.38))
        }
        return hold("NO_FLIP_OR_REGIME")
    }

    fun stopLossPrice(entry: Double, atr: Double?, stopLossPct: Double, short: Boolean): Double {
        val distance = maxOf(entry * stopLossPct.coerceAtLeast(0.0), (atr ?: 0.0) * ATR_STOP_MULT)
        return if (short) entry + distance else entry - distance
    }

    fun activationPrice(entry: Double, atr: Double?, takeProfitPct: Double, short: Boolean): Double {
        val distance = maxOf(entry * takeProfitPct.coerceAtLeast(0.0), (atr ?: 0.0) * ATR_ACTIVATION_MULT)
        return if (short) entry - distance else entry + distance
    }

    private fun isLongPullback(rsi: Double, ret5: Double?, distToSt: Double?): Boolean =
        rsi <= LONG_RSI_CEILING &&
            (ret5 == null || ret5 <= PULLBACK_MAX_RET5) &&
            (distToSt == null || distToSt <= PULLBACK_MAX_DIST_ATR)

    private fun isShortPullback(rsi: Double, ret5: Double?, distToSt: Double?): Boolean =
        rsi <= SHORT_RSI_CEILING &&
            (ret5 == null || ret5 >= -PULLBACK_MAX_RET5) &&
            (distToSt == null || distToSt <= PULLBACK_MAX_DIST_ATR)

    private fun hold(reason: String) = RegimeSignal("HOLD", reason, 0.0)
}
