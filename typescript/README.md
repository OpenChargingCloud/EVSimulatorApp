# `typescript/` — the fourth back end

ISO 15118 EXI for the browser, the WebView inspector and the Capacitor bridge (`docs/CONCEPT.md`
A5). **Done: the runtime, the generated codec — verified against cbV2G's bytes — and the JSON-LD
pass, which agrees with the C# documents character for character.**

## Run

```bash
cd typescript && npm test
```

No install step, and that is a property rather than a convenience: Node ≥ 22.6 runs TypeScript by
**stripping** types, so nothing here needs a compiler, a bundler or a `node_modules`. The suite reads
the same vector corpus the other three back ends are held to, so a green run means wire conformance
against cbV2G rather than self-consistency.

`tsconfig.json` exists for editors and for `tsc --noEmit` where a toolchain happens to be installed.
It is not needed to run anything.

## What type stripping demands of the generated code

Stripping erases type annotations and nothing else, so the emitter may only produce **erasable**
syntax. Three things a TypeScript author would reach for first are therefore out:

| Not available | Why | What the emitter will do instead |
|---|---|---|
| `enum` | Emits a runtime object, so it is not erasable | A frozen const object plus a name table — which the JSON-LD form needs anyway |
| Parameter properties (`constructor(public x: number)`) | Emits assignments | Declared fields and an explicit constructor body, as the Swift back end already does |
| `namespace` | Emits an IIFE | ES modules |

This is a constraint worth having. A codec that runs from source, on any Node and in any browser
with no build step, is a codec someone will actually put in a WebView.

## The runtime

`src/runtime/` is a port of C#'s `Exi/` — `BitReader`, `BitWriter`, `ExiPrimitives`,
`ExiStringTable` — and all four back ends must agree bit for bit.

Three places where JavaScript needed a decision the other three did not:

**`bigint` for the EXI Unsigned Integer and Integer, and only for those.** `number` is a double, so
anything above 2^53 rounds silently. An n-bit field is at most 32 bits wide and stays a `number`. It
is the same decision the JSON-LD form makes one layer up, for the same reason.

**Code points, not code units.** A JavaScript string is UTF-16, and U+1F600 is two units but one
value. `writeStringValue` iterates `[...value]`. The corpus has three astral vectors and one of them
carries the note *"this is what catches a code-unit-wise encoder"* — writing the obvious
`value.length` / `charCodeAt` version fails exactly that vector and no other.

**`value * 2 + bit` in `readBits`, not `value << 1 | bit`.** JavaScript's bitwise operators are
defined on *signed* 32-bit integers, so a 32-bit read built with shifts comes back negative. The
write path has the mirror of it: `value >>> i`, never `>>`.

## The generated codec

`src/appprotocol/` and `src/iso2/` come from the same generator, the same schema plan and the same
pass as the C#, Kotlin and Swift codecs:

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out typescript/src/appprotocol \
  --lang typescript --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

ISO 15118-20 arrived on 2026-08-04 — CommonMessages, AC and DC, each its own set with its own V2GTP
payload type and its own copy of the XMLDSig schema:

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonMessages.xsd;libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/V2G_CI_CommonTypes.xsd;libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Iso15118_20.CommonMessages/Schemas/xmldsig-core-schema.xsd" \
  --out typescript/src/iso20common \
  --lang typescript --namespace cloud.charging.v2g.iso20.common --codec CommonMessagesCodec \
  --fragments "AbsolutePriceSchedule CertificateInstallationReq MeteringConfirmationReq OEMProvisioningCertificateChain PnC_AReqAuthorizationMode SignedInstallationData SignedInfo"
```

AC and DC follow the same pattern — swap `CommonMessages` for `AC` / `DC` in the schema names, the
output path (`iso20ac` / `iso20dc`), the namespace and the codec (`ACCodec` / `DCCodec`), with
`--fragments "AC_ChargeParameterDiscoveryRes SignedInfo"` and its DC twin. The lists mirror
`<ExiFragmentElements>` in the matching C# project, as they do for the other back ends.

**Generating them found an emitter bug three years of schema old.** `ChildParams` spelled an
optional type `Type?` — how Kotlin and Swift write one, and a syntax error in TypeScript, where `?`
marks an optional parameter or property and never a type. ISO 15118-2 has no inline `xs:choice`
anywhere, so no file had ever exercised the path; the first that would not parse was -20
CommonMessages' `AuthorizationReqType`. Regenerating `iso2/`, `appprotocol/` and `xmldsig/` after the
fix leaves every file byte-identical, which is the check that the fix reached only the broken path.

Every AppProtocol vector encodes to cbV2G's bytes and decodes back; every ISO 15118-2 vector decodes
and re-encodes byte-identically, which reaches the `V2G_Message` wrapper, the `BodyType` substitution
group, attributes, simple content, optional runs and bounded lists. Every -20 vector does the same
per set, plus the DC messages a live Josev station sent — kept in a separate corpus on purpose, since
those are interoperability evidence rather than conformance evidence.

Three shapes differ from the other back ends, and none changes a byte:

* **A frozen const object per enumeration**, plus a `…Names` table, because `enum` is not erasable.
  The wire value *is* the index, so nothing is looked up on decode — `exiEnum` only checks that the
  index is one the type has, which a decoder must do with network input.
* **`instanceof` for substitution dispatch**, ordered most-derived-first, since `instanceof` is true
  for a base class too. The member guard uses `constructor !==` rather than `instanceof`, because
  its whole point is that the value is *exactly* the member type its branch selected — a consumer
  can extend a generated class, and such a value used to go out with its ancestor's event code.
* **An if/else-if chain where Kotlin has `when`**, seeded with `if (false) {}` so that every arm is
  spelled `else if` and no part of the emitter has to know which arm comes first.

## The JSON-LD pass

`<Set>CodecJson.toJSON` / `.parseJSON`, emitted from the same schema plan in the same pass as the
codec, and held to `JsonLd.documents.json` — the documents the C# back end produces — **in both
directions and character for character**. This is the back end the 64-bit-as-string rule was written
for: the other three carry it and would round-trip either way, while here a `TimeStamp` written as a
JSON number is rounded the moment it passes 2^53.

There is no hand-written JSON tree, and that is the one place this port does *less* than the others.
Kotlin and Swift each carry one because the agreement is compared as text and both would otherwise
have surrendered member order to an unordered map and escaping to a library's conventions. A
JavaScript object preserves the insertion order of its string keys by specification,
`JSON.stringify` walks them in that order, and its escaping and number formatting are fixed by the
same specification. So the plain object *is* the ordered tree and `JSON.stringify` *is* the writer.

What is harder: TypeScript's types are gone at runtime, so a polymorphic property cannot be checked
with a cast. The constructor is passed to `JsonPrimitives.cast` and the check is an `instanceof` —
without it a wrong `@type` would surface far away, as a missing property on a value of the wrong
class.

### What the JSON corpus caught that the byte corpus could not

`xs:boolean` decoded to the raw `1`, not `true`. TypeScript would have rejected the assignment;
stripping does not, so the value was a number wearing a boolean's type. **The bytes never noticed** —
`1 ? 1 : 0` and `true ? 1 : 0` are the same bit — and every wire vector stayed green. It showed up as
`"freeService":1` against `"freeService":true`.

The other one was a divergence between the two emitters: the codec's keyword list was still Kotlin's,
which reserves `object` where JavaScript does not, so the codec named XMLDSig's `Object` field
`object_` while the JSON pass read `value.object`. It loaded and ran, and failed at the first signed
message with an error a whole file away from the cause. They share one list now.
