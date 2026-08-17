import CryptoKit
import Foundation
import XCTest

@testable import V2GEvcc

import ExiIso20Common

/// The -20 price-schedule check, against the corpus C# generates and is itself held to.
///
/// Same reason as its -2 sibling: the verdict never reaches the wire, so no recorded session can pin
/// it. Every case here is a whole `ScheduleExchangeRes` frame, decoded exactly as a session would
/// decode it — the only thing this test does that a session does not is *look* at the answer.
///
/// Two of the seven exist because a corpus can hold what a recording cannot express. `signed-dynamic`
/// is the same schedule as `signed-scheduled` in the other control mode, and a verifier that searches
/// only the schedule tuples fails it while looking perfectly healthy on every other case.
/// `no-price-schedule` expects *no verdict at all* rather than a failing one — most stations never
/// sign, and a screen that cannot tell "nothing to check" from "checked and unhappy" accuses them.
final class Iso20PriceScheduleCheckTests: XCTestCase {

    private struct Case {
        let name: String
        let frame: [UInt8]
        let verifyKey: P521.Signing.PublicKey?
        let expected: Iso20TariffResult?
    }

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map { i in
            let start = s.index(s.startIndex, offsetBy: i)
            return UInt8(s[start...s.index(start, offsetBy: 1)], radix: 16)!
        }
    }

    private static func publicKey(_ any: Any?) throws -> P521.Signing.PublicKey? {
        guard let dict = any as? [String: Any],
              let x = dict["x"] as? String, let y = dict["y"] as? String
        else { return nil }
        // An uncompressed point: 0x04 ‖ X ‖ Y. P-521 coordinates are 66 bytes each.
        return try P521.Signing.PublicKey(x963Representation: Data([0x04] + hex(x) + hex(y)))
    }

    private static func corpus() throws -> [Case] {

        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }

        let url = dir.appendingPathComponent("vectors/PriceSchedule.signature.vectors.json")
        guard let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let raw = json["cases"] as? [[String: Any]]
        else { throw XCTSkip("price-schedule corpus not found at \(url.path)") }

        return try raw.map { c in
            let expected = c["expected"] as? [String: Any]
            return Case(name:      c["name"] as! String,
                        frame:     hex(c["frame"] as! String),
                        verifyKey: try publicKey(c["verifyKey"]),
                        expected:  expected.map {
                            Iso20TariffResult(signaturePresent: $0["signaturePresent"] as! Bool,
                                              digestOk:         $0["digestOk"] as! Bool,
                                              signatureOk:      $0["signatureOk"] as! Bool)
                        })
        }
    }

    func testEveryCorpusCaseReachesTheVerdictCSharpReached() throws {

        for c in try Self.corpus() {

            let decoded = try CommonMessagesCodec.decodeAny(c.frame)
            guard let res = decoded as? ScheduleExchangeRes
            else { return XCTFail("\(c.name): not a ScheduleExchangeRes") }

            let verdict = Iso20PriceScheduleCheck.evaluate(res,
                                                           headerSignature: res.header.signature,
                                                           verifyKey: c.verifyKey)

            XCTAssertEqual(verdict, c.expected, "\(c.name)")
        }
    }

    /// The offer with nothing to verify produces no verdict — not a failing one.
    func testAnOfferWithoutAPriceScheduleProducesNoVerdict() throws {

        guard let c = try Self.corpus().first(where: { $0.name == "no-price-schedule" })
        else { return XCTFail("the no-price-schedule case is gone") }

        let res = try CommonMessagesCodec.decodeAny(c.frame) as! ScheduleExchangeRes
        XCTAssertNil(Iso20PriceScheduleCheck.schedule(in: res))
        XCTAssertNil(Iso20PriceScheduleCheck.evaluate(res, headerSignature: res.header.signature,
                                                      verifyKey: nil))
    }

    /// Dynamic mode hides the schedule somewhere else entirely, and a verifier that only knows the
    /// Scheduled tuples reports this signed offer as unsigned.
    func testTheDynamicControlModeScheduleIsFoundToo() throws {

        guard let c = try Self.corpus().first(where: { $0.name == "signed-dynamic" })
        else { return XCTFail("the signed-dynamic case is gone") }

        let res = try CommonMessagesCodec.decodeAny(c.frame) as! ScheduleExchangeRes
        XCTAssertNil(res.scheduled_SEResControlMode, "this case must have no schedule tuples at all")
        XCTAssertNotNil(Iso20PriceScheduleCheck.schedule(in: res))

        let verdict = try XCTUnwrap(Iso20PriceScheduleCheck.evaluate(
            res, headerSignature: res.header.signature, verifyKey: c.verifyKey))
        XCTAssertTrue(verdict.digestOk)
        XCTAssertTrue(verdict.signatureOk)
    }

    func testTheCorpusStillCarriesItsNegatives() throws {
        let names = Set(try Self.corpus().map(\.name))
        for required in ["signed-scheduled", "signed-dynamic", "unsigned", "digest-tampered",
                         "wrong-key", "no-verify-key", "no-price-schedule"] {
            XCTAssertTrue(names.contains(required), "the \(required) case is gone")
        }
    }
}
