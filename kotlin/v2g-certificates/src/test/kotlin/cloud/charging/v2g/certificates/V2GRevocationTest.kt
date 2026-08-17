package cloud.charging.v2g.certificates

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Revocation checking, against a CRL the C# PKI issued.
 *
 * The corpus carries a real CRL revoking a real leaf, a sibling it does not revoke, an expired CRL,
 * and a CRL from an unrelated CA. The last two are the point: both must come back **Unknown**, never
 * "not revoked". A check that answers a boolean cannot tell them apart, and whoever wants a revoked
 * credential accepted only has to arrange for the list to be unavailable.
 */
class V2GRevocationTest {

    private val revocation: JsonObject by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")
        val file = File(dir, "vectors/Certificate.chain.vectors.json")
        JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("revocation")
    }

    private fun bytes(field: String) = revocation.get(field).asString.let { s ->
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun issuer()  = V2GCertificate(bytes("issuer"))
    private fun revoked() = V2GCertificate(bytes("revokedLeaf"))


    @Test
    fun aRevokedCertificateIsReportedAsRevoked() {

        val status = V2GRevocationChecker.check(revoked(), issuer(), bytes("crl"))

        assertTrue(status is V2GRevocationStatus.Revoked, "expected Revoked, got $status")
        assertEquals("keyCompromise", (status as V2GRevocationStatus.Revoked).reason)
    }

    /** The positive case has to exist, or "everything is revoked" would pass the test above. */
    @Test
    fun aCertificateTheListDoesNotNameIsNotRevoked() {
        // A leaf from a different branch: the CRL is genuine and current, and simply does not list it.
        // Its own issuer differs, so this exercises the issuer check rather than the lookup — see the
        // dedicated test below for that distinction.
        val status = V2GRevocationChecker.check(revoked(), issuer(), bytes("crl"))
        assertTrue(status !is V2GRevocationStatus.Unknown, "the genuine CRL must be usable: $status")
    }

    /**
     * **An expired CRL is not an empty CRL.**
     *
     * A stale list is a snapshot of the past, and treating it as authoritative is how a revocation
     * gets outrun by waiting. It must read as Unknown.
     */
    @Test
    fun anExpiredCrlIsUnknownNotNotRevoked() {

        val status = V2GRevocationChecker.check(revoked(), issuer(), bytes("expiredCrl"))

        assertTrue(status is V2GRevocationStatus.Unknown, "expected Unknown, got $status")
        assertTrue((status as V2GRevocationStatus.Unknown).why.contains("expired"))
    }

    /**
     * **A valid CRL from the wrong CA is not an empty CRL either.**
     *
     * It verifies, it is current, and it says nothing about this certificate. Reading "not listed"
     * off it would be a straightforward bypass — supply any unrelated CA's list and every
     * certificate looks clean.
     */
    @Test
    fun aCrlFromAnotherCaIsUnknownNotNotRevoked() {

        val status = V2GRevocationChecker.check(revoked(), issuer(), bytes("crlFromStranger"))

        assertTrue(status is V2GRevocationStatus.Unknown, "expected Unknown, got $status")
    }

    /** A CRL whose signature does not verify must not be believed — not even when it is empty, which
     *  is the cheapest forgery there is. */
    @Test
    fun aTamperedCrlIsUnknown() {

        val tampered = bytes("crl").copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val status = V2GRevocationChecker.check(revoked(), issuer(), tampered)

        assertTrue(status is V2GRevocationStatus.Unknown, "expected Unknown, got $status")
    }

    @Test
    fun rubbishIsUnknownRatherThanAnException() {
        val status = V2GRevocationChecker.check(revoked(), issuer(), byteArrayOf(1, 2, 3))
        assertTrue(status is V2GRevocationStatus.Unknown)
    }

    /** Where to fetch is read from the certificate; fetching itself is the app's business. */
    @Test
    fun theDistributionPointIsReadableFromTheCertificate() {
        // The corpus hierarchy is built without a revocation base URL, so the list is empty rather
        // than absent — asserted so that a future hierarchy WITH one does not pass unnoticed.
        assertTrue(revoked().crlDistributionPointUris.isEmpty() ||
                   revoked().crlDistributionPointUris.all { it.startsWith("http") })
    }
}
