import { ExiStringTable } from "./exi-string-table.ts";

/**
 * Errors a decoder raises on malformed input.
 *
 * One class with a `kind`, rather than a class per case: JavaScript has no exhaustive matching to
 * reward a hierarchy with, and a bridge that has to serialise an error across into the WebView is
 * better served by a discriminant it can put in a string. The cases are the Swift enum's, name for
 * name, so a report from one back end reads the same as from another.
 */
export class ExiError extends Error {

    readonly kind: string;

    constructor(kind: string, message: string) {
        super(message);
        this.name = "ExiError";
        this.kind = kind;
    }

    static bitstreamExhausted()        { return new ExiError("bitstreamExhausted", "the bitstream ended while more bits were required."); }
    static unsignedIntegerOverflow()   { return new ExiError("unsignedIntegerOverflow", "an EXI Unsigned Integer did not terminate within 64 bits of value."); }
    static signedIntegerOutOfRange()   { return new ExiError("signedIntegerOutOfRange", "an EXI Integer's magnitude does not fit a signed 64-bit value."); }
    static invalidHeader()             { return new ExiError("invalidHeader", "the stream does not start with the EXI header byte."); }
    static invalidCodePoint(v: bigint) { return new ExiError("invalidCodePoint", `${v} is not a valid Unicode code point.`); }

    static valueTableHitOutOfRange(id: number, partitionSize: number) {
        return new ExiError("valueTableHitOutOfRange",
                            `a string value-table hit named slot ${id} of a partition holding ${partitionSize}.`);
    }
    static unknownDocumentIndex(index: number) {
        return new ExiError("unknownDocumentIndex", `unknown document index ${index}.`);
    }
    static invalidEventCode(what: string) {
        return new ExiError("invalidEventCode", `${what}: an event code this position does not allow.`);
    }
    static unknownEnumValue(type: string, index: number) {
        return new ExiError("unknownEnumValue", `${index} is not a member of ${type}.`);
    }
    static unsupportedConstruct(what: string) {
        return new ExiError("unsupportedConstruct", `${what} is modelled as absent-only; reading one is a gap, not a malformed stream.`);
    }
}


/**
 * Bit-level writer, MSB-first within each byte to match EXI bit-packed alignment: the first bit
 * written occupies bit 7 (0x80) of the first byte, the second bit 6 (0x40), and so on.
 *
 * A faithful port of the C# `BitWriter` and its Kotlin and Swift counterparts; all four must agree
 * bit for bit. It owns and grows its buffer, as the Swift one does — the C#/Kotlin shape of writing
 * into a caller's array at an offset buys nothing here.
 */
export class BitWriter {

    #buffer: Uint8Array;
    #bitPos = 0;

    constructor(capacity = 256) {
        this.#buffer = new Uint8Array(Math.max(capacity, 16));
    }

    get bitsWritten(): number { return this.#bitPos; }
    get bytesWritten(): number { return (this.#bitPos + 7) >> 3; }

    /** The bytes written so far, zero-padded to a byte boundary. */
    get bytes(): Uint8Array { return this.#buffer.slice(0, this.bytesWritten); }

    /** Write the lowest `numBits` of `value`, MSB first. */
    writeBits(value: number, numBits: number): void {
        if (numBits < 0 || numBits > 32) throw new RangeError(`numBits out of range: ${numBits}`);
        for (let i = numBits - 1; i >= 0; i--) {
            // >>> rather than >>: a 32-bit write of a value with bit 31 set would otherwise sign-extend.
            this.writeBit(((value >>> i) & 1) !== 0);
        }
    }

    writeBit(bit: boolean): void {

        const byteIndex = this.#bitPos >> 3;
        if (byteIndex >= this.#buffer.length) this.#grow(byteIndex + 1);

        const mask = 1 << (7 - (this.#bitPos & 7));
        // Overwrite the target bit rather than only OR-ing 1s, so a rewound writer cannot leave
        // stale 1-bits behind — the bug the C# BitWriter had, found by re-recording a session trace.
        if (bit) this.#buffer[byteIndex] |= mask;
        else     this.#buffer[byteIndex] &= ~mask;

        this.#bitPos++;
    }

    /** Pad to the next byte boundary with zero bits. */
    alignToByte(): void {
        const remainder = this.#bitPos & 7;
        if (remainder !== 0) for (let i = 0; i < 8 - remainder; i++) this.writeBit(false);
    }

    #grow(needed: number): void {
        const grown = new Uint8Array(Math.max(needed, this.#buffer.length * 2));
        grown.set(this.#buffer);
        this.#buffer = grown;
    }
}


/** Bit-level reader, MSB-first to match EXI bit-packed alignment. */
export class BitReader {

    readonly #buffer: Uint8Array;
    readonly #offset: number;
    #bitPos = 0;

    /**
     * The EXI string value-table partitions for this stream.
     *
     * It hangs off the reader so the generated decoders need no extra parameter threaded through
     * every call — a value read only has to name its own slot. There is deliberately no counterpart
     * on {@link BitWriter}: cbV2G is miss-only, every checked-in vector is its output, and an
     * encoder that started emitting hits would invalidate all of them.
     */
    readonly stringTable = new ExiStringTable();

    constructor(buffer: Uint8Array, offset = 0) {
        this.#buffer = buffer;
        this.#offset = offset;
    }

    get bitsRead(): number { return this.#bitPos; }
    get bytesConsumed(): number { return (this.#bitPos + 7) >> 3; }

    readBit(): boolean {
        const byteIndex = this.#offset + (this.#bitPos >> 3);
        if (byteIndex >= this.#buffer.length) throw ExiError.bitstreamExhausted();
        const bit = ((this.#buffer[byteIndex] >> (7 - (this.#bitPos & 7))) & 1) !== 0;
        this.#bitPos++;
        return bit;
    }

    readBits(numBits: number): number {
        if (numBits < 0 || numBits > 32) throw new RangeError(`numBits out of range: ${numBits}`);
        let value = 0;
        // `* 2 +` rather than `<< 1 |`: a 32-bit read would otherwise produce a negative number,
        // because JavaScript's bitwise operators are defined on signed 32-bit integers.
        for (let i = 0; i < numBits; i++) value = value * 2 + (this.readBit() ? 1 : 0);
        return value;
    }
}
