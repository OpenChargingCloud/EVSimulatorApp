import { BitReader, BitWriter, ExiError } from "./bitio.ts";
import { ExiPrimitives } from "./exi-primitives.ts";

/**
 * The EXI string value tables: one local partition per slot, plus one global partition per stream.
 *
 * A port of the C#, Kotlin and Swift tables. The generated encoders are **miss-only** — cbV2G never
 * emits hits, and every checked-in vector is its output — but the decode side has to resolve them,
 * because they are legal EXI and a conforming peer (EXIficient, Josev) may send them. The write
 * path exists so the hit case is testable in both directions rather than only asserted about.
 */
export class ExiStringTable {

    readonly #global: string[] = [];
    readonly #globalIndex = new Map<string, number>();
    readonly #locals = new Map<string, { values: string[]; index: Map<string, number> }>();

    #partition(slot: string) {
        let partition = this.#locals.get(slot);
        if (partition === undefined) {
            partition = { values: [], index: new Map() };
            this.#locals.set(slot, partition);
        }
        return partition;
    }

    /** Encode a string value at `slot`, emitting a hit when possible. */
    writeStringValue(w: BitWriter, slot: string, value: string): void {

        const local = this.#partition(slot);

        const localId = local.index.get(value);
        if (localId !== undefined) {
            ExiPrimitives.writeUnsignedInteger(w, 0n);
            ExiStringTable.#writeCompactId(w, localId, local.values.length);
            return;
        }

        const globalId = this.#globalIndex.get(value);
        if (globalId !== undefined) {
            ExiPrimitives.writeUnsignedInteger(w, 1n);
            ExiStringTable.#writeCompactId(w, globalId, this.#global.length);
            return;
        }

        const points = [...value];
        ExiPrimitives.writeUnsignedInteger(w, BigInt(points.length + 2));
        for (const point of points) ExiPrimitives.writeUnsignedInteger(w, BigInt(point.codePointAt(0)!));

        this.#add(slot, value);
    }

    /** Decode a string value at `slot`, resolving hits against the partitions. */
    readStringValue(r: BitReader, slot: string): string {

        const local = this.#partition(slot);
        const head = ExiPrimitives.readUnsignedInteger(r);

        if (head === 0n) {
            const id = ExiStringTable.#readCompactId(r, local.values.length);
            if (id >= local.values.length) throw ExiError.valueTableHitOutOfRange(id, local.values.length);
            return local.values[id];
        }

        if (head === 1n) {
            const id = ExiStringTable.#readCompactId(r, this.#global.length);
            if (id >= this.#global.length) throw ExiError.valueTableHitOutOfRange(id, this.#global.length);
            return this.#global[id];
        }

        const length = Number(head - 2n);
        let value = "";

        for (let i = 0; i < length; i++) {
            const point = ExiPrimitives.readUnsignedInteger(r);
            if (point > 0x10FFFFn || (point >= 0xD800n && point <= 0xDFFFn)) {
                throw ExiError.invalidCodePoint(point);
            }
            value += String.fromCodePoint(Number(point));
        }

        this.#add(slot, value);
        return value;
    }

    /** Append a freshly-seen (miss) value to the local and global partitions. */
    #add(slot: string, value: string): void {

        const local = this.#partition(slot);
        local.index.set(value, local.values.length);
        local.values.push(value);

        // A miss means the value was in neither partition, so it is new globally too.
        this.#globalIndex.set(value, this.#global.length);
        this.#global.push(value);
    }

    static #writeCompactId(w: BitWriter, id: number, partitionSize: number): void {
        const bits = ExiStringTable.#bitsFor(partitionSize);
        if (bits > 0) w.writeBits(id, bits);
    }

    static #readCompactId(r: BitReader, partitionSize: number): number {
        const bits = ExiStringTable.#bitsFor(partitionSize);
        return bits > 0 ? r.readBits(bits) : 0;
    }

    /** ⌈log₂(count)⌉, with the EXI convention that a size-1 partition needs 0 bits. */
    static #bitsFor(count: number): number {
        if (count <= 1) return 0;
        let bits = 0;
        for (let v = count - 1; v > 0; v >>= 1) bits++;
        return bits;
    }
}
