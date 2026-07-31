import type { JsonObject, JsonValue } from "./json.ts";

/**
 * The hand-written half of the generated JSON-LD (de)serializer — the TypeScript twin of C#'s,
 * Kotlin's and Swift's `JsonPrimitives`, method for method.
 *
 * Every function exists because the generated alternative would be worse: emitting the same checking
 * beside each of ~1,800 fields would multiply the size of the generated sources and give the emitter
 * ~1,800 chances to get it slightly wrong instead of one chance here. Nothing in this file knows a
 * schema, a field or a message.
 *
 * **Errors name the field and the type that wanted it.** A failure deep inside a nested message is
 * otherwise a `TypeError` about `undefined` whose stack trace is all generated function names, and
 * the one thing it does not say is which property was missing.
 */
export const JsonPrimitives = {

    // ── Structure ─────────────────────────────────────────────────────────

    object(node: JsonValue | undefined, what: string): JsonObject {
        if (node === undefined || node === null) throw new JsonLdError(`${what} is missing.`);
        if (typeof node !== "object" || Array.isArray(node)) {
            throw new JsonLdError(`${what} is ${describe(node)}, not a JSON object.`);
        }
        return node;
    },

    /**
     * The `@type` discriminator.
     *
     * Every generated object carries one, which is what makes a polymorphic field readable at all: a
     * substitution-group member is chosen on the wire by an event code that JSON has no equivalent
     * of, so the concrete type has to be written down.
     */
    typeTag(json: JsonObject, what: string): string {
        const tag = json["@type"];
        if (typeof tag !== "string") throw new JsonLdError(`${what} has no "@type".`);
        return tag;
    },

    required(json: JsonObject, property: string, owner: string): JsonValue {
        const node = json[property];
        if (node === undefined || node === null) {
            throw new JsonLdError(`${owner} is missing the required property '${property}'.`);
        }
        return node;
    },

    /**
     * An optional property — `null` when absent *or* explicitly null.
     *
     * The two are folded together deliberately: the serializer omits absent values rather than
     * writing nulls, so a null can only come from something else's serializer, and refusing it would
     * make this stricter than it can justify being.
     */
    optional(json: JsonObject, property: string): JsonValue | null {
        const node = json[property];
        return node === undefined || node === null ? null : node;
    },

    array(json: JsonObject, property: string, owner: string): JsonValue[] {
        const node = JsonPrimitives.required(json, property, owner);
        if (!Array.isArray(node)) {
            throw new JsonLdError(`${owner}.${property} is ${describe(node)}, not a JSON array.`);
        }
        return node;
    },

    /**
     * The result of parsing a polymorphic property, checked against the type the field declares.
     *
     * The constructor is passed in because TypeScript's types are gone at runtime — an `as T` would
     * assert without checking, and the wrong `@type` would then surface far away as a missing
     * property. The other three back ends get the same check from their own type systems.
     */
    cast<T>(parsed: unknown, type: new (...args: never[]) => T, property: string, owner: string): T {
        if (!(parsed instanceof type)) {
            throw new JsonLdError(
                `${owner}.${property} has @type '${(parsed as object)?.constructor?.name}', `
              + `which is not a ${type.name}.`);
        }
        return parsed;
    },


    // ── Values ────────────────────────────────────────────────────────────

    bool(node: JsonValue, property: string, owner: string): boolean {
        if (typeof node !== "boolean") throw wrong(node, property, owner, "a boolean");
        return node;
    },

    /**
     * An integer that fits a `number`: every width up to 32 bits.
     *
     * Checked for integrality rather than trusted. A JSON number is a double, so `1.5` and `1e400`
     * are both syntactically fine and neither is a field value — and a fractional value silently
     * truncated on the way to `writeBits` would come back as different bytes.
     */
    int(node: JsonValue, property: string, owner: string): number {
        if (typeof node !== "number") throw wrong(node, property, owner, "a number");
        if (!Number.isSafeInteger(node)) {
            throw new JsonLdError(`${owner}.${property} is ${node}, which is not an integer.`);
        }
        return node;
    },

    /**
     * A 64-bit integer, written and read as a **JSON string**.
     *
     * Not a number, and this is the one place the format deliberately departs from the obvious
     * encoding — **written for this language above all**. JSON has no integers, only doubles, and
     * everything above 2^53 is silently rounded here. ISO 15118 reaches past it: `X509SerialNumber`
     * is an `xs:long` and real certificate serials use the full range, and `TimeAnchor`/`TimeStamp`
     * are `xs:unsignedLong`.
     *
     * The other three back ends carry the same rule and would round-trip either way. This is the one
     * that would not.
     */
    big(node: JsonValue, property: string, owner: string): bigint {
        if (typeof node !== "string") throw wrong(node, property, owner, "a 64-bit integer as a string");
        try {
            return BigInt(node);
        } catch {
            throw new JsonLdError(`${owner}.${property} is not a 64-bit integer.`);
        }
    },

    stringValue(node: JsonValue, property: string, owner: string): string {
        if (typeof node !== "string") throw wrong(node, property, owner, "a string");
        return node;
    },

    /**
     * An octet string, as lower-case hex.
     *
     * Hex rather than base64 although the XSD has both `xs:hexBinary` and `xs:base64Binary`: the
     * grammar layer collapses them to one kind, so the JSON cannot tell them apart, and picking one
     * keeps the mapping single-valued. Hex is also what every vector file in this repository shows.
     */
    binary(node: JsonValue, property: string, owner: string): Uint8Array {

        const text = JsonPrimitives.stringValue(node, property, owner);
        if (text.length % 2 !== 0) throw new JsonLdError(`${owner}.${property} is not hex.`);

        const bytes = new Uint8Array(text.length / 2);
        for (let i = 0; i < bytes.length; i++) {
            const byte = Number.parseInt(text.slice(i * 2, i * 2 + 2), 16);
            if (Number.isNaN(byte)) throw new JsonLdError(`${owner}.${property} is not hex.`);
            bytes[i] = byte;
        }
        return bytes;
    },

    /** Hex for the serializer's side, lower-case. */
    toHex(value: Uint8Array): string {
        return [...value].map(b => b.toString(16).padStart(2, "0")).join("");
    },

    /**
     * An enumeration, by **case name**.
     *
     * Not by index: an index in the JSON would make the bridge's output depend on the order members
     * happen to appear in the XSD. The name is what a person reading an event stream can act on, and
     * it is what the other three back ends write.
     */
    enumeration(node: JsonValue, property: string, owner: string,
                type: string, names: readonly string[]): number {

        const name  = JsonPrimitives.stringValue(node, property, owner);
        const index = names.indexOf(name);

        if (index < 0) {
            throw new JsonLdError(`${owner}.${property} is not a ${type}: '${name}'. `
                                + `Known: ${names.join(", ")}.`);
        }
        return index;
    },
};


function wrong(node: JsonValue, property: string, owner: string, expected: string): JsonLdError {
    return new JsonLdError(`${owner}.${property} is ${describe(node)}, which is not ${expected}.`);
}

function describe(node: JsonValue): string {
    if (node === null)          return "null";
    if (Array.isArray(node))    return "an array";
    if (typeof node === "object") return "an object";
    if (typeof node === "string") return `the string "${node}"`;
    return `'${String(node)}'`;
}


/**
 * A JSON-LD document that could not be read as the message it claims to be.
 *
 * Its own type rather than a generic parse error, because these are two different failures with two
 * different audiences: malformed text is a transport problem, while this is a schema problem.
 */
export class JsonLdError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "JsonLdError";
    }
}
