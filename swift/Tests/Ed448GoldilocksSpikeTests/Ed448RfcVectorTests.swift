import XCTest

import Goldilocks
@testable import ExiIso20Common

/// Does `swift-goldilocks` reproduce RFC 8032 §7.4 byte for byte?
///
/// The acceptance test for the Ed448 dependency, held deliberately apart from the tests of our own
/// signing layer: this one asks whether *the library* is correct, which is a different question
/// from whether we call it correctly. CryptoKit lacks the curve entirely — the primitive is absent,
/// not merely an unregistered provider as on the JVM — so -20's second signature suite needs a
/// bundled one (`docs/CONCEPT.md` §3.3, §8 #10).
///
/// It ran as a spike and was merged on its results; `swift/SPIKE-ed448.md` holds the measurements
/// and the arguments against. Keeping it running on master is the point — a library evaluation that
/// lives in a branch is one nobody re-runs when the dependency moves.
///
/// This target is also the only one importing both `Goldilocks` and `ExiIso20Common`, which makes
/// it the one place that can check what our signing path actually hands the primitive — see
/// ``testOurSigningPathIsTheVectorCheckedPrimitiveOverTheFragment``.
///
/// The corpus is the same file the C# and Kotlin suites read, out of the submodule rather than
/// copied. It is the strongest oracle in the repository: every other vector file is some
/// implementation's output, while these are the standard's own published numbers, and Ed448 is
/// deterministic so signing is an equality check rather than another round trip.
///
/// Passing this does not make the library safe to depend on — it says nothing about side channels,
/// maintenance or the wrapper's memory handling. It is the necessary half that can be measured.
final class Ed448GoldilocksRfcVectorTests: XCTestCase {

    struct Vector {
        let label: String
        let secretKey: [UInt8]
        let publicKey: [UInt8]
        let message: [UInt8]
        let context: [UInt8]
        let signature: [UInt8]
    }

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i...s.index(i, offsetBy: 1)], radix: 16)!
        }
    }

    private static func repositoryRoot() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { return dir }
        }
        throw XCTSkip("repository root not found from \(#filePath)")
    }

    static func vectors() throws -> [Vector] {
        let url = try repositoryRoot().appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Tests/Vectors/Ed448.rfc8032.vectors.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let raw = json?["vectors"] as? [[String: Any]] else {
            throw XCTSkip("vector file has no 'vectors' array: \(url.path)")
        }
        return raw.map {
            Vector(label:     $0["label"] as! String,
                   secretKey: hex($0["secretKey"] as! String),
                   publicKey: hex($0["publicKey"] as! String),
                   message:   hex($0["message"] as! String),
                   context:   hex($0["context"] as! String),
                   signature: hex($0["signature"] as! String))
        }
    }

    /// The corpus loaded, and it is the whole of §7.4 rather than the easy cases.
    func testTheCorpusIsComplete() throws {
        let all = try Self.vectors()

        XCTAssertEqual(all.count, 9)
        XCTAssertEqual(all.filter { !$0.context.isEmpty }.count, 1)
        XCTAssertEqual(all.map(\.message.count).max(), 1023)
    }

    func testEveryPublicKeyDerivesFromItsSecretKey() throws {
        for v in try Self.vectors() where v.context.isEmpty {
            XCTAssertEqual(try Goldilocks.Ed448.derivePublicKey(privateKey: v.secretKey),
                           v.publicKey, v.label)
        }
    }

    /// The measurement this spike exists for.
    ///
    /// Only the empty-context vectors: the API has no context parameter, which is correct for
    /// ISO 15118-20 and means the `"foo"` vector is out of scope rather than a failure — see
    /// ``testTheContextVectorIsOutOfScopeRatherThanFailing``.
    func testSigningReproducesTheRfcSignatures() throws {
        for v in try Self.vectors() where v.context.isEmpty {
            let signature = try Goldilocks.Ed448.sign(message: v.message,
                                                      privateKey: v.secretKey,
                                                      publicKey: v.publicKey)
            XCTAssertEqual(signature, v.signature, v.label)
        }
    }

    func testVerifyingAcceptsTheRfcSignatures() throws {
        for v in try Self.vectors() where v.context.isEmpty {
            XCTAssertTrue(try Goldilocks.Ed448.verify(signature: v.signature,
                                                      message: v.message,
                                                      publicKey: v.publicKey), v.label)
        }
    }

    /// The zero-length message, called out because it is where a C binding usually breaks.
    ///
    /// Swift hands `withUnsafeBytes` a **nil** `baseAddress` for an empty collection, and the
    /// wrapper passes that straight to `ce_ed448_sign`. Whether the C side tolerates a null pointer
    /// with length zero is not something the wrapper documents, and `SessionSetupReq`-shaped empty
    /// payloads are not exotic. RFC 8032's first vector settles it.
    func testTheEmptyMessageVectorIsHandled() throws {
        let blank = try XCTUnwrap(try Self.vectors().first { $0.message.isEmpty })

        XCTAssertEqual(try Goldilocks.Ed448.sign(message: blank.message,
                                                 privateKey: blank.secretKey,
                                                 publicKey: blank.publicKey),
                       blank.signature)
    }

    /// A tampered signature must be refused, or the equality checks above prove nothing about
    /// `verify`.
    func testATamperedSignatureIsRefused() throws {
        let v = try XCTUnwrap(try Self.vectors().first)
        var bad = v.signature
        bad[0] ^= 0xFF

        XCTAssertFalse(try Goldilocks.Ed448.verify(signature: bad, message: v.message,
                                                   publicKey: v.publicKey))
    }

    /// The `"foo"`-context vector is unreachable through this API, and that is the right outcome.
    ///
    /// ISO 15118-20 uses `#eddsa-ed448` — pure Ed448, empty context — so an API without a context
    /// parameter expresses exactly what is needed and nothing more. What the test pins is that the
    /// library does not *silently* treat it as an empty-context signature: verifying the `"foo"`
    /// signature must fail, because that is the empty-context answer to a different question.
    func testTheContextVectorIsOutOfScopeRatherThanFailing() throws {
        let withFoo = try XCTUnwrap(try Self.vectors().first { !$0.context.isEmpty })

        XCTAssertFalse(try Goldilocks.Ed448.verify(signature: withFoo.signature,
                                                   message: withFoo.message,
                                                   publicKey: withFoo.publicKey),
                       "a context signature verified without one — the API would be hiding a "
                     + "parameter rather than fixing it")
    }

    /// Wrong lengths throw rather than reading past the end of a buffer.
    func testMalformedInputsAreRejected() throws {
        let v = try XCTUnwrap(try Self.vectors().first)

        XCTAssertThrowsError(try Goldilocks.Ed448.derivePublicKey(privateKey: [UInt8](repeating: 0, count: 56)))
        XCTAssertThrowsError(try Goldilocks.Ed448.verify(signature: Array(v.signature.dropLast()),
                                                         message: v.message,
                                                         publicKey: v.publicKey))
    }

    // ── What it would look like in place ────────────────────────────────────────────────────────

    /// `V2GSignature.sign` is the vector-checked primitive over the fragment, and nothing else.
    ///
    /// This is the join between the two halves, and the only place that can make it: the vectors
    /// above validate Goldilocks, `Iso20CommonV2GSignatureTests` validates our dispatch and wire
    /// formats, and neither can see whether the bytes actually handed to the primitive are the
    /// `SignedInfo` fragment. Here both dependencies are in scope, so the check is an equality
    /// against a hand-rolled call rather than another round trip.
    ///
    /// Pure EdDSA signs the fragment octets **directly** — SHAKE256 inside Ed448 replaces the
    /// external pre-hash, unlike the P-521 path where SHA-512 is a separate step. If
    /// `V2GSignature` ever grows one, or a context, or a different notion of which octets are
    /// signed, this fails while every test either side of it keeps passing.
    func testOurSigningPathIsTheVectorCheckedPrimitiveOverTheFragment() throws {
        let v   = try XCTUnwrap(try Self.vectors().first)
        let key = try Ed448PrivateKey(rawRepresentation: v.secretKey)

        let signedInfo = SignedInfoType(
            canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
            signatureMethod: .init(algorithm: V2GSignature.Algorithm.eddsaEd448.rawValue),
            reference: [.init(uRI: "#id1",
                              transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                              digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                              digestValue: [UInt8](repeating: 0xAB, count: 64))])

        let ours     = try V2GSignature.sign(signedInfo, with: key)
        let fragment = CommonMessagesCodec.encodeFragment_SignedInfo(signedInfo)
        let expected = try Goldilocks.Ed448.sign(message: fragment,
                                                 privateKey: v.secretKey,
                                                 publicKey: v.publicKey)

        XCTAssertEqual(ours, expected)
        XCTAssertEqual(ours.count, 114)

        // The key wrapper derives the same public key the RFC pairs with this seed, so signing
        // through it cannot silently use a different one.
        XCTAssertEqual(key.publicKey.rawRepresentation, v.publicKey)

        // And the fragment really is this set's — 230 over 9 bits after the header, as
        // ExiIso20CommonTests pins independently.
        XCTAssertEqual(fragment[0], 0x80)
        XCTAssertEqual(fragment[1], 0b01110011)
    }
}
