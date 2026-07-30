/* CEd448Vendored — Swift-facing C API for the vendored libgoldilocks
 * Ed448/X448 implementation. Used on platforms where neither
 * OpenSSL-Package (Apple) nor a system libcrypto (Linux) is available
 * — currently Android and WebAssembly.
 *
 * Upstream: https://github.com/otrv4/libgoldilocks (MIT-licensed),
 * which itself forks Mike Hamburg's libdecaf.
 */
#ifndef CE_ED448_H
#define CE_ED448_H 1

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Ed448 sizes (RFC 8032). */
#define CE_ED448_PRIVATE_KEY_BYTES 57
#define CE_ED448_PUBLIC_KEY_BYTES  57
#define CE_ED448_SIGNATURE_BYTES   114

/* X448 sizes (RFC 7748). */
#define CE_X448_PRIVATE_KEY_BYTES 56
#define CE_X448_PUBLIC_KEY_BYTES  56
#define CE_X448_SHARED_SECRET_BYTES 56

/* Return codes. 0 == success. */
typedef enum {
    CE_ED448_SUCCESS = 0,
    CE_ED448_FAILURE = -1
} ce_ed448_result;

/* Securely zero a memory region. The vendored impl provides this. */
void ce_ed448_cleanse(void *ptr, size_t len);

/* Derive an Ed448 public key from a 57-byte secret seed. */
void ce_ed448_derive_public_key(
    uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t privkey[CE_ED448_PRIVATE_KEY_BYTES]);

/* Pure Ed448 signing (context = empty, prehashed = no). */
void ce_ed448_sign(
    uint8_t signature[CE_ED448_SIGNATURE_BYTES],
    const uint8_t privkey[CE_ED448_PRIVATE_KEY_BYTES],
    const uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t *message,
    size_t message_len);

/* Pure Ed448 verification. Returns 0 on valid, -1 on invalid. */
ce_ed448_result ce_ed448_verify(
    const uint8_t signature[CE_ED448_SIGNATURE_BYTES],
    const uint8_t pubkey[CE_ED448_PUBLIC_KEY_BYTES],
    const uint8_t *message,
    size_t message_len);

/* Derive an X448 public key from a 56-byte secret scalar. */
void ce_x448_derive_public_key(
    uint8_t pubkey[CE_X448_PUBLIC_KEY_BYTES],
    const uint8_t scalar[CE_X448_PRIVATE_KEY_BYTES]);

/* X448 ECDH. Returns 0 on success, -1 if the peer's public key is in
 * a small subgroup (i.e. the shared secret is the all-zero point). */
ce_ed448_result ce_x448_shared_secret(
    uint8_t shared[CE_X448_SHARED_SECRET_BYTES],
    const uint8_t scalar[CE_X448_PRIVATE_KEY_BYTES],
    const uint8_t peer_pubkey[CE_X448_PUBLIC_KEY_BYTES]);

#ifdef __cplusplus
}
#endif

#endif
