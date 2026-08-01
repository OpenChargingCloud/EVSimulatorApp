package cloud.charging.v2g.bridge

import cloud.charging.v2g.exi.JsonArray
import cloud.charging.v2g.exi.JsonNull
import cloud.charging.v2g.exi.JsonNumber
import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonString
import cloud.charging.v2g.exi.JsonValue
import cloud.charging.v2g.pairing.PairingWarnings

/**
 * What a session needs to know before it can start — the one thing that travels *into* the bridge
 * (`docs/CONCEPT.md` B1).
 *
 * This is the scanned pairing code's content plus the choices a human made on the confirmation
 * sheet. Deliberately so: the only path into a session is one somebody approved, and nothing here
 * re-parses a QR code — `PairingUri` already did that, in front of the user.
 *
 * **It arrives from a WebView, so it is untrusted, so it is parsed rather than deserialised.** The
 * events go out to a page that can only watch; this comes back from a page that can be navigated,
 * injected into, or replaced by a different page altogether.
 *
 * **Unknown properties are refused rather than ignored.** A key this build does not read is either a
 * newer front end or somebody probing, and the two are indistinguishable here. Ignoring it means a
 * setting the user saw on the sheet, and believes they approved, silently did not happen.
 *
 * A port of C#'s `SessionConfig`, held to `Vectors/Bridge.config.json` — including the refusal
 * messages, because three back ends that say no for three different reasons are three different
 * products.
 */
data class SessionConfig(
    /** Where the station is: an address literal or a `.local` name. Never resolved. */
    val host: String,
    val port: Int,
    /** `tls` or `tcp`. */
    val transport: String,
    /** `iso15118-2` or `iso15118-20`. */
    val protocol: String,
    /** `ac` or `dc`. */
    val mode: String,
    /** `eim` for external payment, `pnc` for Plug & Charge. */
    val authorization: String,
    /**
     * The TOTP read off the station's display, if the code carried one.
     *
     * Passed on as it was read, never recomputed from this device's clock: the phone's time is not
     * trustworthy and the station is the one that decides (§4.6).
     */
    val totp: String? = null,
    /** The root certificate fingerprint the pairing code pinned, if it carried one. */
    val rootFingerprint: String? = null,
) {

    /**
     * The configuration as JSON, in the order every back end writes it.
     *
     * Hand-written and ordered for the same reason [BridgeEvent.toJson] is: the moment a WebView
     * writes this shape it is a wire format. Absent optionals are omitted rather than written as
     * null, matching the JSON-LD side.
     */
    fun toJson(): JsonObject {

        val json = JsonObject()
        json["host"]          = JsonValue.of(host)
        json["port"]          = JsonValue.of(port)
        json["transport"]     = JsonValue.of(transport)
        json["protocol"]      = JsonValue.of(protocol)
        json["mode"]          = JsonValue.of(mode)
        json["authorization"] = JsonValue.of(authorization)

        totp?.let            { json["totp"]            = JsonValue.of(it) }
        rootFingerprint?.let { json["rootFingerprint"] = JsonValue.of(it) }

        return json
    }


    companion object {

        private val TRANSPORTS     = listOf("tls", "tcp")
        private val PROTOCOLS      = listOf("iso15118-2", "iso15118-20")
        private val MODES          = listOf("ac", "dc")
        private val AUTHORIZATIONS = listOf("eim", "pnc")

        private val KNOWN = listOf(
            "host", "port", "transport", "protocol", "mode", "authorization", "totp", "rootFingerprint")


        /** Reads a configuration a WebView sent, or explains why it is not one. */
        fun parse(node: JsonValue?): SessionConfig {

            val json = node as? JsonObject
                ?: throw SessionConfigException("a session configuration is a JSON object.")

            for (key in json.keys)
                if (key !in KNOWN)
                    throw SessionConfigException(
                        "'$key' is not a configuration property this build reads. " +
                        "Known: ${KNOWN.joinToString(", ")}.")

            val host = text(json, "host")

            // The one rule here that is about safety rather than shape. B1 restricts a session to a
            // private-range target, and the restriction has to hold at the point the socket is opened
            // rather than at the point the sheet was shown — the sheet is a different process's
            // memory, and this is the last place that can say no.
            if (!PairingWarnings.isPrivateTarget(host))
                throw SessionConfigException(
                    "'$host' is not a private or link-local address. A session is only offered to a " +
                    "counterpart that could plausibly be the one in front of you.")

            val port = json["port"]
            if (port == null || port is JsonNull || port is JsonObject || port is JsonArray)
                throw SessionConfigException("'port' is missing.")

            val portNumber = (port as? JsonNumber)?.text?.toIntOrNull()
            if (portNumber == null || portNumber < 1 || portNumber > 65535)
                throw SessionConfigException(
                    "'port' is ${port.toJsonString()}, which is not a TCP port.")

            return SessionConfig(
                host            = host,
                port            = portNumber,
                transport       = oneOf(json, "transport",     TRANSPORTS),
                protocol        = oneOf(json, "protocol",      PROTOCOLS),
                mode            = oneOf(json, "mode",          MODES),
                authorization   = oneOf(json, "authorization", AUTHORIZATIONS),
                totp            = optional(json, "totp"),
                rootFingerprint = optional(json, "rootFingerprint"))
        }


        private fun text(json: JsonObject, property: String): String {

            val value = json[property] as? JsonString
                ?: throw SessionConfigException("'$property' is missing or is not a string.")

            if (value.value.isEmpty())
                throw SessionConfigException("'$property' is empty.")

            return value.value
        }

        private fun oneOf(json: JsonObject, property: String, permitted: List<String>): String {

            val value = text(json, property)

            if (value !in permitted)
                throw SessionConfigException(
                    "'$property' is '$value'. Known: ${permitted.joinToString(", ")}.")

            return value
        }

        /**
         * An optional property — absent or explicitly null both mean absent.
         *
         * The two are folded together because the writer omits rather than nulls, so a null can only
         * come from something else's writer. An *empty* string is refused, though: that is a value
         * somebody meant to supply and did not.
         */
        private fun optional(json: JsonObject, property: String): String? =
            if (json[property] == null || json[property] is JsonNull) null
            else text(json, property)
    }
}


/**
 * A session configuration that will not be acted on, and why.
 *
 * Its own type rather than a generic argument exception, because the message goes back over the
 * bridge to a page that will show it to somebody: it is a user-facing refusal, not an assertion.
 */
class SessionConfigException(message: String) : Exception(message)
