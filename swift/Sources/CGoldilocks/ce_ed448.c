/* Thin shim that maps the swift-facing ce_ed448_* API onto libgoldilocks. */

#include "ce_ed448.h"

#include <string.h>

#include "goldilocks.h"
#include "goldilocks/ed448.h"
#include "goldilocks/point_448.h"

/* Constant-time memory zero. libgoldilocks's utils.c provides this under
 * the name goldilocks_bzero. */
extern void goldilocks_bzero(void *s, size_t len);

void ce_ed448_cleanse(void *ptr, size_t len) {
    if (ptr == NULL || len == 0) return;
    goldilocks_bzero(ptr, len);
}

void ce_ed448_derive_public_key(
    uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t privkey[CE_ED448_PRIVATE_KEY_BYTES]
) {
    goldilocks_ed448_derive_public_key(pubkey, privkey);
}

void ce_ed448_sign(
    uint8_t signature[CE_ED448_SIGNATURE_BYTES],
    const uint8_t privkey[CE_ED448_PRIVATE_KEY_BYTES],
    const uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t *message,
    size_t message_len
) {
    goldilocks_ed448_sign(
        signature, privkey, pubkey,
        message, message_len,
        /* prehashed */ 0,
        /* context */ NULL,
        /* context_len */ 0);
}

ce_ed448_result ce_ed448_verify(
    const uint8_t signature[CE_ED448_SIGNATURE_BYTES],
    const uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t *message,
    size_t message_len
) {
    goldilocks_error_t e = goldilocks_ed448_verify(
        signature, pubkey,
        message, message_len,
        /* prehashed */ 0,
        /* context */ NULL,
        /* context_len */ 0);
    return e == GOLDILOCKS_SUCCESS ? CE_ED448_SUCCESS : CE_ED448_FAILURE;
}

void ce_x448_derive_public_key(
    uint8_t pubkey[CE_X448_PUBLIC_KEY_BYTES],
    const uint8_t scalar[CE_X448_PRIVATE_KEY_BYTES]
) {
    goldilocks_x448_derive_public_key(pubkey, scalar);
}

ce_ed448_result ce_x448_shared_secret(
    uint8_t shared[CE_X448_SHARED_SECRET_BYTES],
    const uint8_t scalar[CE_X448_PRIVATE_KEY_BYTES],
    const uint8_t peer_pubkey[CE_X448_PUBLIC_KEY_BYTES]
) {
    goldilocks_error_t e = goldilocks_x448(shared, peer_pubkey, scalar);
    return e == GOLDILOCKS_SUCCESS ? CE_ED448_SUCCESS : CE_ED448_FAILURE;
}
