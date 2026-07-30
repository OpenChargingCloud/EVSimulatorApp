/* Implementation of the public cg_shake_* API by thunking through to the
 * private libgoldilocks goldilocks_sha3_* sponge functions.
 *
 * cg_shake_sponge_s and goldilocks_keccak_sponge_s have identical layout
 * (uint64_t opaque[26]), so we cast pointers between them directly. The
 * goldilocks_keccak_sponge_p typedef is an array-of-one which decays to a
 * plain struct pointer at every call site. */

#include "include/cgoldilocks_shake.h"

#include <string.h>
#include <goldilocks/shake.h>

static inline goldilocks_keccak_sponge_s *as_goldilocks(cg_shake_sponge_s *s) {
    return (goldilocks_keccak_sponge_s *)s;
}

static const struct goldilocks_kparams_s *params_for(cg_shake_variant variant) {
    switch (variant) {
        case CG_SHAKE_128: return &GOLDILOCKS_SHAKE128_params_s;
        case CG_SHAKE_256: return &GOLDILOCKS_SHAKE256_params_s;
    }
    return &GOLDILOCKS_SHAKE128_params_s;
}

void cg_shake_init(cg_shake_sponge_s *sponge, cg_shake_variant variant) {
    goldilocks_sha3_init(as_goldilocks(sponge), params_for(variant));
}

cg_shake_result cg_shake_update(
    cg_shake_sponge_s *sponge,
    const uint8_t *in,
    size_t len)
{
    return (cg_shake_result)goldilocks_sha3_update(as_goldilocks(sponge), in, len);
}

cg_shake_result cg_shake_output(
    cg_shake_sponge_s *sponge,
    uint8_t *out,
    size_t len)
{
    return (cg_shake_result)goldilocks_sha3_output(as_goldilocks(sponge), out, len);
}

void cg_shake_destroy(cg_shake_sponge_s *sponge) {
    goldilocks_sha3_destroy(as_goldilocks(sponge));
}

cg_shake_result cg_shake_hash(
    cg_shake_variant variant,
    const uint8_t *in, size_t in_len,
    uint8_t *out, size_t out_len)
{
    return (cg_shake_result)goldilocks_sha3_hash(
        out, out_len, in, in_len, params_for(variant));
}
