package cloud.charging.v2g.iso20.dc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-20 DC Kotlin codec: decode `expectedHex`, re-encode,
 * require the bytes back. See `kotlin/README.md` for why this side runs the loop while the C#
 * suite encodes from fixtures, and for what the round trip does *not* prove on its own.
 *
 * `expectedHex` comes from EVerest's libcbv2g at a pinned commit.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "EVSimulatorApp.slnx").isFile) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_20.DC.vectors.json")
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
        is DC_ChargeParameterDiscoveryReq -> DCCodec.encode(m)
        is DC_ChargeParameterDiscoveryRes -> DCCodec.encode(m)
        is DC_CableCheckReq               -> DCCodec.encode(m)
        is DC_CableCheckRes               -> DCCodec.encode(m)
        is DC_PreChargeReq                -> DCCodec.encode(m)
        is DC_PreChargeRes                -> DCCodec.encode(m)
        is DC_ChargeLoopReq               -> DCCodec.encode(m)
        is DC_ChargeLoopRes               -> DCCodec.encode(m)
        is DC_WeldingDetectionReq         -> DCCodec.encode(m)
        is DC_WeldingDetectionRes         -> DCCodec.encode(m)
        is DC_CPDReqEnergyTransferMode    -> DCCodec.encode(m)
        is DC_CPDResEnergyTransferMode    -> DCCodec.encode(m)
        else -> error("no encode overload for ${m::class.simpleName}")
    }

    @Test
    fun `round-trips every DC vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                reencode(DCCodec.decodeAny(expected))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-20 DC vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
