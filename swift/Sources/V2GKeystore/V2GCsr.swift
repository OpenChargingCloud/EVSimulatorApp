import CryptoKit
import Foundation
import SwiftASN1
import X509

/// Builds a PKCS#10 certificate signing request for a key this app holds.
///
/// ## Why a CSR is the first real customer of ``V2GSigner``
///
/// A CSR proves possession of a private key, so it must be **signed by that key** — and if the key
/// lives in a secure element its bytes never leave. The request is assembled, the octets are handed
/// out, a signature comes back. Had this taken a private key, hardware-backed credentials would have
/// been impossible in a second place as well as the first.
///
/// ## Two things worth knowing about the implementation
///
/// **`swift-certificates` cannot quite do this on its own.** It offers an initialiser taking a
/// pre-computed signature, which is exactly the external-signer shape — but the bytes that signature
/// must cover (`CertificationRequestInfo`) are `@usableFromInline internal`, so there is no public
/// way to ask for them. The way through is to build the request once with a placeholder signature,
/// serialise it, and take the first element of the outer `SEQUENCE`, which by PKCS#10's own
/// definition *is* those bytes. Then sign and build again. BouncyCastle, which the Kotlin half uses,
/// takes an external `ContentSigner` directly and needs none of this.
///
/// **The signature form changes here.** ``V2GSigner`` returns raw `r‖s`, because that is what ISO
/// 15118 puts on the wire and the field is sized for it. X.509 and PKCS#10 want the DER form,
/// `SEQUENCE { INTEGER r, INTEGER s }`. A CSR carrying a raw signature is not malformed in a way any
/// parser notices — it simply fails to verify at whichever CA receives it.
public enum V2GCsr {

    /// - Parameters:
    ///   - subject: the distinguished name to request, e.g. `CN=DE8AA1A2B3C4D5`
    ///   - signer: the key proving possession; only P-256 so far
    /// - Returns: the DER-encoded CertificationRequest
    public static func build(subject: DistinguishedName, signer: any V2GSigner) throws -> [UInt8] {

        guard signer.curve == .p256 else {
            throw V2GKeyError.unsupportedCurve(signer.curve)
        }

        let publicKey = try Certificate.PublicKey(
            P256.Signing.PublicKey(derRepresentation: Data(signer.publicKeyDer)))

        // Round one: a placeholder signature, only so the request can be serialised and the bytes
        // that a real signature must cover read back out of it.
        let placeholder = try Certificate.Signature(
            signatureAlgorithm: .ecdsaWithSHA256,
            signatureBytes: ASN1BitString(bytes: ArraySlice(placeholderDerSignature)))

        let draft = try CertificateSigningRequest(
            version: .v1, subject: subject, publicKey: publicKey,
            attributes: CertificateSigningRequest.Attributes(),
            signatureAlgorithm: .ecdsaWithSHA256, signature: placeholder)

        var serializer = DER.Serializer()
        try serializer.serialize(draft)
        let info = try firstElement(ofSequence: serializer.serializedBytes)

        // Round two: the real signature, over exactly those bytes.
        let raw = try signer.signature(over: info)
        let der = try P256.Signing.ECDSASignature(rawRepresentation: Data(raw)).derRepresentation

        let signature = try Certificate.Signature(
            signatureAlgorithm: .ecdsaWithSHA256,
            signatureBytes: ASN1BitString(bytes: ArraySlice(Array(der))))

        let request = try CertificateSigningRequest(
            version: .v1, subject: subject, publicKey: publicKey,
            attributes: CertificateSigningRequest.Attributes(),
            signatureAlgorithm: .ecdsaWithSHA256, signature: signature)

        var out = DER.Serializer()
        try out.serialize(request)
        return out.serializedBytes
    }

    /// Any well-formed 64-byte ECDSA signature in DER, used only to make the draft serialisable. Its
    /// value never reaches a caller: the draft exists to be measured, not to be sent.
    private static var placeholderDerSignature: [UInt8] {
        Array(try! P256.Signing.ECDSASignature(rawRepresentation: Data(repeating: 1, count: 64))
                  .derRepresentation)
    }

    /// The first TLV inside a definite-length DER `SEQUENCE`, whole — header and content.
    ///
    /// Deliberately a small explicit walk rather than a parse-and-reserialise: what is wanted is the
    /// *original* bytes, and a value that round-trips through any encoder is not guaranteed to come
    /// back identical. A signature over re-encoded bytes would verify here and nowhere else.
    internal static func firstElement(ofSequence der: [UInt8]) throws -> [UInt8] {

        var index = 0

        func readHeader() throws -> (contentStart: Int, length: Int) {
            guard index + 1 < der.count else { throw V2GCsrError.truncated }
            index += 1                                   // tag
            let first = Int(der[index]); index += 1      // length

            if first < 0x80 { return (index, first) }

            let count = first & 0x7F
            guard count > 0, count <= 4, index + count <= der.count else { throw V2GCsrError.truncated }

            var length = 0
            for _ in 0 ..< count { length = (length << 8) | Int(der[index]); index += 1 }
            return (index, length)
        }

        _ = try readHeader()                             // the outer SEQUENCE
        let elementStart = index
        let (contentStart, length) = try readHeader()    // the first element

        guard contentStart + length <= der.count else { throw V2GCsrError.truncated }
        return Array(der[elementStart ..< contentStart + length])
    }
}

public enum V2GCsrError: Error, CustomStringConvertible {
    case truncated

    public var description: String {
        switch self {
        case .truncated: return "the DER ended in the middle of a value."
        }
    }
}
