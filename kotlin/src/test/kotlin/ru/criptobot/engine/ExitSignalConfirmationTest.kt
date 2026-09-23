package ru.criptobot.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExitSignalConfirmationTest {
    @Test
    fun `requires matching exit on a different bar`() {
        val confirmation = ExitSignalConfirmation()

        assertFalse(confirmation.confirm("SBER", "SELL", "2026-07-30T10"))
        assertFalse(confirmation.confirm("SBER", "SELL", "2026-07-30T10"))
        assertTrue(confirmation.confirm("SBER", "SELL", "2026-07-30T11"))
    }

    @Test
    fun `opposite or cleared signal resets candidate`() {
        val confirmation = ExitSignalConfirmation()

        assertFalse(confirmation.confirm("SBER", "SELL", "2026-07-30T10"))
        confirmation.clear("SBER")
        assertFalse(confirmation.confirm("SBER", "SELL", "2026-07-30T11"))
    }
}
