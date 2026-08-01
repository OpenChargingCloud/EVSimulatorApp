import ExiRuntime
import V2GPairing

/// What a session needs to know before it can start — the one thing that travels *into* the bridge
/// (`docs/CONCEPT.md` B1).
///
/// This is the scanned pairing code's content plus the choices a human made on the confirmation
/// sheet. Deliberately so: the only path into a session is one somebody approved, and nothing here
/// re-parses a QR code — ``PairingUri`` already did that, in front of the user.
///
/// **It arrives from a WebView, so it is untrusted, so it is parsed rather than decoded.** The events
/// go out to a page that can only watch; this comes back from a page that can be navigated, injected
/// into, or replaced by a different page altogether. `Codable` would accept whatever shape it was
/// handed and leave the checking to whoever remembered to write it.
///
/// **Unknown properties are refused rather than ignored.** A key this build does not read is either a
/// newer front end or somebody probing, and the two are indistinguishable here. Ignoring it means a
/// setting the user saw on the sheet, and believes they approved, silently did not happen.
///
/// A port of C#'s `SessionConfig`, held to `Vectors/Bridge.config.json` — including the refusal
/// messages, because three back ends that say no for three different reasons are three different
/// products.
public struct SessionConfig: Equatable, Sendable {

    /// Where the station is: an address literal or a `.local` name. Never resolved.
    public let host: String
    public let port: Int

    /// `tls` or `tcp`.
    public let transport: String

    /// `iso15118-2` or `iso15118-20`.
    public let `protocol`: String

    /// `ac` or `dc`.
    public let mode: String

    /// `eim` for external payment, `pnc` for Plug & Charge.
    public let authorization: String

    /// The TOTP read off the station's display, if the code carried one.
    ///
    /// Passed on as it was read, never recomputed from this device's clock: the phone's time is not
    /// trustworthy and the station is the one that decides (§4.6).
    public let totp: String?

    /// The root certificate fingerprint the pairing code pinned, if it carried one.
    public let rootFingerprint: String?

    public init(host: String, port: Int, transport: String, protocol: String, mode: String,
                authorization: String, totp: String? = nil, rootFingerprint: String? = nil) {
        self.host            = host
        self.port            = port
        self.transport       = transport
        self.protocol        = `protocol`
        self.mode            = mode
        self.authorization   = authorization
        self.totp            = totp
        self.rootFingerprint = rootFingerprint
    }


    // MARK: The permitted values

    private static let transports     = ["tls", "tcp"]
    private static let protocols      = ["iso15118-2", "iso15118-20"]
    private static let modes          = ["ac", "dc"]
    private static let authorizations = ["eim", "pnc"]

    private static let known = [
        "host", "port", "transport", "protocol", "mode", "authorization", "totp", "rootFingerprint"]


    /// Reads a configuration a WebView sent, or explains why it is not one.
    public static func parse(_ node: JsonValue?) throws -> SessionConfig {

        guard let json = node as? JsonObject else {
            throw SessionConfigError("a session configuration is a JSON object.")
        }

        for key in json.keys where !known.contains(key) {
            throw SessionConfigError("'\(key)' is not a configuration property this build reads. "
                                   + "Known: \(known.joined(separator: ", ")).")
        }

        let host = try text(json, "host")

        // The one rule here that is about safety rather than shape. B1 restricts a session to a
        // private-range target, and the restriction has to hold at the point the socket is opened
        // rather than at the point the sheet was shown — the sheet is a different process's memory,
        // and this is the last place that can say no.
        guard PairingWarnings.isPrivateTarget(host) else {
            throw SessionConfigError("'\(host)' is not a private or link-local address. A session is "
                                   + "only offered to a counterpart that could plausibly be the one "
                                   + "in front of you.")
        }

        guard let port = json["port"],
              !(port is JsonNull), !(port is JsonObject), !(port is JsonArray) else {
            throw SessionConfigError("'port' is missing.")
        }

        guard let portNumber = (port as? JsonNumber).flatMap({ Int($0.text) }),
              portNumber >= 1, portNumber <= 65535 else {
            throw SessionConfigError("'port' is \(port.jsonString), which is not a TCP port.")
        }

        return SessionConfig(
            host:            host,
            port:            portNumber,
            transport:       try oneOf(json, "transport",     transports),
            protocol:        try oneOf(json, "protocol",      protocols),
            mode:            try oneOf(json, "mode",          modes),
            authorization:   try oneOf(json, "authorization", authorizations),
            totp:            try optional(json, "totp"),
            rootFingerprint: try optional(json, "rootFingerprint"))
    }


    /// The configuration as JSON, in the order every back end writes it.
    ///
    /// Hand-written and ordered for the same reason ``BridgeEvent/toJSON(_:)`` is: the moment a
    /// WebView writes this shape it is a wire format. Absent optionals are omitted rather than
    /// written as null, matching the JSON-LD side.
    public func toJSON() -> JsonObject {

        let json = JsonObject()
        json["host"]          = JsonValue.of(host)
        json["port"]          = JsonNumber(String(port))
        json["transport"]     = JsonValue.of(transport)
        json["protocol"]      = JsonValue.of(`protocol`)
        json["mode"]          = JsonValue.of(mode)
        json["authorization"] = JsonValue.of(authorization)

        if let totp            { json["totp"]            = JsonValue.of(totp) }
        if let rootFingerprint { json["rootFingerprint"] = JsonValue.of(rootFingerprint) }

        return json
    }


    // MARK: Plumbing

    private static func text(_ json: JsonObject, _ property: String) throws -> String {

        guard let value = json[property] as? JsonString else {
            throw SessionConfigError("'\(property)' is missing or is not a string.")
        }
        guard !value.value.isEmpty else {
            throw SessionConfigError("'\(property)' is empty.")
        }
        return value.value
    }

    private static func oneOf(_ json: JsonObject, _ property: String,
                              _ permitted: [String]) throws -> String {

        let value = try text(json, property)

        guard permitted.contains(value) else {
            throw SessionConfigError("'\(property)' is '\(value)'. Known: "
                                   + "\(permitted.joined(separator: ", ")).")
        }
        return value
    }

    /// An optional property — absent or explicitly null both mean absent.
    ///
    /// The two are folded together because the writer omits rather than nulls, so a null can only
    /// come from something else's writer. An *empty* string is refused, though: that is a value
    /// somebody meant to supply and did not.
    private static func optional(_ json: JsonObject, _ property: String) throws -> String? {
        guard let node = json[property], !(node is JsonNull) else { return nil }
        return try text(json, property)
    }
}


/// A session configuration that will not be acted on, and why.
///
/// Its own type rather than a generic error, because the message goes back over the bridge to a page
/// that will show it to somebody: it is a user-facing refusal, not an assertion.
public struct SessionConfigError: Error, CustomStringConvertible, Equatable {

    public let description: String

    public init(_ description: String) { self.description = description }
}
