# Swift EXI codecs

The third back end of the shared generator, next to C# and Kotlin. Only `ExiRuntime` is
hand-written; every codec module is emitted from the XSDs and checked in.

## Layout

| Target | Contents |
|---|---|
| `ExiRuntime` | Hand-written `BitReader` / `BitWriter` / `ExiPrimitives` / `ExiStringTable` — a port of the C# runtime. |
| `ExiAppProtocol` | Generated `SupportedAppProtocol` codec + vector test (encode vs `expectedHex`, and decode → re-encode). |

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
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out swift/Sources/ExiAppProtocol \
  --lang swift --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

**The order of `--xsd` matters** once a set has more than one schema: it decides declaration
order, so the same files in a different order regenerate output that differs everywhere while
encoding the same bytes. Regenerating without changing the emitter must leave every file
byte-identical — the cheapest check that a refactor was behaviour-neutral.

## How the Swift back end differs

Three shapes differ from Kotlin. None changes a byte.

* **Records are `struct`s**, so messages are values; the codec object is an `enum` with static
  members, Swift's idiom for a namespace that cannot be instantiated.
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

The back end currently models the AppProtocol slice: enums, structs of required singles, an
optional run, and a bounded repeating child. **Everything else fails loudly** — attributes,
`xs:choice`, substitution groups, simple content, wildcards, fragment codecs. That is deliberate:
each construct lands with its own vectors rather than being guessed at, and a silent
almost-right encoder is the one outcome this project cannot afford.

## How these codecs are checked

`kotlin/README.md` describes three independent gates. Swift currently has the first:

1. **Vectors** — `expectedHex` from EVerest's libcbv2g at a pinned commit, read straight out of
   the submodule, the same corpus the C# and Kotlin suites use. AppProtocol encodes and compares;
   each vector is also decoded and re-encoded, which exercises the decoder but *cannot* catch a
   bug mirrored in both directions.
2. **Cross-emitter comparison** — not yet wired up for Swift. This is what rules out the mirrored
   bug, so it is the next gate to add, not an optional extra.
3. **Structure**, in the .NET suite — not yet wired up either (the Kotlin counterparts are
   `KotlinEmitterSplitTests` / `CodegenDriverTests`).

Bit-exactness and well-formedness are separate concerns: a byte-level diff says nothing about
whether the emitted Swift *compiles*. That check runs nowhere but `swift test`.
