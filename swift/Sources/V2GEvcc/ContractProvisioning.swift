import CommonCrypto
import CryptoKit
import Foundation

/// What went wrong unwrapping an issued contract key.
public enum ContractProvisioningError: Error, CustomStringConvertible, Equatable {

    /// A wire field arrived at the wrong width. Refused before anything is decrypted, because a
    /// length check that runs after the cipher is a length check that ran too late.
    case badFieldWidth(field: String, expected: Int, got: Int)

    /// The AES-GCM tag did not authenticate — -20 only. -2 cannot reach this: CBC has no tag.
    case authenticationFailed

    /// The unwrapped scalar is not a valid private key on the curve.
    case notAValidScalar

    public var description: String {
        switch self {
        case .badFieldWidth(let field, let expected, let got):
            return "\(field): expected \(expected) bytes, got \(got)."
        case .authenticationFailed:
            return "SECP521_EncryptedPrivateKey: the AES-GCM tag did not authenticate."
        case .notAValidScalar:
            return "the unwrapped bytes are not a valid private key on this curve."
        }
    }
}


/// The ISO 15118-**2** contract-provisioning key transport (§7.9.2.4), receiving side.
///
/// The secondary actor generated an ephemeral **secp256r1** key pair, ran ECDH against this car's
/// public key, derived a 128-bit AES key and AES-128-**CBC**-encrypted the issued contract's raw
/// private scalar. Here the car repeats the ECDH with its own private key and the transmitted
/// `DHpublickey`, and unwraps. Wire shapes come from the schema: `DHpublickey` = 65 B (uncompressed
/// P-256 point `0x04‖X32‖Y32`), `ContractSignatureEncryptedPrivateKey` = 48 B (`IV16‖ciphertext32`).
///
/// **Who the receiver is depends on the message.** For a `CertificateInstallationRes` it is the OEM
/// provisioning key — the only key a car has before it has a contract. For a `CertificateUpdateRes`
/// it is the *expiring contract* key, which is what makes a renewal self-authenticating: only the
/// holder of the old contract can open the new one.
///
/// ## Nothing here can fail on a wrong key, and that is the point
///
/// CBC authenticates nothing. Decrypting with the wrong ECDH partner does not throw — it yields 32
/// bytes of nonsense that make a perfectly valid private key belonging to nobody. ``matches(_:_:)``
/// is the check that catches it, and the caller has to run it; the corpus case
/// `iso2/install-wrong-receiver` records the exact nonsense so that a port whose KDF or cipher
/// differs fails there rather than silently agreeing to disagree.
///
/// ## Differences from the -20 transport, none of them cosmetic
///
/// A different curve (secp256r1 against secp521r1), a different cipher mode (CBC against GCM, so
/// this one is unauthenticated), a different KDF — the counter goes **after** Z here and before it
/// there — and half the key length. ``ContractProvisioning20`` shares no code with this, and making
/// it would only hide that.
///
/// ## Honesty note
///
/// The KDF is the ANSI X9.63 form the standard names, with empty SharedInfo. No capture in this
/// project contains a real `CertificateInstallationRes` from a foreign stack, so these *crypto
/// payload* octets are self-consistent across C#, Kotlin and Swift and nothing more. The wire
/// messages around them stay byte-exact per the usual oracles.
public enum ContractProvisioning2 {

    /// secp256r1.
    public static let scalarBytes = 32
    /// AES block size, and the IV that prefixes the ciphertext.
    public static let ivBytes = 16
    /// AES-128, per §7.9.2.4.
    static let keyBytes = 16

    public static let dhPublicKeyLength         = 1 + 2 * scalarBytes   // 65
    public static let encryptedPrivateKeyLength = ivBytes + scalarBytes // 48

    /// Repeats the ECDH with the car's own private key and the station's transmitted point, then
    /// unwraps the scalar and rebuilds the contract private key.
    ///
    /// The returned key is *a* key, always — see the type comment. Whether it is *the* key is
    /// ``matches(_:_:)``'s question.
    public static func recoverContractKey(receiver: P256.KeyAgreement.PrivateKey,
                                          dhPublicKey: [UInt8],
                                          encryptedPrivateKey: [UInt8]) throws -> P256.Signing.PrivateKey {

        guard dhPublicKey.count == dhPublicKeyLength, dhPublicKey[0] == 0x04 else {
            throw ContractProvisioningError.badFieldWidth(field: "DHpublickey",
                                                          expected: dhPublicKeyLength, got: dhPublicKey.count)
        }
        guard encryptedPrivateKey.count == encryptedPrivateKeyLength else {
            throw ContractProvisioningError.badFieldWidth(field: "ContractSignatureEncryptedPrivateKey",
                                                          expected: encryptedPrivateKeyLength,
                                                          got: encryptedPrivateKey.count)
        }

        let ephemeral = try P256.KeyAgreement.PublicKey(x963Representation: Data(dhPublicKey))
        let aesKey    = deriveKey(try receiver.sharedSecretFromKeyAgreement(with: ephemeral))

        let scalar = try AesCbc.decrypt(key: aesKey,
                                        iv: Array(encryptedPrivateKey[..<ivBytes]),
                                        ciphertext: Array(encryptedPrivateKey[ivBytes...]))

        guard let key = try? P256.Signing.PrivateKey(rawRepresentation: Data(scalar)) else {
            throw ContractProvisioningError.notAValidScalar
        }
        return key
    }

    /// Whether an unwrapped key really belongs to the certificate it arrived with — the check CBC's
    /// lack of authentication makes the caller's job.
    ///
    /// A sign/verify round trip rather than a comparison of parameters, because that is the property
    /// actually wanted: *this key can produce signatures that certificate verifies*.
    public static func matches(_ privateKey: P256.Signing.PrivateKey,
                               _ certificatePublicKey: P256.Signing.PublicKey) -> Bool {
        let probe = Data("contract-key-check".utf8)
        guard let signature = try? privateKey.signature(for: probe) else { return false }
        return certificatePublicKey.isValidSignature(signature, for: probe)
    }

    /// ANSI X9.63 KDF with SHA-256 and empty SharedInfo: `SHA-256(Z ‖ counter=1)`, truncated to the
    /// 16-byte AES-128 key. The counter goes **after** Z, which is the one place this differs from
    /// the ConcatKDF the -20 transport uses — and a port that copies the wrong one derives a
    /// different key from the same shared secret, silently.
    static func deriveKey(_ sharedSecret: SharedSecret) -> [UInt8] {
        var input = sharedSecret.withUnsafeBytes { Array($0) }
        input.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        return Array(SHA256.hash(data: input).prefix(keyBytes))
    }
}


/// The ISO 15118-**20** contract-provisioning key transport (`SignedInstallationData`), receiving side.
///
/// The SECC generated an ephemeral **secp521r1** key pair, ran ECDH against this car's static OEM
/// provisioning key, derived an AES-256 key and AES-256-**GCM**-encrypted the issued contract's raw
/// private scalar. Wire shapes: `DHPublicKey` = 133 B (uncompressed P-521 point `0x04‖X66‖Y66`),
/// `SECP521_EncryptedPrivateKey` = 94 B (`IV12‖ciphertext66‖tag16`).
///
/// Unlike its -2 sibling this **does** fail on a wrong key: GCM's tag check refuses, and no
/// after-the-fact certificate comparison is needed. That difference is recorded in the corpus rather
/// than only described — `iso20/install-wrong-receiver` expects no key at all where the -2 case
/// expects nonsense.
///
/// ## Honesty note
///
/// The KDF here is a single-round ConcatKDF (`SHA-512(0x00000001 ‖ Z)[0..<32]`, empty OtherInfo). It
/// is schema-valid and round-trips, but no external reference stack implements -20 provisioning to
/// diff against — Josev raises `NotImplementedError` on both sides — so as with -2 these payload
/// octets are self-consistent only.
public enum ContractProvisioning20 {

    /// secp521r1.
    public static let scalarBytes = 66
    public static let ivBytes  = 12
    public static let tagBytes = 16

    public static let dhPublicKeyLength         = 1 + 2 * scalarBytes            // 133
    public static let encryptedPrivateKeyLength = ivBytes + scalarBytes + tagBytes // 94

    /// Repeats the ECDH with the car's OEM private key and the station's transmitted point, then
    /// unwraps the scalar. Throws ``ContractProvisioningError/authenticationFailed`` when the tag
    /// does not hold — which is what a wrong key looks like here.
    public static func recoverContractKey(oemKey: P521.KeyAgreement.PrivateKey,
                                          dhPublicKey: [UInt8],
                                          encryptedPrivateKey: [UInt8]) throws -> P521.Signing.PrivateKey {

        guard dhPublicKey.count == dhPublicKeyLength, dhPublicKey[0] == 0x04 else {
            throw ContractProvisioningError.badFieldWidth(field: "DHPublicKey",
                                                          expected: dhPublicKeyLength, got: dhPublicKey.count)
        }
        guard encryptedPrivateKey.count == encryptedPrivateKeyLength else {
            throw ContractProvisioningError.badFieldWidth(field: "SECP521_EncryptedPrivateKey",
                                                          expected: encryptedPrivateKeyLength,
                                                          got: encryptedPrivateKey.count)
        }

        let ephemeral = try P521.KeyAgreement.PublicKey(x963Representation: Data(dhPublicKey))
        let aesKey    = SymmetricKey(data: deriveKey(try oemKey.sharedSecretFromKeyAgreement(with: ephemeral)))

        let sealed = try AES.GCM.SealedBox(
            nonce:      try AES.GCM.Nonce(data: Data(encryptedPrivateKey[..<ivBytes])),
            ciphertext: Data(encryptedPrivateKey[ivBytes ..< (ivBytes + scalarBytes)]),
            tag:        Data(encryptedPrivateKey[(ivBytes + scalarBytes)...]))

        guard let scalar = try? AES.GCM.open(sealed, using: aesKey) else {
            throw ContractProvisioningError.authenticationFailed
        }

        guard let key = try? P521.Signing.PrivateKey(rawRepresentation: scalar) else {
            throw ContractProvisioningError.notAValidScalar
        }
        return key
    }

    /// Single-round ConcatKDF (NIST SP 800-56A §5.8.1 with SHA-512, empty OtherInfo):
    /// `SHA-512(counter=1 ‖ Z)`, truncated to the 32-byte AES-256 key. Counter **before** Z — see
    /// ``ContractProvisioning2/deriveKey(_:)`` for the half that has it the other way round.
    static func deriveKey(_ sharedSecret: SharedSecret) -> [UInt8] {
        var input: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        input.append(contentsOf: sharedSecret.withUnsafeBytes { Array($0) })
        return Array(SHA512.hash(data: input).prefix(32))
    }
}


/// AES-128-CBC with no padding, which CryptoKit does not offer at all.
///
/// ## Why CommonCrypto rather than our own AES
///
/// The same argument `Package.swift` makes for depending on swift-certificates instead of writing a
/// certificate parser: C# uses `System.Security.Cryptography.Aes`, Kotlin uses
/// `Cipher.getInstance("AES/CBC/NoPadding")`, and both get their platform's implementation. Writing
/// our own block cipher only here would make Swift the outlier in the risky direction. CommonCrypto
/// is the platform's, ships in the SDK, and needs no package dependency.
///
/// Padding is `None` deliberately: the scalar is exactly two AES blocks, so there is nothing to pad
/// and the field is the 48 bytes the schema expects. PKCS#7 would append a whole block and make it
/// 64 — a mistake that round-trips perfectly against itself.
enum AesCbc {

    static func decrypt(key: [UInt8], iv: [UInt8], ciphertext: [UInt8]) throws -> [UInt8] {

        var out = [UInt8](repeating: 0, count: ciphertext.count)
        var written = 0
        let capacity = out.count

        let status = key.withUnsafeBytes { keyBytes in
            iv.withUnsafeBytes { ivBytes in
                ciphertext.withUnsafeBytes { inBytes in
                    out.withUnsafeMutableBytes { outBytes in
                        CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(0),
                                keyBytes.baseAddress, key.count,
                                ivBytes.baseAddress,
                                inBytes.baseAddress, ciphertext.count,
                                outBytes.baseAddress, capacity,
                                &written)
                    }
                }
            }
        }

        guard status == CCCryptorStatus(kCCSuccess), written == ciphertext.count else {
            throw ContractProvisioningError.badFieldWidth(field: "ContractSignatureEncryptedPrivateKey",
                                                          expected: ciphertext.count, got: written)
        }
        return out
    }
}
