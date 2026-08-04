package cloud.charging.v2g.iso2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate for the generated EXI **fragment** codecs — the encoding XMLDSig digests: EXI header,
 * the element's fragment-grammar event code, its content, End Fragment. No document or body
 * wrapper.
 *
 * `expectedHex` comes from EVerest's libcbv2g (`encode_iso2_exiFragment`) at a pinned commit. As
 * everywhere else on this side, the loop is decode → re-encode; see `kotlin/README.md` for why,
 * and for what that does not prove on its own.
 */
class FragmentTest {

    private val vectors: List<JsonObject> by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/WWCP_ISO15118_EXI_Tests/Vectors/Iso15118_2.fragments.vectors.json")
        assertTrue(f.isFile, "fragment corpus not found at $f")
        JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
    }

    private fun parseHex(hex: String): ByteArray =
        hex.trim().split(" ", "\n", "\t")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun toHex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    private fun roundTrip(element: String, bytes: ByteArray): ByteArray = when (element) {
        "AuthorizationReq"   -> Iso15118_2Codec.encodeFragment_AuthorizationReq(
                                    Iso15118_2Codec.decodeFragment_AuthorizationReq(bytes))
        "MeteringReceiptReq" -> Iso15118_2Codec.encodeFragment_MeteringReceiptReq(
                                    Iso15118_2Codec.decodeFragment_MeteringReceiptReq(bytes))
        "SalesTariff"        -> Iso15118_2Codec.encodeFragment_SalesTariff(
                                    Iso15118_2Codec.decodeFragment_SalesTariff(bytes))
        "SignedInfo"         -> Iso15118_2Codec.encodeFragment_SignedInfo(
                                    Iso15118_2Codec.decodeFragment_SignedInfo(bytes))
        else -> error("no fragment codec for '$element'")
    }

    @Test
    fun `round-trips every ISO 15118-2 fragment vector`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = parseHex(v.get("expectedHex").asString)

            val actual = try {
                roundTrip(v.get("element").asString, expected)
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }

            if (!actual.contentEquals(expected)) {
                failures += "$name:\n    expected (${expected.size}): ${toHex(expected)}\n" +
                            "    actual   (${actual.size}): ${toHex(actual)}"
            }
        }

        println("ISO 15118-2 fragment vectors: ${vectors.size - failures.size}/${vectors.size} round-trip byte-exact")
        assertTrue(failures.isEmpty(), "fragment round-trip mismatches:\n" + failures.joinToString("\n"))
    }
}
