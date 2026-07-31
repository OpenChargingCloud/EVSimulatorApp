dependencies {
    testImplementation(kotlin("test"))
    // Reads the chain corpus the C# PKI generates, verbatim, so the two cannot drift.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
