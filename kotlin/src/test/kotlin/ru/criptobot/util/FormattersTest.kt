package ru.criptobot.util

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormattersTest {
    @Test
    fun `safeFloat reads numeric JSON primitive`() {
        assertEquals(20_200.495, safeFloat(JsonPrimitive(20_200.495)), 1e-9)
    }

    @Test
    fun `safeFloat keeps default for non numeric JSON primitive`() {
        assertEquals(42.0, safeFloat(JsonPrimitive("invalid"), 42.0))
    }

    @Test
    fun `formats session time in UTC`() {
        assertEquals("27.07.2026 03:50 UTC", formatMoexDateTime("2026-07-27T03:50:00Z"))
    }

    @Test
    fun `keeps unknown date value readable`() {
        assertEquals("неизвестно", formatMoexDateTime(null))
        assertEquals("старый формат", formatMoexDateTime("старый формат"))
    }

    @Test
    fun `formats money as USDT`() {
        assertEquals("1 000.00 USDT", formatRub(1000))
    }
}
