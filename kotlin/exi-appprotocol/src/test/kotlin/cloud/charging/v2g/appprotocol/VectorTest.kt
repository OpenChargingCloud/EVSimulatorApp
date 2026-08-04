package cloud.charging.v2g.appprotocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate for the Kotlin back end: encode every AppProtocol vector and require the bytes to
 * match `expectedHex`.
 *
 * These vectors are **not** self-produced — `expectedHex` comes from EVerest's libcbv2g at a
 * pinned commit, the de-facto ISO 15118 reference encoder. The same file drives the C# suite,
 * so green here means the Kotlin codec is wire-conformant by the same standard as the C# one,
 * not merely consistent with it.
 */
class VectorTest {

    private val vectors: List<JsonObject> by lazy {
        // Walk up to the repository root, then into the C# test project — the corpus is shared
        // deliberately rather than copied, so the two back ends can never drift apart.
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        val f = File(dir, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/new/Vanaheimr.V2G.Exi.Tests/Vectors/AppProtocol.vectors.json")
        assertTrue(f.isFile, "vector corpus not found at $f")
        JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("vectors").map { it.asJsonObject }
    }

    private fun hex(bytes: ByteArray) =
        bytes.joinToString(" ") { "%02x".format(it) }

    private fun build(v: JsonObject): ByteArray {
        val input = v.getAsJsonObject("input")
        return when (val type = v.get("messageType").asString) {
            "SupportedAppProtocolReq" ->
                SupportedAppProtocolCodec.encode(
                    SupportedAppProtocolReq(
                        input.getAsJsonArray("appProtocols").map {
                            val o = it.asJsonObject
                            AppProtocolType(
                                protocolNamespace  = o.get("protocolNamespace").asString,
                                versionNumberMajor = o.get("versionNumberMajor").asLong.toUInt(),
                                versionNumberMinor = o.get("versionNumberMinor").asLong.toUInt(),
                                schemaID           = o.get("schemaId").asInt.toUByte(),
                                priority           = o.get("priority").asInt.toUByte(),
                            )
                        }
                    )
                )

            "SupportedAppProtocolRes" ->
                SupportedAppProtocolCodec.encode(
                    SupportedAppProtocolRes(
                        responseCode = ResponseCode.valueOf(input.get("code").asString),
                        schemaID     = input.get("schemaId")
                            ?.takeIf { !it.isJsonNull }?.asInt?.toUByte(),
                    )
                )

            else -> error("unknown messageType '$type'")
        }
    }

    @Test
    fun `encodes every vector byte-exactly against the cbV2G reference`() {
        assertTrue(vectors.isNotEmpty(), "no vectors loaded")
        val failures = mutableListOf<String>()

        for (v in vectors) {
            val name = v.get("name").asString
            val expected = v.get("expectedHex").asString.trim().lowercase()
            val actual = try {
                hex(build(v))
            } catch (e: Throwable) {
                failures += "$name: threw ${e::class.simpleName}: ${e.message}"
                continue
            }
            if (actual != expected) {
                failures += "$name:\n    expected: $expected\n    actual:   $actual"
            }
        }

        println("AppProtocol vectors: ${vectors.size - failures.size}/${vectors.size} byte-exact")
        assertTrue(failures.isEmpty(), "byte mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `round-trips every vector through decode`() {
        for (v in vectors) {
            val name = v.get("name").asString
            val bytes = build(v)
            val decoded = SupportedAppProtocolCodec.decodeAny(bytes)
            val reencoded = when (decoded) {
                is SupportedAppProtocolReq -> SupportedAppProtocolCodec.encode(decoded)
                is SupportedAppProtocolRes -> SupportedAppProtocolCodec.encode(decoded)
                else -> error("unexpected decoded type ${decoded::class}")
            }
            assertEquals(hex(bytes), hex(reencoded), "round-trip mismatch for $name")
        }
    }
}
