// No `kotlin("android")` here: AGP 9 brings its own Kotlin support and registers the `kotlin`
// extension itself, so applying the JetBrains plugin as well fails outright.
plugins {
    id("com.android.library") version "9.3.1"
}

android {

    namespace  = "cloud.charging.evsim"
    compileSdk = 36

    defaultConfig {
        // Capacitor 8's own floor. Nothing here needs anything newer.
        minSdk = 23
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

dependencies {

    compileOnly("com.capacitorjs:core:8.5.0")

    // The session, the events and the configuration — four back ends agree on all three, and this
    // module adds nothing to them. It is transport.
    api("cloud.charging.v2g:v2g-bridge:0.1.0")

    // Spelled out rather than `kotlin("test")`: that helper takes its version from the JetBrains
    // Kotlin plugin, which is not applied here (AGP 9 brings its own), so it would resolve to a
    // coordinate with no version at all.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")

    testImplementation("com.capacitorjs:core:8.5.0")

    // The real org.json, because the mockable android.jar's is a stub that throws. Android's own
    // org.json is a fork of this one; the difference is documented where it matters, in
    // EvSimulatorPluginTest.
    testImplementation("org.json:json:20250517")
}
