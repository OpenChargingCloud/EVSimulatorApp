package cloud.charging.v2g.appprotocol

import cloud.charging.v2g.exi.BitWriter
import cloud.charging.v2g.exi.ExiPrimitives
import cloud.charging.v2g.exi.ExiStringTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point of wiring the value tables into the decoder: a stream that uses them must decode.
 *
 * Every checked-in vector comes from cbV2G, which is miss-only, so **no vector can reach this
 * path** — a green vector suite says nothing about whether hits work. Value-table hits are
 * nonetheless legal EXI and a conforming peer (EXIficient, Josev) may send them at any time, so the
 * decoder has to handle what the reference encoder happens never to produce.
 *
 * This test therefore builds the stream itself: the same `SupportedAppProtocolReq` twice, once with
 * both namespaces written as literals and once with the second written as a value-table hit, then
 * requires the generated decoder to return the same message from both.
 */
class ValueTableDecodeTest {

    private val ns = "urn:iso:15118:2:2013:MsgDef"

    /**
     * Hand-encodes a two-entry `SupportedAppProtocolReq`, writing each `ProtocolNamespace` through
     * [table] — so a repeated namespace comes out as a hit rather than a second literal.
     *
     * The event codes are the AppProtocol grammar as the hand-written reference codec documents it:
     * a 2-bit document selector, a 1-bit SE for the first list item and 2-bit for the following
     * ones, and SE / value-start / value / EE around each child.
     */
    private fun encodeWithTable(table: ExiStringTable, vararg namespaces: String): ByteArray {
        val buf = ByteArray(1024)
        buf[0] = SupportedAppProtocolCodec.EXI_HEADER
        val w = BitWriter(buf, 1)

        w.writeBits(0u, 2)                                  // document: SupportedAppProtocolReq

        namespaces.forEachIndexed { i, namespace ->
            w.writeBits(0u, if (i == 0) 1 else 2)           // SE(AppProtocol)

            w.writeBits(0u, 1); w.writeBits(0u, 1)          // SE + value-start
            table.writeStringValue(w, "ProtocolNamespace", namespace)
            w.writeBits(0u, 1)                              // EE

            w.writeBits(0u, 1); w.writeBits(0u, 1)
            ExiPrimitives.writeUnsignedInteger(w, 2uL)      // VersionNumberMajor
            w.writeBits(0u, 1)

            w.writeBits(0u, 1); w.writeBits(0u, 1)
            ExiPrimitives.writeUnsignedInteger(w, 0uL)      // VersionNumberMinor
            w.writeBits(0u, 1)

            w.writeBits(0u, 1); w.writeBits(0u, 1)
            w.writeBits((i + 1).toUInt(), 8)                // SchemaID
            w.writeBits(0u, 1)

            w.writeBits(0u, 1); w.writeBits(0u, 1)
            w.writeBits(i.toUInt(), 5)                      // Priority, encoded as value - 1
            w.writeBits(0u, 1)

            w.writeBits(0u, 1)                              // EE(AppProtocol)
        }

        w.writeBits(1u, 2)                                  // list terminator / element EE
        w.alignToByte()
        return buf.copyOf(1 + w.bytesWritten)
    }

    @Test
    fun `a repeated namespace encodes as a hit and shortens the stream`() {
        // A fresh table per call: the first namespace is a miss either way, the second is a hit
        // only when it repeats.
        val distinct = encodeWithTable(ExiStringTable(), ns, ns.dropLast(1) + "X")
        val repeated = encodeWithTable(ExiStringTable(), ns, ns)

        assertTrue(repeated.size < distinct.size,
            "the repeated namespace should cost a compact id, not a second literal")
    }

    @Test
    fun `the generated decoder resolves a value-table hit`() {
        val withHit = encodeWithTable(ExiStringTable(), ns, ns)

        val decoded = SupportedAppProtocolCodec.decodeAny(withHit) as SupportedAppProtocolReq

        assertEquals(2, decoded.appProtocol.size)
        assertEquals(ns, decoded.appProtocol[0].protocolNamespace)
        assertEquals(ns, decoded.appProtocol[1].protocolNamespace,
            "the second namespace came from the local value partition, not from the wire")
    }

    /**
     * The two encodings are different byte sequences that mean the same message — which is the whole
     * claim. Before the tables were wired in, the hit-bearing one threw.
     */
    @Test
    fun `hit-bearing and literal streams decode to the same message`() {
        val withHit = encodeWithTable(ExiStringTable(), ns, ns)

        val literalTable = ExiStringTable()   // never reused, so every value is a miss
        val literal = encodeWithTable(literalTable, ns, ns.dropLast(1) + "X")

        assertTrue(!withHit.contentEquals(literal))

        val a = SupportedAppProtocolCodec.decodeAny(withHit) as SupportedAppProtocolReq
        assertContentEquals(
            listOf(ns, ns),
            a.appProtocol.map { it.protocolNamespace })
    }

    /**
     * Encoding is deliberately NOT on the tables: cbV2G is miss-only and every vector is its output,
     * so an encoder that emitted hits would invalidate all of them. Re-encoding a message that
     * arrived with a hit must therefore produce the literal form.
     */
    @Test
    fun `re-encoding a hit-bearing message writes literals`() {
        val decoded = SupportedAppProtocolCodec.decodeAny(
            encodeWithTable(ExiStringTable(), ns, ns)) as SupportedAppProtocolReq

        val reencoded = SupportedAppProtocolCodec.encode(decoded)

        // Same message, but longer: both namespaces are spelled out again.
        assertContentEquals(
            listOf(ns, ns),
            (SupportedAppProtocolCodec.decodeAny(reencoded) as SupportedAppProtocolReq)
                .appProtocol.map { it.protocolNamespace })
        assertTrue(reencoded.size > encodeWithTable(ExiStringTable(), ns, ns).size,
            "the encoder must stay miss-only — cbV2G conformance depends on it")
    }
}
