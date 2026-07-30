#if canImport(CryptoKit)
import CryptoKit
import Foundation

/// The ISO 15118-20 DC half of the -20 signature suite: SHA-512 over a signed element's EXI
/// **fragment** goes into a `SignedInfo` Reference, and the `SignedInfo` fragment is itself signed.
///
/// ## Why every set carries its own copy
///
/// This helper is repeated across the -20 sets, and that is not laziness. Each set embeds its own
/// copy of the XMLDSig schema, and the fragment grammar's element selector is sized by the whole
/// set — so the *same* `SignedInfo` lands on a different event code in each. Here it is **129 over 8 bits**.
/// Different octets, therefore different signed bytes. Borrowing another set's helper would sign
/// something no peer asked for, and the signature would verify locally and nowhere else. Note the
/// DER sets move too, even though their *messages* are AC's.
///
/// ## What is implemented, and what is not
///
/// ISO 15118-20 allows two signature algorithms. Only the ECDSA one is here:
///
/// * **ECDSA over secp521r1 with SHA-512** — CryptoKit has P-521 natively, and exposes the raw
///   `r‖s` pair the wire wants as `rawRepresentation`. 66 + 66 bytes.
/// * **Ed448** (RFC 8032, 114 bytes) — **not available.** CryptoKit does not have the curve at
///   all; this is not a matter of an unregistered provider as it would be on the JVM, where
///   BouncyCastle supplies it. Implementing it needs a bundled crypto library (BC-Swift, OpenSSL
///   or wolfSSL), which is a dependency and binary-size decision for the app rather than anything
///   this layer can settle. See `docs/CONCEPT.md` §3.3.
///
/// A peer that selects Ed448 therefore cannot be answered by this build, and
/// ``sign(_:with:)`` is the only signing path there is — rather than an Ed448 stub that would
/// look implemented.
public enum V2GSignature {

    /// The wire length of a -20 ECDSA signature: r and s, 66 bytes each.
    public static let ecdsaSignatureLength = 132

    /// The wire length an Ed448 signature would have, kept for the check below.
    public static let ed448SignatureLength = 114

    /// SHA-512 over an element's EXI fragment — the digest a `SignedInfo` Reference carries.
    ///
    /// The input must be a *fragment*, not a message: a document-wrapped encoding of the same
    /// content digests differently and would be rejected by any conforming peer.
    public static func digest(ofFragment fragment: [UInt8]) -> [UInt8] {
        Array(SHA512.hash(data: fragment))
    }

    /// Signs a `SignedInfo` over its own EXI fragment with ECDSA-P521, returning the raw `r‖s`
    /// pair.
    public static func sign(_ signedInfo: SignedInfoType,
                            with key: P521.Signing.PrivateKey) throws -> [UInt8] {
        let fragment = DCCodec.encodeFragment_SignedInfo(signedInfo)
        return Array(try key.signature(for: Data(fragment)).rawRepresentation)
    }

    /// Verifies a raw `r‖s` ECDSA-P521 signature over a `SignedInfo`'s own EXI fragment.
    ///
    /// A signature of any other length is refused before CryptoKit sees it. That covers the DER
    /// form, which a JCA- or OpenSSL-minded implementation produces by default and which verifies
    /// against itself perfectly — and an Ed448 signature, which this build cannot check at all and
    /// must not silently report as invalid *cryptography* when the real answer is "unsupported
    /// algorithm".
    public static func verify(_ signedInfo: SignedInfoType,
                              signature: [UInt8],
                              with key: P521.Signing.PublicKey) throws -> Bool {
        if signature.count == ed448SignatureLength {
            throw V2GSignatureError.ed448NotAvailable
        }
        guard signature.count == ecdsaSignatureLength,
              let parsed = try? P521.Signing.ECDSASignature(rawRepresentation: Data(signature))
        else { return false }

        let fragment = DCCodec.encodeFragment_SignedInfo(signedInfo)
        return key.isValidSignature(parsed, for: Data(fragment))
    }
}

public enum V2GSignatureError: Error, Equatable {
    /// The signature is Ed448-shaped. The algorithm is part of the -20 suite but CryptoKit has no
    /// Ed448, so this build can neither produce nor check one — reported as a distinct condition so
    /// a caller can tell "unsupported" from "invalid".
    case ed448NotAvailable
}
#endif
