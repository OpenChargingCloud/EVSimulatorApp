package cloud.charging.v2g.jsonld

import cloud.charging.v2g.exi.JsonObject
import cloud.charging.v2g.exi.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This back end's JSON-LD documents, against the ones the C# back end produces.
 *
 * ## Why this is the check, and the round trip is not
 *
 * `EXI → JSON → EXI` proves a mapping loses nothing, and it is **blind to what the mapping is
 * called**: rename every property and it stays green, because the serializer and the parser rename
 * together. Measured on the C# side — replacing the naming rule with a naïve
 * lower-the-first-character one turned `evseStatus` into `eVSEStatus` in every message of every set,
 * and all 163 round-trip tests still passed.
 *
 * So the agreement is checked against **text**. `JsonLd.documents.json` holds every vector's JSON
 * form exactly as C# wrote it, and this compares character for character: property names, property
 * order, `@context` and `@type` placement, hex for binary, strings for 64-bit integers, and which
 * optional properties are omitted rather than written as null. That is what §4.4 means by
 * cross-language agreement, and it is the reason `JsonNaming` lives in the generator's
 * language-neutral layer instead of three times inside the emitters.
 *
 * Both directions are checked, because they can fail apart: a serializer can agree while a parser
 * quietly accepts something it should not.
 */
class JsonLdAgreementTest {

    /** One message set's four generated entry points. */
    private class Bridge(
        val name: String,
        val vectorFile: String,
        val decodeAny: (ByteArray) -> Any,
        val encodeAny: (Any) -> ByteArray,
        val toJson: (Any) -> JsonObject,
        val parseJson: (JsonValue) -> Any,
    )

    private val bridges = listOf(
        Bridge("AppProtocol", "AppProtocol.vectors.json",
               cloud.charging.v2g.appprotocol.SupportedAppProtocolCodec::decodeAny,
               cloud.charging.v2g.appprotocol.SupportedAppProtocolCodec::encodeAny,
               cloud.charging.v2g.appprotocol.SupportedAppProtocolCodecJson::toJson,
               { cloud.charging.v2g.appprotocol.SupportedAppProtocolCodecJson.parseJson(it) }),

        Bridge("ISO 15118-2", "Iso15118_2.vectors.json",
               cloud.charging.v2g.iso2.Iso15118_2Codec::decodeAny,
               cloud.charging.v2g.iso2.Iso15118_2Codec::encodeAny,
               cloud.charging.v2g.iso2.Iso15118_2CodecJson::toJson,
               { cloud.charging.v2g.iso2.Iso15118_2CodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 CommonMessages", "Iso15118_20.CommonMessages.vectors.json",
               cloud.charging.v2g.iso20.common.CommonMessagesCodec::decodeAny,
               cloud.charging.v2g.iso20.common.CommonMessagesCodec::encodeAny,
               cloud.charging.v2g.iso20.common.CommonMessagesCodecJson::toJson,
               { cloud.charging.v2g.iso20.common.CommonMessagesCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 DC", "Iso15118_20.DC.vectors.json",
               cloud.charging.v2g.iso20.dc.DCCodec::decodeAny,
               cloud.charging.v2g.iso20.dc.DCCodec::encodeAny,
               cloud.charging.v2g.iso20.dc.DCCodecJson::toJson,
               { cloud.charging.v2g.iso20.dc.DCCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 AC", "Iso15118_20.AC.vectors.json",
               cloud.charging.v2g.iso20.ac.ACCodec::decodeAny,
               cloud.charging.v2g.iso20.ac.ACCodec::encodeAny,
               cloud.charging.v2g.iso20.ac.ACCodecJson::toJson,
               { cloud.charging.v2g.iso20.ac.ACCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 WPT", "Iso15118_20.WPT.vectors.json",
               cloud.charging.v2g.iso20.wpt.WPTCodec::decodeAny,
               cloud.charging.v2g.iso20.wpt.WPTCodec::encodeAny,
               cloud.charging.v2g.iso20.wpt.WPTCodecJson::toJson,
               { cloud.charging.v2g.iso20.wpt.WPTCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 ACDP", "Iso15118_20.ACDP.vectors.json",
               cloud.charging.v2g.iso20.acdp.ACDPCodec::decodeAny,
               cloud.charging.v2g.iso20.acdp.ACDPCodec::encodeAny,
               cloud.charging.v2g.iso20.acdp.ACDPCodecJson::toJson,
               { cloud.charging.v2g.iso20.acdp.ACDPCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 AC_DER_IEC", "Iso15118_20.AC_DER_IEC.vectors.json",
               cloud.charging.v2g.iso20.acderiec.AcDerIecCodec::decodeAny,
               cloud.charging.v2g.iso20.acderiec.AcDerIecCodec::encodeAny,
               cloud.charging.v2g.iso20.acderiec.AcDerIecCodecJson::toJson,
               { cloud.charging.v2g.iso20.acderiec.AcDerIecCodecJson.parseJson(it) }),

        Bridge("ISO 15118-20 AC_DER_SAE", "Iso15118_20.AC_DER_SAE.vectors.json",
               cloud.charging.v2g.iso20.acdersae.AcDerSaeCodec::decodeAny,
               cloud.charging.v2g.iso20.acdersae.AcDerSaeCodec::encodeAny,
               cloud.charging.v2g.iso20.acdersae.AcDerSaeCodecJson::toJson,
               { cloud.charging.v2g.iso20.acdersae.AcDerSaeCodecJson.parseJson(it) }),
    )


    private val vectorsDirectory: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory)
            dir = dir.parentFile ?: error("repository root not found")

        File(dir, "libs/Vanaheimr.V2G.Exi/Vanaheimr.V2G.Exi.Tests/Vectors")
    }

    private val documents: JsonObject by lazy {
        val file = File(vectorsDirectory, "JsonLd.documents.json")
        require(file.isFile) { "the JSON-LD document corpus is missing at $file" }
        (JsonValue.parse(file.readText()) as JsonObject)["sets"] as JsonObject
    }

    private fun vectors(fileName: String): List<Pair<String, String>> {
        val root = JsonValue.parse(File(vectorsDirectory, fileName).readText()) as JsonObject
        return (root["vectors"] as cloud.charging.v2g.exi.JsonArray).asList().map {
            val v = it as JsonObject
            (v["name"] as cloud.charging.v2g.exi.JsonString).value to
            (v["expectedHex"] as cloud.charging.v2g.exi.JsonString).value
        }
    }

    private fun hex(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }


    @Test
    fun `this back end writes the documents C# writes`() {

        var checked = 0

        for (bridge in bridges) {

            val expected = documents[bridge.name] as? JsonObject
                ?: error("the corpus has no documents for ${bridge.name}")

            for ((name, expectedHex) in vectors(bridge.vectorFile)) {

                val produced = bridge.toJson(bridge.decodeAny(hex(expectedHex)))

                assertEquals((expected[name] ?: error("${bridge.name}/$name missing")).toJsonString(),
                             produced.toJsonString(),
                             "${bridge.name}/$name")
                checked++
            }
        }

        assertTrue(checked >= 160, "only $checked documents were compared")
    }


    /**
     * The other direction: C#'s documents, read by this parser, encode to the original bytes.
     *
     * A serializer and a parser can fail apart. One that agreed on output while accepting something
     * looser than C# does would pass the test above and still break the moment a Pi sent a document
     * this app read differently.
     */
    @Test
    fun `this back end reads the documents C# writes`() {

        for (bridge in bridges) {

            val expected = documents[bridge.name] as JsonObject

            for ((name, expectedHex) in vectors(bridge.vectorFile)) {

                val message = bridge.parseJson(expected[name] ?: error("${bridge.name}/$name missing"))

                assertEquals(hex(expectedHex).let(::toHex), toHex(bridge.encodeAny(message)),
                             "${bridge.name}/$name: the bytes changed on the way through JSON")
            }
        }
    }


    /** Every set's `@context` is its XSD target namespace, and every document carries it. */
    @Test
    fun `every document carries its vocabulary`() {

        for (bridge in bridges) {

            val set = documents[bridge.name] as JsonObject
            val context = set["@context"] ?: error("${bridge.name} has no @context in the corpus")

            for ((name, expectedHex) in vectors(bridge.vectorFile)) {
                val produced = bridge.toJson(bridge.decodeAny(hex(expectedHex)))
                assertEquals(context.toJsonString(), produced["@context"]?.toJsonString(),
                             "${bridge.name}/$name")
            }
        }
    }
}
