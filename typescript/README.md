# `typescript/` — the fourth back end

ISO 15118 EXI for the browser, the WebView inspector and the Capacitor bridge (`docs/CONCEPT.md`
A5). **The runtime and the generated codec are here and verified against cbV2G's bytes; the JSON-LD
pass is not.**

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
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out typescript/src/appprotocol \
  --lang typescript --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

Every AppProtocol vector encodes to cbV2G's bytes and decodes back; every ISO 15118-2 vector decodes
and re-encodes byte-identically, which reaches the `V2G_Message` wrapper, the `BodyType` substitution
group, attributes, simple content, optional runs and bounded lists.

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

## Still to do

The JSON-LD pass, which is cheap now that the emitter exists — and is where the 64-bit-as-string
decision in `JsonPrimitives` was aimed all along.
