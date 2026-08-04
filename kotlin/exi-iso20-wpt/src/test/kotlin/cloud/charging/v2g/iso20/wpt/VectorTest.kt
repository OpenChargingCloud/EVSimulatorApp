package cloud.charging.v2g.iso20.wpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-20 WPT Kotlin codec: decode `expectedHex`, re-encode,
 * require the bytes back. See `kotlin/README.md` for what this loop does and does not prove.
 *
 * `expectedHex` comes from EVerest's libcbv2g at a pinned commit — **except** for anything routed
 * through `WPT_LF_TransmitterDataType`'s `TxSpecData` list, whose grammar cbV2G cannot encode at
 * all (see `kotlin/README.md`, "Unvalidated construct"). If a vector ever starts covering that
 * path, its bytes are this project's own design rather than a reference.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors/Iso15118_20.WPT.vectors.json")
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
        is WPT_FinePositioningSetupReq     -> WPTCodec.encode(m)
        is WPT_FinePositioningSetupRes     -> WPTCodec.encode(m)
        is WPT_FinePositioningReq          -> WPTCodec.encode(m)
        is WPT_FinePositioningRes          -> WPTCodec.encode(m)
        is WPT_PairingReq                  -> WPTCodec.encode(m)
        is WPT_PairingRes                  -> WPTCodec.encode(m)
        is WPT_ChargeParameterDiscoveryReq -> WPTCodec.encode(m)
        is WPT_ChargeParameterDiscoveryRes -> WPTCodec.encode(m)
        is WPT_AlignmentCheckReq           -> WPTCodec.encode(m)
        is WPT_AlignmentCheckRes           -> WPTCodec.encode(m)
        is WPT_ChargeLoopReq               -> WPTCodec.encode(m)
        is WPT_ChargeLoopRes               -> WPTCodec.encode(m)
        else -> error("no encode overload for ${m::class.simpleName}")
    }

    @Test
    fun `round-trips every WPT vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                reencode(WPTCodec.decodeAny(expected))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-20 WPT vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
