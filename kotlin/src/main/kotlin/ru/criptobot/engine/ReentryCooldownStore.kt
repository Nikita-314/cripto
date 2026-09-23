package ru.criptobot.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ReentryCooldown(
    val symbol: String,
    val until: Instant,
    val reason: String,
)

class ReentryCooldownStore(private val path: Path) {
    private val entries = ConcurrentHashMap<String, ReentryCooldown>()

    init {
        load()
    }

    @Synchronized
    fun block(symbol: String, duration: Duration, reason: String, now: Instant = Instant.now()) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isEmpty() || duration.isZero || duration.isNegative) return
        entries[normalized] = ReentryCooldown(normalized, now.plus(duration), reason)
        save()
    }

    @Synchronized
    fun active(symbol: String, now: Instant = Instant.now()): ReentryCooldown? {
        val normalized = symbol.trim().uppercase()
        val entry = entries[normalized] ?: return null
        if (!entry.until.isAfter(now)) {
            entries.remove(normalized)
            save()
            return null
        }
        return entry
    }

    private fun load() {
        if (!Files.exists(path)) return
        runCatching {
            Files.readAllLines(path).forEach { line ->
                val fields = line.split('\t', limit = 3)
                if (fields.size != 3) return@forEach
                val symbol = fields[0].trim().uppercase()
                val until = runCatching { Instant.parse(fields[1]) }.getOrNull() ?: return@forEach
                if (symbol.isNotEmpty() && until.isAfter(Instant.now())) {
                    entries[symbol] = ReentryCooldown(symbol, until, fields[2])
                }
            }
        }
    }

    private fun save() {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        val content = entries.values.sortedBy { it.symbol }.joinToString("\n") {
            "${it.symbol}\t${it.until}\t${it.reason.replace('\t', ' ')}"
        }
        Files.writeString(temp, if (content.isEmpty()) "" else "$content\n")
        runCatching {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
