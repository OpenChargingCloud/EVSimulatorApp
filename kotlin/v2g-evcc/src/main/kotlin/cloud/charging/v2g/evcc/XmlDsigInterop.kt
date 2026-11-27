package cloud.charging.v2g.evcc

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

import cloud.charging.v2g.keystore.V2GSignatureHash
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
 *
 * ## Which algorithms
 *
 * -2 is always `ecdsa-sha256` over a SHA-256 digest. -20 follows an [XmlDsigSuite]: `ecdsa-sha512`
 * over SHA-512 by default, Josev's SHA-256 pair on request.
 */
internal object XmlDsigInterop {

    private const val CANONICALIZATION_EXI = "http://www.w3.org/TR/canonical-exi/"

    /** Signs one -2 element: digests its fragment, assembles SignedInfo, signs the standalone form.
     *  -2 has one suite, and it is the SHA-256 pair. */
    fun sign2(referenceId: String, signedElementFragment: ByteArray, contractKey: V2GSigner): I2SignatureType {

        val suite = XmlDsigSuite.JosevSha256
        val signedInfo = I2SignedInfoType(
            id = null,
            canonicalizationMethod = I2CanonicalizationMethodType(CANONICALIZATION_EXI, null),
            signatureMethod = I2SignatureMethodType(suite.signatureMethod, null, null),
            reference = listOf(I2ReferenceType(
                id = null, type = null, uRI = "#$referenceId",
                transforms = I2TransformsType(listOf(I2TransformType(CANONICALIZATION_EXI, null, null))),
                digestMethod = I2DigestMethodType(suite.digestMethod, null),
                digestValue = suite.digest(signedElementFragment))))

        return I2SignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = I2SignatureValueType(null, contractKey.signature(standaloneOctets(signedInfo), suite.hash)),
            keyInfo = null,
            `object` = null)
    }

    /** The -20 CommonMessages counterpart. Five near-identical copies of this exist in the C# side,
     *  one per message set, for the reason the class comment gives — this port needs one so far. */
    fun sign20(referenceId: String, signedElementFragment: ByteArray, contractKey: V2GSigner,
               suite: XmlDsigSuite): CSignatureType {

        val signedInfo = CSignedInfoType(
            id = null,
            canonicalizationMethod = CCanonicalizationMethodType(CANONICALIZATION_EXI, null),
            signatureMethod = CSignatureMethodType(suite.signatureMethod, null, null),
            reference = listOf(CReferenceType(
                id = null, type = null, uRI = "#$referenceId",
                transforms = CTransformsType(listOf(CTransformType(CANONICALIZATION_EXI, null, null))),
                digestMethod = CDigestMethodType(suite.digestMethod, null),
                digestValue = suite.digest(signedElementFragment))))

        return CSignatureType(
            id = null,
            signedInfo = signedInfo,
            signatureValue = CSignatureValueType(null, contractKey.signature(standaloneOctets(signedInfo), suite.hash)),
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

    /**
     * Verifies raw `r‖s` over [octets] under the hash that [signatureMethod] declares. An algorithm
     * this form does not know is `false`, not an exception and not a guess. The hash comes from the
     * declaration because a verifier with its own fixed hash would refuse every signature made
     * under the other suite.
     */
    fun verify(octets: ByteArray, signatureValue: ByteArray, publicKey: PublicKey,
               signatureMethod: String): Boolean {
        val hash = XmlDsigSuite.entries.firstOrNull { it.signatureMethod == signatureMethod }?.hash
            ?: return false
        return runCatching {
            Signature.getInstance(hash.jcaP1363).apply {
                initVerify(publicKey)
                update(octets)
            }.verify(signatureValue)
        }.getOrDefault(false)
    }
}


/**
 * Which algorithms a -20 header signature declares and uses. A port of C# `XmlDsigSuite`.
 *
 * The grammar is the same for both: the `SignedInfo` is signed in its standalone xmldsig encoding,
 * the entry point [V2G20-1449] names. Josev and EVerest 2026.10.0 both verify those octets.
 *
 * - [Iso20] is `ecdsa-sha512` over a SHA-512 digest ([V2G20-2473], [V2G20-2475]). It is the default.
 *   Our car signed SHA-256 until 2026-11-27, and EVerest refused it with `FAILED_SignatureError`.
 * - [JosevSha256] is `ecdsa-sha256` over SHA-256, which is all Josev verifies. -2 always uses it.
 */
enum class XmlDsigSuite(
    val signatureMethod: String,
    val digestMethod: String,
    val hash: V2GSignatureHash,
    private val digestName: String,
) {
    Iso20("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512", "http://www.w3.org/2001/04/xmlenc#sha512",
          V2GSignatureHash.SHA512, "SHA-512"),
    JosevSha256("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256", "http://www.w3.org/2001/04/xmlenc#sha256",
                V2GSignatureHash.SHA256, "SHA-256");

    /** The `DigestValue` of a reference under this suite. */
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance(digestName).digest(bytes)
}


/**
 * Contract credentials that switch an EVCC from EIM to **Plug & Charge**. Null (the default) keeps
 * the session on external payment.
 *
 * @param contractCertificate the contract leaf certificate, DER
 * @param subCertificates the MO sub-CA certificates, DER, leaf-issuer first
 * @param contractKey a signer for the contract leaf — P-256 for -2, whose raw `r‖s` field is 64
 *   bytes. A [V2GSigner] rather than a `PrivateKey` because a secure-element key **has no bytes to
 *   hand over** (`docs/CONCEPT.md` §3.4); taking a key here would have ruled out hardware backing
 *   for every credential, whatever its curve.
 * @param suite the algorithms of a -20 `AuthorizationReq` signature; [XmlDsigSuite.Iso20] unless a
 *   Josev station is on the other end. -2 ignores it.
 */
class PncEvccOptions(
    val contractCertificate: ByteArray,
    val subCertificates: List<ByteArray>,
    val contractKey: V2GSigner,
    val suite: XmlDsigSuite = XmlDsigSuite.Iso20,
)
