package com.javiblzq.assistant.providers

import android.util.Log
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.StravaActivitySummary
import com.javiblzq.assistant.data.StravaRunAnalysis
import com.javiblzq.assistant.net.HttpClient
import com.javiblzq.assistant.net.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class StravaTokenBundle(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val athleteName: String
)

data class StravaSyncResult(
    val ok: Boolean,
    val statusText: String,
    val updatedTokens: StravaTokenBundle? = null,
    val activities: List<StravaActivitySummary> = emptyList(),
    val latestRunAnalysis: StravaRunAnalysis? = null
)

class StravaClient {

    companion object {
        private const val TAG = "AssistantStrava"
        internal const val ACTIVITY_PERMISSION_REQUIRED =
            "Strava: vuelve a autorizar y concede el permiso para leer tus actividades."
        private val rawCode = Regex("[0-9a-fA-F]{40}")
        private val activityScopes = setOf("activity:read", "activity:read_all")

        internal fun hasActivityScope(scope: String): Boolean =
            scope.split(',', ' ').any { it in activityScopes }

        internal fun authorizationCodeFrom(input: String): String? {
            val value = input.trim()
            if (rawCode.matches(value)) return value
            return callbackParameter(value, "code")
        }

        internal fun callbackParameter(input: String, name: String): String? {
            val encoded = Regex("(?:^|[?&])${name}=([^&#]+)")
                .find(input.substringBefore('#'))?.groupValues?.get(1) ?: return null
            return try {
                URLDecoder.decode(encoded, "UTF-8").trim().takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    fun exchangeAuthorizationCode(
        clientId: String,
        clientSecret: String,
        code: String
    ): Pair<StravaTokenBundle?, String> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return null to "Guarda primero tu Strava Client ID y Client Secret en el portal."
        }
        val resp = HttpClient.postForm(
            url = "https://www.strava.com/oauth/token",
            params = mapOf(
                "client_id" to clientId.trim(),
                "client_secret" to clientSecret.trim(),
                "code" to code.trim(),
                "grant_type" to "authorization_code"
            )
        )
        if (!resp.isSuccess) {
            return null to failureMessage(resp, "canje del código")
        }
        return parseTokenResponse(resp.body)
    }

    fun refreshAccessToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String
    ): Pair<StravaTokenBundle?, String> {
        val resp = HttpClient.postForm(
            url = "https://www.strava.com/oauth/token",
            params = mapOf(
                "client_id" to clientId.trim(),
                "client_secret" to clientSecret.trim(),
                "refresh_token" to refreshToken.trim(),
                "grant_type" to "refresh_token"
            )
        )
        if (!resp.isSuccess) {
            return null to failureMessage(resp, "renovación del token")
        }
        return parseTokenResponse(resp.body)
    }

    private fun parseTokenResponse(body: String): Pair<StravaTokenBundle?, String> {
        return try {
            val o = JSONObject(body)
            val access = o.optString("access_token", "")
            val refresh = o.optString("refresh_token", "")
            val exp = o.optLong("expires_at", 0L)
            val ath = o.optJSONObject("athlete")
            val name = listOfNotNull(
                ath?.optString("firstname", "")?.takeIf { it.isNotBlank() },
                ath?.optString("lastname", "")?.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            if (access.isEmpty() || refresh.isEmpty() || exp <= 0L) {
                null to "Tokens vacíos en respuesta Strava"
            } else {
                StravaTokenBundle(access, refresh, exp, name) to "OK"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Respuesta de tokens inválida: ${e.javaClass.simpleName}")
            null to "Strava devolvió una respuesta de tokens inválida."
        }
    }

    private fun failureMessage(response: HttpResponse, operation: String): String {
        Log.w(TAG, "${operation}: HTTP ${response.code}")
        return when (response.code) {
            -1 -> "Strava: fallo de conexión durante ${operation}."
            400 -> when (operation) {
                "canje del código" -> "Strava rechazó el código (HTTP 400). Vuelve a autorizar y usa la URL recién generada. Comprueba el Client ID y el Client Secret."
                "renovación del token" -> "Strava rechazó la renovación (HTTP 400). Comprueba las credenciales de la aplicación y vuelve a autorizar."
                else -> "Strava: solicitud inválida durante ${operation} (HTTP 400)."
            }
            401, 403 -> if (isMissingActivityPermission(response)) ACTIVITY_PERMISSION_REQUIRED
                else "Strava rechazó el acceso (HTTP ${response.code}). Vuelve a autorizar en el portal."
            429 -> "Strava ha limitado las solicitudes. Inténtalo más tarde."
            else -> "Strava: fallo durante ${operation} (HTTP ${response.code})."
        }
    }

    private fun isMissingActivityPermission(response: HttpResponse): Boolean {
        if (response.code != 401 && response.code != 403) return false
        val errors = try {
            JSONObject(response.body).optJSONArray("errors")
        } catch (_: Exception) {
            null
        } ?: return false
        return (0 until errors.length()).any {
            val error = errors.optJSONObject(it)
            error?.optString("field") == "activity:read_permission" && error.optString("code") == "missing"
        }
    }

    fun sync(config: AssistantConfig): StravaSyncResult {
        var accessToken = config.stravaAccessToken
        var updatedBundle: StravaTokenBundle? = null
        val nowSec = System.currentTimeMillis() / 1000L

        if (config.stravaRefreshToken.isBlank() && accessToken.isBlank()) {
            return StravaSyncResult(ok = false, statusText = "NO CONFIGURADO")
        }

        val canRefresh = config.stravaClientId.isNotBlank() &&
            config.stravaClientSecret.isNotBlank() &&
            config.stravaRefreshToken.isNotBlank()
        fun refresh() = refreshAccessToken(config.stravaClientId, config.stravaClientSecret, config.stravaRefreshToken)
        if (canRefresh && (accessToken.isBlank() || config.stravaExpiresAt <= nowSec + 90)) {
            val (refreshed, msg) = refresh()
            if (refreshed == null) {
                return StravaSyncResult(ok = false, statusText = msg)
            }
            accessToken = refreshed.accessToken
            updatedBundle = refreshed
        }

        if (accessToken.isBlank()) {
            return StravaSyncResult(ok = false, statusText = "FALTA CLIENT ID/SECRET")
        }

        fun activitiesResponse() = HttpClient.get(
            url = "https://www.strava.com/api/v3/athlete/activities?per_page=30",
            headers = mapOf("Authorization" to "Bearer $accessToken")
        )
        var listResp = activitiesResponse()
        if (listResp.code == 401 && !isMissingActivityPermission(listResp) && canRefresh && updatedBundle == null) {
            val (refreshed, msg) = refresh()
            if (refreshed == null) return StravaSyncResult(ok = false, statusText = msg)
            accessToken = refreshed.accessToken
            updatedBundle = refreshed
            listResp = activitiesResponse()
        }
        if (!listResp.isSuccess) {
            return StravaSyncResult(
                ok = false,
                statusText = failureMessage(listResp, "lectura de actividades"),
                updatedTokens = updatedBundle
            )
        }

        val activities = try {
            parseActivities(JSONArray(listResp.body))
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "Respuesta de actividades inválida")
            return StravaSyncResult(false, "Strava devolvió una lista de actividades inválida.", updatedBundle)
        }
        val latestRun = activities.firstOrNull { it.isRun }
        var runAnalysis: StravaRunAnalysis? = null

        if (latestRun != null) {
            val streamResp = HttpClient.get(
                url = "https://www.strava.com/api/v3/activities/${latestRun.id}/streams?keys=time,distance,velocity_smooth,heartrate,cadence,altitude&key_by_type=true",
                headers = mapOf("Authorization" to "Bearer $accessToken")
            )
            val samples = try {
                if (streamResp.isSuccess) parseStreams(JSONObject(streamResp.body)) else emptyList()
            } catch (_: org.json.JSONException) {
                Log.w(TAG, "Respuesta de métricas inválida")
                emptyList()
            }
            runAnalysis = StravaAnalyzer.analyzeRun(latestRun, samples, config.hrZones)
        }

        return StravaSyncResult(
            ok = true,
            statusText = "OK (${activities.size})",
            updatedTokens = updatedBundle,
            activities = activities,
            latestRunAnalysis = runAnalysis
        )
    }

    private fun parseActivities(arr: JSONArray): List<StravaActivitySummary> {
        val out = ArrayList<StravaActivitySummary>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val startLocal = o.optString("start_date_local", "")
            val dateIso = if (startLocal.length >= 10) startLocal.substring(0, 10) else ""
            val sportType = o.optString("sport_type", o.optString("type", "Workout"))
            val rawCad = if (o.has("average_cadence") && !o.isNull("average_cadence")) o.optDouble("average_cadence") else null
            val cadSpm = if (rawCad != null && sportType in setOf("Run", "TrailRun", "VirtualRun")) rawCad * 2.0 else rawCad
            out.add(
                StravaActivitySummary(
                    id = o.optLong("id"),
                    name = o.optString("name", sportType),
                    sportType = sportType,
                    startDateLocalIso = startLocal,
                    dateIso = dateIso,
                    distanceM = o.optDouble("distance", 0.0),
                    movingTimeS = o.optInt("moving_time", 0),
                    elapsedTimeS = o.optInt("elapsed_time", 0),
                    elevationGainM = o.optDouble("total_elevation_gain", 0.0),
                    averageSpeedMps = o.optDouble("average_speed", 0.0),
                    averageHeartRate = if (o.has("average_heartrate") && !o.isNull("average_heartrate")) o.optDouble("average_heartrate") else null,
                    maxHeartRate = if (o.has("max_heartrate") && !o.isNull("max_heartrate")) o.optDouble("max_heartrate") else null,
                    averageCadenceSpm = cadSpm
                )
            )
        }
        return out
    }

    private fun parseStreams(root: JSONObject): List<StreamSample> {
        val timeArr = root.optJSONObject("time")?.optJSONArray("data") ?: return emptyList()
        val velArr = root.optJSONObject("velocity_smooth")?.optJSONArray("data")
        val hrArr = root.optJSONObject("heartrate")?.optJSONArray("data")
        val movingArr = root.optJSONObject("moving")?.optJSONArray("data")

        val out = ArrayList<StreamSample>(timeArr.length())
        for (i in 0 until timeArr.length()) {
            val t = timeArr.optDouble(i, Double.NaN)
            if (!t.isFinite()) continue
            val spd = velArr?.optDouble(i, Double.NaN)?.takeIf { it.isFinite() }
            val hr = hrArr?.optDouble(i, Double.NaN)?.takeIf { it.isFinite() }
            val moving = movingArr?.optBoolean(i, true) ?: true
            out.add(StreamSample(t, spd, hr, moving))
        }
        return out
    }
}
