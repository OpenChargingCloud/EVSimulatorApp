import XCTest
@testable import V2GEvcc

/// The handshake reads *which* protocol was accepted, not only that one was.
///
/// A station that says OK to a schema the EV never offered is latent today, because our handshake
/// offers exactly one entry — and precisely the thing nobody would re-check on the day a second
/// entry is added (found in the C# sweep of 2026-08-03; mirrors
/// `EvccReadsTheOfferTests.Sap_AnAcceptedSchemaWeNeverOfferedIsRefused`).
final class SapHandshakeTests: XCTestCase {

    func testAnAcceptedSchemaWeNeverOfferedIsRefused() throws {

        let station = ScriptedStation([ScriptedStation.sapOk(schemaID: 7)])

        XCTAssertThrowsError(try SapHandshake.runEvccSide(station.stream, .iso15118_2, .ac)) { error in
            XCTAssertTrue(String(describing: error).contains("SchemaID 7"), String(describing: error))
        }
    }
}
