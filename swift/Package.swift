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
        .library(name: "V2GEd448", targets: ["V2GEd448"]),
        .library(name: "V2GMetering", targets: ["V2GMetering"]),
        .library(name: "V2GTP", targets: ["V2GTP"]),
        .library(name: "V2GDispatch", targets: ["V2GDispatch"]),
    ],
    targets: [
        .target(name: "ExiRuntime"),
        .testTarget(name: "ExiRuntimeTests", dependencies: ["ExiRuntime"]),

        // Ed448 for -20's second signature suite, which CryptoKit cannot provide at all: it lacks
        // the curve, not merely a registered provider (docs/CONCEPT.md §3.3).
        //
        // Vendored rather than depended upon. The C is libgoldilocks, checked in verbatim and never
        // edited — see Sources/CGoldilocks/PROVENANCE.md for the chain it came down and the licence
        // notices that must travel with it. V2GEd448 is our own ~100-line surface over it, so no
        // third party's release cadence sits between us and the code that makes our signatures.
        //
        // Correctness is not taken on trust: Ed448VectorTests holds it to RFC 8032 §7.4's published
        // vectors, byte for byte.
        .target(name: "CGoldilocks", exclude: ["LICENSE.libgoldilocks.txt", "PROVENANCE.md"],
                publicHeadersPath: "include", cSettings: [.headerSearchPath("private")]),
        .target(name: "V2GEd448", dependencies: ["CGoldilocks"]),
        .testTarget(name: "V2GEd448Tests", dependencies: ["V2GEd448"]),

        // Generated — see swift/README.md for the regeneration commands.
        .target(name: "ExiAppProtocol", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiAppProtocolTests", dependencies: ["ExiAppProtocol"]),

        .target(name: "ExiIso2", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiIso2Tests", dependencies: ["ExiIso2"]),

        // One target per -20 message set, as in kotlin/: they are independent grammars that happen
        // to embed the same CommonTypes, and each carries its own copy of the XMLDSig schema.
        .target(name: "ExiIso20Common", dependencies: ["ExiRuntime", "V2GEd448"]),
        .testTarget(name: "ExiIso20CommonTests", dependencies: ["ExiIso20Common"]),
        .target(name: "ExiIso20DC", dependencies: ["ExiRuntime", "V2GEd448"]),
        .testTarget(name: "ExiIso20DCTests", dependencies: ["ExiIso20DC"]),
        .target(name: "ExiIso20AC", dependencies: ["ExiRuntime", "V2GEd448"]),
        .testTarget(name: "ExiIso20ACTests", dependencies: ["ExiIso20AC"]),
        .target(name: "ExiIso20ACDP", dependencies: ["ExiRuntime"]),
        .testTarget(name: "ExiIso20ACDPTests", dependencies: ["ExiIso20ACDP"]),

        // The Amendment 1 DER sets. Their corpora are of mixed provenance — see the tests.
        .target(name: "ExiIso20AcDerIec", dependencies: ["ExiRuntime", "V2GEd448"]),
        .testTarget(name: "ExiIso20AcDerIecTests", dependencies: ["ExiIso20AcDerIec"]),
        .target(name: "ExiIso20AcDerSae", dependencies: ["ExiRuntime", "V2GEd448"]),
        .testTarget(name: "ExiIso20AcDerSaeTests", dependencies: ["ExiIso20AcDerSae"]),

        // Verifying a station's meter-signed reading (docs/CONCEPT.md §4.3). Depends on nothing but
        // CryptoKit: the payload is a byte layout, and the field is protocol-agnostic.
        .target(name: "V2GMetering"),
        .testTarget(name: "V2GMeteringTests", dependencies: ["V2GMetering"]),

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
        .testTarget(name: "Ed448IntegrationTests", dependencies: ["V2GEd448", "ExiIso20Common"]),
    ]
)
