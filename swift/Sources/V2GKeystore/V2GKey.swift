import CryptoKit
import Foundation

/// The curves ISO 15118 credentials actually use.
public enum V2GKeyCurve: String, Sendable, CaseIterable {

    /// ISO 15118-2's contract and TLS keys, and the only curve any current secure element holds.
    case p256

    /// One of ISO 15118-20's two mandatory signature suites.
    case p521

    /// The other. The JDK has no Ed448 at all and CryptoKit lacks the curve entirely — see §3.3.
    case ed448

    /// Whether a secure element can hold this key **at all**.
    ///
    /// This is the whole of `docs/CONCEPT.md` §3.4, expressed as a value rather than a paragraph:
    /// the iOS Secure Enclave does P-256 and nothing else, and Android's StrongBox/TEE does P-256 and
    /// RSA where it does anything. So a -2 contract key can be hardware-backed and a -20 one cannot,
    /// on either platform, for reasons no amount of application code can work around.
    public var canBeHardwareBacked: Bool { self == .p256 }
}


/// The digest an ECDSA signature is computed over. **The caller names it**, never the curve: the
/// signature form that declares the algorithm in its `SignedInfo` is what decides, and a signer that
/// picked the curve's natural pairing would contradict that declaration.
///
/// Both are needed. ISO 15118-2, Josev's -20 interop form, and a PKCS#10 request are SHA-256. The
/// -20 header signature is `ecdsa-sha512` ([V2G20-2473]), and EVerest 2026.10.0 refuses anything else.
public enum V2GSignatureHash: Sendable, CaseIterable {
    case sha256
    case sha512
}


/// Where a private key actually lives, and therefore what can be claimed about it.
///
/// The point of naming this rather than assuming it: §3.4 asks for the asymmetry to be **explicit in
/// the UI**, because "a simulator that quietly pretends its keys are hardware-protected is worse than
/// one that says they aren't". A protection level that is a value can be displayed, asserted and got
/// wrong loudly; one that is an implementation detail can only be got wrong quietly.
public enum V2GKeyProtection: Sendable, Equatable {

    /// A secure element — Secure Enclave, StrongBox, or a TEE. The private key has **no bytes** the
    /// app can hold: it signs on request and cannot be exported. That is what makes it worth having,
    /// and it is why ``V2GSigner`` asks for a signature rather than for a key.
    case hardware(element: String)

    /// A software key at rest in platform-encrypted storage — Keychain, EncryptedSharedPreferences.
    /// Protected by the device, not by a separate processor.
    case softwareInSecureStorage

    /// A software key held in process memory. Tests and ephemeral material; never a credential a user
    /// would rely on.
    case softwareInMemory

    public var isHardwareBacked: Bool {
        if case .hardware = self { return true }
        return false
    }

    /// What to show a user, in the plainest terms that are still true.
    ///
    /// Both software cases open with the word "software" on purpose. An earlier version said only
    /// "not by separate hardware", which is accurate and requires the reader to work out the
    /// consequence — and §3.4's whole point is that a user should not have to. The word is what a
    /// person scanning a list of keys is looking for.
    public var disclosure: String {
        switch self {
        case .hardware(let element):
            return "Protected by this device's \(element). The private key cannot be read, exported "
                 + "or copied — not by this app and not by anything else."
        case .softwareInSecureStorage:
            return "A software key, stored encrypted by the device. The private key exists as data "
                 + "this app can read, so it is protected by the device's encryption and not by "
                 + "separate hardware."
        case .softwareInMemory:
            return "A software key, held in memory only. Not stored, not protected, and gone when "
                 + "the app closes."
        }
    }
}


/// A private key that can sign but need not be readable.
///
/// **The shape is the point.** A secure-element key cannot be exported, so any interface that hands
/// out private key material rules out hardware backing entirely — whatever the curve. Asking for a
/// signature instead is what leaves the door open, and is why `PncEvccOptions` takes one of these
/// rather than a `P256.Signing.PrivateKey`.
///
/// §3.4 asks for the key store to be designed around the asymmetry "from the start rather than
/// discovering it at the first P-521 keygen". The curve half of that is a table; this half is an API
/// decision, and it is the one that cannot be retrofitted cheaply.
public protocol V2GSigner: Sendable {

    var curve: V2GKeyCurve { get }
    var protection: V2GKeyProtection { get }

    /// The public key, DER-encoded as SubjectPublicKeyInfo.
    var publicKeyDer: [UInt8] { get }

    /// Signs `octets` **with ECDSA over their `hash` digest**, returning the raw `r‖s` pair
    /// ISO 15118 puts on the wire — never DER. The field is sized to the curve, so a DER signature
    /// does not fit and the usual mistake fails loudly rather than silently.
    ///
    /// ## Why the caller names the hash, not the curve
    ///
    /// CryptoKit pairs each curve with a default digest: SHA-256 for P-256, SHA-512 for P-521. Either
    /// default contradicts some declaration. A P-521 OEM key that simply forwarded the octets once
    /// produced a signature declaring SHA-256 and computed over SHA-512. It verified against itself
    /// and against nothing else, and the recorded provisioning session caught it.
    ///
    /// The hash used to be fixed at SHA-256, because the only form signed through here was Josev's,
    /// which declares `ecdsa-sha256` on both protocols and whatever the key. -20 asks for
    /// `ecdsa-sha512`, and EVerest 2026.10.0 refused the SHA-256 form with `FAILED_SignatureError`
    /// (2026-11-27). So the form decides, and says so here.
    ///
    /// A Secure Enclave key keeps working under both: it signs whatever digest it is handed.
    func signature(over octets: [UInt8], hash: V2GSignatureHash) throws -> [UInt8]
}


/// Why a key could not be created or stored as asked.
public enum V2GKeyError: Error, Equatable, CustomStringConvertible {

    /// Hardware backing was requested for a curve no secure element can hold. Refused rather than
    /// downgraded: silently storing a software key where hardware was asked for is exactly the
    /// "quietly pretends" case §3.4 warns about.
    case curveCannotBeHardwareBacked(V2GKeyCurve)

    /// The curve is not one this build can operate on at all.
    case unsupportedCurve(V2GKeyCurve)

    /// A software signer was asked to describe itself as hardware-backed. Distinct from
    /// ``curveCannotBeHardwareBacked`` on purpose: P-256 *can* live in a secure element, just not in
    /// this object, and reporting the curve as the reason would send someone looking in the wrong
    /// place entirely.
    case softwareSignerCannotClaimHardware

    public var description: String {
        switch self {
        case .curveCannotBeHardwareBacked(let curve):
            return "\(curve.rawValue) cannot be held in a secure element — the Secure Enclave and "
                 + "StrongBox do P-256 only (§3.4). A software key is available; it must be asked "
                 + "for, not substituted."
        case .unsupportedCurve(let curve):
            return "this build cannot operate on \(curve.rawValue) keys."
        case .softwareSignerCannotClaimHardware:
            return "this signer holds the key in software and cannot describe itself as "
                 + "hardware-backed. A hardware key comes from the platform, never from a key this "
                 + "code was handed."
        }
    }
}


/// A software P-256 signer held in memory. What the tests use, and the fallback an app has before it
/// binds anything to the platform.
public struct InMemoryP256Signer: V2GSigner {

    private let key: P256.Signing.PrivateKey

    public let protection: V2GKeyProtection
    public var curve: V2GKeyCurve { .p256 }
    public var publicKeyDer: [UInt8] { Array(key.publicKey.derRepresentation) }

    public init(_ key: P256.Signing.PrivateKey,
                protection: V2GKeyProtection = .softwareInMemory) throws {
        // A software signer claiming hardware would defeat the whole disclosure. The check is here
        // rather than at the call site because this is the type that knows it holds bytes — and the
        // reason is *that*, not the curve: P-256 can live in a secure element, simply not in an
        // object it was handed to.
        if protection.isHardwareBacked {
            throw V2GKeyError.softwareSignerCannotClaimHardware
        }
        self.key = key
        self.protection = protection
    }

    public func signature(over octets: [UInt8], hash: V2GSignatureHash) throws -> [UInt8] {
        switch hash {
        case .sha256: return Array(try key.signature(for: SHA256.hash(data: Data(octets))).rawRepresentation)
        case .sha512: return Array(try key.signature(for: SHA512.hash(data: Data(octets))).rawRepresentation)
        }
    }
}


/// A software P-521 signer held in memory — the other of ISO 15118-20's mandatory ECDSA suites.
///
/// No hardware variant exists or can: ``V2GKeyCurve/canBeHardwareBacked`` is false for P-521 on both
/// platforms, so unlike ``InMemoryP256Signer`` this type is not a fallback to something better. It is
/// the only shape a P-521 credential can take here.
///
/// It arrived with contract provisioning: a -20 OEM provisioning key must be P-521 to take part in
/// the secp521r1 key agreement at all, and until then every credential this stack signed with was a
/// contract key, which is P-256 because -2's signature field is 64 bytes wide.
public struct InMemoryP521Signer: V2GSigner {

    private let key: P521.Signing.PrivateKey

    public let protection: V2GKeyProtection
    public var curve: V2GKeyCurve { .p521 }
    public var publicKeyDer: [UInt8] { Array(key.publicKey.derRepresentation) }

    public init(_ key: P521.Signing.PrivateKey,
                protection: V2GKeyProtection = .softwareInMemory) throws {
        if protection.isHardwareBacked {
            throw V2GKeyError.softwareSignerCannotClaimHardware
        }
        self.key = key
        self.protection = protection
    }

    /// The digest named, explicitly — see ``V2GSigner/signature(over:hash:)``. Handing the octets to
    /// CryptoKit would hash them with SHA-512 whatever the declaration says.
    public func signature(over octets: [UInt8], hash: V2GSignatureHash) throws -> [UInt8] {
        switch hash {
        case .sha256: return Array(try key.signature(for: SHA256.hash(data: Data(octets))).rawRepresentation)
        case .sha512: return Array(try key.signature(for: SHA512.hash(data: Data(octets))).rawRepresentation)
        }
    }
}
