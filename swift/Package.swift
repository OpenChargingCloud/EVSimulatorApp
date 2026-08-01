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
        .library(name: "ExiXmlDsig", targets: ["ExiXmlDsig"]),
        .library(name: "V2GCertificates", targets: ["V2GCertificates"]),
        .library(name: "V2GKeystore", targets: ["V2GKeystore"]),
        .library(name: "V2GPairing",  targets: ["V2GPairing"]),
        .library(name: "V2GBridge",   targets: ["V2GBridge"]),
        .library(name: "V2GEvcc", targets: ["V2GEvcc"]),
    ],
    dependencies: [
        // The Swift package's first third-party *package* dependency, and a deliberate one.
        //
        // C# uses System.Security.Cryptography.X509Certificates and Kotlin java.security.cert — both
        // their platform's implementation. Writing our own only here would make Swift the outlier in
        // the risky direction, and the direction is what settles it: the contract chain arrives from
        // a scanned QR code, so it is UNTRUSTED INPUT reaching a parser on a phone. Certificate
        // parsers are a classic vulnerability class; this is not the place to have our own.
        //
        // (An earlier draft of this note also argued that writing certificates is harder than
        // reading them. True, and irrelevant: the app never issues certificates. It may need to
        // produce a CSR, which this library also covers.)
        //
        // It brings two more with it — swift-asn1 and swift-crypto — so this is three packages, not
        // one. All Apple's, all Apache-2.0, same platform floors as ours. Bounded to the current
        // minor so an explicit `swift package update` cannot wander; Package.resolved is the actual
        // pin and is checked in.
        //
        // Note the contrast with CGoldilocks below, which is vendored rather than depended upon.
        // That was a C library with no SwiftPM story and it signs our messages; this is a Swift
        // package from the same vendor as the toolchain, and it parses. If the release cadence ever
        // becomes a problem, it can be vendored the same way — the V2GCertificates seam exists so
        // that swap costs one target.
        .package(url: "https://github.com/apple/swift-certificates.git", .upToNextMinor(from: "1.19.4")),
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

        // The EVCC state machines (docs/CONCEPT.md §5, A4) — hand-written, not generated, and the
        // first thing in this package that is a *session* rather than a message. Its gate is
        // therefore different in kind from every other target's: not a vector corpus but recorded
        // sessions, replayed. See Tests/V2GEvccTests/SessionTrace.swift.
        // The standalone W3C XMLDSig grammar. Not a message set: it exists only to reproduce the EXI
        // fragment encoding of a SignedInfo built over xmldsig-core-schema.xsd *alone*, which is what
        // Josev/EXIficient actually signs — distinct from the combined fragment grammar the -2 and
        // -20 codecs use for everything else. Generated, like every other codec here.
        .target(name: "ExiXmlDsig", dependencies: ["ExiRuntime"]),

        // The X.509 seam. Everything that knows swift-certificates exists lives here and nowhere
        // else, so the dependency is one target wide — replaceable, and absent from the graph of
        // anyone who only wants a codec. B2's certificate wallet is what this grows into.
        .target(name: "V2GCertificates", dependencies: [
            .product(name: "X509", package: "swift-certificates"),
        ]),
        .testTarget(name: "V2GCertificatesTests", dependencies: ["V2GCertificates"]),

        // Private keys, and what may honestly be claimed about them (docs/CONCEPT.md §3.4). No
        // certificates and no EXI: a key is a key whatever it ends up signing, and the module that
        // decides how a key is protected should not also be the one parsing untrusted input.
        .target(name: "V2GKeystore", dependencies: [
            // CSR assembly needs X.509 structures. The keystore still never touches a certificate's
            // trust decisions — that is V2GCertificates' business.
            .product(name: "X509", package: "swift-certificates"),
        ]),
        .testTarget(name: "V2GKeystoreTests", dependencies: ["V2GKeystore"]),

        // The scanned pairing code. No dependencies at all, deliberately: this is the first thing to
        // touch input from outside, it runs before any session or key exists, and it should be
        // possible to reason about it without reasoning about anything else.
        .target(name: "V2GPairing"),
        .testTarget(name: "V2GPairingTests", dependencies: ["V2GPairing"]),

        // The event stream the Capacitor plugin emits (docs/CONCEPT.md B1). Every message goes out
        // twice — as JSON-LD and as the raw V2GTP frame — so it needs the codecs and their JSON-LD
        // passes, and nothing else: what produces the events is the session runner, and this target
        // only says what an event IS.
        // V2GPairing is here for the command side: SessionConfig restricts a session to a
        // private-range target, and that rule already exists — it is the same one the confirmation
        // sheet applies to a scanned code. A second copy is how the two would drift apart.
        .target(name: "V2GBridge", dependencies: [
            "ExiRuntime", "ExiAppProtocol", "ExiIso2", "ExiIso20Common", "ExiIso20AC", "ExiIso20DC",
            "V2GPairing",
        ]),
        .testTarget(name: "V2GBridgeTests", dependencies: ["V2GBridge"]),

        // Test-only: the JSON-LD documents this back end produces, against the ones C# produces.
        // Its own test target because the property is about ALL message sets at once, and no
        // existing target sees more than its own.
        .testTarget(name: "JsonLdAgreementTests", dependencies: [
            "ExiRuntime", "ExiAppProtocol", "ExiIso2", "ExiIso20Common",
            "ExiIso20AC", "ExiIso20DC", "ExiIso20ACDP", "ExiIso20AcDerIec", "ExiIso20AcDerSae",
        ]),

        .target(name: "V2GEvcc", dependencies: [
            "V2GTP", "V2GDispatch", "ExiAppProtocol", "ExiIso2", "ExiIso20Common",
            "ExiIso20AC", "ExiIso20DC", "ExiXmlDsig", "V2GCertificates", "V2GKeystore",
        ]),
        .testTarget(name: "V2GEvccTests", dependencies: ["V2GEvcc"]),
    ]
)
