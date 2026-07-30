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
    ],
    targets: [
        .target(name: "ExiRuntime"),
        .testTarget(name: "ExiRuntimeTests", dependencies: ["ExiRuntime"]),
    ]
)
