package cloud.charging.v2g.session

import cloud.charging.v2g.bridge.SessionConfig
import cloud.charging.v2g.certificates.InMemoryTrustStore
import cloud.charging.v2g.certificates.V2GCertificate
import cloud.charging.v2g.certificates.V2GCertificateChain
import cloud.charging.v2g.certificates.V2GChainValidator
import cloud.charging.v2g.certificates.V2GFingerprint
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The socket half of a live session: TCP to a station, optionally wrapped in TLS.
 *
 * Small on purpose. Everything a laptop can check lives above this file; what is left here is the
 * part only a real network exercises, so the less of it there is, the better.
 *
 * ## This resolves host names, and that is not a contradiction
 *
 * `PairingWarnings.isPrivateTarget` refuses to resolve anything, because it judges a code **nobody
 * has agreed to trust yet** — a lookup there would already be a callback to whoever printed it. By
 * the time a [SessionConfig] exists, a human has read the confirmation sheet and said yes, and the
 * whole point of saying yes is to open a connection. A `.local` name cannot be reached without
 * resolving it.
 *
 * The restriction still holds, though, and it holds *before* this: `SessionConfig.parse` refuses any
 * host that is not private or link-local, so what gets resolved here is never an arbitrary name.
 */
object TcpV2GTransport {

    /**
     * @param connectTimeoutMillis how long to wait for the TCP handshake.
     * @param readTimeoutMillis    how long a single read may block. A station that stops answering
     *                             mid-session is a failed session, not a thread parked forever.
     */
    fun connect(config: SessionConfig,
                connectTimeoutMillis: Int = 10_000,
                readTimeoutMillis:    Int = 30_000): V2GConnection {

        val socket = Socket()

        try {
            socket.connect(InetSocketAddress(config.host, config.port), connectTimeoutMillis)
            socket.soTimeout   = readTimeoutMillis
            socket.tcpNoDelay  = true      // V2GTP is request/response; Nagle only adds latency here
        } catch (e: Exception) {
            socket.close()
            throw e
        }

        if (config.transport == "tcp")
            return SocketConnection(socket)

        val pin = config.rootFingerprint
            ?: throw CertificateException(
                "a TLS session needs the root fingerprint from the pairing code, and this " +
                "configuration carries none. The confirmation sheet reports that as " +
                "'noTrustAnchor'; connecting anyway would mean trusting whoever answers.")

        return try {

            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(PinnedRootTrustManager(hex(pin))), null)

            val ssl = context.socketFactory
                .createSocket(socket, config.host, config.port, true) as SSLSocket

            ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")

            // No endpoint identification, deliberately. That algorithm checks the certificate's
            // subject against the host name, which is how the web decides identity — and a SECC's
            // certificate carries an EVSE id, not a DNS name or the address the pairing code
            // happened to print. Identity here is the pinned root plus a chain that reaches it.
            ssl.startHandshake()

            SocketConnection(ssl)

        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }


    private fun hex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != ':' }
        require(clean.length % 2 == 0) { "'$text' is not a hex fingerprint." }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}


private class SocketConnection(private val socket: Socket) : V2GConnection {
    override val input:  InputStream  get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override fun close() = socket.close()
}


/**
 * Trusts exactly one root: the one the pairing code named, and only if the station actually sends it.
 *
 * **Not the platform trust store.** A V2G root is not a web CA and is not installed on a phone, so
 * falling back to the system anchors would not add a second opinion — it would replace the only
 * opinion there is with one that cannot possibly apply.
 *
 * The station has to transmit the root itself. That is what makes a fingerprint enough for a freshly
 * installed app: the code carries 32 bytes, the station carries the certificate, and neither alone
 * would do.
 */
private class PinnedRootTrustManager(private val pin: ByteArray) : X509ExtendedTrustManager() {

    private fun check(chain: Array<out X509Certificate>?) {

        if (chain.isNullOrEmpty())
            throw CertificateException("the station presented no certificate.")

        val presented = chain.map { V2GCertificate(it.encoded) }

        val anchor = presented.firstOrNull { V2GFingerprint.of(it.der).bytes.contentEquals(pin) }
            ?: throw CertificateException(
                "none of the ${presented.size} certificate(s) the station presented is the root the " +
                "pairing code pinned. The station has to send its root for the fingerprint to be " +
                "checkable at all.")

        // A self-signed station certificate that IS the pinned root: the pin is the whole identity,
        // and there is no path left to build. Legitimate for a single-station setup, and refusing it
        // would mean the pin only works when somebody also runs a CA.
        if (presented.size == 1) return

        val verdict = V2GChainValidator().validate(
            V2GCertificateChain.of(presented.filter { it !== anchor }.map { it.der }),
            InMemoryTrustStore(listOf(anchor)))

        verdict.rejection?.let {
            throw CertificateException("the station's certificate chain was rejected: $it")
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    // This side is the EV. It never validates a client, and a trust manager that quietly accepted
    // one would be a trust manager doing something nobody asked it to.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("this trust manager is the EV's; it validates stations, not clients.")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
