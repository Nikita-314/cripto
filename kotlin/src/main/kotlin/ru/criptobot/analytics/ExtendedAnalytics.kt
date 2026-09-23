package ru.criptobot.analytics

import ru.criptobot.config.Settings
import ru.criptobot.data.CandleSeries
import ru.criptobot.util.isoNow
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class AnalyticsRepository(private val settings: Settings) {
    private val db = AnalyticsDb(settings)
    private val signalLogger = SignalLogger(db)

    fun analyticsDb(): AnalyticsDb = db
    fun logger(): SignalLogger = signalLogger

    fun fetchAll(sql: String, params: List<Any?> = emptyList()): List<Map<String, Any?>> {
        val path = settings.analyticsDbPath.toAbsolutePath().toString()
        return DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, v ->
                    when (v) {
                        null -> ps.setObject(i + 1, null)
                        is Int -> ps.setInt(i + 1, v)
                        is Double -> ps.setDouble(i + 1, v)
                        else -> ps.setString(i + 1, v.toString())
                    }
                }
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) })
                        }
                    }
                }
            }
        }
    }
}

class OutcomeEvaluator(private val repo: AnalyticsRepository) {
    private val log = org.slf4j.LoggerFactory.getLogger(OutcomeEvaluator::class.java)

    suspend fun evaluatePending(loadCandles: suspend (String) -> CandleSeries?, limit: Int = 200): Int {
        val missingOutcome = listOf("price_after_5m", "price_after_15m", "price_after_60m", "price_eod",
            "mfe", "mae", "outcome_5m_pct", "outcome_15m_pct", "outcome_60m_pct", "outcome_eod_pct")
            .joinToString(" OR ") { "o.$it IS NULL" }
        val rows = repo.fetchAll(
            """
            SELECT s.signal_id, s.ticker, s.signal_ts, s.entry_price
            FROM signals s LEFT JOIN signal_outcomes o ON o.signal_id = s.signal_id
            WHERE s.entry_price IS NOT NULL AND (
              s.side IN ('BUY','SELL') OR (s.side IN ('HOLD','BLOCK') AND s.reason_code IN (
                'BLOCK_ML','BLOCK_TREND','BLOCK_HIGH_VOLUME_RATIO','BLOCK_ADAPTIVE','BLOCK_ADAPTIVE_BUCKET'))
            ) AND (o.signal_id IS NULL OR (($missingOutcome) AND
              (o.evaluated_at IS NULL OR datetime(replace(substr(o.evaluated_at,1,19),'T',' ')) < datetime('now','-30 minutes'))))
            ORDER BY s.signal_ts DESC LIMIT ?
            """.trimIndent(), listOf(limit),
        )
        var updated = 0
        for (row in rows) {
            try {
                val outcome = evaluateSignal(row, loadCandles) ?: continue
                repo.logger().upsertSignalOutcome(row["signal_id"].toString(), outcome)
                updated++
            } catch (e: Exception) {
                log.warn("Outcome eval failed {}: {}", row["signal_id"], e.message)
            }
        }
        return updated
    }

    private suspend fun evaluateSignal(row: Map<String, Any?>, loadCandles: suspend (String) -> CandleSeries?): Map<String, Any?>? {
        val ts = parseTs(row["signal_ts"]?.toString() ?: return null) ?: return null
        val ticker = row["ticker"]?.toString()?.uppercase() ?: return null
        val entry = (row["entry_price"] as? Number)?.toDouble() ?: return null
        val series = loadCandles(ticker) ?: return null
        val candles = series.candles.filter { it.time != null }
        if (candles.isEmpty()) return null
        fun priceAfter(minutes: Long): Double? =
            candles.firstOrNull { it.time!! >= ts.plus(minutes, ChronoUnit.MINUTES) }?.close
        val p5 = priceAfter(5); val p15 = priceAfter(15); val p60 = priceAfter(60)
        val signalDate = ts.atOffset(ZoneOffset.UTC).toLocalDate()
        val sameDay = candles.filter {
            it.time!!.atOffset(ZoneOffset.UTC).toLocalDate() == signalDate && it.time!! >= ts
        }
        val dayFinished = signalDate.isBefore(Instant.now().atOffset(ZoneOffset.UTC).toLocalDate())
        val peod = sameDay.lastOrNull()?.close?.takeIf { dayFinished }
        val mfe = sameDay.maxOfOrNull { it.high }?.takeIf { dayFinished }?.let { (it / entry - 1) * 100 }
        val mae = sameDay.minOfOrNull { it.low }?.takeIf { dayFinished }?.let { (it / entry - 1) * 100 }
        fun pct(v: Double?) = if (v != null && entry != 0.0) (v / entry - 1) * 100 else null
        return mapOf(
            "price_after_5m" to p5, "price_after_15m" to p15, "price_after_60m" to p60, "price_eod" to peod,
            "mfe" to mfe, "mae" to mae,
            "outcome_5m_pct" to pct(p5), "outcome_15m_pct" to pct(p15),
            "outcome_60m_pct" to pct(p60), "outcome_eod_pct" to pct(peod),
        )
    }

    private fun parseTs(raw: String): Instant? = runCatching {
        when {
            raw.endsWith("Z") -> Instant.parse(raw)
            raw.contains("+") -> Instant.parse(raw)
            else -> Instant.parse("${raw.replace(" ", "T")}Z")
        }
    }.getOrNull()
}

object TradeSignalId {
    fun resolve(repo: AnalyticsRepository, ticker: String, tradeMeta: Map<String, Any?>?): Pair<String?, String?> {
        tradeMeta?.get("signal_id")?.toString()?.takeIf { it.isNotBlank() }?.let { return it to null }
        val row = repo.fetchAll(
            """SELECT d.signal_id FROM decision_logs d WHERE d.ticker=? AND d.decision_type='TRADE_OPEN'
               AND d.signal_id IS NOT NULL AND NOT EXISTS (
                 SELECT 1 FROM decision_logs c WHERE c.signal_id=d.signal_id AND c.decision_type='TRADE_CLOSE')
               ORDER BY d.decision_ts DESC LIMIT 1""", listOf(ticker),
        ).firstOrNull()
        row?.get("signal_id")?.toString()?.let { return it to null }
        val row2 = repo.fetchAll(
            """SELECT p.signal_id FROM paper_trade_links p WHERE p.ticker=? AND p.side='BUY'
               AND p.signal_id IS NOT NULL ORDER BY p.event_ts DESC LIMIT 1""", listOf(ticker),
        ).firstOrNull()
        row2?.get("signal_id")?.toString()?.let { return it to null }
        return null to if (tradeMeta == null) "trade_meta_missing_after_restart" else "signal_id_unrecoverable"
    }
}

class PaperTradeMapper(private val logger: SignalLogger) {
    fun mapOpen(signalId: String?, localTradeId: String?, ticker: String, qty: Double, price: Double, comment: String = "") =
        logger.linkPaperTrade(signalId, localTradeId, ticker, "BUY", qty, price, comment)

    fun mapClose(signalId: String?, localTradeId: String?, ticker: String, qty: Double, price: Double, comment: String = "") =
        logger.linkPaperTrade(signalId, localTradeId, ticker, "SELL", qty, price, comment)
}
