plugins {
    kotlin("jvm") version "2.1.0" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Coordinates, so that `capacitor/android` — a separate build, because it needs the Android SDK
    // and this one must not — can include this build and have Gradle substitute the modules from
    // source. Nothing is published anywhere; these are the names the substitution matches on.
    group   = "cloud.charging.v2g"
    version = "0.1.0"

    repositories { mavenCentral() }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            // The generated codec uses UByte/UShort/UInt/ULong throughout: XSD's unsigned
            // built-ins map onto Kotlin's unsigned types, which are still opt-in API.
            freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showStandardStreams = true }
    }
}
