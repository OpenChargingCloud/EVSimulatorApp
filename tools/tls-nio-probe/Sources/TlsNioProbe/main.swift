import Foundation
import NIOCore
import NIOPosix
import NIOSSL

// swift-nio-ssl / BoringSSL, measured the same way the four platform stacks were: point it at
// tools/tls-clienthello-observer.py and read what it offers. The observer hangs up rather than
// answering, so every run below "fails" its handshake — that is the method, not the result.

let host = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "127.0.0.1"
let port = Int(CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "44340")!
let mode = CommandLine.arguments.count > 3 ? CommandLine.arguments[3] : "default"

// First, what the *enum* can even name — the question that sank Network.framework, whose
// tls_ciphersuite_t has no static ECDH member at all. An API that cannot name a suite cannot
// offer it however the implementation is configured.
if mode == "names" {
    // Unlike Apple's `tls_ciphersuite_t`, `NIOTLSCipher` is a RawRepresentable over UInt16 rather
    // than a closed enum — so *naming* a suite is never the obstacle here, and the only question is
    // what the vendored BoringSSL actually implements. That is what the wire runs below answer.
    for (name, code) in [("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", UInt16(0xC023)),
                         ("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256",  UInt16(0xC025)),
                         ("TLS_AES_256_GCM_SHA384",                  UInt16(0x1302)),
                         ("TLS_CHACHA20_POLY1305_SHA256",            UInt16(0x1303))] {
        print("  nameable: \(name) = 0x\(String(code, radix: 16)) -> \(NIOTLSCipher(rawValue: code))")
    }
    // And that the -20 signature algorithm exists in the API at all.
    print("  sigalg ecdsa_secp521r1_sha512 exists in SignatureAlgorithm: \(SignatureAlgorithm.ecdsaSecp521R1Sha512)")
    print("  sigalg ecdsa_secp256r1_sha256 exists in SignatureAlgorithm: \(SignatureAlgorithm.ecdsaSecp256R1Sha256)")
    exit(0)
}

var config = TLSConfiguration.makeClientConfiguration()
// The observer never answers, so trust never gets a chance to matter — but leaving verification on
// would make the failure ambiguous.
config.certificateVerification = .none

switch mode {
case "c023", "c025", "iso2":
    // One suite at a time, because "the -2 pair crashes" does not say which half.
    config.minimumTLSVersion = .tlsv12
    config.maximumTLSVersion = .tlsv12
    // Both -2 suites, by IANA code point, so an unnameable one is a compile error rather than a
    // silent omission.
    config.cipherSuiteValues = mode == "c023" ? [NIOTLSCipher(rawValue: 0xC023)]
                             : mode == "c025" ? [NIOTLSCipher(rawValue: 0xC025)]
                             : [NIOTLSCipher(rawValue: 0xC023), NIOTLSCipher(rawValue: 0xC025)]
case "iso20":
    config.minimumTLSVersion = .tlsv13
    config.cipherSuiteValues = [
        NIOTLSCipher(rawValue: 0x1302),
        NIOTLSCipher(rawValue: 0x1303),
    ]
    // The field the whole -20 question turns on.
    config.verifySignatureAlgorithms = [.ecdsaSecp521R1Sha512]
case "iso20-nosigalg":
    // The control that isolates one variable: TLS 1.3 and nothing else changed, so the P-521
    // station certificate meets a client that never advertised `ecdsa_secp521r1_sha512`.
    config.minimumTLSVersion = .tlsv13
case "iso20-sigalgs-only":
    config.minimumTLSVersion = .tlsv13
    config.verifySignatureAlgorithms = [.ecdsaSecp521R1Sha512, .ecdsaSecp256R1Sha256]
default:
    break
}

do {
    let context = try NIOSSLContext(configuration: config)
    let group   = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    defer { try? group.syncShutdownGracefully() }

    let bootstrap = ClientBootstrap(group: group).channelInitializer { channel in
        do {
            let handler = try NIOSSLClientHandler(context: context, serverHostname: nil)
            return channel.pipeline.addHandler(handler)
        } catch {
            return channel.eventLoop.makeFailedFuture(error)
        }
    }

    let channel = try bootstrap.connect(host: host, port: port).wait()
    try? channel.closeFuture.wait()
    print("\(mode): connection ended (the observer hangs up; the ClientHello is the result)")
} catch {
    print("\(mode): \(error)")
}
