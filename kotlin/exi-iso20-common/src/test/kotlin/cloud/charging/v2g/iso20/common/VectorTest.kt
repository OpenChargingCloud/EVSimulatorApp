package cloud.charging.v2g.iso20.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-20 CommonMessages Kotlin codec: decode `expectedHex`,
 * re-encode, require the bytes back. See `kotlin/README.md` for why this side runs the loop while
 * the C# suite encodes from fixtures, and for what the round trip does *not* prove on its own.
 *
 * `expectedHex` comes from EVerest's libcbv2g at a pinned commit.
 *
 * Unlike -2, this schema set has one global element per message rather than a single envelope, so
 * re-encoding needs an explicit dispatch — `decodeAny` can only promise `Any`.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_20.CommonMessages.vectors.json")
        assertTrue(f.isFile, "vector corpus not found at $f")
        JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
    }

    private fun parseHex(hex: String): ByteArray =
        hex.trim().split(" ", "\n", "\t")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun toHex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    private fun reencode(m: Any): ByteArray = when (m) {
        is SessionSetupReq            -> CommonMessagesCodec.encode(m)
        is SessionSetupRes            -> CommonMessagesCodec.encode(m)
        is AuthorizationSetupReq      -> CommonMessagesCodec.encode(m)
        is AuthorizationSetupRes      -> CommonMessagesCodec.encode(m)
        is AuthorizationReq           -> CommonMessagesCodec.encode(m)
        is AuthorizationRes           -> CommonMessagesCodec.encode(m)
        is ServiceDiscoveryReq        -> CommonMessagesCodec.encode(m)
        is ServiceDiscoveryRes        -> CommonMessagesCodec.encode(m)
        is ServiceDetailReq           -> CommonMessagesCodec.encode(m)
        is ServiceDetailRes           -> CommonMessagesCodec.encode(m)
        is ServiceSelectionReq        -> CommonMessagesCodec.encode(m)
        is ServiceSelectionRes        -> CommonMessagesCodec.encode(m)
        is ScheduleExchangeReq        -> CommonMessagesCodec.encode(m)
        is ScheduleExchangeRes        -> CommonMessagesCodec.encode(m)
        is PowerDeliveryReq           -> CommonMessagesCodec.encode(m)
        is PowerDeliveryRes           -> CommonMessagesCodec.encode(m)
        is MeteringConfirmationReq    -> CommonMessagesCodec.encode(m)
        is MeteringConfirmationRes    -> CommonMessagesCodec.encode(m)
        is SessionStopReq             -> CommonMessagesCodec.encode(m)
        is SessionStopRes             -> CommonMessagesCodec.encode(m)
        is CertificateInstallationReq -> CommonMessagesCodec.encode(m)
        is CertificateInstallationRes -> CommonMessagesCodec.encode(m)
        is VehicleCheckInReq          -> CommonMessagesCodec.encode(m)
        is VehicleCheckInRes          -> CommonMessagesCodec.encode(m)
        is VehicleCheckOutReq         -> CommonMessagesCodec.encode(m)
        is VehicleCheckOutRes         -> CommonMessagesCodec.encode(m)
        is SignedInstallationData     -> CommonMessagesCodec.encode(m)
        is SignedMeteringData         -> CommonMessagesCodec.encode(m)
        else -> error("no encode overload for ${m::class.simpleName}")
    }

    @Test
    fun `round-trips every CommonMessages vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                reencode(CommonMessagesCodec.decodeAny(expected))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-20 CommonMessages vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
