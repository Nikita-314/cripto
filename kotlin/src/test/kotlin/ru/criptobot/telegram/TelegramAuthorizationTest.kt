package ru.criptobot.telegram

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class TelegramAuthorizationTest {
    @Test fun `only owner in private chat is allowed`() {
        val chat = TelegramChat(42, "private")
        assertTrue(TelegramService.ownerAllowed(42, chat, TelegramUser(42)))
        assertFalse(TelegramService.ownerAllowed(42, chat, TelegramUser(7)))
        assertFalse(TelegramService.ownerAllowed(42, TelegramChat(7, "private"), TelegramUser(42)))
        assertFalse(TelegramService.ownerAllowed(42, TelegramChat(42, "group"), TelegramUser(42)))
        assertFalse(TelegramService.ownerAllowed(42, chat, null))
        assertFalse(TelegramService.ownerAllowed(42, null, TelegramUser(42)))
        assertFalse(TelegramService.ownerAllowed(null, chat, TelegramUser(42)))
        assertFalse(TelegramService.ownerAllowed(-42, TelegramChat(-42, "group"), TelegramUser(-42)))
    }

    @Test fun `callback authorization uses clicking user not bot message author`() {
        val query = Json { ignoreUnknownKeys = true }.decodeFromString<TelegramCallbackQuery>(
            """{"id":"q","from":{"id":42},"message":{"message_id":1,"from":{"id":999},"chat":{"id":42,"type":"private"}},"data":"reset_performance_baseline"}"""
        )
        assertTrue(TelegramService.ownerAllowed(42, query.message?.chat, query.from))
        assertFalse(TelegramService.ownerAllowed(42, query.message?.chat, query.message?.from))
    }
}
