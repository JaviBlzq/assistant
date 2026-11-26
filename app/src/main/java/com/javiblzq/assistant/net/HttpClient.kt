package com.javiblzq.assistant.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

data class HttpResponse(
    val code: Int,
    val body: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = code in 200..299 && error == null
}

object HttpClient {
    private const val USER_AGENT = "SemyraAssistantTablet/1.0"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 12000
    ): HttpResponse = request("GET", url, null, null, headers, timeoutMs)

    fun head(url: String, timeoutMs: Int): HttpResponse =
        request("HEAD", url, null, null, emptyMap(), timeoutMs)

    fun postForm(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): HttpResponse {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return request(
            method = "POST",
            url = url,
            body = body,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            headers = headers,
            timeoutMs = timeoutMs
        )
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000
    ): HttpResponse = request(
        method = "POST",
        url = url,
        body = jsonBody,
        contentType = "application/json; charset=UTF-8",
        headers = headers,
        timeoutMs = timeoutMs
    )

    fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileFieldName: String,
        fileName: String,
        fileContentType: String,
        fileBytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000
    ): HttpResponse {
        val boundary = "----AssistantBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    sslSocketFactory = Tls12SocketFactory.socketFactory()
                }
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, */*")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                outputStream.use { out ->
                    for ((k, v) in fields) {
                        out.write("--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
                        out.write("Content-Disposition: form-data; name=\"$k\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                        out.write(v.toByteArray(Charsets.UTF_8))
                        out.write(lineEnd.toByteArray(Charsets.UTF_8))
                    }
                    val safeName = fileName.replace("\"", "_")
                    out.write("--$boundary$lineEnd".toByteArray(Charsets.UTF_8))
                    out.write(
                        "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$safeName\"$lineEnd"
                            .toByteArray(Charsets.UTF_8)
                    )
                    out.write("Content-Type: $fileContentType$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                    out.write(fileBytes)
                    out.write(lineEnd.toByteArray(Charsets.UTF_8))
                    out.write("--$boundary--$lineEnd".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            HttpResponse(code = code, body = text)
        } catch (e: Exception) {
            HttpResponse(code = -1, body = "", error = e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    sslSocketFactory = Tls12SocketFactory.socketFactory()
                }
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/calendar, text/plain, */*")
                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (body != null) {
                    doOutput = true
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
                }
            }
            val code = conn.responseCode
            if (method == "HEAD") return HttpResponse(code = code, body = "")
            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            HttpResponse(code = code, body = text)
        } catch (e: Exception) {
            HttpResponse(code = -1, body = "", error = e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
