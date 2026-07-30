dependencies {
    testImplementation(kotlin("test"))
    // Reads the meter corpus the C# side generates, verbatim, so the two cannot drift.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
