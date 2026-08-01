// A build of its own, deliberately not part of `kotlin/`.
//
// Everything under `kotlin/` compiles and tests with nothing but a JDK — that is a property the
// project keeps on purpose, next to "`dotnet test` needs no C toolchain, no Java and no network".
// An Android library needs the Android SDK and the Android Gradle Plugin, so putting this module in
// that build would make the whole Kotlin back end untestable without them.
//
// The Kotlin modules arrive as an included build instead: the same sources, compiled from source,
// with no publishing step and no copy to drift.
//
// **This file is read only when the module is built on its own.** Included in an application by
// `cap sync`, the app's own settings.gradle is the one Gradle reads, and the versions below come
// from the app's buildscript classpath instead — which is why `build.gradle.kts` names no versions.
// They are the same versions either way, and the wrapper in this directory is the app's wrapper.

pluginManagement {

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("com.android.library")        version "8.13.0"
        id("org.jetbrains.kotlin.android") version "2.1.0"
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
