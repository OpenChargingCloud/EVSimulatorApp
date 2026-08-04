package cloud.charging.v2g.iso20.acderiec

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-20 AC_DER_IEC Kotlin codec: decode `expectedHex`, re-encode,
 * require the bytes back. See `kotlin/README.md` for why this side runs the loop while the C#
 * suite encodes from fixtures, and for what the round trip does *not* prove on its own.
 *
 * This corpus has two provenances and says which per vector. Six of its plain-AC vectors carry
 * cbV2G's bytes — the DER grammar was measured to encode those messages identically to plain AC,
 * so the reference encoder is valid for them. The rest are the C# back end's own output, because
 * cbexigen does not generate the Amendment 1 DER schemas: for those this test is a cross-language
 * agreement check between two ports of the same grammar, not evidence of wire conformance.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_20.AC_DER_IEC.vectors.json")
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
        is AC_ChargeParameterDiscoveryReq -> AcDerIecCodec.encode(m)
        is AC_ChargeParameterDiscoveryRes -> AcDerIecCodec.encode(m)
        is AC_ChargeLoopReq               -> AcDerIecCodec.encode(m)
        is AC_ChargeLoopRes               -> AcDerIecCodec.encode(m)
        is AC_CPDReqEnergyTransferMode    -> AcDerIecCodec.encode(m)
        is AC_CPDResEnergyTransferMode    -> AcDerIecCodec.encode(m)
        else -> error("no encode overload for ${m::class.simpleName}")
    }

    @Test
    fun `round-trips every AC_DER_IEC vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                reencode(AcDerIecCodec.decodeAny(expected))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-20 AC_DER_IEC vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
