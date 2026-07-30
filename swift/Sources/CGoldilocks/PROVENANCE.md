# CGoldilocks — vendored Ed448 implementation

Third-party C, checked in verbatim. **Do not edit these files.** They are the only code in this
repository that is neither hand-written here nor emitted by our generator, and the point of
vendoring is that what we ship is exactly what was reviewed.

## Where it came from

```
Mike Hamburg / Cryptography Research  —  libdecaf, a.k.a. Ed448-Goldilocks   (2014–2016)
        └─ forked 2018 by the libgoldilocks contributors (github.com/otrv4/libgoldilocks)
               └─ a Swift-facing C shim (ce_ed448.c/.h) added on top
                      └─ packaged as github.com/Kingpin-Apps/swift-goldilocks 0.1.1
                             └─ copied here at revision 44a49050, 2026-07-30
```

**This corrects what our own docs said for a day.** They described the library as "Mike Hamburg's
libgoldilocks / libdecaf successor", following the package's README. It is not Hamburg's successor:
it is a **community fork** of his libdecaf, made by the OTRv4 project in 2018 and refactored since.
The distinction matters for exactly the reason the library was chosen — "the reference
implementation rather than fresh code" is weaker if there is a fork and eight years of drift in
between. The finding came out of vendoring, from a comment in a header nobody reads when a package
is merely a dependency, which is an argument for vendoring on its own.

Related, and noted rather than acted on: `ce_ed448.h`'s own comment says it exists for "platforms
where neither OpenSSL-Package (Apple) nor a system libcrypto (Linux) is available — currently
Android and WebAssembly". That describes some other project's build, not this one, so the shim was
lifted from elsewhere too. It compiles and it passes RFC 8032 §7.4; the comment is just evidence of
how far the provenance chain actually runs.

## Licensing

MIT throughout, and the notices must travel with the code:

- `LICENSE.libgoldilocks.txt` — the fork's licence, which also records the original
  Hamburg / Cryptography Research copyright. **Keep it.**
- Per-file `Copyright (c) 2014–2016 Cryptography Research, Inc.` and
  `Copyright (c) 2018 the libgoldilocks contributors` headers — keep those too.

MIT requires the copyright notice and permission notice in "all copies or substantial portions",
which a vendored tree is. Stripping the headers to tidy up would be a licence violation.

## What we use

Only Ed448 — `ce_ed448_derive_public_key`, `ce_ed448_sign`, `ce_ed448_verify`. The tree also builds
X448 and SHAKE128/256; the C is left whole rather than pruned, because deleting from a crypto
implementation to save 300 KB of *source* trades a real risk for no benefit. SHAKE is not optional
in any case: Ed448 hashes with SHAKE256 internally.

`Sources/V2GEd448` is our own Swift surface over this, and is the part we maintain.

## Updating

Do not patch in place. Replace the whole directory from a specific upstream revision, record the
revision here, and re-run the acceptance test:

```bash
swift test --filter Ed448
```

That test is RFC 8032 §7.4 — the standard's own published vectors, byte for byte. It is the
condition for accepting any version of this code, including the one currently checked in.
