// The live session runner (docs/CONCEPT.md B1): a socket to a station, the EVCC state machines
// above it, and a bridge event for every frame that crosses.
//
// Its own module rather than part of v2g-bridge, because v2g-bridge says what an event IS and says
// so without the state machines — the note in its build file is explicit about that, and a live
// runner living there would drag the whole EVCC stack into the definition of an event.
dependencies {

    api(project(":v2g-bridge"))
    api(project(":v2g-evcc"))

    // TLS is a trust decision rather than a transport detail: the pairing code's root fingerprint is
    // the only anchor, and checking a chain against it is this module's business.
    api(project(":v2g-certificates"))

    testImplementation(kotlin("test"))
}
