import CryptoKit
import Foundation
import XCTest

@testable import V2GEvcc

import ExiIso2

/// §7.9.2.5, against the corpus C# generates and is itself held to.
///
/// The corpus exists because the verdict never reaches the wire: the EV checks a signed offer and tells
/// the station nothing about the result, so no recorded session trace can pin it. Every case here is a
/// whole `ChargeParameterDiscoveryRes` frame, decoded exactly as a session would decode it — the only
/// thing this test does that a session does not is *look* at the answer.
///
/// Three of the six cases cannot come from a recording at all. A station does not offer a tampered
/// digest, does not sign with a key the EV does not hold, and an EV in the field usually holds no tariff
/// key at all. Those are precisely the cases where a verifier that always answers "fine" still looks
/// perfectly healthy.
final class Iso2TariffCheckTests: XCTestCase {

    private struct Case {
        let name: String
        let frame: [UInt8]
        let verifyKey: P256.Signing.PublicKey?
        let signaturePresent: Bool
        let digestOk: Bool
        let signatureOk: Bool
        let signatureGrammar: String
    }

    // ── the corpus ────────────────────────────────────────────────────────

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map { i in
            let start = s.index(s.startIndex, offsetBy: i)
            return UInt8(s[start...s.index(start, offsetBy: 1)], radix: 16)!
        }
    }

    private static func publicKey(_ any: Any?) throws -> P256.Signing.PublicKey? {
        guard let dict = any as? [String: Any],
              let x = dict["x"] as? String, let y = dict["y"] as? String
        else { return nil }
        // An uncompressed point: 0x04 ‖ X ‖ Y.
        return try P256.Signing.PublicKey(x963Representation: Data([0x04] + hex(x) + hex(y)))
    }

    private static func corpus() throws -> [Case] {

        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }

        let url = dir.appendingPathComponent("vectors/Tariff.signature.vectors.json")
        guard let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let raw = json["cases"] as? [[String: Any]]
        else { throw XCTSkip("tariff corpus not found at \(url.path)") }

        return try raw.map { c in
            let expected = c["expected"] as! [String: Any]
            return Case(name:             c["name"] as! String,
                        frame:            hex(c["frame"] as! String),
                        verifyKey:        try publicKey(c["verifyKey"]),
                        signaturePresent: expected["signaturePresent"] as! Bool,
                        digestOk:         expected["digestOk"] as! Bool,
                        signatureOk:      expected["signatureOk"] as! Bool,
                        signatureGrammar: expected["signatureGrammar"] as! String)
        }
    }

    // ── the check ─────────────────────────────────────────────────────────

    func testEveryCorpusCaseReachesTheVerdictCSharpReached() throws {

        for c in try Self.corpus() {

            let decoded = try Iso15118_2Codec.decodeAny(c.frame)
            guard let message = decoded as? V2G_Message,
                  let body = message.body.bodyElement as? ChargeParameterDiscoveryResType
            else { return XCTFail("\(c.name): not a ChargeParameterDiscoveryRes") }

            let verdict = Iso2TariffCheck.evaluate(offer: body.sASchedules as? SAScheduleListType,
                                                   headerSignature: message.header.signature,
                                                   verifyKey: c.verifyKey)

            XCTAssertEqual(verdict.signaturePresent, c.signaturePresent, "\(c.name): signaturePresent")
            XCTAssertEqual(verdict.digestOk,         c.digestOk,         "\(c.name): digestOk")
            XCTAssertEqual(verdict.signatureOk,      c.signatureOk,      "\(c.name): signatureOk")
            XCTAssertEqual(verdict.signatureGrammar, c.signatureGrammar, "\(c.name): signatureGrammar")
        }
    }

    /// The corpus still carries the cases it was built for. A regeneration that quietly dropped the
    /// negatives would leave this suite green over a verifier that only ever answers "fine".
    func testTheCorpusStillCarriesItsNegatives() throws {
        let names = Set(try Self.corpus().map(\.name))
        for required in ["signed-msgdef", "signed-standalone", "unsigned",
                         "digest-tampered", "wrong-key", "no-verify-key"] {
            XCTAssertTrue(names.contains(required), "the \(required) case is gone")
        }
    }

    /// The one case that is a *conformance* statement rather than an interop one: ISO's own grammar.
    /// Split out so a regression there is not reported as "some case failed".
    func testTheIsoGrammarCaseVerifiesUnderIsoGrammar() throws {
        guard let c = try Self.corpus().first(where: { $0.name == "signed-msgdef" })
        else { return XCTFail("the signed-msgdef case is gone") }

        let message = try Iso15118_2Codec.decodeAny(c.frame) as! V2G_Message
        let body    = message.body.bodyElement as! ChargeParameterDiscoveryResType

        let verdict = Iso2TariffCheck.evaluate(offer: body.sASchedules as? SAScheduleListType,
                                               headerSignature: message.header.signature,
                                               verifyKey: c.verifyKey)

        XCTAssertEqual(verdict.signatureGrammar, "iso2-msgdef")
        XCTAssertTrue(verdict.digestOk)
        XCTAssertTrue(verdict.signatureOk)
    }
}
