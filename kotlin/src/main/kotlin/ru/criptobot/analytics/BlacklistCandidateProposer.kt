package ru.criptobot.analytics

import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import ru.criptobot.universe.BlacklistCandidate
import ru.criptobot.universe.BlacklistCandidateStore
import ru.criptobot.util.safeFloat
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds evidence-backed blacklist candidates from analytics + market volume.
 * Never applies the blacklist itself — only proposes for human review.
 *
 * Proposals stay silent until the bot has enough global practice
 * ([Settings.blacklistMinClosedTradesGlobal] / [Settings.blacklistMinActiveDays]).
 */
class BlacklistCandidateProposer(
    private val settings: Settings,
    private val repo: AnalyticsRepository,
    private val store: BlacklistCandidateStore,
) {
    private val log = LoggerFactory.getLogger(BlacklistCandidateProposer::class.java)

    data class ProposalBatch(
        val newlyProposed: List<BlacklistCandidate>,
        val refreshedPending: Int,
        val maturity: MaturityStatus,
    )

    data class MaturityStatus(
        val ready: Boolean,
        val closedTrades: Int,
        val minClosedTrades: Int,
        val activeDays: Double,
        val minActiveDays: Int,
        val buySellOutcomes: Int,
        val detail: String,
    )

    fun maturityStatus(): MaturityStatus {
        val minTrades = settings.blacklistMinClosedTradesGlobal
        val minDays = settings.blacklistMinActiveDays
        val closed = repo.fetchAll(
            """
            SELECT COUNT(*) AS n
            FROM decision_logs
            WHERE decision_type='TRADE_CLOSE'
              AND json_extract(details_json,'$.net_pnl') IS NOT NULL
            """.trimIndent(),
        ).firstOrNull()?.let { safeFloat(it["n"]).toInt() } ?: 0

        val outcomes = repo.fetchAll(
            """
            SELECT COUNT(*) AS n
            FROM signals s
            JOIN signal_outcomes o ON o.signal_id = s.signal_id
            WHERE s.side IN ('BUY','SELL')
            """.trimIndent(),
        ).firstOrNull()?.let { safeFloat(it["n"]).toInt() } ?: 0

        val firstTs = repo.fetchAll(
            """
            SELECT MIN(run_started_at) AS first_ts FROM signal_runs
            """.trimIndent(),
        ).firstOrNull()?.get("first_ts")?.toString()
        val activeDays = firstTs?.let { raw ->
            runCatching {
                ChronoUnit.HOURS.between(Instant.parse(raw), Instant.now()) / 24.0
            }.getOrDefault(0.0)
        } ?: 0.0

        val ready = closed >= minTrades && activeDays >= minDays.toDouble()
        val detail = buildString {
            append("закрытых сделок $closed/$minTrades")
            append("; дней работы ${String.format("%.1f", activeDays)}/$minDays")
            append("; BUY/SELL outcomes $outcomes")
            if (!ready) append(" — ещё рано предлагать кандидатов")
            else append(" — порог зрелости достигнут")
        }
        return MaturityStatus(ready, closed, minTrades, activeDays, minDays, outcomes, detail)
    }

    fun refresh(
        activeSymbols: Collection<String>,
        alreadyExcluded: Set<String>,
        quoteVolumes: Map<String, Double>,
    ): ProposalBatch {
        val maturity = maturityStatus()
        if (!settings.blacklistProposerEnabled) {
            return ProposalBatch(emptyList(), 0, maturity)
        }
        if (!maturity.ready) {
            log.info("Blacklist proposer idle (warmup): {}", maturity.detail)
            return ProposalBatch(emptyList(), 0, maturity)
        }

        val excluded = alreadyExcluded.map { it.uppercase() }.toSet()
        val newly = mutableListOf<BlacklistCandidate>()
        var refreshed = 0
        val since = Instant.now().minus(settings.blacklistLookbackDays.toLong(), ChronoUnit.DAYS).toString()

        fun consider(
            symbol: String,
            reasonCode: String,
            reasonText: String,
            evidence: Map<String, Any?>,
        ) {
            val sym = symbol.uppercase()
            if (sym in excluded) return
            val created = store.propose(sym, reasonCode, reasonText, evidence)
            if (created != null) newly += created else refreshed++
        }

        // Only trade-quality evidence after warmup. Low-volume / data noise alone is not enough early on.
        proposePoorOutcomes(activeSymbols, since, ::consider)
        proposeLosingTrades(since, ::consider)
        proposeLowVolumeWithLosingPractice(activeSymbols, quoteVolumes, since, ::consider)

        if (newly.isNotEmpty()) {
            log.info("Blacklist proposer created {} new candidates (refreshed={})", newly.size, refreshed)
        }
        return ProposalBatch(newly, refreshed, maturity)
    }

    private fun proposePoorOutcomes(
        activeSymbols: Collection<String>,
        since: String,
        consider: (String, String, String, Map<String, Any?>) -> Unit,
    ) {
        val minN = settings.blacklistMinObservations
        val threshold = settings.blacklistPoorOutcomePct
        val rows = repo.fetchAll(
            """
            SELECT s.ticker AS ticker,
                   COUNT(*) AS signals_total,
                   AVG(o.outcome_eod_pct) AS avg_eod_pct,
                   MIN(o.outcome_eod_pct) AS worst_eod_pct,
                   AVG(o.mae) AS avg_mae
            FROM signals s
            JOIN signal_outcomes o ON o.signal_id = s.signal_id
            WHERE s.side IN ('BUY','SELL')
              AND s.signal_ts >= ?
            GROUP BY s.ticker
            HAVING COUNT(*) >= ?
               AND AVG(o.outcome_eod_pct) <= ?
            ORDER BY avg_eod_pct ASC
            LIMIT 30
            """.trimIndent(),
            listOf(since, minN, threshold),
        )
        val active = activeSymbols.map { it.uppercase() }.toSet()
        for (row in rows) {
            val ticker = row["ticker"]?.toString()?.uppercase() ?: continue
            if (ticker !in active) continue
            consider(
                ticker,
                "POOR_OUTCOMES",
                "Средний EOD-результат сигналов стабильно отрицательный",
                mapOf(
                    "signals_total" to row["signals_total"],
                    "avg_eod_pct" to formatNum(row["avg_eod_pct"]),
                    "worst_eod_pct" to formatNum(row["worst_eod_pct"]),
                    "avg_mae" to formatNum(row["avg_mae"]),
                    "lookback_days" to settings.blacklistLookbackDays,
                    "threshold_pct" to threshold,
                ),
            )
        }
    }

    private fun proposeLosingTrades(
        since: String,
        consider: (String, String, String, Map<String, Any?>) -> Unit,
    ) {
        val minN = settings.blacklistMinObservations
        val rows = repo.fetchAll(
            """
            SELECT ticker,
                   COUNT(*) AS closes,
                   SUM(CASE WHEN CAST(json_extract(details_json,'$.net_pnl') AS REAL) < 0 THEN 1 ELSE 0 END) AS losses,
                   AVG(CAST(json_extract(details_json,'$.net_pnl') AS REAL)) AS avg_net_pnl,
                   SUM(CAST(json_extract(details_json,'$.net_pnl') AS REAL)) AS sum_net_pnl
            FROM decision_logs
            WHERE decision_type='TRADE_CLOSE'
              AND decision_ts >= ?
              AND json_extract(details_json,'$.net_pnl') IS NOT NULL
            GROUP BY ticker
            HAVING COUNT(*) >= ?
               AND AVG(CAST(json_extract(details_json,'$.net_pnl') AS REAL)) < 0
               AND SUM(CASE WHEN CAST(json_extract(details_json,'$.net_pnl') AS REAL) < 0 THEN 1 ELSE 0 END) * 1.0 / COUNT(*) >= 0.75
            ORDER BY avg_net_pnl ASC
            LIMIT 30
            """.trimIndent(),
            listOf(since, minN),
        )
        for (row in rows) {
            val ticker = row["ticker"]?.toString()?.uppercase() ?: continue
            consider(
                ticker,
                "LOSING_TRADES",
                "Большинство закрытых сделок убыточны",
                mapOf(
                    "closes" to row["closes"],
                    "losses" to row["losses"],
                    "avg_net_pnl" to formatNum(row["avg_net_pnl"]),
                    "sum_net_pnl" to formatNum(row["sum_net_pnl"]),
                    "lookback_days" to settings.blacklistLookbackDays,
                ),
            )
        }
    }

    /** Low volume alone is not enough — only with losing trade practice. */
    private fun proposeLowVolumeWithLosingPractice(
        activeSymbols: Collection<String>,
        quoteVolumes: Map<String, Double>,
        since: String,
        consider: (String, String, String, Map<String, Any?>) -> Unit,
    ) {
        val softFloor = settings.blacklistLowVolumeUsdt
        if (softFloor <= 0) return
        val minN = settings.blacklistMinObservations
        val losers = repo.fetchAll(
            """
            SELECT ticker,
                   COUNT(*) AS closes,
                   AVG(CAST(json_extract(details_json,'$.net_pnl') AS REAL)) AS avg_net_pnl
            FROM decision_logs
            WHERE decision_type='TRADE_CLOSE'
              AND decision_ts >= ?
              AND json_extract(details_json,'$.net_pnl') IS NOT NULL
            GROUP BY ticker
            HAVING COUNT(*) >= ?
               AND AVG(CAST(json_extract(details_json,'$.net_pnl') AS REAL)) < 0
            """.trimIndent(),
            listOf(since, minN),
        ).associate {
            (it["ticker"]?.toString()?.uppercase() ?: "") to it
        }.filterKeys { it.isNotEmpty() }

        val active = activeSymbols.map { it.uppercase() }.toSet()
        for ((sym, row) in losers) {
            if (sym !in active) continue
            val qv = quoteVolumes[sym] ?: continue
            if (qv >= softFloor) continue
            consider(
                sym,
                "LOW_VOLUME_AND_LOSSES",
                "Низкий 24h объём и отрицательная практика сделок",
                mapOf(
                    "quote_volume_24h_usdt" to formatNum(qv),
                    "soft_floor_usdt" to softFloor,
                    "closes" to row["closes"],
                    "avg_net_pnl" to formatNum(row["avg_net_pnl"]),
                    "lookback_days" to settings.blacklistLookbackDays,
                ),
            )
        }
    }

    private fun formatNum(value: Any?): String {
        val n = safeFloat(value, Double.NaN)
        if (n.isNaN()) return value?.toString() ?: "n/a"
        return String.format("%.4f", n)
    }
}
