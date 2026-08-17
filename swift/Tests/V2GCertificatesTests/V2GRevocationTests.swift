import Foundation
import XCTest
@testable import V2GCertificates

/// Revocation checking, against a CRL the C# PKI issued — the same corpus the Kotlin half is held to.
///
/// The material carries a real CRL revoking a real leaf, an expired CRL, and a CRL from an unrelated
/// CA. The last two are the point: both must come back **unknown**, never "not revoked". A check that
/// answers a boolean cannot tell them apart, and whoever wants a revoked credential accepted only has
/// to arrange for the list to be unavailable.
final class V2GRevocationTests: XCTestCase {

    private struct Material: Decodable {
        let issuer: String
        let revokedLeaf: String
        let unrevokedLeaf: String
        let crl: String
        let expiredCrl: String
        let crlFromStranger: String
    }

    private struct Corpus: Decodable { let revocation: Material }

    private func material() throws -> Material {
        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }
        let file = dir.appendingPathComponent(
            "vectors/Certificate.chain.vectors.json")
        return try JSONDecoder().decode(Corpus.self, from: try Data(contentsOf: file)).revocation
    }

    private func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map {
            let i = s.index(s.startIndex, offsetBy: $0)
            return UInt8(s[i ..< s.index(i, offsetBy: 2)], radix: 16)!
        }
    }

    func testARevokedCertificateIsReportedAsRevoked() throws {

        let m = try material()
        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.revokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: hex(m.crl))

        guard case .revoked(_, let reason) = status else {
            return XCTFail("expected .revoked, got \(status)")
        }
        XCTAssertEqual(reason, "keyCompromise",
                       "the reason is shown to a user and must read the same in every back end")
    }

    /// The positive case has to exist, or "everything is revoked" would pass the test above.
    func testACertificateTheListDoesNotNameIsNotRevoked() throws {

        let m = try material()

        // The same genuine CRL, asked about a certificate it does not list. Its issuer differs, so
        // this lands on the issuer check — which is itself the right answer, and distinct from
        // "not revoked". Asserted as "not a false clean bill" rather than as a specific case.
        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.unrevokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: hex(m.crl))

        if case .revoked = status { XCTFail("a certificate the list does not name is not revoked") }
    }

    /// **An expired CRL is not an empty CRL.** A stale list is a snapshot of the past, and treating
    /// it as authoritative is how a revocation gets outrun by waiting.
    func testAnExpiredCrlIsUnknownNotNotRevoked() throws {

        let m = try material()
        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.revokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: hex(m.expiredCrl))

        guard case .unknown(let why) = status else { return XCTFail("expected .unknown, got \(status)") }
        XCTAssertTrue(why.contains("expired"), why)
    }

    /// **A valid CRL from the wrong CA is not an empty CRL either.** It verifies, it is current, and
    /// it says nothing about this certificate. Reading "not listed" off it would be a bypass: supply
    /// any unrelated CA's list and everything looks clean.
    func testACrlFromAnotherCaIsUnknownNotNotRevoked() throws {

        let m = try material()
        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.revokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: hex(m.crlFromStranger))

        guard case .unknown = status else { return XCTFail("expected .unknown, got \(status)") }
    }

    /// A CRL whose signature does not verify must not be believed — not even when it is empty, which
    /// is the cheapest forgery there is.
    func testATamperedCrlIsUnknown() throws {

        let m = try material()
        var tampered = hex(m.crl)
        tampered[tampered.count - 1] ^= 0x01

        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.revokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: tampered)

        guard case .unknown = status else { return XCTFail("expected .unknown, got \(status)") }
    }

    func testRubbishIsUnknownRatherThanACrash() throws {

        let m = try material()
        let status = V2GRevocationChecker.check(try V2GCertificate(der: hex(m.revokedLeaf)),
                                                issuedBy: try V2GCertificate(der: hex(m.issuer)),
                                                against: [1, 2, 3])

        guard case .unknown = status else { return XCTFail("expected .unknown, got \(status)") }
    }

    /// The parser reads what it claims to: issuer, window, and the entry it was asked about.
    func testTheCrlParsesIntoTheFieldsTheCheckUses() throws {

        let m = try material()
        let crl = try V2GCrl(der: hex(m.crl))

        XCTAssertEqual(crl.entries.count, 1)
        XCTAssertNotNil(crl.nextUpdate)
        XCTAssertLessThan(crl.thisUpdate, crl.nextUpdate!)
        XCTAssertEqual(crl.entries[0].reason, "keyCompromise")
        XCTAssertFalse(crl.tbsBytes.isEmpty)
    }
}
