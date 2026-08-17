import CryptoKit
import Foundation
import V2GTP
@testable import V2GEvcc

/// One recorded frame: the whole thing, header included. `message` is a label for failure text.
///
/// `signature` is the raw `r‖s` value the frame carried, when it carried one. Its presence means the
/// frame is **not** byte-comparable as it stands — ECDSA's nonce is random — and the C# side compares
/// it by substituting the recorded value and verifying the produced one separately (`SignedFrame.cs`).
/// This harness does not do that yet, and refuses such a frame rather than pretending.
struct TraceFrame: Decodable {
    let payloadType: String
    let message: String
    let frame: String
    let signature: String?

    /// The raw `r‖s` the station's **meter** put in `MeterInfo`, when it fitted one. A second
    /// randomised signature by a second signer, and one that travels in *responses* — so unlike
    /// `signature` it never makes a request incomparable, and the replay never has to substitute it.
    let meterSignature: String?

    var isSigned: Bool { signature != nil }
    var signatureBytes: [UInt8]? { signature.map(SignedFrame.hex) }

    var carriesMeterSignature: Bool { meterSignature != nil }
    var meterSignatureBytes: [UInt8]? { meterSignature.map(SignedFrame.hex) }

    var bytes: [UInt8] {
        stride(from: 0, to: frame.count, by: 2).map {
            let i = frame.index(frame.startIndex, offsetBy: $0)
            let j = frame.index(i, offsetBy: 2)
            return UInt8(frame[i ..< j], radix: 16)!
        }
    }
}

/// A public key, as the two field elements — enough to verify a raw `r‖s` signature.
struct TraceSigningKey: Decodable {

    let x: String
    let y: String

    /// Which curve the elements are on. Absent means `P-256`, which is what every trace recorded
    /// before contract provisioning used and what a contract key still uses; a -20 OEM provisioning
    /// key is `P-521`, and reading its 66-byte coordinates as 32-byte ones would report a perfectly
    /// good signature as invalid.
    let curve: String?

    var curveName: String { curve ?? "P-256" }
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

    // 3 since 2026-08-03, when frames gained an optional meterSignature and traces a meterKey — a
    // station whose meter signs its readings. 2 since 2026-07-31, when frames gained an optional
    // signature. Both bumps are deliberate even though the changes are additive: a reader that
    // silently ignored the new field would compare a frame as though its bytes were deterministic
    // and fail for the wrong reason.
    static let schemaVersion = 3

    let schemaVersion: Int
    let name: String
    let mode: String
    let exchanges: [TraceExchange]
    let signingKey: TraceSigningKey?
    let meterKey: TraceSigningKey?

    // `protocol` is a Swift keyword, so the field is renamed rather than back-ticked everywhere.
    let protocolName: String

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, name, mode, exchanges, signingKey, meterKey
        case protocolName = "protocol"
    }

    static func load(_ name: String) throws -> SessionTrace {

        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(
                atPath: dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }

        let file = dir.appendingPathComponent(
            "vectors/Session.\(name).trace.json")

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

    /// The corpus key, on whichever curve the trace names — see ``TraceSigningKey/curve``.
    private enum CorpusKey {
        case p256(P256.Signing.PublicKey)
        case p521(P521.Signing.PublicKey)

        func verifies(_ frame: [UInt8]) -> Bool {
            switch self {
            case .p256(let key): return SignedFrame.verifies(frame, with: key)
            case .p521(let key): return SignedFrame.verifies(frame, with: key)
            }
        }
    }

    private var cachedSigningKey: CorpusKey?

    /// The corpus public key, built once. Verification needs a key from outside the frame — taking
    /// one from the message itself would accept anything a port cared to sign with.
    private func signingKey() throws -> CorpusKey {
        if let cachedSigningKey { return cachedSigningKey }
        guard let key = trace.signingKey else {
            throw TraceMismatch(description:
                "trace '\(trace.name)' carries a signed exchange but no signing key. The C# " +
                "SessionTrace.Build refuses to produce that, so this file was hand-edited.")
        }
        let built: CorpusKey = key.curveName == "P-521"
            ? .p521(try SignedFrame.publicKey521(x: key.x, y: key.y))
            : .p256(try SignedFrame.publicKey(x: key.x, y: key.y))
        cachedSigningKey = built
        return built
    }

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

            // A meter signature in a *request* would need the same substitution one field along, and
            // this harness does not do it. No recorded request carries one — the EV only ever echoes
            // a reading inside a signed MeteringReceiptReq, which the C# corpus refuses to record for
            // a separate reason (the echoed bytes sit inside the digested fragment). Refusing beats
            // comparing bytes that cannot match.
            guard !exchange.request.carriesMeterSignature else {
                throw TraceMismatch(description:
                    "exchange \(replayed) (\(exchange.request.message)) carries a meter signature in " +
                    "a request. This harness can only substitute the header signature, so it would " +
                    "compare 64 random bytes and fail for the wrong reason.")
            }

            // A signed frame cannot be compared as bytes — ECDSA's nonce is random. SignedFrame
            // explains the substitution; the short of it is that the signature value is the only
            // random part, so putting the recorded one back makes everything else comparable
            // exactly, and the produced one is checked on its own below.
            let comparable = try exchange.request.signatureBytes.map {
                try SignedFrame.withSignatureValue(frame, $0)
            } ?? frame

            guard comparable == expected else {
                throw TraceMismatch(description:
                    "exchange \(replayed) (\(exchange.request.message)) differs from the trace " +
                    "'\(trace.name)'" +
                    (exchange.request.isSigned ? " (compared with the recorded signature substituted)" : "") +
                    ":\n" + Self.diff(expected, comparable))
            }

            if exchange.request.isSigned,
               !(try signingKey().verifies(frame)) {
                throw TraceMismatch(description:
                    "exchange \(replayed) (\(exchange.request.message)) matches the trace once its " +
                    "signature is substituted, but the signature it actually produced does not verify " +
                    "against the corpus key. The message is right and the signing is not — a wrong " +
                    "key, wrong octets, or a wrong signature encoding.")
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
