dependencies {
    // `api`, not `implementation`: the generated JSON-LD serializer returns a JsonObject, so
    // exi-runtime is part of this module's public surface now. The wire codec alone never leaked a
    // runtime type — encode returns a ByteArray and decodeAny an Any — which is why this was
    // `implementation` until the JSON pass existed.
    api(project(":exi-runtime"))
    testImplementation(kotlin("test"))
}
