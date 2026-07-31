package cloud.charging.v2g.pairing

import java.net.URI

enum class PairingTransport { TLS, TCP }

/**
 * A scanned pairing code, parsed.
 *
 * Everything here is **untrusted input**. A pairing code is an image on a display that anyone can
 * tape over, and malicious stickers at public chargers are an established attack. So this type
 * deliberately *classifies* rather than decides: it reports what the code asks for and what is wrong
 * with it, and the decision to connect belongs to a human looking at a confirmation sheet.
 *
 * Unknown parameters are kept in [extra] rather than dropped or rejected. A newer Pi must be able to
 * talk to an older app — and an app that silently discards what it does not understand cannot warn
 * that the code contained something it could not read. Nothing interprets [extra], which is exactly
 * why carrying it is safe.
 *
 * A port of the C# `PairingPayload`, held to `Vectors/Pairing.payload.vectors.json`.
 */
data class PairingPayload(
    val version: Int,
    val host: String,
    val port: Int,
    val transport: PairingTransport,
    val protocols: List<String> = emptyList(),
    val crypto: String? = null,
    val nonConformant: Boolean = false,
    /** The peer's own reason, **displayed verbatim** and never interpreted. */
    val nonConformanceReason: String? = null,
    val rootFingerprint: String? = null,
    val meter: String? = null,
    val totp: String? = null,
    val evseId: String? = null,
    val tariffId: String? = null,
    val currency: String? = null,
    val uiLanguage: String? = null,
    val wifiSsid: String? = null,
    val wifiPsk: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val warnings: List<PairingWarning> get() = PairingWarnings.of(this)
}


/** The code was a pairing code and is broken — distinct from "that was some other QR code", which
 *  is a shrug rather than something to tell the user about. */
class PairingFormatException(message: String) : Exception(message)


enum class PairingWarningKind {
    UNSUPPORTED_VERSION, PLAINTEXT_TRANSPORT, WEAKENED_CRYPTO, DECLARED_NON_CONFORMANCE,
    PUBLIC_TARGET, NO_TRUST_ANCHOR, NO_PROXIMITY_PROOF, CARRIES_WIFI_CREDENTIALS, UNKNOWN_PARAMETERS;

    /** The spelling the corpus uses and every back end reports. Kept apart from the enum's own name
     *  so a Kotlin rename cannot silently stop matching the other two. */
    val corpusName: String get() = name.split('_').mapIndexed { i, w ->
        if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

data class PairingWarning(val kind: PairingWarningKind, val detail: String) {
    /** Whether this alone should stop the code being offered as connectable at all. */
    val isBlocking: Boolean
        get() = kind == PairingWarningKind.UNSUPPORTED_VERSION || kind == PairingWarningKind.PUBLIC_TARGET
}


object PairingWarnings {

    /** The curve the -20 conformant profile requires. */
    const val CONFORMANT_CURVE = "secp521r1"

    fun of(payload: PairingPayload): List<PairingWarning> = buildList {

        if (payload.version != 1)
            add(PairingWarning(PairingWarningKind.UNSUPPORTED_VERSION,
                "payload version ${payload.version}; this build reads version 1"))

        if (payload.transport == PairingTransport.TCP)
            add(PairingWarning(PairingWarningKind.PLAINTEXT_TRANSPORT,
                "the counterpart offers plaintext TCP — the session will not be encrypted"))

        // "Unstated" is reported as well as "weakened". A code that says nothing about its curve is
        // not thereby conformant, and silence is the easiest thing for a hostile code to offer.
        if (payload.crypto == null)
            add(PairingWarning(PairingWarningKind.WEAKENED_CRYPTO,
                "no crypto profile stated; the -20 conformant profile is $CONFORMANT_CURVE"))
        else if (!payload.crypto.equals(CONFORMANT_CURVE, ignoreCase = true))
            add(PairingWarning(PairingWarningKind.WEAKENED_CRYPTO,
                "crypto profile is ${payload.crypto}, not the conformant $CONFORMANT_CURVE"))

        if (payload.nonConformant)
            add(PairingWarning(PairingWarningKind.DECLARED_NON_CONFORMANCE,
                payload.nonConformanceReason?.takeIf { it.isNotEmpty() }
                    ?: "the counterpart declares itself non-conformant but gives no reason"))

        if (!isPrivateTarget(payload.host))
            add(PairingWarning(PairingWarningKind.PUBLIC_TARGET,
                "${payload.host} is not a private or link-local address"))

        if (payload.rootFingerprint == null)
            add(PairingWarning(PairingWarningKind.NO_TRUST_ANCHOR,
                "no root fingerprint; the certificate chain cannot be checked against this code"))

        if (payload.totp == null)
            add(PairingWarning(PairingWarningKind.NO_PROXIMITY_PROOF,
                "static code — it proves nothing about being present now"))

        if (payload.wifiPsk != null)
            add(PairingWarning(PairingWarningKind.CARRIES_WIFI_CREDENTIALS,
                "the code carries the password for network ${payload.wifiSsid ?: "(unnamed)"}"))

        if (payload.extra.isNotEmpty())
            add(PairingWarning(PairingWarningKind.UNKNOWN_PARAMETERS,
                "unread parameters: " + payload.extra.keys.sorted().joinToString(", ")))
    }

    /**
     * Whether a host is somewhere the intended counterpart could plausibly be.
     *
     * **Nothing here resolves anything, and that is the rule rather than an optimisation.** Resolving
     * a name would mean a DNS query on behalf of a code nobody has decided to trust yet — a callback
     * to whoever printed it, before any human agreed to anything. So the decision is made on the
     * text: an address literal is judged, and anything else is a name, of which only `.local`
     * counts as reachable-but-local.
     *
     * The JVM makes this easy to get wrong: `InetAddress.getByName` **resolves**, and a port that
     * reached for it would perform exactly the query this rule exists to prevent. Hence the literal
     * parsing below.
     */
    fun isPrivateTarget(host: String): Boolean {

        val bare = host.substringBefore('%')   // strip an IPv6 zone: fe80::1%wlan0

        ipv4(bare)?.let { b ->
            return when {
                b[0] == 127                       -> true                        // loopback
                b[0] == 10                        -> true                        // 10/8
                b[0] == 172 && b[1] in 16..31     -> true                        // 172.16/12
                b[0] == 192 && b[1] == 168        -> true                        // 192.168/16
                b[0] == 169 && b[1] == 254        -> true                        // link-local
                else                              -> false
            }
        }

        if (bare.contains(':')) {
            val lower = bare.lowercase()
            if (lower == "::1") return true                                      // loopback
            val head = lower.substringBefore(':').padStart(4, '0')
            val first = head.toIntOrNull(16) ?: return false
            return (first and 0xFFC0) == 0xFE80 ||                               // fe80::/10
                   (first and 0xFE00) == 0xFC00                                  // fc00::/7
        }

        return host.endsWith(".local", ignoreCase = true)
    }

    /** Four dotted decimal octets, or null. Deliberately not `InetAddress` — see above. */
    private fun ipv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        if (parts.any { it.length > 1 && it.startsWith("0") }) return null   // no octal ambiguity
        return octets.toIntArray()
    }
}


/**
 * Parses a scanned string into a [PairingPayload].
 *
 * A port of the C# `PairingUri`. The two halves — the Pi that renders the code and the app that
 * reads it — never run in the same process, so the format is pinned by a shared corpus rather than
 * by two readings of a specification.
 */
object PairingUri {

    const val DEFAULT_BASE = "https://open.charging.cloud/evsim/pair"
    const val ALT_SCHEME = "v2gsim"

    private val KNOWN = setOf("v", "totp", "evseId", "tariffId", "currency", "uiLanguage",
                              "host", "port", "tp", "proto", "crypto", "nc", "ncwhy",
                              "root", "meter", "wifi")

    /**
     * @return null if this is not a pairing code at all
     * @throws PairingFormatException if it is one and malformed
     */
    fun parse(text: String): PairingPayload? {

        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null

        val scheme = uri.scheme?.lowercase()
        val isPairing = scheme == ALT_SCHEME ||
                        (scheme == "https" && (uri.path ?: "").endsWith("/pair"))
        if (!isPairing) return null

        // Deliberately the fragment only. Parameters in the query are NOT read, because a query is
        // sent to the server: a format that worked either way would hand every scan to whoever runs
        // the host.
        val fragment = uri.rawFragment ?: ""
        if (fragment.isEmpty())
            throw PairingFormatException(
                "the pairing code carries no fragment; data in the query string is not read, " +
                "because a query is sent to the server")

        val fields = parseFields(fragment)

        val version = require(fields, "v")
        val versionNumber = version.toIntOrNull()
            ?: throw PairingFormatException("version '$version' is not a number")

        val host = require(fields, "host")
        val port = require(fields, "port")
        val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw PairingFormatException("port '$port' is not a port number")

        val transport = when (fields["tp"]) {
            null, "tls" -> PairingTransport.TLS
            "tcp"       -> PairingTransport.TCP
            else        -> throw PairingFormatException("unknown transport '${fields["tp"]}'")
        }

        val wifi = fields["wifi"]?.let { splitWifi(it) }

        return PairingPayload(
            version = versionNumber,
            host = host,
            port = portNumber,
            transport = transport,
            protocols = fields["proto"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: emptyList(),
            crypto = fields["crypto"],
            nonConformant = fields["nc"] == "1" || fields["nc"] == "true",
            nonConformanceReason = fields["ncwhy"],
            rootFingerprint = fields["root"]?.lowercase(),
            meter = fields["meter"],
            totp = fields["totp"],
            evseId = fields["evseId"],
            tariffId = fields["tariffId"],
            currency = fields["currency"],
            uiLanguage = fields["uiLanguage"],
            wifiSsid = wifi?.first,
            wifiPsk = wifi?.second,
            extra = fields.filterKeys { it !in KNOWN },
        )
    }

    private fun parseFields(fragment: String): Map<String, String> {

        val fields = LinkedHashMap<String, String>()

        for (pair in fragment.split('&').filter { it.isNotEmpty() }) {

            val at = pair.indexOf('=')
            if (at < 0) throw PairingFormatException("'$pair' is not a key=value pair")

            val key = pair.substring(0, at)
            // A repeated key is how a hostile code smuggles a second value past a reader that shows
            // the first one, so it is refused rather than resolved. "First wins" and "last wins" are
            // both defensible, and an attacker needs only the sheet and the connector to disagree.
            if (fields.containsKey(key))
                throw PairingFormatException("parameter '$key' appears more than once")

            fields[key] = percentDecode(pair.substring(at + 1))
        }

        return fields
    }

    /**
     * Percent-decoding, and **not** `URLDecoder`: that turns `+` into a space, which is a
     * form-encoding rule that has no business in a URI fragment. A PSK containing a plus would
     * silently become a different password.
     */
    private fun percentDecode(text: String): String {
        val out = StringBuilder()
        var i = 0
        val bytes = ArrayList<Byte>()

        fun flush() {
            if (bytes.isNotEmpty()) { out.append(String(bytes.toByteArray(), Charsets.UTF_8)); bytes.clear() }
        }

        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length + 1 && i + 2 <= text.length - 1 + 1) {
                val hex = text.substring(i + 1, minOf(i + 3, text.length))
                val value = hex.toIntOrNull(16)
                if (hex.length == 2 && value != null) {
                    bytes.add(value.toByte()); i += 3; continue
                }
            }
            flush(); out.append(c); i++
        }
        flush()
        return out.toString()
    }

    private fun require(fields: Map<String, String>, key: String): String =
        fields[key] ?: throw PairingFormatException("required parameter '$key' is missing")

    /** `ssid:psk`, with `\:` escaping a colon in the SSID. */
    private fun splitWifi(value: String): Pair<String?, String?> {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length && value[i + 1] == ':') { out.append(':'); i += 2 }
            else if (c == ':') return out.toString() to value.substring(i + 1)
            else { out.append(c); i++ }
        }
        return out.toString() to null
    }
}
