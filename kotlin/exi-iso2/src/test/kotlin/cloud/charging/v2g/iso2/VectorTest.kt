package cloud.charging.v2g.iso2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated ISO 15118-2 Kotlin codec.
 *
 * The C# suite drives the same corpus the other way round: it builds each message from a
 * hand-written fixture, encodes it, and compares against `expectedHex`. Porting those ~250 lines
 * of fixtures to Kotlin would duplicate a lot of hand-written state with no added signal, so this
 * side runs the loop instead — **decode `expectedHex`, re-encode, require the bytes back**.
 *
 * What that buys, and what it does not:
 *
 *  - It exercises the decoder, which the C# vector test never touches.
 *  - Every byte of every vector must be consumed and reproduced, so a wrong event code or width
 *    shows up as soon as it is not perfectly symmetric.
 *  - **It cannot catch a mirror-image bug.** If encode and decode are wrong in exactly opposite
 *    ways — say both use width 3 where the grammar wants 2 — the round trip still closes. What
 *    rules that out is a separate check: every one of the 170 generated -2 codec functions was
 *    compared operation-by-operation against the C# emitter's output from the same SchemaPlan,
 *    and the C# side *is* pinned to these vectors. The two together are the real gate.
 *
 * `expectedHex` comes from EVerest's libcbv2g at a pinned commit, via `tools/cbv2g-ref/main_iso2.c`.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        // Walk up to the repository root, then into the C# test project — the corpus is shared
        // deliberately rather than copied, so the two back ends can never drift apart.
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_2.vectors.json")
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

    @Test
    fun `round-trips every ISO 15118-2 vector through decode and re-encode`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                val decoded = Iso15118_2Codec.decodeAny(expected)
                Iso15118_2Codec.encode(decoded as V2G_Message)
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-2 vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
