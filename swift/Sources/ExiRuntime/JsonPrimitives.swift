import Foundation

/// The hand-written half of the generated JSON-LD (de)serializer — the Swift twin of C#'s and
/// Kotlin's `JsonPrimitives`, method for method.
///
/// Every function exists because the generated alternative would be worse: emitting the same
/// checking beside each of ~1,800 fields would multiply the size of the generated sources and give
/// the emitter ~1,800 chances to get it slightly wrong instead of one chance here. Nothing in this
/// file knows a schema, a field or a message.
///
/// **Errors name the field and the type that wanted it.** A failure deep inside a nested message is
/// otherwise a trap or a nil-unwrap whose stack trace is all generated function names, and the one
/// thing it does not say is which property was missing.
public enum JsonPrimitives {

    // MARK: Structure

    public static func object(_ node: JsonValue?, _ what: String) throws -> JsonObject {
        guard let node else { throw JsonLdError("\(what) is missing.") }
        guard let json = node as? JsonObject else {
            throw JsonLdError("\(what) is \(describe(node)), not a JSON object.")
        }
        return json
    }

    /// The `@type` discriminator.
    ///
    /// Every generated object carries one, which is what makes a polymorphic field readable at all:
    /// a substitution-group member is chosen on the wire by an event code that JSON has no
    /// equivalent of, so the concrete type has to be written down.
    public static func typeTag(_ json: JsonObject, _ what: String) throws -> String {
        guard let tag = json["@type"] as? JsonString else {
            throw JsonLdError("\(what) has no \"@type\".")
        }
        return tag.value
    }

    public static func required(_ json: JsonObject, _ property: String, _ owner: String) throws -> JsonValue {
        guard let node = json[property] else {
            throw JsonLdError("\(owner) is missing the required property '\(property)'.")
        }
        return node
    }

    /// An optional property — nil when absent *or* explicitly null.
    ///
    /// The two are folded together deliberately: the serializer omits absent values rather than
    /// writing nulls, so a null can only come from something else's serializer, and refusing it
    /// would make this stricter than it can justify being.
    public static func optional(_ json: JsonObject, _ property: String) -> JsonValue? {
        guard let node = json[property], !(node is JsonNull) else { return nil }
        return node
    }

    public static func array(_ json: JsonObject, _ property: String, _ owner: String) throws -> JsonArray {
        let node = try required(json, property, owner)
        guard let array = node as? JsonArray else {
            throw JsonLdError("\(owner).\(property) is \(describe(node)), not a JSON array.")
        }
        return array
    }

    /// The result of parsing a polymorphic property, checked against the type the field declares.
    ///
    /// A bare `as!` would trap, naming two generated type names and nothing else. This says which
    /// property carried the wrong `@type`, which is the only part a caller can act on.
    public static func cast<T>(_ parsed: Any, _ property: String, _ owner: String) throws -> T {
        guard let value = parsed as? T else {
            throw JsonLdError("\(owner).\(property) has @type '\(type(of: parsed))', which is not a "
                            + "\(T.self).")
        }
        return value
    }


    // MARK: Values

    public static func bool(_ node: JsonValue, _ property: String, _ owner: String) throws -> Bool {
        guard let value = node as? JsonBool else { throw wrong(node, property, owner, "a boolean") }
        return value.value
    }

    public static func int8  (_ n: JsonValue, _ p: String, _ o: String) throws -> Int8   { try number(n, p, o) }
    public static func int16 (_ n: JsonValue, _ p: String, _ o: String) throws -> Int16  { try number(n, p, o) }
    public static func int32 (_ n: JsonValue, _ p: String, _ o: String) throws -> Int32  { try number(n, p, o) }
    public static func uint8 (_ n: JsonValue, _ p: String, _ o: String) throws -> UInt8  { try number(n, p, o) }
    public static func uint16(_ n: JsonValue, _ p: String, _ o: String) throws -> UInt16 { try number(n, p, o) }
    public static func uint32(_ n: JsonValue, _ p: String, _ o: String) throws -> UInt32 { try number(n, p, o) }

    /// A 64-bit integer, written and read as a **JSON string**.
    ///
    /// Not a number, and this is the one place the format deliberately departs from the obvious
    /// encoding. JSON has no integers — only doubles — and the JSON-LD side of this bridge is
    /// consumed by JavaScript, where every number above 2^53 is silently rounded. ISO 15118 reaches
    /// past it: `X509SerialNumber` is an `xs:long` and real certificate serials use the full range,
    /// and `TimeAnchor`/`TimeStamp` are `xs:unsignedLong`.
    ///
    /// The corruption would be silent, would not reproduce here, and would appear only on a phone,
    /// as a certificate that fails to verify.
    public static func int64(_ node: JsonValue, _ property: String, _ owner: String) throws -> Int64 {
        guard let value = Int64(try stringValue(node, property, owner)) else {
            throw JsonLdError("\(owner).\(property) is not a 64-bit integer.")
        }
        return value
    }

    public static func uint64(_ node: JsonValue, _ property: String, _ owner: String) throws -> UInt64 {
        guard let value = UInt64(try stringValue(node, property, owner)) else {
            throw JsonLdError("\(owner).\(property) is not an unsigned 64-bit integer.")
        }
        return value
    }

    public static func stringValue(_ node: JsonValue, _ property: String, _ owner: String) throws -> String {
        guard let value = node as? JsonString else { throw wrong(node, property, owner, "a string") }
        return value.value
    }

    /// An octet string, as lower-case hex.
    ///
    /// Hex rather than base64 although the XSD has both `xs:hexBinary` and `xs:base64Binary`: the
    /// grammar layer collapses them to one kind, so the JSON cannot tell them apart, and picking one
    /// keeps the mapping single-valued. Hex is also what every vector file in this repository shows.
    public static func binary(_ node: JsonValue, _ property: String, _ owner: String) throws -> [UInt8] {

        let text = Array(try stringValue(node, property, owner))
        guard text.count % 2 == 0 else { throw JsonLdError("\(owner).\(property) is not hex.") }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(text.count / 2)

        for i in stride(from: 0, to: text.count, by: 2) {
            guard let byte = UInt8(String(text[i ... i + 1]), radix: 16) else {
                throw JsonLdError("\(owner).\(property) is not hex.")
            }
            bytes.append(byte)
        }
        return bytes
    }

    /// Hex for the serializer's side, lower-case.
    public static func toHex(_ value: [UInt8]) -> String {
        value.map { String(format: "%02x", $0) }.joined()
    }

    public static func fromInt64(_ value: Int64)   -> String { String(value) }
    public static func fromUInt64(_ value: UInt64) -> String { String(value) }

    /// An enumeration, by **case name**.
    ///
    /// Not by raw value: the generated Swift enums are `Int`-backed, because the wire encoding is an
    /// index, and an index in the JSON would make the bridge's output depend on the order members
    /// happen to appear in the XSD. The name is what a person reading an event stream can act on,
    /// and it is what the other two back ends write — C#'s `ToString()` and Kotlin's `.name`.
    public static func enumeration<T: CaseIterable>(_ node: JsonValue, _ property: String,
                                                    _ owner: String, _ type: T.Type) throws -> T {

        let name = try stringValue(node, property, owner)

        guard let value = T.allCases.first(where: { String(describing: $0) == name }) else {
            throw JsonLdError("\(owner).\(property) is not a \(T.self): '\(name)'. Known: "
                            + T.allCases.map { String(describing: $0) }.joined(separator: ", ") + ".")
        }
        return value
    }

    /// An enumeration on the way out — its case name, for the reason above.
    public static func fromEnumeration<T>(_ value: T) -> String { String(describing: value) }


    // MARK: Plumbing

    /// A number, read from the text it arrived as.
    ///
    /// ``JsonNumber`` keeps the text rather than a parsed value on purpose. A JSON number has no
    /// width, so a reader that eagerly parsed into `Int64` or `Double` would have to widen and then
    /// narrow — and narrowing is where a value silently changes. Parsing straight into the type the
    /// field actually declares makes an out-of-range value a refusal instead.
    private static func number<T: FixedWidthInteger>(_ node: JsonValue, _ property: String,
                                                     _ owner: String) throws -> T {

        guard let text = (node as? JsonNumber)?.text else {
            throw wrong(node, property, owner, "a number")
        }
        guard let value = T(text) else {
            throw JsonLdError("\(owner).\(property) is \(text), which a \(T.self) cannot hold.")
        }
        return value
    }

    private static func wrong(_ node: JsonValue, _ property: String, _ owner: String,
                              _ expected: String) -> JsonLdError {
        JsonLdError("\(owner).\(property) is \(describe(node)), which is not \(expected).")
    }

    private static func describe(_ node: JsonValue) -> String {
        switch node {
        case is JsonObject:            return "an object"
        case is JsonArray:             return "an array"
        case let s as JsonString:      return "the string \"\(s.value)\""
        case is JsonNull:              return "null"
        default:                       return "'\(node.jsonString)'"
        }
    }
}


/// A JSON-LD document that could not be read as the message it claims to be.
///
/// Its own type rather than a generic parse error, because these are two different failures with two
/// different audiences: malformed text is a transport problem, while this is a schema problem.
public struct JsonLdError: Error, CustomStringConvertible, Equatable {

    public let description: String

    public init(_ description: String) { self.description = description }
}
