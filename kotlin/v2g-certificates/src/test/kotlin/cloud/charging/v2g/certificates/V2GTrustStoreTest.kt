package cloud.charging.v2g.certificates

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MO root store, and the question a scanned root actually poses.
 *
 * The four candidates come from the shared corpus rather than being built here: what is under test
 * is the *relationship* between two certificates, and three languages each constructing their own
 * would be three languages agreeing with themselves.
 *
 * The one shape that cannot be exported is a compromised key — it produces the same bytes as an
 * honest rotation, which is precisely the point — so that limit is asserted directly below.
 */
class V2GTrustStoreTest {

    private val rotation: JsonObject by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "../ISO15118ConformanceTests.Simulation/" +
                             "Vectors/Certificate.chain.vectors.json")
        JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("rootRotation")
    }

    private fun certificate(field: String) = V2GCertificate(
        rotation.get(field).asString.let { s ->
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        })

    private fun storeWithTrustedRoot() = InMemoryTrustStore(listOf(certificate("trusted")))


    @Test
    fun anUnknownRootIsNew() {
        assertEquals(V2GRootInstallVerdict.New,
                     InMemoryTrustStore().verdict(certificate("trusted")))
    }

    @Test
    fun theSameCertificateTwiceIsAlreadyTrusted() {
        assertEquals(V2GRootInstallVerdict.AlreadyTrusted,
                     storeWithTrustedRoot().verdict(certificate("trusted")))
    }

    /** Same name, same key, later dates: the CA re-issued itself. A user who trusted it still does. */
    @Test
    fun sameSubjectAndSameKeyIsARenewal() {
        assertEquals(V2GRootInstallVerdict.Renewal,
                     storeWithTrustedRoot().verdict(certificate("renewal")))
    }

    /**
     * **The case this whole distinction exists for.**
     *
     * Same name, different key, nobody vouching. A user's mental model reads "update"; it is a
     * different CA wearing a known name, and nothing about the earlier decision carries over. If this
     * returned `Renewal`, an attacker would only need to name their root after one already trusted.
     */
    @Test
    fun sameSubjectWithADifferentKeyIsAReplacementNotARenewal() {
        val verdict = storeWithTrustedRoot().verdict(certificate("stranger"))
        assertEquals(V2GRootInstallVerdict.ReplacementUnderKnownName, verdict)
    }

    /** The friendly rotation: the CA introduces its successor by signing it with the key it still
     *  has. Cryptographic continuity, so the dialog can say something stronger than "this is new". */
    @Test
    fun aSuccessorSignedByATrustedRootIsVouchedFor() {

        val store   = storeWithTrustedRoot()
        val vouched = certificate("vouched")
        val verdict = store.verdict(vouched)

        assertTrue(verdict is V2GRootInstallVerdict.VouchedByTrustedRoot,
                   "expected a vouched rotation, got $verdict")
        assertEquals(V2GFingerprint.of(certificate("trusted").der),
                     (verdict as V2GRootInstallVerdict.VouchedByTrustedRoot).fingerprintOfVouchingRoot)

        assertTrue(store.defects(vouched, Date(1_767_225_600_000L)).isEmpty(),
                   "a link certificate is not self-signed, and that is not a defect")
    }

    /** The same successor offered to a store without the voucher is a stranger — and not an anchor
     *  at all, since nothing self-signs it and nothing vouches. */
    @Test
    fun theSameSuccessorWithoutTheVoucherIsNotAnAnchorAtAll() {

        val store   = InMemoryTrustStore()
        val vouched = certificate("vouched")

        assertEquals(V2GRootInstallVerdict.New, store.verdict(vouched))
        assertTrue(store.defects(vouched, Date(1_767_225_600_000L))
                        .any { it is V2GRootDefect.NeitherSelfSignedNorVouched })
    }

    /**
     * **What vouching is worth when the old key is in the wrong hands.**
     *
     * The corpus cannot carry this case, because a stolen key produces bytes indistinguishable from
     * an honest rotation — so it is asserted structurally instead: the verdict rests on *the key that
     * signed*, and says nothing about who was holding it. Nobody should later mistake
     * `VouchedByTrustedRoot` for proof.
     */
    @Test
    fun vouchingIsAStatementAboutAKeyNotAboutItsHolder() {

        val store   = storeWithTrustedRoot()
        val vouched = certificate("vouched")

        // The signature is the whole basis. Remove the voucher from the store and the same
        // certificate stops being vouched for — nothing about the certificate itself changed.
        assertTrue(store.verdict(vouched) is V2GRootInstallVerdict.VouchedByTrustedRoot)
        assertEquals(V2GRootInstallVerdict.New, InMemoryTrustStore().verdict(vouched))
    }

    @Test
    fun addingIsIdempotentAndRemovalIsByFingerprint() {

        val store  = InMemoryTrustStore()
        val anchor = certificate("trusted")

        store.add(anchor)
        store.add(anchor)
        assertEquals(1, store.roots.size, "the same root twice is one root")

        val fingerprint = V2GFingerprint.of(anchor.der)
        assertTrue(store.trusts(fingerprint))

        store.remove(fingerprint)
        assertTrue(store.roots.isEmpty())
        assertFalse(store.trusts(fingerprint))
    }

    /** The fingerprint is what a user compares by eye, so its shape is part of the interface — and it
     *  must read the same in every back end. */
    @Test
    fun theFingerprintIsGroupedUppercaseHex() {

        val printed = V2GFingerprint.of(certificate("trusted").der).toString()

        assertEquals(32 * 2 + 31, printed.length, "32 bytes as pairs, separated")
        assertEquals(31, printed.count { it == ':' })
        assertEquals(printed.uppercase(), printed)
    }
}
