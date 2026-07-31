package cloud.charging.v2g.exi

/**
 * A JSON value, with the order of an object's members preserved.
 *
 * ## Why this is hand-written rather than kotlinx.serialization or Gson
 *
 * The JSON-LD form is checked across three back ends by comparing **text**, character for character,
 * against `Vectors/JsonLd.documents.json`. That makes escaping and number formatting part of the
 * format rather than an implementation detail — and three JSON libraries have three sets of
 * conventions about exactly those. Reconciling them would be more work than the two hundred lines
 * below, and would leave the agreement resting on library versions.
 *
 * The order matters for the same reason: two objects with the same members in a different order are
 * equal as data and different as a document. [JsonObject] therefore keeps insertion order, which is
 * also what makes a generated message readable — `@type` first, then the fields in schema order.
 *
 * It is deliberately not a general-purpose JSON library. There is no support for anything the
 * generated serializer does not emit.
 */
sealed class JsonValue {

    /** The compact text form — the one the corpus compares. */
    fun toJsonString(): String = StringBuilder().also { write(it) }.toString()

    internal abstract fun write(sb: StringBuilder)

    override fun toString(): String = toJsonString()


    companion object {

        fun of(value: String)  : JsonValue = JsonString(value)
        fun of(value: Boolean) : JsonValue = if (value) JsonBool.True else JsonBool.False

        /** An integral number. Never a floating-point one: nothing in ISO 15118's JSON form is. */
        fun of(value: Long)    : JsonValue = JsonNumber(value.toString())
        fun of(value: Int)     : JsonValue = JsonNumber(value.toString())
        fun of(value: UInt)    : JsonValue = JsonNumber(value.toString())
        fun of(value: UShort)  : JsonValue = JsonNumber(value.toString())
        fun of(value: UByte)   : JsonValue = JsonNumber(value.toString())
        fun of(value: Short)   : JsonValue = JsonNumber(value.toString())
        fun of(value: Byte)    : JsonValue = JsonNumber(value.toString())

        /** Reads a document. Throws [JsonLdException] on anything that is not JSON. */
        fun parse(text: String): JsonValue = JsonReader(text).readDocument()
    }
}


class JsonObject : JsonValue() {

    private val members = LinkedHashMap<String, JsonValue>()

    operator fun set(key: String, value: JsonValue?) {
        if (value != null) members[key] = value
    }

    operator fun get(key: String): JsonValue? = members[key]

    fun containsKey(key: String): Boolean = members.containsKey(key)

    val keys: Set<String> get() = members.keys
    val size: Int get() = members.size

    override fun write(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((key, value) in members) {
            if (!first) sb.append(',')
            first = false
            JsonString(key).write(sb)
            sb.append(':')
            value.write(sb)
        }
        sb.append('}')
    }
}


class JsonArray : JsonValue() {

    private val items = ArrayList<JsonValue>()

    fun add(value: JsonValue) { items.add(value) }

    val size: Int get() = items.size
    operator fun get(index: Int): JsonValue = items[index]
    fun asList(): List<JsonValue> = items

    override fun write(sb: StringBuilder) {
        sb.append('[')
        for ((i, item) in items.withIndex()) {
            if (i > 0) sb.append(',')
            item.write(sb)
        }
        sb.append(']')
    }
}


class JsonString(val value: String) : JsonValue() {

    /**
     * Escapes exactly what RFC 8259 requires and nothing else.
     *
     * Not `\u` for every non-ASCII character: the generated documents are UTF-8, the corpus is
     * UTF-8, and escaping beyond the requirement is one of the places three JSON libraries would
     * disagree while all being correct.
     */
    override fun write(sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when {
                c == '"'   -> sb.append("\\\"")
                c == '\\'  -> sb.append("\\\\")
                c == '\n'  -> sb.append("\\n")
                c == '\r'  -> sb.append("\\r")
                c == '\t'  -> sb.append("\\t")
                c == '\b'  -> sb.append("\\b")
                c == '' -> sb.append("\\f")
                c < ' '    -> sb.append("\\u").append("%04x".format(c.code))
                else       -> sb.append(c)
            }
        }
        sb.append('"')
    }
}


/** A number, kept as the text it was written or read as — see [JsonPrimitives] for why. */
class JsonNumber(val text: String) : JsonValue() {
    override fun write(sb: StringBuilder) { sb.append(text) }
}


class JsonBool private constructor(val value: Boolean) : JsonValue() {
    override fun write(sb: StringBuilder) { sb.append(if (value) "true" else "false") }

    companion object {
        val True  = JsonBool(true)
        val False = JsonBool(false)
    }
}


object JsonNull : JsonValue() {
    override fun write(sb: StringBuilder) { sb.append("null") }
}


/**
 * A small recursive-descent reader.
 *
 * It refuses trailing content, unterminated strings and unquoted keys rather than guessing, because
 * everything it reads arrives from outside — a station, a QR code, a bridge — and a lenient parser
 * is an attack surface with a friendly name.
 */
private class JsonReader(private val text: String) {

    private var at = 0

    fun readDocument(): JsonValue {
        val value = readValue()
        skipWhitespace()
        if (at != text.length) fail("trailing content")
        return value
    }

    private fun readValue(): JsonValue {
        skipWhitespace()
        if (at >= text.length) fail("the document ended early")

        return when (val c = text[at]) {
            '{'  -> readObject()
            '['  -> readArray()
            '"'  -> JsonString(readString())
            't'  -> literal("true", JsonBool.True)
            'f'  -> literal("false", JsonBool.False)
            'n'  -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
        }
    }

    private fun readObject(): JsonObject {
        val json = JsonObject()
        at++                                   // '{'
        skipWhitespace()
        if (peek() == '}') { at++; return json }

        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("an object key must be a string")
            val key = readString()
            skipWhitespace()
            if (peek() != ':') fail("expected ':' after the key '$key'")
            at++
            json[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> at++
                '}' -> { at++; return json }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): JsonArray {
        val array = JsonArray()
        at++                                   // '['
        skipWhitespace()
        if (peek() == ']') { at++; return array }

        while (true) {
            array.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> at++
                ']' -> { at++; return array }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        at++                                   // '"'
        val sb = StringBuilder()
        while (true) {
            if (at >= text.length) fail("an unterminated string")
            when (val c = text[at++]) {
                '"'  -> return sb.toString()
                '\\' -> {
                    if (at >= text.length) fail("an unterminated escape")
                    when (val e = text[at++]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/'  -> sb.append('/')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        'b'  -> sb.append('\b')
                        'f'  -> sb.append('')
                        'u'  -> {
                            if (at + 4 > text.length) fail("a truncated \\u escape")
                            sb.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> fail("unknown escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): JsonNumber {
        val start = at
        if (peek() == '-') at++
        while (at < text.length && (text[at] in '0'..'9' || text[at] in ".eE+-")) at++
        return JsonNumber(text.substring(start, at))
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, at)) fail("expected '$word'")
        at += word.length
        return value
    }

    private fun peek(): Char = if (at < text.length) text[at] else fail("the document ended early")

    private fun skipWhitespace() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun fail(what: String): Nothing =
        throw JsonLdException("malformed JSON at offset $at: $what.")
}
