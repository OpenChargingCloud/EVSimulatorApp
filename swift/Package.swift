// swift-tools-version:6.0
import PackageDescription

// Swift EXI codecs for ISO 15118-2 / -20 — the third back end of the shared generator, next to
// the C# and Kotlin ones. Only `ExiRuntime` is hand-written; every codec module that follows is
// emitted by `Vanaheimr.V2G.Exi.Codegen --lang swift` and checked in, as on the Kotlin side.
//
// One target per message set (rather than one big module) for the same reason `kotlin/` splits:
// the -20 sets are tens of thousands of generated lines, and Swift's type checker degrades badly
// on bulk. See docs/CONCEPT.md §3.7.
let package = Package(
    name: "V2GExi",
    platforms: [
        // The codec itself is platform-free; these floors exist because the app target is iOS.
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "ExiRuntime", targets: ["ExiRuntime"]),
        .library(name: "ExiAppProtocol", targets: ["ExiAppProtocol"]),
        .library(name: "ExiIso2", targets: ["ExiIso2"]),
        .library(name: "ExiIso20Common", targets: ["ExiIso20Common"]),
        .library(name: "ExiIso20DC", targets: ["ExiIso20DC"]),
        .library(name: "ExiIso20AC", targets: ["ExiIso20AC"]),
        .library(name: "ExiIso20ACDP", targets: ["ExiIso20ACDP"]),
        .library(name: "ExiIso20AcDerIec", targets: ["ExiIso20AcDerIec"]),
        .library(name: "ExiIso20AcDerSae", targets: ["ExiIso20AcDerSae"]),
        .library(name: "V2GTP", targets: ["V2GTP"]),
        .library(name: "V2GDispatch", targets: ["V2GDispatch"]),
    ],
    // The Ed448 half of -20's signature suite, which CryptoKit cannot provide at all — it lacks the
    // curve, not merely a registered provider (docs/CONCEPT.md §3.3, §8 #10).
    //
    // Chosen after measurement rather than from the README: it reproduces RFC 8032 §7.4 byte for
    // byte and costs ~81 KB of machine code, against megabytes for OpenSSL. Despite the "pure
    // Swift" billing it vendors Mike Hamburg's libgoldilocks C sources and wraps them, which is the
    // better news — the field arithmetic is the reference implementation rather than fresh code.
    // Findings, including the arguments against, in swift/SPIKE-ed448.md.
    //
    // Pinned `exact:` deliberately: a v0.1.x package with one author behind the wrapper, and a
    // version range would let a crypto dependency move under us between builds.
    //
    // All five -20 sets link it — each carries its own `V2GSignature`, for the fragment-selector
    // reason documented there. ACDP does not: it has no signable element.
    dependencies: [
        .package(url: "https://github.com/Kingpin-Apps/swift-goldilocks.git", exact: "0.1.1"),
    ],
    targets: [
        .target(name: "ExiRuntime"),
        .testTarget(name: "ExiRuntimeTests", dependencies: ["ExiRuntime"]),

        // Generated — see swift/README.md for the regeneration commands.
        .target(name: "ExiAppProtocol", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiAppProtocolTests", dependencies: ["ExiAppProtocol"]),

        .target(name: "ExiIso2", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiIso2Tests", dependencies: ["ExiIso2"]),

        // One target per -20 message set, as in kotlin/: they are independent grammars that happen
        // to embed the same CommonTypes, and each carries its own copy of the XMLDSig schema.
        .target(name: "ExiIso20Common", dependencies: ["ExiRuntime", .product(name: "Goldilocks", package: "swift-goldilocks")]),
        .testTarget(name: "ExiIso20CommonTests", dependencies: ["ExiIso20Common"]),
        .target(name: "ExiIso20DC", dependencies: ["ExiRuntime", .product(name: "Goldilocks", package: "swift-goldilocks")]),
        .testTarget(name: "ExiIso20DCTests", dependencies: ["ExiIso20DC"]),
        .target(name: "ExiIso20AC", dependencies: ["ExiRuntime", .product(name: "Goldilocks", package: "swift-goldilocks")]),
        .testTarget(name: "ExiIso20ACTests", dependencies: ["ExiIso20AC"]),
        .target(name: "ExiIso20ACDP", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiIso20ACDPTests", dependencies: ["ExiIso20ACDP"]),

        // The Amendment 1 DER sets. Their corpora are of mixed provenance — see the tests.
        .target(name: "ExiIso20AcDerIec", dependencies: ["ExiRuntime", .product(name: "Goldilocks", package: "swift-goldilocks")]),
        .testTarget(name: "ExiIso20AcDerIecTests", dependencies: ["ExiIso20AcDerIec"]),
        .target(name: "ExiIso20AcDerSae", dependencies: ["ExiRuntime", .product(name: "Goldilocks", package: "swift-goldilocks")]),
        .testTarget(name: "ExiIso20AcDerSaeTests", dependencies: ["ExiIso20AcDerSae"]),

        // Hand-written, and split for the reason kotlin/ splits them: reading a frame's type and
        // length pulls in nothing, while resolving that type to a decoder needs every message set.
        .target(name: "V2GTP"),
        .testTarget(name: "V2GTPTests", dependencies: ["V2GTP"]),
        .target(name: "V2GDispatch", dependencies: [
            "V2GTP", "ExiIso2", "ExiIso20Common", "ExiIso20AC", "ExiIso20DC", "ExiIso20ACDP",
        ]),
        .testTarget(name: "V2GDispatchTests", dependencies: ["V2GDispatch"]),

        // The dependency's acceptance test, kept in its own target rather than folded into
        // ExiIso20CommonTests: it checks *the library* against RFC 8032, which is a different
        // question from whether our codecs are right, and it should stay separately runnable when
        // a version bump needs re-checking. It is also the only target that sees both Goldilocks
        // and ExiIso20Common, so it is where the two halves are joined — see the test named for
        // it.
        .testTarget(name: "Ed448GoldilocksSpikeTests", dependencies: [
            .product(name: "Goldilocks", package: "swift-goldilocks"),
            "ExiIso20Common",
        ]),
    ]
)
