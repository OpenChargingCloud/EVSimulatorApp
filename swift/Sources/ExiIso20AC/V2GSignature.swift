#if canImport(CryptoKit)
import CryptoKit
import Foundation
import V2GEd448

/// The ISO 15118-20 AC half of the -20 signature suite: SHA-512 over a signed element's EXI
/// **fragment** goes into a `SignedInfo` Reference, and the `SignedInfo` fragment is itself signed.
///
/// ## Why every set carries its own copy
///
/// This helper is repeated across the -20 sets, and that is not laziness. Each set embeds its own
/// copy of the XMLDSig schema, and the fragment grammar's element selector is sized by the whole
/// set — so the *same* `SignedInfo` lands on a different event code in each. Here it is **135 over 8 bits**.
/// Different octets, therefore different signed bytes. Borrowing another set's helper would sign
/// something no peer asked for, and the signature would verify locally and nowhere else. Note the
/// DER sets move too, even though their *messages* are AC's.
///
/// ## Both algorithms of the suite
///
/// * **ECDSA over secp521r1 with SHA-512** — CryptoKit has P-521 natively and exposes the raw
///   `r‖s` pair the wire wants as `rawRepresentation`. 66 + 66 bytes.
/// * **Ed448** (RFC 8032, 114 bytes) — via `V2GEd448`, our own surface over a vendored
///   libgoldilocks (`Sources/CGoldilocks`). CryptoKit does not have the curve at all: not an
///   unregistered provider as it would be on the JVM, the primitive is absent. Held to RFC 8032
///   §7.4 byte for byte by `Ed448VectorTests` — see `swift/SPIKE-ed448.md` for why this
///   implementation.
///
/// Ed448 here is **pure** EdDSA with an **empty context**, matching ISO 15118-20's
/// `#eddsa-ed448` identifier — RFC 9231 §2.3.12 lists the prehashed `#eddsa-ed448ph` separately,
/// and it is not implemented. Pure EdDSA signs the fragment octets directly: SHAKE256 inside the
/// algorithm takes the place of an external pre-hash, so unlike ``sign(_:with:)-(_,P521.Signing.PrivateKey)``
/// there is no separate SHA-512 step. The SHA-512 in ``digest(ofFragment:)`` is a different thing —
/// it hashes *signed elements* into their References, whichever signature algorithm is chosen.
///
/// ## Which algorithm, decided by the message rather than by us
///
/// `SignedInfo` carries the algorithm in `SignatureMethod/@Algorithm`, so a peer *states* its
/// choice. Every entry point below reads that declaration and refuses to act against it: signing
/// with a key whose algorithm the SignedInfo does not name is a
/// ``V2GSignatureError/algorithmMismatch(declared:attempted:)``, and an identifier we do not
/// implement is an ``V2GSignatureError/unsupportedAlgorithm(_:)``.
///
/// This replaced dispatching on the signature *length*, which was a guess at something we had been
/// told — and which would have signed under one algorithm while declaring another, producing a
/// signature that verifies locally and is rejected by every conforming peer.
public enum V2GSignature {

    // ── The algorithm identifiers, as they appear on the wire ───────────────────────────────────

    /// EXI canonicalization — the only C14N ISO 15118-20 uses.
    public static let canonicalizationExi = "http://www.w3.org/TR/canonical-exi/"

    /// SHA-512 digest method.
    public static let sha512 = "http://www.w3.org/2001/04/xmlenc#sha512"

    /// The two signature methods ISO 15118-20's suite allows.
    public enum Algorithm: String, CaseIterable, Sendable {
        /// ECDSA over secp521r1 with SHA-512, raw `r‖s`.
        case ecdsaSha512 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"
        /// Pure Ed448 (RFC 8032), empty context — RFC 9231 §2.3.12.
        case eddsaEd448  = "http://www.w3.org/2021/04/xmldsig-more#eddsa-ed448"

        /// The `SignatureValue` length this algorithm always produces.
        public var signatureLength: Int {
            switch self {
            case .ecdsaSha512: return 132   // 66 + 66
            case .eddsaEd448:  return 114
            }
        }
    }

    /// The wire length of a -20 ECDSA signature: r and s, 66 bytes each.
    public static let ecdsaSignatureLength = Algorithm.ecdsaSha512.signatureLength

    /// The wire length of an Ed448 signature.
    public static let ed448SignatureLength = Algorithm.eddsaEd448.signatureLength

    /// The algorithm a `SignedInfo` declares, or a throw naming what it asked for.
    ///
    /// A caller routes on this rather than on a signature length. `#eddsa-ed448ph` reaches this and
    /// is refused by name — the prehashed variant is a different algorithm, not a flag.
    public static func algorithm(of signedInfo: SignedInfoType) throws -> Algorithm {
        let declared = signedInfo.signatureMethod.algorithm
        guard let algorithm = Algorithm(rawValue: declared) else {
            throw V2GSignatureError.unsupportedAlgorithm(declared)
        }
        return algorithm
    }

    private static func require(_ signedInfo: SignedInfoType, is wanted: Algorithm) throws {
        let declared = try algorithm(of: signedInfo)
        guard declared == wanted else {
            throw V2GSignatureError.algorithmMismatch(declared: declared, attempted: wanted)
        }
    }

    // ── Digest ──────────────────────────────────────────────────────────────────────────────────

    /// SHA-512 over an element's EXI fragment — the digest a `SignedInfo` Reference carries.
    ///
    /// The input must be a *fragment*, not a message: a document-wrapped encoding of the same
    /// content digests differently and would be rejected by any conforming peer.
    public static func digest(ofFragment fragment: [UInt8]) -> [UInt8] {
        Array(SHA512.hash(data: fragment))
    }

    // ── ECDSA over secp521r1 ────────────────────────────────────────────────────────────────────

    /// Signs a `SignedInfo` over its own EXI fragment with ECDSA-P521, returning the raw `r‖s` pair.
    public static func sign(_ signedInfo: SignedInfoType,
                            with key: P521.Signing.PrivateKey) throws -> [UInt8] {
        try require(signedInfo, is: .ecdsaSha512)
        let fragment = ACCodec.encodeFragment_SignedInfo(signedInfo)
        return Array(try key.signature(for: Data(fragment)).rawRepresentation)
    }

    /// Verifies a raw `r‖s` ECDSA-P521 signature over a `SignedInfo`'s own EXI fragment.
    ///
    /// A signature of any other length is refused before CryptoKit sees it. That covers the DER
    /// form, which a JCA- or OpenSSL-minded implementation produces by default and which verifies
    /// against itself perfectly.
    public static func verify(_ signedInfo: SignedInfoType,
                              signature: [UInt8],
                              with key: P521.Signing.PublicKey) throws -> Bool {
        try require(signedInfo, is: .ecdsaSha512)
        guard signature.count == ecdsaSignatureLength,
              let parsed = try? P521.Signing.ECDSASignature(rawRepresentation: Data(signature))
        else { return false }

        let fragment = ACCodec.encodeFragment_SignedInfo(signedInfo)
        return key.isValidSignature(parsed, for: Data(fragment))
    }

    // ── Ed448 ───────────────────────────────────────────────────────────────────────────────────

    /// Signs a `SignedInfo` over its own EXI fragment with pure Ed448, returning the raw 114 bytes.
    public static func sign(_ signedInfo: SignedInfoType,
                            with key: Ed448PrivateKey) throws -> [UInt8] {
        try require(signedInfo, is: .eddsaEd448)
        let fragment = ACCodec.encodeFragment_SignedInfo(signedInfo)
        return try Ed448.sign(fragment,
                              seed: key.rawRepresentation,
                              publicKey: key.publicKey.rawRepresentation)
    }

    /// Verifies a raw Ed448 signature over a `SignedInfo`'s own EXI fragment.
    public static func verify(_ signedInfo: SignedInfoType,
                              signature: [UInt8],
                              with key: Ed448PublicKey) throws -> Bool {
        try require(signedInfo, is: .eddsaEd448)
        guard signature.count == ed448SignatureLength else { return false }

        let fragment = ACCodec.encodeFragment_SignedInfo(signedInfo)
        return try Ed448.verify(signature, of: fragment, publicKey: key.rawRepresentation)
    }
}

// ── Ed448 keys ──────────────────────────────────────────────────────────────────────────────────

/// An Ed448 public key: 57 raw bytes, as RFC 8032 encodes them.
public struct Ed448PublicKey: Equatable, Sendable {

    public static let byteCount = 57

    public let rawRepresentation: [UInt8]

    public init(rawRepresentation: [UInt8]) throws {
        guard rawRepresentation.count == Self.byteCount else {
            throw V2GSignatureError.invalidKeyLength(expected: Self.byteCount,
                                                     actual: rawRepresentation.count)
        }
        self.rawRepresentation = rawRepresentation
    }
}

/// An Ed448 private key: the 57-byte seed, carrying its derived public key.
///
/// The public key is a stored property rather than a parameter because the underlying C API takes
/// both, and two adjacent `[UInt8]` arguments of identical length are an argument-order mistake
/// waiting to happen. Deriving it once here removes the trap and the recomputation together.
///
/// Note the consequence of the per-set module split: this type exists once *per message set*, so a
/// wallet holding one Ed448 key and talking to several sets converts between them —
/// `try ExiIso20AC.Ed448PrivateKey(rawRepresentation: key.rawRepresentation)`. That is cheap and
/// lossless, since the seed is the whole key, but it is real friction and it is the same friction
/// the duplicated `V2GSignature` has. Both follow from the sets being independent grammars rather
/// than from a choice made here.
public struct Ed448PrivateKey: Sendable {

    public static let byteCount = 57

    public let rawRepresentation: [UInt8]
    public let publicKey: Ed448PublicKey

    public init(rawRepresentation: [UInt8]) throws {
        guard rawRepresentation.count == Self.byteCount else {
            throw V2GSignatureError.invalidKeyLength(expected: Self.byteCount,
                                                     actual: rawRepresentation.count)
        }
        self.rawRepresentation = rawRepresentation
        self.publicKey = try Ed448PublicKey(
            rawRepresentation: try Ed448.derivePublicKey(fromSeed: rawRepresentation))
    }

    /// A fresh key from the system CSPRNG.
    ///
    /// Note what this is *not*: an Ed448 key cannot live in the Secure Enclave, which holds P-256
    /// only. -20 keys are software keys wherever they are stored, and `docs/CONCEPT.md` §3.4 says
    /// the UI has to be honest about that rather than implying hardware protection.
    public init() throws {
        try self.init(rawRepresentation:
            SymmetricKey(size: .init(bitCount: Self.byteCount * 8)).withUnsafeBytes(Array.init))
    }
}

public enum V2GSignatureError: Error, Equatable {

    /// The `SignedInfo` names a signature method this build does not implement — `#eddsa-ed448ph`,
    /// say. Carries the identifier verbatim, because the useful thing to log is what the peer
    /// actually asked for.
    case unsupportedAlgorithm(String)

    /// The `SignedInfo` declares one algorithm and the key offered is for the other. Refused rather
    /// than honoured: signing under an algorithm the message does not name produces a signature no
    /// conforming peer will accept, and it is a caller mistake rather than a cryptographic failure.
    case algorithmMismatch(declared: V2GSignature.Algorithm, attempted: V2GSignature.Algorithm)

    /// An Ed448 key of the wrong length.
    case invalidKeyLength(expected: Int, actual: Int)
}
#endif
