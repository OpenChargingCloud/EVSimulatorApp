# Spike: `swift-goldilocks` as the Ed448 answer

Run on branch `spike/ed448-goldilocks`, 2026-07-30; **merged to master on the strength of the
measurements below.**

What the merge settled and what it did not:

- **Settled** — the library is the chosen Ed448 primitive. The dependency, the vector test and
  these findings are on master, so the acceptance test runs with every `swift test` rather than
  living in a branch nobody re-runs.
- **Since done (same day)** — Ed448 is *implemented*, in all five -20 sets. See
  [If it is adopted](#if-it-is-adopted) for the checklist this became, every item of which is now
  either done or deliberately not: `ed448NotAvailable` is gone, the dispatch reads the declared
  algorithm URI, and the vector test stayed in its own target rather than moving.

The report below is kept as it was written, because the reasoning is what makes the decision
reviewable — including the parts that argue against.

## Result

**It reproduces RFC 8032 §7.4 byte for byte.** All eight empty-context vectors, signing and
verifying, plus public-key derivation. Nine spike tests, all green; the full Swift suite goes from
72 to 81 and stays green.

Falsifiability checked, not assumed: flipping one nibble in one vector fails exactly three of the
nine tests.

## What was measured

| | |
|---|---|
| Version | `0.1.1`, pinned `exact:`, revision `44a49050` |
| Licence | MIT (wrapper) + MIT (vendored libgoldilocks) |
| Platforms | iOS 14+, macOS 13+, watchOS 9+, tvOS 14+, visionOS 1+ — clears our iOS 16 / macOS 13 floor |
| Machine code | **63.1 KB** C + **17.7 KB** Swift wrapper = ~81 KB `__text` |
| Build | SwiftPM only; no external toolchain, no script phase |

For scale, our own `ExiIso20AcDerSae` codec is **1,651 KB** of `__text`. The whole Ed448 dependency
is about 5% of one generated message set — which is the number that matters against OpenSSL, whose
megabytes were the reason to look elsewhere.

## Corrections to what the package appeared to be

**It is not "a pure Swift implementation", as Swift Package Index bills it.** `Package.swift`
declares a C target, `CGoldilocks`, holding vendored libgoldilocks — Mike Hamburg's
Ed448-Goldilocks / libdecaf successor — with a thin Swift wrapper over it. 32 C files, 4 Swift.

That flips the assessment in both directions, and on balance for the better:

- **Better on correctness.** Fresh Ed448 field arithmetic with four commits behind it would have
  been the worst thing to find. Hamburg's implementation is the reference for this curve and is
  written to be constant-time. The risk moves out of the arithmetic and into the binding layer —
  lengths, buffer ownership, error paths — which is auditable in an afternoon because it is 4 files.
- **Worse on one stated premise.** `docs/CONCEPT.md` §2 lists "no runtime to bundle" as an Option-A
  advantage. C sources in the build are a much smaller thing than a bundled runtime, and SwiftPM
  compiles them with the clang already in Xcode, but the sentence needs a footnote.

## The API question that prompted the search is settled

The wrapper documents itself as *"RFC 8032 pure Ed448 (context = empty, prehashed = no)"* — exactly
what ISO 15118-20's `#eddsa-ed448` denotes (RFC 9231 §2.3.12 lists `#eddsa-ed448ph` separately).

The absent context parameter is **not** a gap. `Ed448RfcVectorTests.testTheContextVectorIsOutOfScope‐
RatherThanFailing` pins the distinction that matters: §7.4's `"foo"`-context signature must be
*rejected*, not silently accepted as an empty-context one. It is. So the API fixes the context at
empty rather than hiding it, which is the behaviour we want.

## The binding-layer bug that did not happen

Swift hands `withUnsafeBytes` a **nil** `baseAddress` for an empty collection, and the wrapper
passes it straight to `ce_ed448_sign` with length 0. Nothing in the package documents whether the C
side tolerates that. RFC 8032's first vector is a zero-length message, so
`testTheEmptyMessageVectorIsHandled` settles it: correct signature, no crash.

Worth keeping as a test rather than a note — it is the single most likely place for this wrapper to
break under a future libgoldilocks bump.

## What this spike does *not* establish

- **Nothing about side channels.** libgoldilocks is written to be constant-time; the wrapper makes
  no such claim and was not analysed. Test vectors cannot see timing.
- **Nothing about maintenance.** 4 commits, 1 star, one author, v0.1.x, no audit statement. The
  vendored C is mature and RFC 8032 is frozen, so the wrapper is the part that could rot — and it
  is small enough to fork if it does.
- **Nothing about transport TLS.** This closes the app-layer half of §3.3 only. Conformant-mode
  TLS 1.3 on iOS is untouched and still needs its own answer.
- **Nothing about ISO 15118-20's actual text.** The empty context rests on the standard, which is
  paid and not in this repository. Every implementation here agrees on it; none of them read it.

## If it is adopted — and what happened when it was

The checklist as written, with the outcome against each. Kept in this shape because item 1 was not
followed, and the reason is worth more than a tidy list.

1. ~~Move the vector test out of the spike target into `ExiIso20CommonTests`~~ — **not done, on
   purpose.** It checks *the library* against RFC 8032, which is a different question from whether
   our codecs are right, and it wants to stay separately runnable when a version bump needs
   re-checking. It also turned out to be the only target that sees both Goldilocks and
   `ExiIso20Common`, which makes it the one place that can join the two halves —
   `testOurSigningPathIsTheVectorCheckedPrimitiveOverTheFragment` lives there for that reason.
   The per-set treatment *was* applied: all five sets carry their own helper, as with P-521.
2. ✅ **`ed448NotAvailable` is gone**, replaced by `unsupportedAlgorithm(String)` and
   `algorithmMismatch(declared:attempted:)` — errors that say something a caller can act on rather
   than "this build is incomplete".
3. ✅ **The dispatch reads the declared algorithm URI.** Every entry point refuses to act against
   what the `SignedInfo` names, which is what turns `#eddsa-ed448ph` into a refusal by name instead
   of a message signed under the wrong variant.
4. ⬜ **Vendor or fork rather than depend** — still open, and still the right hedge if the bus factor
   is judged too thin. The C library is the part worth having; the wrapper is ~100 lines we could
   own. Nothing about the integration makes this harder later: the seam is four functions.

One thing the integration surfaced that the spike did not predict: **Ed448 key types are per-set**,
because the message sets are separate modules. A wallet holding one key converts between them —
cheap and lossless, since the seed is the whole key, but real friction, and it follows from the same
independence that forces five `V2GSignature` copies rather than from anything about this library.

## To reproduce

```bash
cd swift && swift test --filter Ed448Goldilocks
```
