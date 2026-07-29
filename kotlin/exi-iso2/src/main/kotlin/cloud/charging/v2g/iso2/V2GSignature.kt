package cloud.charging.v2g.iso2

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * XMLDSig signing and verification for ISO 15118-2 (§7.9 / Annex J), mirroring the C# `V2GSignature`.
 *
 * A V2G signature has two levels of digest:
 *
 *  1. each signed element is encoded as an EXI **fragment** and SHA-256 digested; that digest goes
 *     into a [ReferenceType] inside [SignedInfoType];
 *  2. the `SignedInfo` is itself encoded as an EXI fragment, SHA-256 digested, and signed with
 *     ECDSA over NIST P-256.
 *
 * The `SignatureValue` on the wire is the raw `r‖s` pair (32 + 32 bytes, IEEE P1363), **not** the
 * ASN.1/DER encoding the JCA hands out by default — ISO 15118-2 fixes the plain concatenation.
 * That is why the algorithm name below is the `inP1363Format` variant rather than
 * `SHA256withECDSA`; getting this wrong yields a signature that verifies locally against itself
 * and is rejected by every conforming peer.
 *
 * All fragment bytes come from the generated, cbV2G-byte-exact fragment codecs, so the digests
 * match what a conforming peer computes.
 */
object V2GSignature {

    /** EXI canonicalization — the only C14N ISO 15118-2 uses. */
    const val CANONICALIZATION_EXI = "http://www.w3.org/TR/canonical-exi/"

    /** ECDSA-SHA256 signature method. */
    const val ECDSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"

    /** SHA-256 digest method. */
    const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"

    /**
     * JCA name for ECDSA-SHA256 producing the raw `r‖s` form ISO 15118 requires. Available from
     * Java 9 in the SunEC provider.
     */
    private const val JCA_ECDSA_P1363 = "SHA256withECDSAinP1363Format"

    /** SHA-256 of an element's EXI fragment — the value that goes into its `DigestValue`. */
    fun digest(fragmentBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(fragmentBytes)

    /**
     * A single-reference [SignedInfoType] over one already-computed element digest, with the fixed
     * EXI-C14N / ECDSA-SHA256 / SHA-256 algorithm URIs. The reference URI is `"#" + referenceId` —
     * the `Id` attribute of the signed element.
     */
    fun buildSignedInfo(referenceId: String, digest: ByteArray): SignedInfoType =
        buildSignedInfo(listOf(referenceId to digest))

    /**
     * A multi-reference [SignedInfoType] — one reference per signed element, all under ONE header
     * signature (for example every signed `SalesTariff` of an SAScheduleList offer, §7.9.2.5).
     *
     * [includeExiTransform] adds the schema-optional `Transforms` list holding the single EXI-C14N
     * transform. Some peers (Josev's pydantic models) treat it as mandatory and fail message
     * validation on a Reference without it, so it is offered rather than assumed either way.
     */
    fun buildSignedInfo(
        references: List<Pair<String, ByteArray>>,
        includeExiTransform: Boolean = false,
    ): SignedInfoType =
        SignedInfoType(
            id = null,
            canonicalizationMethod = CanonicalizationMethodType(algorithm = CANONICALIZATION_EXI, aNY = null),
            signatureMethod = SignatureMethodType(algorithm = ECDSA_SHA256, hMACOutputLength = null, aNY = null),
            reference = references.map { (referenceId, digest) ->
                ReferenceType(
                    id = null,
                    type = null,
                    uRI = "#$referenceId",
                    transforms = if (includeExiTransform)
                        TransformsType(listOf(TransformType(CANONICALIZATION_EXI, xPath = null, aNY = null)))
                    else null,
                    digestMethod = DigestMethodType(algorithm = SHA256, aNY = null),
                    digestValue = digest,
                )
            },
        )

    /**
     * The header [SignatureType] from a signed `SignedInfo` and its raw `r‖s` `SignatureValue`
     * (KeyInfo and Object absent, as ISO 15118-2 uses).
     */
    fun buildSignature(signedInfo: SignedInfoType, signatureValue: ByteArray): SignatureType =
        SignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = SignatureValueType(id = null, value = signatureValue),
            keyInfo = null,
            `object` = null,
        )

    /** The exact octets that are SHA-256'd and signed: the `SignedInfo` as an EXI fragment. */
    fun signedInfoFragment(signedInfo: SignedInfoType): ByteArray =
        Iso15118_2Codec.encodeFragment_SignedInfo(signedInfo)

    /**
     * Signs a [SignedInfoType]: SHA-256 over its EXI fragment, ECDSA-P256, returning the raw
     * `r‖s` (64-byte) `SignatureValue`.
     */
    fun sign(signedInfo: SignedInfoType, privateKey: PrivateKey): ByteArray =
        Signature.getInstance(JCA_ECDSA_P1363).run {
            initSign(privateKey)
            update(signedInfoFragment(signedInfo))
            sign()
        }

    /**
     * Verifies a raw `r‖s` `SignatureValue` against a [SignedInfoType] and public key. This only
     * checks the ECDSA signature over the SignedInfo fragment — confirming that each reference
     * digest matches its signed element is [verifyReference]'s job, and skipping it would accept a
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
     * Confirms that a reference's `DigestValue` equals the SHA-256 of the given signed-element
     * fragment — the second half of verification. Uses [MessageDigest.isEqual], which is
     * constant-time.
     */
    fun verifyReference(reference: ReferenceType, signedElementFragment: ByteArray): Boolean =
        MessageDigest.isEqual(reference.digestValue, digest(signedElementFragment))
}
