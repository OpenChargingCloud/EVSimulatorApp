package cloud.charging.v2g.iso20.acdp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-20 ACDP Kotlin codec: decode `expectedHex`, re-encode,
 * require the bytes back. See `kotlin/README.md` for what this loop does and does not prove.
 *
 * `expectedHex` comes from EVerest's libcbv2g at a pinned commit.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_20.ACDP.vectors.json")
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
        is ACDP_VehiclePositioningReq -> ACDPCodec.encode(m)
        is ACDP_VehiclePositioningRes -> ACDPCodec.encode(m)
        is ACDP_ConnectReq            -> ACDPCodec.encode(m)
        is ACDP_ConnectRes            -> ACDPCodec.encode(m)
        is ACDP_DisconnectReq         -> ACDPCodec.encode(m)
        is ACDP_DisconnectRes         -> ACDPCodec.encode(m)
        is ACDP_SystemStatusReq       -> ACDPCodec.encode(m)
        is ACDP_SystemStatusRes       -> ACDPCodec.encode(m)
        else -> error("no encode overload for ${m::class.simpleName}")
    }

    @Test
    fun `round-trips every ACDP vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                reencode(ACDPCodec.decodeAny(expected))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-20 ACDP vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
