package cloud.charging.v2g.evcc

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

import cloud.charging.v2g.keystore.V2GSigner
import cloud.charging.v2g.xmldsig.XmlDsigCodec
import cloud.charging.v2g.xmldsig.CanonicalizationMethodType as XCanonicalizationMethodType
import cloud.charging.v2g.xmldsig.DigestMethodType as XDigestMethodType
import cloud.charging.v2g.xmldsig.ReferenceType as XReferenceType
import cloud.charging.v2g.xmldsig.SignatureMethodType as XSignatureMethodType
import cloud.charging.v2g.xmldsig.SignedInfoType as XSignedInfoType
import cloud.charging.v2g.xmldsig.TransformType as XTransformType
import cloud.charging.v2g.xmldsig.TransformsType as XTransformsType

import cloud.charging.v2g.iso2.CanonicalizationMethodType as I2CanonicalizationMethodType
import cloud.charging.v2g.iso2.DigestMethodType as I2DigestMethodType
import cloud.charging.v2g.iso2.ReferenceType as I2ReferenceType
import cloud.charging.v2g.iso2.SignatureMethodType as I2SignatureMethodType
import cloud.charging.v2g.iso2.SignatureType as I2SignatureType
import cloud.charging.v2g.iso2.SignatureValueType as I2SignatureValueType
import cloud.charging.v2g.iso2.SignedInfoType as I2SignedInfoType
import cloud.charging.v2g.iso2.TransformType as I2TransformType
import cloud.charging.v2g.iso2.TransformsType as I2TransformsType

import cloud.charging.v2g.iso20.common.CanonicalizationMethodType as CCanonicalizationMethodType
import cloud.charging.v2g.iso20.common.DigestMethodType as CDigestMethodType
import cloud.charging.v2g.iso20.common.ReferenceType as CReferenceType
import cloud.charging.v2g.iso20.common.SignatureMethodType as CSignatureMethodType
import cloud.charging.v2g.iso20.common.SignatureType as CSignatureType
import cloud.charging.v2g.iso20.common.SignatureValueType as CSignatureValueType
import cloud.charging.v2g.iso20.common.SignedInfoType as CSignedInfoType
import cloud.charging.v2g.iso20.common.TransformType as CTransformType
import cloud.charging.v2g.iso20.common.TransformsType as CTransformsType

/**
 * Signing a Plug & Charge request, in the form a live Josev peer produces and accepts.
 *
 * ## The one non-obvious thing
 *
 * The `SignedInfo` that travels **in the message** is encoded under its own message set's grammar,
 * but the octets that are actually **signed** are the same `SignedInfo` encoded under the *standalone*
 * W3C xmldsig grammar — the one `exi-xmldsig` exists for. Those are different bytes for the same
 * structure, because the fragment selector is sized over a different set of global elements. Signing
 * the message-set encoding instead would produce a signature that verifies locally and nowhere else,
 * which is the failure this whole arrangement exists to avoid. A port of the C# `XmlDsigInterop2` /
 * `XmlDsigInteropSign`, down to the mapping step in the middle.
 *
 * ## Why the mapping is a copy and not a cast
 *
 * `SignedInfoType` is generated three times over — once per message set, once standalone — from the
 * same `xmldsig-core-schema.xsd`. They are structurally identical and mutually unassignable, exactly
 * like the three `MessageHeaderType`s that [SessionContext] reconciles. So the map below is a
 * field-for-field copy that looks like boilerplate and is not.
 */
internal object XmlDsigInterop {

    private const val CANONICALIZATION_EXI = "http://www.w3.org/TR/canonical-exi/"
    private const val ECDSA_SHA256         = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"
    private const val SHA256_URI           = "http://www.w3.org/2001/04/xmlenc#sha256"

    /** Raw `r‖s`, not DER — the field is 64 bytes and a DER signature does not fit, so the usual
     *  trap fails loudly rather than silently. */
    private const val JCA_P1363 = "SHA256withECDSAinP1363Format"

    /** Signs one -2 element: digests its fragment, assembles SignedInfo, signs the standalone form. */
    fun sign2(referenceId: String, signedElementFragment: ByteArray, contractKey: V2GSigner): I2SignatureType {

        val signedInfo = I2SignedInfoType(
            id = null,
            canonicalizationMethod = I2CanonicalizationMethodType(CANONICALIZATION_EXI, null),
            signatureMethod = I2SignatureMethodType(ECDSA_SHA256, null, null),
            reference = listOf(I2ReferenceType(
                id = null, type = null, uRI = "#$referenceId",
                transforms = I2TransformsType(listOf(I2TransformType(CANONICALIZATION_EXI, null, null))),
                digestMethod = I2DigestMethodType(SHA256_URI, null),
                digestValue = sha256(signedElementFragment))))

        return I2SignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = I2SignatureValueType(null, signRaw(standaloneOctets(signedInfo), contractKey)),
            keyInfo = null,
            `object` = null)
    }

    /** The -20 CommonMessages counterpart. Five near-identical copies of this exist in the C# side,
     *  one per message set, for the reason the class comment gives — this port needs one so far. */
    fun sign20(referenceId: String, signedElementFragment: ByteArray, contractKey: V2GSigner): CSignatureType {

        val signedInfo = CSignedInfoType(
            id = null,
            canonicalizationMethod = CCanonicalizationMethodType(CANONICALIZATION_EXI, null),
            signatureMethod = CSignatureMethodType(ECDSA_SHA256, null, null),
            reference = listOf(CReferenceType(
                id = null, type = null, uRI = "#$referenceId",
                transforms = CTransformsType(listOf(CTransformType(CANONICALIZATION_EXI, null, null))),
                digestMethod = CDigestMethodType(SHA256_URI, null),
                digestValue = sha256(signedElementFragment))))

        return CSignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = CSignatureValueType(null, signRaw(standaloneOctets(signedInfo), contractKey)),
            keyInfo = null,
            `object` = null)
    }

    /** The octets a -2 signature is actually over. Public to the module because verifying needs
     *  exactly the same bytes signing produced, and deriving them twice is how the two drift. */
    fun standaloneOctets(signedInfo: I2SignedInfoType): ByteArray =
        XmlDsigCodec.encodeFragment_SignedInfo(XSignedInfoType(
            id = signedInfo.id,
            canonicalizationMethod = XCanonicalizationMethodType(
                signedInfo.canonicalizationMethod.algorithm, signedInfo.canonicalizationMethod.aNY),
            signatureMethod = XSignatureMethodType(
                signedInfo.signatureMethod.algorithm, signedInfo.signatureMethod.hMACOutputLength,
                signedInfo.signatureMethod.aNY),
            reference = signedInfo.reference.map { r ->
                XReferenceType(r.id, r.type, r.uRI,
                    r.transforms?.let { t ->
                        XTransformsType(t.transform.map { XTransformType(it.algorithm, it.xPath, it.aNY) })
                    },
                    XDigestMethodType(r.digestMethod.algorithm, r.digestMethod.aNY),
                    r.digestValue)
            }))

    /** The -20 counterpart. Identical shape, unassignable type — see the object's comment. */
    fun standaloneOctets(signedInfo: CSignedInfoType): ByteArray =
        XmlDsigCodec.encodeFragment_SignedInfo(XSignedInfoType(
            id = signedInfo.id,
            canonicalizationMethod = XCanonicalizationMethodType(
                signedInfo.canonicalizationMethod.algorithm, signedInfo.canonicalizationMethod.aNY),
            signatureMethod = XSignatureMethodType(
                signedInfo.signatureMethod.algorithm, signedInfo.signatureMethod.hMACOutputLength,
                signedInfo.signatureMethod.aNY),
            reference = signedInfo.reference.map { r ->
                XReferenceType(r.id, r.type, r.uRI,
                    r.transforms?.let { t ->
                        XTransformsType(t.transform.map { XTransformType(it.algorithm, it.xPath, it.aNY) })
                    },
                    XDigestMethodType(r.digestMethod.algorithm, r.digestMethod.aNY),
                    r.digestValue)
            }))

    fun verify(octets: ByteArray, signatureValue: ByteArray, publicKey: PublicKey): Boolean =
        runCatching {
            Signature.getInstance(JCA_P1363).apply {
                initVerify(publicKey)
                update(octets)
            }.verify(signatureValue)
        }.getOrDefault(false)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Which key does the work, and whether its bytes exist at all, is the signer's business and
     *  not this file's — see `V2GSigner` on why that shape is what leaves hardware backing possible. */
    private fun signRaw(octets: ByteArray, key: V2GSigner) = key.signature(octets)
}


/**
 * Contract credentials that switch an EVCC from EIM to **Plug & Charge**. Null (the default) keeps
 * the session on external payment.
 *
 * @param contractCertificate the contract leaf certificate, DER
 * @param subCertificates the MO sub-CA certificates, DER, leaf-issuer first
 * @param contractKey a signer for the contract leaf — P-256, since the interop form's raw `r‖s`
 *   field is 64 bytes. A [V2GSigner] rather than a `PrivateKey` because a secure-element key **has
 *   no bytes to hand over** (`docs/CONCEPT.md` §3.4); taking a key here would have ruled out
 *   hardware backing for every credential, whatever its curve.
 */
class PncEvccOptions(
    val contractCertificate: ByteArray,
    val subCertificates: List<ByteArray>,
    val contractKey: V2GSigner,
)
