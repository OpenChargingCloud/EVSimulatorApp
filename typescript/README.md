# `typescript/` — the fourth back end

ISO 15118 EXI for the browser, the WebView inspector and the Capacitor bridge (`docs/CONCEPT.md`
A5). **Started 2026-07-31: the runtime is here and verified; the generated codec is not.**

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

## Still to do

The `TypeScriptCodecEmitter` — the generated codec itself, the bulk of A5 — and then the JSON-LD
pass, which is cheap once the emitter exists and is where the 64-bit-as-string decision in
`JsonPrimitives` was aimed all along.
