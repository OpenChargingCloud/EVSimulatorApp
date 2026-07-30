dependencies {
    // Deliberately empty: V2GTP is the 8-byte header around an EXI payload and knows nothing
    // about EXI itself. Keeping it dependency-free lets a transport read a frame's type and
    // length without pulling in a single codec.
    testImplementation(kotlin("test"))
}
