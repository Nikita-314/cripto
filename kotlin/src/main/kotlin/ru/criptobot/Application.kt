package ru.criptobot

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.criptobot.api.OpenAiService
import ru.criptobot.config.Settings
import ru.criptobot.data.DataLoader
import ru.criptobot.engine.SignalEngine
import ru.criptobot.telegram.TelegramCommandHandler
import ru.criptobot.telegram.TelegramService
import ru.criptobot.universe.BinanceUniverseProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.exists

private val log = LoggerFactory.getLogger("ru.criptobot.Application")

fun main() = runBlocking {
    val projectRoot = resolveProjectRoot()
    val settings = Settings.load(projectRoot)
    log.info("Starting criptobot Kotlin from {}", projectRoot)
    val application = Application(settings)
    try {
        application.start()
    } finally {
        application.close()
    }
}

fun resolveProjectRoot(): Path {
    val cwd = Path.of(".").toAbsolutePath().normalize()
    return when {
        cwd.resolve(".env").exists() -> cwd
        cwd.parent?.resolve(".env")?.exists() == true -> cwd.parent!!
        cwd.fileName.toString() == "kotlin" && cwd.parent?.resolve(".env")?.exists() == true -> cwd.parent!!
        else -> cwd
    }
}

class Application(private val settings: Settings) : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private val dataLoader = DataLoader(settings.binanceApiBaseUrl)
    private val universe = BinanceUniverseProvider(settings)
    private val openAi = OpenAiService(settings)
    private val telegram = TelegramService(settings)
    private lateinit var engine: SignalEngine
    private val dailyPnlStateFile = settings.projectRoot.resolve("daily_pnl_report_state.txt")

    suspend fun start() {
        require(settings.tradingMode in setOf("paper", "binance")) {
            "TRADING_MODE must be paper or binance"
        }
        require(settings.tradingMode == "paper") {
            "Live Binance trading is not enabled yet. Set TRADING_MODE=paper for the virtual USDT balance."
        }
        engine = SignalEngine(
            settings = settings,
            dataLoader = dataLoader,
            universeProvider = universe,
            openAi = openAi,
            telegramSend = { msg ->
                settings.telegramChatId?.let { telegram.sendMessage(it, msg, telegram.keyboardMain()) }
            },
            telegramStrategyProposal = { msg ->
                settings.telegramChatId?.let {
                    telegram.sendMessage(it, msg, telegram.keyboardAiStrategyProposal())
                }
            },
            telegramEdit = { msgId, msg ->
                settings.telegramChatId?.let { telegram.editMessage(it, msgId, msg) }
            },
        )
        engine.initialize()

        launch(Dispatchers.IO) {
            log.info("Independent position monitor started interval={}ms", settings.positionMonitorIntervalMs)
            engine.runPositionMonitor()
        }

        launch(Dispatchers.IO) {
            engine.runShadowShortPaperTrading()
        }

        if (telegram.enabled()) {
            val handler = TelegramCommandHandler(telegram, engine)
            launch(Dispatchers.Default) {
                log.info("Telegram polling started")
                telegram.pollUpdates(
                    onMessage = { msg -> handler.handle(msg) },
                    onCallback = { query -> handler.handleCallback(query) },
                )
            }
            settings.telegramChatId?.let { chatId ->
                launch(Dispatchers.IO) { runDailyPnlReporter(chatId) }
            }
        } else {
            log.warn("Telegram disabled: TELEGRAM_BOT_TOKEN empty")
        }

        settings.telegramChatId?.let { chatId ->
            if (telegram.notifyEnabled()) {
                telegram.sendMessage(chatId,
                    """
                    ✅ КРИПТОБОТ ЗАПУЩЕН
                    ━━━━━━━━━━━━━━━━━━
                    ⚙️ Режим: 🧪 тестовый баланс USDT (без реальных денег)
                    🏦 Биржа: Binance Spot
                    🧠 Стратегия: Supertrend 1h pullback + 4h, лонг и шорт
                    🕒 Таймфрейм: ${settings.interval}
                    👀 Под наблюдением: ${engine.symbols.size} пар
                    
                    🔍 Бот анализирует рынок.
                    🔔 О входах, выходах и важных изменениях сообщу отдельно.
                    """.trimIndent(),
                    telegram.keyboardMain(),
                )
            }
        }

        log.info("Bot started mode={} interval={} symbols={}", settings.tradingMode, settings.interval, engine.symbols.size)
        while (coroutineContext.isActive) {
            try {
                engine.runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Trading cycle failed; next cycle will retry: {}", e.message, e)
            }
            delay(settings.pollSeconds * 1000L)
        }
    }

    private suspend fun runDailyPnlReporter(chatId: Long) {
        val zone = ZoneId.of("Europe/Moscow")
        val reportTime = LocalTime.of(23, 55)
        var lastReportedDay = readLastReportedDay()
        if (lastReportedDay == null) {
            val now = ZonedDateTime.now(zone)
            lastReportedDay = completedTradingDay(now, reportTime)
            saveLastReportedDay(lastReportedDay)
            log.info("Daily P&L reporter initialized; next report at {} {}", reportTime, zone.id)
        }

        while (coroutineContext.isActive) {
            val now = ZonedDateTime.now(zone)
            val completedDay = completedTradingDay(now, reportTime)
            if (completedDay.isAfter(lastReportedDay)) {
                val text = buildString {
                    appendLine("📅 ИТОГ ТОРГОВОГО ДНЯ")
                    appendLine()
                    append(engine.pnlForDay(completedDay))
                }
                val messageId = telegram.sendMessage(chatId, text, telegram.keyboardPnlWeek())
                if (messageId != null) {
                    saveLastReportedDay(completedDay)
                    lastReportedDay = completedDay
                    log.info("Daily P&L report sent for {} at {}", completedDay, now)
                } else {
                    log.warn("Daily P&L report for {} was not delivered; will retry", completedDay)
                }
            }
            delay(60_000L)
        }
    }

    private fun completedTradingDay(now: ZonedDateTime, reportTime: LocalTime): LocalDate =
        if (now.toLocalTime().isBefore(reportTime)) now.toLocalDate().minusDays(1) else now.toLocalDate()

    private fun readLastReportedDay(): LocalDate? = runCatching {
        if (!Files.exists(dailyPnlStateFile)) null
        else Files.readString(dailyPnlStateFile).trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    }.onFailure { log.warn("Cannot read daily P&L state: {}", it.message) }.getOrNull()

    private fun saveLastReportedDay(day: LocalDate) {
        runCatching { Files.writeString(dailyPnlStateFile, day.toString()) }
            .onFailure { log.warn("Cannot save daily P&L state: {}", it.message) }
    }

    fun close() {
        if (::engine.isInitialized) engine.close()
        dataLoader.close()
        universe.close()
        openAi.close()
        telegram.close()
    }
}
