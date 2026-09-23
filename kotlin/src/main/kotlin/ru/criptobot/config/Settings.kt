package ru.criptobot.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Path
import kotlin.io.path.exists

data class Settings(
    val projectRoot: Path,
    val seedSymbols: List<String>,
    val universeSource: String,
    val exchange: String,
    val interval: String,
    val buyThreshold: Double,
    val sellThreshold: Double,
    val positionSizeRub: Double,
    val minOrderRub: Double,
    val maxOpenPositions: Int,
    val maxOpenShorts: Int,
    val shortEnabled: Boolean,
    val shortMlProbMax: Double,
    val stopLossPct: Double,
    val takeProfitPct: Double,
    val trailingTpEnabled: Boolean,
    val trailingTpCallbackPct: Double,
    val positionMonitorIntervalMs: Long,
    val trailingStateFile: Path,
    val reentryCooldownStateFile: Path,
    val stopLossReentryCooldownMinutes: Long,
    val profitableExitReentryCooldownMinutes: Long,
    val minPrice: Double,
    val maxPrice: Double,
    val tradingMode: String,
    val paperInitialBalanceRub: Double,
    val paperStateFile: Path,
    val paperBalanceStateFile: Path,
    val paperCommissionRate: Double,
    val paperCommissionMinRub: Double,
    val paperIncludeCommission: Boolean,
    val binanceApiBaseUrl: String,
    val binanceApiKey: String,
    val binanceApiSecret: String,
    val binanceUniverseQuote: String,
    val binanceUniverseLimit: Int,
    val binanceUniverseCacheFile: Path,
    val binanceMinQuoteVolume: Double,
    val universeRefreshCycles: Int,
    val universeOverridesFile: Path,
    val blacklistCandidatesFile: Path,
    val blacklistProposerEnabled: Boolean,
    val blacklistLookbackDays: Int,
    val blacklistMinObservations: Int,
    val blacklistPoorOutcomePct: Double,
    val blacklistLowVolumeUsdt: Double,
    val blacklistRefreshCycles: Int,
    val blacklistMinClosedTradesGlobal: Int,
    val blacklistMinActiveDays: Int,
    val blacklistNotifyTelegram: Boolean,
    val blacklistLiquidityFailCount: Int,
    val openaiApiKey: String,
    val openaiModel: String,
    val openaiRebalanceCycles: Int,
    val enableAiRiskFilter: Boolean,
    val strategyStateFile: Path,
    val telegramBotToken: String,
    val telegramChatId: Long?,
    val telegramNotifyHold: Boolean,
    val pollSeconds: Int,
    val tvRequestDelaySec: Double,
    val strategyMode: String,
    val mlEnabled: Boolean,
    val mlProbThreshold: Double,
    val analyticsEnabled: Boolean,
    val analyticsDbPath: Path,
    val analyticsStrategyVersion: String,
    val analyticsLogHolds: Boolean,
    val analyticsOutcomeEvalEnabled: Boolean,
    val analyticsDbMaxMb: Int,
    val analyticsDbTargetMb: Int,
    val analyticsDbMaintenanceCycles: Int,
    val analyticsDbMinRetentionDays: Int,
    val positionMgmtMode: String,
    val adaptiveMode: String,
    val adaptiveStateFile: Path,
    val adaptiveRuntimeStateFile: Path,
    val adaptiveUseDbVolumeLoop: Boolean,
    val adaptiveLookbackDays: Int,
    val adaptiveRefreshCycles: Int,
    val adaptiveMinObservations: Int,
    val adaptiveNegativeOutcomePct: Double,
    val adaptivePositiveOutcomePct: Double,
    val adaptiveMaxThresholdDeltaFrac: Double,
    val paperHighVolumeFilterEnabled: Boolean,
    val paperHighVolumeRatioLimit: Double,
    val paperHighVolumeAction: String,
    val paperHighVolumeMlBump: Double,
    val paperBuyBarConfirmation: Boolean,
    val trendGateMode: String,
) {
    companion object {
        fun load(projectRoot: Path = Path.of(".").toAbsolutePath().normalize()): Settings {
            val envRoot = when {
                projectRoot.resolve(".env").exists() -> projectRoot
                projectRoot.parent?.resolve(".env")?.exists() == true -> projectRoot.parent!!
                else -> projectRoot
            }
            val dotenv = if (envRoot.resolve(".env").exists()) {
                Dotenv.configure().directory(envRoot.toString()).ignoreIfMissing().load()
            } else {
                Dotenv.configure().ignoreIfMissing().load()
            }

            fun get(key: String, default: String = ""): String =
                dotenv.get(key)?.trim().orEmpty().ifEmpty { System.getenv(key)?.trim().orEmpty() }
                    .ifEmpty { default }

            fun getBool(key: String, default: Boolean = false): Boolean {
                val v = get(key).lowercase()
                if (v.isEmpty()) return default
                return v in setOf("1", "true", "yes", "on")
            }

            fun path(key: String, default: String): Path {
                val raw = get(key, default)
                val p = envRoot.resolve(raw)
                return if (raw.startsWith("/")) Path.of(raw) else p.normalize()
            }

            val chatRaw = get("TELEGRAM_CHAT_ID")
            return Settings(
                projectRoot = envRoot,
                seedSymbols = get("TV_SYMBOLS", "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT")
                    .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() },
                universeSource = get("UNIVERSE_SOURCE", "binance").lowercase(),
                exchange = get("TV_EXCHANGE", "BINANCE"),
                interval = get("TV_INTERVAL", "1h"),
                buyThreshold = get("BUY_THRESHOLD", "0.55").toDoubleOrNull() ?: 0.55,
                sellThreshold = get("SELL_THRESHOLD", "-0.55").toDoubleOrNull() ?: -0.55,
                positionSizeRub = get("POSITION_SIZE_USDT", get("POSITION_SIZE_RUB", "100")).toDoubleOrNull() ?: 100.0,
                minOrderRub = get("MIN_ORDER_USDT", get("MIN_ORDER_RUB", "10")).toDoubleOrNull()?.coerceAtLeast(0.0) ?: 10.0,
                maxOpenPositions = get("MAX_OPEN_POSITIONS", "8").toIntOrNull()?.coerceAtLeast(0) ?: 8,
                maxOpenShorts = get("MAX_OPEN_SHORTS", "3").toIntOrNull()?.coerceAtLeast(0) ?: 3,
                shortEnabled = getBool("SHORT_ENABLED", true),
                shortMlProbMax = get("SHORT_ML_PROB_MAX", "0.40").toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.40,
                stopLossPct = get("STOP_LOSS_PCT", "0.03").toDoubleOrNull() ?: 0.03,
                takeProfitPct = get("TAKE_PROFIT_PCT", "0.06").toDoubleOrNull() ?: 0.06,
                trailingTpEnabled = getBool("TRAILING_TP_ENABLED", true),
                trailingTpCallbackPct = get("TRAILING_TP_CALLBACK_PCT", "0.015")
                    .toDoubleOrNull()?.coerceIn(0.001, 0.20) ?: 0.015,
                positionMonitorIntervalMs = get("POSITION_MONITOR_INTERVAL_MS", "1000")
                    .toLongOrNull()?.coerceIn(250L, 60_000L) ?: 1_000L,
                trailingStateFile = path("TRAILING_STATE_FILE", "trailing_positions.json"),
                reentryCooldownStateFile = path("REENTRY_COOLDOWN_STATE_FILE", "reentry_cooldowns.tsv"),
                stopLossReentryCooldownMinutes = get("STOP_LOSS_REENTRY_COOLDOWN_MINUTES", "240")
                    .toLongOrNull()?.coerceIn(0L, 10_080L) ?: 240L,
                profitableExitReentryCooldownMinutes = get("PROFITABLE_EXIT_REENTRY_COOLDOWN_MINUTES", "60")
                    .toLongOrNull()?.coerceIn(0L, 1_440L) ?: 60L,
                minPrice = get("MIN_PRICE", "0.00000001").toDoubleOrNull() ?: 0.00000001,
                maxPrice = get("MAX_PRICE", "10000000").toDoubleOrNull() ?: 10_000_000.0,
                tradingMode = get("TRADING_MODE", "paper").lowercase(),
                paperInitialBalanceRub = get("PAPER_INITIAL_BALANCE_USDT", get("PAPER_INITIAL_BALANCE_RUB", "1000"))
                    .toDoubleOrNull() ?: 1_000.0,
                paperStateFile = path("PAPER_STATE_FILE", "paper_state.json"),
                paperBalanceStateFile = path("PAPER_BALANCE_STATE_FILE", "paper_balance_state.json"),
                paperCommissionRate = get("PAPER_COMMISSION_RATE", "0.001").toDoubleOrNull() ?: 0.001,
                paperCommissionMinRub = get("PAPER_COMMISSION_MIN_USDT", get("PAPER_COMMISSION_MIN_RUB", "0"))
                    .toDoubleOrNull() ?: 0.0,
                paperIncludeCommission = getBool("PAPER_INCLUDE_COMMISSION", true),
                binanceApiBaseUrl = get("BINANCE_API_BASE_URL", "https://api.binance.com"),
                binanceApiKey = get("BINANCE_API_KEY"),
                binanceApiSecret = get("BINANCE_API_SECRET"),
                binanceUniverseQuote = get("BINANCE_UNIVERSE_QUOTE", "USDT").uppercase(),
                // 0 = unlimited (all matching USDT spot pairs)
                binanceUniverseLimit = get("BINANCE_UNIVERSE_LIMIT", "0").toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                binanceUniverseCacheFile = path("BINANCE_UNIVERSE_CACHE_FILE", "binance_universe_cache.json"),
                // 0 = do not hard-filter universe by volume (soft floor used by blacklist proposer)
                binanceMinQuoteVolume = get("BINANCE_MIN_QUOTE_VOLUME", "0").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                universeRefreshCycles = get("UNIVERSE_REFRESH_CYCLES", "30").toIntOrNull() ?: 30,
                universeOverridesFile = path("UNIVERSE_OVERRIDES_FILE", "universe_overrides.json"),
                blacklistCandidatesFile = path("BLACKLIST_CANDIDATES_FILE", "blacklist_candidates.json"),
                blacklistProposerEnabled = getBool("BLACKLIST_PROPOSER_ENABLED", true),
                blacklistLookbackDays = get("BLACKLIST_LOOKBACK_DAYS", "14").toIntOrNull()?.coerceIn(1, 90) ?: 14,
                // Per-symbol sample before a coin can become a candidate
                blacklistMinObservations = get("BLACKLIST_MIN_OBSERVATIONS", "15").toIntOrNull()?.coerceAtLeast(5) ?: 15,
                blacklistPoorOutcomePct = get("BLACKLIST_POOR_OUTCOME_PCT", "-1.0").toDoubleOrNull() ?: -1.0,
                blacklistLowVolumeUsdt = get("BLACKLIST_LOW_VOLUME_USDT", "200000").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 200_000.0,
                blacklistRefreshCycles = get("BLACKLIST_REFRESH_CYCLES", "30").toIntOrNull()?.coerceAtLeast(1) ?: 30,
                // Global warmup: do not propose anyone until enough practice exists
                blacklistMinClosedTradesGlobal = get("BLACKLIST_MIN_CLOSED_TRADES_GLOBAL", "40")
                    .toIntOrNull()?.coerceAtLeast(5) ?: 40,
                blacklistMinActiveDays = get("BLACKLIST_MIN_ACTIVE_DAYS", "3").toIntOrNull()?.coerceAtLeast(1) ?: 3,
                blacklistNotifyTelegram = getBool("BLACKLIST_NOTIFY_TELEGRAM", false),
                blacklistLiquidityFailCount = get("BLACKLIST_LIQUIDITY_FAIL_COUNT", "3")
                    .toIntOrNull()?.coerceAtLeast(2) ?: 3,
                openaiApiKey = get("OPENAI_API_KEY"),
                openaiModel = get("OPENAI_MODEL", "gpt-4o-mini"),
                openaiRebalanceCycles = get("OPENAI_REBALANCE_CYCLES", "20").toIntOrNull() ?: 20,
                enableAiRiskFilter = getBool("ENABLE_AI_RISK_FILTER", false),
                strategyStateFile = path("STRATEGY_STATE_FILE", "strategy_state.json"),
                telegramBotToken = get("TELEGRAM_BOT_TOKEN"),
                telegramChatId = chatRaw.toLongOrNull(),
                telegramNotifyHold = getBool("TELEGRAM_NOTIFY_HOLD", false),
                pollSeconds = get("POLL_SECONDS", "60").toIntOrNull() ?: 60,
                tvRequestDelaySec = get("TV_REQUEST_DELAY_SEC", "0.2").toDoubleOrNull() ?: 0.2,
                strategyMode = get("STRATEGY_MODE", "supertrend_ml").lowercase(),
                mlEnabled = getBool("ML_ENABLED", true),
                mlProbThreshold = get("ML_PROB_THRESHOLD", "0.10").toDoubleOrNull() ?: 0.10,
                analyticsEnabled = getBool("ANALYTICS_ENABLED", true),
                analyticsDbPath = path("ANALYTICS_DB_PATH", "analytics.db"),
                analyticsStrategyVersion = get("ANALYTICS_STRATEGY_VERSION", "binance-paper"),
                analyticsLogHolds = getBool("ANALYTICS_LOG_HOLDS", true),
                analyticsOutcomeEvalEnabled = getBool("ANALYTICS_OUTCOME_EVAL_ENABLED", true),
                analyticsDbMaxMb = get("ANALYTICS_DB_MAX_MB", "10240").toIntOrNull()?.coerceAtLeast(32) ?: 10240,
                analyticsDbTargetMb = get("ANALYTICS_DB_TARGET_MB", "9216").toIntOrNull()?.coerceAtLeast(16) ?: 9216,
                analyticsDbMaintenanceCycles = get("ANALYTICS_DB_MAINTENANCE_CYCLES", "5").toIntOrNull()?.coerceAtLeast(1) ?: 5,
                analyticsDbMinRetentionDays = get("ANALYTICS_DB_MIN_RETENTION_DAYS", "90").toIntOrNull()?.coerceAtLeast(7) ?: 90,
                positionMgmtMode = get("POSITION_MGMT_MODE", "off").lowercase(),
                adaptiveMode = get("ADAPTIVE_MODE", "paper").lowercase(),
                adaptiveStateFile = path("ADAPTIVE_STATE_FILE", "adaptive_state.json"),
                adaptiveRuntimeStateFile = path("ADAPTIVE_RUNTIME_STATE_PATH", "adaptive_runtime_state.json"),
                adaptiveUseDbVolumeLoop = getBool("ADAPTIVE_USE_DB_VOLUME_LOOP", true),
                adaptiveLookbackDays = get("ADAPTIVE_LOOKBACK_DAYS", "14").toIntOrNull() ?: 14,
                adaptiveRefreshCycles = get("ADAPTIVE_REFRESH_CYCLES", "5").toIntOrNull() ?: 5,
                adaptiveMinObservations = get("ADAPTIVE_MIN_OBSERVATIONS", "5").toIntOrNull() ?: 5,
                adaptiveNegativeOutcomePct = get("ADAPTIVE_NEGATIVE_OUTCOME_PCT", "-0.25").toDoubleOrNull() ?: -0.25,
                adaptivePositiveOutcomePct = get("ADAPTIVE_POSITIVE_OUTCOME_PCT", "0.05").toDoubleOrNull() ?: 0.05,
                adaptiveMaxThresholdDeltaFrac = get("ADAPTIVE_MAX_THRESHOLD_DELTA_FRAC", "0.15").toDoubleOrNull() ?: 0.15,
                paperHighVolumeFilterEnabled = getBool("PAPER_ENABLE_HIGH_VOLUME_ENTRY_FILTER", false),
                paperHighVolumeRatioLimit = get("PAPER_HIGH_VOLUME_RATIO_LIMIT", "2.0").toDoubleOrNull() ?: 2.0,
                paperHighVolumeAction = get("PAPER_HIGH_VOLUME_ACTION", "block").lowercase(),
                paperHighVolumeMlBump = get("PAPER_HIGH_VOLUME_ML_THRESHOLD_REL_BUMP", "0.25").toDoubleOrNull() ?: 0.25,
                paperBuyBarConfirmation = getBool("PAPER_BUY_BAR_CONFIRMATION", true),
                trendGateMode = get("TREND_GATE_MODE", "supertrend").lowercase().let {
                    if (it == "supertrend") "supertrend" else "strict"
                },
            )
        }
    }
}
