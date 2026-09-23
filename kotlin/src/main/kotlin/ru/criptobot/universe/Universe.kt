package ru.criptobot.universe

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import ru.criptobot.config.Settings
import ru.criptobot.data.StockInstrument
import java.util.concurrent.ConcurrentHashMap

class BinanceUniverseProvider(private val settings: Settings) {
    private val log = LoggerFactory.getLogger(BinanceUniverseProvider::class.java)
    private val client = HttpClient(CIO) { engine { requestTimeout = 60_000 } }
    private val json = Json { ignoreUnknownKeys = true }
    private val quoteVolumes = ConcurrentHashMap<String, Double>()

    fun quoteVolume24h(symbol: String): Double = quoteVolumes[symbol.uppercase()] ?: 0.0

    fun allQuoteVolumes(): Map<String, Double> = quoteVolumes.toMap()

    suspend fun fetch(): List<StockInstrument> {
        runCatching {
            val parsed = fetchFromBinance()
            if (parsed.isNotEmpty()) {
                cache(parsed)
                log.info("Loaded Binance universe: {} symbols", parsed.size)
                return parsed
            }
            log.info("Binance universe API returned 0 instruments, trying cache/seed")
        }.onFailure { log.warn("Binance universe failed: {}", it.message) }

        val cached = readCache()
        if (cached.isNotEmpty()) {
            log.info("Using cached universe: {} symbols", cached.size)
            return cached
        }
        log.warn("Using seed symbols fallback")
        return settings.seedSymbols.map { StockInstrument(it, exchange = settings.exchange) }
    }

    private suspend fun fetchFromBinance(): List<StockInstrument> {
        val base = settings.binanceApiBaseUrl.trimEnd('/')
        val exchangeInfo = json.parseToJsonElement(client.get("$base/api/v3/exchangeInfo").bodyAsText()).jsonObject
        val symbolsArr = exchangeInfo["symbols"]?.jsonArray ?: return emptyList()
        val quote = settings.binanceUniverseQuote
        val tradable = symbolsArr.mapNotNull { el ->
            val obj = el.jsonObject
            val symbol = obj["symbol"]?.jsonPrimitive?.content?.uppercase().orEmpty()
            val status = obj["status"]?.jsonPrimitive?.content.orEmpty()
            val quoteAsset = obj["quoteAsset"]?.jsonPrimitive?.content?.uppercase().orEmpty()
            val baseAsset = obj["baseAsset"]?.jsonPrimitive?.content.orEmpty()
            val spotAllowed = obj["isSpotTradingAllowed"]?.jsonPrimitive?.booleanOrNull ?: true
            if (symbol.isEmpty() || quoteAsset != quote || status != "TRADING" || !spotAllowed) return@mapNotNull null
            if (CryptoUniverseExclusions.shouldExclude(baseAsset, symbol, permissionTags(obj))) return@mapNotNull null
            StockInstrument(
                symbol = symbol,
                name = baseAsset,
                exchange = "BINANCE",
                tradable = true,
                status = status,
                lotSize = 1,
            )
        }
        if (tradable.isEmpty()) return emptyList()

        val tickers = json.parseToJsonElement(client.get("$base/api/v3/ticker/24hr").bodyAsText()).jsonArray
        val quoteVolumeBySymbol = tickers.mapNotNull { el ->
            val obj = el.jsonObject
            val symbol = obj["symbol"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null
            val qv = obj["quoteVolume"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            symbol to qv
        }.toMap()

        quoteVolumes.clear()
        quoteVolumes.putAll(quoteVolumeBySymbol)

        val ranked = tradable
            .map { it.copy(quoteVolume24h = quoteVolumeBySymbol[it.symbol] ?: 0.0) }
            .sortedByDescending { it.quoteVolume24h }

        // 0 = no hard volume cut for trading universe; soft threshold is used only by blacklist proposer.
        val volumeFloor = settings.binanceMinQuoteVolume.coerceAtLeast(0.0)
        val volumeFiltered = if (volumeFloor > 0) {
            ranked.filter { it.quoteVolume24h >= volumeFloor }
        } else {
            ranked
        }
        // Drop stables / xStocks before the size cap so the 50 slots are real crypto.
        val filtered = StockRiskFilter().deterministicFilter(volumeFiltered)

        // 0 = unlimited (all matching pairs)
        val limit = settings.binanceUniverseLimit
        return if (limit <= 0) filtered else filtered.take(limit)
    }

    private fun permissionTags(obj: JsonObject): Set<String> {
        val tags = mutableSetOf<String>()
        obj["permissions"]?.jsonArray?.forEach { el ->
            el.jsonPrimitive.contentOrNull?.let { tags += it }
        }
        obj["permissionSets"]?.jsonArray?.forEach { setEl ->
            setEl.jsonArray.forEach { el ->
                el.jsonPrimitive.contentOrNull?.let { tags += it }
            }
        }
        return tags
    }

    private fun cache(items: List<StockInstrument>) {
        val arr = buildJsonArray {
            items.forEach { i ->
                addJsonObject {
                    put("symbol", i.symbol); put("name", i.name); put("exchange", i.exchange)
                    put("tradable", i.tradable); put("status", i.status); put("lot_size", i.lotSize)
                    put("quote_volume_24h", i.quoteVolume24h)
                }
            }
        }
        settings.binanceUniverseCacheFile.toFile().writeText(json.encodeToString(arr))
    }

    private fun readCache(): List<StockInstrument> {
        val f = settings.binanceUniverseCacheFile.toFile()
        if (!f.exists()) return emptyList()
        return json.parseToJsonElement(f.readText()).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val sym = o["symbol"]?.jsonPrimitive?.content?.uppercase().orEmpty()
            if (sym.isEmpty()) return@mapNotNull null
            val qv = o["quote_volume_24h"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (qv > 0) quoteVolumes[sym] = qv
            StockInstrument(
                sym,
                o["name"]?.jsonPrimitive?.content.orEmpty(),
                o["exchange"]?.jsonPrimitive?.content.orEmpty(),
                o["tradable"]?.jsonPrimitive?.booleanOrNull ?: true,
                o["status"]?.jsonPrimitive?.content.orEmpty(),
                o["lot_size"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1,
                qv,
            )
        }
    }

    fun close() {
        client.close()
    }
}

object CryptoUniverseExclusions {
    const val TOKENIZED_STOCK_PERMISSION = "TRD_GRP_261"

    private val stableOrFiatBases = setOf(
        "AEUR", "BFUSD", "BUSD", "DAI", "EUR", "EURI", "FDUSD", "PYUSD",
        "RLUSD", "TUSD", "U", "USD", "USD1", "USDC", "USDE", "USDP", "USDS",
        "USDT", "UST", "USTC", "XUSD",
    )

    // Binance xStocks / tokenized equities (plus short tickers that do not end with a 4+ letter *B).
    private val tokenizedStockBases = setOf(
        "AAOIB", "AAPLB", "ALABB", "AMATB", "AMDB", "AMZNB", "ARMB", "ASMLB", "ASTSB",
        "AVGOB", "AXTIB", "BABAB", "BEB", "BMNRB", "CBRSB", "COHRB", "COINB", "CRCLB",
        "CRDOB", "CRWVB", "DELLB", "DJTB", "DRAMB", "EWYB", "FLNCB", "GLWB", "GMEB",
        "GOOGLB", "GSB", "HOODB", "IBMB", "INTCB", "INTWB", "IRENB", "KORUB", "LITEB",
        "METAB", "MRVLB", "MSFTB", "MSTRB", "MUB", "MUUB", "MVLLB", "NBISB", "NFLXB",
        "NOKB", "NVDAB", "ORCLB", "PLTRB", "PYPLB", "QCOMB", "QNTB", "QQQB", "RKLBB",
        "SKHYB", "SMCIB", "SMHB", "SNDKB", "SNXXB", "SOXLB", "SOXSB", "SPCXB", "SPYB",
        "TQQQB", "TSLAB", "TSMB", "USARB", "WDCB",
    )

    fun baseAsset(symbol: String, name: String = ""): String {
        val fromName = name.trim().uppercase()
        if (fromName.isNotEmpty()) return fromName
        val sym = symbol.uppercase()
        return if (sym.endsWith("USDT") && sym.length > 4) sym.removeSuffix("USDT") else sym
    }

    fun isStableOrFiat(base: String): Boolean = base.uppercase() in stableOrFiatBases

    fun isTokenizedStockBase(base: String): Boolean {
        val b = base.uppercase()
        if (b in tokenizedStockBases) return true
        // AAPLB / SNDKB / QQQB wrappers. Do not treat BNB/BCH as stocks.
        return b.length >= 4 && b.endsWith("B") && b !in setOf("BNB", "BCH")
    }

    fun hasTokenizedStockPermission(permissionTags: Collection<String>): Boolean =
        permissionTags.any { it.equals(TOKENIZED_STOCK_PERMISSION, ignoreCase = true) }

    fun shouldExclude(
        base: String,
        symbol: String = "",
        permissionTags: Collection<String> = emptyList(),
    ): Boolean {
        val resolved = baseAsset(symbol, base)
        return isStableOrFiat(resolved) ||
            hasTokenizedStockPermission(permissionTags) ||
            isTokenizedStockBase(resolved)
    }
}

class StockRiskFilter {
    private val badStatus = listOf("break", "halt", "suspend", "delist")
    private val leveragedSymbol = Regex(".*(UP|DOWN|BULL|BEAR)USDT$")
    private val spotSymbol = Regex("^[A-Z0-9]{2,20}$")

    fun deterministicFilter(instruments: List<StockInstrument>): List<StockInstrument> =
        instruments.filter { instrument ->
            val statusOk = instrument.status.isBlank() ||
                instrument.status.equals("TRADING", ignoreCase = true)
            instrument.tradable &&
                statusOk &&
                spotSymbol.matches(instrument.symbol.uppercase()) &&
                badStatus.none { w -> instrument.status.lowercase().contains(w) } &&
                !leveragedSymbol.matches(instrument.symbol.uppercase()) &&
                !instrument.name.contains("LEVERAGED", ignoreCase = true) &&
                !CryptoUniverseExclusions.shouldExclude(instrument.name, instrument.symbol)
        }
}
