package cloud.charging.v2g.exi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The EXI string value-table codec: local hits, global hits, interleaving, compact-id width growth,
 * and rejection of out-of-range hit indices. Mirrors the C# `ExiStringTableTests` case for case —
 * the two implementations have to agree bit for bit, so they are asked the same questions.
 *
 * The ISO 15118 wire path itself is miss-only (see [ExiPrimitives]); this exercises the machinery
 * the decoder needs for streams from stacks that do emit hits.
 */
class ExiStringTableTest {

    private fun encode(table: ExiStringTable, vararg items: Pair<String, String>): ByteArray {
        val buf = ByteArray(4096)
        val w = BitWriter(buf)
        for ((key, value) in items) table.writeStringValue(w, key, value)
        w.alignToByte()
        return buf.copyOf(w.bytesWritten)
    }

    private fun decode(table: ExiStringTable, bytes: ByteArray, vararg keys: String): List<String> {
        val r = BitReader(bytes)
        return keys.map { table.readStringValue(r, it) }
    }

    /**
     * The cross-language contract. `ExiStringTableTests` on the C# side asserts the SAME hex for
     * the SAME sequence, so the two implementations are pinned to each other rather than each to
     * its own reading of the spec. A round trip inside one language cannot catch a mistake both
     * halves share — this can.
     */
    @Test
    fun `mixed hits and misses match the cross-language vector`() {
        val bytes = encode(ExiStringTable(),
            "1" to "alpha", "2" to "beta", "1" to "alpha", "2" to "alpha",
            "1" to "gamma", "1" to "gamma", "2" to "beta")

        assertEquals(
            "07616C7068610662657461000103B3B0B6B6B0804000",
            bytes.joinToString("") { "%02X".format(it) },
            "the C# ExiStringTable pins this same value — if one moves, both must")
    }

    @Test
    fun `a local hit round-trips and is shorter than two misses`() {
        val twoMisses = encode(ExiStringTable(), "1" to "urn:a", "1" to "urn:b")
        val missThenHit = encode(ExiStringTable(), "1" to "urn:a", "1" to "urn:a")

        assertTrue(missThenHit.size < twoMisses.size,
            "the repeated value should cost a compact id, not a second literal")
        assertContentEquals(
            listOf("urn:a", "urn:a"),
            decode(ExiStringTable(), missThenHit, "1", "1"))
    }

    @Test
    fun `a value first seen under another key comes back as a global hit`() {
        // "urn:x" is a miss at key 1, then absent from key 2's local partition but present
        // globally — so the second occurrence is a global hit.
        val bytes = encode(ExiStringTable(), "1" to "urn:x", "2" to "urn:x")

        assertContentEquals(listOf("urn:x", "urn:x"), decode(ExiStringTable(), bytes, "1", "2"))
    }

    @Test
    fun `interleaved hits and misses round-trip`() {
        val items = arrayOf(
            "1" to "alpha",   // miss  (local1=[alpha], global=[alpha])
            "2" to "beta",    // miss  (local2=[beta],  global=[alpha,beta])
            "1" to "alpha",   // local hit
            "2" to "alpha",   // global hit — not in local2
            "1" to "gamma",   // miss
            "1" to "gamma",   // local hit
            "2" to "beta",    // local hit
        )

        val bytes = encode(ExiStringTable(), *items)

        assertContentEquals(
            items.map { it.second },
            decode(ExiStringTable(), bytes, *items.map { it.first }.toTypedArray()))
    }

    @Test
    fun `the compact id widens with the partition`() {
        // ⌈log₂(size)⌉, with EXI's convention that a size-1 partition needs no bits at all.
        for ((size, expectedBits) in listOf(1 to 0, 2 to 1, 3 to 2, 4 to 2, 5 to 3, 8 to 3, 9 to 4)) {
            val table = ExiStringTable()
            val fill = (0 until size).map { "1" to "v$it" }.toTypedArray()

            val withoutHit = encode(ExiStringTable(), *fill)
            val withHit = encode(table, *fill, "1" to "v0")

            // The hit costs UnsignedInteger(0) — one byte — plus the compact id.
            val hitBits = (withHit.size - withoutHit.size) * 8
            assertTrue(hitBits >= 8 + expectedBits - 7,
                "partition size $size: hit cost $hitBits bits, expected ~${8 + expectedBits}")
        }
    }

    @Test
    fun `a local hit into an empty partition is refused`() {
        val buf = ByteArray(16)
        val w = BitWriter(buf)
        ExiPrimitives.writeUnsignedInteger(w, 0uL)   // local hit, but nothing has been seen yet
        w.alignToByte()

        assertFailsWith<IllegalArgumentException> {
            ExiStringTable().readStringValue(BitReader(buf.copyOf(w.bytesWritten)), "1")
        }
    }

    @Test
    fun `a global hit into an empty partition is refused`() {
        val buf = ByteArray(16)
        val w = BitWriter(buf)
        ExiPrimitives.writeUnsignedInteger(w, 1uL)   // global hit into an empty global partition
        w.alignToByte()

        assertFailsWith<IllegalArgumentException> {
            ExiStringTable().readStringValue(BitReader(buf.copyOf(w.bytesWritten)), "1")
        }
    }

    /**
     * The load-bearing compatibility claim: a value the table has never seen encodes to exactly what
     * the miss-only primitive writes. That is what lets the wire path stay on [ExiPrimitives] while
     * the decoder gains the table — a first occurrence is the same bytes either way.
     */
    @Test
    fun `a miss is byte-identical to the miss-only primitive`() {
        val viaTable = encode(ExiStringTable(), "1" to "urn:iso:15118:2:2013:MsgDef")

        val buf = ByteArray(4096)
        val w = BitWriter(buf)
        ExiPrimitives.writeStringValue(w, "urn:iso:15118:2:2013:MsgDef")
        w.alignToByte()

        assertContentEquals(buf.copyOf(w.bytesWritten), viaTable)
    }

    @Test
    fun `astral characters count as one value each`() {
        val emoji = "a😀b"   // U+1F600 is one code point, two UTF-16 units
        val bytes = encode(ExiStringTable(), "1" to emoji)

        assertEquals(emoji, decode(ExiStringTable(), bytes, "1").single())
    }

    /**
     * The seam the generated decoders actually use: [ExiPrimitives.readStringValue] must honour the
     * slot it is given, not just any slot.
     *
     * Everything else here calls the table directly and so cannot notice a delegation that drops the
     * argument — which is exactly what a mutation test found. The give-away is the compact-id WIDTH:
     * with the slots kept apart, the third value is a local hit into a partition of size 1 and costs
     * zero id bits; merge the partitions and it becomes size 2, the decoder reads one bit too many,
     * and the stream misaligns from there on.
     */
    @Test
    fun `the primitive keeps distinct slots apart`() {
        val buf = ByteArray(1024)
        val w = BitWriter(buf)
        val table = ExiStringTable()
        table.writeStringValue(w, "A", "urn:v")   // miss  → localA=[v], global=[v]
        table.writeStringValue(w, "B", "urn:w")   // miss  → localB=[w], global=[v,w]
        table.writeStringValue(w, "A", "urn:v")   // local hit, localA size 1 → 0-bit compact id
        w.alignToByte()
        val bytes = buf.copyOf(w.bytesWritten)

        val r = BitReader(bytes)
        assertEquals("urn:v", ExiPrimitives.readStringValue(r, "A"))
        assertEquals("urn:w", ExiPrimitives.readStringValue(r, "B"))
        assertEquals("urn:v", ExiPrimitives.readStringValue(r, "A"),
            "the hit must resolve against slot A's partition, not a merged one")
    }
}
