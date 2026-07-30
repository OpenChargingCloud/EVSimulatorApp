import XCTest
@testable import V2GTP

/// The 8-byte header. These tests pin the bytes literally, to the same array the C# `V2GTPFrameTests`
/// pins — a round trip cannot see a byte order flipped on both the write and the read side.
final class V2GTPTests: XCTestCase {

    func testHeaderBytesAreThePinnedOnes() {
        let header = V2GTP.header(payloadType: V2GTP.payloadTypeAppProtocol, payloadLength: 42)
        XCTAssertEqual(header, [0x01, 0xFE, 0x80, 0x01, 0x00, 0x00, 0x00, 0x2A])
    }

    func testHeaderRoundTrips() {
        let header = V2GTP.header(payloadType: V2GTP.payloadTypeIso20DC, payloadLength: 1234)
        let read = V2GTP.readHeader(header)

        XCTAssertEqual(read?.payloadType, V2GTP.payloadTypeIso20DC)
        XCTAssertEqual(read?.payloadLength, 1234)
    }

    /// Both multi-byte fields are big-endian. Reading them the other way round would survive a round
    /// trip and fail against every peer, so the low and high bytes are checked in place.
    func testMultiByteFieldsAreBigEndian() {
        let header = V2GTP.header(payloadType: 0x1234, payloadLength: 0x05060708)

        XCTAssertEqual(Array(header[2...3]), [0x12, 0x34])
        XCTAssertEqual(Array(header[4...7]), [0x05, 0x06, 0x07, 0x08])
    }

    func testRejectsAWrongVersion() {
        var header = V2GTP.header(payloadType: V2GTP.payloadTypeIso20Main, payloadLength: 0)
        header[0] = 0x02
        XCTAssertNil(V2GTP.readHeader(header))
    }

    func testRejectsABufferTooShortForAHeader() {
        XCTAssertNil(V2GTP.readHeader([UInt8](repeating: 0, count: 7)))
    }

    /// SAP and the DIN/-2 messages share payload id 0x8001; they are told apart by session phase.
    /// Pinned because an earlier distinct 0x8000 here was a real wire-conformance bug, caught only
    /// by a live interop run.
    func testSapAndIso2ShareOnePayloadId() {
        XCTAssertEqual(V2GTP.payloadTypeAppProtocol, 0x8001)
        XCTAssertEqual(V2GTP.payloadTypeDinIso2Main, V2GTP.payloadTypeAppProtocol)
    }
}
