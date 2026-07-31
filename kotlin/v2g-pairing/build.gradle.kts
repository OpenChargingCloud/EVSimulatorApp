dependencies {
    testImplementation(kotlin("test"))
    // Reads the pairing corpus the C# side generates, verbatim, so the Pi and the app cannot drift.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
