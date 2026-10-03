// swift-tools-version:6.0
import PackageDescription

// What swift-nio-ssl's vendored BoringSSL puts on the wire — the fifth stack of
// docs/experiments/tls-platform-suites.md, and the option docs/decisions/ios-tls-stack.md argues
// against. Kept for the same reason the observer beside it is kept: the decision rests on finding 1
// there, and a claim about a TLS stack is only worth what its last re-run says.
//
// Standalone on purpose — no gate builds it, so swift-nio never reaches swift/Package.swift.
//
//     swift run --package-path tools/tls-nio-probe TlsNioProbe 127.0.0.1 44370 iso20-sigalgs-only
let package = Package(
    name: "TlsNioProbe",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.29.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
    ],
    targets: [
        .executableTarget(name: "TlsNioProbe", dependencies: [
            .product(name: "NIOSSL", package: "swift-nio-ssl"),
            .product(name: "NIOCore", package: "swift-nio"),
            .product(name: "NIOPosix", package: "swift-nio"),
        ]),
    ])
