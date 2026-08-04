package cloud.charging.v2g.tp

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The V2GTP payload-type dispatcher: given a frame, resolve the right message set and decode it
 * without knowing in advance which of the six codecs applies; given a set and an already-encoded
 * EXI payload, wrap it with the matching header. Mirrors the C# `V2GTPDispatcherTests`.
 *
 * The payloads are real cbV2G bytes read out of the shared vector corpus rather than hand-built,
 * so a payload type wired to the wrong codec cannot pass: the decode either throws or yields a
 * type from the wrong package, and both are checked.
 */
class V2GTPDispatcherTest {

    private val repoRoot: File by lazy {
        var dir = File(".").absoluteFile
        while (!File(dir, "libs/Vanaheimr.V2G.Exi").isDirectory) {
            dir = dir.parentFile ?: error("repository root not found from ${File(".").absolutePath}")
        }
        dir
    }

    /** The first vector of [corpus], as the raw EXI payload bytes. */
    private fun firstPayload(corpus: String): ByteArray {
        val f = File(repoRoot, "libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors/$corpus")
        assertTrue(f.isFile, "vector corpus not found at $f")
        val hex = JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonArray("vectors").first().asJsonObject
            .get("expectedHex").asString
        return hex.trim().split(" ", "\n", "\t")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun assertRoundtrips(set: MessageSet, corpus: String, expectedPackage: String) {
        val payload = firstPayload(corpus)
        val frame = V2GTPDispatcher.encode(set, payload)

        assertEquals(V2GTP.HEADER_SIZE + payload.size, frame.size)

        val result = V2GTPDispatcher.decode(frame)
        val decoded = assertIs<V2GTPDecodeResult.Decoded>(result,
            "framing $corpus as $set and decoding it back should succeed")

        assertEquals(set, decoded.set)
        assertEquals(expectedPackage, decoded.message::class.java.packageName,
            "the payload type must resolve to $set's own codec, not another set's")
    }

    @Test
    fun `a SAP frame shares the -2 payload id and is not dispatched by payload type`() {
        // The SupportedAppProtocol handshake uses payload id 0x8001 — the SAME as the DIN/-2 messages
        // (ISO 15118-20 §A / libcbv2g V2GTP20_SAP_PAYLOAD_ID / Josev). SAP is told apart from a -2 message
        // by session phase, not payload type, so the payload-type dispatcher does NOT resolve SAP.
        assertEquals(0x8001u.toUShort(), V2GTPDispatcher.payloadTypeOf(MessageSet.AppProtocol))
        assertEquals(
            V2GTPDispatcher.payloadTypeOf(MessageSet.Iso15118_2),
            V2GTPDispatcher.payloadTypeOf(MessageSet.AppProtocol))
    }

    @Test
    fun `round-trips an ISO 15118-2 frame`() =
        assertRoundtrips(MessageSet.Iso15118_2, "Iso15118_2.vectors.json", "cloud.charging.v2g.iso2")

    @Test
    fun `round-trips an ISO 15118-20 CommonMessages frame`() =
        assertRoundtrips(MessageSet.Iso20CommonMessages, "Iso15118_20.CommonMessages.vectors.json",
            "cloud.charging.v2g.iso20.common")

    @Test
    fun `round-trips an ISO 15118-20 AC frame`() =
        assertRoundtrips(MessageSet.Iso20AC, "Iso15118_20.AC.vectors.json", "cloud.charging.v2g.iso20.ac")

    @Test
    fun `round-trips an ISO 15118-20 DC frame`() =
        assertRoundtrips(MessageSet.Iso20DC, "Iso15118_20.DC.vectors.json", "cloud.charging.v2g.iso20.dc")

    @Test
    fun `round-trips an ISO 15118-20 WPT frame`() =
        assertRoundtrips(MessageSet.Iso20WPT, "Iso15118_20.WPT.vectors.json", "cloud.charging.v2g.iso20.wpt")

    @Test
    fun `round-trips an ISO 15118-20 ACDP frame`() =
        assertRoundtrips(MessageSet.Iso20ACDP, "Iso15118_20.ACDP.vectors.json", "cloud.charging.v2g.iso20.acdp")

    /**
     * Every set gets its own payload type — the mapping is a bijection over the sets that have one.
     * AppProtocol and Iso15118_2 are the documented exception, and share 0x8001.
     */
    @Test
    fun `distinct sets get distinct payload types`() {
        val byType = MessageSet.entries
            .filter { it != MessageSet.AppProtocol }
            .groupBy { V2GTPDispatcher.payloadTypeOf(it) }

        assertTrue(byType.all { it.value.size == 1 },
            "payload types shared between sets: " +
            byType.filter { it.value.size > 1 }.map { "${it.key} -> ${it.value}" })
    }

    @Test
    fun `a bad header is a clean failure`() {
        val frame = byteArrayOf(0x02, 0xFE.toByte(), 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00)
        val failed = assertIs<V2GTPDecodeResult.Failed>(V2GTPDispatcher.decode(frame))
        assertTrue(failed.error.contains("V2GTP frame"), failed.error)
    }

    @Test
    fun `a lying length field is a clean failure`() {
        val frame = ByteArray(V2GTP.HEADER_SIZE + 3)
        V2GTP.writeHeader(frame, V2GTP.PAYLOAD_TYPE_APP_PROTOCOL, 99u)   // lies about the length

        val failed = assertIs<V2GTPDecodeResult.Failed>(V2GTPDispatcher.decode(frame))
        assertTrue(failed.error.contains("length mismatch"), failed.error)
    }

    @Test
    fun `an unknown payload type is a clean failure, not a guess`() {
        val frame = ByteArray(V2GTP.HEADER_SIZE + 1)
        V2GTP.writeHeader(frame, 0x8101u.toUShort(), 1u)   // ScheduleRenegotiation — not modelled

        val failed = assertIs<V2GTPDecodeResult.Failed>(V2GTPDispatcher.decode(frame))
        assertTrue(failed.error.contains("unknown V2GTP payload type 0x8101"), failed.error)
    }

    /**
     * The boundary the port has to keep: a framing problem is a value, a broken payload is an
     * exception. Anything else and a transport would either swallow corrupt EXI or have to catch
     * around a well-formed but unknown frame.
     */
    @Test
    fun `malformed EXI inside a recognised set still throws`() {
        val frame = V2GTPDispatcher.encode(MessageSet.Iso20DC, byteArrayOf(0x80.toByte(), 0xFF.toByte()))
        assertFailsWith<IllegalArgumentException> { V2GTPDispatcher.decode(frame) }
    }

    @Test
    fun `an empty payload is framed and rejected by the codec, not by the dispatcher`() {
        val frame = V2GTPDispatcher.encode(MessageSet.Iso20AC, ByteArray(0))
        assertEquals(V2GTP.HEADER_SIZE, frame.size)
        assertEquals(0u, V2GTP.tryReadHeader(frame)!!.payloadLength)

        assertFailsWith<IllegalArgumentException> { V2GTPDispatcher.decode(frame) }
    }
}
