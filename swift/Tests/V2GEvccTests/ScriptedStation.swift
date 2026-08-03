import ExiAppProtocol
import V2GDispatch
import V2GTP
@testable import V2GEvcc

/// A station with a script instead of a state machine: its responses are laid out in full before
/// the session starts, and it never reads what the EV sent.
///
/// That deafness is the point. The C# tests for these behaviours (`EvccReadsTheOfferTests`,
/// `Evcc2EnergyTransferModeTests`, `Evcc20DynamicModeTests`) doctor one field of a real SECC's
/// response and let the rest of the session run live; the ports have no SECC, and a scripted
/// answer sequence is the smallest thing that puts a **foreign offer** in front of the EVCC — the
/// one thing the trace corpus structurally cannot contain, because every trace is a session with
/// our own C# station, which offers exactly what this EVCC prefers. A constant and a list agree
/// until a foreign station disagrees; these tests are that station.
///
/// The EVCC alternates write/read strictly, so pre-canned responses need no sequencing. A session
/// that refuses mid-script never reads the rest; a session that outruns the script gets an empty
/// read, which the transport reports as "connection closed" — the partial-script tests treat that
/// as the expected stop, the request under test already being in ``requests()`` by then.
final class ScriptedStation: V2GByteStream {

    private var readable: [UInt8]
    private var readOffset = 0
    private var sent: [UInt8] = []

    private(set) lazy var stream = V2GTPStream(self)

    init(_ frames: [[UInt8]]) {
        readable = frames.flatMap { $0 }
    }

    func read(maxLength: Int) throws -> [UInt8] {
        guard readOffset < readable.count else { return [] }   // end of script: "connection closed"
        let n = min(maxLength, readable.count - readOffset)
        defer { readOffset += n }
        return Array(readable[readOffset ..< readOffset + n])
    }

    func write(_ bytes: [UInt8]) throws {
        sent += bytes
    }

    /// The requests the EV actually wrote, one whole V2GTP frame each, header included.
    func requests() -> [[UInt8]] {
        var frames: [[UInt8]] = []
        var at = 0
        while at + V2GTP.headerSize <= sent.count {
            guard let header = V2GTP.readHeader(sent, offset: at) else {
                fatalError("request at offset \(at) is not a V2GTP frame")
            }
            let total = V2GTP.headerSize + Int(header.payloadLength)
            frames.append(Array(sent[at ..< at + total]))
            at += total
        }
        return frames
    }

    /// The EXI payloads of every session request after the SAP handshake, ready for a codec.
    func sessionRequestPayloads() -> [[UInt8]] {
        requests().dropFirst().map { Array($0[V2GTP.headerSize...]) }
    }

    /// A framed `SupportedAppProtocolRes` — OK, accepting `schemaID`.
    static func sapOk(schemaID: UInt8? = 1) -> [UInt8] {
        let payload = SupportedAppProtocolCodec.encode(
            SupportedAppProtocolRes(responseCode: .OK_SuccessfulNegotiation, schemaID: schemaID))
        return V2GTP.header(payloadType: V2GTP.payloadTypeAppProtocol,
                            payloadLength: UInt32(payload.count)) + payload
    }

    /// An already-encoded session response, framed for `set`.
    static func framed(_ set: MessageSet, _ exiPayload: [UInt8]) -> [UInt8] {
        V2GTPDispatcher.encode(set, exiPayload)
    }
}
