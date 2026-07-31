import Foundation
import V2GTP
@testable import V2GEvcc

/// One recorded frame: the whole thing, header included. `message` is a label for failure text.
struct TraceFrame: Decodable {
    let payloadType: String
    let message: String
    let frame: String

    var bytes: [UInt8] {
        stride(from: 0, to: frame.count, by: 2).map {
            let i = frame.index(frame.startIndex, offsetBy: $0)
            let j = frame.index(i, offsetBy: 2)
            return UInt8(frame[i ..< j], radix: 16)!
        }
    }
}

/// One recorded request/response pair.
struct TraceExchange: Decodable {
    let index: Int
    let request: TraceFrame
    let response: TraceFrame
}

/// A session recorded by the C# side, read verbatim out of the submodule's `Vectors/` directory —
/// the same arrangement `V2GMeteringTests` already uses, and for the same reason: a port checked
/// against its own output can be wrong together with itself.
///
/// See `Vanaheimr.V2G.Simulation.Tests/Traces/SessionTrace.cs` for what these files do and do not
/// prove. In short: they pin this port to what the C# EVCC does, and cannot catch a bug it has too.
struct SessionTrace: Decodable {

    static let schemaVersion = 1

    let schemaVersion: Int
    let name: String
    let mode: String
    let exchanges: [TraceExchange]

    // `protocol` is a Swift keyword, so the field is renamed rather than back-ticked everywhere.
    let protocolName: String

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, name, mode, exchanges
        case protocolName = "protocol"
    }

    static func load(_ name: String) throws -> SessionTrace {

        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) { break }
        }

        let file = dir.appendingPathComponent(
            "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Simulation.Tests/Vectors/Session.\(name).trace.json")

        let trace = try JSONDecoder().decode(SessionTrace.self, from: Data(contentsOf: file))

        guard trace.schemaVersion == Self.schemaVersion else {
            throw SessionAborted(
                "trace schema version \(trace.schemaVersion), this build understands \(Self.schemaVersion)")
        }
        return trace
    }
}


/// Raised the moment a replayed session sends something the trace did not record.
struct TraceMismatch: Error, CustomStringConvertible {
    let description: String
}


/// A station made of a recorded session: it answers each request with the trace's next recorded
/// response, and requires the request that arrived to be byte-identical to the one recorded in that
/// slot. No peer, no socket, no SECC — only the file.
///
/// The Swift half of the oracle, mirroring `TraceReplayStream.cs` and Kotlin's `TraceReplay`. It
/// fails on the **first** divergent frame: once a request differs, every later one is a consequence
/// of a session that already went wrong, and twelve mismatches would bury the one thing that broke.
final class TraceReplay: V2GByteStream {

    private let trace: SessionTrace
    private var pending: [UInt8] = []
    private var readable: [UInt8] = []
    private var readOffset = 0

    /// How many exchanges were replayed. A session that stops early sends no wrong bytes.
    private(set) var replayed = 0

    var complete: Bool { replayed == trace.exchanges.count }

    init(_ trace: SessionTrace) {
        self.trace = trace
    }

    func write(_ bytes: [UInt8]) throws {

        pending += bytes

        while true {

            guard pending.count >= V2GTP.headerSize else { return }
            guard let header = V2GTP.readHeader(pending) else {
                throw TraceMismatch(description:
                    "exchange \(replayed): the bytes written are not a V2GTP frame (bad version/type bytes).")
            }

            let total = V2GTP.headerSize + Int(header.payloadLength)
            guard pending.count >= total else { return }

            let frame = Array(pending[0 ..< total])
            pending.removeFirst(total)

            guard replayed < trace.exchanges.count else {
                throw TraceMismatch(description:
                    "the session sent exchange \(replayed), but the trace '\(trace.name)' records only " +
                    "\(trace.exchanges.count). The port charges on past where the recording ends.")
            }

            let exchange = trace.exchanges[replayed]
            let expected = exchange.request.bytes
            guard frame == expected else {
                throw TraceMismatch(description:
                    "exchange \(replayed) (\(exchange.request.message)) differs from the trace " +
                    "'\(trace.name)':\n" + Self.diff(expected, frame))
            }

            readable += exchange.response.bytes
            replayed += 1
        }
    }

    func read(maxLength: Int) throws -> [UInt8] {
        guard readOffset < readable.count else {
            throw TraceMismatch(description:
                "exchange \(replayed): the session tried to read a response without having written a " +
                "complete request first — nothing in the trace answers that.")
        }
        let n = min(maxLength, readable.count - readOffset)
        defer { readOffset += n }
        return Array(readable[readOffset ..< readOffset + n])
    }

    /// Where two frames first part company. The offset is the useful part: under 8 it is the V2GTP
    /// header, above it the EXI body.
    private static func diff(_ expected: [UInt8], _ actual: [UInt8]) -> String {

        var at = 0
        while at < expected.count && at < actual.count && expected[at] == actual[at] { at += 1 }

        let where_ = at < V2GTP.headerSize
            ? "byte \(at), inside the 8-byte V2GTP header"
            : "byte \(at) (EXI payload offset \(at - V2GTP.headerSize))"

        func window(_ bytes: [UInt8]) -> String {
            guard bytes.count > at else { return "<ends here>" }
            return bytes[at ..< min(at + 16, bytes.count)].map { String(format: "%02x", $0) }.joined()
        }

        return "  first difference at \(where_)\n" +
               "  trace  \(expected.count) bytes, from there: \(window(expected))\n" +
               "  actual \(actual.count) bytes, from there: \(window(actual))"
    }
}
