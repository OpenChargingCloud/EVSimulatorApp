import Foundation

/// A JSON value, with the order of an object's members preserved.
///
/// ## Why this is hand-written rather than `JSONSerialization` or `Codable`
///
/// The JSON-LD form is checked across three back ends by comparing **text**, character for
/// character, against `Vectors/JsonLd.documents.json`. That makes member order, escaping and number
/// formatting part of the format rather than implementation details — and `JSONSerialization` gives
/// up the first of those immediately: it reads an object into a `Dictionary`, which has no order at
/// all, and writes one back in whatever order it likes.
///
/// It is deliberately not a general-purpose JSON library. There is no support for anything the
/// generated serializer does not emit.
///
/// A class hierarchy rather than an `indirect enum`, so that reading a value is `as?` — the same
/// shape the Kotlin twin uses, which keeps the two emitters producing parallel code.
public class JsonValue {

    /// The compact text form — the one the corpus compares.
    public var jsonString: String {
        var out = ""
        write(into: &out)
        return out
    }

    func write(into out: inout String) {
        fatalError("abstract")
    }

    // MARK: Construction

    public static func of(_ value: String) -> JsonValue { JsonString(value) }
    public static func of(_ value: Bool)   -> JsonValue { JsonBool(value) }

    /// An integral number. Never a floating-point one: nothing in ISO 15118's JSON form is.
    public static func of(_ value: Int8)   -> JsonValue { JsonNumber(String(value)) }
    public static func of(_ value: Int16)  -> JsonValue { JsonNumber(String(value)) }
    public static func of(_ value: Int32)  -> JsonValue { JsonNumber(String(value)) }
    public static func of(_ value: UInt8)  -> JsonValue { JsonNumber(String(value)) }
    public static func of(_ value: UInt16) -> JsonValue { JsonNumber(String(value)) }
    public static func of(_ value: UInt32) -> JsonValue { JsonNumber(String(value)) }

    /// Reads a document. Throws ``JsonLdError`` on anything that is not JSON.
    public static func parse(_ text: String) throws -> JsonValue {
        var reader = JsonReader(text)
        return try reader.readDocument()
    }
}


public final class JsonObject: JsonValue {

    private var order: [String] = []
    private var members: [String: JsonValue] = [:]

    public override init() {}

    public subscript(key: String) -> JsonValue? {
        get { members[key] }
        set {
            guard let newValue else { return }
            if members[key] == nil { order.append(key) }
            members[key] = newValue
        }
    }

    public var keys: [String] { order }
    public var count: Int { order.count }
    public func contains(_ key: String) -> Bool { members[key] != nil }

    override func write(into out: inout String) {
        out += "{"
        for (i, key) in order.enumerated() {
            if i > 0 { out += "," }
            JsonString(key).write(into: &out)
            out += ":"
            members[key]!.write(into: &out)
        }
        out += "}"
    }
}


public final class JsonArray: JsonValue {

    private var items: [JsonValue] = []

    public override init() {}

    public func add(_ value: JsonValue) { items.append(value) }

    public var count: Int { items.count }
    public var asList: [JsonValue] { items }

    override func write(into out: inout String) {
        out += "["
        for (i, item) in items.enumerated() {
            if i > 0 { out += "," }
            item.write(into: &out)
        }
        out += "]"
    }
}


public final class JsonString: JsonValue {

    public let value: String

    public init(_ value: String) { self.value = value }

    /// Escapes exactly what RFC 8259 requires and nothing else.
    ///
    /// Not `\u` for every non-ASCII character: the documents are UTF-8 and so is the corpus, and
    /// escaping beyond the requirement is one of the places three JSON writers would disagree while
    /// all being correct.
    override func write(into out: inout String) {
        out += "\""
        for c in value.unicodeScalars {
            switch c {
            case "\"":  out += "\\\""
            case "\\":  out += "\\\\"
            case "\n":  out += "\\n"
            case "\r":  out += "\\r"
            case "\t":  out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if c.value < 0x20 {
                    out += String(format: "\\u%04x", c.value)
                } else {
                    out.unicodeScalars.append(c)
                }
            }
        }
        out += "\""
    }
}


/// A number, kept as the text it was written or read as — see ``JsonPrimitives`` for why.
public final class JsonNumber: JsonValue {

    public let text: String

    public init(_ text: String) { self.text = text }

    override func write(into out: inout String) { out += text }
}


public final class JsonBool: JsonValue {

    public let value: Bool

    public init(_ value: Bool) { self.value = value }

    override func write(into out: inout String) { out += value ? "true" : "false" }
}


public final class JsonNull: JsonValue {

    public override init() {}

    override func write(into out: inout String) { out += "null" }
}


/// A small recursive-descent reader.
///
/// It refuses trailing content, unterminated strings and unquoted keys rather than guessing, because
/// everything it reads arrives from outside — a station, a QR code, a bridge — and a lenient parser
/// is an attack surface with a friendly name.
private struct JsonReader {

    private let scalars: [Character]
    private var at = 0

    init(_ text: String) { self.scalars = Array(text) }

    mutating func readDocument() throws -> JsonValue {
        let value = try readValue()
        skipWhitespace()
        guard at == scalars.count else { throw fail("trailing content") }
        return value
    }

    private mutating func readValue() throws -> JsonValue {

        skipWhitespace()
        guard at < scalars.count else { throw fail("the document ended early") }

        switch scalars[at] {
        case "{":  return try readObject()
        case "[":  return try readArray()
        case "\"": return JsonString(try readString())
        case "t":  return try literal("true",  JsonBool(true))
        case "f":  return try literal("false", JsonBool(false))
        case "n":  return try literal("null",  JsonNull())
        case let c where c == "-" || c.isNumber: return readNumber()
        case let c: throw fail("unexpected '\(c)'")
        }
    }

    private mutating func readObject() throws -> JsonObject {

        let json = JsonObject()
        at += 1                                   // '{'
        skipWhitespace()
        if try peek() == "}" { at += 1; return json }

        while true {
            skipWhitespace()
            guard try peek() == "\"" else { throw fail("an object key must be a string") }
            let key = try readString()
            skipWhitespace()
            guard try peek() == ":" else { throw fail("expected ':' after the key '\(key)'") }
            at += 1
            json[key] = try readValue()
            skipWhitespace()
            switch try peek() {
            case ",": at += 1
            case "}": at += 1; return json
            default:  throw fail("expected ',' or '}'")
            }
        }
    }

    private mutating func readArray() throws -> JsonArray {

        let array = JsonArray()
        at += 1                                   // '['
        skipWhitespace()
        if try peek() == "]" { at += 1; return array }

        while true {
            array.add(try readValue())
            skipWhitespace()
            switch try peek() {
            case ",": at += 1
            case "]": at += 1; return array
            default:  throw fail("expected ',' or ']'")
            }
        }
    }

    private mutating func readString() throws -> String {

        at += 1                                   // '"'
        var out = ""

        while true {
            guard at < scalars.count else { throw fail("an unterminated string") }
            let c = scalars[at]; at += 1

            if c == "\"" { return out }
            guard c == "\\" else { out.append(c); continue }

            guard at < scalars.count else { throw fail("an unterminated escape") }
            let e = scalars[at]; at += 1

            switch e {
            case "\"": out += "\""
            case "\\": out += "\\"
            case "/":  out += "/"
            case "n":  out += "\n"
            case "r":  out += "\r"
            case "t":  out += "\t"
            case "b":  out += "\u{08}"
            case "f":  out += "\u{0C}"
            case "u":
                guard at + 4 <= scalars.count,
                      let code = UInt32(String(scalars[at ..< at + 4]), radix: 16),
                      let scalar = Unicode.Scalar(code)
                else { throw fail("a truncated or invalid \\u escape") }
                out.unicodeScalars.append(scalar)
                at += 4
            default: throw fail("unknown escape '\\\(e)'")
            }
        }
    }

    private mutating func readNumber() -> JsonNumber {
        let start = at
        if scalars[at] == "-" { at += 1 }
        while at < scalars.count,
              scalars[at].isNumber || JsonReader.numberPunctuation.contains(scalars[at]) { at += 1 }
        return JsonNumber(String(scalars[start ..< at]))
    }

    private mutating func literal(_ word: String, _ value: JsonValue) throws -> JsonValue {
        let end = at + word.count
        guard end <= scalars.count, String(scalars[at ..< end]) == word else {
            throw fail("expected '\(word)'")
        }
        at = end
        return value
    }

    private func peek() throws -> Character {
        guard at < scalars.count else { throw fail("the document ended early") }
        return scalars[at]
    }

    /// Whitespace, as a set of characters rather than as a string to search.
    ///
    /// `" \t\r\n".contains(c)` looks equivalent and is not: Swift's `Character` is a grapheme
    /// cluster, so CR+LF in that literal combine into a *single* character — the string holds three
    /// characters, and a lone newline is not one of them. Every pretty-printed document failed at
    /// offset 1 with "an object key must be a string", which is a long way from the cause.
    private static let whitespace: Set<Character> = [" ", "\t", "\r", "\n"]

    private static let numberPunctuation: Set<Character> = [".", "e", "E", "+", "-"]

    private mutating func skipWhitespace() {
        while at < scalars.count, JsonReader.whitespace.contains(scalars[at]) { at += 1 }
    }

    private func fail(_ what: String) -> JsonLdError {
        JsonLdError("malformed JSON at offset \(at): \(what).")
    }
}
