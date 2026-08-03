dependencies {
    implementation(project(":exi-runtime"))
    implementation(project(":exi-appprotocol"))
    implementation(project(":exi-iso2"))
    implementation(project(":exi-iso20-common"))
    implementation(project(":exi-iso20-ac"))
    implementation(project(":exi-iso20-dc"))
    // Plug & Charge signs its SignedInfo under the standalone xmldsig grammar, not the combined one.
    implementation(project(":exi-xmldsig"))
    // The eMAID comes out of the contract certificate; one reader for it, not two.
    implementation(project(":v2g-certificates"))
    // Signing goes through a signer, never a raw key — a secure-element key has no bytes to hand over.
    implementation(project(":v2g-keystore"))
    implementation(project(":v2g-tp"))
    implementation(project(":v2g-dispatch"))

    // The vehicle's own energy counter, and the verifier for a station's signed reading.
    implementation(project(":v2g-metering"))

    testImplementation(kotlin("test"))
    // Reads the session-trace corpus the C# side records, verbatim, so the two cannot drift.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
