package com.javiblzq.assistant.providers

import android.util.Base64
import com.javiblzq.assistant.data.AssistantConfig
import com.javiblzq.assistant.data.SmartBulbDevice
import com.javiblzq.assistant.net.HttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TapoSmartHomeClient {
    private val random = SecureRandom()

    fun setBulbState(
        config: AssistantConfig,
        bulb: SmartBulbDevice,
        turnOn: Boolean,
        brightness: Int? = null
    ): Pair<Boolean, String> {
        val webhook = if (turnOn) bulb.webhookOnUrl else bulb.webhookOffUrl
        if (webhook.isNotBlank()) {
            if (SemyraServiceMonitor.httpUri(webhook) == null) return false to "WEBHOOK INVÁLIDO"
            val response = HttpClient.get(webhook, timeoutMs = TIMEOUT_MS)
            return when {
                response.isSuccess -> true to "WEBHOOK OK"
                response.code < 0 -> false to "WEBHOOK SIN RESPUESTA"
                else -> false to "WEBHOOK HTTP ${response.code}"
            }
        }
        val (result, status) = requestLocal(config, bulb, setCommand(turnOn, brightness))
        return (result != null) to status
    }

    fun queryBulbState(config: AssistantConfig, bulb: SmartBulbDevice): Pair<Boolean?, String> {
        val (state, status) = readBulbState(config, bulb)
        return state?.isOn to status
    }

    internal data class BulbState(val isOn: Boolean, val brightness: Int?)

    internal fun readBulbState(config: AssistantConfig, bulb: SmartBulbDevice): Pair<BulbState?, String> {
        val (response, status) = requestLocal(config, bulb, JSONObject().put("method", "get_device_info"))
        if (response == null) return null to status
        val result = response.optJSONObject("result")
        val on = result?.opt("device_on") as? Boolean ?: return null to "ESTADO TAPO INVÁLIDO"
        return BulbState(on, result.optInt("brightness", 0).takeIf { it in 1..100 }) to status
    }

    private fun requestLocal(
        config: AssistantConfig,
        bulb: SmartBulbDevice,
        command: JSONObject
    ): Pair<JSONObject?, String> {
        if (bulb.ipAddress.isBlank()) return null to "SIN IP TAPO"
        if (!isLanAddress(bulb.ipAddress)) return null to "IP TAPO INVÁLIDA"
        if (config.tapoEmail.isBlank() || config.tapoPassword.isBlank()) return null to "SIN CREDENCIALES TAPO"
        val app = "http://${bulb.ipAddress}/app"
        return try {
            val klap = openKlap(app, config)
            val response = if (klap != null) sendKlap(app, klap, command)
                else sendLegacy(app, config, command)
            checkResponse(response)
            response to "TAPO OK"
        } catch (e: TapoError) {
            null to (e.message ?: "ERROR TAPO")
        } catch (_: Exception) {
            null to "TAPO SIN RESPUESTA VÁLIDA"
        }
    }

    private data class KlapSession(val cookie: String, val keys: KlapKeys)

    private fun openKlap(app: String, config: AssistantConfig): KlapSession? {
        val local = ByteArray(16).also(random::nextBytes)
        val first = post("$app/handshake1", local)
        if (first.code != 200 || first.body.size != 48) return null
        val remote = first.body.copyOfRange(0, 16)
        val serverHash = first.body.copyOfRange(16, 48)
        val v2 = klapAuthHash(config.tapoEmail, config.tapoPassword, v2 = true)
        val v1 = klapAuthHash(config.tapoEmail, config.tapoPassword, v2 = false)
        val useV2 = MessageDigest.isEqual(serverHash, sha256(local + remote + v2))
        val authHash = when {
            useV2 -> v2
            MessageDigest.isEqual(serverHash, sha256(local + v1)) -> v1
            else -> throw TapoError("CREDENCIALES TAPO RECHAZADAS")
        }
        val proof = sha256(if (useV2) remote + local + authHash else remote + authHash)
        val cookie = first.cookie ?: throw TapoError("SESIÓN TAPO INVÁLIDA")
        val second = post("$app/handshake2", proof, cookie)
        if (second.code != 200) throw TapoError("TAPO HTTP ${second.code}")
        return KlapSession(second.cookie ?: cookie, deriveKlapKeys(local, remote, authHash))
    }

    private fun sendKlap(app: String, session: KlapSession, command: JSONObject): JSONObject {
        val keys = session.keys
        val sequence = keys.initialSequence + 1
        val sequenceBytes = ByteBuffer.allocate(4).putInt(sequence).array()
        val iv = keys.ivPrefix + sequenceBytes
        val encrypted = aes(Cipher.ENCRYPT_MODE, keys.key, iv, command.toString().toByteArray())
        val signed = sha256(keys.signaturePrefix + sequenceBytes + encrypted) + encrypted
        val reply = post("$app/request?seq=$sequence", signed, session.cookie)
        if (reply.code != 200) throw TapoError("TAPO HTTP ${reply.code}")
        if (reply.body.size < 48) throw TapoError("RESPUESTA TAPO INVÁLIDA")
        val ciphertext = reply.body.copyOfRange(32, reply.body.size)
        val signature = reply.body.copyOfRange(0, 32)
        if (!MessageDigest.isEqual(signature, sha256(keys.signaturePrefix + sequenceBytes + ciphertext))) {
            throw TapoError("FIRMA TAPO INVÁLIDA")
        }
        return JSONObject(aes(Cipher.DECRYPT_MODE, keys.key, iv, ciphertext).toString(Charsets.UTF_8))
    }

    private fun sendLegacy(app: String, config: AssistantConfig, command: JSONObject): JSONObject {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = "-----BEGIN PUBLIC KEY-----\n${base64(pair.public.encoded)}\n-----END PUBLIC KEY-----\n"
        val handshake = postJson(app, jsonCommand("handshake", JSONObject().put("key", publicKey)))
        val result = parseHttpJson(handshake)
        val encrypted = decode64(result.getJSONObject("result").getString("key"))
        val material = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, pair.private)
        }.doFinal(encrypted)
        if (material.size != 32) throw TapoError("CLAVE TAPO INVÁLIDA")
        val key = material.copyOfRange(0, 16)
        val iv = material.copyOfRange(16, 32)
        val cookie = handshake.cookie ?: throw TapoError("SESIÓN TAPO INVÁLIDA")

        fun secureRequest(url: String, inner: JSONObject): JSONObject {
            val request = base64(aes(Cipher.ENCRYPT_MODE, key, iv, inner.toString().toByteArray()))
            val wrapper = jsonCommand("securePassthrough", JSONObject().put("request", request))
            val reply = parseHttpJson(postJson(url, wrapper, cookie))
            val payload = reply.getJSONObject("result").getString("response")
            return JSONObject(aes(Cipher.DECRYPT_MODE, key, iv, decode64(payload)).toString(Charsets.UTF_8))
        }

        val hashedUser = digest("SHA-1", config.tapoEmail.toByteArray()).joinToString("") {
            String.format(Locale.US, "%02x", it.toInt() and 255)
        }
        val credentials = JSONObject().put("username", base64(hashedUser.toByteArray()))
            .put("password", base64(config.tapoPassword.toByteArray()))
        val login = secureRequest(
            app,
            jsonCommand("login_device", credentials).put("request_time_milis", System.currentTimeMillis())
        )
        checkResponse(login)
        val token = login.getJSONObject("result").getString("token")
        if (token.isBlank()) throw TapoError("SESIÓN TAPO INVÁLIDA")
        return secureRequest("$app?token=${URLEncoder.encode(token, "UTF-8")}", command)
    }

    private class TapoError(message: String) : Exception(message)

    private data class Reply(val code: Int, val body: ByteArray, val cookie: String?)

    private fun postJson(url: String, body: JSONObject, cookie: String? = null): Reply =
        post(url, body.toString().toByteArray(), cookie, "application/json")

    private fun post(
        url: String,
        body: ByteArray,
        cookie: String? = null,
        contentType: String = "application/octet-stream"
    ): Reply {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("requestByApp", "true")
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val session = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                .map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("TP_SESSIONID=") }
            val response = if (code == 200) connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                val received = ByteArrayOutputStream()
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    if (received.size() + bytes > 65_536) throw TapoError("RESPUESTA TAPO INVÁLIDA")
                    received.write(buffer, 0, bytes)
                }
                received.toByteArray()
            } else ByteArray(0)
            return Reply(code, response, session)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHttpJson(reply: Reply): JSONObject {
        if (reply.code != 200) throw TapoError("TAPO HTTP ${reply.code}")
        return JSONObject(reply.body.toString(Charsets.UTF_8)).also(::checkResponse)
    }

    private fun checkResponse(reply: JSONObject) {
        val code = reply.optInt("error_code", Int.MIN_VALUE)
        if (code == Int.MIN_VALUE) throw TapoError("RESPUESTA TAPO INVÁLIDA")
        if (code != 0) throw TapoError("TAPO ERROR $code")
    }

    internal data class KlapKeys(
        val key: ByteArray,
        val ivPrefix: ByteArray,
        val initialSequence: Int,
        val signaturePrefix: ByteArray
    )

    companion object {
        private const val TIMEOUT_MS = 3_000
        private val ipv4 = Regex("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")

        internal fun isLanAddress(ip: String): Boolean {
            if (!ipv4.matches(ip)) return false
            val parts = ip.split('.').map(String::toInt)
            return parts.all { it in 0..255 } && (
                parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                    (parts[0] == 192 && parts[1] == 168)
                )
        }

        internal fun setCommand(on: Boolean, brightness: Int?): JSONObject {
            val params = JSONObject().put("device_on", on)
            if (on && brightness != null) params.put("brightness", brightness.coerceIn(1, 100))
            return jsonCommand("set_device_info", params)
        }

        private fun jsonCommand(method: String, params: JSONObject): JSONObject =
            JSONObject().put("method", method).put("params", params)

        internal fun klapAuthHash(email: String, password: String, v2: Boolean): ByteArray {
            val inner = if (v2) "SHA-1" else "MD5"
            val outer = if (v2) "SHA-256" else "MD5"
            return digest(outer, digest(inner, email.toByteArray()) + digest(inner, password.toByteArray()))
        }

        internal fun deriveKlapKeys(local: ByteArray, remote: ByteArray, authHash: ByteArray): KlapKeys {
            val seed = local + remote + authHash
            val iv = sha256("iv".toByteArray() + seed)
            return KlapKeys(
                sha256("lsk".toByteArray() + seed).copyOf(16),
                iv.copyOf(12),
                ByteBuffer.wrap(iv, 28, 4).int,
                sha256("ldk".toByteArray() + seed).copyOf(28)
            )
        }

        private fun sha256(bytes: ByteArray): ByteArray = digest("SHA-256", bytes)
        private fun digest(name: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(name).digest(bytes)
        private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
        private fun aes(mode: Int, key: ByteArray, iv: ByteArray, bytes: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }.doFinal(bytes)
    }
}
