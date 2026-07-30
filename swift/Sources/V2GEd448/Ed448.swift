import CGoldilocks

/// Pure Ed448 (RFC 8032), the second signature algorithm of ISO 15118-20's suite.
///
/// Our own surface over the vendored libgoldilocks in `Sources/CGoldilocks` — see the
/// `PROVENANCE.md` there for where that C came from and why it is not edited. This file is the part
/// we maintain: about a hundred lines, which is why owning it was cheaper than depending on someone
/// else's hundred lines and inheriting their release cadence.
///
/// ## Why this exists at all
///
/// CryptoKit has no Ed448. Not an unregistered provider as on the JVM, where BouncyCastle supplies
/// it — Apple's library does not implement the curve, so there is nothing to register and a bundled
/// implementation is the only option (`docs/CONCEPT.md` §3.3).
///
/// ## Scope, deliberately narrow
///
/// **Pure** Ed448 with an **empty context**, which is what ISO 15118-20's `#eddsa-ed448` identifier
/// denotes. RFC 9231 §2.3.12 lists the prehashed `#eddsa-ed448ph` under its own identifier; it is a
/// different algorithm and is not offered here. Nor is the context exposed: RFC 8032 §5.2 allows up
/// to 255 octets, but -20 uses none, and a parameter nobody may set is a way to get it wrong.
///
/// The vendored C also implements X448 and SHAKE. Neither is surfaced, because neither is needed —
/// -20's key exchange is secp521r1. Adding them later is a few lines; carrying them now would be
/// API surface with no caller and no test.
///
/// ## Correctness
///
/// Held to RFC 8032 §7.4's published vectors by `Ed448VectorTests`, byte for byte. Ed448 is
/// deterministic, so that is an equality check rather than a round trip — the strongest oracle in
/// this repository, since it compares against the standard's own numbers rather than another
/// implementation's output.
public enum Ed448 {

    /// Length of the private-key seed, in bytes.
    public static let privateKeyByteCount = Int(CE_ED448_PRIVATE_KEY_BYTES)   // 57

    /// Length of a public key, in bytes.
    public static let publicKeyByteCount = Int(CE_ED448_PUBLIC_KEY_BYTES)     // 57

    /// Length of a signature, in bytes.
    public static let signatureByteCount = Int(CE_ED448_SIGNATURE_BYTES)      // 114

    public enum Error: Swift.Error, Equatable {
        case invalidKeyLength(expected: Int, actual: Int)
        case invalidSignatureLength(expected: Int, actual: Int)
    }

    /// Derives the public key belonging to a 57-byte seed.
    public static func derivePublicKey(fromSeed seed: [UInt8]) throws -> [UInt8] {
        try check(seed.count, is: privateKeyByteCount)

        var publicKey = [UInt8](repeating: 0, count: publicKeyByteCount)
        publicKey.withUnsafeMutableBufferPointer { out in
            seed.withUnsafeBufferPointer { seed in
                ce_ed448_derive_public_key(out.baseAddress, seed.baseAddress)
            }
        }
        return publicKey
    }

    /// Signs `message`, returning the 114-byte signature.
    ///
    /// The public key is taken as well because the C API wants it — it would otherwise re-derive it
    /// on every call. Callers should not be passing two same-length byte arrays by hand; use
    /// `Ed448PrivateKey`, which holds a matched pair.
    public static func sign(_ message: [UInt8],
                            seed: [UInt8],
                            publicKey: [UInt8]) throws -> [UInt8] {
        try check(seed.count, is: privateKeyByteCount)
        try check(publicKey.count, is: publicKeyByteCount)

        var signature = [UInt8](repeating: 0, count: signatureByteCount)
        signature.withUnsafeMutableBufferPointer { out in
            seed.withUnsafeBufferPointer { seed in
                publicKey.withUnsafeBufferPointer { publicKey in
                    message.withUnsafeBufferPointer { message in
                        // baseAddress is nil for an empty message, which the C side accepts with a
                        // zero length — pinned by the RFC's own zero-length vector, because nothing
                        // upstream documents it.
                        ce_ed448_sign(out.baseAddress, seed.baseAddress, publicKey.baseAddress,
                                      message.baseAddress, message.count)
                    }
                }
            }
        }
        return signature
    }

    /// Verifies a signature over `message`. `false` means the signature is invalid; a wrong *length*
    /// throws instead, because that is a caller mistake rather than a cryptographic verdict.
    public static func verify(_ signature: [UInt8],
                              of message: [UInt8],
                              publicKey: [UInt8]) throws -> Bool {
        guard signature.count == signatureByteCount else {
            throw Error.invalidSignatureLength(expected: signatureByteCount, actual: signature.count)
        }
        try check(publicKey.count, is: publicKeyByteCount)

        return signature.withUnsafeBufferPointer { signature in
            publicKey.withUnsafeBufferPointer { publicKey in
                message.withUnsafeBufferPointer { message in
                    ce_ed448_verify(signature.baseAddress, publicKey.baseAddress,
                                    message.baseAddress, message.count) == CE_ED448_SUCCESS
                }
            }
        }
    }

    private static func check(_ actual: Int, is expected: Int) throws {
        guard actual == expected else {
            throw Error.invalidKeyLength(expected: expected, actual: actual)
        }
    }
}
