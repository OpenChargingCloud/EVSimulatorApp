# Kotlin EXI codecs

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

The generated files are checked in, and **regeneration must land on exactly those paths** — an
extra file next to a stale one means duplicate declarations and a broken build. `--out` takes a
file path here (a path with an extension is the file; without one it is a directory). Run these
from the repository root:

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out kotlin/exi-appprotocol/src/main/kotlin/cloud/charging/v2g/appprotocol/AppProtocolCodec.kt \
  --lang kotlin --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDef.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgBody.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDataTypes.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgHeader.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/xmldsig-core-schema.xsd" \
  --out kotlin/exi-iso2/src/main/kotlin/cloud/charging/v2g/iso2/Iso15118_2Codec.kt \
  --lang kotlin --namespace cloud.charging.v2g.iso2 --codec Iso15118_2Codec
```

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonMessages.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonTypes.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/xmldsig-core-schema.xsd" \
  --out kotlin/exi-iso20-common/src/main/kotlin/cloud/charging/v2g/iso20/common/CommonMessagesCodec.kt \
  --lang kotlin --namespace cloud.charging.v2g.iso20.common --codec CommonMessagesCodec
```

AC and DC follow the same pattern — swap `CommonMessages` for `AC` / `DC` in the schema names,
the output path (`exi-iso20-ac` / `exi-iso20-dc`), the package (`…iso20.ac` / `…iso20.dc`) and the
codec (`ACCodec` / `DCCodec`).

Note the -20 commands pass no `--fragments`, unlike the C# projects of the same name: EXI
fragment codecs are XMLDSig territory and are not implemented in this back end yet. They affect
signature computation, not the message wire format, so the message codecs are complete without
them — but a Kotlin signature implementation will need them.

Regenerating without changing the emitter must leave every file byte-identical; that is the
cheapest check that a refactor was behaviour-neutral.

## How these codecs are checked

Two independent gates, and neither is sufficient alone:

1. **Vectors.** `expectedHex` comes from EVerest's libcbv2g at a pinned commit — the same corpus
   the C# suite uses, read straight out of the submodule rather than copied. AppProtocol encodes
   and compares; -2 decodes and re-encodes, which also exercises the decoder but *cannot* catch a
   bug mirrored in both directions.
2. **Cross-emitter comparison.** Every generated function is compared operation-by-operation
   (event codes, widths, primitive and nested-codec calls, in order) against the C# emitter's
   output from the same `SchemaPlan`. That is what rules out the mirrored bug — and the C# side is
   itself pinned to these vectors.

Bit-exactness and well-formedness are separate concerns: a byte-level diff says nothing about
whether the emitted Kotlin *compiles*. Several real bugs here were caught only by the compiler.

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

The cross-emitter comparison still reports 2 of 130 WPT functions as differing. Both are the
comparison tool seeing Kotlin's `if (rc == 1u)` where C# has `switch`/`case`; the bit operations
themselves are identical.

### Memory

`gradle.properties` raises the Gradle and Kotlin daemon heaps. The generated DER codecs are around
1 MB each in a single file, and the compiler exhausts the default heap on them — the failure moves
between modules with build order, so it is pressure rather than one bad file. The real fix is the
per-type file splitting the plan already calls for to stay under Android's DEX method limits; the
compiler simply hits a wall first.

### Known warnings

The AC and DC codecs emit eight `Check for instance is always 'true'` warnings. Their substitution
head types are concrete, so the last branch of a dispatch chain tests against the property's own
declared type. The dispatch is still correct — branches are emitted most-derived-first, so that
branch is only reached once the derived checks have failed — but the compiler judges each `is` in
isolation. Silencing it means making that last branch a plain `else`, which is only valid when the
head is concrete; with an abstract head the trailing `else -> throw` is genuinely reachable.
