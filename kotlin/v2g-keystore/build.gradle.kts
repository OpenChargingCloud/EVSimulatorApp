dependencies {
    // PKCS#10 assembly. The JDK has no CSR builder at all, and BouncyCastle's takes a ContentSigner
    // — an external signer — which is exactly the shape a secure-element key needs. Same version as
    // the codec modules already pull in.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
}
