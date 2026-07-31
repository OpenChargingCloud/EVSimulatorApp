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

    public init(_ transport: V2GByteStream) {
        self.transport = transport
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

        let payloadLength = Int(parsed.payloadLength)
        let payload = payloadLength > 0
            ? try readExactly(payloadLength,
                              "connection closed inside a frame declaring \(payloadLength) payload byte(s)")
            : []

        return (header + payload, parsed.payloadType)
    }

    /// Wraps an already-EXI-encoded payload with the header for `set` and writes it in one call.
    public func writeFrame(_ set: MessageSet, _ exiPayload: [UInt8]) throws {
        try transport.write(V2GTPDispatcher.encode(set, exiPayload))
    }

    /// As `writeFrame`, but with the payload type given directly — the SAP path, which has no
    /// `MessageSet` of its own on the wire.
    public func writeRawFrame(payloadType: UInt16, _ exiPayload: [UInt8]) throws {
        try transport.write(V2GTP.header(payloadType: payloadType,
                                         payloadLength: UInt32(exiPayload.count)) + exiPayload)
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
