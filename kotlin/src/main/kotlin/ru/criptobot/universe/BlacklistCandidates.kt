package ru.criptobot.universe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.criptobot.util.isoNow
import java.nio.file.Path
import kotlin.io.path.exists

@Serializable
data class BlacklistCandidate(
    val symbol: String,
    val reasonCode: String,
    val reasonText: String,
    val evidence: Map<String, String> = emptyMap(),
    val proposedAt: String = isoNow(),
    val status: String = "pending", // pending | approved | rejected
    val decidedAt: String? = null,
)

@Serializable
private data class BlacklistCandidatesFile(
    val candidates: List<BlacklistCandidate> = emptyList(),
)

class BlacklistCandidateStore(private val file: Path) {
    private val log = LoggerFactory.getLogger(BlacklistCandidateStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile private var items: MutableList<BlacklistCandidate> = load().toMutableList()

    fun pending(): List<BlacklistCandidate> = items.filter { it.status == "pending" }
        .sortedByDescending { it.proposedAt }

    fun all(): List<BlacklistCandidate> = items.toList()

    @Synchronized
    fun propose(
        symbol: String,
        reasonCode: String,
        reasonText: String,
        evidence: Map<String, Any?>,
    ): BlacklistCandidate? {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return null
        val existing = items.firstOrNull { it.symbol == sym && it.status == "pending" }
        if (existing != null) {
            // Refresh evidence for the same pending candidate.
            val updated = existing.copy(
                reasonCode = reasonCode,
                reasonText = reasonText,
                evidence = evidence.mapValues { it.value?.toString() ?: "" },
                proposedAt = isoNow(),
            )
            items = items.map { if (it.symbol == sym && it.status == "pending") updated else it }.toMutableList()
            save()
            return null // not newly created
        }
        if (items.any { it.symbol == sym && it.status == "rejected" }) {
            // Keep human rejection sticky until manually cleared via restore flow.
            return null
        }
        val candidate = BlacklistCandidate(
            symbol = sym,
            reasonCode = reasonCode,
            reasonText = reasonText,
            evidence = evidence.mapValues { it.value?.toString() ?: "" },
        )
        items.add(0, candidate)
        save()
        log.info("Blacklist candidate proposed [{}]: {}", sym, reasonCode)
        return candidate
    }

    @Synchronized
    fun approve(symbol: String): BlacklistCandidate? {
        val sym = symbol.trim().uppercase()
        val idx = items.indexOfFirst { it.symbol == sym && it.status == "pending" }
        if (idx < 0) return null
        val updated = items[idx].copy(status = "approved", decidedAt = isoNow())
        items[idx] = updated
        save()
        return updated
    }

    @Synchronized
    fun reject(symbol: String): BlacklistCandidate? {
        val sym = symbol.trim().uppercase()
        val idx = items.indexOfFirst { it.symbol == sym && it.status == "pending" }
        if (idx < 0) return null
        val updated = items[idx].copy(status = "rejected", decidedAt = isoNow())
        items[idx] = updated
        save()
        return updated
    }

    @Synchronized
    fun clearRejection(symbol: String): Boolean {
        val sym = symbol.trim().uppercase()
        val before = items.size
        items = items.filterNot { it.symbol == sym && it.status == "rejected" }.toMutableList()
        if (items.size != before) {
            save()
            return true
        }
        return false
    }

    private fun load(): List<BlacklistCandidate> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<BlacklistCandidatesFile>(file.toFile().readText()).candidates
        }.getOrElse {
            log.warn("Failed to read blacklist candidates: {}", it.message)
            emptyList()
        }
    }

    private fun save() {
        file.toFile().parentFile?.mkdirs()
        file.toFile().writeText(json.encodeToString(BlacklistCandidatesFile(items)))
    }
}
