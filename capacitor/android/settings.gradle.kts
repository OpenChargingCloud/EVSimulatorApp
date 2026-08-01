// A build of its own, deliberately not part of `kotlin/`.
//
// Everything under `kotlin/` compiles and tests with nothing but a JDK — that is a property the
// project keeps on purpose, next to "`dotnet test` needs no C toolchain, no Java and no network".
// An Android library needs the Android SDK and the Android Gradle Plugin, so putting this module in
// that build would make the whole Kotlin back end untestable without them.
//
// The Kotlin modules arrive as an included build instead: the same sources, compiled from source,
// with no publishing step and no copy to drift.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "capacitor-ev-simulator"

includeBuild("../../kotlin")
