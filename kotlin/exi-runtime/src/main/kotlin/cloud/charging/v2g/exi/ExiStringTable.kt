package cloud.charging.v2g.exi

/**
 * EXI string value-table codec (EXI Format 1.0 §7.1.10 / §7.3.3): one local value partition per
 * value slot — keyed by the slot's QName local part, which is what the grammar layer knows and what
 * the generated decoders pass — plus a single global partition per stream.
 *
 * The key is the NAME rather than an assigned index on purpose: an index would have to come from a
 * table the emitter numbers, so adding one element to a schema would renumber the rest and churn
 * every generated file that mentions them.
 *
 * A faithful port of the C# `ExiStringTable`; the two must agree bit for bit.
 *
 * ## Why this is separate from [ExiPrimitives]
 *
 * The ISO 15118 reference codec (cbexigen/cbV2G) is **miss-only**: it never emits value-table hits.
 * Every checked-in vector is cbV2G output, so the *encode* path stays on the miss-only
 * [ExiPrimitives.writeStringValue] — an encoder that started emitting hits would invalidate all of
 * them and stop interoperating with the reference. This class exists so the *decode* path can read
 * streams from stacks that do emit hits (EXIficient, Josev), which is not a hypothetical: hits are
 * legal EXI and a conforming peer may send them at any time.
 *
 * ## Encoding a value at a given local key
 *
 *  * value in the local partition → `UnsignedInteger(0)`, then the compact id as an n-bit
 *    Unsigned Integer, n = ⌈log₂(m)⌉ over the local partition size m;
 *  * else in the global partition → `UnsignedInteger(1)`, then the compact id over the global size;
 *  * else (miss) → `UnsignedInteger(codePointCount + 2)`, then one code point per rune, and the
 *    value is appended to **both** partitions.
 *
 * A partition of size 1 needs a 0-bit compact id. Hits never grow a partition; only misses do,
 * which is what keeps encoder and decoder in lock-step.
 *
 * One instance carries the partition state for **one stream**. [BitReader] owns one so the
 * generated decoders need no extra parameter.
 */
class ExiStringTable {

    private val global      = ArrayList<String>()
    private val globalIndex = HashMap<String, Int>()
    private val locals      = HashMap<String, Partition>()

    private class Partition {
        val values = ArrayList<String>()
        val index  = HashMap<String, Int>()
    }

    private fun local(key: String): Partition = locals.getOrPut(key) { Partition() }

    /** Encode a string value at [localKey], emitting a hit when possible. */
    fun writeStringValue(w: BitWriter, localKey: String, value: String) {
        val localPartition = local(localKey)

        localPartition.index[value]?.let { localId ->
            ExiPrimitives.writeUnsignedInteger(w, 0uL)
            writeCompactId(w, localId, localPartition.values.size)
            return
        }

        globalIndex[value]?.let { globalId ->
            ExiPrimitives.writeUnsignedInteger(w, 1uL)
            writeCompactId(w, globalId, global.size)
            return
        }

        val cps = value.codePoints().toArray()
        ExiPrimitives.writeUnsignedInteger(w, (cps.size + 2).toULong())
        for (cp in cps) ExiPrimitives.writeUnsignedInteger(w, cp.toULong())

        add(localPartition, value)
    }

    /** Decode a string value at [localKey], resolving hits against the partitions. */
    fun readStringValue(r: BitReader, localKey: String): String {
        val localPartition = local(localKey)
        val head = ExiPrimitives.readUnsignedInteger(r)

        if (head == 0uL) {
            val id = readCompactId(r, localPartition.values.size).toInt()
            require(id < localPartition.values.size) {
                "Local value-table hit id $id out of range (partition size ${localPartition.values.size})."
            }
            return localPartition.values[id]
        }

        if (head == 1uL) {
            val id = readCompactId(r, global.size).toInt()
            require(id < global.size) {
                "Global value-table hit id $id out of range (partition size ${global.size})."
            }
            return global[id]
        }

        val len = (head - 2uL).toInt()
        val sb = StringBuilder(len)
        repeat(len) { sb.appendCodePoint(ExiPrimitives.readUnsignedInteger(r).toInt()) }
        val value = sb.toString()

        add(localPartition, value)
        return value
    }

    /** Append a freshly-seen (miss) value to the local and global partitions. */
    private fun add(localPartition: Partition, value: String) {
        localPartition.index[value] = localPartition.values.size
        localPartition.values.add(value)

        // A miss means the value was in neither partition, so it is new globally too.
        globalIndex[value] = global.size
        global.add(value)
    }

    private fun writeCompactId(w: BitWriter, id: Int, partitionSize: Int) {
        val n = bitsFor(partitionSize)
        if (n > 0) w.writeBits(id.toUInt(), n)
    }

    private fun readCompactId(r: BitReader, partitionSize: Int): UInt {
        val n = bitsFor(partitionSize)
        return if (n > 0) r.readBits(n) else 0u
    }

    private companion object {
        /** ⌈log₂(count)⌉, with the EXI convention that a size-1 partition needs 0 bits. */
        fun bitsFor(count: Int): Int {
            if (count <= 1) return 0
            var bits = 0
            var v = count - 1
            while (v > 0) { bits++; v = v shr 1 }
            return bits
        }
    }
}
