import Foundation

public enum PairingTransport: String, Sendable {
    case tls, tcp
}


/// A scanned pairing code, parsed.
///
/// Everything here is **untrusted input**. A pairing code is an image on a display that anyone can
/// tape over, and malicious stickers at public chargers are an established attack. So this type
/// deliberately *classifies* rather than decides: it reports what the code asks for and what is wrong
/// with it, and the decision to connect belongs to a human looking at a confirmation sheet.
///
/// Unknown parameters are kept in ``extra`` rather than dropped or rejected. A newer Pi must be able
/// to talk to an older app — and an app that silently discards what it does not understand cannot
/// warn that the code contained something it could not read. Nothing interprets ``extra``, which is
/// exactly why carrying it is safe.
///
/// A port of the C# `PairingPayload`, held to `Vectors/Pairing.payload.vectors.json`.
public struct PairingPayload: Equatable, Sendable {

    public let version: Int
    public let host: String
    public let port: Int
    public let transport: PairingTransport
    public let protocols: [String]
    public let crypto: String?
    public let nonConformant: Bool
    /// The peer's own reason, **displayed verbatim** and never interpreted.
    public let nonConformanceReason: String?
    public let rootFingerprint: String?
    public let meter: String?
    public let totp: String?
    public let evseId: String?
    public let tariffId: String?
    public let currency: String?
    public let uiLanguage: String?
    public let wifiSsid: String?
    public let wifiPsk: String?
    public let extra: [String: String]

    public var warnings: [PairingWarning] { PairingWarnings.of(self) }
}


/// The code was a pairing code and is broken — distinct from "that was some other QR code", which is
/// a shrug rather than something to tell the user about.
public struct PairingFormatError: Error, CustomStringConvertible, Equatable {
    public let description: String
    init(_ description: String) { self.description = description }
}


public enum PairingWarningKind: String, Sendable, CaseIterable {
    case unsupportedVersion, plaintextTransport, weakenedCrypto, declaredNonConformance
    case publicTarget, noTrustAnchor, noProximityProof, carriesWifiCredentials, unknownParameters
}

public struct PairingWarning: Equatable, Sendable {

    public let kind: PairingWarningKind
    public let detail: String

    /// Whether this alone should stop the code being offered as connectable at all.
    public var isBlocking: Bool { kind == .unsupportedVersion || kind == .publicTarget }
}


public enum PairingWarnings {

    /// The curve the -20 conformant profile requires.
    public static let conformantCurve = "secp521r1"

    public static func of(_ payload: PairingPayload) -> [PairingWarning] {

        var warnings: [PairingWarning] = []

        if payload.version != 1 {
            warnings.append(.init(kind: .unsupportedVersion,
                detail: "payload version \(payload.version); this build reads version 1"))
        }

        if payload.transport == .tcp {
            warnings.append(.init(kind: .plaintextTransport,
                detail: "the counterpart offers plaintext TCP — the session will not be encrypted"))
        }

        // "Unstated" is reported as well as "weakened". A code that says nothing about its curve is
        // not thereby conformant, and silence is the easiest thing for a hostile code to offer.
        if let crypto = payload.crypto {
            if crypto.lowercased() != conformantCurve {
                warnings.append(.init(kind: .weakenedCrypto,
                    detail: "crypto profile is \(crypto), not the conformant \(conformantCurve)"))
            }
        } else {
            warnings.append(.init(kind: .weakenedCrypto,
                detail: "no crypto profile stated; the -20 conformant profile is \(conformantCurve)"))
        }

        if payload.nonConformant {
            let reason = payload.nonConformanceReason.flatMap { $0.isEmpty ? nil : $0 }
            warnings.append(.init(kind: .declaredNonConformance,
                detail: reason ?? "the counterpart declares itself non-conformant but gives no reason"))
        }

        if !isPrivateTarget(payload.host) {
            warnings.append(.init(kind: .publicTarget,
                detail: "\(payload.host) is not a private or link-local address"))
        }

        if payload.rootFingerprint == nil {
            warnings.append(.init(kind: .noTrustAnchor,
                detail: "no root fingerprint; the certificate chain cannot be checked against this code"))
        }

        if payload.totp == nil {
            warnings.append(.init(kind: .noProximityProof,
                detail: "static code — it proves nothing about being present now"))
        }

        if payload.wifiPsk != nil {
            warnings.append(.init(kind: .carriesWifiCredentials,
                detail: "the code carries the password for network \(payload.wifiSsid ?? "(unnamed)")"))
        }

        if !payload.extra.isEmpty {
            warnings.append(.init(kind: .unknownParameters,
                detail: "unread parameters: " + payload.extra.keys.sorted().joined(separator: ", ")))
        }

        return warnings
    }

    /// Whether a host is somewhere the intended counterpart could plausibly be.
    ///
    /// **Nothing here resolves anything, and that is the rule rather than an optimisation.** Resolving
    /// a name would mean a DNS query on behalf of a code nobody has decided to trust yet — a callback
    /// to whoever printed it, before any human agreed to anything. So the decision is made on the
    /// text: an address literal is judged, and anything else is a name, of which only `.local` counts
    /// as reachable-but-local.
    ///
    /// Foundation offers no resolution-free literal check, so both literal forms are parsed here. The
    /// JVM port makes the same choice for a sharper reason: `InetAddress.getByName` resolves, and a
    /// port that reached for it would perform exactly the query this rule exists to prevent.
    public static func isPrivateTarget(_ host: String) -> Bool {

        let bare = String(host.prefix(while: { $0 != "%" }))   // strip an IPv6 zone: fe80::1%wlan0

        if let b = ipv4(bare) {
            if b[0] == 127 { return true }                              // loopback
            if b[0] == 10  { return true }                              // 10/8
            if b[0] == 172, (16...31).contains(b[1]) { return true }    // 172.16/12
            if b[0] == 192, b[1] == 168 { return true }                 // 192.168/16
            if b[0] == 169, b[1] == 254 { return true }                 // link-local
            return false
        }

        if bare.contains(":") {
            let lower = bare.lowercased()
            if lower == "::1" { return true }                           // loopback
            let head = String(lower.prefix(while: { $0 != ":" }))
            guard let first = UInt16(head.isEmpty ? "0" : head, radix: 16) else { return false }
            return (first & 0xFFC0) == 0xFE80 ||                        // fe80::/10
                   (first & 0xFE00) == 0xFC00                           // fc00::/7
        }

        return host.lowercased().hasSuffix(".local")
    }

    /// Four dotted decimal octets, or nil. Leading zeros are refused rather than accepted: `010.1.1.1`
    /// is octal to some resolvers and decimal to others, and a host that means two different things
    /// is a host this cannot honestly judge.
    private static func ipv4(_ text: String) -> [Int]? {

        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }

        var octets: [Int] = []
        for part in parts {
            guard let value = Int(part), (0...255).contains(value) else { return nil }
            guard !(part.count > 1 && part.hasPrefix("0")) else { return nil }
            octets.append(value)
        }
        return octets
    }
}


/// Parses a scanned string into a ``PairingPayload``.
///
/// A port of the C# `PairingUri`. The two halves — the Pi that renders the code and the app that
/// reads it — never run in the same process, so the format is pinned by a shared corpus rather than
/// by two readings of a specification.
public enum PairingUri {

    public static let defaultBase = "https://open.charging.cloud/evsim/pair"
    public static let altScheme = "v2gsim"

    private static let known: Set<String> = [
        "v", "totp", "evseId", "tariffId", "currency", "uiLanguage",
        "host", "port", "tp", "proto", "crypto", "nc", "ncwhy", "root", "meter", "wifi",
    ]

    /// - Returns: nil if this is not a pairing code at all
    /// - Throws: ``PairingFormatError`` if it is one and malformed
    public static func parse(_ text: String) throws -> PairingPayload? {

        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed), let scheme = components.scheme?.lowercased()
        else { return nil }

        let isPairing = scheme == altScheme || (scheme == "https" && components.path.hasSuffix("/pair"))
        guard isPairing else { return nil }

        // Deliberately the fragment only. Parameters in the query are NOT read, because a query is
        // sent to the server: a format that worked either way would hand every scan to whoever runs
        // the host.
        //
        // `percentEncodedFragment` rather than `fragment`: decoding must happen per value, after the
        // fragment is split. Decoding first would let an encoded `%26` inside a value become a real
        // separator and smuggle in a parameter — the classic double-decode bug.
        let fragment = components.percentEncodedFragment ?? ""
        guard !fragment.isEmpty else {
            throw PairingFormatError(
                "the pairing code carries no fragment; data in the query string is not read, "
              + "because a query is sent to the server")
        }

        let fields = try parseFields(fragment)

        let version = try require(fields, "v")
        guard let versionNumber = Int(version) else {
            throw PairingFormatError("version '\(version)' is not a number")
        }

        let host = try require(fields, "host")
        let port = try require(fields, "port")
        guard let portNumber = Int(port), (1...65535).contains(portNumber) else {
            throw PairingFormatError("port '\(port)' is not a port number")
        }

        let transport: PairingTransport
        switch fields["tp"] {
        case nil, "tls": transport = .tls
        case "tcp":      transport = .tcp
        default:         throw PairingFormatError("unknown transport '\(fields["tp"]!)'")
        }

        let wifi = fields["wifi"].map(splitWifi)

        return PairingPayload(
            version: versionNumber,
            host: host,
            port: portNumber,
            transport: transport,
            protocols: fields["proto"]?
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty } ?? [],
            crypto: fields["crypto"],
            nonConformant: fields["nc"] == "1" || fields["nc"] == "true",
            nonConformanceReason: fields["ncwhy"],
            rootFingerprint: fields["root"]?.lowercased(),
            meter: fields["meter"],
            totp: fields["totp"],
            evseId: fields["evseId"],
            tariffId: fields["tariffId"],
            currency: fields["currency"],
            uiLanguage: fields["uiLanguage"],
            wifiSsid: wifi?.ssid,
            wifiPsk: wifi?.psk,
            extra: fields.filter { !known.contains($0.key) })
    }

    private static func parseFields(_ fragment: String) throws -> [String: String] {

        var fields: [String: String] = [:]

        for pair in fragment.split(separator: "&") {

            guard let equals = pair.firstIndex(of: "=") else {
                throw PairingFormatError("'\(pair)' is not a key=value pair")
            }

            let key = String(pair[pair.startIndex ..< equals])
            // A repeated key is how a hostile code smuggles a second value past a reader that shows
            // the first one, so it is refused rather than resolved. "First wins" and "last wins" are
            // both defensible, and an attacker needs only the sheet and the connector to disagree.
            guard fields[key] == nil else {
                throw PairingFormatError("parameter '\(key)' appears more than once")
            }

            let raw = String(pair[pair.index(after: equals)...])
            fields[key] = raw.removingPercentEncoding ?? raw
        }

        return fields
    }

    private static func require(_ fields: [String: String], _ key: String) throws -> String {
        guard let value = fields[key] else {
            throw PairingFormatError("required parameter '\(key)' is missing")
        }
        return value
    }

    /// `ssid:psk`, with `\:` escaping a colon in the SSID.
    private static func splitWifi(_ value: String) -> (ssid: String?, psk: String?) {

        var ssid = ""
        var index = value.startIndex

        while index < value.endIndex {
            let character = value[index]
            let next = value.index(after: index)

            if character == "\\", next < value.endIndex, value[next] == ":" {
                ssid.append(":")
                index = value.index(after: next)
            } else if character == ":" {
                return (ssid, String(value[next...]))
            } else {
                ssid.append(character)
                index = next
            }
        }

        return (ssid, nil)
    }
}
