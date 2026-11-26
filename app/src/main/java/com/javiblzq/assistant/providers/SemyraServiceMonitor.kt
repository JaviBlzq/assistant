package com.javiblzq.assistant.providers

import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.ServiceHealthItem
import com.javiblzq.assistant.net.HttpClient
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SemyraServiceMonitor {
    fun checkAll(
        config: AssistantConfig,
        harbiz: ServiceHealthItem?,
        strava: ServiceHealthItem?,
        telegramStatus: String,
        weather: ServiceHealthItem,
        osrm: ServiceHealthItem?
    ): List<ServiceHealthItem> {
        val services = listOf<() -> ServiceHealthItem>(
            { harbiz ?: probe("harbiz", "HARBIZ DDP", "https://app.harbiz.io/", reachabilityOnly = true) },
            { strava ?: probe("strava", "STRAVA API", "https://www.strava.com/api/v3", reachabilityOnly = true) },
            { probe("semyra-sport", "SEMYRA SPORT", config.semyraApiUrl) },
            {
                val endpoint = probe("telegram", "TELEGRAM BOT", "https://api.telegram.org/", reachabilityOnly = true)
                if (config.telegramBotToken.isBlank()) endpoint.copy(detail = "SIN TOKEN · ${endpoint.detail}")
                else endpoint.copy(
                    online = endpoint.online && telegramStatus == "BOT ACTIVO",
                    detail = telegramStatus
                )
            },
            { weather },
            { osrm ?: probe("osrm", "OSRM", "https://router.project-osrm.org/route/v1/driving/-3.7038,40.4168;-3.6900,40.4400?overview=false") }
        ) + config.semyraServiceUrls.filter { it != config.semyraApiUrl }.distinct().map { url ->
            {
                val name = httpUri(url)?.host ?: "SEMYRA"
                probe("semyra-${Integer.toHexString(url.hashCode())}", name, url)
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        return try {
            pool.invokeAll(services.map { Callable(it) }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probe(id: String, name: String, url: String, reachabilityOnly: Boolean = false): ServiceHealthItem {
        if (url.isBlank()) return ServiceHealthItem(id, name, false, -1, "SIN CONFIGURAR")
        if (httpUri(url) == null) return ServiceHealthItem(id, name, false, -1, "URL INVÁLIDA")
        val start = System.nanoTime()
        var response = HttpClient.head(url, 3_000)
        if (response.code in listOf(403, 405, 501)) response = HttpClient.get(url, timeoutMs = 3_000)
        val reachable = response.code in 200..399 || (reachabilityOnly && response.code in listOf(401, 403, 404))
        val detail = when {
            response.code < 0 -> "SIN RESPUESTA"
            reachabilityOnly && reachable -> "ENDPOINT HTTP ${response.code}"
            else -> "HTTP ${response.code}"
        }
        return ServiceHealthItem(id, name, reachable, elapsedMs(start), detail)
    }

    companion object {
        internal fun elapsedMs(startNs: Long): Int = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        internal fun httpUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
            (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
                it.host != null && it.userInfo == null && it.fragment == null
        }
    }
}
