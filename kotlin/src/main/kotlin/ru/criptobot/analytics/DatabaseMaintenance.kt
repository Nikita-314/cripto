package ru.criptobot.analytics

import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists

data class DatabaseMaintenanceResult(
    val ran: Boolean,
    val beforeBytes: Long,
    val afterBytes: Long,
    val deletedRows: Int,
    val reason: String,
) {
    val beforeMb: Double get() = beforeBytes / 1024.0 / 1024.0
    val afterMb: Double get() = afterBytes / 1024.0 / 1024.0
}

class DatabaseMaintenance(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(DatabaseMaintenance::class.java)
    private val path = settings.analyticsDbPath
    private val maxBytes get() = settings.analyticsDbMaxMb * 1024L * 1024L
    private val targetBytes get() = settings.analyticsDbTargetMb.coerceAtMost(settings.analyticsDbMaxMb) * 1024L * 1024L

    fun runIfNeeded(cycle: Int, force: Boolean = false): DatabaseMaintenanceResult {
        if (!settings.analyticsEnabled || !path.exists()) {
            return DatabaseMaintenanceResult(false, 0, 0, 0, "analytics_disabled_or_missing")
        }
        if (!force && cycle % settings.analyticsDbMaintenanceCycles != 0) {
            val size = fileSizeBytes()
            return DatabaseMaintenanceResult(false, size, size, 0, "skip_cycle")
        }

        val before = fileSizeBytes()
        var deleted = 0
        var compacted = false
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
            }

            deleted += deleteOlderThanRetention(conn)

            if (before > maxBytes && estimatedSizeBytes(conn) > targetBytes) {
                deleted += trimOldestSignals(conn)
            }

            deleted += deleteOrphans(conn)
            conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            val allocatedBytes = queryLong(conn, "PRAGMA page_size") * queryLong(conn, "PRAGMA page_count")
            val reclaimableBytes = (allocatedBytes - estimatedSizeBytes(conn)).coerceAtLeast(0)
            if (fileSizeBytes() > maxBytes && reclaimableBytes >= 64L * 1024L * 1024L) {
                conn.createStatement().use { it.execute("VACUUM") }
                compacted = true
            }
        }

        val after = fileSizeBytes()
        val ran = deleted > 0 || compacted
        if (ran || before > maxBytes) {
            log.info(
                "Analytics DB maintenance: before={}MB after={}MB deleted_rows={} compacted={} limit={}MB target={}MB",
                "%.1f".format(before / 1024.0 / 1024.0),
                "%.1f".format(after / 1024.0 / 1024.0),
                deleted,
                compacted,
                settings.analyticsDbMaxMb,
                settings.analyticsDbTargetMb,
            )
        }
        val reason = when {
            compacted -> "compacted"
            deleted > 0 -> "retention_applied"
            before > maxBytes -> "over_limit_no_reclaimable_pages"
            else -> "under_limit"
        }
        return DatabaseMaintenanceResult(ran, before, after, deleted, reason)
    }

    private fun deleteOlderThanRetention(conn: Connection): Int {
        val cutoff = "-${settings.analyticsDbMinRetentionDays} days"
        var deleted = 0
        val oldSignalsSql = """
            SELECT signal_id FROM signals
            WHERE datetime(replace(substr(signal_ts,1,19),'T',' ')) < datetime('now', ?)
        """.trimIndent()
        val oldSignalIds = selectStrings(conn, oldSignalsSql, cutoff)
        deleted += deleteSignalTree(conn, oldSignalIds)
        deleted += execute(
            conn,
            "DELETE FROM decision_logs WHERE signal_id IS NULL AND datetime(replace(substr(decision_ts,1,19),'T',' ')) < datetime('now', ?)",
            cutoff,
        )
        deleted += execute(
            conn,
            "DELETE FROM adaptive_actions WHERE datetime(replace(substr(action_ts,1,19),'T',' ')) < datetime('now', ?)",
            cutoff,
        )
        deleted += execute(
            conn,
            "DELETE FROM signal_runs WHERE datetime(replace(substr(run_started_at,1,19),'T',' ')) < datetime('now', ?)",
            cutoff,
        )
        return deleted
    }

    private fun trimOldestSignals(conn: Connection): Int {
        var deleted = 0
        var guard = 0
        while (estimatedSizeBytes(conn) > targetBytes && guard++ < 1_000) {
            val ids = selectStrings(
                conn,
                """
                SELECT signal_id FROM signals
                ORDER BY datetime(replace(substr(signal_ts,1,19),'T',' ')) ASC
                LIMIT 5000
                """.trimIndent(),
            )
            if (ids.isEmpty()) break
            deleted += deleteSignalTree(conn, ids)
        }
        return deleted
    }

    private fun deleteSignalTree(conn: Connection, signalIds: List<String>): Int {
        if (signalIds.isEmpty()) return 0
        var deleted = 0
        for (chunk in signalIds.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += executeMany(conn, "DELETE FROM signal_outcomes WHERE signal_id IN ($placeholders)", chunk)
            deleted += executeMany(conn, "DELETE FROM model_inference_logs WHERE signal_id IN ($placeholders)", chunk)
            deleted += executeMany(conn, "DELETE FROM decision_logs WHERE signal_id IN ($placeholders)", chunk)
            deleted += executeMany(conn, "DELETE FROM paper_trade_links WHERE signal_id IN ($placeholders)", chunk)
            deleted += executeMany(conn, "DELETE FROM signals WHERE signal_id IN ($placeholders)", chunk)
        }
        return deleted
    }

    private fun deleteOrphans(conn: Connection): Int {
        var deleted = 0
        deleted += execute(conn, "DELETE FROM signal_outcomes WHERE signal_id NOT IN (SELECT signal_id FROM signals)")
        deleted += execute(conn, "DELETE FROM model_inference_logs WHERE signal_id IS NOT NULL AND signal_id NOT IN (SELECT signal_id FROM signals)")
        deleted += execute(conn, "DELETE FROM decision_logs WHERE signal_id IS NOT NULL AND signal_id NOT IN (SELECT signal_id FROM signals)")
        deleted += execute(conn, "DELETE FROM paper_trade_links WHERE signal_id IS NOT NULL AND signal_id NOT IN (SELECT signal_id FROM signals)")
        return deleted
    }

    private fun estimatedSizeBytes(conn: Connection): Long {
        val pageSize = queryLong(conn, "PRAGMA page_size")
        val pageCount = queryLong(conn, "PRAGMA page_count")
        val freePages = queryLong(conn, "PRAGMA freelist_count")
        return pageSize * (pageCount - freePages).coerceAtLeast(0)
    }

    private fun fileSizeBytes(): Long {
        val base = path.toFile()
        return listOf(base, File(base.parentFile, "${base.name}-wal"), File(base.parentFile, "${base.name}-shm"))
            .sumOf { it.takeIf(File::exists)?.length() ?: 0L }
    }

    private fun selectStrings(conn: Connection, sql: String, vararg params: String): List<String> {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, value -> ps.setString(i + 1, value) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString(1)
                return out
            }
        }
    }

    private fun execute(conn: Connection, sql: String, vararg params: String): Int {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, value -> ps.setString(i + 1, value) }
            return ps.executeUpdate()
        }
    }

    private fun executeMany(conn: Connection, sql: String, params: List<String>): Int {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, value -> ps.setString(i + 1, value) }
            return ps.executeUpdate()
        }
    }

    private fun queryLong(conn: Connection, sql: String): Long {
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
}
