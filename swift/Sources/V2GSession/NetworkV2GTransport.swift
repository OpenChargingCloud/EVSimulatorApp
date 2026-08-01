import Foundation
import Network
import Security

import V2GBridge
import V2GCertificates
import V2GEvcc

/// The socket half of a live session: TCP to a station, optionally wrapped in TLS.
///
/// Small on purpose. Everything a laptop can check lives above this file; what is left here is the
/// part only a real network exercises, so the less of it there is, the better.
///
/// ## This resolves host names, and that is not a contradiction
///
/// `PairingWarnings.isPrivateTarget` refuses to resolve anything, because it judges a code **nobody
/// has agreed to trust yet** — a lookup there would already be a callback to whoever printed it. By
/// the time a ``SessionConfig`` exists, a human has read the confirmation sheet and said yes, and the
/// whole point of saying yes is to open a connection. A `.local` name cannot be reached without
/// resolving it.
///
/// The restriction still holds, and it holds *before* this: `SessionConfig.parse` refuses any host
/// that is not private or link-local, so what gets resolved here is never an arbitrary name.
///
/// ## Blocking, over an API that is not
///
/// `NWConnection` is callback-based and `V2GByteStream` is blocking, because the state machines above
/// it are written as straight-line code — which is the right shape for a protocol that is strictly
/// request/response, and the shape the C# and Kotlin ports use. ``NetworkByteStream`` below is where
/// the two meet: a receive loop fills a buffer, and reads wait on a condition until there is
/// something in it.
public enum NetworkV2GTransport {

    /// - Parameters:
    ///   - connectTimeout: how long to wait for the TCP (and TLS) handshake.
    ///   - readTimeout: how long a single read may block. A station that stops answering mid-session
    ///     is a failed session, not a thread parked forever.
    public static func connect(_ config: SessionConfig,
                               connectTimeout: TimeInterval = 10,
                               readTimeout: TimeInterval = 30) throws -> V2GConnection {

        let parameters: NWParameters

        if config.transport == "tcp" {
            parameters = .tcp
        } else {
            guard let pin = config.rootFingerprint else {
                throw SessionRunnerError(
                    "a TLS session needs the root fingerprint from the pairing code, and this "
                  + "configuration carries none. The confirmation sheet reports that as "
                  + "'noTrustAnchor'; connecting anyway would mean trusting whoever answers.")
            }
            parameters = .init(tls: tlsOptions(pinnedRoot: try bytes(ofHex: pin)), tcp: .init())
        }

        // V2GTP is request/response; Nagle only adds latency here.
        (parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options)?.noDelay = true

        let endpoint = NWEndpoint.hostPort(host: .init(config.host),
                                           port: .init(rawValue: UInt16(config.port))!)

        let connection = NWConnection(to: endpoint, using: parameters)
        let stream     = NetworkByteStream(connection, readTimeout: readTimeout)

        try stream.start(timeout: connectTimeout)

        return stream
    }


    // MARK: TLS

    /// Trusts exactly one root: the one the pairing code named, and only if the station sends it.
    ///
    /// **Not the system trust store.** A V2G root is not a web CA and is not installed on a phone, so
    /// falling back to the platform anchors would not add a second opinion — it would replace the
    /// only opinion there is with one that cannot possibly apply. Hence a verify block that ignores
    /// `SecTrustEvaluate`'s own verdict entirely.
    ///
    /// The station has to transmit the root itself. That is what makes a fingerprint enough for a
    /// freshly installed app: the code carries 32 bytes, the station carries the certificate, and
    /// neither alone would do.
    ///
    /// There is no hostname check, deliberately. That is how the web decides identity, and a SECC's
    /// certificate carries an EVSE id rather than a DNS name or the address the pairing code happened
    /// to print. Identity here is the pinned root plus a chain that reaches it.
    private static func tlsOptions(pinnedRoot pin: [UInt8]) -> NWProtocolTLS.Options {

        let options = NWProtocolTLS.Options()

        sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)

        sec_protocol_options_set_verify_block(
            options.securityProtocolOptions,
            { _, trust, complete in

                let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()

                guard let presented = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                      !presented.isEmpty else {
                    complete(false)
                    return
                }

                let der = presented.map { [UInt8](SecCertificateCopyData($0) as Data) }

                // The chain validator is `async`, and the verify block is not. The box carries the
                // completion handler across, which is allowed: Network documents it as callable
                // exactly once, from any queue.
                let answer = VerdictBox(complete)

                Task {
                    answer.call(await accepts(chain: der, pinnedRoot: pin))
                }
            },
            DispatchQueue(label: "cloud.charging.v2g.tls-verify"))

        return options
    }


    /// The trust decision itself, with no Security types in it so that it can be read on its own.
    static func accepts(chain der: [[UInt8]], pinnedRoot pin: [UInt8]) async -> Bool {

        let presented = der.compactMap { try? V2GCertificate(der: $0) }
        guard presented.count == der.count else { return false }

        guard let anchor = presented.first(where: { V2GFingerprint(of: $0.der).bytes == pin })
        else { return false }         // the station never sent the root the code pinned

        // A self-signed station certificate that IS the pinned root: the pin is the whole identity,
        // and there is no path left to build. Legitimate for a single-station setup, and refusing it
        // would mean the pin only works when somebody also runs a CA.
        if presented.count == 1 { return true }

        let rest = presented.filter { V2GFingerprint(of: $0.der) != V2GFingerprint(of: anchor.der) }

        guard let chain = try? V2GCertificateChain(der: rest.map(\.der)) else { return false }

        return await V2GChainValidator()
            .validate(chain, against: InMemoryTrustStore(roots: [anchor]))
            .isTrusted
    }


    static func bytes(ofHex text: String) throws -> [UInt8] {

        let clean = Array(text.filter { !$0.isWhitespace && $0 != ":" })

        guard clean.count % 2 == 0 else {
            throw SessionRunnerError("'\(text)' is not a hex fingerprint.")
        }

        var out: [UInt8] = []
        out.reserveCapacity(clean.count / 2)

        for i in stride(from: 0, to: clean.count, by: 2) {
            guard let byte = UInt8(String(clean[i ... i + 1]), radix: 16) else {
                throw SessionRunnerError("'\(text)' is not a hex fingerprint.")
            }
            out.append(byte)
        }
        return out
    }
}


/// A blocking ``V2GByteStream`` over an `NWConnection`.
///
/// The receive loop is always armed: `NWConnection` hands over whatever has arrived and expects to be
/// asked again, so the loop appends to a buffer and re-arms, and ``read(maxLength:)`` waits until the
/// buffer has something or the connection ends. That is also why short reads are normal here and why
/// `V2GTPStream.readExactly` loops — a frame arrives in as many pieces as the network felt like.
/// `@unchecked Sendable` is a claim, and here is the claim: every mutable field below is read and
/// written only while `condition` is held, and the two semaphores in `start` and `write` establish
/// the ordering for the rest. `NWConnection`'s handlers are `@Sendable` and arrive on its own queue,
/// which is what forces the question in the first place.
public final class NetworkByteStream: V2GByteStream, V2GConnection, @unchecked Sendable {

    private let connection: NWConnection
    private let readTimeout: TimeInterval
    private let queue = DispatchQueue(label: "cloud.charging.v2g.session")

    private let condition = NSCondition()
    private var buffer: [UInt8] = []
    private var ended = false
    private var failure: Error?

    init(_ connection: NWConnection, readTimeout: TimeInterval) {
        self.connection  = connection
        self.readTimeout = readTimeout
    }

    public var stream: V2GByteStream { self }


    func start(timeout: TimeInterval) throws {

        let ready = DispatchSemaphore(value: 0)

        connection.stateUpdateHandler = { [self] state in
            switch state {
            case .ready:
                ready.signal()
            case .failed(let error), .waiting(let error):
                // `.waiting` is not a transient state worth honouring here: it means the path is not
                // usable, and a station in the room either answers now or is not there.
                finish(with: error)
                ready.signal()
            case .cancelled:
                finish(with: nil)
            default:
                break
            }
        }

        connection.start(queue: queue)

        if ready.wait(timeout: .now() + timeout) == .timedOut {
            connection.cancel()
            throw SessionRunnerError("the station did not answer within \(Int(timeout)) s.")
        }

        // Read back out of the instance rather than out of a captured `var`: the handler runs on the
        // connection's queue, and the semaphore is what orders that write against this read.
        condition.lock()
        let problem = failure
        condition.unlock()

        if let problem {
            connection.cancel()
            throw problem
        }

        receive()
    }


    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self] data, _, isComplete, error in

            guard let self else { return }

            if let data, !data.isEmpty {
                condition.lock()
                buffer += [UInt8](data)
                condition.broadcast()
                condition.unlock()
            }

            if let error { finish(with: error); return }
            if isComplete { finish(with: nil); return }

            receive()
        }
    }


    private func finish(with error: Error?) {
        condition.lock()
        ended = true
        if failure == nil { failure = error }
        condition.broadcast()
        condition.unlock()
    }


    public func read(maxLength: Int) throws -> [UInt8] {

        condition.lock()
        defer { condition.unlock() }

        let deadline = Date().addingTimeInterval(readTimeout)

        while buffer.isEmpty && !ended {
            if !condition.wait(until: deadline) {
                throw SessionRunnerError("the station stopped answering (waited \(Int(readTimeout)) s).")
            }
        }

        if let failure, buffer.isEmpty { throw failure }

        // An empty result means "the peer closed", which is what V2GTPStream.readExactly turns into
        // a named SessionAborted. Returning it rather than throwing keeps that message the one a
        // reader sees.
        let n = Swift.min(maxLength, buffer.count)
        defer { buffer.removeFirst(n) }
        return Array(buffer.prefix(n))
    }


    public func write(_ bytes: [UInt8]) throws {

        let done    = DispatchSemaphore(value: 0)
        let problem = ErrorBox()

        // Blocking on the send rather than firing and forgetting: the protocol above is strictly
        // request/response, so there is no throughput to win, and a failed write that surfaced later
        // would surface as a mysterious read failure instead.
        connection.send(content: Data(bytes), completion: .contentProcessed { error in
            problem.error = error
            done.signal()
        })

        done.wait()
        if let error = problem.error { throw error }
    }


    public func close() {
        connection.cancel()
    }
}


/// One error, written by a completion handler and read after the semaphore it signals.
///
/// `@unchecked Sendable` because the semaphore is the ordering: nothing reads the field before the
/// handler has signalled, and nothing writes it after.
private final class ErrorBox: @unchecked Sendable {
    var error: Error?
}


/// The TLS verify block's completion handler, carried into the `Task` that decides.
private final class VerdictBox: @unchecked Sendable {

    private let complete: (Bool) -> Void

    init(_ complete: @escaping (Bool) -> Void) { self.complete = complete }

    func call(_ trusted: Bool) { complete(trusted) }
}
