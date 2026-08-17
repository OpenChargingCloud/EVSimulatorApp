import Foundation
import Network

import ExiRuntime

/// A station that answers exactly what one recorded session recorded, over a real socket.
///
/// ## Why a socket and not a pair of byte arrays
///
/// The EVCC trace tests already replay these sessions over an in-memory stream, and they prove the
/// state machines say the right bytes. What they cannot exercise is the thing that only exists on a
/// network: **a frame does not arrive all at once.** `V2GTPStream.readExactly` loops for exactly that
/// reason, and an in-memory stream — which always returns everything asked for — is the one input
/// that can never take the loop round twice.
///
/// So this deliberately writes each response in ``fragment`` byte pieces. A reader that assumed one
/// `read` per frame passes every existing test in this repository and fails here on the first
/// response.
///
/// ## It is also still the byte oracle
///
/// Each request is compared with the recorded one before the matching response goes out. A live
/// runner that reached the station and said something slightly different would otherwise look like a
/// success.
///
/// Loopback only, and no port is chosen: port `.any` takes whatever is free. Nothing leaves the
/// machine.
final class RecordedStation: @unchecked Sendable {

    /// Small enough that every frame in the corpus is split several times.
    private static let fragment = 3

    private let exchangesJson: [JsonObject]
    private let listener: NWListener
    private let queue = DispatchQueue(label: "recorded-station")

    private let lock = NSLock()
    private var _complaint: String?
    private var _served = 0
    private var pending: [UInt8] = []
    private var cursor = 0                 // which exchange we are waiting on
    private var awaiting = 0               // bytes still expected for its request

    private(set) var port: UInt16 = 0

    /// What went wrong on the station side, if anything. Read after the session ends.
    var complaint: String? { lock.lock(); defer { lock.unlock() }; return _complaint }

    /// How many recorded exchanges the EV actually walked through.
    var served: Int { lock.lock(); defer { lock.unlock() }; return _served }

    var exchanges: Int { exchangesJson.count }


    init(trace: JsonObject) throws {

        exchangesJson = (trace["exchanges"] as! JsonArray).asList.map { $0 as! JsonObject }

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint   = .hostPort(host: .ipv4(.loopback), port: .any)

        listener = try NWListener(using: parameters)
    }


    func start() throws -> RecordedStation {

        let ready = DispatchSemaphore(value: 0)

        listener.stateUpdateHandler = { [self] state in
            if case .ready = state {
                port = listener.port?.rawValue ?? 0
                ready.signal()
            }
            if case .failed = state { ready.signal() }
        }

        listener.newConnectionHandler = { [self] connection in
            connection.start(queue: queue)
            receive(on: connection)
        }

        listener.start(queue: queue)

        guard ready.wait(timeout: .now() + 5) == .success, port != 0 else {
            throw StationError("the recorded station never became ready")
        }

        awaiting = expectedRequest(at: 0)?.count ?? 0

        return self
    }


    func stop() {
        listener.cancel()
    }


    // MARK: The conversation

    private func receive(on connection: NWConnection) {

        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [self] data, _, isComplete, error in

            if let data, !data.isEmpty { consume([UInt8](data), on: connection) }

            if error != nil || isComplete { return }

            receive(on: connection)
        }
    }


    private func consume(_ bytes: [UInt8], on connection: NWConnection) {

        lock.lock()
        pending += bytes
        lock.unlock()

        while true {

            lock.lock()
            guard let expected = expectedRequest(at: cursor), pending.count >= expected.count else {
                lock.unlock()
                return
            }

            let actual = Array(pending.prefix(expected.count))
            pending.removeFirst(expected.count)

            guard actual == expected else {
                _complaint = "exchange \(cursor): the EV sent \(hex(actual)), "
                           + "the recording has \(hex(expected))"
                lock.unlock()
                connection.cancel()
                return
            }

            let response = (exchangesJson[cursor]["response"] as? JsonObject)
                .flatMap { $0["frame"] as? JsonString }
                .map { RecordedStation.bytes(ofHex: $0.value) }

            cursor  += 1
            _served += 1
            lock.unlock()

            guard let response else { return }       // a recorded exchange with no answer

            // In pieces, on purpose — see the type comment.
            var at = 0
            while at < response.count {
                let n = Swift.min(RecordedStation.fragment, response.count - at)
                connection.send(content: Data(response[at ..< at + n]),
                                completion: .contentProcessed { _ in })
                at += n
            }
        }
    }


    private func expectedRequest(at index: Int) -> [UInt8]? {
        guard index < exchangesJson.count,
              let request = exchangesJson[index]["request"] as? JsonObject,
              let frame   = request["frame"] as? JsonString else { return nil }
        return RecordedStation.bytes(ofHex: frame.value)
    }

    private func hex(_ value: [UInt8]) -> String {
        value.map { String(format: "%02x", $0) }.joined()
    }


    // MARK: Loading

    static func bytes(ofHex text: String) -> [UInt8] {
        let clean = Array(text)
        return stride(from: 0, to: clean.count, by: 2).map {
            UInt8(String(clean[$0 ... $0 + 1]), radix: 16)!
        }
    }

    static let repositoryRoot: URL = {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("EVSimulatorApp.slnx").path) {
            let parent = directory.deletingLastPathComponent()
            precondition(parent != directory, "repository root not found")
            directory = parent
        }
        return directory
    }()

    /// One recorded session, from the corpus the C# side generates.
    static func load(_ name: String) throws -> JsonObject {

        let file = repositoryRoot.appendingPathComponent(
            "vectors/Session.\(name).trace.json")

        return try JsonValue.parse(String(contentsOf: file, encoding: .utf8)) as! JsonObject
    }
}


struct StationError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
