# Kotlin EXI codecs

| Module | Contents |
|---|---|
| `exi-runtime` | Hand-written `BitReader` / `BitWriter` / `ExiPrimitives` — a port of the C# runtime. |
| `exi-appprotocol` | Generated `SupportedAppProtocol` codec + vector test (encode vs `expectedHex`). |
| `exi-iso2` | Generated ISO 15118-2 codec + vector test (decode → re-encode → `expectedHex`). |

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

Regenerating without changing the emitter must leave both files byte-identical; that is the
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
