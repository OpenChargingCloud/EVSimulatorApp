import { BitReader, BitWriter, ExiError } from "./bitio.ts";

/**
 * EXI primitive type codecs — a faithful port of the C# `ExiPrimitives`.
 *
 * String values are **miss-only** on the encode side (verbatim value, `length + 2` prefix),
 * matching the ISO 15118 wire reality: EVerest's cbexigen/cbV2G never emits value-table hits. The
 * decode side resolves hits, because they are legal EXI and a conforming peer may send them.
 *
 * Float, Decimal and DateTime are deliberately absent — the -2/-20 schemas model physical
 * quantities as multiplier/value integer pairs instead.
 *
 * ## `bigint`, and where it stops
 *
 * The EXI Unsigned Integer and Integer are 64-bit, and JavaScript's `number` is a double: every
 * value above 2^53 would be silently rounded. So those two are `bigint` here, and only those two —
 * an n-bit field is at most 32 bits wide and a plain `number` holds it exactly. It is the same
 * decision the JSON-LD form makes for the same reason, one layer down.
 */
export const ExiPrimitives = {

    /** EXI Unsigned Integer: 7 bits of value per byte, MSB = continuation flag. */
    writeUnsignedInteger(w: BitWriter, value: bigint): void {
        let v = value;
        do {
            let chunk = Number(v & 0x7Fn);
            v >>= 7n;
            if (v !== 0n) chunk |= 0x80;
            w.writeBits(chunk, 8);
        } while (v !== 0n);
    },

    readUnsignedInteger(r: BitReader): bigint {
        let value = 0n;
        let shift = 0n;
        for (;;) {
            const chunk = r.readBits(8);
            value |= BigInt(chunk & 0x7F) << shift;
            if ((chunk & 0x80) === 0) return value;
            shift += 7n;
            if (shift > 63n) throw ExiError.unsignedIntegerOverflow();
        }
    },

    /**
     * EXI Integer: a 1-bit sign (0 = non-negative) followed by the magnitude as an Unsigned
     * Integer. For negative values the magnitude is `|value| - 1`, so -1 maps to 0 and zero has a
     * single representation.
     */
    writeSignedInteger(w: BitWriter, value: bigint): void {
        if (value < 0n) {
            w.writeBits(1, 1);
            ExiPrimitives.writeUnsignedInteger(w, -value - 1n);
        } else {
            w.writeBits(0, 1);
            ExiPrimitives.writeUnsignedInteger(w, value);
        }
    },

    readSignedInteger(r: BitReader): bigint {
        const negative = r.readBits(1) !== 0;
        const magnitude = ExiPrimitives.readUnsignedInteger(r);
        if (magnitude > 0x7FFFFFFFFFFFFFFFn) throw ExiError.signedIntegerOutOfRange();
        return negative ? -magnitude - 1n : magnitude;
    },

    /**
     * EXI string value, "miss" case: `UnsignedInteger(codePointCount + 2)` followed by each Unicode
     * code point as an `UnsignedInteger`. The +2 leaves codes 0 and 1 for local / global
     * value-table hits.
     *
     * Iteration is per **code point** — `[...s]`, not `s.length` — because a JavaScript string is a
     * sequence of UTF-16 code units and an astral character such as U+1F600 is two of them but one
     * value. Counting units would put a different length on the wire than the other three back ends.
     */
    writeStringValue(w: BitWriter, value: string): void {
        const points = [...value];
        ExiPrimitives.writeUnsignedInteger(w, BigInt(points.length + 2));
        for (const point of points) ExiPrimitives.writeUnsignedInteger(w, BigInt(point.codePointAt(0)!));
    },

    /** Reads a string value at the given slot, resolving hits against the reader's string table. */
    readStringValue(r: BitReader, slot: string): string {
        return r.stringTable.readStringValue(r, slot);
    },

    /**
     * EXI Binary: the byte count as an Unsigned Integer, then the raw octets. hexBinary and
     * base64Binary are identical on the wire — the difference is only lexical, which EXI never sees.
     */
    writeBinary(w: BitWriter, data: Uint8Array): void {
        ExiPrimitives.writeUnsignedInteger(w, BigInt(data.length));
        for (const b of data) w.writeBits(b, 8);
    },

    readBinary(r: BitReader): Uint8Array {
        const length = Number(ExiPrimitives.readUnsignedInteger(r));
        const out = new Uint8Array(length);
        for (let i = 0; i < length; i++) out[i] = r.readBits(8);
        return out;
    },

    writeBoolean(w: BitWriter, value: boolean): void {
        w.writeBits(value ? 1 : 0, 1);
    },

    readBoolean(r: BitReader): boolean {
        return r.readBits(1) !== 0;
    },
};


/**
 * Resolves an EXI enumeration index, throwing rather than returning `undefined` on a value the type
 * does not have.
 *
 * The generated enumerations are frozen objects rather than TypeScript `enum`s, because Node runs
 * these sources by *stripping* types and an `enum` is not erasable — it emits a runtime object, so
 * the file would need a compiler. A const object needs none, and the index-to-name table it carries
 * is what the JSON-LD form writes anyway.
 */
export function exiEnum(type: string, members: readonly string[], raw: number): number {
    if (raw >= members.length) throw ExiError.unknownEnumValue(type, raw);
    return raw;
}

/** Always throws. The generated decoders need an expression where a construct is not modelled. */
export function exiUnsupported(what: string): never {
    throw ExiError.unsupportedConstruct(what);
}
