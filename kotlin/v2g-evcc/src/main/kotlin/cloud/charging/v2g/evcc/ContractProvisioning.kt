package cloud.charging.v2g.evcc

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What went wrong unwrapping an issued contract key. */
class ContractProvisioningException(message: String) : GeneralSecurityException(message)

/**
 * The ISO 15118-**2** contract-provisioning key transport (§7.9.2.4), receiving side.
 *
 * The secondary actor generated an ephemeral **secp256r1** key pair, ran ECDH against this car's public
 * key, derived a 128-bit AES key and AES-128-**CBC**-encrypted the issued contract's raw private scalar.
 * Here the car repeats the ECDH with its own private key and the transmitted `DHpublickey`, and unwraps.
 * Wire shapes come from the schema: `DHpublickey` = 65 B (uncompressed P-256 point `0x04‖X32‖Y32`),
 * `ContractSignatureEncryptedPrivateKey` = 48 B (`IV16‖ciphertext32`).
 *
 * **Who the receiver is depends on the message.** For a `CertificateInstallationRes` it is the OEM
 * provisioning key — the only key a car has before it has a contract. For a `CertificateUpdateRes` it is
 * the *expiring contract* key, which is what makes a renewal self-authenticating: only the holder of the
 * old contract can open the new one.
 *
 * ## Nothing here can fail on a wrong key, and that is the point
 *
 * CBC authenticates nothing. Decrypting with the wrong ECDH partner does not throw — it yields 32 bytes
 * of nonsense that make a perfectly valid private key belonging to nobody. [matches] is the check that
 * catches it, and the caller has to run it; the corpus case `iso2/install-wrong-receiver` records the
 * exact nonsense, so a port whose KDF or cipher differs fails there rather than silently agreeing to
 * disagree.
 *
 * ## Differences from the -20 transport, none of them cosmetic
 *
 * A different curve (secp256r1 against secp521r1), a different cipher mode (CBC against GCM, so this one
 * is unauthenticated), a different KDF — the counter goes **after** Z here and before it there — and half
 * the key length. [ContractProvisioning20] shares no code with this, and making it would only hide that.
 *
 * ## Honesty note
 *
 * The KDF is the ANSI X9.63 form the standard names, with empty SharedInfo. No capture in this project
 * contains a real `CertificateInstallationRes` from a foreign stack, so these *crypto payload* octets are
 * self-consistent across C#, Kotlin and Swift and nothing more. The wire messages around them stay
 * byte-exact per the usual oracles.
 */
object ContractProvisioning2 {

    /** secp256r1. */
    const val SCALAR_BYTES = 32

    /** AES block size, and the IV that prefixes the ciphertext. */
    const val IV_BYTES = 16

    /** AES-128, per §7.9.2.4. */
    private const val KEY_BYTES = 16

    const val DH_PUBLIC_KEY_LENGTH = 1 + 2 * SCALAR_BYTES              // 65
    const val ENCRYPTED_PRIVATE_KEY_LENGTH = IV_BYTES + SCALAR_BYTES   // 48

    internal val curve: ECParameterSpec by lazy { namedCurve("secp256r1") }

    /**
     * Repeats the ECDH with the car's own private key and the station's transmitted point, then unwraps
     * the scalar and rebuilds the contract private key.
     *
     * The returned key is *a* key, always — see the class comment. Whether it is *the* key is [matches]'
     * question.
     */
    fun recoverContractKey(receiver: PrivateKey, dhPublicKey: ByteArray, encryptedPrivateKey: ByteArray): PrivateKey {

        if (dhPublicKey.size != DH_PUBLIC_KEY_LENGTH || dhPublicKey[0] != 0x04.toByte())
            throw ContractProvisioningException(
                "DHpublickey: expected a $DH_PUBLIC_KEY_LENGTH-byte uncompressed P-256 point.")

        if (encryptedPrivateKey.size != ENCRYPTED_PRIVATE_KEY_LENGTH)
            throw ContractProvisioningException(
                "ContractSignatureEncryptedPrivateKey: expected $ENCRYPTED_PRIVATE_KEY_LENGTH bytes.")

        val aesKey = deriveKey(sharedSecret(receiver, uncompressedPoint(dhPublicKey, SCALAR_BYTES, curve)))

        val scalar = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
                 IvParameterSpec(encryptedPrivateKey.copyOfRange(0, IV_BYTES)))
            doFinal(encryptedPrivateKey.copyOfRange(IV_BYTES, encryptedPrivateKey.size))
        }

        return KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve))
    }

    /**
     * Whether an unwrapped key really belongs to the certificate it arrived with — the check CBC's lack
     * of authentication makes the caller's job.
     *
     * A sign/verify round trip rather than a comparison of parameters, because that is the property
     * actually wanted: *this key can produce signatures that certificate verifies*.
     */
    fun matches(privateKey: PrivateKey, certificatePublicKey: PublicKey): Boolean = try {
        val probe = "contract-key-check".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(probe); sign()
        }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(certificatePublicKey); update(probe); verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    }

    /**
     * ANSI X9.63 KDF with SHA-256 and empty SharedInfo: `SHA-256(Z ‖ counter=1)`, truncated to the
     * 16-byte AES-128 key. The counter goes **after** Z, which is the one place this differs from the
     * ConcatKDF the -20 transport uses — and a port that copies the wrong one derives a different key
     * from the same shared secret, silently.
     */
    internal fun deriveKey(z: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(z + byteArrayOf(0, 0, 0, 1))
            .copyOf(KEY_BYTES)
}

/**
 * The ISO 15118-**20** contract-provisioning key transport (`SignedInstallationData`), receiving side.
 *
 * The SECC generated an ephemeral **secp521r1** key pair, ran ECDH against this car's static OEM
 * provisioning key, derived an AES-256 key and AES-256-**GCM**-encrypted the issued contract's raw
 * private scalar. Wire shapes: `DHPublicKey` = 133 B (uncompressed P-521 point `0x04‖X66‖Y66`),
 * `SECP521_EncryptedPrivateKey` = 94 B (`IV12‖ciphertext66‖tag16`).
 *
 * Unlike its -2 sibling this **does** fail on a wrong key: GCM's tag check refuses, and no after-the-fact
 * certificate comparison is needed. That difference is recorded in the corpus rather than only described
 * — `iso20/install-wrong-receiver` expects no key at all where the -2 case expects nonsense.
 *
 * ## Honesty note
 *
 * The KDF here is a single-round ConcatKDF (`SHA-512(0x00000001 ‖ Z)[0..32]`, empty OtherInfo). It is
 * schema-valid and round-trips, but no external reference stack implements -20 provisioning to diff
 * against — Josev raises `NotImplementedError` on both sides — so as with -2 these payload octets are
 * self-consistent only.
 */
object ContractProvisioning20 {

    /** secp521r1. */
    const val SCALAR_BYTES = 66
    const val IV_BYTES = 12
    const val TAG_BYTES = 16

    const val DH_PUBLIC_KEY_LENGTH = 1 + 2 * SCALAR_BYTES                          // 133
    const val ENCRYPTED_PRIVATE_KEY_LENGTH = IV_BYTES + SCALAR_BYTES + TAG_BYTES   // 94

    internal val curve: ECParameterSpec by lazy { namedCurve("secp521r1") }

    /**
     * Repeats the ECDH with the car's OEM private key and the station's transmitted point, then unwraps
     * the scalar. Throws when the tag does not hold — which is what a wrong key looks like here.
     */
    fun recoverContractKey(oemKey: PrivateKey, dhPublicKey: ByteArray, encryptedPrivateKey: ByteArray): PrivateKey {

        if (dhPublicKey.size != DH_PUBLIC_KEY_LENGTH || dhPublicKey[0] != 0x04.toByte())
            throw ContractProvisioningException(
                "DHPublicKey: expected a $DH_PUBLIC_KEY_LENGTH-byte uncompressed P-521 point.")

        if (encryptedPrivateKey.size != ENCRYPTED_PRIVATE_KEY_LENGTH)
            throw ContractProvisioningException(
                "SECP521_EncryptedPrivateKey: expected $ENCRYPTED_PRIVATE_KEY_LENGTH bytes.")

        val aesKey = deriveKey(sharedSecret(oemKey, uncompressedPoint(dhPublicKey, SCALAR_BYTES, curve)))

        // JCA takes the tag as a trailing part of the ciphertext, which is exactly the layout the wire
        // field already has: IV ‖ ciphertext ‖ tag.
        val scalar = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
                 GCMParameterSpec(TAG_BYTES * 8, encryptedPrivateKey.copyOfRange(0, IV_BYTES)))
            doFinal(encryptedPrivateKey.copyOfRange(IV_BYTES, encryptedPrivateKey.size))
        }

        return KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve))
    }

    /**
     * Single-round ConcatKDF (NIST SP 800-56A §5.8.1 with SHA-512, empty OtherInfo):
     * `SHA-512(counter=1 ‖ Z)`, truncated to the 32-byte AES-256 key. Counter **before** Z — see
     * [ContractProvisioning2.deriveKey] for the half that has it the other way round.
     */
    internal fun deriveKey(z: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512")
            .digest(byteArrayOf(0, 0, 0, 1) + z)
            .copyOf(32)
}


/** The named curve's parameters, the only way JCA hands them out without an existing key. */
private fun namedCurve(name: String): ECParameterSpec =
    AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec(name)) }
        .getParameterSpec(ECParameterSpec::class.java)

/** An uncompressed `0x04‖X‖Y` point as a JCA public key. Width is checked by the caller. */
private fun uncompressedPoint(point: ByteArray, width: Int, curve: ECParameterSpec): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(
            ECPoint(BigInteger(1, point.copyOfRange(1, 1 + width)),
                    BigInteger(1, point.copyOfRange(1 + width, 1 + 2 * width))),
            curve))

/**
 * The raw ECDH shared secret — the x-coordinate of the shared point, and nothing else.
 *
 * JCA's plain `"ECDH"` agreement returns exactly that, matching C#'s `DeriveRawSecretAgreement` and
 * CryptoKit's `SharedSecret`. `"ECDHwithSHA256KDF"` and friends would hash it first, which is a
 * different value and a different protocol.
 */
private fun sharedSecret(privateKey: PrivateKey, peer: PublicKey): ByteArray =
    KeyAgreement.getInstance("ECDH").run {
        init(privateKey); doPhase(peer, true); generateSecret()
    }
