import CryptoKit
import Foundation

import ExiIso2
import ExiIso20Common
import V2GDispatch
import V2GTP
import V2GKeystore
@testable import V2GEvcc

/// The Swift half of the signature-aware comparison, mirroring `SignedFrame.cs`.
///
/// A signed frame cannot be compared byte for byte: ECDSA's nonce is random, so the same message
/// signed twice differs. But **only the signature value is random** — the body is deterministic, and
/// so is `SignedInfo`, which holds a digest of the signed element. So the recorded signature is
/// substituted into the frame a port produced, the result re-encoded and compared exactly as before,
/// and the produced signature verified separately against the corpus key. Everything but those 64
/// bytes is still checked exactly, including `SignedInfo`, therefore the digest, therefore which
/// octets were signed.
///
/// See the C# original for the full argument. This is the same mechanism in the same order, and the
/// two must agree — a port that passed here and failed there would mean the mechanism itself differs
/// between back ends, which is worse than either failing.
enum SignedFrame {

    /// Re-encodes `frame` with `signatureValue` in place of whatever signature it carried.
    static func withSignatureValue(_ frame: [UInt8], _ signatureValue: [UInt8]) throws -> [UInt8] {

        let (set, message) = try decode(frame)

        switch message {

        case let m as ExiIso2.V2G_Message:
            guard let signature = m.header.signature else {
                throw TraceMismatch(description: "substituting a signature into an unsigned V2G_Message")
            }
            signature.signatureValue = ExiIso2.SignatureValueType(value: signatureValue)
            return V2GTPDispatcher.encode(set, Iso15118_2Codec.encode(m))

        case let r as ExiIso20Common.AuthorizationReq:
            guard let signature = r.header.signature else {
                throw TraceMismatch(description: "substituting a signature into an unsigned AuthorizationReq")
            }
            signature.signatureValue = ExiIso20Common.SignatureValueType(value: signatureValue)
            return V2GTPDispatcher.encode(set, CommonMessagesCodec.encode(r))

        default:
            // Deliberately closed, and loud. A trace whose signed message this does not model must
            // fail rather than have the comparison silently skipped for exactly the message it was
            // built to compare.
            throw TraceMismatch(description:
                "the trace corpus does not model a signature on \(type(of: message)). " +
                "Add it here rather than letting the comparison skip it.")
        }
    }

    /// Verifies a frame's own signature against its own `SignedInfo` — the half the substitution
    /// throws away. Without it a port could emit any 64 bytes it liked and still compare equal.
    static func verifies(_ frame: [UInt8], with publicKey: P256.Signing.PublicKey) -> Bool {

        guard let (_, message) = try? decode(frame) else { return false }

        switch message {
        case let m as ExiIso2.V2G_Message:
            guard let s = m.header.signature else { return false }
            return XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(s.signedInfo),
                                         s.signatureValue.value, publicKey)

        case let r as ExiIso20Common.AuthorizationReq:
            guard let s = r.header.signature else { return false }
            return XmlDsigInterop.verify(XmlDsigInterop.standaloneOctets(s.signedInfo),
                                         s.signatureValue.value, publicKey)

        default:
            return false
        }
    }

    /// A P-256 public key from the two field elements the trace records. CryptoKit takes them
    /// concatenated, uncompressed and without the 0x04 prefix.
    static func publicKey(x: String, y: String) throws -> P256.Signing.PublicKey {
        try P256.Signing.PublicKey(rawRepresentation: Data(hex(x) + hex(y)))
    }

    private static func decode(_ frame: [UInt8]) throws -> (MessageSet, Any) {
        switch try V2GTPDispatcher.decode(frame) {
        case .decoded(let set, let message): return (set, message)
        case .failed(let error):             throw TraceMismatch(description: "trace: \(error)")
        }
    }

    static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i ..< s.index(i, offsetBy: 2)], radix: 16)!
        }
    }
}


/// The fixed Plug & Charge identity a recorded session signs with, read out of the submodule — the
/// same file the C# and Kotlin suites read.
///
/// Shared rather than duplicated for the reason the corpus exists at all: three copies of a constant
/// drift, and a drifted contract key would change the message body, not just the signature, so the
/// failure would look like a state-machine bug rather than a stale constant.
enum PncMaterial {

    /// Decoded rather than read as `[String: Any]`: a non-Sendable dictionary cannot be a static
    /// property under strict concurrency, and a typed struct is better anyway — a renamed field
    /// fails at the decode instead of at a force-cast three call sites later.
    private struct Material: Decodable {
        let privateKeyD: String
        let certificate: String
        let certificateWithUnusableEmaid: String
    }

    private static var material: Material {
        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }
        let file = dir.appendingPathComponent(
            "../../ISO15118ConformanceTests.Simulation/Vectors/Session.pnc-material.json")
        return try! JSONDecoder().decode(Material.self, from: try! Data(contentsOf: file))
    }

    static var certificate: [UInt8] { SignedFrame.hex(material.certificate) }

    /// A certificate whose Common Name is 19 characters and therefore cannot be an eMAID — the
    /// negative case every back end's length check is held to.
    static var certificateWithUnusableEmaid: [UInt8] {
        SignedFrame.hex(material.certificateWithUnusableEmaid)
    }

    static var key: P256.Signing.PrivateKey {
        try! P256.Signing.PrivateKey(rawRepresentation: Data(SignedFrame.hex(material.privateKeyD)))
    }

    /// The corpus key as a signer. Software and in memory, and it says so — which is the whole
    /// arrangement §3.4 asks for, working the same way for test material as for a real credential.
    static var signer: InMemoryP256Signer { try! InMemoryP256Signer(key) }

    /// The eMAID the certificate carries. Not passed in any more — `PncEvccOptions` reads it from
    /// the certificate, as C# and Kotlin do. Kept here only so a test can say what it expects.
    static let expectedEmaid = "DE8AA1A2B3C4D5"

    static var options: PncEvccOptions {
        get throws {
            try PncEvccOptions(contractCertificate: certificate, subCertificates: [certificate],
                               contractKey: signer)
        }
    }
}
