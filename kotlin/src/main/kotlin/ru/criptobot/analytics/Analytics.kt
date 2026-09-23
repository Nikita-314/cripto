package ru.criptobot.analytics

import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import ru.criptobot.util.isoNow
import ru.criptobot.util.toJsonString
import java.sql.DriverManager
import java.util.UUID

class AnalyticsDb(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(AnalyticsDb::class.java)
    private val path = settings.analyticsDbPath.toAbsolutePath().toString()

    init {
        ensureSchema()
        log.info("Analytics DB: {}", path)
    }

    private fun connect() = DriverManager.getConnection("jdbc:sqlite:$path").also { it.autoCommit = true }

    private fun ensureSchema() {
        val statements = listOf(
            """CREATE TABLE IF NOT EXISTS signal_runs (
              run_id TEXT PRIMARY KEY, strategy_code TEXT NOT NULL, strategy_version TEXT NOT NULL,
              run_started_at TEXT NOT NULL, run_finished_at TEXT, universe_size INTEGER,
              tradable_universe_size INTEGER, instruments_checked INTEGER, signals_found INTEGER,
              run_status TEXT, comment TEXT)""",
            """CREATE TABLE IF NOT EXISTS signals (
              signal_id TEXT PRIMARY KEY, run_id TEXT, ticker TEXT NOT NULL, signal_ts TEXT NOT NULL,
              strategy_code TEXT, strategy_version TEXT, side TEXT, entry_price REAL, stop_price REAL,
              target_price REAL, confidence_score REAL, reason_code TEXT, reason_text TEXT,
              feature_snapshot_json TEXT, model_snapshot_json TEXT, market_regime TEXT,
              execution_mode TEXT, status TEXT, created_at TEXT, updated_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS decision_logs (
              decision_id TEXT PRIMARY KEY, run_id TEXT, signal_id TEXT, ticker TEXT NOT NULL,
              decision_ts TEXT NOT NULL, decision_type TEXT NOT NULL, decision_label TEXT NOT NULL,
              reason_code TEXT, reason_text TEXT, details_json TEXT, created_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS model_inference_logs (
              inference_id TEXT PRIMARY KEY, signal_id TEXT, run_id TEXT, ticker TEXT,
              model_type TEXT NOT NULL, model_version TEXT, inference_ts TEXT,
              input_features_json TEXT, raw_output_json TEXT, decision_label TEXT,
              confidence_score REAL, action_recommendation TEXT,
              model_used_in_final_decision INTEGER, created_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS signal_outcomes (
              outcome_id TEXT PRIMARY KEY, signal_id TEXT NOT NULL UNIQUE,
              price_after_5m REAL, price_after_15m REAL, price_after_60m REAL, price_eod REAL,
              mfe REAL, mae REAL, outcome_5m_pct REAL, outcome_15m_pct REAL,
              outcome_60m_pct REAL, outcome_eod_pct REAL, evaluated_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS paper_trade_links (
              link_id TEXT PRIMARY KEY, signal_id TEXT, local_trade_id TEXT, ticker TEXT NOT NULL,
              side TEXT, event_ts TEXT, qty REAL, price REAL, paper_position_id TEXT, comment TEXT)""",
            """CREATE TABLE IF NOT EXISTS adaptive_actions (
              action_id TEXT PRIMARY KEY, action_ts TEXT NOT NULL, mode TEXT NOT NULL, scope TEXT NOT NULL,
              bucket_key TEXT, parameter_name TEXT NOT NULL, old_value REAL, new_value REAL,
              confidence_score REAL, sample_size INTEGER, action_status TEXT NOT NULL, reason_text TEXT,
              metrics_json TEXT, window_summary_json TEXT, created_at TEXT NOT NULL,
              applied INTEGER DEFAULT 1, reverted INTEGER DEFAULT 0, evaluation_status TEXT DEFAULT 'n/a')""",
            "CREATE INDEX IF NOT EXISTS idx_signal_runs_started ON signal_runs(run_started_at)",
            "CREATE INDEX IF NOT EXISTS idx_signals_ts ON signals(signal_ts)",
            "CREATE INDEX IF NOT EXISTS idx_signals_ticker_ts ON signals(ticker, signal_ts)",
            "CREATE INDEX IF NOT EXISTS idx_signals_status ON signals(status)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_ts ON decision_logs(decision_ts)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_signal ON decision_logs(signal_id)",
            "CREATE INDEX IF NOT EXISTS idx_decisions_trade_lookup ON decision_logs(ticker, decision_type, decision_ts)",
            "CREATE INDEX IF NOT EXISTS idx_inferences_ts ON model_inference_logs(inference_ts)",
            "CREATE INDEX IF NOT EXISTS idx_inferences_signal ON model_inference_logs(signal_id)",
            "CREATE INDEX IF NOT EXISTS idx_paper_links_lookup ON paper_trade_links(ticker, side, event_ts)",
            "CREATE INDEX IF NOT EXISTS idx_adaptive_actions_ts ON adaptive_actions(action_ts)",
        )
        connect().use { conn ->
            conn.createStatement().use { st ->
                statements.forEach { st.execute(it) }
            }
        }
    }

    fun execute(sql: String, params: List<Any?> = emptyList()) {
        connect().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, v ->
                    when (v) {
                        null -> ps.setObject(i + 1, null)
                        is Int -> ps.setInt(i + 1, v)
                        is Long -> ps.setLong(i + 1, v)
                        is Double -> ps.setDouble(i + 1, v)
                        is Boolean -> ps.setInt(i + 1, if (v) 1 else 0)
                        else -> ps.setString(i + 1, v.toString())
                    }
                }
                ps.executeUpdate()
            }
        }
    }

    fun fetchOne(sql: String, params: List<Any?> = emptyList()): Map<String, Any?>? {
        connect().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, v -> ps.setString(i + 1, v?.toString()) }
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val meta = rs.metaData
                    return (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getObject(it) }
                }
            }
        }
    }
}

class SignalLogger(private val db: AnalyticsDb) {
    fun recoverInterruptedRuns(): Int {
        val stale = db.fetchOne(
            "SELECT COUNT(*) AS count FROM signal_runs WHERE run_status='RUNNING' AND run_finished_at IS NULL"
        )
        val count = (stale?.get("count") as? Number)?.toInt() ?: 0
        if (count == 0) return 0
        db.execute(
            """UPDATE signal_runs SET run_finished_at=?, run_status='INTERRUPTED',
               comment=CASE WHEN comment IS NULL OR comment='' THEN 'process_restarted'
               ELSE comment || '; process_restarted' END
               WHERE run_status='RUNNING' AND run_finished_at IS NULL""",
            listOf(isoNow()),
        )
        return count
    }

    fun startRun(strategyCode: String, strategyVersion: String, universeSize: Int, comment: String = ""): String {
        val runId = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO signal_runs (run_id, strategy_code, strategy_version, run_started_at,
               universe_size, tradable_universe_size, instruments_checked, signals_found, run_status, comment)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(runId, strategyCode, strategyVersion, isoNow(), universeSize, universeSize, 0, 0, "RUNNING", comment),
        )
        return runId
    }

    fun finishRun(runId: String, instrumentsChecked: Int, signalsFound: Int, runStatus: String, comment: String) {
        db.execute(
            """UPDATE signal_runs SET run_finished_at=?, instruments_checked=?, signals_found=?,
               run_status=?, comment=? WHERE run_id=?""",
            listOf(isoNow(), instrumentsChecked, signalsFound, runStatus, comment, runId),
        )
    }

    fun logSignal(
        runId: String?, ticker: String, strategyCode: String, strategyVersion: String,
        side: String, entryPrice: Double?, stopPrice: Double?, targetPrice: Double?,
        confidenceScore: Double?, reasonCode: String?, reasonText: String?,
        featureSnapshot: Map<String, Any?>, modelSnapshot: Map<String, Any?>,
        marketRegime: String?, executionMode: String, status: String, signalTs: String = isoNow(),
    ): String {
        val signalId = UUID.randomUUID().toString()
        val now = isoNow()
        db.execute(
            """INSERT INTO signals (signal_id, run_id, ticker, signal_ts, strategy_code, strategy_version,
               side, entry_price, stop_price, target_price, confidence_score, reason_code, reason_text,
               feature_snapshot_json, model_snapshot_json, market_regime, execution_mode, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(signalId, runId, ticker, signalTs, strategyCode, strategyVersion, side,
                entryPrice, stopPrice, targetPrice, confidenceScore, reasonCode, reasonText,
                toJsonString(featureSnapshot), toJsonString(modelSnapshot), marketRegime, executionMode, status, now, now),
        )
        return signalId
    }

    fun updateSignalStatus(signalId: String?, status: String) {
        if (signalId.isNullOrBlank()) return
        db.execute("UPDATE signals SET status=?, updated_at=? WHERE signal_id=?", listOf(status, isoNow(), signalId))
    }

    fun logDecision(
        runId: String?, signalId: String?, ticker: String, decisionType: String, decisionLabel: String,
        reasonCode: String?, reasonText: String?, details: Map<String, Any?>, decisionTs: String = isoNow(),
    ): String {
        val id = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO decision_logs (decision_id, run_id, signal_id, ticker, decision_ts,
               decision_type, decision_label, reason_code, reason_text, details_json, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(id, runId, signalId, ticker, decisionTs, decisionType, decisionLabel,
                reasonCode, reasonText, toJsonString(details), isoNow()),
        )
        return id
    }

    fun logModelInference(
        runId: String?, signalId: String?, ticker: String, modelVersion: String,
        inputFeatures: Map<String, Any?>, rawOutput: Map<String, Any?>, decisionLabel: String,
        confidenceScore: Double?, actionRecommendation: String,
    ) {
        val id = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO model_inference_logs (inference_id, signal_id, run_id, ticker, model_type,
               model_version, inference_ts, input_features_json, raw_output_json, decision_label,
               confidence_score, action_recommendation, model_used_in_final_decision, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(id, signalId, runId, ticker, "classifier", modelVersion, isoNow(),
                toJsonString(inputFeatures), toJsonString(rawOutput), decisionLabel,
                confidenceScore, actionRecommendation, 1, isoNow()),
        )
    }

    fun linkPaperTrade(signalId: String?, localTradeId: String?, ticker: String, side: String, qty: Double, price: Double, comment: String = "") {
        val id = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO paper_trade_links (link_id, signal_id, local_trade_id, ticker, side, event_ts, qty, price, comment)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(id, signalId, localTradeId, ticker, side, isoNow(), qty, price, comment),
        )
    }

    fun upsertSignalOutcome(signalId: String, outcome: Map<String, Any?>) {
        val id = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO signal_outcomes (outcome_id, signal_id, price_after_5m, price_after_15m, price_after_60m,
               price_eod, mfe, mae, outcome_5m_pct, outcome_15m_pct, outcome_60m_pct, outcome_eod_pct, evaluated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(signal_id) DO UPDATE SET
               price_after_5m=excluded.price_after_5m, price_after_15m=excluded.price_after_15m,
               price_after_60m=excluded.price_after_60m, price_eod=excluded.price_eod,
               mfe=excluded.mfe, mae=excluded.mae, outcome_5m_pct=excluded.outcome_5m_pct,
               outcome_15m_pct=excluded.outcome_15m_pct, outcome_60m_pct=excluded.outcome_60m_pct,
               outcome_eod_pct=excluded.outcome_eod_pct, evaluated_at=excluded.evaluated_at""",
            listOf(id, signalId, outcome["price_after_5m"], outcome["price_after_15m"], outcome["price_after_60m"],
                outcome["price_eod"], outcome["mfe"], outcome["mae"], outcome["outcome_5m_pct"],
                outcome["outcome_15m_pct"], outcome["outcome_60m_pct"], outcome["outcome_eod_pct"], isoNow()),
        )
    }

    fun logAdaptationAction(
        mode: String, scope: String, bucketKey: String?, parameterName: String,
        oldValue: Double?, newValue: Double?, confidence: Double?, sampleSize: Int,
        actionStatus: String, reasonText: String, metrics: Map<String, Any?>, windowSummary: Map<String, Any?>,
    ) {
        val id = UUID.randomUUID().toString()
        db.execute(
            """INSERT INTO adaptive_actions (action_id, action_ts, mode, scope, bucket_key, parameter_name,
               old_value, new_value, confidence_score, sample_size, action_status, reason_text,
               metrics_json, window_summary_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            listOf(id, isoNow(), mode, scope, bucketKey, parameterName, oldValue, newValue, confidence,
                sampleSize, actionStatus, reasonText, toJsonString(metrics), toJsonString(windowSummary), isoNow()),
        )
    }
}
