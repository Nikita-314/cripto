package ru.criptobot.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.criptobot.adaptive.AdaptiveAnalysisEngine
import ru.criptobot.adaptive.AdaptiveStateStore
import ru.criptobot.adaptive.ShadowShortObservation
import ru.criptobot.adaptive.ShadowShortPaperSimulator
import ru.criptobot.analytics.*
import ru.criptobot.api.OpenAiService
import ru.criptobot.broker.Broker
import ru.criptobot.broker.BinanceClient
import ru.criptobot.broker.PaperBroker
import ru.criptobot.config.Settings
import ru.criptobot.data.DataLoader
import ru.criptobot.data.IndicatorSnapshot
import ru.criptobot.data.TradingViewClient
import ru.criptobot.features.FeatureEngine
import ru.criptobot.ml.MlSignalModel
import ru.criptobot.position.ManagedPositionState
import ru.criptobot.position.PositionManagement
import ru.criptobot.telegram.SignalEngineRef
import ru.criptobot.universe.BinanceUniverseProvider
import ru.criptobot.universe.BlacklistCandidateStore
import ru.criptobot.universe.StockRiskFilter
import ru.criptobot.universe.UniverseOverridesStore
import ru.criptobot.util.*
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val PNL_REPORT_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

class SignalEngine(
    private val settings: Settings,
    private val dataLoader: DataLoader,
    private val universeProvider: BinanceUniverseProvider,
    private val openAi: OpenAiService,
    private val telegramSend: suspend (String) -> Long?,
    private val telegramStrategyProposal: suspend (String) -> Long?,
    private val telegramEdit: suspend (Long, String) -> Unit,
) : SignalEngineRef {
    private val log = LoggerFactory.getLogger(SignalEngine::class.java)
    private val broker: Broker = if (settings.tradingMode == "paper") PaperBroker(settings) else BinanceClient(settings)
    private val paperBroker get() = broker as? PaperBroker
    private val mlModel = MlSignalModel()
    private val tv = TradingViewClient(settings, dataLoader)
    private val scorer = IndicatorScorer()
    private val riskFilter = StockRiskFilter()
    private val universeOverrides = UniverseOverridesStore(settings.universeOverridesFile)
    private val adaptiveStore = AdaptiveStateStore(settings.adaptiveRuntimeStateFile)
    private val analyticsRepo = if (settings.analyticsEnabled) AnalyticsRepository(settings) else null
    private val blacklistCandidates = BlacklistCandidateStore(settings.blacklistCandidatesFile)
    private val blacklistProposer = analyticsRepo?.let { BlacklistCandidateProposer(settings, it, blacklistCandidates) }
    private val analyticsLogger = analyticsRepo?.logger()
    private val dbMaintenance = if (settings.analyticsEnabled) DatabaseMaintenance(settings) else null
    private val outcomeEvaluator = analyticsRepo?.let { OutcomeEvaluator(it) }
    private val paperMapper = analyticsLogger?.let { PaperTradeMapper(it) }
    private val adaptiveEngine = if (analyticsRepo != null && analyticsLogger != null)
        AdaptiveAnalysisEngine(settings, analyticsRepo, analyticsLogger) else null
    private var strategy = StrategyConfig.load(settings.strategyStateFile) ?: StrategyConfig.defaults(settings)
    private var lastRebalanceTradeCount = 0
    @Volatile private var pendingAiStrategy: StrategyConfig? = null
    @Volatile private var pendingAiStrategyTradeCount: Int = 0

    private val operatorLock = Mutex()
    private val paperLock = Mutex()
    private val closeAllLock = Mutex()
    private val manualCloseAllPending = AtomicBoolean(false)

    var cycle = 0; private set
    var symbols = emptyList<String>(); private set
    private var eligibleSymbols = emptyList<String>()
    private var lotSizes = emptyMap<String, Int>()
    private val lastPrices = mutableMapOf<String, Double>()
    private var lastPerformance: Map<String, Any> = broker.getBalanceSnapshot(emptyMap())
    private val tradeMeta = mutableMapOf<String, MutableMap<String, Any?>>()
    private val lastBuyBarKey = mutableMapOf<String, String>()
    private val lastEntryAttemptBarKey = mutableMapOf<String, String>()
    private val entryAttemptStateFile = settings.projectRoot.resolve("entry_attempt_state.tsv")
    private val lastCloseBarKey = mutableMapOf<String, String>()
    private val pendingBuy = mutableMapOf<String, MutableMap<String, Any?>>()
    private val pendingShort = mutableMapOf<String, MutableMap<String, Any?>>()
    private val lastDecisionMeta = mutableMapOf<String, Map<String, Any?>>()
    private val lastEvaluatedCandle = mutableMapOf<String, Instant>()
    private val pmStates = mutableMapOf<String, ManagedPositionState>()
    private val pmProfiles = mutableMapOf<String, ru.criptobot.position.InstrumentProfile>()
    private val marketSample = mutableListOf<Map<String, Any>>()
    @Volatile private var previousMarketBreadth = MarketBreadthDecision(false, 0, 0.5)
    @Volatile private var fourHourUpShare = 0.0
    @Volatile private var consecutiveBullishFourHourCycles = 0
    private val effectiveStrategyVersion = "${settings.analyticsStrategyVersion}+${RegimeAdaptiveStrategy.VERSION}"
    private val selectiveCloseRequestFile = settings.projectRoot.resolve("selective_close_request.tsv")
    private val trailingPositions = TrailingPositionStore(settings.trailingStateFile)
    private val reentryCooldowns = ReentryCooldownStore(settings.reentryCooldownStateFile)
    private val exitSignalConfirmation = ExitSignalConfirmation()
    private val exitFailureCount = ConcurrentHashMap<String, Int>()
    private val nextExitAttemptAt = ConcurrentHashMap<String, Instant>()
    private val lastExitFailureNoticeAt = ConcurrentHashMap<String, Instant>()
    private val marketClosedDeferredExits = ConcurrentHashMap.newKeySet<String>()
    private val shadowShortStateFile = settings.projectRoot.resolve("shadow_short_paper_state.json")
    private val shadowShortSimulator = ShadowShortPaperSimulator(
        stateFile = shadowShortStateFile,
        initialBalanceRub = 100_000.0,
        positionSizeRub = settings.positionSizeRub,
        commissionRate = settings.paperCommissionRate,
        stopLossPct = strategy.stopLossPct,
        takeProfitPct = strategy.takeProfitPct,
        trailingCallbackPct = settings.trailingTpCallbackPct,
    )
    private val shadowShortObservations = Channel<ShadowShortObservation>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var currentRunId: String? = null
    private var currentSignalsFound = 0
    private var currentInstrumentsChecked = 0
    private var runStatus = "OK"
    private var runComment = ""
    private val currentReasonCounts = mutableMapOf<String, Int>()

    suspend fun initialize() {
        loadEntryAttempts()
        val recoveredRuns = analyticsLogger?.recoverInterruptedRuns() ?: 0
        if (recoveredRuns > 0) log.warn("Marked {} unfinished analytics runs as INTERRUPTED", recoveredRuns)
        refreshUniverse()
        applyPaperCloseAllRequest()
        applyPaperResetRequest()
    }

    suspend fun runShadowShortPaperTrading() {
        log.info(
            "Shadow SHORT paper trader started: isolated_balance=100000 state={} broker_orders=false",
            shadowShortStateFile,
        )
        for (observation in shadowShortObservations) {
            try {
                val allowEntry = if (
                    observation.shortCandidate && !shadowShortSimulator.hasPosition(observation.symbol)
                ) {
                    multiTimeframeEntryAllowed(
                        observation.symbol,
                        "SHORT",
                        observation.confidence,
                    ).allowed
                } else false
                shadowShortSimulator.observe(observation, allowEntry)?.let { event ->
                    val state = shadowShortSimulator.snapshot()
                    log.info(
                        "Shadow SHORT paper {} [{}]: price={} positions={} closed={} realized={}",
                        event, observation.symbol, observation.price, state.positions.size,
                        state.closedTrades.size, state.realizedPnlRub,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Shadow SHORT paper observation failed [{}]: {}", observation.symbol, e.message)
            }
        }
    }

    private suspend fun applyPaperCloseAllRequest() {
        if (settings.tradingMode != "paper") return
        val request = settings.projectRoot.resolve("paper_close_all_request.txt")
        if (!Files.exists(request)) return
        Files.deleteIfExists(request)
        val result = flattenAllPaperPositions("Смена стратегии: закрытие всех позиций")
        telegramSend("🛑 Закрыты все позиции перед long-only стратегией\n$result")
        log.info("Paper close-all request completed: {}", result)
    }

    private suspend fun applyPaperResetRequest() {
        if (settings.tradingMode != "paper") return
        val request = settings.projectRoot.resolve("paper_reset_request.txt")
        if (!Files.exists(request)) return
        val amount = runCatching { Files.readString(request).trim().toDouble() }.getOrElse {
            log.error("Invalid paper reset request: {}", it.message)
            return
        }
        require(amount > 0) { "Paper reset amount must be positive" }
        val snap = resetPaperAccount(amount)
        Files.deleteIfExists(request)
        telegramSend(formatResetMessage(snap))
        log.info("Paper account reset request completed: {} USDT", amount)
    }

    suspend fun refreshUniverse() {
        if (settings.universeSource == "seed") {
            eligibleSymbols = settings.seedSymbols.distinct()
            lotSizes = eligibleSymbols.associateWith { 1 }
            symbols = applyOverrides(eligibleSymbols)
            log.info("Active universe source=seed size={} symbols={}", symbols.size, symbols.joinToString(","))
            return
        }

        val instruments = riskFilter.deterministicFilter(universeProvider.fetch())
        val seedSet = settings.seedSymbols.toSet()
        val prioritized = instruments
            .sortedWith(
                compareByDescending<ru.criptobot.data.StockInstrument> { it.symbol in seedSet }
                    .thenByDescending { it.quoteVolume24h }
            )
        val (filtered, blocked) = openAi.aiFilterInstruments(prioritized)
        if (blocked.isNotEmpty()) log.info("AI risk filter blocked {} symbols", blocked.size)
        val base = filtered.map { it.symbol }.distinct().ifEmpty { settings.seedSymbols }
        eligibleSymbols = base
        lotSizes = filtered.associate { it.symbol to it.lotSize.coerceAtLeast(1) }
        symbols = applyOverrides(base)
        log.info(
            "Active universe source={} size={} symbols={}",
            settings.universeSource,
            symbols.size,
            symbols.joinToString(","),
        )
    }

    private fun applyOverrides(base: List<String>): List<String> {
        val excluded = universeOverrides.excluded
        val included = universeOverrides.included
        val seedSet = settings.seedSymbols.toSet()
        return (included.filter { it in eligibleSymbols } + base.filter { it !in excluded })
            .distinct()
            .sortedWith(compareByDescending<String> { it in included || it in seedSet }.thenBy { it })
    }

    suspend fun runOnce() {
        operatorLock.withLock {
            if (manualCloseAllPending.get()) return
            cycle++; currentSignalsFound = 0; currentInstrumentsChecked = 0
            runStatus = "OK"; runComment = ""; marketSample.clear()
            currentReasonCounts.clear()
            currentRunId = analyticsLogger?.startRun(
                settings.strategyMode,
                effectiveStrategyVersion,
                symbols.size,
                "cycle=$cycle; breadth_falling_share=${previousMarketBreadth.fallingShare}; breadth_sample=${previousMarketBreadth.sampleSize}",
            )
            try {
            if (cycle % settings.universeRefreshCycles == 0) refreshUniverse()
            adaptiveEngine?.refresh(cycle)
            refreshFourHourMarketRegime()
            val latestPrices = mutableMapOf<String, Double>()
            for (symbol in symbols) {
                if (manualCloseAllPending.get()) break
                try { processSymbol(symbol, latestPrices) }
                catch (e: Exception) {
                    log.warn("Failed for {}: {}", symbol, e.message)
                    runStatus = "ERROR"
                    if (runComment.length < 2_000) {
                        runComment = listOf(runComment, "$symbol:${e.message}").filter { it.isNotBlank() }.joinToString("; ")
                    }
                }
                kotlinx.coroutines.delay((settings.tvRequestDelaySec * 1000).toLong())
            }
            previousMarketBreadth = MarketBreadthGuard.evaluate(marketSample)

            paperLock.withLock {
                val merged = lastPrices.toMutableMap().apply { putAll(latestPrices) }
                lastPerformance = broker.performance(merged)
                log.info("[{}] {}", settings.tradingMode.uppercase(), lastPerformance)
            }

            if (pendingAiStrategy == null) {
                val (proposed, msg) = openAi.maybeUpdateStrategy(
                    cycle, strategy, lastPerformance, marketSample, lastRebalanceTradeCount,
                )
                if (msg != null) {
                    pendingAiStrategy = proposed
                    pendingAiStrategyTradeCount = safeFloat(lastPerformance["trade_count"]).toInt()
                    telegramStrategyProposal(msg)
                }
            }

            if (settings.analyticsOutcomeEvalEnabled && outcomeEvaluator != null) {
                val n = outcomeEvaluator.evaluatePending(loadCandles = { symbol ->
                    dataLoader.loadCandles(symbol, settings.exchange, settings.interval)
                })
                if (n > 0) log.info("Analytics outcomes evaluated: {}", n)
            }
            if (cycle % settings.blacklistRefreshCycles == 0) {
                refreshBlacklistCandidates(notify = settings.blacklistNotifyTelegram)
            }
            } catch (e: CancellationException) {
                runStatus = "INTERRUPTED"
                runComment = listOf(runComment, "coroutine_cancelled").filter { it.isNotBlank() }.joinToString("; ")
                throw e
            } catch (e: Exception) {
                runStatus = "ERROR"
                runComment = listOf(runComment, e.message ?: e::class.simpleName.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("; ")
                throw e
            } finally {
                val reasonSummary = currentReasonCounts.entries
                    .sortedByDescending { it.value }
                    .joinToString(",") { "${it.key}:${it.value}" }
                val analyticsComment = buildString {
                    append("cycle=$cycle")
                    if (reasonSummary.isNotBlank()) append("; reasons={$reasonSummary}")
                    if (runComment.isNotBlank()) append("; errors={$runComment}")
                }
                currentRunId?.let {
                    analyticsLogger?.finishRun(it, currentInstrumentsChecked, currentSignalsFound, runStatus, analyticsComment)
                }
            }
            if (settings.telegramNotifyHold && currentSignalsFound == 0) {
                telegramSend(noSignalSummary())
            }
            dbMaintenance?.runIfNeeded(cycle)
        }
    }

    private suspend fun refreshFourHourMarketRegime() = coroutineScope {
        val semaphore = Semaphore(6)
        val directions = symbols.take(30).map { symbol ->
            async {
                semaphore.withPermit {
                    dataLoader.loadCandles(symbol, settings.exchange, "4h")
                        ?.let(FeatureEngine::buildFeatures)?.lastOrNull()?.stDir
                }
            }
        }.awaitAll().filterNotNull()
        if (directions.size >= 10) {
            fourHourUpShare = directions.count { it > 0.0 }.toDouble() / directions.size
            consecutiveBullishFourHourCycles = if (fourHourUpShare >= 0.60) {
                consecutiveBullishFourHourCycles + 1
            } else {
                0
            }
            log.info(
                "MTF 4h market regime: up_share={} sample={} bullish_streak={}",
                fourHourUpShare,
                directions.size,
                consecutiveBullishFourHourCycles,
            )
        }
    }

    private suspend fun multiTimeframeEntryAllowed(symbol: String, side: String, confidence: Double): MultiTimeframeDecision = coroutineScope {
        val fourHour = async { dataLoader.loadCandles(symbol, settings.exchange, "4h")?.let(FeatureEngine::buildFeatures)?.lastOrNull() }
        val tenMinute = async { dataLoader.loadCandles(symbol, settings.exchange, "10m")?.let(FeatureEngine::buildFeatures)?.lastOrNull() }
        MultiTimeframeEntryFilter.evaluate(
            side,
            confidence,
            fourHour.await(),
            tenMinute.await(),
            fourHourUpShare,
            bullishRegimeConfirmed = side.uppercase() != "BUY" || consecutiveBullishFourHourCycles >= 2,
        )
    }

    suspend fun runPositionMonitor() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                monitorPositionsOnce()
                consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                log.error("Independent position monitor failed; retrying: {}", e.message, e)
            }
            val retryDelayMs = if (consecutiveFailures == 0) {
                settings.positionMonitorIntervalMs
            } else {
                val multiplier = 1L shl (consecutiveFailures - 1).coerceAtMost(4)
                (settings.positionMonitorIntervalMs * multiplier).coerceAtMost(10_000L)
            }
            delay(retryDelayMs)
        }
    }

    internal suspend fun monitorPositionsOnce() {
        val openPositions = paperLock.withLock { broker.getOpenPositions(lastPrices) }
        val openSymbols = openPositions.map { it["symbol"].toString().uppercase() }.toSet()
        trailingPositions.symbols().filter { it !in openSymbols }.forEach(trailingPositions::remove)
        if (openSymbols.isEmpty()) return
        val prices = dataLoader.loadBinanceLastPrices(openSymbols)
        applySelectiveCloseRequest(prices)
        openPositions.forEach { row ->
            val symbol = row["symbol"].toString().uppercase()
            val price = prices[symbol] ?: return@forEach
            paperLock.withLock {
                val (qty, avg) = broker.getPosition(symbol)
                if (qty == 0.0 || avg <= 0) return@withLock
                lastPrices[symbol] = price
                var trailingState = trailingPositions.state(symbol)
                if (trailingState == null && settings.trailingTpEnabled) {
                    val isShort = qty < 0
                    val sl = if (isShort) avg * (1 + strategy.stopLossPct) else avg * (1 - strategy.stopLossPct)
                    val tp = if (isShort) avg * (1 - strategy.takeProfitPct) else avg * (1 + strategy.takeProfitPct)
                    if (trailingPositions.registerIfAbsent(
                            symbol = symbol,
                            direction = if (isShort) "SHORT" else "LONG",
                            entryPrice = avg,
                            stopLossPrice = sl,
                            activationPrice = tp,
                            callbackPct = settings.trailingTpCallbackPct,
                        )
                    ) {
                        log.warn(
                            "Recovered missing trailing protection [{}]: direction={} entry={} stop={} activation={}",
                            symbol, if (isShort) "SHORT" else "LONG", avg, sl, tp,
                        )
                    }
                    trailingState = trailingPositions.state(symbol)
                }
                val activeTrailingState = trailingState
                val evaluation = if (activeTrailingState != null) {
                    val directionMatches = if (activeTrailingState.direction == "SHORT") qty < 0 else qty > 0
                    if (!directionMatches) {
                        trailingPositions.remove(symbol)
                        return@withLock
                    }
                    trailingPositions.evaluate(symbol, price)?.also {
                        if (it.activatedNow) {
                            log.info(
                                "Trailing TP activated [{}]: price={} activation={} callback={}%",
                                symbol, price, activeTrailingState.activationPrice, activeTrailingState.callbackPct * 100,
                            )
                        }
                    }
                } else {
                    val isShort = qty < 0
                    val sl = if (isShort) avg * (1 + strategy.stopLossPct) else avg * (1 - strategy.stopLossPct)
                    val tp = if (isShort) avg * (1 - strategy.takeProfitPct) else avg * (1 + strategy.takeProfitPct)
                    val stopTriggered = if (isShort) price >= sl else price <= sl
                    val takeTriggered = if (isShort) price <= tp else price >= tp
                    when {
                        stopTriggered -> TrailingEvaluation("Stop-loss", sl)
                        takeTriggered -> TrailingEvaluation("Take-profit", tp)
                        else -> TrailingEvaluation()
                    }
                }
                val reason = evaluation?.exitReason
                if (reason == null) {
                    if (marketClosedDeferredExits.remove(symbol)) {
                        nextExitAttemptAt.remove(symbol)
                        exitFailureCount.remove(symbol)
                        lastExitFailureNoticeAt.remove(symbol)
                        log.info(
                            "Deferred protective exit cancelled [{}]: current price={} no longer triggers protection",
                            symbol,
                            price,
                        )
                    }
                    return@withLock
                }
                attemptProtectedExit(symbol, price, reason, evaluation.triggerPrice, qty, avg)
            }
        }
    }

    private suspend fun applySelectiveCloseRequest(prices: Map<String, Double>) {
        if (!Files.exists(selectiveCloseRequestFile)) return
        val requested = Files.readAllLines(selectiveCloseRequestFile).mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            parts.firstOrNull()?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.let { symbol ->
                symbol to parts.getOrNull(1).orEmpty().ifBlank { "Пересмотр прибыльной модели" }
            }
        }
        if (requested.isEmpty()) {
            Files.deleteIfExists(selectiveCloseRequestFile)
            return
        }
        val remaining = mutableListOf<Pair<String, String>>()
        for ((symbol, reason) in requested) {
            val price = prices[symbol]
            if (price == null) {
                remaining += symbol to reason
                continue
            }
            paperLock.withLock {
                val (qty, avg) = broker.getPosition(symbol)
                if (qty == 0.0) return@withLock
                if (!broker.isTradingOpen(symbol)) {
                    remaining += symbol to reason
                    return@withLock
                }
                val side = if (qty < 0) "CLOSE_SHORT" else "SELL"
                val execution = broker.placeOrder(symbol, side, kotlin.math.abs(qty) * price, price)
                val afterQty = broker.getPosition(symbol).first
                val filledQty = kotlin.math.abs(qty - afterQty)
                if (filledQty > 0.0) {
                    if (afterQty == 0.0) {
                        closeTrade(symbol, executionPrice(execution, price), reason, qty, avg, execution)
                        lastCloseBarKey[symbol] = barKeyAt(Instant.now(), settings.interval)
                    } else if (execution != null) {
                        notifyProtectedPartialClose(
                            symbol,
                            executionPrice(execution, price),
                            reason,
                            filledQty,
                            kotlin.math.abs(afterQty),
                            execution,
                        )
                    } else {
                        log.warn(
                            "Selective close [{}] changed position from {} to {} without execution details; keeping request active",
                            symbol,
                            qty,
                            afterQty,
                        )
                    }
                }
                if (afterQty != 0.0) remaining += symbol to reason
            }
        }
        if (remaining.isEmpty()) Files.deleteIfExists(selectiveCloseRequestFile)
        else Files.write(selectiveCloseRequestFile, remaining.map { "${it.first}|${it.second}" })
    }

    private suspend fun attemptProtectedExit(
        symbol: String,
        price: Double,
        reason: String,
        triggerPrice: Double?,
        qty: Double,
        avg: Double,
    ): Boolean {
        val now = Instant.now()
        if (now.isBefore(nextExitAttemptAt[symbol] ?: Instant.EPOCH)) return false
        if (!broker.isTradingOpen(symbol)) {
            marketClosedDeferredExits.add(symbol)
            nextExitAttemptAt[symbol] = now.plusSeconds(60)
            log.info(
                "Protective exit deferred [{}]: market session is closed; position remains protected and will be retried",
                symbol,
            )
            return false
        }
        marketClosedDeferredExits.remove(symbol)
        val side = if (qty < 0) "CLOSE_SHORT" else "SELL"
        log.info(
            "Independent risk exit [{}]: reason={} currentPrice={} trigger={}",
            symbol, reason, price, triggerPrice,
        )
        val execution = broker.placeOrder(symbol, side, kotlin.math.abs(qty) * price, price)
        val remainingQty = broker.getPosition(symbol).first
        if (remainingQty == 0.0) {
            exitFailureCount.remove(symbol)
            nextExitAttemptAt.remove(symbol)
            lastExitFailureNoticeAt.remove(symbol)
            closeTrade(symbol, executionPrice(execution, price), reason, qty, avg, execution)
            lastCloseBarKey[symbol] = barKeyAt(now, settings.interval)
            return true
        }
        if (execution != null) {
            exitFailureCount.remove(symbol)
            nextExitAttemptAt.remove(symbol)
            lastExitFailureNoticeAt.remove(symbol)
            val filledQty = execution.qty.coerceAtMost(kotlin.math.abs(qty))
            notifyProtectedPartialClose(
                symbol = symbol,
                exitPrice = executionPrice(execution, price),
                reason = reason,
                filledQty = filledQty,
                remainingQty = kotlin.math.abs(remainingQty),
                execution = execution,
            )
            log.info(
                "Protective exit partially filled [{}]: filled={} remaining={}",
                symbol, filledQty, kotlin.math.abs(remainingQty),
            )
            return true
        }

        val failures = exitFailureCount.merge(symbol, 1, Int::plus) ?: 1
        val retrySeconds = (5L shl (failures - 1).coerceAtMost(6)).coerceAtMost(300L)
        nextExitAttemptAt[symbol] = now.plusSeconds(retrySeconds)
        if (!broker.hasTwoSidedLiquidity(symbol)) {
            blacklistForLiquidity(symbol, "PROTECTIVE_EXIT_NO_BUYERS")
        }
        log.error(
            "Protective exit not filled [{}]; position remains open, retry in {}s (attempt {})",
            symbol, retrySeconds, failures,
        )
        val previousNotice = lastExitFailureNoticeAt[symbol] ?: Instant.EPOCH
        if (previousNotice.plusSeconds(900).isBefore(now)) {
            lastExitFailureNoticeAt[symbol] = now
            telegramSend(
                """
                ⚠️ ЗАЩИТА СРАБОТАЛА, НО ЗАЯВКА НЕ ИСПОЛНЕНА
                ━━━━━━━━━━━━━━━━━━
                🏷️ Акция: $symbol
                💡 Причина: ${reasonLabel(reason)}
                📍 Цена сигнала: ${formatPrice(price)} ₽
                
                Заявка не исполнена: сейчас может отсутствовать встречная ликвидность,
                либо BCS отменил/отклонил её. Позиция остаётся открытой.
                Бот продолжит попытки закрытия автоматически.
                """.trimIndent(),
            )
        }
        return false
    }

    private suspend fun notifyProtectedPartialClose(
        symbol: String,
        exitPrice: Double,
        reason: String,
        filledQty: Double,
        remainingQty: Double,
        execution: ru.criptobot.broker.OrderExecution,
    ) {
        val meta = tradeMeta[symbol]
        val (signalId, _) = analyticsRepo?.let { TradeSignalId.resolve(it, symbol, meta) }
            ?: (meta?.get("signal_id")?.toString() to null)
        val net = execution.netPnl
        analyticsLogger?.logDecision(
            currentRunId, signalId, symbol, "TRADE_PARTIAL_CLOSE", execution.side, reason, reason,
            mapOf(
                "qty" to filledQty,
                "remaining_qty" to remainingQty,
                "exit_price" to exitPrice,
                "net_pnl" to net,
            ),
        )
        paperMapper?.mapClose(
            signalId, meta?.get("local_trade_id")?.toString(), symbol, filledQty, exitPrice, "partial:$reason",
        )
        telegramSend(
            """
            🟡 ПОЗИЦИЯ ЗАКРЫТА ЧАСТИЧНО
            ━━━━━━━━━━━━━━━━━━
            🏷️ Акция: $symbol
            💡 Причина: ${reasonLabel(reason)}
            📦 Исполнено: ${formatQty(filledQty)}
            📦 Осталось: ${formatQty(remainingQty)}
            💵 Цена исполнения: ${formatPrice(exitPrice)} ₽
            ${if (net >= 0) "📈" else "📉"} Результат части: ${formatSignedRub(net)}
            
            Защита остатка позиции продолжает работать.
            """.trimIndent(),
        )
    }

    private fun blacklistForLiquidity(symbol: String, reason: String) {
        if (universeOverrides.remove(symbol)) {
            symbols = applyOverrides(eligibleSymbols)
            log.warn("Liquidity blacklist added [{}]: {}", symbol, reason)
        }
    }

    private fun executionPrice(execution: ru.criptobot.broker.OrderExecution?, fallback: Double): Double {
        if (execution == null || execution.qty <= 0) return fallback
        if (execution.averageFillPrice > 0) return execution.averageFillPrice
        return execution.filledNotionalRub / execution.qty
    }

    private suspend fun processSymbol(symbol: String, latestPrices: MutableMap<String, Double>) {
        currentInstrumentsChecked++
        val decision = when (settings.strategyMode) {
            "supertrend_ml" -> decideSupertrendMl(symbol)
            else -> decideIndicator(symbol)
        } ?: return
        val (action, score, snapshot) = decision
        if (snapshot.price < settings.minPrice || snapshot.price > settings.maxPrice) {
            logBlock(symbol, "OUT_OF_PRICE_RANGE", snapshot.price); return
        }

        paperLock.withLock {
            val barKey = barKeyAt(Instant.now(), settings.interval)
            if (checkRiskExit(symbol, snapshot.price, barKey)) { lastPrices[symbol] = snapshot.price; return }
            latestPrices[symbol] = snapshot.price; lastPrices[symbol] = snapshot.price
            val (qty, _) = broker.getPosition(symbol)
            val hasPosition = qty != 0.0
            val hasLong = qty > 0
            val hasShort = qty < 0
            val meta = lastDecisionMeta[symbol].orEmpty()
            val featureSnapshot = (meta["feature_snapshot"] as? Map<String, Any?>) ?: emptyMap()
            var modelSnapshot = (meta["model_snapshot"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
            val reasonCode = meta["reason_code"]?.toString()
            val reasonText = meta["reason_text"]?.toString()
            val marketRegime = meta["market_regime"]?.toString()
            marketSample += mapOf(
                "symbol" to symbol,
                "price" to snapshot.price,
                "score" to score,
                "rsi" to snapshot.rsi,
                "supertrend_direction" to safeFloat(featureSnapshot["supertrend_direction"], Double.NaN),
            )

            var confirmedBuy = false
            var confirmedShort = false
            if (hasPosition) {
                pendingBuy.remove(symbol)
                pendingShort.remove(symbol)
            } else if (symbol in pendingBuy) {
                val pend = pendingBuy[symbol]!!
                val signalBarKey = pend["signal_bar_key"]?.toString()
                if (signalBarKey != null && signalBarKey != barKey) {
                    if (action !in listOf("BUY", "HOLD")) {
                        pendingBuy.remove(symbol)
                        log.info("BUY pending cancelled [{}]: next-bar action={}", symbol, action)
                    } else {
                        val sigClose = safeFloat(pend["signal_close"])
                        val nextClose = nextBarCloseAfterSignal(symbol, signalBarKey)
                        pendingBuy.remove(symbol)
                        confirmedBuy = when {
                            nextClose == null || sigClose <= 0 -> {
                                logBuyConfirmationFailure(symbol, sigClose, nextClose, "missing_close")
                                false
                            }
                            !RegimeAdaptiveStrategy.nextBarConfirmsLong(sigClose, nextClose) -> {
                                logBuyConfirmationFailure(symbol, sigClose, nextClose, "next_bar_not_a_usable_pullback")
                                false
                            }
                            else -> {
                                log.info("BUY confirmed [{}]: signal_close={} next_close={}", symbol, sigClose, nextClose)
                                true
                            }
                        }
                    }
                } else if (action !in listOf("BUY", "HOLD")) {
                    pendingBuy.remove(symbol)
                }
            } else if (symbol in pendingShort) {
                val pend = pendingShort[symbol]!!
                val signalBarKey = pend["signal_bar_key"]?.toString()
                if (signalBarKey != null && signalBarKey != barKey) {
                    if (action !in listOf("SHORT", "HOLD")) {
                        pendingShort.remove(symbol)
                        log.info("SHORT pending cancelled [{}]: next-bar action={}", symbol, action)
                    } else {
                        val sigClose = safeFloat(pend["signal_close"])
                        val nextClose = nextBarCloseAfterSignal(symbol, signalBarKey)
                        pendingShort.remove(symbol)
                        confirmedShort = when {
                            nextClose == null || sigClose <= 0 -> {
                                logShortConfirmationFailure(symbol, sigClose, nextClose, "missing_close")
                                false
                            }
                            !RegimeAdaptiveStrategy.nextBarConfirmsShort(sigClose, nextClose) -> {
                                logShortConfirmationFailure(symbol, sigClose, nextClose, "next_bar_not_a_usable_bounce")
                                false
                            }
                            else -> {
                                log.info("SHORT confirmed [{}]: signal_close={} next_close={}", symbol, sigClose, nextClose)
                                true
                            }
                        }
                    }
                } else if (action !in listOf("SHORT", "HOLD")) {
                    pendingShort.remove(symbol)
                }
            }

            var effectiveBuy = confirmedBuy || action == "BUY"
            val effectiveShort = confirmedShort || action == "SHORT"
            var effectiveEntryRub = settings.positionSizeRub
            val entryRisk = if (effectiveBuy || effectiveShort) entryRiskSizing(symbol, snapshot.price, featureSnapshot) else null
            if (entryRisk != null) {
                modelSnapshot.putAll(entryRisk)
                effectiveEntryRub = safeFloat(entryRisk["effective_position_size_rub"], settings.positionSizeRub)
                val quality = ProfitabilityEntryGuard.evaluate(
                    side = if (effectiveShort) "SHORT" else "BUY",
                    rsi = safeFloat(featureSnapshot["rsi"], Double.NaN).takeUnless(Double::isNaN),
                    return5 = safeFloat(featureSnapshot["return_5"], Double.NaN).takeUnless(Double::isNaN),
                    distanceToSupertrendAtr = safeFloat(featureSnapshot["distance_to_supertrend_atr"], Double.NaN).takeUnless(Double::isNaN),
                    minimumOrderRub = effectiveEntryRub,
                    stopLossPct = strategy.stopLossPct,
                    equityRub = safeFloat(lastPerformance["equity_rub"] ?: lastPerformance["current_balance_rub"]),
                    bearishMarket = previousMarketBreadth.bearish,
                    confidence = score,
                    allowUnavoidableSingleLotRisk = entryRisk["unavoidable_single_lot"] == true,
                )
                modelSnapshot["profitability_model_version"] = effectiveStrategyVersion
                modelSnapshot["entry_monetary_risk_rub"] = quality.monetaryRiskRub
                modelSnapshot["entry_monetary_risk_limit_rub"] = quality.monetaryRiskLimitRub
                modelSnapshot["market_breadth_falling_share"] = previousMarketBreadth.fallingShare
                if (!quality.allowed) {
                    logBlock(
                        symbol,
                        quality.reasonCode ?: "PROFITABILITY_ENTRY_REJECTED",
                        mapOf(
                            "side" to if (effectiveShort) "SHORT" else "BUY",
                            "rsi" to featureSnapshot["rsi"],
                            "return_5" to featureSnapshot["return_5"],
                            "distance_to_supertrend_atr" to featureSnapshot["distance_to_supertrend_atr"],
                            "monetary_risk_rub" to quality.monetaryRiskRub,
                            "monetary_risk_limit_rub" to quality.monetaryRiskLimitRub,
                            "breadth_falling_share" to previousMarketBreadth.fallingShare,
                        ),
                    )
                    return
                }
            }

            val isStrategyExit = (hasLong && action == "SELL") || (hasShort && action == "COVER")
            val reversalExit = lastDecisionMeta[symbol]?.get("reason_code")?.toString() in RegimeAdaptiveStrategy.HARD_REVERSAL_EXITS
            if (isStrategyExit && !reversalExit && !exitSignalConfirmation.confirm(symbol, action, barKey)) {
                logBlock(
                    symbol,
                    "EXIT_AWAITING_NEXT_BAR_CONFIRMATION",
                    mapOf("action" to action, "first_bar" to barKey),
                )
                return
            }
            if (!isStrategyExit) exitSignalConfirmation.clear(symbol)

            if (hasPosition && action == "HOLD") return
            if (hasLong && action == "BUY") return
            if (hasShort && action == "SHORT") return
            if (!hasPosition && action in listOf("SELL", "COVER")) return
            if ((effectiveBuy || effectiveShort) && manualCloseAllPending.get()) return

            if (effectiveBuy || effectiveShort) {
                reentryCooldowns.active(symbol)?.let { cooldown ->
                    logBlock(
                        symbol,
                        "REENTRY_COOLDOWN",
                        mapOf("until" to cooldown.until.toString(), "reason" to cooldown.reason),
                    )
                    return
                }
            }

            var barKeyBuy: String? = null
            if ((effectiveBuy || effectiveShort) && lastEntryAttemptBarKey[symbol] == barKey) {
                logBlock(symbol, "SAME_BAR_ENTRY_ATTEMPT", barKey)
                return
            }
            if (effectiveBuy) {
                barKeyBuy = barKey
                if (lastBuyBarKey[symbol] == barKeyBuy || lastCloseBarKey[symbol] == barKeyBuy) {
                    logBlock(symbol, "SAME_BAR_REENTRY", barKeyBuy); return
                }
                val drawdownControl = portfolioRiskControl()
                val requireConfirmation =
                    settings.tradingMode != "paper" ||
                        settings.paperBuyBarConfirmation ||
                        drawdownControl.requireBarConfirmation
                if (!confirmedBuy && requireConfirmation) {
                    val refClose = safeFloat(featureSnapshot["close"]).takeIf { it > 0 } ?: snapshot.price
                    val pend = pendingBuy[symbol]
                    if (pend == null) {
                        pendingBuy[symbol] = mutableMapOf("signal_bar_key" to barKeyBuy, "signal_close" to refClose)
                        pendingShort.remove(symbol)
                        log.info("BUY deferred [{}]: pending 1-bar confirmation (signal_close={} bar_key={})", symbol, refClose, barKeyBuy)
                        logBuyPending(symbol, refClose, barKeyBuy)
                    } else if (pend["signal_bar_key"]?.toString() == barKeyBuy) {
                        pend["signal_close"] = refClose
                    } else {
                        pendingBuy[symbol] = mutableMapOf("signal_bar_key" to barKeyBuy, "signal_close" to refClose)
                        log.info("BUY deferred [{}]: pending 1-bar confirmation (signal_close={} bar_key={})", symbol, refClose, barKeyBuy)
                        logBuyPending(symbol, refClose, barKeyBuy)
                    }
                    return
                }
                val openPositions = broker.getOpenPositions(lastPrices).size
                if (settings.maxOpenPositions > 0 && openPositions >= settings.maxOpenPositions) {
                    logBlock(
                        symbol,
                        "MAX_OPEN_POSITIONS",
                        mapOf("open_positions" to openPositions, "limit" to settings.maxOpenPositions),
                    )
                    return
                }
                val affordable = maxAffordableBuyRub()
                if (affordable <= 0) {
                    logBlock(symbol, "INSUFFICIENT_CASH", mapOf("requested_rub" to effectiveEntryRub, "cash_rub" to affordable))
                    return
                }
                if (effectiveEntryRub > affordable) {
                    logBlock(
                        symbol,
                        "INSUFFICIENT_CASH_FOR_LOT",
                        mapOf("minimum_lot_rub" to effectiveEntryRub, "cash_rub" to affordable),
                    )
                    return
                }
                if (!broker.hasTwoSidedLiquidity(symbol)) {
                    logBlock(symbol, "PRE_ENTRY_EMPTY_ORDER_BOOK", null)
                    return
                }
            }
            if (effectiveShort) {
                if (!broker.canOpenShort(symbol)) {
                    logBlock(symbol, "SHORT_NOT_AVAILABLE", null)
                    return
                }
                val drawdownControl = portfolioRiskControl()
                val requireConfirmation =
                    settings.tradingMode != "paper" ||
                        settings.paperBuyBarConfirmation ||
                        drawdownControl.requireBarConfirmation
                if (!confirmedShort && requireConfirmation) {
                    val refClose = safeFloat(featureSnapshot["close"]).takeIf { it > 0 } ?: snapshot.price
                    val pend = pendingShort[symbol]
                    if (pend == null || pend["signal_bar_key"]?.toString() != barKey) {
                        pendingShort[symbol] = mutableMapOf("signal_bar_key" to barKey, "signal_close" to refClose)
                        pendingBuy.remove(symbol)
                        log.info("SHORT deferred [{}]: pending 1-bar confirmation (signal_close={} bar_key={})", symbol, refClose, barKey)
                        logShortPending(symbol, refClose, barKey)
                    } else {
                        pend["signal_close"] = refClose
                    }
                    return
                }
                val openPositions = broker.getOpenPositions(lastPrices)
                if (settings.maxOpenPositions > 0 && openPositions.size >= settings.maxOpenPositions) {
                    logBlock(symbol, "MAX_OPEN_POSITIONS", mapOf("open_positions" to openPositions.size, "limit" to settings.maxOpenPositions))
                    return
                }
                val openShorts = openPositions.count { safeFloat(it["qty"]) < 0 }
                if (settings.maxOpenShorts > 0 && openShorts >= settings.maxOpenShorts) {
                    logBlock(symbol, "MAX_OPEN_SHORTS", mapOf("open_shorts" to openShorts, "limit" to settings.maxOpenShorts))
                    return
                }
                if (!broker.hasTwoSidedLiquidity(symbol)) {
                    logBlock(symbol, "PRE_ENTRY_EMPTY_ORDER_BOOK", null)
                    return
                }
            }

            var signalId: String? = null
            if (analyticsLogger != null && (effectiveBuy || effectiveShort || action == "BUY" || (action == "HOLD" && settings.analyticsLogHolds))) {
                signalId = analyticsLogger.logSignal(currentRunId, symbol, settings.strategyMode, effectiveStrategyVersion,
                    if (effectiveBuy) "BUY" else if (effectiveShort) "SHORT" else action, snapshot.price,
                    if (effectiveBuy) snapshot.price * (1 - strategy.stopLossPct) else if (effectiveShort) snapshot.price * (1 + strategy.stopLossPct) else null,
                    if (effectiveBuy) snapshot.price * (1 + strategy.takeProfitPct) else if (effectiveShort) snapshot.price * (1 - strategy.takeProfitPct) else null,
                    score, reasonCode, reasonText, featureSnapshot, modelSnapshot, marketRegime,
                    settings.tradingMode, if (effectiveBuy || effectiveShort) "OPEN" else "HOLD")
            }
            if (analyticsLogger != null && modelSnapshot.isNotEmpty()) {
                val mlOk = modelSnapshot["ml_ok"] as? Boolean ?: false
                val mlProba = modelSnapshot["ml_prob_up"] as? Double
                analyticsLogger.logModelInference(
                    currentRunId, signalId, symbol, "kotlin-ml", featureSnapshot,
                    mapOf("p_up" to mlProba, "threshold" to modelSnapshot["ml_threshold_effective"]),
                    if (mlOk) "ALLOW_ENTRY" else "BLOCK_ENTRY", mlProba,
                    if (effectiveBuy) "BUY" else if (effectiveShort) "SHORT" else "HOLD",
                )
            }
            if (reasonCode != null && reasonCode.startsWith("BLOCK")) {
                analyticsLogger?.logDecision(
                    currentRunId, signalId, symbol, "STRATEGY_DECISION", "BLOCK", reasonCode,
                    reasonText ?: reasonCode,
                    mapOf("feature_snapshot" to featureSnapshot, "model_snapshot" to modelSnapshot),
                )
            }

            log.info("Symbol={} price={} score={} action={}", symbol, snapshot.price, score, action)

            if (effectiveBuy) {
                currentSignalsFound++
                val beforeQty = broker.getPosition(symbol).first
                val localTradeId = UUID.randomUUID().toString()
                lastEntryAttemptBarKey[symbol] = barKey
                saveEntryAttempts()
                val execution = broker.placeOrder(symbol, "BUY", effectiveEntryRub, snapshot.price)
                val (afterQty, afterAvg) = broker.getPosition(symbol)
                if (afterQty > beforeQty) {
                    barKeyBuy?.let { lastBuyBarKey[symbol] = it }; pendingBuy.remove(symbol)
                    val (sl, tp) = protectivePrices(afterAvg, false, featureSnapshot)
                    val msgId = kotlinx.coroutines.runBlocking { telegramSend(formatEntry(symbol, score, snapshot, sl, tp, entryRisk)) }
                    tradeMeta[symbol] = mutableMapOf("entry_price" to afterAvg, "entry_message_id" to msgId, "sl_price" to sl, "tp_price" to tp,
                        "signal_id" to signalId, "local_trade_id" to localTradeId, "entry_bar_key" to barKeyBuy)
                    if (settings.trailingTpEnabled) {
                        trailingPositions.register(
                            symbol, "LONG", afterAvg, sl, tp, settings.trailingTpCallbackPct,
                        )
                    }
                    analyticsLogger?.logDecision(currentRunId, signalId, symbol, "TRADE_OPEN", "BUY", "ORDER_FILLED", "Order opened.",
                        mapOf("qty_after" to afterQty, "avg_price" to afterAvg))
                    paperMapper?.mapOpen(signalId, localTradeId, symbol, afterQty - beforeQty, snapshot.price)
                    maybeInitPm(symbol, afterAvg, afterQty, barKeyBuy)
                } else {
                    analyticsLogger?.updateSignalStatus(signalId, "FAILED")
                    analyticsLogger?.logDecision(
                        currentRunId,
                        signalId,
                        symbol,
                        "TRADE_OPEN",
                        "REJECTED",
                        "ORDER_REJECTED",
                        "Broker did not increase the position.",
                        mapOf(
                            "requested_rub" to effectiveEntryRub,
                            "price" to snapshot.price,
                            "qty_before" to beforeQty,
                            "qty_after" to afterQty,
                            "execution" to execution?.toString(),
                        ),
                    )
                }
            } else if (effectiveShort) {
                currentSignalsFound++
                val beforeQty = broker.getPosition(symbol).first
                val localTradeId = UUID.randomUUID().toString()
                lastEntryAttemptBarKey[symbol] = barKey
                saveEntryAttempts()
                val execution = broker.placeOrder(symbol, "OPEN_SHORT", effectiveEntryRub, snapshot.price)
                val (afterQty, afterAvg) = broker.getPosition(symbol)
                if (afterQty < beforeQty) {
                    lastBuyBarKey[symbol] = barKey
                    pendingShort.remove(symbol)
                    val (sl, tp) = protectivePrices(afterAvg, true, featureSnapshot)
                    val msgId = telegramSend(formatShortEntry(symbol, score, snapshot, sl, tp, entryRisk))
                    tradeMeta[symbol] = mutableMapOf(
                        "entry_price" to afterAvg, "entry_message_id" to msgId, "sl_price" to sl, "tp_price" to tp,
                        "signal_id" to signalId, "local_trade_id" to localTradeId, "entry_bar_key" to barKey, "direction" to "SHORT",
                    )
                    if (settings.trailingTpEnabled) {
                        trailingPositions.register(
                            symbol, "SHORT", afterAvg, sl, tp, settings.trailingTpCallbackPct,
                        )
                    }
                    analyticsLogger?.logDecision(currentRunId, signalId, symbol, "TRADE_OPEN", "SHORT", "ORDER_FILLED",
                        "Short position opened.", mapOf("qty_after" to afterQty, "avg_price" to afterAvg))
                    paperMapper?.mapOpen(signalId, localTradeId, symbol, afterQty - beforeQty, snapshot.price)
                } else {
                    analyticsLogger?.updateSignalStatus(signalId, "FAILED")
                    analyticsLogger?.logDecision(currentRunId, signalId, symbol, "TRADE_OPEN", "REJECTED",
                        "SHORT_ORDER_REJECTED", "Broker did not open the short position.",
                        mapOf("requested_rub" to effectiveEntryRub, "price" to snapshot.price, "execution" to execution?.toString()))
                }
            } else if (action == "SELL") {
                val (qtyBefore, avgBefore) = broker.getPosition(symbol)
                if (qtyBefore > 0) {
                    val entryBar = tradeMeta[symbol]?.get("entry_bar_key")?.toString()
                    if (entryBar != null && entryBar == barKey) return
                    currentSignalsFound++
                    val execution = broker.placeOrder(symbol, "SELL", qtyBefore * snapshot.price, snapshot.price)
                    val remainingQty = broker.getPosition(symbol).first
                    if (remainingQty == 0.0) {
                        closeTrade(symbol, executionPrice(execution, snapshot.price), "Сигнал стратегии", qtyBefore, avgBefore, execution)
                        lastCloseBarKey[symbol] = barKey
                    } else if (execution != null) {
                        notifyProtectedPartialClose(
                            symbol = symbol,
                            exitPrice = executionPrice(execution, snapshot.price),
                            reason = "Сигнал стратегии",
                            filledQty = execution.qty.coerceAtMost(kotlin.math.abs(qtyBefore)),
                            remainingQty = kotlin.math.abs(remainingQty),
                            execution = execution,
                        )
                        lastCloseBarKey[symbol] = barKey
                    }
                }
            } else if (action == "COVER") {
                val (qtyBefore, avgBefore) = broker.getPosition(symbol)
                if (qtyBefore < 0) {
                    currentSignalsFound++
                    val absQty = -qtyBefore
                    val execution = broker.placeOrder(symbol, "CLOSE_SHORT", absQty * snapshot.price, snapshot.price)
                    val remainingQty = broker.getPosition(symbol).first
                    if (remainingQty == 0.0) {
                        closeTrade(symbol, executionPrice(execution, snapshot.price), "Разворот Supertrend", qtyBefore, avgBefore, execution)
                        lastCloseBarKey[symbol] = barKey
                    } else if (execution != null) {
                        notifyProtectedPartialClose(
                            symbol = symbol,
                            exitPrice = executionPrice(execution, snapshot.price),
                            reason = "Разворот Supertrend",
                            filledQty = execution.qty.coerceAtMost(kotlin.math.abs(qtyBefore)),
                            remainingQty = kotlin.math.abs(remainingQty),
                            execution = execution,
                        )
                        lastCloseBarKey[symbol] = barKey
                    }
                }
            }
            Unit
        }
    }

    private suspend fun decideIndicator(symbol: String): Triple<String, Double, IndicatorSnapshot>? {
        val snap = tv.getSnapshot(symbol)
        val score = scorer.score(snap, strategy)
        val action = when { score >= strategy.buyThreshold -> "BUY"; score <= strategy.sellThreshold -> "SELL"; else -> "HOLD" }
        lastDecisionMeta[symbol] = mapOf("reason_code" to action, "feature_snapshot" to emptyMap<String, Any?>())
        return Triple(action, score, snap)
    }

    private suspend fun decideSupertrendMl(symbol: String): Triple<String, Double, IndicatorSnapshot>? {
        val series = dataLoader.loadBinanceCandles(symbol, RegimeAdaptiveStrategy.DECISION_INTERVAL, RegimeAdaptiveStrategy.HISTORY_DAYS)
            ?: return null.also { rememberEvaluationSkip(symbol, "NO_CANDLES") }
        if (series.size < 100) {
            return null.also { rememberEvaluationSkip(symbol, "SHORT_HISTORY", mapOf("series_size" to series.size)) }
        }
        val candleTime = series.candles.lastOrNull()?.time
        if (candleTime != null && !DataLoader.isFreshForTrading(candleTime, RegimeAdaptiveStrategy.DECISION_INTERVAL)) {
            return null.also {
                rememberEvaluationSkip(symbol, "STALE_CANDLE", mapOf("candle_time" to candleTime.toString()))
            }
        }
        if (candleTime != null && lastEvaluatedCandle[symbol] == candleTime) {
            return null.also {
                rememberEvaluationSkip(symbol, "NO_NEW_BAR", mapOf("candle_time" to candleTime.toString()))
            }
        }
        if (candleTime != null) lastEvaluatedCandle[symbol] = candleTime
        val features = FeatureEngine.buildFeatures(series)
            ?: return null.also { rememberEvaluationSkip(symbol, "NO_FEATURES") }
        if (features.size < 50) {
            return null.also { rememberEvaluationSkip(symbol, "SHORT_FEATURES", mapOf("feature_rows" to features.size)) }
        }
        val last = features.last()
        val previous = features.getOrNull(features.size - 2)
        val close = last.close ?: return null.also { rememberEvaluationSkip(symbol, "NO_CLOSE") }
        val stDir = last.stDir ?: 0.0
        val ema200 = last.ema200 ?: 0.0
        val featureSnapshot = buildFeatureSnapshot(last)
        val mlProba = if (settings.mlEnabled) mlModel.predictProbaUp(last) else null
        val positionQty = broker.getPosition(symbol).first
        val hasLong = positionQty > 0
        val hasShort = positionQty < 0
        val marketRegime = if (stDir >= 1) "trend_up" else "trend_down"
        val fourHourDir = FeatureEngine.buildFeatures(dataLoader.aggregateFourHour(series))?.lastOrNull()?.stDir
        val signal = RegimeAdaptiveStrategy.evaluate(
            previousStDir = previous?.stDir,
            current = last,
            mlProbUp = mlProba,
            fallingShare = previousMarketBreadth.fallingShare,
            shortsEnabled = settings.shortEnabled,
            hasLong = hasLong,
            hasShort = hasShort,
            minPreviousTrendBars = RegimeAdaptiveStrategy.MIN_TREND_BARS,
            previousTrendAge = RegimeAdaptiveStrategy.previousTrendAge(features, features.lastIndex),
            higherTfDir = fourHourDir,
            requireHigherTf = RegimeAdaptiveStrategy.REQUIRE_HIGHER_TF,
        )
        val trendEval = TrendGate.evaluate(stDir, close, ema200, settings.trendGateMode, settings.tradingMode)
        val modelSnapshot = mutableMapOf<String, Any?>(
            "ml_prob_up" to mlProba,
            "strategy_version" to RegimeAdaptiveStrategy.VERSION,
            "supertrend_flipped" to RegimeAdaptiveStrategy.flipped(previous?.stDir, stDir),
            "trend_gate_mode" to trendEval.effectiveMode,
            "trend_ok" to trendEval.trendOk,
            "trend_supertrend_ok" to trendEval.trendSupertrendOk,
            "trend_close_above_ema200" to trendEval.trendCloseAboveEma200,
            "trend_block_detail" to trendEval.blockDetail,
            "market_breadth_falling_share" to previousMarketBreadth.fallingShare,
            "four_hour_supertrend_direction" to fourHourDir,
        )
        lastDecisionMeta[symbol] = mapOf(
            "reason_code" to signal.reasonCode,
            "reason_text" to signal.reasonCode,
            "feature_snapshot" to featureSnapshot,
            "model_snapshot" to modelSnapshot,
            "market_regime" to marketRegime,
        )

        if (signal.action == "SELL" || signal.action == "COVER") {
            return Triple(signal.action, signal.confidence, snap(last, close, symbol))
        }
        if (hasLong || hasShort) {
            rememberReason(signal.reasonCode)
            return Triple("HOLD", 0.0, snap(last, close, symbol))
        }

        val shortTrendOk = stDir <= -1 && close < ema200
        val shortMlOk = mlProba == null || mlProba <= settings.shortMlProbMax
        val shadowFeatures = featureSnapshot.mapNotNull { (key, value) ->
            (value as? Number)?.toDouble()?.takeUnless(Double::isNaN)?.let { key to it }
        }.toMap() + mapOf(
            "market_breadth_falling_share" to previousMarketBreadth.fallingShare,
            "ml_prob_up" to (mlProba ?: 0.5),
        )
        shadowShortObservations.trySend(
            ShadowShortObservation(
                timestamp = isoNow(),
                symbol = symbol,
                price = close,
                confidence = 1.0 - (mlProba ?: 0.3),
                shortCandidate = signal.action == "SHORT" || (shortTrendOk && shortMlOk),
                trendReversedUp = stDir >= 1 && close > ema200,
                features = shadowFeatures,
            )
        )

        if (signal.action == "BUY" || signal.action == "SHORT") {
            lastDecisionMeta[symbol] = mapOf(
                "reason_code" to signal.reasonCode,
                "reason_text" to if (signal.action == "SHORT") {
                    "Supertrend flipped down below EMA200."
                } else {
                    "Supertrend flipped up above EMA200."
                },
                "feature_snapshot" to featureSnapshot,
                "model_snapshot" to modelSnapshot,
                "market_regime" to marketRegime,
            )
            return Triple(signal.action, signal.confidence, snap(last, close, symbol))
        }
        rememberReason(signal.reasonCode)
        log.info(
            "Regime gate hold {}: reason={} flipped={} p_up={} falling_share={} four_hour_st={}",
            symbol, signal.reasonCode, modelSnapshot["supertrend_flipped"], mlProba,
            previousMarketBreadth.fallingShare, fourHourDir,
        )
        return Triple("HOLD", 0.0, snap(last, close, symbol))
    }

    private fun snap(last: ru.criptobot.features.FeatureRow, price: Double, symbol: String) =
        IndicatorSnapshot(symbol, price, last.ema50 ?: price, last.ema50 ?: price, last.rsi ?: 50.0, 25.0,
            last.macd ?: 0.0, last.macdSignal ?: 0.0, supertrend = last.stLine ?: 0.0, supertrendDirection = last.stDir ?: 0.0)

    private fun buildFeatureSnapshot(last: ru.criptobot.features.FeatureRow) = mapOf(
        "close" to last.close,
        "supertrend_value" to last.stLine,
        "supertrend_direction" to last.stDir,
        "ema50" to last.ema50,
        "ema200" to last.ema200,
        "rsi" to last.rsi,
        "macd" to last.macd,
        "macd_signal" to last.macdSignal,
        "macd_histogram" to last.macdHist,
        "atr" to last.atr,
        "atr_pct" to last.atrPct,
        "volume_ratio" to last.volRatio,
        "distance_to_supertrend_atr" to last.distToSt,
        "close_above_supertrend" to last.closeAboveSt,
        "ema50_distance" to last.emaDist50,
        "ema200_distance" to last.emaDist200,
        "return_1" to last.ret1,
        "return_3" to last.ret3,
        "return_5" to last.ret5,
        "return_10" to last.ret10,
        "volatility_20" to last.volatility,
        "hour_utc" to last.hour,
        "day_of_week" to last.dow,
        "price_vs_ema200_pct" to if (last.close != null && last.ema200 != null && last.ema200 != 0.0) ((last.close / last.ema200) - 1) * 100 else null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun protectivePrices(entry: Double, short: Boolean, snapshot: Any?): Pair<Double, Double> {
        val atr = when (snapshot) {
            is Map<*, *> -> safeFloat(snapshot["atr"], Double.NaN).takeUnless(Double::isNaN)
            else -> null
        }
        return RegimeAdaptiveStrategy.stopLossPrice(entry, atr, strategy.stopLossPct, short) to
            RegimeAdaptiveStrategy.activationPrice(entry, atr, strategy.takeProfitPct, short)
    }

    private fun entryRiskSizing(symbol: String, price: Double, fs: Map<String, Any?>): Map<String, Any?> {
        // Binance paper uses fractional qty sized in USDT, not exchange lots of 1 coin.
        val equity = safeFloat(lastPerformance["equity_rub"] ?: lastPerformance["current_balance_rub"])
            .coerceAtLeast(settings.paperInitialBalanceRub)
        val cash = safeFloat(lastPerformance["cash_rub"], equity)
        val capitalSize = CapitalAwarePositionSizer.chooseTarget(
            equityRub = equity,
            cashRub = cash,
            normalPositionRub = settings.positionSizeRub,
            boostedPositionRub = settings.positionSizeRub,
        )
        val vr = safeFloat(fs["volume_ratio"], Double.NaN)
        var volumeMultiplier = 1.0
        if (!vr.isNaN()) { if (vr > 5) volumeMultiplier = 0.25 else if (vr > 2) volumeMultiplier = 0.5 }
        val drawdownControl = portfolioRiskControl()
        val riskMultiplier = volumeMultiplier * drawdownControl.positionMultiplier
        val targetNotional = (capitalSize.configuredTargetRub * riskMultiplier)
            .coerceAtLeast(settings.minOrderRub)
            .coerceAtMost(cash.coerceAtLeast(0.0))
        val orderQuantity = if (price > 0) targetNotional / price else 0.0
        val monetaryRisk = targetNotional * strategy.stopLossPct.coerceAtLeast(0.0)
        val monetaryRiskLimit = maxOf(equity * 0.003, 1.0)
        return mapOf(
            "entry_risk_multiplier" to riskMultiplier,
            "volume_risk_multiplier" to volumeMultiplier,
            "drawdown_risk_multiplier" to drawdownControl.positionMultiplier,
            "portfolio_drawdown_pct" to drawdownControl.drawdownPct,
            "volume_ratio" to if (vr.isNaN()) null else vr,
            "lot_size" to 1,
            "minimum_lot_rub" to settings.minOrderRub,
            "lots_required" to 1,
            "order_quantity" to orderQuantity,
            "minimum_order_rub" to settings.minOrderRub,
            "base_position_size_rub" to capitalSize.configuredTargetRub,
            "boosted_position_size_rub" to settings.positionSizeRub,
            "normal_position_size_rub" to settings.positionSizeRub,
            "capital_free_fraction" to capitalSize.freeCapitalFraction,
            "capital_size_boosted" to capitalSize.boosted,
            "available_cash_rub" to cash,
            "unavoidable_single_lot" to false,
            "risk_sized_position_rub" to targetNotional,
            "entry_monetary_risk_rub" to monetaryRisk,
            "entry_monetary_risk_limit_rub" to monetaryRiskLimit,
            "effective_position_size_rub" to targetNotional,
        )
    }

    private fun portfolioRiskControl(): PortfolioRiskDecision = PortfolioRiskControl.evaluate(
        initialEquity = safeFloat(lastPerformance["initial_balance_rub"]),
        currentEquity = safeFloat(lastPerformance["equity_rub"] ?: lastPerformance["current_balance_rub"]),
    )

    private suspend fun maybeInitPm(symbol: String, avg: Double, qty: Double, barKey: String?) {
        if (settings.positionMgmtMode == "off") return
        val series = dataLoader.loadCandles(symbol, settings.exchange, settings.interval)
        val prof = PositionManagement.computeProfile(series)
        pmProfiles[symbol] = prof
        pmStates[symbol] = PositionManagement.buildInitialState(avg, qty, series, strategy.stopLossPct, strategy.takeProfitPct, barKey ?: "")
    }

    private fun checkRiskExit(symbol: String, price: Double, barKey: String): Boolean {
        // When enabled, one independent monitor owns protection for both
        // legacy positions (static SL/TP) and new trailing positions.
        if (settings.trailingTpEnabled) return false
        val (qty, avg) = broker.getPosition(symbol)
        if (qty == 0.0 || avg <= 0) return false
        val isShort = qty < 0
        if (!isShort && settings.positionMgmtMode != "off" && symbol in pmStates) {
            val prof = pmProfiles[symbol] ?: PositionManagement.computeProfile(null)
            val (state, events) = PositionManagement.evaluateTick(pmStates[symbol]!!, price, barKey, prof)
            pmStates[symbol] = state
            for (ev in events) {
                if (ev.fullExit || ev.closeFraction > 0) {
                    val q = if (ev.fullExit) qty else qty * ev.closeFraction
                    val exec = broker.placeOrder(symbol, "SELL", q * price, price)
                    if (exec != null || broker.getPosition(symbol).first == 0.0) {
                        if (ev.fullExit) {
                            closeTradeSync(symbol, executionPrice(exec, price), ev.code, qty, avg, exec)
                            pmStates.remove(symbol)
                            pmProfiles.remove(symbol)
                        } else {
                            partialClose(symbol, executionPrice(exec, price), q, ev.code, avg, exec)
                        }
                        lastCloseBarKey[symbol] = barKey
                        return true
                    }
                    return false
                }
            }
            if (settings.positionMgmtMode == "paper") return false
        }
        val sl = if (isShort) avg * (1 + strategy.stopLossPct) else avg * (1 - strategy.stopLossPct)
        val tp = if (isShort) avg * (1 - strategy.takeProfitPct) else avg * (1 + strategy.takeProfitPct)
        val exitTriggered = if (isShort) price >= sl || price <= tp else price <= sl || price >= tp
        if (exitTriggered) {
            val side = if (isShort) "CLOSE_SHORT" else "SELL"
            val exec = broker.placeOrder(symbol, side, kotlin.math.abs(qty) * price, price)
            val stopTriggered = if (isShort) price >= sl else price <= sl
            if (exec != null || broker.getPosition(symbol).first == 0.0) {
                closeTradeSync(
                    symbol, executionPrice(exec, price),
                    if (stopTriggered) "Stop-loss" else "Take-profit", qty, avg, exec,
                )
                pmStates.remove(symbol)
                lastCloseBarKey[symbol] = barKey
                return true
            }
        }
        return false
    }

    private fun partialClose(symbol: String, price: Double, qty: Double, reason: String, avg: Double, exec: ru.criptobot.broker.OrderExecution?) {
        val meta = tradeMeta[symbol]
        val (signalId, _) = analyticsRepo?.let { TradeSignalId.resolve(it, symbol, meta) }
            ?: (meta?.get("signal_id")?.toString() to null)
        val filledQty = exec?.qty ?: qty
        val net = exec?.netPnl ?: (price - avg) * filledQty
        analyticsLogger?.logDecision(
            currentRunId, signalId, symbol, "TRADE_PARTIAL_CLOSE", "SELL", reason, reason,
            mapOf("qty" to filledQty, "exit_price" to price, "net_pnl" to net),
        )
        paperMapper?.mapClose(
            signalId, meta?.get("local_trade_id")?.toString(), symbol, filledQty, price, "partial:$reason",
        )
        kotlinx.coroutines.runBlocking {
            telegramSend(
                """
                🟡 ЧАСТЬ ПОЗИЦИИ ЗАКРЫТА
                ━━━━━━━━━━━━━━━━━━
                🏷️ Акция: $symbol
                💡 Причина: ${reasonLabel(reason)}
                📦 Продано: ${formatQty(filledQty)}
                💵 Цена: ${formatPrice(price)} ₽
                
                ${if (net >= 0) "📈" else "📉"} Результат этой продажи: ${formatSignedRub(net)}
                """.trimIndent()
            )
        }
    }

    private fun closeTradeSync(symbol: String, exitPrice: Double, reason: String, qty: Double, avg: Double, execution: ru.criptobot.broker.OrderExecution?) {
        kotlinx.coroutines.runBlocking { closeTrade(symbol, exitPrice, reason, qty, avg, execution) }
    }

    private suspend fun closeTrade(symbol: String, exitPrice: Double, reason: String, qty: Double, avg: Double, execution: ru.criptobot.broker.OrderExecution?) {
        val meta = tradeMeta[symbol]
        val (signalId, _) = analyticsRepo?.let { TradeSignalId.resolve(it, symbol, meta) } ?: (meta?.get("signal_id")?.toString() to null)
        tradeMeta.remove(symbol); pmStates.remove(symbol)
        trailingPositions.remove(symbol)
        val net = execution?.netPnl ?: (exitPrice - avg) * qty - (execution?.commissionRub ?: 0.0)
        val netPct = execution?.netPnlPct ?: if (avg > 0) ((exitPrice / avg) - 1) * 100 else 0.0
        val normalizedReason = reason.lowercase()
        val cooldownMinutes = when {
            "stop-loss" in normalizedReason || "stop loss" in normalizedReason ->
                settings.stopLossReentryCooldownMinutes
            "take-profit" in normalizedReason || "take profit" in normalizedReason ->
                settings.profitableExitReentryCooldownMinutes
            else -> 0L
        }
        if (cooldownMinutes > 0) {
            reentryCooldowns.block(symbol, Duration.ofMinutes(cooldownMinutes), reason)
            log.info(
                "Re-entry cooldown [{}]: {} minutes after {}",
                symbol, cooldownMinutes, reason,
            )
        }
        val msg = buildString {
            appendLine("${if (net >= 0) "✅" else "🔴"} ПОЗИЦИЯ ЗАКРЫТА")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("🏷️ Акция: $symbol")
            appendLine("💡 Причина: ${reasonLabel(reason)}")
            appendLine("📦 Количество: ${formatQty(qty)}")
            appendLine()
            appendLine("🚪 Вход: ${formatPrice(avg)} ₽")
            appendLine("🏁 Выход: ${formatPrice(exitPrice)} ₽")
            appendLine("${if (net >= 0) "📈" else "📉"} Результат: ${formatClosePnlLine(net, netPct)}")
        }
        telegramSend(msg)
        analyticsLogger?.updateSignalStatus(signalId, "CLOSED")
        analyticsLogger?.logDecision(currentRunId, signalId, symbol, "TRADE_CLOSE", "SELL", "CLOSE_TRADE", reason,
            mapOf("qty" to qty, "exit_price" to exitPrice, "net_pnl" to net))
        paperMapper?.mapClose(signalId, meta?.get("local_trade_id")?.toString(), symbol, qty, exitPrice, reason)
        (meta?.get("entry_message_id") as? Number)?.toLong()?.let {
            telegramEdit(it, "ЗАВЕРШЕНО\n\n$msg")
        }
    }

    private suspend fun nextBarCloseAfterSignal(symbol: String, signalBarKey: String): Double? {
        val series = dataLoader.loadCandles(symbol, settings.exchange, settings.interval) ?: return null
        val afterSignal = series.candles.filter { candle ->
            candle.time != null && barKeyAt(candle.time!!, settings.interval) > signalBarKey
        }
        return afterSignal.maxByOrNull { it.time!! }?.close ?: series.lastClose()
    }

    private fun logBuyConfirmationFailure(symbol: String, signalClose: Double, nextClose: Double?, detail: String) {
        log.info(
            "BUY confirmation rejected [{}]: {} signal_close={} next_close={}",
            symbol, detail, signalClose, nextClose,
        )
        analyticsLogger?.logDecision(
            currentRunId, null, symbol, "STRATEGY_DECISION", "SKIP", "BUY_BAR_CONFIRMATION_FAIL",
            "BUY deferred: next bar close did not confirm signal bar close.",
            mapOf("detail" to detail, "confirmation_passed" to false, "signal_bar_close" to signalClose, "next_bar_close" to nextClose),
        )
    }

    private fun logBuyPending(symbol: String, signalClose: Double, signalBarKey: String?) {
        analyticsLogger?.logDecision(
            currentRunId,
            null,
            symbol,
            "STRATEGY_DECISION",
            "DEFER",
            "BUY_BAR_PENDING",
            "BUY waits for confirmation by the next completed bar.",
            mapOf(
                "signal_bar_close" to signalClose,
                "signal_bar_key" to signalBarKey,
                "confirmation_required" to true,
            ),
        )
    }

    private fun logShortConfirmationFailure(symbol: String, signalClose: Double, nextClose: Double?, detail: String) {
        log.info(
            "SHORT confirmation rejected [{}]: {} signal_close={} next_close={}",
            symbol, detail, signalClose, nextClose,
        )
        analyticsLogger?.logDecision(
            currentRunId, null, symbol, "STRATEGY_DECISION", "SKIP", "SHORT_BAR_CONFIRMATION_FAIL",
            "SHORT deferred: next bar close did not confirm dump after signal bar.",
            mapOf("detail" to detail, "confirmation_passed" to false, "signal_bar_close" to signalClose, "next_bar_close" to nextClose),
        )
    }

    private fun logShortPending(symbol: String, signalClose: Double, signalBarKey: String?) {
        analyticsLogger?.logDecision(
            currentRunId,
            null,
            symbol,
            "STRATEGY_DECISION",
            "DEFER",
            "SHORT_BAR_PENDING",
            "SHORT waits for confirmation by a lower next completed bar.",
            mapOf(
                "signal_bar_close" to signalClose,
                "signal_bar_key" to signalBarKey,
                "confirmation_required" to true,
            ),
        )
    }

    private fun maxAffordableBuyRub(): Double {
        val snap = broker.getBalanceSnapshot(lastPrices)
        return safeFloat(snap["cash_rub"])
    }

    private fun logBlock(symbol: String, code: String, detail: Any?) {
        rememberReason(code)
        log.info("Entry blocked [{}]: {} detail={}", symbol, code, detail)
        val meta = lastDecisionMeta[symbol].orEmpty()
        val balance = broker.getBalanceSnapshot(lastPrices)
        analyticsLogger?.logDecision(
            currentRunId,
            null,
            symbol,
            "STRATEGY_DECISION",
            "BLOCK",
            code,
            code,
            mapOf(
                "detail" to detail,
                "feature_snapshot" to meta["feature_snapshot"],
                "model_snapshot" to meta["model_snapshot"],
                "reason_text" to meta["reason_text"],
                "cash_rub" to balance["cash_rub"],
                "equity_rub" to (balance["equity_rub"] ?: balance["current_balance_rub"]),
                "open_positions" to broker.getOpenPositions(lastPrices).size,
            ),
        )
    }

    private fun loadEntryAttempts() {
        if (!Files.exists(entryAttemptStateFile)) return
        runCatching {
            Files.readAllLines(entryAttemptStateFile).forEach { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    lastEntryAttemptBarKey[parts[0]] = parts[1]
                }
            }
        }.onFailure { log.warn("Failed to load entry-attempt state: {}", it.message) }
    }

    private fun saveEntryAttempts() {
        runCatching {
            val body = lastEntryAttemptBarKey.entries
                .sortedBy { it.key }
                .joinToString("\n") { "${it.key}\t${it.value}" }
            Files.writeString(entryAttemptStateFile, body)
        }.onFailure { log.warn("Failed to save entry-attempt state: {}", it.message) }
    }

    private fun rememberReason(code: String) {
        currentReasonCounts[code] = currentReasonCounts.getOrDefault(code, 0) + 1
    }

    private fun rememberEvaluationSkip(symbol: String, code: String, detail: Map<String, Any?> = emptyMap()) {
        rememberReason(code)
        analyticsLogger?.logDecision(
            currentRunId,
            null,
            symbol,
            "EVALUATION",
            "SKIP",
            code,
            reasonLabel(code),
            detail,
        )
    }

    private fun modeLabel(): String = when {
        settings.tradingMode == "paper" -> "🧪 симуляция без реальных денег"
        else -> "💳 реальные сделки Binance"
    }

    private fun formatPrice(value: Double): String =
        String.format("%.4f", value).trimEnd('0').trimEnd('.')

    private fun formatQty(value: Double): String =
        String.format("%.6f", value).trimEnd('0').trimEnd('.')

    private fun formatPercent(value: Double): String = String.format("%.2f%%", value)

    private fun reasonLabel(code: String): String = when (code.uppercase()) {
        "NO_CANDLES" -> "нет рыночных данных"
        "SHORT_HISTORY", "SHORT_FEATURES" -> "недостаточно истории"
        "STALE_CANDLE" -> "рынок закрыт или данные устарели"
        "NO_NEW_BAR" -> "новая свеча ещё не сформировалась"
        "NO_FEATURES", "NO_CLOSE" -> "не удалось рассчитать индикаторы"
        "HOLD_OPEN_POSITION" -> "позиция уже открыта"
        "BLOCK_ML", "BLOCK_ADAPTIVE", "BLOCK_ADAPTIVE_BUCKET" -> "сигнал недостаточно сильный"
        "BLOCK_TREND" -> "направление тренда не подтверждено"
        "BLOCK_HIGH_VOLUME_RATIO" -> "аномально высокий объём"
        "OUT_OF_PRICE_RANGE" -> "цена вне разрешённого диапазона"
        "SAME_BAR_REENTRY" -> "повторный вход на той же свече запрещён"
        "INSUFFICIENT_CASH" -> "недостаточно свободных денег"
        "MAX_OPEN_POSITIONS" -> "достигнут лимит открытых позиций"
        "MAX_OPEN_SHORTS" -> "достигнут лимит коротких позиций"
        "Stop-loss".uppercase() -> "сработала защита от убытка"
        "Take-profit".uppercase() -> "достигнута цель по прибыли"
        "Manual close all".uppercase() -> "закрыто вручную"
        else -> code.replace('_', ' ').lowercase()
    }

    private fun noSignalSummary(): String {
        val reasons = currentReasonCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("\n") { "• ${reasonLabel(it.key)} — ${it.value}" }
            .ifBlank { "• Нет новых закрытых свечей" }
        val equity = safeFloat(lastPerformance["equity_rub"] ?: lastPerformance["current_balance_rub"])
        val pnl = safeFloat(lastPerformance["total_pnl_rub"])
        return buildString {
            appendLine("🕒 РЫНОК ПРОВЕРЕН")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("🔄 Цикл: #$cycle")
            appendLine("👀 Проверено пар: $currentInstrumentsChecked из ${symbols.size}")
            appendLine("⏸️ Новых сделок: нет")
            appendLine()
            appendLine("💼 Портфель: ${formatRub(equity)}")
            appendLine("${if (pnl >= 0) "📈" else "📉"} Результат: ${formatSignedRub(pnl)}")
            appendLine()
            appendLine("💡 Основные причины:")
            appendLine(reasons)
        }
    }

    private fun formatEntry(symbol: String, score: Double, snap: IndicatorSnapshot, sl: Double, tp: Double, entryRisk: Map<String, Any?>?) = buildString {
        val amount = entryRisk?.get("effective_position_size_rub") ?: settings.positionSizeRub
        val riskMultiplier = safeFloat(entryRisk?.get("entry_risk_multiplier"), 1.0)
        appendLine("🟢 ОТКРЫТА НОВАЯ ПОЗИЦИЯ")
        appendLine("━━━━━━━━━━━━━━━━━━")
        appendLine("🏷️ Акция: $symbol")
        appendLine("📈 Направление: LONG")
        appendLine("🚪 Цена входа: ${formatPrice(snap.price)} ₽")
        appendLine("💰 Сумма: ${formatRub(amount)}")
        entryRisk?.get("order_quantity")?.let { appendLine("📦 Количество: $it шт.") }
        appendLine("📊 Сила сигнала: ${formatPercent(score * 100)}")
        appendLine()
        appendLine("🛡️ Защита от убытка: ${formatPrice(sl)} ₽")
        appendLine("🎯 Цель по прибыли: ${formatPrice(tp)} ₽")
        if (riskMultiplier < 0.999) {
            appendLine("⚠️ Система риска допускает только минимальный лот")
        }
        appendLine("⚙️ Режим: ${modeLabel()}")
    }

    private fun formatShortEntry(
        symbol: String,
        score: Double,
        snap: IndicatorSnapshot,
        sl: Double,
        tp: Double,
        entryRisk: Map<String, Any?>?,
    ) = buildString {
        appendLine("🔻 ОТКРЫТА КОРОТКАЯ ПОЗИЦИЯ")
        appendLine("━━━━━━━━━━━━━━━━━━")
        appendLine("🏷️ Акция: $symbol")
        appendLine("📉 Направление: SHORT")
        appendLine("🚪 Цена входа: ${formatPrice(snap.price)} ₽")
        entryRisk?.get("order_quantity")?.let { appendLine("📦 Количество: $it шт.") }
        appendLine("📊 Сила сигнала снижения: ${formatPercent(score * 100)}")
        appendLine()
        appendLine("🛡️ Стоп-лосс: ${formatPrice(sl)} ₽")
        appendLine("🎯 Цель: ${formatPrice(tp)} ₽")
        appendLine("⚙️ Режим: ${modeLabel()}")
    }

    override fun status(): String {
        val s = broker.getBalanceSnapshot(lastPrices)
        val equity = safeFloat(s["equity_rub"] ?: s["current_balance_rub"])
        val totalPnl = safeFloat(s["total_pnl_rub"])
        val open = broker.getOpenPositions(lastPrices).size
        return buildString {
            appendLine("🤖 АВТОТОРГ РАБОТАЕТ")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("⚙️ Режим: ${modeLabel()}")
            appendLine("🕒 Таймфрейм: ${settings.interval}")
            appendLine("🔄 Цикл: #$cycle")
            appendLine("👀 Отслеживается пар: ${symbols.size}")
            appendLine()
            appendLine("💼 Портфель: ${formatRub(equity)}")
            appendLine("${if (totalPnl >= 0) "📈" else "📉"} Общий результат: ${formatSignedRub(totalPnl)}")
            appendLine("💵 Свободные деньги: ${formatRub(s["cash_rub"])}")
            appendLine("📂 Открытых позиций: $open${if (settings.maxOpenPositions > 0) " из ${settings.maxOpenPositions}" else " (без лимита бота)"}")
        }
    }

    override fun symbolsList(): String {
        val inc = universeOverrides.included
        val exc = universeOverrides.excluded
        return buildString {
            appendLine("🧺 СПИСОК НАБЛЮДЕНИЯ")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("👀 Активных пар: ${symbols.size}")
            appendLine("🌐 Источник: ${settings.universeSource.uppercase()}")
            appendLine()
            appendLine(symbols.chunked(15).joinToString("\n") { it.joinToString(", ") }.ifBlank { "—" })
            if (inc.isNotEmpty()) appendLine("\nДобавлены вручную: ${inc.joinToString(", ")}")
            if (exc.isNotEmpty()) appendLine("Исключены вручную: ${exc.joinToString(", ")}")
        }
    }

    override fun addSymbol(symbol: String): String = kotlinx.coroutines.runBlocking {
        operatorLock.withLock {
            val sym = symbol.trim().uppercase()
            if (sym.isEmpty()) return@withLock "❌ Укажите пару: /add BTCUSDT"
            if (sym !in eligibleSymbols) {
                return@withLock "⚠️ $sym нельзя вернуть: пара отсутствует в доступном списке или заблокирована риск-фильтром."
            }
            universeOverrides.add(sym)
            symbols = applyOverrides(eligibleSymbols)
            "✅ $sym добавлена в работу.\n👀 Активных пар: ${symbols.size}"
        }
    }

    override fun removeSymbol(symbol: String): String = kotlinx.coroutines.runBlocking {
        operatorLock.withLock {
            val sym = symbol.trim().uppercase()
            if (sym.isEmpty()) return@withLock "❌ Укажите пару: /remove ETHUSDT"
            universeOverrides.remove(sym)
            symbols = applyOverrides(eligibleSymbols)
            "✅ $sym исключена из работы.\n👀 Активных пар: ${symbols.size}"
        }
    }

    override fun strategy(): String {
        val control = portfolioRiskControl()
        return buildString {
            appendLine("⚙️ КАК БОТ ПРИНИМАЕТ РЕШЕНИЯ")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("🧠 Стратегия: Supertrend flip 1h, подтверждение 4h, лонг и шорт")
            appendLine("🕒 Таймфрейм решения: ${RegimeAdaptiveStrategy.DECISION_INTERVAL} + 4h")
            appendLine("📊 Вход на смене Supertrend, если 4h смотрит в ту же сторону")
            appendLine("🛡️ Стоп-лосс: ${formatPercent(strategy.stopLossPct * 100)}")
            appendLine("🎯 Цель: ${formatPercent(strategy.takeProfitPct * 100)}")
            appendLine("📂 Максимум позиций: ${if (settings.maxOpenPositions > 0) settings.maxOpenPositions else "без лимита бота"}")
            appendLine("🔻 Короткие позиции: ${if (settings.shortEnabled) "включены, макс. ${settings.maxOpenShorts}" else "выключены"}")
            appendLine()
            appendLine("📉 Текущая просадка: ${formatPercent(control.drawdownPct)}")
            appendLine("💰 Размер следующей позиции: ${formatPercent(control.positionMultiplier * 100)} от базового")
            appendLine(
                if (settings.paperBuyBarConfirmation || control.requireBarConfirmation)
                    "✅ Подтверждение следующим баром: включено"
                else "⭕ Подтверждение следующим баром: выключено"
            )
        }
    }

    private fun refreshBlacklistCandidates(notify: Boolean) {
        val proposer = blacklistProposer ?: return
        val batch = proposer.refresh(
            activeSymbols = eligibleSymbols,
            alreadyExcluded = universeOverrides.excluded,
            quoteVolumes = universeProvider.allQuoteVolumes(),
        )
        if (!batch.maturity.ready) {
            log.debug("Blacklist warmup: {}", batch.maturity.detail)
            return
        }
        if (batch.newlyProposed.isNotEmpty()) {
            log.info(
                "Blacklist candidates ready silently: {} new (open «Чёрный список» in Telegram)",
                batch.newlyProposed.size,
            )
        }
    }

    override fun blacklistCandidatesList(): String {
        val pending = blacklistCandidates.pending()
        val excluded = universeOverrides.excluded
        val maturity = blacklistProposer?.maturityStatus()
        return buildString {
            appendLine("⛔ ЧЁРНЫЙ СПИСОК / КАНДИДАТЫ")
            appendLine("━━━━━━━━━━━━━━━━━━")
            if (maturity != null) {
                appendLine(if (maturity.ready) "✅ Этап аналитики: готов" else "🧊 Этап аналитики: накопление")
                appendLine(maturity.detail)
                appendLine()
            }
            appendLine("⏳ Кандидаты (ждут решения): ${pending.size}")
            appendLine("🚫 Уже в чёрном списке: ${excluded.size}")
            appendLine()
            if (maturity != null && !maturity.ready) {
                appendLine("Кандидаты появятся только после накопления практики.")
            } else if (pending.isEmpty()) {
                appendLine("Новых кандидатов нет.")
            } else {
                pending.take(15).forEach { c ->
                    appendLine("• ${c.symbol} — ${c.reasonCode}")
                    appendLine("  ${c.reasonText}")
                    appendLine("  /bl_approve ${c.symbol} | /bl_reject ${c.symbol}")
                    appendLine()
                }
            }
            if (excluded.isNotEmpty()) {
                appendLine("Чёрный список:")
                appendLine(excluded.sorted().joinToString(", "))
            }
        }
    }

    override fun approveBlacklistCandidate(symbol: String): String = kotlinx.coroutines.runBlocking {
        operatorLock.withLock {
            val sym = symbol.trim().uppercase()
            if (sym.isEmpty()) return@withLock "❌ Укажите пару: /bl_approve BTCUSDT"
            val approved = blacklistCandidates.approve(sym)
                ?: return@withLock "ℹ️ Нет pending-кандидата для $sym"
            universeOverrides.remove(sym)
            symbols = applyOverrides(eligibleSymbols)
            analyticsLogger?.logDecision(
                currentRunId, null, sym, "BLACKLIST", "APPROVED",
                approved.reasonCode, approved.reasonText,
                approved.evidence + mapOf("source" to "human_approve"),
            )
            "✅ $sym переведён в чёрный список.\nПричина: ${approved.reasonText}\n👀 Активных пар: ${symbols.size}"
        }
    }

    override fun rejectBlacklistCandidate(symbol: String): String = kotlinx.coroutines.runBlocking {
        operatorLock.withLock {
            val sym = symbol.trim().uppercase()
            if (sym.isEmpty()) return@withLock "❌ Укажите пару: /bl_reject BTCUSDT"
            val rejected = blacklistCandidates.reject(sym)
                ?: return@withLock "ℹ️ Нет pending-кандидата для $sym"
            analyticsLogger?.logDecision(
                currentRunId, null, sym, "BLACKLIST", "REJECTED",
                rejected.reasonCode, rejected.reasonText,
                rejected.evidence + mapOf("source" to "human_reject"),
            )
            "✅ Предложение по $sym отклонено. Пара остаётся в работе."
        }
    }

    @Synchronized
    override fun applyAiStrategy(): String {
        val proposed = pendingAiStrategy
            ?: return "ℹ️ Активного предложения ИИ уже нет."
        strategy = proposed
        lastRebalanceTradeCount = pendingAiStrategyTradeCount
        strategy.save(settings.strategyStateFile, lastRebalanceTradeCount)
        pendingAiStrategy = null
        pendingAiStrategyTradeCount = 0
        log.info("AI strategy proposal accepted by Telegram operator")
        return "✅ Предложение ИИ применено.\n\n${strategy()}"
    }

    @Synchronized
    override fun rejectAiStrategy(): String {
        if (pendingAiStrategy == null) return "ℹ️ Активного предложения ИИ уже нет."
        lastRebalanceTradeCount = pendingAiStrategyTradeCount
        pendingAiStrategy = null
        pendingAiStrategyTradeCount = 0
        log.info("AI strategy proposal rejected by Telegram operator")
        return "❌ Предложение ИИ отклонено. Текущая стратегия не изменена."
    }

    override fun pnl(): String {
        val zone = PNL_REPORT_ZONE
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        return pnlSince(dayStart, "ЗАКРЫТЫЕ СДЕЛКИ ЗА СЕГОДНЯ", "с 00:00 МСК")
    }

    override fun pnlForDay(day: LocalDate): String {
        val zone = PNL_REPORT_ZONE
        val dayStart = day.atStartOfDay(zone).toInstant()
        val nextDayStart = day.plusDays(1).atStartOfDay(zone).toInstant()
        val label = day.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        return pnlSince(
            dayStart,
            "ЗАКРЫТЫЕ СДЕЛКИ ЗА $label",
            "$label, МСК",
            nextDayStart,
        )
    }

    override fun pnlWeek(): String {
        val zone = PNL_REPORT_ZONE
        val today = LocalDate.now(zone)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return pnlSince(
            monday.atStartOfDay(zone).toInstant(),
            "ЗАКРЫТЫЕ СДЕЛКИ С НАЧАЛА НЕДЕЛИ",
            "с понедельника, ${monday.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))}, 00:00 МСК",
        )
    }

    private fun pnlSince(periodStart: Instant, title: String, periodLabel: String, periodEnd: Instant? = null): String {
        val endClause = if (periodEnd == null) "" else " AND decision_ts < ?"
        val queryParams = buildList {
            add(periodStart.toString())
            periodEnd?.let { add(it.toString()) }
        }
        val row = analyticsRepo?.fetchAll(
            """
            SELECT
              COALESCE(SUM(CAST(json_extract(details_json, '$.net_pnl') AS REAL)), 0) AS realized_pnl,
              SUM(CASE WHEN decision_type='TRADE_CLOSE' THEN 1 ELSE 0 END) AS closed_count,
              SUM(CASE WHEN decision_type='TRADE_CLOSE'
                        AND CAST(json_extract(details_json, '$.net_pnl') AS REAL) > 0 THEN 1 ELSE 0 END) AS profitable_count,
              SUM(CASE WHEN decision_type='TRADE_CLOSE'
                        AND CAST(json_extract(details_json, '$.net_pnl') AS REAL) < 0 THEN 1 ELSE 0 END) AS losing_count,
              SUM(CASE WHEN decision_type='TRADE_PARTIAL_CLOSE' THEN 1 ELSE 0 END) AS partial_count
            FROM decision_logs
            WHERE decision_ts >= ?
              $endClause
              AND decision_type IN ('TRADE_CLOSE', 'TRADE_PARTIAL_CLOSE')
            """.trimIndent(),
            queryParams,
        )?.firstOrNull()
        if (row == null) {
            return "⚠️ РЕЗУЛЬТАТ ТОРГОВЛИ\n━━━━━━━━━━━━━━━━━━\nЖурнал закрытых сделок сейчас недоступен."
        }
        val total = safeFloat(row["realized_pnl"])
        val closed = safeFloat(row["closed_count"]).toInt()
        val profitable = safeFloat(row["profitable_count"]).toInt()
        val losing = safeFloat(row["losing_count"]).toInt()
        val partial = safeFloat(row["partial_count"]).toInt()
        return buildString {
            appendLine("${if (total >= 0) "📈" else "📉"} $title")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("💰 Зафиксированный результат: ${formatSignedRub(total)}")
            appendLine()
            appendLine("✅ Прибыльных: $profitable")
            appendLine("🔴 Убыточных: $losing")
            appendLine("🔄 Полностью закрыто: $closed")
            if (partial > 0) appendLine("🟡 Частичных закрытий: $partial")
            appendLine()
            appendLine("🗓️ Период: $periodLabel")
            appendLine("Открытые позиции и их текущая переоценка не учитываются.")
        }
    }

    override fun balance(): String {
        val s = broker.getBalanceSnapshot(lastPrices)
        val initial = safeFloat(s["initial_balance_rub"])
        val equity = safeFloat(s["equity_rub"] ?: s["current_balance_rub"])
        val changePct = if (initial > 0) (equity / initial - 1.0) * 100 else 0.0
        return buildString {
            appendLine("💰 СОСТОЯНИЕ СЧЁТА")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("🏁 Стартовый баланс: ${formatRub(initial)}")
            appendLine("💼 Текущий портфель: ${formatRub(equity)}")
            appendLine("💵 Свободно: ${formatRub(s["cash_rub"])}")
            appendLine("📂 В позициях: ${formatRub(s["market_value_rub"])}")
            appendLine()
            appendLine("${if (changePct >= 0) "📈" else "📉"} Изменение: ${formatPercent(changePct)}")
        }
    }

    override fun resetPerformanceBaseline(): String = kotlinx.coroutines.runBlocking {
        paperLock.withLock {
            val snap = broker.resetPerformanceBaseline(lastPrices)
            lastPerformance = snap
            val baseline = safeFloat(snap["initial_balance_rub"])
            log.info("Performance baseline reset without closing positions: {} RUB", baseline)
            """
                ✅ НОВЫЙ ПЕРИОД ОТСЧЁТА
                ━━━━━━━━━━━━━━━━━━
                🏁 Новая база: ${formatRub(baseline)}
                📂 Открытые позиции сохранены
                📈 Общий результат: ${formatSignedRub(safeFloat(snap["total_pnl_rub"]))}
                
                «Баланс» и «Статус» теперь считают результат с этого момента.
                P&L показывает только закрытые сделки за текущий день.
            """.trimIndent()
        }
    }

    override fun positions(): String {
        val rows = broker.getOpenPositions(lastPrices)
        if (rows.isEmpty()) return "🧾 ОТКРЫТЫЕ ПОЗИЦИИ\n━━━━━━━━━━━━━━━━━━\nСейчас открытых позиций нет."
        return buildString {
            appendLine("🧾 ОТКРЫТЫЕ ПОЗИЦИИ — ${rows.size}")
            appendLine("━━━━━━━━━━━━━━━━━━")
            rows.forEachIndexed { index, row ->
                val avg = safeFloat(row["avg_price"])
                val last = safeFloat(row["last_price"])
                val pnl = safeFloat(row["unrealized_pnl_rub"])
                val qty = safeFloat(row["qty"])
                val isShort = qty < 0
                val pct = if (avg > 0) {
                    if (isShort) (avg / last - 1.0) * 100 else (last / avg - 1.0) * 100
                } else 0.0
                if (index > 0) appendLine()
                appendLine("${if (pnl >= 0) "🟢" else "🔴"} ${row["symbol"]} · ${if (isShort) "SHORT" else "LONG"}")
                appendLine("📦 Количество: ${formatQty(kotlin.math.abs(qty))}")
                appendLine("💵 Средняя / сейчас: ${formatPrice(avg)} → ${formatPrice(last)} ₽")
                appendLine("💼 Стоимость: ${formatRub(row["market_value_rub"])}")
                appendLine("${if (pnl >= 0) "📈" else "📉"} Результат: ${formatSignedRub(pnl)} (${formatPercent(pct)})")
            }
        }
    }

    private suspend fun resetPaperAccount(amount: Double?): Map<String, Any> =
        operatorLock.withLock {
            paperLock.withLock {
                for (row in broker.getOpenPositions(lastPrices)) {
                    val symbol = row["symbol"].toString()
                    val qty = safeFloat(row["qty"])
                    val avg = safeFloat(row["avg_price"])
                    val latest = dataLoader.loadCandles(symbol, settings.exchange, settings.interval)
                        ?.lastClose()?.takeIf { it > 0 }
                        ?: safeFloat(row["last_price"]).takeIf { it > 0 }
                        ?: avg
                    val execution = if (qty < 0) {
                        broker.placeOrder(symbol, "CLOSE_SHORT", -qty * latest, latest)
                    } else {
                        broker.placeOrder(symbol, "SELL", qty * latest, latest)
                    }
                    if (execution != null || broker.getPosition(symbol).first == 0.0) {
                        closeTrade(symbol, executionPrice(execution, latest), "Сброс торгового счёта", qty, avg, execution)
                    }
                }
                val snap = paperBroker?.resetBalance(amount) ?: broker.resetBalance(amount)
                tradeMeta.clear()
                pendingBuy.clear()
                pendingShort.clear()
                pmStates.clear()
                pmProfiles.clear()
                lastPrices.clear()
                lastBuyBarKey.clear()
                lastEntryAttemptBarKey.clear()
                lastCloseBarKey.clear()
                lastEvaluatedCandle.clear()
                lastPerformance = snap
                snap
            }
        }

    private fun formatResetMessage(snap: Map<String, Any>) =
        """
            ✅ НОВЫЙ ОТСЧЁТ НАЧАТ
            ━━━━━━━━━━━━━━━━━━
            Стартовый баланс: ${formatRub(snap["initial_balance_rub"])}
            Позиции: закрыты и очищены
            Результат и комиссии: обнулены
            
            Все следующие показатели считаются с этого момента.
        """.trimIndent()

    override fun resetBalance(amount: Double?) = kotlinx.coroutines.runBlocking {
        formatResetMessage(resetPaperAccount(amount))
    }

    override fun setBalance(amount: Double) = resetBalance(amount)

    override fun canCloseAll(): Pair<Boolean, String> {
        if (settings.tradingMode != "paper") return false to "🛑 Только paper"
        val n = broker.getOpenPositions(lastPrices).size
        if (n == 0) return false to "🛑 Нет позиций"
        return true to "🛑 Закроем $n позиций"
    }

    override fun closeAll(): String = kotlinx.coroutines.runBlocking {
        flattenAllPaperPositions("Manual close all")
    }

    override fun setCloseAllPause(active: Boolean) { manualCloseAllPending.set(active) }

    private suspend fun flattenAllPaperPositions(reason: String): String {
        return closeAllLock.withLock {
            paperLock.withLock {
                val open = broker.getOpenPositions(lastPrices)
                if (open.isEmpty()) return@withLock "🛑 Нет позиций"
                val symbolsToClose = open.map { it["symbol"].toString().uppercase() }.toSet()
                val livePrices = runCatching { dataLoader.loadBinanceLastPrices(symbolsToClose) }.getOrDefault(emptyMap())
                var closed = 0
                var net = 0.0
                for (row in open) {
                    val sym = row["symbol"].toString()
                    val qty = safeFloat(row["qty"])
                    val avg = safeFloat(row["avg_price"])
                    val latest = livePrices[sym.uppercase()]
                        ?: safeFloat(row["last_price"]).takeIf { it > 0 }
                        ?: avg
                    val side = if (qty < 0) "CLOSE_SHORT" else "SELL"
                    val exec = broker.placeOrder(sym, side, kotlin.math.abs(qty) * latest, latest)
                    if (exec != null || broker.getPosition(sym).first == 0.0) {
                        closeTrade(sym, executionPrice(exec, latest), reason, qty, avg, exec)
                        net += exec?.netPnl ?: 0.0
                        closed++
                        trailingPositions.remove(sym)
                    }
                }
                lastPerformance = broker.performance(lastPrices)
                tradeMeta.clear()
                pendingBuy.clear()
                pendingShort.clear()
                pmStates.clear()
                pmProfiles.clear()
                lastBuyBarKey.clear()
                lastEntryAttemptBarKey.clear()
                lastCloseBarKey.clear()
                lastEvaluatedCandle.clear()
                manualCloseAllPending.set(false)
                "🛑 Закрыто $closed | net ${formatSignedRub(net)}"
            }
        }
    }

    fun close() = broker.close()
}
