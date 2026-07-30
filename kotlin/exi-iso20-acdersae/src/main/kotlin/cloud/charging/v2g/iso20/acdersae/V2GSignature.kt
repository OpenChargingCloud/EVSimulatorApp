package cloud.charging.v2g.iso20.acdersae

import org.bouncycastle.crypto.params.Ed448PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed448PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed448Signer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * XMLDSig signing and verification for ISO 15118-20 AC_DER_SAE (Amendment 1 DER).
 *
 * The same suite and the same shape as CommonMessages' `V2GSignature` — SHA-512 digests, and either
 * **ECDSA over NIST P-521** (raw `r‖s`, 132 bytes) or **Ed448** (RFC 8032, raw 114 bytes) — but
 * against this message set's own `SignedInfoType` and its own codec.
 *
 * That duplication is not an oversight, and the difference is not theoretical. Each -20 message set
 * embeds its own copy of the XMLDSig schema, and the fragment grammar's element selector is sized by
 * the whole set — so the DER amendment moves it even though this set's *messages* are AC's:
 * SignedInfo is event code 324 at 9 bits here, against 135 at 8 bits in plain AC and 230 at 9 bits
 * in CommonMessages. The same logical SignedInfo therefore signs different octets in each set, and
 * borrowing AC's helper here would sign the wrong bytes — a signature that verifies locally and
 * nowhere else.
 *
 * The element this set signs is `AC_ChargeParameterDiscoveryRes` — the DER schemas leave AC's
 * message roots in place and only add substitution members — whose fragment codec the generator
 * emits from `--fragments`.
 *
 * Ed448 is why this module depends on BouncyCastle: the JDK has no Ed448 at all — not a missing
 * provider, the algorithm simply is not there. Being a *pure* EdDSA scheme it signs the SignedInfo
 * fragment octets directly, with no external pre-hash; the SHA-512 in [digest] is a different
 * thing, hashing the *signed elements* into their References.
 */
object V2GSignature {

    /** EXI canonicalization — the only C14N ISO 15118-20 uses. */
    const val CANONICALIZATION_EXI = "http://www.w3.org/TR/canonical-exi/"

    /** ECDSA-SHA512 signature method. */
    const val ECDSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"

    /** Ed448 (pure EdDSA, RFC 8032) signature method — RFC 9231 §2.3.12. */
    const val EDDSA_ED448 = "http://www.w3.org/2021/04/xmldsig-more#eddsa-ed448"

    /** SHA-512 digest method. */
    const val SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"

    /**
     * JCA name for ECDSA-SHA512 producing the raw `r‖s` form ISO 15118 requires, rather than the
     * ASN.1/DER the plain name yields. Available from Java 9 in the SunEC provider.
     */
    private const val JCA_ECDSA_P1363 = "SHA512withECDSAinP1363Format"

    /** SHA-512 of an element's EXI fragment — the value that goes into its `DigestValue`. */
    fun digest(fragmentBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(fragmentBytes)

    /**
     * A single-reference [SignedInfoType] over one already-computed element digest, with the fixed
     * EXI-C14N / SHA-512 algorithm URIs and the given signature method ([ECDSA_SHA512] by default,
     * or [EDDSA_ED448]). The reference URI is `"#" + referenceId` — the `Id` attribute of the
     * signed element.
     *
     * [includeExiTransform] adds the schema-optional `Transforms` list holding the single EXI-C14N
     * transform; some peers (Josev's pydantic models) treat it as mandatory and reject a Reference
     * without it.
     */
    fun buildSignedInfo(
        referenceId: String,
        digest: ByteArray,
        signatureMethodAlgorithm: String = ECDSA_SHA512,
        includeExiTransform: Boolean = false,
    ): SignedInfoType =
        buildSignedInfo(listOf(referenceId to digest), signatureMethodAlgorithm, includeExiTransform)

    /** A multi-reference [SignedInfoType] — one Reference per signed element, one signature. */
    fun buildSignedInfo(
        references: List<Pair<String, ByteArray>>,
        signatureMethodAlgorithm: String = ECDSA_SHA512,
        includeExiTransform: Boolean = false,
    ): SignedInfoType =
        SignedInfoType(
            id = null,
            canonicalizationMethod = CanonicalizationMethodType(algorithm = CANONICALIZATION_EXI, aNY = null),
            signatureMethod = SignatureMethodType(
                algorithm = signatureMethodAlgorithm, hMACOutputLength = null, aNY = null,
            ),
            reference = references.map { (referenceId, digest) ->
                ReferenceType(
                    id = null,
                    type = null,
                    uRI = "#$referenceId",
                    transforms = if (includeExiTransform)
                        TransformsType(listOf(TransformType(CANONICALIZATION_EXI, xPath = null, aNY = null)))
                    else null,
                    digestMethod = DigestMethodType(algorithm = SHA512, aNY = null),
                    digestValue = digest,
                )
            },
        )

    /** The header [SignatureType] from a signed `SignedInfo` and its raw SignatureValue. */
    fun buildSignature(signedInfo: SignedInfoType, signatureValue: ByteArray): SignatureType =
        SignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = SignatureValueType(id = null, value = signatureValue),
            keyInfo = null,
            `object` = null,
        )

    /** The exact octets that are signed (or verified): the `SignedInfo` as an EXI fragment. */
    fun signedInfoFragment(signedInfo: SignedInfoType): ByteArray =
        AcDerSaeCodec.encodeFragment_SignedInfo(signedInfo)

    /**
     * Signs a [SignedInfoType]: SHA-512 over its EXI fragment, ECDSA-P521, returning the raw `r‖s`
     * (132-byte) SignatureValue.
     */
    fun sign(signedInfo: SignedInfoType, privateKey: PrivateKey): ByteArray =
        Signature.getInstance(JCA_ECDSA_P1363).run {
            initSign(privateKey)
            update(signedInfoFragment(signedInfo))
            sign()
        }

    /**
     * Verifies a raw `r‖s` SignatureValue against a [SignedInfoType] and public key. This checks
     * only the signature over the SignedInfo fragment — confirming that each Reference digest
     * matches its signed element is [verifyReference]'s job, and skipping it would accept a
     * correctly signed SignedInfo attached to entirely different content.
     */
    fun verify(signedInfo: SignedInfoType, signatureValue: ByteArray, publicKey: PublicKey): Boolean =
        try {
            Signature.getInstance(JCA_ECDSA_P1363).run {
                initVerify(publicKey)
                update(signedInfoFragment(signedInfo))
                verify(signatureValue)
            }
        } catch (_: java.security.SignatureException) {
            // A malformed r‖s is a failed verification, not a crash.
            false
        }

    /**
     * Signs a [SignedInfoType] with Ed448 (RFC 8032), returning the raw 114-byte SignatureValue.
     * Pure EdDSA: the fragment octets are signed directly, with no external pre-hash.
     */
    fun signEd448(signedInfo: SignedInfoType, privateKey: Ed448PrivateKeyParameters): ByteArray {
        val message = signedInfoFragment(signedInfo)
        // The empty byte array is Ed448's context string (RFC 8032 §5.2), which ISO 15118-20 leaves
        // empty. A non-empty context would produce signatures a conforming peer cannot verify.
        return Ed448Signer(ByteArray(0)).apply {
            init(true, privateKey)
            update(message, 0, message.size)
        }.generateSignature()
    }

    /**
     * Verifies a raw 114-byte Ed448 SignatureValue against a [SignedInfoType] and public key. As
     * with [verify], this covers the SignedInfo only — see [verifyReference] for the other half.
     */
    fun verifyEd448(
        signedInfo: SignedInfoType,
        signatureValue: ByteArray,
        publicKey: Ed448PublicKeyParameters,
    ): Boolean {
        val message = signedInfoFragment(signedInfo)
        return Ed448Signer(ByteArray(0)).apply {
            init(false, publicKey)
            update(message, 0, message.size)
        }.verifySignature(signatureValue)
    }

    /**
     * Confirms that a Reference's `DigestValue` equals the SHA-512 of the given signed-element
     * fragment — the second half of verification. [MessageDigest.isEqual] is constant-time.
     */
    fun verifyReference(reference: ReferenceType, signedElementFragment: ByteArray): Boolean =
        MessageDigest.isEqual(reference.digestValue, digest(signedElementFragment))
}
