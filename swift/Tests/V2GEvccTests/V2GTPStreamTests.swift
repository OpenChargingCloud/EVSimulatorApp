import XCTest

import V2GTP
@testable import V2GEvcc

/// What the framing layer will and will not believe.
///
/// The trace tests hold this class to whole recorded sessions, which is the only thing that proves it
/// reads real frames correctly. What they cannot cover is a peer that lies, because every recorded
/// peer is one of ours — and the peer here is whatever answered the socket.
final class V2GTPStreamTests: XCTestCase {

    /// Hands over a fixed sequence of bytes, a few at a time, and accepts anything written.
    private final class FixedStream: V2GByteStream {

        private let content: [UInt8]
        private var at = 0

        init(_ content: [UInt8]) { self.content = content }

        func write(_ bytes: [UInt8]) throws { }

        func read(maxLength: Int) throws -> [UInt8] {
            let n = Swift.min(maxLength, content.count - at)
            defer { at += n }
            return Array(content[at ..< at + n])
        }
    }

    private func header(_ payloadType: UInt16, _ payloadLength: UInt32) -> [UInt8] {
        V2GTP.header(payloadType: payloadType, payloadLength: payloadLength)
    }


    /// A declared length nobody could mean is refused before it is allocated.
    ///
    /// `0x7FFFFFFF` is a 2 GiB allocation that an 8-byte frame can ask for — on a phone, an
    /// out-of-memory kill that costs the sender nothing. `0xFFFFFFFF` is the value that catches the
    /// Kotlin twin, where `.toInt()` turns it into `-1` and a reader without this check returns a
    /// silently truncated frame; Swift's `Int(_:)` widens instead, so the outcome differs — which is
    /// exactly why the check does not depend on the word size to be safe.
    func testAnAbsurdDeclaredLengthIsRefusedRatherThanAllocatedFor() {

        for declared: UInt32 in [0x7FFF_FFFF, 0xFFFF_FFFF, UInt32(V2GTP.maximumPayloadBytes) + 1] {

            let stream = V2GTPStream(FixedStream(header(V2GTP.payloadTypeDinIso2Main, declared)))

            XCTAssertThrowsError(try stream.readRawFrame(),
                                 String(format: "0x%08x was accepted", declared)) { error in
                XCTAssertTrue("\(error)".contains("accepts at most"), "\(error)")
            }
        }
    }


    /// And the limit is far above anything the corpus contains.
    func testAFrameAtTheLargestRecordedSizeIsReadWhole() throws {

        let payload = [UInt8](repeating: 0, count: 921 - V2GTP.headerSize)  // -20 AuthorizationReq
        let frame   = header(V2GTP.payloadTypeIso20Main, UInt32(payload.count)) + payload

        let read = try V2GTPStream(FixedStream(frame)).readRawFrame()

        XCTAssertEqual(read.frame.count, 921)
        XCTAssertEqual(read.payloadType, V2GTP.payloadTypeIso20Main)
    }
}
