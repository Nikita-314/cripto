package ru.criptobot.universe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists

@Serializable
data class UniverseOverridesData(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

class UniverseOverridesStore(private val file: Path) {
    private val log = LoggerFactory.getLogger(UniverseOverridesStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile private var data = load()

    val included: Set<String> get() = data.include.map { it.uppercase() }.toSet()
    val excluded: Set<String> get() = data.exclude.map { it.uppercase() }.toSet()

    fun snapshot(): UniverseOverridesData = data.copy(
        include = data.include.map { it.uppercase() },
        exclude = data.exclude.map { it.uppercase() },
    )

    @Synchronized
    fun add(symbol: String): Boolean {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return false
        val nextExclude = data.exclude.filterNot { it.equals(sym, ignoreCase = true) }
        val nextInclude = (listOf(sym) + data.include.map { it.uppercase() }).distinct()
        if (nextInclude == data.include.map { it.uppercase() } && nextExclude == data.exclude) return false
        data = data.copy(include = nextInclude, exclude = nextExclude)
        save()
        log.info("Universe override add: {}", sym)
        return true
    }

    @Synchronized
    fun remove(symbol: String): Boolean {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) return false
        val nextInclude = data.include.filterNot { it.equals(sym, ignoreCase = true) }
        val nextExclude = (listOf(sym) + data.exclude.map { it.uppercase() }).distinct()
        if (nextInclude == data.include && nextExclude == data.exclude.map { it.uppercase() }) return false
        data = data.copy(include = nextInclude, exclude = nextExclude)
        save()
        log.info("Universe override remove: {}", sym)
        return true
    }

    private fun load(): UniverseOverridesData {
        val f = file.toFile()
        if (!f.exists()) return UniverseOverridesData()
        return runCatching {
            json.decodeFromString<UniverseOverridesData>(f.readText())
        }.getOrElse {
            log.warn("Failed to read universe overrides: {}", it.message)
            UniverseOverridesData()
        }
    }

    private fun save() {
        file.toFile().parentFile?.mkdirs()
        file.toFile().writeText(json.encodeToString(data))
    }
}
