package cloud.charging.v2g.exi

/**
 * The hand-written half of the generated JSON-LD (de)serializer — the Kotlin twin of C#'s
 * `JsonPrimitives`, method for method.
 *
 * Every function exists because the generated alternative would be worse: emitting the same
 * null-checking and range-checking beside each of ~1,800 fields would multiply the size of the
 * generated sources and give the emitter ~1,800 chances to get it slightly wrong instead of one
 * chance here. Nothing in this file knows a schema, a field or a message.
 *
 * **Errors name the field and the type that wanted it.** A failure deep inside a nested message is
 * otherwise a null-pointer exception whose stack trace is all generated function names, and the one
 * thing it does not say is which property was missing.
 */
object JsonPrimitives {

    // ── Structure ─────────────────────────────────────────────────────────

    fun obj(node: JsonValue?, what: String): JsonObject =
        node as? JsonObject
            ?: throw JsonLdException(
                   if (node == null) "$what is missing."
                   else "$what is ${describe(node)}, not a JSON object.")

    /**
     * The `@type` discriminator.
     *
     * Every generated object carries one, which is what makes a polymorphic field readable at all: a
     * substitution-group member is chosen on the wire by an event code that JSON has no equivalent
     * of, so the concrete type has to be written down.
     */
    fun typeTag(json: JsonObject, what: String): String =
        (json["@type"] as? JsonString)?.value
            ?: throw JsonLdException("$what has no \"@type\".")

    fun required(json: JsonObject, property: String, owner: String): JsonValue =
        json[property] ?: throw JsonLdException("$owner is missing the required property '$property'.")

    /**
     * An optional property — null when absent *or* explicitly null.
     *
     * The two are folded together deliberately: the serializer omits absent values rather than
     * writing nulls, so a null can only come from something else's serializer, and refusing it would
     * make this stricter than it can justify being.
     */
    fun optional(json: JsonObject, property: String): JsonValue? =
        json[property]?.takeIf { it !is JsonNull }

    fun array(json: JsonObject, property: String, owner: String): JsonArray =
        required(json, property, owner) as? JsonArray
            ?: throw JsonLdException("$owner.$property is ${describe(json[property]!!)}, not a JSON array.")

    /**
     * The result of parsing a polymorphic property, checked against the type the field declares.
     *
     * A bare cast would raise a ClassCastException naming two generated type names and nothing else.
     * This says which property carried the wrong `@type`, which is the only part a caller can act on.
     */
    inline fun <reified T> cast(parsed: Any, property: String, owner: String): T =
        parsed as? T
            ?: throw JsonLdException(
                   "$owner.$property has @type '${parsed::class.simpleName}', which is not a " +
                   "${T::class.simpleName}.")


    // ── Values ────────────────────────────────────────────────────────────

    fun bool(node: JsonValue, property: String, owner: String): Boolean =
        (node as? JsonBool)?.value ?: wrong(node, property, owner, "a boolean")

    fun int8  (node: JsonValue, property: String, owner: String): Byte   = number(node, property, owner, String::toByteOrNull,   "an 8-bit integer")
    fun int16 (node: JsonValue, property: String, owner: String): Short  = number(node, property, owner, String::toShortOrNull,  "a 16-bit integer")
    fun int32 (node: JsonValue, property: String, owner: String): Int    = number(node, property, owner, String::toIntOrNull,    "a 32-bit integer")
    fun uint8 (node: JsonValue, property: String, owner: String): UByte  = number(node, property, owner, String::toUByteOrNull,  "an unsigned 8-bit integer")
    fun uint16(node: JsonValue, property: String, owner: String): UShort = number(node, property, owner, String::toUShortOrNull, "an unsigned 16-bit integer")
    fun uint32(node: JsonValue, property: String, owner: String): UInt   = number(node, property, owner, String::toUIntOrNull,   "an unsigned 32-bit integer")

    /**
     * A 64-bit integer, written and read as a **JSON string**.
     *
     * Not a number, and this is the one place the format deliberately departs from the obvious
     * encoding. JSON has no integers — only doubles — and the JSON-LD side of this bridge is
     * consumed by JavaScript, where every number above 2^53 is silently rounded. ISO 15118 reaches
     * past it: `X509SerialNumber` is an `xs:long` and real certificate serials use the full range,
     * and `TimeAnchor`/`TimeStamp` are `xs:unsignedLong`.
     *
     * The corruption would be silent, would not reproduce on the JVM, and would appear only on a
     * phone, as a certificate that fails to verify.
     */
    fun int64(node: JsonValue, property: String, owner: String): Long =
        stringValue(node, property, owner).toLongOrNull()
            ?: throw JsonLdException("$owner.$property is not a 64-bit integer.")

    fun uint64(node: JsonValue, property: String, owner: String): ULong =
        stringValue(node, property, owner).toULongOrNull()
            ?: throw JsonLdException("$owner.$property is not an unsigned 64-bit integer.")

    fun stringValue(node: JsonValue, property: String, owner: String): String =
        (node as? JsonString)?.value ?: wrong(node, property, owner, "a string")

    /**
     * An octet string, as lower-case hex.
     *
     * Hex rather than base64 although the XSD has both `xs:hexBinary` and `xs:base64Binary`: the
     * grammar layer collapses them to one kind, so the JSON cannot tell them apart, and picking one
     * keeps the mapping single-valued. Hex is also what every vector file in this repository shows.
     */
    fun binary(node: JsonValue, property: String, owner: String): ByteArray {

        val text = stringValue(node, property, owner)
        if (text.length % 2 != 0) throw JsonLdException("$owner.$property is not hex.")

        return ByteArray(text.length / 2) {
            text.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte()
                ?: throw JsonLdException("$owner.$property is not hex.")
        }
    }

    /** Hex for the serializer's side, lower-case. */
    fun toHex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromInt64(value: Long)   : String = value.toString()
    fun fromUInt64(value: ULong) : String = value.toString()

    fun <T : Enum<T>> enumeration(node: JsonValue, property: String, owner: String,
                                  values: Array<T>): T {
        val name = stringValue(node, property, owner)
        return values.firstOrNull { it.name == name }
            ?: throw JsonLdException(
                   "$owner.$property is not a ${values.firstOrNull()?.javaClass?.simpleName}: " +
                   "'$name'. Known: ${values.joinToString(", ") { it.name }}.")
    }


    // ── Plumbing ──────────────────────────────────────────────────────────

    /**
     * A number, read from the text it arrived as.
     *
     * [JsonNumber] keeps the text rather than a parsed value on purpose. A JSON number has no width,
     * so a reader that eagerly parsed into `Long` or `Double` would have to widen and then narrow —
     * and narrowing is where a value silently changes. Parsing straight into the type the field
     * actually declares makes an out-of-range value a refusal instead.
     */
    private inline fun <T> number(node: JsonValue, property: String, owner: String,
                                  parse: (String) -> T?, expected: String): T {

        val text = (node as? JsonNumber)?.text ?: wrong(node, property, owner, "a number")

        return parse(text)
            ?: throw JsonLdException("$owner.$property is $text, which is not $expected.")
    }

    private fun wrong(node: JsonValue, property: String, owner: String, expected: String): Nothing =
        throw JsonLdException("$owner.$property is ${describe(node)}, which is not $expected.")

    private fun describe(node: JsonValue): String = when (node) {
        is JsonObject -> "an object"
        is JsonArray  -> "an array"
        is JsonString -> "the string \"${node.value}\""
        is JsonNull   -> "null"
        else          -> "'${node.toJsonString()}'"
    }
}


/**
 * A JSON-LD document that could not be read as the message it claims to be.
 *
 * Its own type rather than a generic parse error, because these are two different failures with two
 * different audiences: malformed text is a transport problem, while this is a schema problem.
 */
class JsonLdException(message: String) : Exception(message)
