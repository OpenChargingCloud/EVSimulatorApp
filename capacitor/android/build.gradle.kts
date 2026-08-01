// No versions in this block, and that is what makes the module work in both places it is built.
//
// Standalone — `./gradlew testDebugUnitTest` here — the versions come from `settings.gradle.kts`.
// Inside an application, `cap sync` includes this project in the app's build, where the Android and
// Kotlin plugins are already on the buildscript classpath: declaring a version there is an error,
// because Gradle cannot check a version against a plugin it did not resolve.
//
// Both contexts therefore use the app's toolchain — AGP 8.13, Kotlin 2.1.0, Gradle 8.14.3 — rather
// than agreeing by coincidence. The wrapper in this directory is the same one `shell/android` uses.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {

    namespace  = "cloud.charging.evsim"
    compileSdk = 36

    defaultConfig {
        // Capacitor 8's own floor. Nothing here needs anything newer.
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Left at its default (false) on purpose: a stubbed android.jar that silently returns
            // zero would turn the JSObject measurement in EvSimulatorPluginTest into a test that
            // measures nothing.
            isReturnDefaultValues = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {

    compileOnly("com.capacitorjs:core:8.5.0")

    // The session, the events and the configuration — four back ends agree on all three, and this
    // module adds nothing to them. It is transport.
    api("cloud.charging.v2g:v2g-bridge:0.1.0")

    // The live runner and the socket, so a host application can install one without depending on
    // the Kotlin build directly. Nothing here constructs it: which runner a build uses is the
    // application's decision (see EvSimulatorPlugin.runner).
    api("cloud.charging.v2g:v2g-session:0.1.0")

    // Spelled out rather than `kotlin("test")`: that helper takes its version from the Kotlin
    // plugin's own coordinates, which the consumed build resolves differently from the standalone
    // one, and a coordinate with no version resolves to nothing at all.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")

    testImplementation("com.capacitorjs:core:8.5.0")

    // The real org.json, because the mockable android.jar's is a stub that throws. Android's own
    // org.json is a fork of this one; the difference is documented where it matters, in
    // EvSimulatorPluginTest.
    testImplementation("org.json:json:20250517")
}
