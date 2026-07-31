dependencies {
    // Only to read CRL distribution points out of an extension; the JVM exposes the raw bytes
    // and nothing that parses them.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    // Reads the chain corpus the C# PKI generates, verbatim, so the two cannot drift.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
