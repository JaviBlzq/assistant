package com.javiblzq.assistant.providers

import android.os.Build
import android.util.Log
import com.javiblzq.assistant.data.HarbizWorkout
import com.javiblzq.assistant.net.Tls12SocketFactory
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

data class HarbizSessionResult(
    val accountId: String,
    val token: String,
    val expiresAt: String
)

data class HarbizSyncResult(
    val ok: Boolean,
    val message: String,
    val session: HarbizSessionResult? = null,
    val workouts: List<HarbizWorkout> = emptyList(),
    val authenticationRejected: Boolean = false
)

/**
 * Direct read-only Meteor DDP v1 WebSocket client matching semyra-sport's HarbizDdp protocol.
 * Enforces strictly read-only DDP calls: login, getAllCalEventsClient, and calevents.getById.
 */
class HarbizClient {

    private class MethodRejected(val reason: String) : Exception()
    private class TransportFailure(val phase: String, cause: Exception? = null) : Exception(cause)

    private class DdpSession(private val serverUri: URI) : WebSocketClient(serverUri, Draft_6455(), null, 12_000) {
        private val connectLatch = CountDownLatch(1)
        private val seq = AtomicInteger(0)
        private val pending = ConcurrentHashMap<String, CompletableCall>()
        @Volatile
        private var transportError: Exception? = null

        private class CompletableCall {
            val latch = CountDownLatch(1)
            @Volatile var result: Any? = null
            @Volatile var error: String? = null
        }

        fun configureSocket() {
            val factory = Tls12SocketFactory.socketFactory()
            if (Build.VERSION.SDK_INT > 22) {
                setSocketFactory(factory)
                return
            }

            // Layer TLS over a connected socket so Conscrypt receives the SNI hostname
            // and can use the underlying TCP socket on older Android releases.
            val tcp = Socket()
            try {
                tcp.connect(InetSocketAddress(serverUri.host, 443), 12_000)
                setSocket(factory.createSocket(tcp, serverUri.host, 443, true))
            } catch (e: Exception) {
                tcp.close()
                throw e
            }
        }

        override fun onSetSSLParameters(sslParameters: SSLParameters) {
            if (Build.VERSION.SDK_INT >= 24) {
                super.onSetSSLParameters(sslParameters)
                return
            }

            // The endpoint identification API is unavailable before Android 7.
            // Verify the peer before Java-WebSocket sends the upgrade request.
            val tls = socket as SSLSocket
            val timeout = tls.soTimeout
            try {
                tls.soTimeout = 12_000
                tls.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(serverUri.host, tls.session)) {
                    throw SSLPeerUnverifiedException("Hostname ${serverUri.host} could not be verified")
                }
            } finally {
                tls.soTimeout = timeout
            }
        }

        override fun onOpen(handshakedata: ServerHandshake?) {
            send(JSONObject().put("msg", "connect").put("version", "1").put("support", JSONArray().put("1")).toString())
        }

        override fun onMessage(message: String?) {
            if (message.isNullOrEmpty() || message.length > 4 * 1024 * 1024) return
            try {
                val m = JSONObject(message)
                when (m.optString("msg")) {
                    "connected" -> connectLatch.countDown()
                    "ping" -> {
                        val pong = JSONObject().put("msg", "pong")
                        if (m.has("id")) pong.put("id", m.optString("id"))
                        send(pong.toString())
                    }
                    "failed" -> {
                        transportError = IllegalStateException("Unsupported DDP version")
                        connectLatch.countDown()
                    }
                    "result" -> {
                        val id = m.optString("id", "")
                        val call = pending.remove(id) ?: return
                        if (m.has("error") && !m.isNull("error")) {
                            val err = m.optJSONObject("error")
                            call.error = err?.optString("reason")?.takeIf { it.isNotBlank() }
                                ?: err?.optString("message")?.takeIf { it.isNotBlank() }
                                ?: m.optString("error").ifBlank { "Harbiz error" }
                        } else {
                            call.result = m.opt("result")
                        }
                        call.latch.countDown()
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            failTransport(SocketException("WebSocket closed ($code)"))
        }

        override fun onError(ex: Exception?) {
            failTransport(ex ?: IllegalStateException("WebSocket error"))
        }

        private fun failTransport(error: Exception) {
            if (transportError == null) transportError = error
            connectLatch.countDown()
            for (call in pending.values) {
                call.latch.countDown()
            }
            pending.clear()
        }

        fun openDdp() {
            try {
                configureSocket()
                if (!connectBlocking(20, TimeUnit.SECONDS)) {
                    throw transportError ?: SocketTimeoutException()
                }
            } catch (e: Exception) {
                throw TransportFailure("WebSocket", e)
            }
            if (!connectLatch.await(15, TimeUnit.SECONDS)) {
                throw TransportFailure("DDP", SocketTimeoutException())
            }
            transportError?.let { throw TransportFailure("DDP", it) }
        }

        fun callMethod(method: String, params: JSONArray, timeoutMs: Long = 18_000): Any? {
            require(method in ALLOWED_METHODS) { "Method $method is not permitted" }
            transportError?.let { throw TransportFailure(method, it) }
            val id = seq.incrementAndGet().toString()
            val call = CompletableCall()
            pending[id] = call
            val payload = JSONObject()
                .put("msg", "method")
                .put("method", method)
                .put("params", params)
                .put("id", id)
            try {
                send(payload.toString())
                if (!call.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw TransportFailure(method, transportError ?: SocketTimeoutException())
                }
                call.error?.let { throw MethodRejected(it) }
                transportError?.let { throw TransportFailure(method, it) }
                return call.result
            } finally {
                pending.remove(id)
            }
        }
    }

    fun connectWithCredentials(emailOrUsername: String, password: String): HarbizSyncResult {
        val identifier = emailOrUsername.trim()
        val isEmail = identifier.contains("@")
        val userField = if (isEmail) "email" else "username"
        val pwObj = JSONObject()
            .put("digest", sha256Hex(password))
            .put("algorithm", "sha-256")
        fun authParams(login: String): JSONArray = JSONArray().put(JSONObject()
            .put("user", JSONObject().put(userField, login))
            .put("password", pwObj))

        return executeSync { client ->
            try {
                client.callMethod("login", authParams(identifier))
            } catch (e: MethodRejected) {
                val lower = identifier.lowercase(Locale.ROOT)
                val message = e.reason
                if (isEmail && lower != identifier &&
                    (message.contains("User not found", ignoreCase = true) ||
                        message.contains("Usuario no encontrado", ignoreCase = true))) {
                    client.callMethod("login", authParams(lower))
                } else {
                    throw e
                }
            }
        }
    }

    fun connectWithToken(token: String): HarbizSyncResult = resume(token)

    fun syncWithToken(token: String, timezoneId: String): HarbizSyncResult = resume(token, timezoneId)

    private fun resume(token: String, timezoneId: String? = null): HarbizSyncResult {
        if (token.isBlank()) {
            return HarbizSyncResult(ok = false, message = "Sin token de Harbiz")
        }
        val authParams = JSONArray().put(JSONObject().put("resume", token.trim()))
        return executeSync(timezoneId) { it.callMethod("login", authParams) }
    }

    private fun openSession(): DdpSession {
        for (attempt in 1..2) {
            val client = DdpSession(URI("wss://app.harbiz.io/websocket"))
            client.connectionLostTimeout = 20
            try {
                client.openDdp()
                Log.i(TAG, "WebSocket TLS y DDP conectados")
                return client
            } catch (e: Exception) {
                client.close()
                val cause = e.cause
                if (attempt == 2 || (cause !is SocketTimeoutException && cause !is SocketException)) throw e
                Log.w(TAG, "Reintento de conexión: ${cause.javaClass.simpleName}")
                Thread.sleep(1_000)
            }
        }
        error("No se pudo abrir Harbiz")
    }

    private fun executeSync(timezoneId: String? = null, login: (DdpSession) -> Any?): HarbizSyncResult {
        var client: DdpSession? = null
        var session: HarbizSessionResult? = null
        return try {
            client = openSession()
            val authRaw = login(client) as? JSONObject
                ?: return HarbizSyncResult(ok = false, message = "Respuesta de login inválida")
            val token = authRaw.optString("token", "")
            val accountId = authRaw.optString("id", "")
            val expiresMs = HarbizParser.parseEjsonTimestampMs(authRaw.opt("tokenExpires")) ?: 0L
            if (token.isEmpty()) {
                return HarbizSyncResult(ok = false, message = "Harbiz no devolvió token de sesión")
            }
            session = HarbizSessionResult(
                accountId = accountId,
                token = token,
                expiresAt = if (expiresMs > 0) formatUtcIso(expiresMs) else ""
            )
            Log.i(TAG, "Sesión aceptada")
            if (timezoneId == null) return HarbizSyncResult(true, "SESIÓN Harbiz VALIDADA", session)

            // Look ahead 7 days so upcoming scheduled routines (e.g. today's workout) are visible
            val cursorMs = System.currentTimeMillis() + 7L * 86_400_000L
            val calParams = JSONArray()
                .put(-1)
                .put(JSONObject().put("\$date", cursorMs))
                .put(JSONObject.NULL)
            val calArr = client.callMethod("getAllCalEventsClient", calParams) as? JSONArray
                ?: return HarbizSyncResult(false, "Harbiz devolvió un calendario inválido.", session)
            val recentCutoff = System.currentTimeMillis() - 7L * 86_400_000L
            val discovered = HarbizParser.discoverWorkoutEvents(calArr)
            val selected = discovered.filter { it.scheduledAtMs >= recentCutoff } +
                discovered.filter { it.scheduledAtMs < recentCutoff }.take(14)

            val workouts = mutableListOf<HarbizWorkout>()
            for (summary in selected) {
                val detailRaw = try {
                    client.callMethod("calevents.getById", JSONArray().put(summary.id)) as? JSONObject
                } catch (_: MethodRejected) {
                    null
                }
                HarbizParser.parseWorkoutDetail(detailRaw ?: JSONObject(), timezoneId, summary)?.let(workouts::add)
            }

            HarbizSyncResult(
                ok = true,
                message = "OK (${workouts.size} sesiones)",
                session = session,
                workouts = workouts
            )
        } catch (e: MethodRejected) {
            Log.w(TAG, if (session == null) "Autenticación rechazada" else "Lectura del calendario rechazada")
            val message = when {
                session != null -> "Harbiz aceptó la sesión, pero rechazó la lectura del calendario. Comprueba el perfil de cliente."
                timezoneId != null -> "Harbiz rechazó la sesión guardada. Vuelve a conectar tu cuenta en el portal."
                else -> "Harbiz rechazó el inicio de sesión. Comprueba tus credenciales o tu token en Harbiz."
            }
            HarbizSyncResult(false, message, session, authenticationRejected = session == null)
        } catch (e: TransportFailure) {
            Log.w(TAG, "${e.phase}: ${e.cause?.javaClass?.simpleName}")
            val message = if (e.cause is SSLException) {
                "Harbiz: error TLS. Comprueba la fecha y hora de la tablet."
            } else {
                "Harbiz: fallo de conexión durante ${e.phase}. Inténtalo de nuevo."
            }
            HarbizSyncResult(false, message, session)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Sincronización: ${e.javaClass.simpleName}")
            HarbizSyncResult(false, "Harbiz: no se pudo completar la sincronización.", session)
        } finally {
            try {
                client?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "AssistantHarbiz"
        private val ALLOWED_METHODS = setOf("login", "getAllCalEventsClient", "calevents.getById")

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun formatUtcIso(epochMs: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(epochMs))
        }
    }
}
