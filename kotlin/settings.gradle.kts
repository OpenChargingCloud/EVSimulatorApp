rootProject.name = "v2g-exi-kotlin"

include("exi-runtime")
include("exi-appprotocol")
include("exi-iso2")
include("exi-iso20-common")

// The standalone W3C XMLDSig grammar. Not a message set: it exists only to reproduce the EXI
// fragment encoding of a SignedInfo built over xmldsig-core-schema.xsd *alone*, which is what
// Josev/EXIficient actually signs — distinct from the combined fragment grammar the -2 and -20
// codecs use for everything else.
include("exi-xmldsig")

include("exi-iso20-ac")
include("exi-iso20-dc")
include("exi-iso20-wpt")
include("exi-iso20-acdp")
include("exi-iso20-acderiec")
include("exi-iso20-acdersae")

include("v2g-tp")
include("v2g-dispatch")
include("v2g-metering")
// X.509 for the app: reading, the MO root store, chain validation. No EXI anywhere — it is the
// wallet's half, not the codec's, and the JVM brings its own X.509 and PKIX.
include("v2g-certificates")
// Private keys, and what may honestly be claimed about them (docs/CONCEPT.md §3.4). No certificates
// and no EXI: a key is a key whatever it ends up signing.
include("v2g-keystore")
// The scanned pairing code: payload format, warning classification, TOTP. No EXI — it is what
// stands in for plug-in, SLAC and SDP, and it happens before any session exists.
include("v2g-pairing")
// Test-only: the JSON-LD documents this back end produces, against the ones C# produces.
include("jsonld-agreement")
include("v2g-evcc")
