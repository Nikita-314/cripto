package ru.criptobot.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TelegramServiceTest {
    @Test
    fun `token is redacted from HTTP error`() {
        val token = "123456:secret-token"
        val message = "Request failed [url=https://api.telegram.org/bot$token/getUpdates]"

        val redacted = TelegramService.redactToken(message, token)

        assertFalse(redacted.contains(token))
        assertEquals(
            "Request failed [url=https://api.telegram.org/bot<redacted>/getUpdates]",
            redacted,
        )
    }

    @Test
    fun `long Telegram text is split without losing content`() {
        val text = (1..20).joinToString("\n") { "Строка $it" }

        val chunks = TelegramService.splitTelegramText(text, limit = 50)

        assertEquals(text, chunks.joinToString("\n"))
        assertFalse(chunks.any { it.length > 50 })
    }
}
