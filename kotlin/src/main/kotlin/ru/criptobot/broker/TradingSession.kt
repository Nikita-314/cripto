package ru.criptobot.broker

import java.time.Instant
import java.time.ZoneOffset

/** Crypto markets are 24/7 — daily P&L period resets at UTC midnight. */
internal object TradingSession {
    fun currentDayStart(now: Instant = Instant.now()): Instant =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

    fun shouldStartNewPeriod(lastResetAt: String?, now: Instant = Instant.now()): Boolean {
        val sessionStart = currentDayStart(now)
        if (now.isBefore(sessionStart)) return false
        val previous = lastResetAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return previous == null || previous.isBefore(sessionStart)
    }
}
