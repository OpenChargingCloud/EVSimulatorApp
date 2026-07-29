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
| `exi-iso20-acderiec` | Generated ISO 15118-20 AC_DER_IEC codec. **No vector corpus exists**, so this one is only checked by compiling and by the cross-emitter comparison. |
| `exi-iso20-acdersae` | Generated ISO 15118-20 AC_DER_SAE codec. Same — no vectors. |

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
* the Ed448 **context string**, which RFC 8032 fixes to empty for plain `Ed448`. Sign and verify
  share it, so every other test passes with a wrong one — checked, and it does. The test that
  catches it crosses over to BouncyCastle's JCA `Ed448` entry, the spec-named algorithm, and
  requires signatures to verify in both directions.

Signing for the AC / DC / DER / WPT / ACDP message sets is not implemented; those sets sign through
CommonMessages' header in practice, and none of them has its own signature helper on the C# side
either.

**The order of `--xsd` matters.** It decides the order of declarations in the output, so passing
the same files in a different order regenerates a file that differs everywhere while encoding the
same bytes. Use the order given above — the message set's own schema first — or the byte-identity
check below turns into noise.

Regenerating without changing the emitter must leave every file byte-identical; that is the
cheapest check that a refactor was behaviour-neutral.

## How these codecs are checked

Three independent gates, and none is sufficient alone:

1. **Vectors.** `expectedHex` comes from EVerest's libcbv2g at a pinned commit — the same corpus
   the C# suite uses, read straight out of the submodule rather than copied. AppProtocol encodes
   and compares; -2 decodes and re-encodes, which also exercises the decoder but *cannot* catch a
   bug mirrored in both directions.
2. **Cross-emitter comparison.** Every generated function is compared operation-by-operation
   (event codes, widths, primitive and nested-codec calls, in order) against the C# emitter's
   output from the same `SchemaPlan`. That is what rules out the mirrored bug — and the C# side is
   itself pinned to these vectors.
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
