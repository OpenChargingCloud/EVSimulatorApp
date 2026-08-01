// swift-tools-version:6.0
import PackageDescription

// A package of its own, deliberately not a target in `swift/Package.swift`.
//
// Capacitor ships as an iOS-only binary framework, so anything that links it can only be built for
// iOS. The main package builds and tests on macOS — that is how `swift test` runs here at all — and
// adding an iOS-only target to it would end that. The V2G sources arrive as a local package
// dependency instead: the same sources, compiled from source, with nothing to copy and nothing to
// drift.
//
//     xcodebuild -scheme EvSimulatorPlugin -destination 'generic/platform=iOS Simulator' build
//
let package = Package(
    name: "EvSimulatorPlugin",
    platforms: [
        // Capacitor 8's own floor is iOS 14; the V2G package's is iOS 16, and the higher of two
        // floors is the floor.
        .iOS(.v16),
    ],
    products: [
        .library(name: "EvSimulatorPlugin", targets: ["EvSimulatorPlugin"]),
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.5.0"),
        .package(path: "../../swift"),
    ],
    targets: [
        .target(
            name: "EvSimulatorPlugin",
            dependencies: [
                .product(name: "Capacitor",  package: "capacitor-swift-pm"),

                // Unused here, and not optional: Capacitor's own umbrella header imports Cordova,
                // so anything that imports Capacitor has to link it too.
                .product(name: "Cordova",    package: "capacitor-swift-pm"),

                // "swift", not "V2GExi": a path dependency's identity is its directory name, not
                // the name in its manifest.
                .product(name: "ExiRuntime", package: "swift"),
                .product(name: "V2GBridge",  package: "swift"),

                // The live runner and the socket, so a host application can install one without
                // depending on the V2G package directly. Nothing here constructs it: which runner a
                // build uses is the application's decision (see EvSimulatorPlugin.runner).
                .product(name: "V2GSession", package: "swift"),
            ],
            // Swift 5 language mode, for this target only.
            //
            // `CAPPlugin` is an Objective-C class from a binary framework and is not `Sendable`, so
            // under Swift 6's strict concurrency checking a plugin cannot refer to itself from the
            // background work it starts — which is every plugin that does anything asynchronous.
            // The V2G package this depends on stays in Swift 6 mode; the concession is confined to
            // the file that touches Capacitor, and that file holds no state worth reasoning about
            // beyond the lock around `runner` and `sessions`.
            swiftSettings: [.swiftLanguageMode(.v5)]),
    ]
)
