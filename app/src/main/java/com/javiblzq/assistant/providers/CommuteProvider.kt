package com.javiblzq.assistant.providers

import android.util.Log
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.CommuteRoute
import com.javiblzq.assistant.data.RoutePoint
import com.javiblzq.assistant.net.HttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

open class CommuteProvider {

    private data class TomTomFetchResult(
        val route: CommuteRoute? = null,
        val errorLabel: String? = null
    )

    open fun fetchRoute(
        config: AssistantConfig,
        targetName: String,
        destLat: Double,
        destLon: Double
    ): CommuteRoute {
        val originLat = config.homeLat
        val originLon = config.homeLon
        val originLabel = config.homeLabel

        val providerOrder = resolveProviderOrder(config)
        Log.d(
            TAG,
            "fetchRoute target=$targetName preferred=${config.trafficProvider} order=$providerOrder " +
                "tomtomKeyLen=${config.tomtomApiKey.trim().length} redacted=${isRedactedPlaceholder(config.tomtomApiKey)}"
        )

        var lastTomTomError: String? = if (
            config.trafficProvider.trim().equals("tomtom", ignoreCase = true) &&
            !isUsableApiKey(config.tomtomApiKey)
        ) {
            Log.w(TAG, "fetchRoute($targetName): trafficProvider=tomtom but API key is empty/placeholder")
            "TOMTOM SIN KEY"
        } else null
        for (provider in providerOrder) {
            when (provider) {
                "google" -> {
                    fetchGoogle(originLat, originLon, destLat, destLon, config.googleMapsApiKey, targetName, originLabel)
                        ?.let { return it }
                }
                "tomtom" -> {
                    val res = fetchTomTom(originLat, originLon, destLat, destLon, config.tomtomApiKey, targetName, originLabel)
                    if (res.route != null) return res.route
                    lastTomTomError = res.errorLabel
                }
                else -> {
                    val osrm = fetchOsrm(originLat, originLon, destLat, destLon, targetName, originLabel)
                    return applyFallbackError(osrm, lastTomTomError)
                }
            }
        }

        val fallback = fetchOsrm(originLat, originLon, destLat, destLon, targetName, originLabel)
        return applyFallbackError(fallback, lastTomTomError)
    }

    private fun applyFallbackError(route: CommuteRoute, tomtomError: String?): CommuteRoute {
        if (tomtomError.isNullOrBlank()) return route
        return route.copy(statusLabel = tomtomError)
    }

    internal fun resolveProviderOrder(config: AssistantConfig): List<String> {
        val hasGoogle = isUsableApiKey(config.googleMapsApiKey)
        val hasTomTom = isUsableApiKey(config.tomtomApiKey)
        val preferred = config.trafficProvider.trim().lowercase(Locale.US)
        val order = mutableListOf<String>()
        when (preferred) {
            "tomtom" -> {
                if (hasTomTom) order.add("tomtom")
                if (hasGoogle) order.add("google")
            }
            "google" -> {
                if (hasGoogle) order.add("google")
                if (hasTomTom) order.add("tomtom")
            }
            else -> {
                if (hasTomTom) order.add("tomtom")
                if (hasGoogle) order.add("google")
            }
        }
        order.add("osrm")
        return order
    }

    fun decodePolyline(encoded: String): List<Pair<Double, Double>> =
        decodeEncodedPolyline(encoded)

    private fun fetchGoogle(
        oLat: Double, oLon: Double, dLat: Double, dLon: Double,
        apiKey: String, targetName: String, originLabel: String
    ): CommuteRoute? {
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val url = String.format(
            Locale.US,
            "https://maps.googleapis.com/maps/api/directions/json?origin=%.6f,%.6f&destination=%.6f,%.6f&mode=driving&departure_time=now&traffic_model=best_guess&key=%s",
            oLat, oLon, dLat, dLon, encodedKey
        )
        val resp = HttpClient.get(url)
        if (!resp.isSuccess) return null
        return parseGoogleResponse(resp.body, oLat, oLon, dLat, dLon, targetName, originLabel)
    }

    internal fun parseGoogleResponse(
        body: String,
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        targetName: String,
        originLabel: String
    ): CommuteRoute? {
        return try {
            val root = JSONObject(body)
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route = routes.getJSONObject(0)
            val leg = route.getJSONArray("legs").getJSONObject(0)
            val distM = leg.getJSONObject("distance").getDouble("value")
            val baseSec = leg.getJSONObject("duration").getDouble("value")
            val trafficSec = leg.optJSONObject("duration_in_traffic")?.optDouble("value", baseSec) ?: baseSec
            val delayMin = ((trafficSec - baseSec) / 60.0).roundToInt().coerceAtLeast(0)
            val durationMin = (trafficSec / 60.0).roundToInt().coerceAtLeast(1)
            val distanceKm = round((distM / 1000.0) * 10.0) / 10.0
            val encoded = route.optJSONObject("overview_polyline")?.optString("points", "") ?: ""
            val rawCoords = decodePolyline(encoded)
            val polylinePoints = buildRoutePoints(
                coords = rawCoords,
                oLat = oLat, oLon = oLon, dLat = dLat, dLon = dLon,
                trafficDelayMin = delayMin,
                durationMin = durationMin,
                distanceKm = distanceKm,
                isOsrm = false,
                isOffline = false
            )
            CommuteRoute(
                targetName = targetName,
                originLabel = originLabel,
                durationMin = durationMin,
                distanceKm = distanceKm,
                trafficDelayMin = delayMin,
                providerLabel = "GMAPS LIVE",
                statusLabel = if (delayMin >= 1) "+${delayMin}m TRAFICO" else "FLUIDO",
                fetchedAtMs = System.currentTimeMillis(),
                polylinePoints = polylinePoints
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTomTom(
        oLat: Double, oLon: Double, dLat: Double, dLon: Double,
        apiKey: String, targetName: String, originLabel: String
    ): TomTomFetchResult {
        val trimmedKey = apiKey.trim()
        if (!isUsableApiKey(trimmedKey)) {
            Log.w(TAG, "fetchTomTom($targetName): API key missing or placeholder")
            return TomTomFetchResult(errorLabel = "TOMTOM SIN KEY")
        }
        val encodedKey = URLEncoder.encode(trimmedKey, "UTF-8")
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?traffic=true&travelMode=car&key=%s",
            oLat, oLon, dLat, dLon, encodedKey
        )
        val maskedUrl = maskKeyInUrl(url, encodedKey)
        Log.d(TAG, "fetchTomTom($targetName) GET $maskedUrl")
        val resp = HttpClient.get(url)
        Log.d(TAG, "fetchTomTom($targetName) HTTP code=${resp.code} error=${resp.error} bodyLen=${resp.body.length}")
        if (!resp.isSuccess) {
            val snippet = resp.body.replace(Regex("\\s+"), " ").take(240)
            Log.e(
                TAG,
                "fetchTomTom($targetName) FAILED code=${resp.code} err=${resp.error} body=$snippet"
            )
            val errLabel = when {
                resp.code > 0 -> "TOMTOM ERR ${resp.code}"
                resp.error != null -> "TOMTOM ERR NET"
                else -> "TOMTOM ERR"
            }
            return TomTomFetchResult(errorLabel = errLabel)
        }
        val parsed = parseTomTomResponse(resp.body, oLat, oLon, dLat, dLon, targetName, originLabel)
        if (parsed == null) {
            val snippet = resp.body.replace(Regex("\\s+"), " ").take(240)
            Log.e(TAG, "fetchTomTom($targetName) PARSE FAILED body=$snippet")
            return TomTomFetchResult(errorLabel = "TOMTOM PARSE ERR")
        }
        Log.i(
            TAG,
            "fetchTomTom($targetName) OK duration=${parsed.durationMin}m dist=${parsed.distanceKm}km delay=${parsed.trafficDelayMin}m"
        )
        return TomTomFetchResult(route = parsed)
    }

    internal fun parseTomTomResponse(
        body: String,
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        targetName: String,
        originLabel: String
    ): CommuteRoute? {
        return try {
            val root = JSONObject(body)
            val route = root.getJSONArray("routes").getJSONObject(0)
            val summary = route.getJSONObject("summary")
            val distM = summary.getDouble("lengthInMeters")
            val travelSec = summary.getDouble("travelTimeInSeconds")
            val delaySec = summary.optDouble("trafficDelayInSeconds", 0.0)
            val delayMin = (delaySec / 60.0).roundToInt().coerceAtLeast(0)
            val durationMin = (travelSec / 60.0).roundToInt().coerceAtLeast(1)
            val distanceKm = round((distM / 1000.0) * 10.0) / 10.0

            val rawCoords = mutableListOf<Pair<Double, Double>>()
            val pointsArr = route.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("points")
            if (pointsArr != null) {
                for (i in 0 until pointsArr.length()) {
                    val pt = pointsArr.optJSONObject(i) ?: continue
                    val lat = pt.optDouble("latitude", Double.NaN)
                    val lon = pt.optDouble("longitude", Double.NaN)
                    if (lat.isFinite() && lon.isFinite()) {
                        rawCoords.add(lat to lon)
                    }
                }
            }
            val polylinePoints = buildRoutePoints(
                coords = rawCoords,
                oLat = oLat, oLon = oLon, dLat = dLat, dLon = dLon,
                trafficDelayMin = delayMin,
                durationMin = durationMin,
                distanceKm = distanceKm,
                isOsrm = false,
                isOffline = false
            )
            CommuteRoute(
                targetName = targetName,
                originLabel = originLabel,
                durationMin = durationMin,
                distanceKm = distanceKm,
                trafficDelayMin = delayMin,
                providerLabel = "TOMTOM LIVE",
                statusLabel = if (delayMin >= 1) "+${delayMin}m TRAFICO" else "FLUIDO",
                fetchedAtMs = System.currentTimeMillis(),
                polylinePoints = polylinePoints
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOsrm(
        oLat: Double, oLon: Double, dLat: Double, dLon: Double,
        targetName: String, originLabel: String
    ): CommuteRoute {
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline",
            oLon, oLat, dLon, dLat
        )
        val resp = HttpClient.get(url)
        if (resp.isSuccess) {
            parseOsrmResponse(resp.body, oLat, oLon, dLat, dLon, targetName, originLabel)?.let {
                return it
            }
        }
        return CommuteRoute(
            targetName = targetName,
            originLabel = originLabel,
            durationMin = 0,
            distanceKm = 0.0,
            trafficDelayMin = 0,
            providerLabel = "OFFLINE",
            statusLabel = "SIN RED",
            fetchedAtMs = System.currentTimeMillis(),
            polylinePoints = buildRoutePoints(
                coords = emptyList(),
                oLat = oLat, oLon = oLon, dLat = dLat, dLon = dLon,
                trafficDelayMin = 0,
                durationMin = 0,
                distanceKm = 0.0,
                isOsrm = false,
                isOffline = true
            )
        )
    }

    internal fun parseOsrmResponse(
        body: String,
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        targetName: String,
        originLabel: String
    ): CommuteRoute? {
        return try {
            val root = JSONObject(body)
            val routes = root.optJSONArray("routes")
            if (routes == null || routes.length() == 0) return null
            val r = routes.getJSONObject(0)
            val durSec = r.getDouble("duration")
            val distM = r.getDouble("distance")
            val durationMin = (durSec / 60.0).roundToInt().coerceAtLeast(1)
            val distanceKm = round((distM / 1000.0) * 10.0) / 10.0
            val encoded = r.optString("geometry", "")
            val rawCoords = decodePolyline(encoded)
            val polylinePoints = buildRoutePoints(
                coords = rawCoords,
                oLat = oLat, oLon = oLon, dLat = dLat, dLon = dLon,
                trafficDelayMin = 0,
                durationMin = durationMin,
                distanceKm = distanceKm,
                isOsrm = true,
                isOffline = false
            )
            CommuteRoute(
                targetName = targetName,
                originLabel = originLabel,
                durationMin = durationMin,
                distanceKm = distanceKm,
                trafficDelayMin = 0,
                providerLabel = "OSRM CAR",
                statusLabel = "RUTA BASE",
                fetchedAtMs = System.currentTimeMillis(),
                polylinePoints = polylinePoints
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRoutePoints(
        coords: List<Pair<Double, Double>>,
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        trafficDelayMin: Int,
        durationMin: Int,
        distanceKm: Double,
        isOsrm: Boolean,
        isOffline: Boolean
    ): List<RoutePoint> {
        val path = if (coords.size >= 2) coords else synthesizePath(oLat, oLon, dLat, dLon, count = 8)
        val n = path.size
        val avgSpeedKmh = if (durationMin > 0 && distanceKm > 0.0) distanceKm / (durationMin / 60.0) else 35.0
        return path.mapIndexed { idx, (lat, lon) ->
            val frac = if (n > 1) idx.toDouble() / (n - 1) else 0.5
            val level = computeCongestionLevel(
                frac = frac,
                trafficDelayMin = trafficDelayMin,
                avgSpeedKmh = avgSpeedKmh,
                isOsrm = isOsrm,
                isOffline = isOffline
            )
            RoutePoint(lat = lat, lon = lon, congestionLevel = level)
        }
    }

    private fun computeCongestionLevel(
        frac: Double,
        trafficDelayMin: Int,
        avgSpeedKmh: Double,
        isOsrm: Boolean,
        isOffline: Boolean
    ): Int {
        if (isOffline) return 0
        if (trafficDelayMin >= 5) {
            return when {
                frac in 0.32..0.68 -> 2
                frac in 0.16..0.84 -> 1
                else -> 0
            }
        }
        if (trafficDelayMin in 1..4) {
            return when {
                frac in 0.42..0.56 -> if (trafficDelayMin >= 2) 2 else 1
                frac in 0.24..0.76 -> 1
                else -> 0
            }
        }
        if (isOsrm) {
            return if (avgSpeedKmh < 50.0) {
                when {
                    frac in 0.44..0.56 -> 2
                    frac in 0.26..0.74 -> 1
                    else -> 0
                }
            } else {
                if (frac in 0.35..0.65) 1 else 0
            }
        }
        return if (avgSpeedKmh < 28.0 && frac in 0.40..0.60) 1 else 0
    }

    private fun synthesizePath(
        oLat: Double,
        oLon: Double,
        dLat: Double,
        dLon: Double,
        count: Int = 8
    ): List<Pair<Double, Double>> {
        val total = count.coerceAtLeast(2)
        val dLatSpan = dLat - oLat
        val dLonSpan = dLon - oLon
        val len = hypot(dLatSpan, dLonSpan)
        val perpLat = if (len > 1e-6) -dLonSpan / len else 0.0
        val perpLon = if (len > 1e-6) dLatSpan / len else 0.0
        val arcAmp = len * 0.12
        return (0 until total).map { i ->
            val t = i.toDouble() / (total - 1)
            val bend = sin(PI * t) * arcAmp
            (oLat + dLatSpan * t + perpLat * bend) to (oLon + dLonSpan * t + perpLon * bend)
        }
    }

    companion object {
        private const val TAG = "CommuteProvider"

        internal fun isRedactedPlaceholder(key: String): Boolean {
            val t = key.trim()
            return t == "••••••" || t.all { it == '•' || it == '*' } && t.isNotEmpty()
        }

        internal fun isUsableApiKey(key: String): Boolean {
            val t = key.trim()
            return t.isNotEmpty() && !isRedactedPlaceholder(t)
        }

        internal fun maskKeyInUrl(url: String, encodedKey: String): String {
            if (encodedKey.isEmpty()) return url
            val masked = if (encodedKey.length <= 6) {
                "***"
            } else {
                "${encodedKey.take(3)}***${encodedKey.takeLast(3)}"
            }
            return url.replace("key=$encodedKey", "key=$masked")
        }

        fun decodePolyline(encoded: String): List<Pair<Double, Double>> =
            decodeEncodedPolyline(encoded)

        private fun decodeEncodedPolyline(encoded: String): List<Pair<Double, Double>> {
            if (encoded.isEmpty()) return emptyList()
            val poly = ArrayList<Pair<Double, Double>>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    if (index >= len) return poly
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                do {
                    if (index >= len) return poly
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dlng

                poly.add((lat / 1e5) to (lng / 1e5))
            }
            return poly
        }
    }
}
