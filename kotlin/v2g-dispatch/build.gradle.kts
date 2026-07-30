dependencies {
    api(project(":v2g-tp"))

    // Needs every message-set module to map a V2GTP payload type to that set's decodeAny —
    // the same reason Vanaheimr.V2G.Exi.Dispatch references every message-set assembly.
    // `api`, because the decoded message is one of their types and callers have to name it.
    api(project(":exi-iso2"))
    api(project(":exi-iso20-common"))
    api(project(":exi-iso20-ac"))
    api(project(":exi-iso20-dc"))
    api(project(":exi-iso20-wpt"))
    api(project(":exi-iso20-acdp"))

    // Not exi-appprotocol: SAP shares payload id 0x8001 with the -2 messages and is decoded by the
    // handshake itself, never by payload type, so the dispatcher only ever *frames* a SAP payload.

    testImplementation(kotlin("test"))
    // Reads the shared vector corpus verbatim: the dispatcher is fed real cbV2G payloads rather
    // than hand-built ones, so a wrong payload-type mapping surfaces as a decode failure.
    testImplementation("com.google.code.gson:gson:2.11.0")
}
