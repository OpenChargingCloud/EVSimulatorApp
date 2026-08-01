import V2GDispatch
import V2GTP

/// Whatever a session's bytes travel over. A socket in the app, a recorded trace in the tests —
/// the state machines never learn which they got, and that is the whole point.
///
/// Deliberately not `Foundation.Stream`: this package's codecs are Foundation-free, and one protocol
/// with two methods keeps it that way. `read` may return fewer bytes than asked for, as a socket
/// does; [V2GTPStream] is where "fewer" becomes "keep reading".
public protocol V2GByteStream: AnyObject {
    func write(_ bytes: [UInt8]) throws
    func read(maxLength: Int) throws -> [UInt8]
}

/// A session that cannot continue: the peer refused, or answered something the protocol forbids.
public struct SessionAborted: Error, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// Reads and writes single V2GTP frames — the one place in this module that touches transport
/// octets directly. A port of the C# `V2GTPStream`.
public final class V2GTPStream {

    private let transport: V2GByteStream

    /// Called with every complete frame that crosses, its payload type, and the direction it went
    /// (`out` for a frame this EV sent, `in` for one it received).
    ///
    /// **This is the only place in the stack where a frame is whole.** Above it there are decoded
    /// messages and no bytes; below it there are bytes and no frame boundaries — `readExactly` may
    /// take several reads to fill one, and a tap on the byte stream would have to re-implement the
    /// framing to find out where a frame ends. So the bridge's event stream is fed from here.
    ///
    /// A no-op by default: everything that ran before there was a live runner still runs.
    private let onFrame: ([UInt8], UInt16, String) -> Void

    public init(_ transport: V2GByteStream,
                onFrame: @escaping ([UInt8], UInt16, String) -> Void = { _, _, _ in }) {
        self.transport = transport
        self.onFrame   = onFrame
    }

    /// Reads one frame — the 8-byte header, then exactly as many payload bytes as it declares — and
    /// hands the whole frame to the dispatcher. Throws on a malformed header, a peer that closes
    /// mid-frame, or an unrecognised payload type: a broken frame is not a recoverable condition
    /// for a session peer, so there is no `try` variant.
    public func readFrame() throws -> (set: MessageSet, message: Any) {
        // `decode` itself throws on malformed EXI *inside* a recognised set — a framing problem is
        // the `.failed` case below, and the two are deliberately different things.
        switch try V2GTPDispatcher.decode(readRawFrame().frame) {
        case .decoded(let set, let message): return (set, message)
        case .failed(let error):             throw SessionAborted("V2GTP frame: \(error)")
        }
    }

    /// Reads one frame at the transport level, whole, WITHOUT resolving it to a message set. Used by
    /// the SupportedAppProtocol handshake, which shares payload id 0x8001 with the -2 messages and
    /// so cannot be routed by payload type alone.
    public func readRawFrame() throws -> (frame: [UInt8], payloadType: UInt16) {

        let header = try readExactly(V2GTP.headerSize,
                                     "connection closed before a full 8-byte header arrived")

        guard let parsed = V2GTP.readHeader(header) else {
            throw SessionAborted("V2GTP frame: bad version/type bytes in the 8-byte header.")
        }

        // Before the allocation, and on the UNSIGNED value. `Int(_:)` on a 64-bit platform widens
        // rather than wrapping, so Swift is spared the truncation that catches Kotlin's `.toInt()` —
        // but the 2 GiB allocation is the same, and a reader should not depend on its word size to
        // be safe. See `V2GTP.maximumPayloadBytes`.
        guard parsed.payloadLength <= UInt32(V2GTP.maximumPayloadBytes) else {
            throw SessionAborted(
                "V2GTP frame: a frame of payload type "
              + String(format: "0x%04x", parsed.payloadType)
              + " declares \(parsed.payloadLength) payload byte(s); "
              + "this reader accepts at most \(V2GTP.maximumPayloadBytes).")
        }

        let payloadLength = Int(parsed.payloadLength)
        let payload = payloadLength > 0
            ? try readExactly(payloadLength,
                              "connection closed inside a frame declaring \(payloadLength) payload byte(s)")
            : []

        let frame = header + payload
        onFrame(frame, parsed.payloadType, "in")

        return (frame, parsed.payloadType)
    }

    /// Wraps an already-EXI-encoded payload with the header for `set` and writes it in one call.
    public func writeFrame(_ set: MessageSet, _ exiPayload: [UInt8]) throws {
        try writeAll(V2GTPDispatcher.encode(set, exiPayload))
    }

    /// As `writeFrame`, but with the payload type given directly — the SAP path, which has no
    /// `MessageSet` of its own on the wire.
    public func writeRawFrame(payloadType: UInt16, _ exiPayload: [UInt8]) throws {
        try writeAll(V2GTP.header(payloadType: payloadType,
                                  payloadLength: UInt32(exiPayload.count)) + exiPayload)
    }

    private func writeAll(_ frame: [UInt8]) throws {

        try transport.write(frame)

        // The payload type is read back out of the frame that just went, rather than passed down
        // from the caller: both write paths build the header themselves, and reporting what is
        // actually on the wire is one fewer thing that can disagree with it.
        if let parsed = V2GTP.readHeader(frame) {
            onFrame(frame, parsed.payloadType, "out")
        }
    }

    private func readExactly(_ count: Int, _ whatWentWrong: String) throws -> [UInt8] {
        var buffer: [UInt8] = []
        buffer.reserveCapacity(count)
        while buffer.count < count {
            let chunk = try transport.read(maxLength: count - buffer.count)
            if chunk.isEmpty {
                throw SessionAborted(
                    "V2GTP frame: \(whatWentWrong) (got \(buffer.count) of \(count)).")
            }
            buffer += chunk
        }
        return buffer
    }
}
