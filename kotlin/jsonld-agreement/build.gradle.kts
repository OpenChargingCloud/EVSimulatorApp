// Nothing but tests.
//
// The JSON-LD agreement is a property of ALL nine message sets at once — that they produce the same
// documents the C# back end does — and no existing module sees all nine. v2g-dispatch comes closest
// and deliberately stops short: it excludes AppProtocol, because SAP shares a payload id with the -2
// messages and is decoded by the handshake rather than by payload type. Widening it to satisfy a
// test would blur what that module means.
dependencies {
    testImplementation(kotlin("test"))

    testImplementation(project(":exi-appprotocol"))
    testImplementation(project(":exi-iso2"))
    testImplementation(project(":exi-iso20-common"))
    testImplementation(project(":exi-iso20-ac"))
    testImplementation(project(":exi-iso20-dc"))
    testImplementation(project(":exi-iso20-wpt"))
    testImplementation(project(":exi-iso20-acdp"))
    testImplementation(project(":exi-iso20-acderiec"))
    testImplementation(project(":exi-iso20-acdersae"))
}
