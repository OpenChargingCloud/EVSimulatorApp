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
 * The chain validator, against the verdicts `WWCP_ISO15118_PKI` says are right — the same corpus the
 * Swift validator is held to, read out of the submodule rather than copied.
 *
 * Each case carries the two answers this design keeps apart: `trusted` — path, signatures, dates, CA
 * flags — and `findings`, the ISO 15118 profile deviations that leave a sound chain sound and still
 * worth talking about.
 *
 * The negatives come from that library's evil-certificate factory, which exists to defeat exactly the
 * validator this could have been: one that does PKIX and stops. `contract_with_serverauth` builds a
 * perfect path.
 */
class V2GChainValidatorTest {

    private val corpus: JsonObject by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile)
            dir = dir.parentFile ?: error("repository root not found")

        val file = File(dir, "../ISO15118ConformanceTests.Simulation/" +
                             "Vectors/Certificate.chain.vectors.json")
        require(file.isFile) { "chain corpus not found at $file" }
        JsonParser.parseString(file.readText()).asJsonObject
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun root() = V2GCertificate(hex(corpus.get("root").asString))

    private fun cases() = corpus.getAsJsonArray("cases").map { it.asJsonObject }

    private fun chainOf(case: JsonObject) =
        V2GCertificateChain.of(case.getAsJsonArray("chain").map { hex(it.asString) })

    /** The corpus hierarchy is built around "now"; validating at a fixed instant would make this
     *  suite fail on a calendar rather than on a defect. */
    private val validationTime = Date()


    @Test
    fun everyCorpusCaseReachesTheVerdictTheCSharpPkiSays() {

        val store = InMemoryTrustStore(listOf(root()))
        val validator = V2GChainValidator()

        for (case in cases()) {

            val name    = case.get("name").asString
            val what    = case.get("what").asString
            val verdict = validator.validate(chainOf(case), store, validationTime)

            assertEquals(case.get("trusted").asBoolean, verdict.isTrusted,
                "$name: $what\n  rejection: ${verdict.rejection?.description}")

            assertEquals(case.getAsJsonArray("findings").map { it.asString }.toSet(),
                         verdict.findings.map { it.corpusName }.toSet(),
                         "$name: profile findings differ")
        }
    }

    /**
     * The case the whole two-tier design exists for, asserted on its own so a failure names it.
     *
     * A contract certificate carrying `serverAuth` builds a perfect path — PKIX has no opinion about
     * an extra purpose. If findings were folded into the trust decision this would be rejected, and a
     * user would be told "invalid" about a chain that is entirely valid and merely wrong.
     */
    @Test
    fun aContractCertificateWithServerAuthIsTrustedAndReported() {

        val case = cases().single { it.get("name").asString == "contract_with_serverauth" }
        val verdict = V2GChainValidator().validate(chainOf(case), InMemoryTrustStore(listOf(root())),
                                                   validationTime)

        assertTrue(verdict.isTrusted, "the chain is sound; that is precisely why the finding matters")
        assertEquals(listOf(V2GProfileFinding.SERVER_AUTH_ON_CONTRACT_CERTIFICATE), verdict.findings)
    }

    /** An empty store is a distinct answer, not "everything is untrusted" by accident: an app that
     *  has installed no roots should say so rather than blaming the certificate. */
    @Test
    fun anEmptyStoreSaysSoRatherThanBlamingTheChain() {

        val good = cases().single { it.get("name").asString == "good" }
        val verdict = V2GChainValidator().validate(chainOf(good), InMemoryTrustStore(), validationTime)

        assertTrue(verdict.rejection is V2GChainRejection.NoTrustAnchors)
    }

    /** Findings survive a rejection — a bundle that is both untrusted *and* the wrong sort of
     *  certificate should say both, or the second scan is as puzzling as the first. */
    @Test
    fun findingsAreReportedEvenWhenTheChainIsRejected() {

        val case = cases().single { it.get("name").asString == "contract_with_serverauth" }
        val verdict = V2GChainValidator().validate(chainOf(case), InMemoryTrustStore(), validationTime)

        assertFalse(verdict.isTrusted)
        assertEquals(listOf(V2GProfileFinding.SERVER_AUTH_ON_CONTRACT_CERTIFICATE), verdict.findings)
    }

    /**
     * A shuffled bundle is refused, and the reason is the interesting part.
     *
     * Not "no path could be built" — one can. The order is what says *which* certificate is the
     * contract certificate, so a bundle that does not link up has not said. The Swift draft that
     * reordered and validated whatever came first trusted a sub-CA as a contract credential; this
     * corpus caught it, and the same rule applies here.
     */
    @Test
    fun aShuffledBundleIsRefusedBecauseItDoesNotSayWhichCertificateIsTheLeaf() {

        val case = cases().single { it.get("name").asString == "chain_out_of_order" }
        val chain = chainOf(case)
        val verdict = V2GChainValidator().validate(chain, InMemoryTrustStore(listOf(root())), validationTime)

        assertTrue(verdict.rejection is V2GChainRejection.BundleDoesNotLinkUp)
        assertFalse(chain.linksUp)
        assertTrue(verdict.findings.isEmpty(), "no finding about a guessed leaf is worth reporting")
    }

    /** The eMAID rule, on a real contract certificate rather than a constructed one. */
    @Test
    fun theCorpusContractCertificateCarriesAReadableEmaid() {

        val good = cases().single { it.get("name").asString == "good" }
        val leaf = chainOf(good).leaf

        assertTrue(leaf.commonName != null, "a contract certificate without a CN carries no identity")
        assertFalse(leaf.isCertificateAuthority, "a contract leaf must not be a CA")
    }
}
