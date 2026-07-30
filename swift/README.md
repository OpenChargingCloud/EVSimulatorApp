# Swift EXI codecs

The third back end of the shared generator, next to C# and Kotlin. Only `ExiRuntime` is
hand-written; every codec module is emitted from the XSDs and checked in.

## Layout

| Target | Contents |
|---|---|
| `ExiRuntime` | Hand-written `BitReader` / `BitWriter` / `ExiPrimitives` / `ExiStringTable` — a port of the C# runtime. |
| `ExiAppProtocol` | Generated `SupportedAppProtocol` codec + vector test (encode vs `expectedHex`, and decode → re-encode). |
| `ExiIso2` | Generated ISO 15118-2 codec — 111 files — + vector test (decode → re-encode → `expectedHex`). |
| `ExiIso20Common` | Generated -20 CommonMessages codec, 160 files. |
| `ExiIso20DC` / `ExiIso20AC` / `ExiIso20ACDP` | The -20 power-transfer sets, 72 / 59 / 58 files. |

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
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Prototype/Schemas/V2G_CI_AppProtocol.xsd \
  --out swift/Sources/ExiAppProtocol \
  --lang swift --namespace cloud.charging.v2g.appprotocol --codec SupportedAppProtocolCodec
```

ISO 15118-2 — note the schema order, which decides declaration order in the output:

```bash
dotnet run --project libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Codegen -c Release -- \
  --xsd "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDef.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgBody.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgDataTypes.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/V2G_CI_MsgHeader.xsd;libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Iso15118_2/Schemas/xmldsig-core-schema.xsd" \
  --out swift/Sources/ExiIso2 \
  --lang swift --namespace cloud.charging.v2g.iso2 --codec Iso15118_2Codec
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

**Two things still fail loudly.** Fragment codecs (`--fragments`) are not implemented — the
XMLDSig signing layer needs them. And **WPT is refused on principle rather than for lack of work**:
`WPT_LF_TransmitterDataType` is the self-loop list shape for which cbexigen's own encoder cannot
represent even the schema's required minimum, so there is no oracle to check an implementation
against. Emitting something plausible there would produce bytes nothing has ever validated. That is deliberate: each construct lands with its own vectors
rather than being guessed at, and a silent almost-right encoder is the one outcome this project
cannot afford.

## How these codecs are checked

`kotlin/README.md` describes three independent gates. Swift has all three — the first back end in
this project that demonstrably does, since gate 2 had no implementation before the Swift port
needed it:

1. **Vectors** — `expectedHex` from EVerest's libcbv2g at a pinned commit, read straight out of
   the submodule, the same corpus the C# and Kotlin suites use. AppProtocol encodes and compares
   (17); the rest decode and re-encode — ISO 15118-2 (39), -20 CommonMessages (26), DC (16),
   AC (10), ACDP (8). **116 vectors, all byte-exact.** Every one exercises the decoder too, and
   none of them *can* catch a bug mirrored in both directions — see gate 2.
2. **Cross-emitter comparison** — `CrossEmitterComparisonTests` in the .NET suite reduces each
   back end's output to the ordered wire operations every generated function performs, and
   requires Swift and C# to agree. This is what rules out the mirrored bug, which the vectors
   cannot: they pin the *encoder*, and the decoder is then checked by round-tripping through that
   same encoder.

   It covers every set the back end generates — -2 and all four -20 sets — and they all agree. It has found three real bugs so far,
   and the third makes the case better than any argument: `SalesTariffEntryType` wrote a 1-bit run
   selector where cbV2G writes 2, because an optional *list* was treated as terminating the run
   rather than belonging to it. That codec compiled, and round-tripped against itself perfectly.
   Every bit after the selector was wrong.

   It also found a divergence on its first run. Swift decoded enumerations through a *generated*
   wrapper where C# and Kotlin read the index inline, so the operation sequences differed even
   though the bytes did not. The fix was to move the fallible lookup into the runtime
   (`ExiRuntime.exiEnum`) and leave the read inline — the comparison was right that a wrapper
   there is a structural difference worth removing.
3. **Structure**, in the .NET suite (`SwiftEmitterSplitTests`) — one file per type, nothing
   declared twice, every codec call resolving to a declaration, the runtime imported exactly where
   it is used, and unmodelled constructs failing loudly.

Bit-exactness and well-formedness are separate concerns: a byte-level diff says nothing about
whether the emitted Swift *compiles*. That check runs nowhere but `swift test`.
