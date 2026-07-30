#if canImport(CryptoKit)
import CryptoKit
import Foundation

/// The ISO 15118-2 half of §7.9: SHA-256 over a signed element's EXI **fragment** goes into a
/// `SignedInfo` Reference, and the `SignedInfo` fragment is itself ECDSA-P256 signed.
///
/// Hand-written, and it lives beside the generated code rather than in a shared module on purpose:
/// the fragment grammar's element selector is sized by the whole schema set, so the same
/// `SignedInfo` lands on a different event code in every set. One shared helper would sign the
/// wrong octets and produce a signature that verifies locally and nowhere else. The codegen driver
/// leaves this file alone — it only removes files whose first line marks them as generated.
///
/// ## The detail that decides interop
///
/// ISO 15118-2 puts the **raw `r‖s` pair** on the wire — 32 + 32 bytes — not ASN.1/DER. CryptoKit
/// exposes exactly that as `rawRepresentation`, so Swift needs no equivalent of the JCA's
/// `SHA256withECDSAinP1363Format` dance. It is still the easiest thing to get wrong here, and the
/// mistake is quiet: a DER signature verifies against itself and is rejected by every conforming
/// peer. ``signatureLength`` is asserted by the tests for that reason.
public enum V2GSignature {

    /// The wire length of an ISO 15118-2 signature: r and s, 32 bytes each.
    public static let signatureLength = 64

    /// SHA-256 over an element's EXI fragment — the digest a `SignedInfo` Reference carries.
    ///
    /// The input must be a *fragment*, not a message: a document-wrapped encoding of the same
    /// content digests differently and would be rejected by any peer that follows §7.9.
    public static func digest(ofFragment fragment: [UInt8]) -> [UInt8] {
        Array(SHA256.hash(data: fragment))
    }

    /// Signs a `SignedInfo` over its own EXI fragment, returning the raw `r‖s` pair.
    public static func sign(_ signedInfo: SignedInfoType,
                            with key: P256.Signing.PrivateKey) throws -> [UInt8] {
        let fragment = Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo)
        let signature = try key.signature(for: Data(fragment))
        return Array(signature.rawRepresentation)
    }

    /// Verifies a raw `r‖s` signature over a `SignedInfo`'s own EXI fragment.
    ///
    /// A signature of any other length is rejected outright rather than handed to CryptoKit: a DER
    /// blob would fail there too, but with an error that says nothing about *why*, and this is the
    /// mistake worth naming.
    public static func verify(_ signedInfo: SignedInfoType,
                              signature: [UInt8],
                              with key: P256.Signing.PublicKey) -> Bool {
        guard signature.count == signatureLength,
              let parsed = try? P256.Signing.ECDSASignature(rawRepresentation: Data(signature))
        else { return false }

        let fragment = Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo)
        return key.isValidSignature(parsed, for: Data(fragment))
    }
}
#endif
