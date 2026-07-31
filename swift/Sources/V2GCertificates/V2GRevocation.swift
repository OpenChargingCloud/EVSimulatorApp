import Foundation
import SwiftASN1
import X509

/// What a revocation check found — **three** answers, not two.
///
/// The third is the whole reason this type exists. "Not on the list" and "no usable list" look
/// identical to a naive check and are not remotely the same thing: the second is the classic
/// soft-fail hole, where whoever wants a revoked credential accepted simply arranges for the list to
/// be unavailable. A boolean cannot express that difference, so this is not a boolean.
///
/// What to *do* about ``unknown`` is a policy the app owns — refuse, warn, or proceed — and the only
/// thing settled here is that it cannot be mistaken for ``notRevoked`` by accident.
public enum V2GRevocationStatus: Equatable, Sendable {

    /// A CRL that verified, was current, and does not list this certificate.
    case notRevoked

    /// - Parameter reason: RFC 5280's reason, spelled the same in every back end. Normalised rather
    ///   than passed through: the JVM calls it `KEY_COMPROMISE`, and this string is something an app
    ///   shows a user. The fingerprint format taught the same lesson one type over.
    case revoked(on: Date, reason: String?)

    /// No answer could be obtained. `why` says which of the several reasons it was.
    case unknown(why: String)
}


/// A certificate revocation list, parsed far enough to answer one question.
///
/// ## Why this is hand-parsed
///
/// `swift-certificates` models OCSP and not CRLs, and the ISO 15118 PKI publishes CRLs. So the
/// structure below is mapped from nodes `swift-asn1` has already decoded — it does the DER work,
/// bounds and tags and lengths included, and this only names the fields. That is the same
/// relationship ``V2GCertificate`` has to `swift-certificates` for extensions, and a different thing
/// from hand-rolling a parser.
///
/// Only what the question needs is read: issuer, validity window, revoked serials with their dates
/// and reasons. Everything else in a CRL is skipped rather than half-understood.
struct V2GCrl {

    struct Entry {
        let serial: [UInt8]        // unsigned big-endian, no leading zeros
        let revocationDate: Date
        let reason: String?
    }

    let issuer: DistinguishedName
    let thisUpdate: Date
    let nextUpdate: Date?
    let entries: [Entry]

    /// The exact bytes the signature covers, as they arrived. Taken from the parsed node rather than
    /// re-serialised: a value that round-trips through any encoder is not guaranteed to come back
    /// identical, and a signature checked over re-encoded bytes would verify here and nowhere else.
    let tbsBytes: [UInt8]

    let signature: [UInt8]


    init(der: [UInt8]) throws {

        let root = try DER.parse(der)
        guard case .constructed(let top) = root.content else { throw V2GCrlError.malformed("not a SEQUENCE") }

        let parts = Array(top)
        guard parts.count == 3 else {
            throw V2GCrlError.malformed("a CertificateList has three elements, found \(parts.count)")
        }

        self.tbsBytes  = Array(parts[0].encodedBytes)
        self.signature = Array(try ASN1BitString(derEncoded: parts[2]).bytes)

        guard case .constructed(let tbs) = parts[0].content else {
            throw V2GCrlError.malformed("tbsCertList is not a SEQUENCE")
        }

        // The fields are dispatched on their tags rather than counted, because version,
        // nextUpdate and revokedCertificates are all OPTIONAL and a positional walk would drift.
        var issuer: DistinguishedName?
        var thisUpdate: Date?
        var nextUpdate: Date?
        var entries: [Entry] = []
        var sequencesSeen = 0

        for node in tbs {
            switch node.identifier {

            case .sequence:
                sequencesSeen += 1
                // First SEQUENCE is the AlgorithmIdentifier, second the issuer Name, third (if
                // present) the revoked list.
                switch sequencesSeen {
                case 1:  break
                case 2:  issuer = try DistinguishedName(derEncoded: node)
                default: entries = try Self.parseEntries(node)
                }

            case .utcTime, .generalizedTime:
                let time = try Self.date(from: node)
                if thisUpdate == nil { thisUpdate = time } else { nextUpdate = time }

            default:
                break   // version, crlExtensions: not needed for this question
            }
        }

        guard let issuer, let thisUpdate else {
            throw V2GCrlError.malformed("a CRL needs an issuer and a thisUpdate")
        }

        self.issuer     = issuer
        self.thisUpdate = thisUpdate
        self.nextUpdate = nextUpdate
        self.entries    = entries
    }


    private static func parseEntries(_ node: ASN1Node) throws -> [Entry] {

        guard case .constructed(let list) = node.content else { return [] }
        var entries: [Entry] = []

        for entryNode in list {
            guard case .constructed(let fields) = entryNode.content else { continue }
            let parts = Array(fields)
            guard parts.count >= 2 else { continue }

            let serial = try Self.unsignedBytes(of: parts[0])
            let date   = try Self.date(from: parts[1])
            let reason = parts.count > 2 ? Self.reason(fromEntryExtensions: parts[2]) : nil

            entries.append(Entry(serial: serial, revocationDate: date, reason: reason))
        }
        return entries
    }

    /// The CRL entry reason (OID 2.5.29.21), when the entry carries one. Absent is a perfectly normal
    /// CRL, so this reports `nil` rather than failing.
    private static func reason(fromEntryExtensions node: ASN1Node) -> String? {

        guard case .constructed(let extensions) = node.content else { return nil }

        for ext in extensions {
            guard case .constructed(let fields) = ext.content else { continue }
            let parts = Array(fields)
            guard let oid = try? ASN1ObjectIdentifier(derEncoded: parts[0]),
                  oid == reasonCodeOID,
                  let value = try? ASN1OctetString(derEncoded: parts[parts.count - 1]),
                  let inner = try? DER.parse(Array(value.bytes)),
                  case .primitive(let raw) = inner.content,
                  let code = raw.last
            else { continue }

            return reasonNames[Int(code)]
        }
        return nil
    }

    private static let reasonCodeOID: ASN1ObjectIdentifier = [2, 5, 29, 21]

    /// RFC 5280 §5.3.1, spelled as the other back ends spell it.
    private static let reasonNames: [Int: String] = [
        0: "unspecified", 1: "keyCompromise", 2: "cACompromise", 3: "affiliationChanged",
        4: "superseded", 5: "cessationOfOperation", 6: "certificateHold", 8: "removeFromCRL",
        9: "privilegeWithdrawn", 10: "aACompromise",
    ]

    private static func unsignedBytes(of node: ASN1Node) throws -> [UInt8] {
        guard case .primitive(let bytes) = node.content else {
            throw V2GCrlError.malformed("a serial number is a primitive INTEGER")
        }
        return Array(bytes.drop { $0 == 0 })   // DER's sign byte is not part of the value
    }

    /// ASN.1 times are always UTC here — both forms carry a `Z`, and RFC 5280 requires it. Building
    /// the `Date` through explicit UTC components rather than a formatter keeps that fact visible:
    /// a local-time interpretation would shift every comparison by the device's offset, which is the
    /// kind of bug that only shows up for users in the wrong timezone.
    private static func date(from node: ASN1Node) throws -> Date {

        var parts = DateComponents()
        parts.timeZone = TimeZone(secondsFromGMT: 0)

        if node.identifier == .utcTime {
            let t = try UTCTime(derEncoded: node)
            (parts.year, parts.month, parts.day) = (t.year, t.month, t.day)
            (parts.hour, parts.minute, parts.second) = (t.hours, t.minutes, t.seconds)
        } else {
            let t = try GeneralizedTime(derEncoded: node)
            (parts.year, parts.month, parts.day) = (t.year, t.month, t.day)
            (parts.hour, parts.minute, parts.second) = (t.hours, t.minutes, t.seconds)
        }

        guard let date = Calendar(identifier: .gregorian).date(from: parts) else {
            throw V2GCrlError.malformed("a time field that is not a date")
        }
        return date
    }
}


enum V2GCrlError: Error, CustomStringConvertible {
    case malformed(String)

    var description: String {
        switch self { case .malformed(let what): return "malformed CRL: \(what)" }
    }
}


/// Checks a certificate against a certificate revocation list.
///
/// ## What is checked before the list is believed
///
/// A CRL is **attacker-supplied input** in exactly the way a certificate chain is, and it fails in a
/// direction that is easy to miss: a forged or substituted CRL need not make false claims, it only
/// needs to be *empty*. So before its contents count for anything:
///
/// * its signature must verify under the issuing CA — otherwise anyone can mint one;
/// * it must be current — an expired CRL is a snapshot of the past, and treating it as authoritative
///   is how a revocation gets outrun by waiting;
/// * its issuer must be the certificate's issuer — a valid CRL from a different CA says nothing about
///   this certificate, and reading it as "not listed" would be a straightforward bypass.
///
/// Each of those failures yields ``V2GRevocationStatus/unknown(why:)``, never `notRevoked`.
///
/// ## What is not here
///
/// **Fetching.** Where a CRL comes from — a URL in the certificate, a cache, a file the user picked —
/// is the app's business, and network I/O has no place in a check that must be testable offline.
///
/// **OCSP.** ISO 15118-20 staples an OCSP response into the TLS handshake for the *station's* chain,
/// which is the transport's business rather than the wallet's. A contract certificate is a separate
/// question, and CRLs are what the ISO 15118 PKI publishes for it.
public enum V2GRevocationChecker {

    public static func check(_ certificate: V2GCertificate,
                             issuedBy issuer: V2GCertificate,
                             against crlDer: [UInt8],
                             now: Date = Date()) -> V2GRevocationStatus {

        let crl: V2GCrl
        do { crl = try V2GCrl(der: crlDer) }
        catch { return .unknown(why: "the CRL could not be parsed: \(error)") }

        // The signature first: everything below is only meaningful if this list came from the CA it
        // claims to. An unverified CRL that happens to be empty is the cheapest possible bypass.
        guard issuer.publicKeyForVerification.isValidSignature(crl.signature, for: crl.tbsBytes,
                                                               signatureAlgorithm: .ecdsaWithSHA256)
        else {
            return .unknown(why: "the CRL's signature does not verify under the issuing CA, so its "
                               + "contents mean nothing.")
        }

        guard crl.issuer == certificate.issuerName else {
            return .unknown(why: "this CRL was issued by \(crl.issuer), which did not issue the "
                               + "certificate — it says nothing about it either way.")
        }

        guard let nextUpdate = crl.nextUpdate else {
            return .unknown(why: "the CRL states no nextUpdate, so there is no point at which it "
                               + "stops being believed.")
        }
        guard now <= nextUpdate else {
            return .unknown(why: "the CRL expired on \(nextUpdate); a stale list cannot say whether a "
                               + "certificate has been revoked since.")
        }
        guard now >= crl.thisUpdate else {
            return .unknown(why: "the CRL is not valid until \(crl.thisUpdate).")
        }

        let serial = certificate.serialNumber.drop { $0 == 0 }
        guard let entry = crl.entries.first(where: { $0.serial == Array(serial) }) else {
            return .notRevoked
        }

        return .revoked(on: entry.revocationDate, reason: entry.reason)
    }
}
