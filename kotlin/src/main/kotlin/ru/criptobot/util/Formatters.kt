package ru.criptobot.util

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun clamp(value: Double, minValue: Double, maxValue: Double): Double = max(min(value, maxValue), minValue)

fun safeFloat(value: Any?, default: Double = 0.0): Double = when (value) {
    null -> default
    is Number -> value.toDouble().let { if (it.isNaN()) default else it }
    is String -> value.toDoubleOrNull() ?: default
    is JsonPrimitive -> value.doubleOrNull?.let { if (it.isNaN()) default else it } ?: default
    else -> default
}

fun formatRub(value: Any?): String {
    val amount = safeFloat(value)
    return String.format("%,.2f USDT", amount).replace(',', ' ')
}

fun formatSignedRub(value: Double): String {
    return if (abs(value) < 10) String.format("%+.4f USDT", value)
    else String.format("%+,.2f USDT", value).replace(',', ' ')
}

fun formatClosePnlLine(pnl: Double, pnlPct: Double): String {
    val pnlPart = if (abs(pnl) < 10) String.format("%.4f USDT", pnl) else formatRub(pnl)
    return "$pnlPart (${String.format("%.4f", pnlPct)}%)"
}

fun isoNow(): String = Instant.now().toString()

fun formatMoexDateTime(value: Any?): String {
    val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return "неизвестно"
    return runCatching {
        utcDateTimeFormatter.format(Instant.parse(raw).atZone(ZoneOffset.UTC))
    }.getOrDefault(raw)
}

fun barKeyAt(instant: Instant, interval: String): String {
    val dt = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
    val floored = when (interval.lowercase()) {
        "15m" -> dt.truncatedTo(ChronoUnit.HOURS).plusMinutes((dt.minute / 15) * 15L)
        "5m" -> dt.truncatedTo(ChronoUnit.HOURS).plusMinutes((dt.minute / 5) * 5L)
        "1d", "d" -> dt.truncatedTo(ChronoUnit.DAYS)
        else -> dt.truncatedTo(ChronoUnit.HOURS)
    }.withSecond(0).withNano(0)
    return floored.toInstant().toString().substring(0, 19)
}

private val utcDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'")
