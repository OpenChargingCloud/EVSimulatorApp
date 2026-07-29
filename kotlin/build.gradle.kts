plugins {
    kotlin("jvm") version "2.1.0" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

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
