package ru.criptobot.telegram

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings

@Serializable data class TelegramUpdateResponse(val ok: Boolean = false, val result: List<TelegramUpdate> = emptyList())
@Serializable data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("callback_query") val callbackQuery: TelegramCallbackQuery? = null,
)
@Serializable data class TelegramMessage(@SerialName("message_id") val messageId: Long, val chat: TelegramChat, val text: String? = null, val from: TelegramUser? = null)
@Serializable data class TelegramUser(val id: Long)
@Serializable data class TelegramChat(val id: Long, val type: String? = null)
@Serializable data class TelegramCallbackQuery(
    val id: String,
    val message: TelegramMessage? = null,
    val data: String? = null,
    val from: TelegramUser? = null,
)

class TelegramService(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(TelegramService::class.java)
    private val token = settings.telegramBotToken
    private val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 35_000 } }
    private val json = Json { ignoreUnknownKeys = true }
    private val base get() = "https://api.telegram.org/bot$token"

    fun isAuthorized(message: TelegramMessage) = ownerAllowed(settings.telegramChatId, message.chat, message.from)
    fun isAuthorized(query: TelegramCallbackQuery) = ownerAllowed(settings.telegramChatId, query.message?.chat, query.from)

    fun enabled() = token.isNotBlank()
    fun notifyEnabled() = enabled() && settings.telegramChatId != null

    suspend fun sendMessage(chatId: Long, text: String, replyMarkup: JsonObject? = null): Long? {
        if (!enabled()) return null
        return runCatching {
            var firstMessageId: Long? = null
            val chunks = splitTelegramText(text)
            chunks.forEachIndexed { index, chunk ->
                var messageId: Long? = null
                var lastError: Exception? = null
                repeat(3) { attempt ->
                    if (messageId != null) return@repeat
                    try {
                        val resp = client.post("$base/sendMessage") {
                            contentType(ContentType.Application.Json)
                            setBody(buildJsonObject {
                                put("chat_id", chatId)
                                put("text", chunk)
                                if (index == chunks.lastIndex) replyMarkup?.let { put("reply_markup", it) }
                            }.toString())
                        }.bodyAsText()
                        val root = json.parseToJsonElement(resp).jsonObject
                        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                            error("Telegram API rejected message: ${root["description"]?.jsonPrimitive?.contentOrNull ?: resp.take(200)}")
                        }
                        messageId = root["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.longOrNull
                            ?: error("Telegram API returned no message_id")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        if (attempt < 2) delay(500L * (attempt + 1))
                    }
                }
                if (messageId == null) throw lastError ?: IllegalStateException("Telegram send failed")
                if (firstMessageId == null) firstMessageId = messageId
            }
            firstMessageId
        }.onFailure { log.warn("Telegram send failed: {}", safeError(it)) }.getOrNull()
    }

    suspend fun editMessage(chatId: Long, messageId: Long, text: String, replyMarkup: JsonObject? = null): Boolean {
        if (!enabled()) return false
        return runCatching {
            val response = client.post("$base/editMessageText") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("message_id", messageId)
                    put("text", text)
                    replyMarkup?.let { put("reply_markup", it) }
                }.toString())
            }.bodyAsText()
            val root = json.parseToJsonElement(response).jsonObject
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val description = root["description"]?.jsonPrimitive?.contentOrNull ?: response.take(200)
                log.warn("Telegram edit rejected: {}", redactToken(description, token))
                false
            } else true
        }.onFailure { log.warn("Telegram edit failed: {}", safeError(it)) }.getOrDefault(false)
    }

    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null) {
        if (!enabled()) return
        runCatching {
            client.post("$base/answerCallbackQuery") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("callback_query_id", callbackQueryId)
                    text?.let { put("text", it) }
                }.toString())
            }
        }.onFailure { log.warn("Telegram callback answer failed: {}", safeError(it)) }
    }

    suspend fun pollUpdates(
        onMessage: suspend (TelegramMessage) -> Unit,
        onCallback: suspend (TelegramCallbackQuery) -> Unit = {},
    ) {
        var offset = 0L
        while (true) {
            try {
                val resp = client.get("$base/getUpdates") { parameter("offset", offset); parameter("timeout", 25) }.bodyAsText()
                val body = json.decodeFromString<TelegramUpdateResponse>(resp)
                if (!body.ok) continue
                for (u in body.result) {
                    offset = u.updateId + 1
                    u.message?.let { onMessage(it) }
                    u.callbackQuery?.let { onCallback(it) }
                }
            } catch (e: Exception) {
                log.warn("Telegram polling error: {}", safeError(e))
                delay(3000)
            }
        }
    }

    internal fun safeError(error: Throwable): String {
        val message = error.message ?: error::class.simpleName.orEmpty()
        return redactToken(message, token)
    }

    fun keyboardMain() = buildJsonObject {
        put("keyboard", buildJsonArray {
            add(buildJsonArray { addJsonObject { put("text", BTN_STATUS) }; addJsonObject { put("text", BTN_STRATEGY) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_PNL) }; addJsonObject { put("text", BTN_BALANCE) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_POSITIONS) }; addJsonObject { put("text", BTN_CLOSE_ALL) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_SYMBOLS) }; addJsonObject { put("text", BTN_BLACKLIST) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_REMOVE_SYMBOL) }; addJsonObject { put("text", BTN_RESTORE_SYMBOL) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_HELP) } })
        })
        put("resize_keyboard", true)
    }

    fun keyboardPnlWeek() = inlineKeyboard(listOf(BTN_PNL_WEEK to CALLBACK_PNL_WEEK))

    fun keyboardPnlToday() = inlineKeyboard(listOf(BTN_PNL_TODAY to CALLBACK_PNL_TODAY))

    private fun inlineKeyboard(buttons: List<Pair<String, String>>) = buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            add(buildJsonArray {
                buttons.forEach { (text, callback) ->
                    addJsonObject { put("text", text); put("callback_data", callback) }
                }
            })
        })
    }

    fun keyboardAiStrategyProposal() = buildJsonObject {
        put("keyboard", buildJsonArray {
            add(buildJsonArray {
                addJsonObject { put("text", BTN_APPLY_AI_STRATEGY) }
                addJsonObject { put("text", BTN_REJECT_AI_STRATEGY) }
            })
        })
        put("resize_keyboard", true)
        put("one_time_keyboard", true)
    }

    fun keyboardBalanceReset() = buildJsonObject {
        put("keyboard", buildJsonArray {
            add(buildJsonArray {
                addJsonObject {
                    put("text", BTN_RESET_PERIOD)
                }
            })
            add(buildJsonArray { addJsonObject { put("text", BTN_CANCEL) } })
        })
        put("resize_keyboard", true)
        put("one_time_keyboard", true)
    }

    fun emptyInlineKeyboard() = buildJsonObject {
        put("inline_keyboard", buildJsonArray {})
    }

    fun keyboardConfirmClose() = buildJsonObject {
        put("keyboard", buildJsonArray {
            add(buildJsonArray { addJsonObject { put("text", BTN_CONFIRM) } })
            add(buildJsonArray { addJsonObject { put("text", BTN_CANCEL) } })
        })
        put("resize_keyboard", true)
    }

    fun keyboardSymbolInput() = buildJsonObject {
        put("keyboard", buildJsonArray {
            add(buildJsonArray { addJsonObject { put("text", BTN_CANCEL) } })
        })
        put("resize_keyboard", true)
        put("input_field_placeholder", "Введите пару, например BTCUSDT")
    }

    fun close() = client.close()

    companion object {
        internal fun ownerAllowed(owner: Long?, chat: TelegramChat?, sender: TelegramUser?): Boolean =
            owner != null && owner > 0 && chat?.id == owner && chat.type == "private" && sender?.id == owner

        internal fun redactToken(message: String, token: String): String =
            if (token.isBlank()) message else message.replace(token, "<redacted>")

        internal fun splitTelegramText(text: String, limit: Int = 3900): List<String> {
            if (text.length <= limit) return listOf(text)
            val chunks = mutableListOf<String>()
            var remaining = text
            while (remaining.length > limit) {
                val newline = remaining.lastIndexOf('\n', limit)
                val splitAt = if (newline > 0) newline else limit
                chunks += remaining.substring(0, splitAt).trimEnd()
                remaining = remaining.substring(splitAt).trimStart('\n')
            }
            if (remaining.isNotEmpty()) chunks += remaining
            return chunks
        }

        const val BTN_STATUS = "📊 Статус"
        const val BTN_STRATEGY = "🧠 Стратегия"
        const val BTN_PNL = "📈 P&L"
        const val BTN_BALANCE = "💰 Баланс"
        const val BTN_POSITIONS = "🧾 Позиции"
        const val BTN_SYMBOLS = "👀 Рабочие пары"
        const val BTN_BLACKLIST = "⛔ Чёрный список"
        const val BTN_REMOVE_SYMBOL = "➖ Убрать пару"
        const val BTN_RESTORE_SYMBOL = "➕ Вернуть пару"
        const val BTN_CLOSE_ALL = "🛑 Закрыть все позиции"
        const val BTN_CONFIRM = "✅ Подтвердить закрытие всех позиций"
        const val BTN_CANCEL = "❌ Отмена"
        const val BTN_APPLY_AI_STRATEGY = "✅ Принять"
        const val BTN_REJECT_AI_STRATEGY = "❌ Не принимать"
        const val BTN_HELP = "❓ Помощь"
        const val BTN_RESET_PERIOD = "🔄 Сбросить период"
        const val BTN_PNL_WEEK = "📅 P&L с начала недели"
        const val BTN_PNL_TODAY = "📈 P&L за сегодня"
        const val CALLBACK_RESET_PERIOD = "reset_performance_baseline"
        const val CALLBACK_PNL_WEEK = "pnl_week"
        const val CALLBACK_PNL_TODAY = "pnl_today"
    }
}

class TelegramCommandHandler(
    private val telegram: TelegramService,
    private val engine: SignalEngineRef,
) {
    private enum class SymbolAction { REMOVE, RESTORE }

    private val pendingCloseAll = mutableSetOf<Long>()
    private val pendingSymbolAction = mutableMapOf<Long, SymbolAction>()

    suspend fun handle(message: TelegramMessage) {
        if (!telegram.isAuthorized(message)) return
        val chatId = message.chat.id
        val text = message.text?.trim().orEmpty()
        if (text.startsWith("/")) {
            when {
                text.startsWith("/start") -> reply(
                    chatId,
                    "👋 КРИПТОБОТ НА СВЯЗИ\n━━━━━━━━━━━━━━━━━━\nВыберите нужный раздел на клавиатуре.",
                    telegram.keyboardMain(),
                )
                text.startsWith("/status") -> reply(chatId, engine.status())
                text.startsWith("/strategy") -> reply(chatId, engine.strategy())
                text.startsWith("/pnl") -> reply(chatId, engine.pnl(), telegram.keyboardPnlWeek())
                text.startsWith("/balance") -> reply(chatId, engine.balance(), telegram.keyboardBalanceReset())
                text.startsWith("/positions") -> reply(chatId, engine.positions())
                text.startsWith("/symbols") -> reply(chatId, engine.symbolsList())
                text.startsWith("/blacklist") -> reply(chatId, engine.blacklistCandidatesList())
                text.startsWith("/bl_approve") -> reply(chatId, engine.approveBlacklistCandidate(text.removePrefix("/bl_approve").trim()))
                text.startsWith("/bl_reject") -> reply(chatId, engine.rejectBlacklistCandidate(text.removePrefix("/bl_reject").trim()))
                text.startsWith("/add") -> reply(chatId, engine.addSymbol(text.removePrefix("/add").trim()))
                text.startsWith("/remove") -> reply(chatId, engine.removeSymbol(text.removePrefix("/remove").trim()))
                text.startsWith("/close_all_positions") -> askCloseAll(chatId)
                text.startsWith("/balance_reset") -> {
                    val amount = text.split(" ").getOrNull(1)?.toDoubleOrNull()
                    reply(chatId, engine.resetBalance(amount))
                }
                text.startsWith("/balance_set") -> {
                    val amount = text.split(" ").getOrNull(1)?.toDoubleOrNull()
                    reply(chatId, if (amount == null) "Укажите новый баланс, например:\n/balance_set 10000" else engine.setBalance(amount))
                }
                text.startsWith("/help") -> reply(chatId, helpText(), telegram.keyboardMain())
                else -> reply(chatId, "Не понял команду. Нажмите «❓ Помощь», чтобы увидеть доступные действия.", telegram.keyboardMain())
            }
            return
        }

        pendingSymbolAction[chatId]?.let { action ->
            if (text == TelegramService.BTN_CANCEL) {
                pendingSymbolAction.remove(chatId)
                reply(chatId, "✅ Изменение списка отменено.", telegram.keyboardMain())
                return
            }
            if (text !in MAIN_BUTTONS) {
                val symbol = text.uppercase()
                if (!symbol.matches(Regex("[A-Z0-9]{1,20}"))) {
                    reply(chatId, "⚠️ Введите только пару, например BTCUSDT.", telegram.keyboardSymbolInput())
                    return
                }
                pendingSymbolAction.remove(chatId)
                val result = when (action) {
                    SymbolAction.REMOVE -> engine.removeSymbol(symbol)
                    SymbolAction.RESTORE -> engine.addSymbol(symbol)
                }
                reply(chatId, result, telegram.keyboardMain())
                return
            }
            pendingSymbolAction.remove(chatId)
        }

        when (text) {
            TelegramService.BTN_STATUS -> reply(chatId, engine.status())
            TelegramService.BTN_STRATEGY -> reply(chatId, engine.strategy())
            TelegramService.BTN_PNL -> reply(chatId, engine.pnl(), telegram.keyboardPnlWeek())
            TelegramService.BTN_BALANCE -> reply(chatId, engine.balance(), telegram.keyboardBalanceReset())
            TelegramService.BTN_RESET_PERIOD -> {
                reply(chatId, engine.resetPerformanceBaseline(), telegram.keyboardMain())
            }
            TelegramService.BTN_POSITIONS -> reply(chatId, engine.positions())
            TelegramService.BTN_SYMBOLS -> reply(chatId, engine.symbolsList())
            TelegramService.BTN_BLACKLIST -> reply(chatId, engine.blacklistCandidatesList())
            TelegramService.BTN_REMOVE_SYMBOL -> {
                pendingSymbolAction[chatId] = SymbolAction.REMOVE
                reply(chatId, "➖ Введите пару, которую нужно убрать из работы (например BTCUSDT):", telegram.keyboardSymbolInput())
            }
            TelegramService.BTN_RESTORE_SYMBOL -> {
                pendingSymbolAction[chatId] = SymbolAction.RESTORE
                reply(chatId, "➕ Введите пару, которую нужно вернуть в работу (например ETHUSDT):", telegram.keyboardSymbolInput())
            }
            TelegramService.BTN_CLOSE_ALL -> askCloseAll(chatId)
            TelegramService.BTN_APPLY_AI_STRATEGY ->
                reply(chatId, engine.applyAiStrategy(), telegram.keyboardMain())
            TelegramService.BTN_REJECT_AI_STRATEGY ->
                reply(chatId, engine.rejectAiStrategy(), telegram.keyboardMain())
            TelegramService.BTN_CONFIRM -> {
                if (chatId !in pendingCloseAll) {
                    reply(chatId, "Подтверждение уже неактивно. Позиции не изменены.", telegram.keyboardMain()); return
                }
                pendingCloseAll.remove(chatId)
                engine.setCloseAllPause(false)
                reply(chatId, "⏳ Закрываю все позиции. Это может занять несколько секунд…", telegram.keyboardMain())
                reply(chatId, engine.closeAll(), telegram.keyboardMain())
            }
            TelegramService.BTN_CANCEL -> {
                pendingCloseAll.remove(chatId)
                engine.setCloseAllPause(false)
                reply(chatId, "✅ Отменено. Позиции оставлены без изменений, новые входы снова разрешены.", telegram.keyboardMain())
            }
            TelegramService.BTN_HELP -> reply(chatId, helpText(), telegram.keyboardMain())
            else -> reply(chatId, "Выберите действие с помощью кнопок ниже.", telegram.keyboardMain())
        }
    }

    suspend fun handleCallback(query: TelegramCallbackQuery) {
        if (!telegram.isAuthorized(query)) return
        val message = query.message
        if (message == null) {
            telegram.answerCallbackQuery(query.id)
            return
        }
        when (query.data) {
            TelegramService.CALLBACK_RESET_PERIOD -> {
                telegram.answerCallbackQuery(query.id, "Начинаю новый период отсчёта")
                val result = engine.resetPerformanceBaseline()
                telegram.editMessage(
                    message.chat.id, message.messageId, "$result\n\n${engine.balance()}",
                    telegram.emptyInlineKeyboard(),
                )
            }
            TelegramService.CALLBACK_PNL_WEEK -> {
                telegram.answerCallbackQuery(query.id, "Показываю результат с понедельника")
                val weekly = engine.pnlWeek()
                val edited = telegram.editMessage(
                    message.chat.id, message.messageId, weekly, telegram.keyboardPnlToday(),
                )
                if (!edited) reply(message.chat.id, weekly, telegram.keyboardPnlToday())
            }
            TelegramService.CALLBACK_PNL_TODAY -> {
                telegram.answerCallbackQuery(query.id, "Показываю результат за сегодня")
                val today = engine.pnl()
                val edited = telegram.editMessage(
                    message.chat.id, message.messageId, today, telegram.keyboardPnlWeek(),
                )
                if (!edited) reply(message.chat.id, today, telegram.keyboardPnlWeek())
            }
            else -> telegram.answerCallbackQuery(query.id)
        }
    }

    private suspend fun askCloseAll(chatId: Long) {
        val (allowed, msg) = engine.canCloseAll()
        if (!allowed) { reply(chatId, msg, telegram.keyboardMain()); return }
        pendingCloseAll.add(chatId)
        engine.setCloseAllPause(true)
        reply(
            chatId,
            "⚠️ ПОДТВЕРДИТЕ ДЕЙСТВИЕ\n━━━━━━━━━━━━━━━━━━\n$msg\n\nДо вашего ответа новые позиции открываться не будут.",
            telegram.keyboardConfirmClose(),
        )
    }

    private suspend fun reply(chatId: Long, text: String, markup: JsonObject? = null) {
        telegram.sendMessage(chatId, text, markup ?: telegram.keyboardMain())
    }

    private fun helpText() = """
        ❓ ЧТО УМЕЕТ БОТ
        ━━━━━━━━━━━━━━━━━━
        📊 Статус — работа бота и состояние портфеля
        🧠 Стратегия — правила входа и ограничения риска
        📈 P&L — прибыль и убыток с начала торговой сессии
        💰 Баланс — деньги и стоимость открытых позиций
        🧾 Позиции — подробности по каждой паре
        👀 Рабочие пары — полный активный список
        ⛔ Чёрный список — кандидаты и уже исключённые пары
        ➖ Убрать пару — сразу в чёрный список
        ➕ Вернуть пару — снять исключение / отклонение кандидата
        ✅ Принять — применить предложенные ИИ изменения
        ❌ Не принимать — оставить текущую стратегию без изменений
        
        Управление списком:
        /symbols
        /add BTCUSDT
        /remove ETHUSDT
        /blacklist
        /bl_approve SYMBOL
        /bl_reject SYMBOL
        
        Сервисные команды:
        /balance_reset [сумма]
        /close_all_positions
    """.trimIndent()

    companion object {
        private val MAIN_BUTTONS = setOf(
            TelegramService.BTN_STATUS,
            TelegramService.BTN_STRATEGY,
            TelegramService.BTN_PNL,
            TelegramService.BTN_BALANCE,
            TelegramService.BTN_POSITIONS,
            TelegramService.BTN_SYMBOLS,
            TelegramService.BTN_BLACKLIST,
            TelegramService.BTN_REMOVE_SYMBOL,
            TelegramService.BTN_RESTORE_SYMBOL,
            TelegramService.BTN_CLOSE_ALL,
            TelegramService.BTN_APPLY_AI_STRATEGY,
            TelegramService.BTN_REJECT_AI_STRATEGY,
            TelegramService.BTN_RESET_PERIOD,
            TelegramService.BTN_HELP,
        )
    }
}

interface SignalEngineRef {
    fun status(): String
    fun strategy(): String
    fun pnl(): String
    fun pnlForDay(day: java.time.LocalDate): String
    fun pnlWeek(): String
    fun balance(): String
    fun positions(): String
    fun symbolsList(): String
    fun addSymbol(symbol: String): String
    fun removeSymbol(symbol: String): String
    fun resetBalance(amount: Double?): String
    fun setBalance(amount: Double): String
    fun resetPerformanceBaseline(): String
    fun canCloseAll(): Pair<Boolean, String>
    fun closeAll(): String
    fun setCloseAllPause(active: Boolean)
    fun applyAiStrategy(): String
    fun rejectAiStrategy(): String
    fun blacklistCandidatesList(): String
    fun approveBlacklistCandidate(symbol: String): String
    fun rejectBlacklistCandidate(symbol: String): String
}
