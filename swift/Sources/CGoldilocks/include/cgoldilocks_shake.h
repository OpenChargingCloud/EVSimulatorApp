/* CGoldilocks SHAKE — thin public re-exposure of libgoldilocks' SHAKE128 and
 * SHAKE256 (FIPS 202 extendable-output functions).
 *
 * The upstream goldilocks/shake.h pulls in goldilocks/common.h, which would
 * leak a lot of internal symbols into the public C module. This header
 * exposes only what callers need, namespaced with `cg_shake_`, and the .c
 * file thunks through to the private goldilocks_sha3_* API.
 */
#ifndef CGOLDILOCKS_SHAKE_H
#define CGOLDILOCKS_SHAKE_H 1

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Sponge state. Must be passed by pointer to every cg_shake_* call. Layout
 * matches goldilocks_keccak_sponge_s (26 × uint64_t = 208 bytes); the struct
 * is exposed by value so Swift consumers can stack-allocate it via
 * UnsafeMutablePointer<cg_shake_sponge_s>.allocate(capacity: 1) without
 * needing a separate alloc/free pair. */
typedef struct cg_shake_sponge_s {
    uint64_t opaque[26];
} cg_shake_sponge_s;

/* Variant selector. */
typedef enum {
    CG_SHAKE_128 = 128,
    CG_SHAKE_256 = 256
} cg_shake_variant;

/* 0 = success; non-zero = failure (matches goldilocks_error_t convention). */
typedef int cg_shake_result;

/* Initialize a sponge for the given SHAKE variant. Always succeeds. */
void cg_shake_init(cg_shake_sponge_s *sponge, cg_shake_variant variant);

/* Absorb `len` bytes from `in` into `sponge`. Returns 0 on success,
 * non-zero if the sponge has already been used for output. */
cg_shake_result cg_shake_update(
    cg_shake_sponge_s *sponge,
    const uint8_t *in,
    size_t len);

/* Squeeze `len` bytes of output from `sponge` into `out`. Can be called
 * multiple times to extend the output. */
cg_shake_result cg_shake_output(
    cg_shake_sponge_s *sponge,
    uint8_t *out,
    size_t len);

/* Securely zero the sponge state. Call when done. */
void cg_shake_destroy(cg_shake_sponge_s *sponge);

/* One-shot: hash `in_len` bytes of `in` and write `out_len` bytes of SHAKE
 * output to `out`. */
cg_shake_result cg_shake_hash(
    cg_shake_variant variant,
    const uint8_t *in, size_t in_len,
    uint8_t *out, size_t out_len);

#ifdef __cplusplus
}
#endif

#endif
