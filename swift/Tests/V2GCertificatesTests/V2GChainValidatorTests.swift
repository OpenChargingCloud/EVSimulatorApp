import Foundation
import XCTest
@testable import V2GCertificates

/// The chain validator, against the verdicts `WWCP_ISO15118_PKI` says are right.
///
/// The corpus carries whole chains and, per case, the two answers kept apart everywhere in this
/// design: `trusted` — path, signatures, dates, CA flags — and `findings`, the ISO 15118 profile
/// deviations that leave a sound chain sound and still worth talking about.
///
/// The negatives come from that library's evil-certificate factory, which exists to defeat exactly
/// the validator this could have been: one that does PKIX and stops. `contract_with_serverauth`
/// builds a perfect path.
final class V2GChainValidatorTests: XCTestCase {

    private struct Case: Decodable {
        let name: String
        let what: String
        let chain: [String]
        let trusted: Bool
        let findings: [String]
    }

    private struct Corpus: Decodable {
        let root: String
        let cases: [Case]
    }

    private func corpus() throws -> Corpus {
        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { break }
        }
        let file = dir.appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Certificate.chain.vectors.json")
        return try JSONDecoder().decode(Corpus.self, from: try Data(contentsOf: file))
    }

    private func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i ..< s.index(i, offsetBy: 2)], radix: 16)!
        }
    }

    /// The corpus certificates are long-lived but not eternal; validating "now" would make this
    /// suite fail on a calendar rather than on a defect. The hierarchy is built around this instant.
    private let validationTime = Date()


    func testEveryCorpusCaseReachesTheVerdictTheCSharpPkiSays() async throws {

        let corpus = try corpus()
        let store  = InMemoryTrustStore(roots: [try V2GCertificate(der: hex(corpus.root))])
        let validator = V2GChainValidator()

        for testCase in corpus.cases {

            let chain = try V2GCertificateChain(der: testCase.chain.map(hex))
            let verdict = await validator.validate(chain, against: store, now: validationTime)

            XCTAssertEqual(verdict.isTrusted, testCase.trusted,
                           "\(testCase.name): \(testCase.what)\n  rejection: " +
                           String(describing: verdict.rejection))

            XCTAssertEqual(Set(verdict.findings.map(\.rawValue)), Set(testCase.findings),
                           "\(testCase.name): profile findings differ")
        }
    }

    /// The case the whole two-tier design exists for, asserted on its own so a failure names it.
    ///
    /// A contract certificate carrying `serverAuth` builds a perfect path — PKIX has no opinion about
    /// an extra purpose. If findings were folded into the trust decision this would be rejected, and
    /// a user would be told "invalid" about a chain that is entirely valid and merely wrong.
    func testAContractCertificateWithServerAuthIsTrustedAndReported() async throws {

        let corpus = try corpus()
        let store  = InMemoryTrustStore(roots: [try V2GCertificate(der: hex(corpus.root))])

        let testCase = try XCTUnwrap(corpus.cases.first { $0.name == "contract_with_serverauth" })
        let chain = try V2GCertificateChain(der: testCase.chain.map(hex))

        let verdict = await V2GChainValidator().validate(chain, against: store, now: validationTime)

        XCTAssertTrue(verdict.isTrusted, "the chain is sound; that is precisely why the finding matters")
        XCTAssertEqual(verdict.findings, [.serverAuthOnContractCertificate])
    }

    /// An empty store is not "everything is untrusted" by accident — it is a distinct answer, because
    /// an app that has installed no roots yet should say so rather than blaming the certificate.
    func testAnEmptyStoreSaysSoRatherThanBlamingTheChain() async throws {

        let corpus = try corpus()
        let good   = try XCTUnwrap(corpus.cases.first { $0.name == "good" })
        let chain  = try V2GCertificateChain(der: good.chain.map(hex))

        let verdict = await V2GChainValidator().validate(chain, against: InMemoryTrustStore(),
                                                         now: validationTime)

        XCTAssertEqual(verdict.rejection, .noTrustAnchors)
    }

    /// Findings survive a rejection. A bundle that is both untrusted *and* the wrong sort of
    /// certificate should say both, or the second scan is as puzzling as the first.
    func testFindingsAreReportedEvenWhenTheChainIsRejected() async throws {

        let corpus = try corpus()
        let testCase = try XCTUnwrap(corpus.cases.first { $0.name == "contract_with_serverauth" })
        let chain = try V2GCertificateChain(der: testCase.chain.map(hex))

        // Same chain, no anchors: rejected, and still recognisably the wrong certificate.
        let verdict = await V2GChainValidator().validate(chain, against: InMemoryTrustStore(),
                                                         now: validationTime)

        XCTAssertFalse(verdict.isTrusted)
        XCTAssertEqual(verdict.findings, [.serverAuthOnContractCertificate])
    }

    /// A shuffled bundle is refused, and the reason is the interesting part.
    ///
    /// Not "no path could be built" — one can. The order is what says *which* certificate is the
    /// contract certificate, so a bundle that does not link up has not said. An earlier draft here
    /// reordered and validated whatever came first, which meant it cheerfully trusted a sub-CA as a
    /// contract credential. The corpus caught it.
    func testAShuffledBundleIsRefusedBecauseItDoesNotSayWhichCertificateIsTheLeaf() async throws {

        let corpus = try corpus()
        let store  = InMemoryTrustStore(roots: [try V2GCertificate(der: hex(corpus.root))])

        let shuffled = try XCTUnwrap(corpus.cases.first { $0.name == "chain_out_of_order" })
        let chain = try V2GCertificateChain(der: shuffled.chain.map(hex))

        let verdict = await V2GChainValidator().validate(chain, against: store, now: validationTime)

        XCTAssertEqual(verdict.rejection, .bundleDoesNotLinkUp)
        XCTAssertFalse(chain.linksUp)
        XCTAssertTrue(verdict.findings.isEmpty,
                      "no finding about a guessed leaf is worth reporting")
    }
}
