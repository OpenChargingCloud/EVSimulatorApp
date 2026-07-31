# Kotlin EXI codecs

## Layout

Each module's generated code is **one file per type**: the data class and its
`encode…` / `decode…` pair live together in `<TypeName>.kt`, as top-level `internal`
functions. `<Codec>.kt` holds only the public face — `encode`, `decodeAny` and the fragment
codecs — because that is what callers use and Kotlin cannot spread an `object` across files.

That is not cosmetic. As a single file a message set reached ~1 MB, which exhausted the Kotlin
compiler's heap, put every method of the set into one class file (Android counts those against a
DEX file's 64k method limit all at once), and made the smallest schema change recompile everything.

| Module | Contents |
|---|---|
| `exi-runtime` | Hand-written `BitReader` / `BitWriter` / `ExiPrimitives` — a port of the C# runtime. |
| `exi-appprotocol` | Generated `SupportedAppProtocol` codec + vector test (encode vs `expectedHex`). |
| `exi-iso2` | Generated ISO 15118-2 codec + vector test (decode → re-encode → `expectedHex`). |
| `exi-iso20-common` | Generated ISO 15118-20 CommonMessages codec + vector test (same loop). |
| `exi-iso20-ac` | Generated ISO 15118-20 AC codec + vector test (same loop). |
| `exi-iso20-dc` | Generated ISO 15118-20 DC codec + vector test (same loop). |
| `exi-iso20-wpt` | Generated ISO 15118-20 WPT codec + vector test (same loop). |
| `exi-iso20-acdp` | Generated ISO 15118-20 ACDP codec + vector test (same loop). |
| `exi-iso20-acderiec` | Generated ISO 15118-20 AC_DER_IEC codec + vector test. Its corpus is **mixed provenance** — see below. |
| `exi-iso20-acdersae` | Generated ISO 15118-20 AC_DER_SAE codec + vector test. Same. |
| `v2g-tp` | Hand-written `V2GTP` — the 8-byte transfer-protocol header. No dependencies at all. |
| `v2g-dispatch` | Hand-written `MessageSet` / `V2GTPDispatcher` — payload type ↔ message set. Depends on every codec module. |
| `v2g-metering` | Verifies a station's signed meter reading. Held to the corpus the C# side generates, not to its own output. |
| `exi-xmldsig` | Generated standalone W3C XMLDSig codec. Not a message set — it exists only to produce the octets a Plug & Charge signature is actually over. |
| `v2g-evcc` | Hand-written EVCC state machines (ISO 15118-2 **and** -20, AC and DC, EIM **and** Plug & Charge) + `V2GTPStream` framing + the SAP handshake. Held to recorded sessions — see below. |

```bash
gradle -p kotlin test --rerun-tasks
```

`--rerun-tasks` matters: Gradle caches the `test` task and will report `BUILD SUCCESSFUL`
without having run anything.

## Regenerating the codecs

```bash
pwsh kotlin/regenerate.ps1
```

That script is the source of truth for how every checked-in codec was produced — schema order,
output paths and fragment element lists. The commands below spell out what it does; prefer the
script, because getting any of those three wrong is silent rather than loud.

The generated files are checked in. `--out` is the **package directory**: the Kotlin back end
emits one file per type, plus one for the codec object. The driver deletes generated files it no
longer produces, so a renamed or dropped type cannot leave a stale declaration behind; files it
did not write — `V2GSignature.kt` lives in the same directories — are identified by their first
line and left alone. Run these from the repository root:

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out kotlin/exi-appprotocol/src/main/kotlin/cloud/charging/v2g/appprotocol \
  --lang kotlin --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDef.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgBody.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDataTypes.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgHeader.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/xmldsig-core-schema.xsd" \
  --out kotlin/exi-iso2/src/main/kotlin/cloud/charging/v2g/iso2 \
  --lang kotlin --namespace cloud.charging.v2g.iso2 --codec Iso15118_2Codec
```

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonMessages.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonTypes.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/xmldsig-core-schema.xsd" \
  --out kotlin/exi-iso20-common/src/main/kotlin/cloud/charging/v2g/iso20/common \
  --lang kotlin --namespace cloud.charging.v2g.iso20.common --codec CommonMessagesCodec
```

AC and DC follow the same pattern — swap `CommonMessages` for `AC` / `DC` in the schema names,
the output path (`exi-iso20-ac` / `exi-iso20-dc`), the package (`…iso20.ac` / `…iso20.dc`) and the
codec (`ACCodec` / `DCCodec`).

`--fragments` names the signable elements that get an EXI **fragment** codec — the encoding
XMLDSig digests: EXI header, the element's fragment-grammar event code, its content, End Fragment,
with no document or body wrapper. The lists mirror `<ExiFragmentElements>` in the matching C#
project. WPT and ACDP have none.

## Signing

`exi-iso2` carries a hand-written `V2GSignature`, the ISO 15118-2 half of §7.9: SHA-256 over a
signed element's EXI fragment goes into a `SignedInfo` Reference, and the `SignedInfo` fragment is
itself ECDSA-P256 signed.

The one detail that decides interop is the signature **format**. ISO 15118-2 puts the raw `r‖s`
pair (32 + 32 bytes) on the wire, while the JCA hands out ASN.1/DER by default — hence
`SHA256withECDSAinP1363Format` rather than `SHA256withECDSA`. Getting that wrong produces a
signature that verifies against itself and is rejected by every conforming peer, which is a
miserable thing to debug on a charger. `V2GSignatureTest` asserts the 64-byte length for exactly
that reason; swapping the algorithm name back to the DER variant fails it.

`exi-iso20-common` carries the -20 counterpart: SHA-512 digests and either algorithm of the -20
signature suite — ECDSA over P-521 (raw `r‖s`, 132 bytes) or **Ed448** (RFC 8032, 114 bytes). Ed448
is why that module depends on BouncyCastle: the JDK has no Ed448 at all, not merely an unregistered
provider.

Two parameters there decide interop and neither is visible in a round trip, so both are pinned
deliberately:

* the ECDSA **format** — `SHA512withECDSAinP1363Format`, asserted by the 132-byte length;
* the Ed448 **context string**, which we set empty. Sign and verify share it, so every other test
  passes with a wrong one — checked, and it does.

  This README used to say RFC 8032 "fixes" the context to empty for plain Ed448. **It does not.**
  RFC 8032 §5.2 gives Ed448 a context parameter of up to 255 octets, and §7.4's own corpus contains
  a `"foo"`-context vector sharing its key and message with the empty-context one directly above —
  same inputs, entirely different signature. RFC 9231 §2.3.12, which is where ISO 15118-20's
  `#eddsa-ed448` identifier comes from, is silent on the matter. So empty is a **choice this
  implementation makes**, conventional and near-certainly right, but resting on the ISO 15118-20
  text rather than on either RFC. `Ed448RfcVectorTest` now pins both halves: that the signer
  reproduces §7.4 byte for byte, and that `signEd448` is that signer with an empty context and
  nothing else in between.

`exi-iso20-ac`, `exi-iso20-dc`, `exi-iso20-acderiec` and `exi-iso20-acdersae` carry the same helper
again, over their own signable element (`AC_`/`DC_ChargeParameterDiscoveryRes`; the DER schemas keep
AC's message roots and only add substitution members).

The repetition is not laziness. Each -20 message set embeds its own copy of the XMLDSig schema, and
the fragment grammar's element selector is sized by the whole set, so the *same* `SignedInfo` lands
on a different event code in each:

| Set | SignedInfo fragment |
|---|---|
| AC | 135, 8 bits |
| DC | 129, 8 bits |
| CommonMessages | 230, 9 bits |
| AC_DER_IEC | 217, 9 bits |
| AC_DER_SAE | 324, 9 bits |

Different octets, therefore different signed bytes. One shared helper would sign the wrong ones and
produce a signature that verifies locally and nowhere else — note that the DER sets move even though
their *messages* are AC's, so borrowing AC's helper there would be wrong too.

Every one of these has a C# counterpart in the matching project, with the same measurements in its
own doc comment — the two back ends generate the same event codes, which is one more place they are
checked against each other.

Still not implemented: **WPT and ACDP**, which have no fragment elements at all — what they would
sign is an open question, not a port.

**The order of `--xsd` matters.** It decides the order of declarations in the output, so passing
the same files in a different order regenerates a file that differs everywhere while encoding the
same bytes. Use the order given above — the message set's own schema first — or the byte-identity
check below turns into noise.

Regenerating without changing the emitter must leave every file byte-identical; that is the
cheapest check that a refactor was behaviour-neutral.

## Framing and dispatch

`v2g-tp` and `v2g-dispatch` are hand-written ports of the C# `V2GTP` / `V2GTPDispatcher` — the layer
between a socket and a codec. They are split because the header is useful without any codec: reading
a frame's type and length pulls in nothing, while resolving the type to a decoder needs all six
message-set modules.

Three places where the Kotlin API has to differ from the C# one, and none of them changes a byte:

* C#'s `bool TryReadHeader(…, out …)` becomes a nullable `V2GTPHeader?`, and
  `bool TryDecode(…, out set, out message, out error)` becomes a sealed `V2GTPDecodeResult`. The
  distinction that matters is preserved exactly: a *framing* problem — bad version bytes, a length
  field that disagrees with the frame, an unmodelled payload type — is a value, while malformed EXI
  inside a recognised set throws out of the codec, the same as calling `decodeAny` directly.
* The payload is **copied** out of the frame. The generated decoders take a `ByteArray` starting at
  the EXI header, where the C# side passes a `ReadOnlySpan` into the frame it already has.
* The payload-type constants are `val`, not `const val`: Kotlin has no `UShort` literal, and the
  wire width is worth keeping.

`PAYLOAD_TYPE_APP_PROTOCOL` and `PAYLOAD_TYPE_DIN_ISO2_MAIN` are **the same value**, 0x8001. SAP and
a -2 message are told apart by session phase, not by payload type, so the dispatcher only ever
*frames* SAP and never decodes it — 0x8001 resolves to the -2 set. That is why `v2g-dispatch` does
not depend on `exi-appprotocol`. A live interop run against Josev caught an earlier distinct 0x8000
here as a wire-conformance bug.

The dispatcher test frames real cbV2G vectors — the first of each set's corpus — rather than
hand-built payloads, and asserts the decoded message's *package*. A payload type wired to the wrong
codec therefore cannot pass: pointing AC at DC's type fails that test and the bijection test with it.
`V2GTPTest` pins the eight header bytes literally, to the same array the C# `V2GTPFrameTests` pins;
flipping the byte order on both the write and the read side — which a round trip cannot see — fails
it.

## The EVCC state machine — and how a state machine gets checked at all

`v2g-evcc` is the vehicle side of an ISO 15118-2 session: `V2GTPStream` (framing over a JVM stream
pair), `SapHandshake`, and `Evcc2` itself. It is hand-written, ported from the C# `Evcc2`.

Every gate above compares *bytes for a message*. A state machine has no such corpus — there is no
reference EVCC, and the question is not what one message encodes to but **which messages, in what
order, carrying what**. "It ran to completion" answers none of that: a session that skips a phase, or
sends the wrong charging profile, completes just as happily.

So the check is the same construction one layer up. The C# side records whole sessions frame by frame
into `Vectors/Session.*.trace.json` — SAP handshake to SessionStop — and `Evcc2TraceTest` replays the
recorded *responses* into this implementation and requires the *requests* it emits to be byte-identical,
V2GTP headers included. Read the corpus out of the submodule, exactly as `v2g-metering` reads the meter
vectors; the file is the only reason to believe the two agree.

Two things this does not give you, both worth knowing before trusting it:

* **It cannot catch a bug the C# EVCC has too.** C# is a defensible reference because it is the
  implementation that earned the live-interop conformance fixes against Josev — "agrees with the one
  that has actually talked to somebody else" is a weaker claim than conformance, and the honest one.
* **Tariff-signature verification and pause/resume are still unported**, and named as missing in the
  class comments rather than quietly absent. Plug & Charge **is** ported — see below.

The replay harness carries its own negative tests (`anAlteredRequestIsRejected`,
`anEarlyEndingSessionIsNotComplete`), because a comparison that silently compares nothing passes every
run. Beyond that both ports were checked by mutation:

| Mutation | Caught at |
|---|---|
| -2: one byte of the EVCCID | exchange 1 |
| -2: one charging cycle fewer | exchange 9 (AC), 11 (DC) — the same two places as in C# |
| -20: `"EVCC01"` → `"EVCC02"` | exchange 1 |
| -20: the AC charge-parameter discovery sent on the **DC** message set | exchange 7, **byte 3** |
| -20: the pinned clock moved by one second | every -20 test |

The last two are what the -20 corpus catches and the -2 one cannot.

A -20 session crosses between three self-contained message sets — CommonMessages for setup,
authorization, service negotiation, schedule exchange and power delivery; AC or DC for the
charge-parameter discovery, the charge loop, and DC's cable check, pre-charge and welding detection.
They are separate grammars with separate V2GTP payload types, so muddling two of them is a
frame-header bug, caught before the EXI body is even reached. Worth knowing how *narrow* that is:
0x8003 (AC) and 0x8004 (DC) share their high byte, so the entire distinction between two message sets
is seven bits apart on the wire.

And -20 puts a **timestamp in every message header**, which is why `SessionContext` takes a clock
instead of reading one: move it by a single second and not one frame matches. That is also why the
corpus pins `FixedSessionId` and `FixedGenChallenge` on the C# recording side — see `SessionTrace.cs`.

### Plug & Charge, and the one check that is not obvious

`exi-xmldsig` is a codec module with no message set behind it. It exists because the `SignedInfo`
that travels **in** a signed message is encoded under its own message set's grammar, while the octets
actually **signed** are the same `SignedInfo` encoded under the *standalone* W3C xmldsig grammar —
different bytes for the same structure, because the fragment selector is sized over a different set
of global elements. That is the form a live Josev peer produces and accepts. Generated, like every
other codec here; hand-writing it is forbidden for the usual reason.

Signed sessions are compared by substituting the recorded signature and verifying the produced one
separately (`SignedFrame.kt`, mirroring `SignedFrame.cs`). Which leaves a gap that took a moment to
see and is worth writing down:

> The signature bytes are substituted away before the comparison, and a produced signature is
> verified using **this port's own** `standaloneOctets`. So a Kotlin standalone encoder that
> disagreed with C#'s would sign over X, verify over X, and pass every check here — while producing
> a signature no other implementation on earth accepts.

`theRecordedSignatureVerifiesUnderThisPortsOwnEncoder` closes it: the **recorded** signature was made
by C# over C#'s octets, so it verifies here only if the two encoders agree. Confirmed by mutation —
corrupting only the standalone mapping leaves both Plug & Charge replay tests green and fails exactly
that one test, in Kotlin and Swift alike.

## How these codecs are checked

Three independent gates, and none is sufficient alone:

1. **Vectors.** `expectedHex` comes from EVerest's libcbv2g at a pinned commit — the same corpus
   the C# suite uses, read straight out of the submodule rather than copied. AppProtocol encodes
   and compares; -2 decodes and re-encodes, which also exercises the decoder but *cannot* catch a
   bug mirrored in both directions.

   **One corpus outranks all of these: RFC 8032 §7.4.** Every other vector file in the repository
   is some implementation's output — cbV2G's, or ours. Those nine Ed448 vectors are the standard's
   own published numbers, so agreeing with them is agreeing with the specification rather than with
   a peer, and no reference encoder has to be trusted. They are also *equality* checks rather than
   round trips, because Ed448 is deterministic. `Ed448RfcVectorTest` runs them against
   `bcprov-jdk18on`; the C# suite runs the same file against BouncyCastle's .NET port, which is a
   different codebase, so the two runs are worth having separately.

   **The two AC DER corpora are the exception, and say so per vector.** cbexigen does not generate
   the ISO 15118-20 Amendment 1 DER schemas, so no reference encoder exists for a message that uses
   a DER member. What does exist: six of the ten plain-AC messages encode *identically* under the
   DER grammar — measured, not assumed — so those six carry cbV2G's own bytes and are real oracle
   coverage. The other four shift, because `DER_` sorts before `Dynamic_`/`Scheduled_` and pushes
   their event codes along; those, and everything using a DER member, are the C# back end's output.
   For them this test is a cross-language agreement check between two ports of one grammar, plus a
   pin against drift — not evidence of wire conformance. `AcDerCorpusTests` on the C# side guards
   the split so the six anchored vectors cannot quietly be replaced by self-generated bytes.
2. **Cross-emitter comparison.** Generated functions are compared operation-by-operation
   (event codes, widths, primitive and nested-codec calls, in order) against the C# emitter's
   output from the same `SchemaPlan`. That is what rules out the mirrored bug — and the C# side is
   itself pinned to these vectors.

   **Scope (updated 2026-07-30):** this runs in `CrossEmitterComparisonTests`, and Kotlin is now
   compared across **eight whole schema sets** — -2, CommonMessages, DC, AC, ACDP, AC_DER_IEC,
   AC_DER_SAE and **WPT**, which Swift refuses and only Kotlin generates. Every one agrees with the
   C# back end operation for operation.

   The wider statements below — including the old "2 of 130 WPT functions differ" figure — came
   from an ad-hoc run during the Kotlin port whose tool was never checked in. They are superseded:
   the checked-in gate reports no divergence in WPT or anywhere else.

   **WPT is where this gate carries the most weight and deserves the least confidence.** Its
   `WPT_LF_TransmitterDataType` is the construct cbexigen's own encoder cannot represent (see
   below), so no vector reaches it and two emitters agreeing is the *only* check the set has. Two
   ports of one grammar agreeing says they read it the same way, not that they read it correctly.

   Extending it to Kotlin needed one fix to the comparison itself, not to the emitter. The
   document-index `when` is emitted in plan order by Kotlin and sorted by index in C# and Swift —
   the same table, written in a different order, which a strict sequence comparison called a
   divergence. Runs of keyed arms are now sorted by key before comparing, because `4 -> Foo` before
   `0 -> Bar` decodes exactly as the reverse.

   Values are deliberately not compared: `(uint)msg.SchemaID`, `UInt32(v)` and
   `msg.schemaID!!.toLong().toUInt()` are one operand written three ways. What must agree is every
   literal event code and every bit width.

   Arm *keys* are excluded from an operation's identity for the same reason — C# writes
   `case 1u:` where Kotlin writes `else if (rc == 1u)`, and folding that in would compare languages
   rather than grammars. But the keys do have to agree on something, so that claim is made
   separately and precisely: `EveryBackEndRoutesEachDocumentIndexToTheSameMessage` extracts each
   set's document-index → message-decoder table and requires all three back ends to produce the
   same one. **Nothing was checking that before.** A back end that routed index 4 to the wrong
   message would have agreed operation for operation, and the vectors would not have noticed
   either: a misrouted document decodes into a well-formed instance of the wrong type and
   round-trips back to the bytes it came from, because both directions read the same table.
3. **Structure**, in the .NET suite (`KotlinEmitterSplitTests`, `CodegenDriverTests`). The Kotlin
   back end is driven directly on a mini-XSD and on the real -2 set, and the result is checked for
   the things a byte diff cannot see: that every `encodeX(…)` call resolves to a function some file
   declares, that nothing is declared twice, that imports match use, and that the driver's
   stale-output removal takes the generated files and leaves the hand-written ones. Each of those
   was confirmed to fail when the emitter is deliberately broken.

Bit-exactness and well-formedness are separate concerns: a byte-level diff says nothing about
whether the emitted Kotlin *compiles*. Several real bugs here were caught only by the compiler,
which still runs nowhere but `gradle -p kotlin test` — gate 3 checks shape, not syntax.

### Unvalidated construct

One shape has **no working reference encoder**: the `TxSpecData` list in WPT's
`WPT_LF_TransmitterDataType` (`minOccurs=2 maxOccurs=255`, followed by an optional
`TxPackageSpecData`). `CodecEmitter` documents that cbexigen's own generated encoder for it cannot
represent even the schema's required minimum, so both back ends emit a plain schema-informed
non-strict reading instead. That is a design decision, not a diff against a reference — bytes from
this construct are unvalidated. The current WPT vectors do not appear to exercise it.

Porting it surfaced a genuine defect **in the C# emitter**: its encoder wrote a 1-bit element EE
after a present optional tail that its decoder never read, leaving the reader one bit short. That
is fixed, and `Iso15118_20WptSelfConsistencyTests` now covers the present-tail case — it did not
before, despite a comment claiming otherwise.

A second quirk of the same grammar is now refused rather than tolerated: with the mid-run list
empty, the particle after it has no event code, so an encoder asked to write it could only drop it.
Both back ends throw instead, naming the field. Two tests in the C# fixture had been passing an
empty container and were silently exercising nothing at all — that is how the decoder bug above
survived.

The cross-emitter comparison still reports 2 of 130 WPT functions as differing. Both are the
comparison tool seeing Kotlin's `if (rc == 1u)` where C# has `switch`/`case`; the bit operations
themselves are identical.

### Memory

`gradle.properties` raises the Kotlin daemon heap to 1 GB. The inherited default is still not
enough, and the failure moves between modules with build order, so it is pressure across the build
rather than one bad file. The figure is measured: 512m fails on `acdersae`, `dc` and `wpt`; 1g
builds everything. Before the per-type split the same build needed 4 GB.

### Substitution dispatch

The build is warning-free. It was not: the AC, DC and DER codecs used to emit sixteen
`Check for instance is always 'true'` warnings, and the compiler was right. Where a substitution
group's head type is itself concrete, the head is one of the members, and being the least derived
it is the last branch of the dispatch — so that branch tested the property against its own declared
type. Always true, which also made the `else -> throw` behind it unreachable: the two were one
branch written twice.

The last branch is now `else` when the head is concrete, and the dead throw is gone. Where the head
is **abstract** it is not a member at all, the last branch is a real check, and the throw stays —
a value matching none of the members is a genuine error there. `KotlinEmitterDispatchTests` pins
both shapes.

Nothing about the wire changed: branches are ordered most-derived-first, because Kotlin's `is`
matches subtypes too, while each member keeps its own alphabetical event code. The AC and DC
vectors cover the collapsed branch — putting a wrong code in it fails them.

That `is` matching subtypes is also why each branch now `require`s the value to be **exactly** its
member type. Within a schema set every derived type is itself a member, so the branches partition
the generated types precisely; but the classes something extends are emitted `open`, and a consumer
can extend them too. Such a value used to take its nearest ancestor's branch and be written with
that member's event code and encoder — or, in an optional run, match no branch and disappear from
the message. Both silent. The guard is emitted only where the member type is extensible at all
(twelve types across the -20 sets); on a final class `is` already means "exactly this", and the
check would be dead code. `SubstitutionGuardTest` in `exi-iso20-ac` builds an outsider and requires
the encoder to refuse it; the C# back end carries the same guard and the same test.
