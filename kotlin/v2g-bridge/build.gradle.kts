// The event stream the Capacitor plugin emits (docs/CONCEPT.md B1).
dependencies {
    // Every message goes out twice — as JSON-LD and as the raw V2GTP frame — so this needs the
    // codecs and their generated JSON-LD passes. Not the state machines: what produces the events is
    // the session runner, and this module only says what an event IS.
    api(project(":exi-appprotocol"))
    api(project(":exi-iso2"))
    api(project(":exi-iso20-common"))
    api(project(":exi-iso20-ac"))
    api(project(":exi-iso20-dc"))

    // The command side (SessionConfig) restricts a session to a private-range target, and that rule
    // already exists: it is the same one the confirmation sheet applies to a scanned code. A second
    // copy here is how the two would drift apart.
    api(project(":v2g-pairing"))

    testImplementation(kotlin("test"))
}
