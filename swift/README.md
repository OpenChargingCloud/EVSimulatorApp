# Swift EXI codecs

The third back end of the shared generator, next to C# and Kotlin. Only `ExiRuntime` is
hand-written; every codec module is emitted from the XSDs and checked in.

> **The phone is the car. It is never the station, and that is the design.**
>
> This back end carries the vehicle side only — `Evcc2`, `Evcc20Base`/`Ac`/`Dc`, and no `Secc` of any
> kind. A phone in somebody's hand simulates a vehicle; the station role has its own home in this
> repository and it is C# on a Raspberry Pi (`pairing/EVSimulatorApp.Pi`, `SeccStation.cs`). So every
> `←SECC` row of the conformance repository's interop matrix — the ones where *their* EV drives *our*
> station — is out of scope here **by construction**, not by omission. A missing `Secc20Dc` is not an
> unported gap and no plan will add one: [`../docs/mobile-workplan.md`](../docs/mobile-workplan.md).

## Layout

| Target | Contents |
|---|---|
| `ExiRuntime` | Hand-written `BitReader` / `BitWriter` / `ExiPrimitives` / `ExiStringTable` — a port of the C# runtime. |
| `ExiAppProtocol` | Generated `SupportedAppProtocol` codec + vector test (encode vs `expectedHex`, and decode → re-encode). |
| `ExiIso2` | Generated ISO 15118-2 codec — 111 files — + vector test (decode → re-encode → `expectedHex`). |
| `ExiIso20Common` | Generated -20 CommonMessages codec, 160 files. |
| `ExiIso20DC` / `ExiIso20AC` / `ExiIso20ACDP` | The -20 power-transfer sets, 72 / 59 / 58 files. |
| `ExiIso20AcDerIec` / `ExiIso20AcDerSae` | The Amendment 1 DER sets, 86 / 107 files. Their corpora are of **mixed provenance** — see below. |
| `V2GTP` | Hand-written 8-byte transfer-protocol header. No dependencies at all. |
| `V2GDispatch` | Hand-written `MessageSet` / `V2GTPDispatcher` — payload type ↔ message set. Depends on every codec target. |
| `V2GMetering` | Verifies a station's signed meter reading. Held to the corpus the C# side generates, not to its own output. |
| `ExiXmlDsig` | Generated standalone W3C XMLDSig codec. Not a message set — it exists only to produce the octets a Plug & Charge signature is actually over. |
| `V2GKeystore` | Private keys and what may honestly be claimed about them (§3.4). No certificates, no EXI. |
| `V2GCertificates` | X.509 for the app: reading, the MO root store, chain validation. The only target that knows `swift-certificates` exists. |
| `V2GBridge` | The event stream the Capacitor plugin emits (B1): what a WebView receives while a session runs. |
| *(test-only)* `JsonLdAgreementTests` | The JSON-LD documents this back end produces, against the ones C# produces. |
| `V2GPairing` | The scanned pairing code: payload format, warning classification, TOTP. No dependencies at all — it runs before any session exists. |
| `V2GEvcc` | Hand-written EVCC state machines (ISO 15118-2 **and** -20, AC and DC, EIM **and** Plug & Charge) + `V2GTPStream` framing + the SAP handshake. Held to recorded sessions — see below. |

`V2GTP` and `V2GDispatch` are split for the reason `kotlin/` splits them: reading a frame's type and
length pulls in nothing, while resolving that type to a decoder needs every message set.

Three shapes differ from the other back ends there, and none changes a byte:

* `V2GTP.header(...)` **returns** the eight bytes where C# and Kotlin write into a caller-supplied
  array — a Swift array is a value type, so the caller would not see the mutation.
* `tryReadHeader`'s `bool` + two `out` parameters become an optional `V2GTPHeader`, and the
  dispatcher's four-way `bool` + `out` becomes a `V2GTPDecodeResult` enum. The distinction that
  matters survives exactly: a *framing* problem is a value, while malformed EXI inside a recognised
  set throws out of the codec, the same as calling `decodeAny` directly.
* Payload-type constants are plain `UInt16` literals. Kotlin needs `val` rather than `const val`
  because it has no `UShort` literal; Swift has one, so the wire width costs nothing here.

`0x8001` is **one id shared by SAP and the DIN/-2 messages** — they are told apart by session phase,
not payload type. The dispatcher therefore only ever *frames* SAP and never decodes it, which is why
`V2GDispatch` does not depend on `ExiAppProtocol`. An earlier distinct `0x8000` here was a real
wire-conformance bug, caught only by a live interop run against Josev.

## The EVCC state machines — and how a state machine gets checked at all

`V2GEvcc` is the vehicle side of a session: `V2GTPStream`, `SapHandshake`, `SessionContext`, and
`Evcc2` / `Evcc20Base`+`Ac`+`Dc`. Hand-written, ported from C#.

Every other gate in this package compares *bytes for a message*. A state machine has no such corpus —
there is no reference EVCC, and the question is not what one message encodes to but **which messages,
in what order, carrying what**. "It ran to completion" answers none of that: a session that skips a
phase, or sends the wrong charging profile, completes just as happily.

So the check is the same construction one layer up. The C# side records whole sessions frame by frame
into `Vectors/Session.*.trace.json` — SAP handshake to SessionStop — and `EvccTraceTests` replays the
recorded *responses* into this implementation and requires the *requests* it emits to be byte-identical,
V2GTP headers included. The file is read out of the submodule, exactly as `V2GMeteringTests` reads the
meter vectors, so the two cannot drift.

Two things it does not give you, both worth knowing before trusting it:

* **It cannot catch a bug the C# EVCC has too.** C# is a defensible reference because it is the
  implementation that earned the live-interop conformance fixes against Josev — "agrees with the one
  that has actually talked to somebody else" is a weaker claim than conformance, and the honest one.
* **Contract provisioning, tariff verification and pause/resume are still unported**, and named as
  missing in the type comments rather than quietly absent. Plug & Charge **is** ported — see below.

Verified by mutation, four of them, all caught:

| Mutation | Caught at |
|---|---|
| -2: one byte of the EVCCID | exchange 1 |
| -20: `"EVCC01"` → `"EVCC02"` | exchange 1 |
| -20: the AC charge-parameter discovery sent on the **DC** message set | exchange 7, byte 3 |
| -20: the pinned clock moved by one second | exchange 1, EXI payload offset 12 |

Two Swift-specific notes, and the first is the interesting one:

* **The three -20 `MessageHeaderType`s are ambiguous by bare name** and have to be written
  module-qualified — `ExiIso20Common.MessageHeaderType` and so on. That is not Swift being awkward;
  it is the situation stated out loud. The -20 sets are self-contained schemas that happen to embed
  identical types, and C# hides the same fact behind `using` aliases. `SessionContext` is where the
  three are reconciled, in all three back ends.
* **The transport is a two-method `V2GByteStream` protocol**, not `Foundation.Stream`: these codecs
  are Foundation-free and one protocol keeps them so. `read` may return short, as a socket does.

### Plug & Charge, and the one check that is not obvious

`ExiXmlDsig` is a codec target with no message set behind it. It exists because the `SignedInfo` that
travels **in** a signed message is encoded under its own message set's grammar, while the octets
actually **signed** are the same `SignedInfo` encoded under the *standalone* W3C xmldsig grammar —
different bytes for the same structure, because the fragment selector is sized over a different set of
global elements. That is the form a live Josev peer produces and accepts.

Signing is CryptoKit `P256.Signing`, whose `rawRepresentation` is already the raw `r‖s` the field
wants — no DER conversion anywhere, and the field is 64 bytes so a DER signature would not fit even
by accident. `PncEvccOptions` reads the **eMAID from the contract certificate's Common Name**, as C#
and Kotlin do. (An earlier draft took it as a value and said this package had no X.509 parser and
wanted none; `V2GCertificates` is that parser, and the reasoning is below.)

Signed sessions are compared by substituting the recorded signature and verifying the produced one
separately (`SignedFrame.swift`). Which leaves a gap that took a moment to see:

> The signature bytes are substituted away before the comparison, and a produced signature is verified
> using **this port's own** `standaloneOctets`. So a Swift standalone encoder that disagreed with C#'s
> would sign over X, verify over X, and pass every check here — while producing a signature no other
> implementation on earth accepts.

`testTheRecordedSignatureVerifiesUnderThisPortsOwnEncoder` closes it: the **recorded** signature was
made by C# over C#'s octets, so it verifies here only if the two encoders agree. Confirmed by
mutation — corrupting only the standalone mapping leaves both Plug & Charge replay tests green and
fails exactly that one test, in Swift and Kotlin alike.

## Certificates: reading, trusting, validating

`V2GCertificates` is the app's X.509. It is also the only target that knows `swift-certificates`
exists — the dependency is one target wide, so vendoring or replacing it costs one file.

**Why a library and not our own ASN.1.** C# uses `System.Security.Cryptography.X509Certificates` and
Kotlin `java.security.cert`; both take their platform's implementation. Writing our own only here
would make Swift the outlier in the risky direction, and the direction settles it: a contract chain
arrives from a **scanned QR code**, so it is untrusted input reaching a parser on a phone.
Certificate parsers are a classic vulnerability class. It brings `swift-asn1` and `swift-crypto`
with it — three packages, all Apple's, all Apache-2.0, bounded to the current minor with
`Package.resolved` as the actual pin.

### The eMAID rule, and how it was found

`eMAIDType` is 14–15 characters (`V2G_CI_MsgDataTypes.xsd`). Nothing enforced it: the generated codec
does not apply string-length facets, reasonably for an encoder that assumes schema-valid input, and
so nothing below the state machine was ever going to. Giving Swift a reader that checked the length
the schema states promptly refused **this repository's own corpus certificate** — a 19-character
Common Name that had been travelling in a recorded Plug & Charge session, accepted by all three back
ends. A second one turned up in the -2 PnC loopback test at 16 characters. The check now lives in all
three, before the session opens rather than four exchanges in; note that it is a **-2** rule, since
-20 never sends the eMAID from the certificate at all.

### The trust store, and what a dialog can honestly say

The app keeps MO roots and takes new ones by QR scan, TOFU-style with a confirmation. Four verdicts,
and the distinctions are the point:

| Verdict | When | What the dialog may say |
|---|---|---|
| `alreadyTrusted` | byte-identical | nothing; it is a no-op |
| `new` | no stored root with this subject | "unknown — you decide" |
| `renewal` | same subject **and same key** | "the same CA, re-issued" |
| `vouchedByTrustedRoot` | signed by a stored root, signature checked | "the root you trust vouches for this one" |
| `replacementUnderKnownName` | same subject, different key, nobody vouches | as loudly as `new`, never as a refresh |

A root's subject is written by whoever made it, so anyone can mint one calling itself "Hubject MO
Root CA". Only the **fingerprint** binds; the dialog may show the name and must not let it convince.

Vouching is the friendly rotation — a CA introducing its successor with the key it still holds — and
`testAStolenRootKeyProducesAnEquallyValidVouching` pins what it is worth: whoever holds that key can
vouch for anything, and it is indistinguishable from an honest rotation because cryptographically it
*is* one. Conversely a CA that **lost** its key cannot use this path, so its legitimate rotation
appears as the loud `replacementUnderKnownName`. Both directions are the honest ones, and neither is
proof.

### Chain validation: two questions, deliberately not one

**Is the chain sound?** Path, signatures, dates, CA flags, path length — RFC 5280's question,
answered by `swift-certificates`. One right answer, no user opinion.

**Does the leaf match the ISO 15118 profile?** Ours, and PKIX has no view on it. A contract
certificate carrying `serverAuth` builds a perfect path and is simply a credential that could also
impersonate a station. Reported, not rejected — fold the two together and the user is told "invalid"
about a chain that is entirely valid and merely wrong.

Held to `Vectors/Certificate.chain.vectors.json`, generated from `WWCP_ISO15118_PKI` including its
evil-certificate factory — which exists to defeat validators that stop at the path maths, and says so
in its own comment. That corpus earned its place immediately: an earlier draft treated a shuffled
bundle as "a wire problem, not a trust problem" and validated whatever came first, which meant it
cheerfully trusted a **sub-CA** as a contract credential. The order is what states *which* certificate
is being presented, so a bundle that does not link up gets `bundleDoesNotLinkUp` and no findings at
all — nothing said about a guessed leaf is worth saying.

### Revocation: three answers, not two

``V2GRevocationChecker`` answers `notRevoked`, `revoked(on:reason:)` or **`unknown(why:)`**, and the third is the
whole reason the type exists. "Not on the list" and "no usable list" look identical to a naive check
and are not the same thing at all — the second is the classic soft-fail hole, where whoever wants a
revoked credential accepted simply arranges for the list to be unavailable. A boolean cannot express
that difference, so it is not a boolean. What to *do* about `unknown` is a policy the app owns.

A CRL is attacker-supplied input in exactly the way a chain is, and it fails in a direction that is
easy to miss: a forged list need not make false claims, it only needs to be **empty**. So before its
contents count for anything, three things are checked, and each failure yields `unknown` rather than
`notRevoked`:

* the signature verifies under the issuing CA;
* the list is current — a stale CRL is a snapshot of the past, and believing it is how a revocation
  gets outrun by waiting;
* its issuer is the certificate's issuer — a genuine CRL from another CA says nothing about this
  certificate, and reading "not listed" off it would be a straightforward bypass.

Confirmed by mutation: unhooking the signature check makes a tampered CRL parse cleanly and report
`revoked` — the demonstration that parsing without verifying is worth nothing.

`swift-certificates` models OCSP and not CRLs, so the list is parsed here from nodes `swift-asn1`
has already decoded — it does the DER work, bounds and tags and lengths included, and `V2GCrl` only
names the fields. That is the same relationship this module has to `swift-certificates` for
extensions, and a different thing from hand-rolling a parser. Only what the question needs is read:
issuer, validity window, revoked serials with dates and reasons; the rest of a CRL is skipped rather
than half-understood. The bytes the signature covers come from the parsed node rather than a
re-serialisation, because a value that round-trips through any encoder is not guaranteed to come
back identical.

Not here: **fetching**. Where a CRL comes from is the app's business, and a check that reaches the
network cannot be run offline — which means nobody runs it. And **OCSP**, which ISO 15118-20 staples
into the TLS handshake for the *station's* chain; that is the transport's business, and a contract
certificate is a separate question that the ISO 15118 PKI answers with CRLs.

### Keys, and what may honestly be claimed about them

``V2GKeystore`` is the key half of the wallet. It holds no certificates and no EXI: a key is a key
whatever it ends up signing, and the module deciding how a key is protected should not also be the
one parsing untrusted input.

`docs/CONCEPT.md` §3.4 gives the constraint — the iOS Secure Enclave holds P-256 and nothing else,
Android's StrongBox/TEE P-256 and RSA — so a -2 contract key can be hardware-backed and a -20 one
cannot, on either platform. That table is the easy half.

**The hard half is that a secure-element key has no bytes to hand over.** Both `PncEvccOptions` used
to take a raw private key, which ruled out hardware backing for *every* credential regardless of
curve. Signing now goes through a `V2GSigner` — "give me a signature", not "give me the key" — which
is the part §3.4 means by designing around the asymmetry "from the start rather than discovering it
at the first keygen": the curve table can be added later, this interface cannot.

Protection is a value rather than an implementation detail, so it can be displayed, asserted, and got
wrong loudly. What the tests pin:

* only P-256 can be hardware-backed, and a -20 curve is refused **even on a device that has a secure
  element** — with a reason naming the curve, because a disabled control explains nothing;
* "no secure element on this device" and "this curve, never" are different sentences, since one is
  about today and the other is forever;
* a software signer cannot describe itself as hardware-backed, and the reason given is that it *holds
  bytes* — not the curve, since P-256 can live in an enclave and simply not in an object handed one;
* software disclosures never claim hardware protection, asserted on the literal string. Both now open
  with the word "software": an earlier version said only "not by separate hardware", which is
  accurate and leaves the reader to work out the consequence — and §3.4's whole point is that a user
  should not have to.

Gating on **use** rather than storage is a separate flag and reads as an extra sentence, because a
key can be biometrically gated and still be software.

Both ports also build **PKCS#10 certificate signing requests**, and a CSR is the first thing that
really needs the signer shape: it proves possession of a private key by being signed with it, so a
secure-element key must be able to produce one without its bytes ever appearing.

Two things about that are worth knowing.

**The signature form changes.** `V2GSigner` returns raw `r‖s`, because that is what ISO 15118 puts on
the wire and the field is sized for it. PKCS#10 and X.509 want the DER form, `SEQUENCE { INTEGER r,
INTEGER s }`. A request carrying a raw signature is not malformed in any way a parser notices — it is
simply refused by whichever CA receives it, which is a miserable place to find out.

**`swift-certificates` cannot quite do this alone. It offers an initialiser taking a pre-computed
signature — exactly the external-signer shape — but the bytes that signature must cover are
`@usableFromInline internal`, so there is no public way to ask for them. The way through is to build
the request once with a placeholder signature, serialise it, and take the first element of the outer
`SEQUENCE`, which by PKCS#10's own definition *is* those bytes.**

Each suite verifies its own request with the library that produced it, which is weaker than it looks.
So both were also checked once against **openssl**, which neither built nor parsed anything here:
`Certificate request self-signature verify OK`, for a request from each language.

Not done: the platform binding itself — Keychain and Android Keystore need a device. The model above
admits them; nothing here pretends they exist.

WPT is a *recognised* payload type with no Swift codec behind it, and the dispatcher says so rather
than reporting an unknown type — which would suggest the frame was malformed.

Each -20 set is its own target, as in `kotlin/`: they are independent grammars that happen to
share `CommonTypes`, and each embeds its own copy of the XMLDSig schema — which is why the same
element lands on a different event code in each.

Every generated type is a **class**: bases and abstract types subclassable, everything else final.
Not a preference — a struct that *contains* a class-typed field loses its synthesised `Equatable`
and `Sendable`, and containment crosses that boundary throughout -2, so the mixed model does not
hold. Neither conformance is generated: a static `==` would compare a derived value by its base's
fields, and `@unchecked Sendable` on a type with `var` properties is a promise the type does not
keep. Kotlin's hierarchy members have only identity equality for the same reason.

```bash
swift test
```

One file per type, as in `kotlin/`. That is mandatory rather than tidy: the -20 sets run to tens
of thousands of lines and Swift's type checker degrades sharply on single files of that size
(`docs/CONCEPT.md` §3.7).

## Regenerating the codecs

Run from the repository root. `--out` is the target's source directory; the driver removes
generated files it no longer produces and leaves hand-written ones alone, identifying its own by
their first line.

```bash
dotnet run --project tools/EVSimulatorApp.Codegen -c Release -- \
  --xsd libs/WWCP_ISO15118/WWCP_ISO15118_EXI/Schemas/V2G_CI_AppProtocol.xsd \
  --out swift/Sources/ExiAppProtocol \
  --lang swift --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

ISO 15118-2 — note the schema order, which decides declaration order in the output:

```bash
dotnet run --project tools/EVSimulatorApp.Codegen -c Release -- \
  --xsd "libs/WWCP_ISO15118/WWCP_ISO15118_2/Schemas/V2G_CI_MsgDef.xsd;libs/WWCP_ISO15118/WWCP_ISO15118_2/Schemas/V2G_CI_MsgBody.xsd;libs/WWCP_ISO15118/WWCP_ISO15118_2/Schemas/V2G_CI_MsgDataTypes.xsd;libs/WWCP_ISO15118/WWCP_ISO15118_2/Schemas/V2G_CI_MsgHeader.xsd;libs/WWCP_ISO15118/WWCP_ISO15118_2/Schemas/xmldsig-core-schema.xsd" \
  --out swift/Sources/ExiIso2 \
  --lang swift --namespace cloud.charging.v2g.iso2 --codec Iso15118_2Codec
```

The standalone W3C XMLDSig grammar — not a message set, and the only target here generated from a
single schema that no session ever carries. See the Plug & Charge section for why it exists:

```bash
dotnet run --project tools/EVSimulatorApp.Codegen -c Release -- \
  --xsd libs/WWCP_ISO15118/WWCP_ISO15118_XMLDSig/Schemas/xmldsig-core-schema.xsd \
  --out swift/Sources/ExiXmlDsig \
  --lang swift --namespace ExiXmlDsig --codec XmlDsigCodec --fragments SignedInfo
```

**The order of `--xsd` matters** once a set has more than one schema: it decides declaration
order, so the same files in a different order regenerate output that differs everywhere while
encoding the same bytes. Regenerating without changing the emitter must leave every file
byte-identical — the cheapest check that a refactor was behaviour-neutral.

## How the Swift back end differs

Three shapes differ from Kotlin. None changes a byte.

* **The codec object is an `enum`** with static members — Swift's idiom for a namespace that
  cannot be instantiated. Message types are classes (see Layout), as Kotlin's hierarchy members
  are.
* **Every decoder is `throws`; encoders are not.** Swift has no unchecked exceptions, so the
  distinction the other back ends get for free lives in the signatures. A decoder faces bytes from
  the network, so malformed input is a recoverable `ExiError`; an encoder is driven by our own
  values, so a bad one is a `precondition`.
* **`--namespace` has nowhere to go.** A Swift module is defined by its SwiftPM target, not
  declared in source, so the schema's target namespace is recorded in the file header only.

One runtime divergence worth knowing: `BitWriter` owns its buffer. C# and Kotlin write into a
caller-supplied array at an offset, which Swift cannot express — arrays are value types, so the
caller would not observe the mutation.

## Coverage

The back end models everything ISO 15118-2 uses: inheritance and abstract types, attributes
(required and optional), substitution groups, `xs:choice`, `xs:simpleContent`, `xs:any` wildcards,
opaque XMLDSig placeholders, and repeating children in every position the grammar puts them.

The -20 sets add inline choices and lists followed by a further particle; both are modelled, so
CommonMessages, DC, AC and ACDP generate as well.

Fragment codecs (`--fragments`) are implemented: the EXI header, the element's fragment-grammar
event code, its content, End Fragment, and no document or body wrapper — the bytes XMLDSig digests.
The selector is sized by the whole schema set, so `SignedInfo` lands on 135/8 bits under AC and
230/9 under CommonMessages. Different octets, therefore different signed bytes, which is why each
set carries its own fragment codec and its own signing helper.

**WPT is refused on principle rather than for lack of work**:
`WPT_LF_TransmitterDataType` is the self-loop list shape for which cbexigen's own encoder cannot
represent even the schema's required minimum, so there is no oracle to check an implementation
against. Emitting something plausible there would produce bytes nothing has ever validated. That is deliberate: each construct lands with its own vectors
rather than being guessed at, and a silent almost-right encoder is the one outcome this project
cannot afford.

## Signing

`ExiIso2/V2GSignature.swift` is hand-written and sits *beside* the generated code, which the
codegen driver leaves alone — it only removes files whose first line marks them as generated.

The detail that decides interop: ISO 15118-2 puts the **raw `r‖s` pair** on the wire, 32 + 32
bytes, not ASN.1/DER. CryptoKit exposes exactly that as `rawRepresentation`, so Swift needs no
equivalent of the JCA's `SHA256withECDSAinP1363Format`. It is still the easiest thing to get wrong,
and the failure is quiet — a DER signature verifies against itself and is rejected by every
conforming peer. The tests pin the 64-byte length and require a DER blob to be refused.

The digest is taken over the **fragment**, never over a document-wrapped encoding of the same
content. That is the other silent way to produce a locally-consistent, universally-rejected
signature, so it has its own test.

For -20 the same helper appears in **every** set — CommonMessages, AC, DC and both DER sets — and
that repetition is the point rather than an oversight. Each set embeds its own copy of the XMLDSig
schema and sizes the fragment selector over the whole set, so the same `SignedInfo` lands on 230/9
bits under CommonMessages, 135/8 under AC and 129/8 under DC. Borrowing another set's helper would
sign octets no peer asked for. `V2GDispatchTests/FragmentDivergenceTests` is the only place that
sees several sets at once, and it checks exactly this — so collapsing the helpers into one fails
there instead of on a charger.

-20 uses SHA-512 and the raw `r‖s` pair over secp521r1: 66 + 66 = 132 bytes, pinned the same way.

**Ed448 is implemented**, via `V2GEd448` — the -20 suite's second algorithm (RFC 8032, 114 bytes).
CryptoKit does not have the curve at all: not an unregistered provider as on the JVM where
BouncyCastle supplies it, but a missing primitive, so it needs a bundled implementation.

**Vendored, not depended upon.** `Sources/CGoldilocks` holds libgoldilocks checked in verbatim —
see [`PROVENANCE.md`](Sources/CGoldilocks/PROVENANCE.md) there for the chain it came down and the
licence notices that must travel with it — and `Sources/V2GEd448` is our own 115-line surface over
it. `swift build` needs no network, and no third party's release cadence sits between us and the
code that makes our signatures. ~81 KB of machine code, against megabytes for OpenSSL. See
`docs/CONCEPT.md` §3.3 and [`SPIKE-ed448.md`](SPIKE-ed448.md) for how it was chosen.

Pure Ed448 with an **empty context**, matching `#eddsa-ed448`. RFC 9231 §2.3.12 lists the prehashed
`#eddsa-ed448ph` under its own identifier; it is a different algorithm and is refused by name.
Pure EdDSA signs the fragment octets directly — SHAKE256 inside the algorithm replaces the external
pre-hash, so there is no separate SHA-512 step as on the P-521 path.

An Ed448 key cannot live in the Secure Enclave, which holds P-256 only. `Ed448PrivateKey` is a
software key wherever it is stored, and `docs/CONCEPT.md` §3.4 says the UI has to be honest about
that rather than implying hardware protection.

### Which algorithm, decided by the message

`SignedInfo` carries the algorithm in `SignatureMethod/@Algorithm`, so a peer *states* its choice.
Every entry point reads that declaration and refuses to act against it:

| Situation | Result |
|---|---|
| declared algorithm matches the key | signs / verifies |
| declared `#ecdsa-sha512`, Ed448 key offered (or the reverse) | `algorithmMismatch(declared:attempted:)` |
| declared `#eddsa-ed448ph`, or anything unknown | `unsupportedAlgorithm(String)`, carrying the identifier verbatim |

This replaced dispatching on the signature *length*, which was a guess at something we had been
told. The failure it prevents is the familiar quiet one: signing under one algorithm while the
message declares another produces a signature that verifies locally and is rejected everywhere else.

`V2GSignature.algorithm(of:)` exposes the same decision, so a caller can route an incoming message
before choosing a key.

### How the two halves are checked

`V2GEd448Tests` holds the *primitive* to RFC 8032 §7.4 — nine published vectors, byte for byte, and
Ed448 is deterministic so that is an equality check rather than a round trip. It needs no codec, and
it is the acceptance test any replacement of the vendored C has to pass.

`Ed448IntegrationTests` joins the halves, being the only target that imports both `V2GEd448` and
`ExiIso20Common`: `V2GSignature.sign` must equal a hand-rolled `Ed448.sign` over the *fragment*.
Neither side can check that alone — the vectors do not know what octets we feed the primitive, and
`Iso20CommonV2GSignatureTests` cannot see inside it.

`V2GDispatchTests/FragmentDivergenceTests` then proves the per-set duplication in the currency that
matters: one seed, the same logical `SignedInfo`, three sets, three *different* signatures — each
verifying only in its own set. Collapsing the five helpers into one fails there rather than in the
field.

## How these codecs are checked

`kotlin/README.md` describes three independent gates. Swift has all three — the first back end in
this project that demonstrably does, since gate 2 had no implementation before the Swift port
needed it:

1. **Vectors** — `expectedHex` from EVerest's libcbv2g at a pinned commit, read straight out of
   the submodule, the same corpus the C# and Kotlin suites use. AppProtocol encodes and compares
   (17); the rest decode and re-encode — ISO 15118-2 (39), -20 CommonMessages (26), DC (16),
   AC (10), ACDP (8), AC_DER_IEC (16), AC_DER_SAE (16). **148 vectors, all byte-exact.** Every one
   exercises the decoder too, and none of them *can* catch a bug mirrored in both directions — see
   gate 2. WPT's 12 are not covered, because that set is refused.

   **The two AC DER corpora do not mean what the others do.** cbexigen does not generate the
   Amendment 1 DER schemas, so for any message using a DER member no reference encoder exists. Six
   of the sixteen carry cbV2G's own bytes — plain-AC messages that encode identically under the DER
   grammar, measured rather than assumed — and those are real conformance evidence. The other ten
   are the C# back end's output: passing them shows the Swift and C# emitters read one schema the
   same way, and says nothing about what a conforming peer would accept. The tests keep the halves
   apart and assert the split, so the anchored six cannot quietly become self-generated.
2. **Cross-emitter comparison** — `CrossEmitterComparisonTests` in the .NET suite reduces each
   back end's output to the ordered wire operations every generated function performs, and
   requires Swift and C# to agree. This is what rules out the mirrored bug, which the vectors
   cannot: they pin the *encoder*, and the decoder is then checked by round-tripping through that
   same encoder.

   It covers **seven whole sets** — -2, CommonMessages, DC, AC, ACDP, AC_DER_IEC and AC_DER_SAE —
   and they all agree. Kotlin is now compared across the same seven plus WPT, so all three back
   ends are equally covered.

   Alongside it, `EveryBackEndRoutesEachDocumentIndexToTheSameMessage` compares each set's
   document-index → message-decoder table across the three back ends. That is a separate claim
   from the operation sequences — those say each codec reads the same bits in the same order, this
   says the dispatcher hands index 4 to the same message — and nothing was making it before.

   It has found **four real bugs**, and two of them make the case better than any argument:

   * `SalesTariffEntryType` wrote a 1-bit run selector where cbV2G writes 2, because an optional
     *list* was treated as terminating the run rather than belonging to it. That codec compiled and
     round-tripped against itself perfectly. Every bit after the selector was wrong.
   * An `xs:choice` decoder never read the element EE its encoder writes, so a real stream would
     have desynchronised by one bit after every choice. Here encoder and decoder *disagreed*, so a
     round trip would have caught it — had any vector exercised that construct. None does. The two
     blind spots compound: vectors miss what both halves get wrong together, and equally miss what
     no vector happens to cover.

   The other two: a required complex child written with a value-start plus a child EE (AppProtocol
   has no such child, so the vectors stayed green), and — on the gate's very first run — enums
   decoded through a *generated* wrapper where C# and Kotlin read the index inline. The operation
   sequences differed even though the bytes did not; the fix moved the fallible lookup into the
   runtime (`ExiRuntime.exiEnum`) and left the read inline. The comparison was right that a wrapper
   there is a structural difference worth removing.
3. **Structure**, in the .NET suite (`SwiftEmitterSplitTests`) — one file per type, nothing
   declared twice, every codec call resolving to a declaration, the runtime imported exactly where
   it is used, and unmodelled constructs failing loudly.

Bit-exactness and well-formedness are separate concerns: a byte-level diff says nothing about
whether the emitted Swift *compiles*. That check runs nowhere but `swift test`.


## `V2GPairing`: the code on the display

The same module as Kotlin's `v2g-pairing`, held to the same two C#-generated corpora
(`Pairing.payload.vectors.json`, `Pairing.totp.vectors.json`), and the long version of why each
refusal exists is in `kotlin/README.md`. Three things differ here.

**No dependencies at all, not even `swift-certificates`.** This is the first code to touch input from
outside, it runs before any session or key exists, and it should be possible to reason about it
without reasoning about anything else.

**`percentEncodedFragment`, never `fragment`.** Decoding has to happen per value, after the fragment
is split on `&` and `=`. Decoding first would let an encoded `%26` inside a value become a real
separator and smuggle in a parameter — the classic double-decode bug. `URLComponents` offers both
properties and the wrong one is shorter to type.

**The no-resolution rule is asserted by consequence rather than mechanically.** Kotlin can install a
resolver that fails any test causing a lookup; Foundation has no equivalent hook. What is asserted
instead is that `localhost` is judged **public** — it is a name, and whether it maps to 127.0.0.1 on
this device is not knowable without asking. Any implementation that resolved would get that one wrong
in the reassuring direction.

Two things the Swift port checks that the others do not, both because Swift makes them possible to
get wrong in ways C# and Kotlin do not:

* **Slot arithmetic below the epoch.** `Int64(someDouble)` truncates towards zero, which merges the
  first slot on each side of 1970. No realistic clock reaches it; a `-1` in an offset calculation
  does. `.rounded(.down)` throughout.
* **The constant-time comparison against an empty comparand.** C#'s `b[i % Math.Max(b.Length, 1)]`
  throws in that case and Swift's traps, and a generated code is never empty — which is a reason it
  has not happened, not a reason it cannot.


## The JSON-LD form

Every codec target carries a second generated (de)serializer beside its wire codec:
`<Set>CodecJson.toJSON(Any)` and `.parseJSON(JsonValue)`, emitted from the same schema plan in the
same pass, and held to `JsonLd.documents.json` character for character. The long version is in
`kotlin/README.md`; three things differ here.

**Everything throws.** Swift has no unchecked exceptions, so serializing throws as well as parsing —
`toJSON` ends in a dispatch that can fail on a type from another message set, and that has to be
declared all the way up.

**Initialisers take labels**, so the parser emits `X(header: …, eVCCID: …)`. The labels are the Swift
property names, which are *not* the JSON property names: one is `eVCCID`, the other `evccid`. Keeping
them apart is why the naming rule derives from the schema plan rather than from a language.

**The JSON tree is hand-written**, in `ExiRuntime`. `JSONSerialization` reads an object into an
unordered `Dictionary`, and member order is part of a format that is compared as text.

One trap worth recording, found by every document failing at offset 1 with "an object key must be a
string": `" \t\r\n".contains(c)` does not test for whitespace. A `Character` is a grapheme cluster,
so CR+LF in that literal combine into a single character — the string holds three, and a lone newline
is not one of them. The whitespace set is a `Set<Character>`.

WPT is absent for the reason it has no codec target either: the back end refuses
`WPT_LF_TransmitterDataType`, whose `maxOccurs=255`-with-a-follower shape has no working reference
encoder.
