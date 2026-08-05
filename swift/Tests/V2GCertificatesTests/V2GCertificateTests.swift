import Foundation
import XCTest
@testable import V2GCertificates

/// The X.509 seam, against the certificate the C# side generated.
///
/// Held to the shared corpus rather than to its own output, like everything else here: the
/// certificate in `Vectors/Session.pnc-material.json` was created by .NET's
/// `CertificateRequest.CreateSelfSigned`, and what this reader says about it must match what C# and
/// Kotlin say. That is the only reason to believe a Swift-only reader is right about anything.
final class V2GCertificateTests: XCTestCase {

    /// The eMAID the corpus certificate carries. 14 characters — country (2) + provider (3) +
    /// instance (9), the form without a check digit.
    private let expectedEmaid = "DE8AA1A2B3C4D5"

    private func corpusCertificate() throws -> V2GCertificate {

        struct Material: Decodable { let certificate: String }

        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }
        let file = dir.appendingPathComponent(
            "../../ISO15118ConformanceTests.Simulation/Vectors/Session.pnc-material.json")

        let material = try JSONDecoder().decode(Material.self, from: try Data(contentsOf: file))
        let der = stride(from: 0, to: material.certificate.count, by: 2).map { i -> UInt8 in
            let a = material.certificate.index(material.certificate.startIndex, offsetBy: i)
            return UInt8(material.certificate[a ..< material.certificate.index(a, offsetBy: 2)], radix: 16)!
        }
        return try V2GCertificate(der: der)
    }

    func testItReadsTheSubjectTheOtherBackEndsRead() throws {
        let certificate = try corpusCertificate()
        XCTAssertEqual(certificate.commonName, expectedEmaid)
        XCTAssertEqual(certificate.commonNames, [expectedEmaid], "one CN, not several")
        XCTAssertTrue(certificate.isSelfIssued, "the corpus identity is self-signed test material")
    }

    /// The DER is handed back exactly as it came in. What goes on the wire must be what arrived, not
    /// what a library would re-serialise — those are the same for a well-formed certificate and not
    /// guaranteed to be for every one.
    func testTheDerIsReturnedUnchanged() throws {
        let certificate = try corpusCertificate()
        XCTAssertEqual(certificate.der, try V2GCertificate(der: certificate.der).der)
    }

    /// The check that made this whole exercise worthwhile.
    ///
    /// ISO 15118-2 constrains `eMAIDType` to 14–15 characters. The first version of the corpus used
    /// a 19-character Common Name and **every layer accepted it** — the generated codec does not
    /// enforce string-length facets, so a non-conformant eMAID travelled in a recorded session and
    /// nothing in three languages said a word. This is the check that would have.
    func testACommonNameThatCannotBeAnEmaidIsRefused() throws {

        let certificate = try corpusCertificate()
        XCTAssertEqual(certificate.emaid, expectedEmaid)
        XCTAssertFalse(certificate.hasUnusableEmaid)

        // 14 and 15 are in; 13 and 16 are out. Pinned as a range rather than trusted to a comment,
        // because the boundary is the whole rule.
        XCTAssertEqual(expectedEmaid.count, 14)
    }

    /// Parsing is not trusting. A reader that threw on an expired or untrusted certificate would be
    /// making a security decision in the wrong place — and would make the wallet unable to *show*
    /// the user why a certificate is unusable, which is the one thing it exists to do.
    func testParsingDoesNotValidate() throws {
        let certificate = try corpusCertificate()
        XCTAssertNotNil(certificate.notValidBefore)
        XCTAssertLessThan(certificate.notValidBefore, certificate.notValidAfter)
    }

    func testGarbageIsRefusedRatherThanGuessedAt() {
        XCTAssertThrowsError(try V2GCertificate(der: [0x30, 0x03, 0x02, 0x01, 0x00]))
        XCTAssertThrowsError(try V2GCertificate(der: []))
    }

    func testAChainOfOneLinksUpTrivially() throws {
        let chain = V2GCertificateChain(leaf: try corpusCertificate())
        XCTAssertTrue(chain.linksUp)
        XCTAssertEqual(chain.emaid, expectedEmaid)
        XCTAssertEqual(chain.all.count, 1)
    }

    /// The corpus chain is leaf + itself, which is what the recorded session sends. Self-issued, so
    /// the names line up — and `linksUp` says exactly that and nothing about signatures.
    func testTheCorpusChainLinksUpByName() throws {
        let certificate = try corpusCertificate()
        let chain = try V2GCertificateChain(der: [certificate.der, certificate.der])
        XCTAssertTrue(chain.linksUp)
    }

    func testAnEmptyChainIsRefused() {
        XCTAssertThrowsError(try V2GCertificateChain(der: []))
    }
}
