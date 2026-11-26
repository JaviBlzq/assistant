package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.MarketQuoteItem
import com.javiblzq.assistant.data.MarketSnapshot
import com.javiblzq.assistant.net.HttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MarketProvider {
    fun fetch(config: AssistantConfig): MarketSnapshot {
        val primary = normalizeSymbol(config.marketPrimarySymbol) ?: AssistantConfig.DEFAULT_MARKET_PRIMARY
        val watchlist = config.marketWatchlistSymbols.mapNotNull(::normalizeSymbol).distinct() - primary
        val symbols = listOf(primary) + watchlist
        val pool = Executors.newFixedThreadPool(symbols.size.coerceAtMost(4))
        try {
            val requests = symbols.associateWith { symbol -> pool.submit(Callable { fetchQuote(symbol) }) }
            val quotes = requests.mapValues { it.value.get() }
            val primaryQuote = quotes[primary] ?: if (primary == AssistantConfig.DEFAULT_MARKET_PRIMARY) {
                if ("URTH" in quotes) quotes["URTH"] else fetchQuote("URTH")
            } else null
            val watchlistQuotes = watchlist.mapNotNull(quotes::get).filter { it.symbol != primaryQuote?.symbol }
            return MarketSnapshot(
                primaryQuote = primaryQuote,
                watchlist = watchlistQuotes,
                statusText = when {
                    primaryQuote == null && watchlistQuotes.isEmpty() -> "SIN DATOS"
                    primaryQuote == null || watchlist.any { quotes[it] == null } -> "DATOS PARCIALES"
                    primaryQuote.symbol != primary -> "ALTERNATIVA MSCI WORLD"
                    else -> "OK"
                },
                fetchedAtMs = System.currentTimeMillis()
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchQuote(symbol: String): MarketQuoteItem? {
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val response = HttpClient.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1mo&interval=1d",
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"),
            timeoutMs = 5_000
        )
        return if (response.isSuccess) parseQuote(symbol, response.body) else null
    }

    companion object {
        private val symbolPattern = Regex("[A-Z0-9.^=+_/&-]{1,32}")

        internal fun normalizeSymbol(symbol: String): String? =
            symbol.trim().uppercase(Locale.US).takeIf(symbolPattern::matches)

        internal fun parseQuote(symbol: String, body: String, fetchedAtMs: Long = System.currentTimeMillis()): MarketQuoteItem? {
            val chart = runCatching { JSONObject(body).optJSONObject("chart") }.getOrNull() ?: return null
            if (!chart.isNull("error")) return null
            val result = chart.optJSONArray("result")?.optJSONObject(0) ?: return null
            val meta = result.optJSONObject("meta") ?: return null
            val values = result.optJSONObject("indicators")?.optJSONArray("quote")
                ?.optJSONObject(0)?.optJSONArray("close")
            val closes = if (values == null) emptyList() else (0 until values.length()).mapNotNull {
                values.optDouble(it).takeIf { close -> close.isFinite() && close > 0 }
            }
            val price = meta.optDouble("regularMarketPrice").takeIf { it.isFinite() && it > 0 }
                ?: closes.lastOrNull() ?: return null
            val previous = meta.optDouble("previousClose").takeIf { it.isFinite() && it > 0 }
                ?: closes.getOrNull(closes.lastIndex - 1)
            val monthStart = meta.optDouble("chartPreviousClose").takeIf { it.isFinite() && it > 0 }
                ?: closes.firstOrNull()
            val dailyChange = meta.optDouble("regularMarketChangePercent").takeIf(Double::isFinite)
                ?: percentChange(price, previous)
            val reportedSymbol = meta.optString("symbol", symbol)
            return MarketQuoteItem(
                symbol = reportedSymbol,
                displayName = meta.optString("longName").ifBlank { meta.optString("shortName").ifBlank { reportedSymbol } },
                price = price,
                changePercent1d = dailyChange,
                changePercent1m = percentChange(price, monthStart),
                currency = meta.optString("currency", ""),
                sparklineCloses = closes,
                fetchedAtMs = fetchedAtMs
            )
        }

        private fun percentChange(price: Double, reference: Double?): Double =
            if (reference == null) 0.0 else ((price / reference - 1) * 100).takeIf(Double::isFinite) ?: 0.0
    }
}
