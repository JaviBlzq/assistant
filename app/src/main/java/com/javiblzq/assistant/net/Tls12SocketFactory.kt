package com.javiblzq.assistant.net

import android.content.Context
import android.os.Build
import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Enables TLS 1.2 and later on every socket, including unconnected WebSockets.
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = patch(delegate.createSocket())

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        patch(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        patch(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        patch(delegate.createSocket(address, port, localAddress, localPort))

    private fun patch(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols ?: emptyArray()
            val wanted = listOf("TLSv1.2", "TLSv1.3").filter { it in supported }.toTypedArray()
            if (wanted.isNotEmpty()) {
                socket.enabledProtocols = wanted
            }
        }
        return socket
    }

    companion object {
        fun socketFactory(): SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        fun installIfNeeded(context: Context) {
            val sslContext = if (Build.VERSION.SDK_INT <= 22) {
                SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
                    init(null, arrayOf<TrustManager>(legacyTrustManager(context)), null)
                }
            } else {
                SSLContext.getInstance("TLS").apply { init(null, null, null) }
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(Tls12SocketFactory(sslContext.socketFactory))
        }

        private fun trustManager(store: KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(store)
                trustManagers.filterIsInstance<X509TrustManager>().single()
            }

        private fun legacyTrustManager(context: Context): X509TrustManager {
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            context.assets.open("certs/legacy-tls-roots.pem").use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input)
                    .forEachIndexed { index, cert -> roots.setCertificateEntry("root-$index", cert) }
            }
            val system = trustManager(null)
            val bundled = trustManager(roots)
            return object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    system.acceptedIssuers + bundled.acceptedIssuers

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (_: CertificateException) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }
            }
        }
    }
}
