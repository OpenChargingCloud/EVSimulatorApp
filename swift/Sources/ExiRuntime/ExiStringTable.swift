/// EXI string value-table codec (EXI Format 1.0 §7.1.10 / §7.3.3): one local value partition per
/// value slot — keyed by the slot's QName local part, which is what the grammar layer knows and
/// what the generated decoders pass — plus a single global partition per stream.
///
/// The key is the NAME rather than an assigned index on purpose: an index would have to come from a
/// table the emitter numbers, so adding one element to a schema would renumber the rest and churn
/// every generated file that mentions them.
///
/// A faithful port of the C# `ExiStringTable`; the two must agree bit for bit.
///
/// ## Why this is separate from ``ExiPrimitives``
///
/// The ISO 15118 reference codec (cbexigen/cbV2G) is **miss-only**: it never emits value-table
/// hits. Every checked-in vector is cbV2G output, so the *encode* path stays on the miss-only
/// ``ExiPrimitives/writeStringValue(_:_:)`` — an encoder that started emitting hits would
/// invalidate all of them and stop interoperating with the reference. This type exists so the
/// *decode* path can read streams from stacks that do emit hits (EXIficient, Josev), which is not
/// hypothetical: hits are legal EXI and a conforming peer may send them at any time.
///
/// ## Encoding a value at a given local key
///
///  * value in the local partition → `UnsignedInteger(0)`, then the compact id as an n-bit
///    Unsigned Integer, n = ⌈log₂(m)⌉ over the local partition size m;
///  * else in the global partition → `UnsignedInteger(1)`, then the compact id over the global size;
///  * else (miss) → `UnsignedInteger(codePointCount + 2)`, then one code point per scalar, and the
///    value is appended to **both** partitions.
///
/// A partition of size 1 needs a 0-bit compact id. Hits never grow a partition; only misses do,
/// which is what keeps encoder and decoder in lock-step.
///
/// One instance carries the partition state for **one stream**. ``BitReader`` owns one so the
/// generated decoders need no extra parameter.
public final class ExiStringTable {

    private struct Partition {
        var values: [String] = []
        var index: [String: Int] = [:]
    }

    private var global: [String] = []
    private var globalIndex: [String: Int] = [:]
    private var locals: [String: Partition] = [:]

    public init() {}

    /// Encode a string value at `localKey`, emitting a hit when possible.
    ///
    /// Unused by the generated encoders, which stay miss-only against cbV2G (see the type note);
    /// it exists so the hit path is testable in both directions.
    public func writeStringValue(_ w: BitWriter, localKey: String, value: String) {
        let localPartition = locals[localKey] ?? Partition()

        if let localId = localPartition.index[value] {
            ExiPrimitives.writeUnsignedInteger(w, 0)
            writeCompactId(w, localId, localPartition.values.count)
            locals[localKey] = localPartition
            return
        }

        if let globalId = globalIndex[value] {
            ExiPrimitives.writeUnsignedInteger(w, 1)
            writeCompactId(w, globalId, global.count)
            locals[localKey] = localPartition
            return
        }

        let scalars = Array(value.unicodeScalars)
        ExiPrimitives.writeUnsignedInteger(w, UInt64(scalars.count + 2))
        for scalar in scalars { ExiPrimitives.writeUnsignedInteger(w, UInt64(scalar.value)) }

        add(localKey, localPartition, value)
    }

    /// Decode a string value at `localKey`, resolving hits against the partitions.
    public func readStringValue(_ r: BitReader, slot localKey: String) throws -> String {
        let localPartition = locals[localKey] ?? Partition()
        let head = try ExiPrimitives.readUnsignedInteger(r)

        if head == 0 {
            let id = Int(try readCompactId(r, localPartition.values.count))
            guard id < localPartition.values.count else {
                throw ExiError.valueTableHitOutOfRange(id: id, partitionSize: localPartition.values.count)
            }
            locals[localKey] = localPartition
            return localPartition.values[id]
        }

        if head == 1 {
            let id = Int(try readCompactId(r, global.count))
            guard id < global.count else {
                throw ExiError.valueTableHitOutOfRange(id: id, partitionSize: global.count)
            }
            locals[localKey] = localPartition
            return global[id]
        }

        let len = Int(head - 2)
        var scalars = String.UnicodeScalarView()
        scalars.reserveCapacity(len)
        for _ in 0..<len {
            let cp = try ExiPrimitives.readUnsignedInteger(r)
            guard cp <= UInt64(UInt32.max), let scalar = Unicode.Scalar(UInt32(cp)) else {
                throw ExiError.invalidCodePoint(cp)
            }
            scalars.append(scalar)
        }
        let value = String(scalars)

        add(localKey, localPartition, value)
        return value
    }

    /// Append a freshly-seen (miss) value to the local and global partitions.
    private func add(_ localKey: String, _ partition: Partition, _ value: String) {
        var p = partition
        p.index[value] = p.values.count
        p.values.append(value)
        locals[localKey] = p

        // A miss means the value was in neither partition, so it is new globally too.
        globalIndex[value] = global.count
        global.append(value)
    }

    private func writeCompactId(_ w: BitWriter, _ id: Int, _ partitionSize: Int) {
        let n = Self.bitsFor(partitionSize)
        if n > 0 { w.writeBits(UInt32(id), n) }
    }

    private func readCompactId(_ r: BitReader, _ partitionSize: Int) throws -> UInt32 {
        let n = Self.bitsFor(partitionSize)
        return n > 0 ? try r.readBits(n) : 0
    }

    /// ⌈log₂(count)⌉, with the EXI convention that a size-1 partition needs 0 bits.
    private static func bitsFor(_ count: Int) -> Int {
        if count <= 1 { return 0 }
        var bits = 0
        var v = count - 1
        while v > 0 { bits += 1; v >>= 1 }
        return bits
    }
}
