import XCTest
import ExiRuntime
import ExiAppProtocol
import ExiIso2
import ExiIso20Common
import ExiIso20AC
import ExiIso20DC
import ExiIso20ACDP
import ExiIso20AcDerIec
import ExiIso20AcDerSae

/// This back end's JSON-LD documents, against the ones the C# back end produces.
///
/// ## Why this is the check, and the round trip is not
///
/// `EXI → JSON → EXI` proves a mapping loses nothing, and it is **blind to what the mapping is
/// called**: rename every property and it stays green, because the serializer and the parser rename
/// together. Measured on the C# side — replacing the naming rule with a naïve
/// lower-the-first-character one turned `evseStatus` into `eVSEStatus` in every message of every
/// set, and all 163 round-trip tests still passed.
///
/// So the agreement is checked against **text**. `JsonLd.documents.json` holds every vector's JSON
/// form exactly as C# wrote it, and this compares character for character: property names, property
/// order, `@context` and `@type` placement, hex for binary, strings for 64-bit integers, and which
/// optional properties are omitted rather than written as null.
///
/// Both directions are checked, because they can fail apart: a serializer can agree while a parser
/// quietly accepts something it should not.
///
/// **WPT is absent, and that is not this file's doing.** The Swift back end refuses
/// `WPT_LF_TransmitterDataType` — a `maxOccurs=255` repeat followed by another particle, whose
/// self-loop shape has no working reference encoder — so there is no `ExiIso20WPT` target to compare.
/// Kotlin and C# cover it.
final class JsonLdAgreementTests: XCTestCase {

    /// One message set's four generated entry points.
    private struct Bridge {
        let name: String
        let vectorFile: String
        let decodeAny: ([UInt8]) throws -> Any
        let encodeAny: (Any) throws -> [UInt8]
        let toJSON: (Any) throws -> JsonObject
        let parseJSON: (JsonValue) throws -> Any
    }

    private let bridges: [Bridge] = [
        Bridge(name: "AppProtocol", vectorFile: "AppProtocol.vectors.json",
               decodeAny: SupportedAppProtocolCodec.decodeAny,
               encodeAny: SupportedAppProtocolCodec.encodeAny,
               toJSON:    SupportedAppProtocolCodecJson.toJSON,
               parseJSON: { try SupportedAppProtocolCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-2", vectorFile: "Iso15118_2.vectors.json",
               decodeAny: Iso15118_2Codec.decodeAny,
               encodeAny: Iso15118_2Codec.encodeAny,
               toJSON:    Iso15118_2CodecJson.toJSON,
               parseJSON: { try Iso15118_2CodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 CommonMessages", vectorFile: "Iso15118_20.CommonMessages.vectors.json",
               decodeAny: CommonMessagesCodec.decodeAny,
               encodeAny: CommonMessagesCodec.encodeAny,
               toJSON:    CommonMessagesCodecJson.toJSON,
               parseJSON: { try CommonMessagesCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 DC", vectorFile: "Iso15118_20.DC.vectors.json",
               decodeAny: DCCodec.decodeAny,
               encodeAny: DCCodec.encodeAny,
               toJSON:    DCCodecJson.toJSON,
               parseJSON: { try DCCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 AC", vectorFile: "Iso15118_20.AC.vectors.json",
               decodeAny: ACCodec.decodeAny,
               encodeAny: ACCodec.encodeAny,
               toJSON:    ACCodecJson.toJSON,
               parseJSON: { try ACCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 ACDP", vectorFile: "Iso15118_20.ACDP.vectors.json",
               decodeAny: ACDPCodec.decodeAny,
               encodeAny: ACDPCodec.encodeAny,
               toJSON:    ACDPCodecJson.toJSON,
               parseJSON: { try ACDPCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 AC_DER_IEC", vectorFile: "Iso15118_20.AC_DER_IEC.vectors.json",
               decodeAny: AcDerIecCodec.decodeAny,
               encodeAny: AcDerIecCodec.encodeAny,
               toJSON:    AcDerIecCodecJson.toJSON,
               parseJSON: { try AcDerIecCodecJson.parseJSON($0) }),

        Bridge(name: "ISO 15118-20 AC_DER_SAE", vectorFile: "Iso15118_20.AC_DER_SAE.vectors.json",
               decodeAny: AcDerSaeCodec.decodeAny,
               encodeAny: AcDerSaeCodec.encodeAny,
               toJSON:    AcDerSaeCodecJson.toJSON,
               parseJSON: { try AcDerSaeCodecJson.parseJSON($0) }),
    ]


    private lazy var vectorsDirectory: URL = {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("libs/Vanaheimr.V2G.Exi").path) {
            let parent = directory.deletingLastPathComponent()
            precondition(parent != directory, "repository root not found")
            directory = parent
        }
        return directory.appendingPathComponent("libs/Vanaheimr.V2G.Exi/libs/WWCP_ISO15118/Vanaheimr.V2G.Exi.Tests/Vectors")
    }()

    private lazy var documents: JsonObject = {
        let file = vectorsDirectory.appendingPathComponent("JsonLd.documents.json")
        let text = try! String(contentsOf: file, encoding: .utf8)
        return (try! JsonValue.parse(text) as! JsonObject)["sets"] as! JsonObject
    }()

    private func vectors(_ fileName: String) throws -> [(name: String, hex: String)] {
        let text = try String(contentsOf: vectorsDirectory.appendingPathComponent(fileName), encoding: .utf8)
        let root = try JsonValue.parse(text) as! JsonObject
        return (root["vectors"] as! JsonArray).asList.map {
            let v = $0 as! JsonObject
            return ((v["name"] as! JsonString).value, (v["expectedHex"] as! JsonString).value)
        }
    }

    private func bytes(_ hex: String) -> [UInt8] {
        let clean = Array(hex.filter { !$0.isWhitespace })
        return stride(from: 0, to: clean.count, by: 2).map {
            UInt8(String(clean[$0 ... $0 + 1]), radix: 16)!
        }
    }


    func testThisBackEndWritesTheDocumentsCSharpWrites() throws {

        var checked = 0

        for bridge in bridges {

            let expected = documents[bridge.name] as! JsonObject

            for (name, hex) in try vectors(bridge.vectorFile) {

                let produced = try bridge.toJSON(try bridge.decodeAny(bytes(hex)))

                XCTAssertEqual(produced.jsonString, expected[name]!.jsonString, "\(bridge.name)/\(name)")
                checked += 1
            }
        }

        XCTAssertGreaterThanOrEqual(checked, 130, "only \(checked) documents were compared")
    }


    /// The other direction: C#'s documents, read by this parser, encode to the original bytes.
    ///
    /// A serializer and a parser can fail apart. One that agreed on output while accepting something
    /// looser than C# does would pass the test above and still break the moment a Pi sent a document
    /// this app read differently.
    func testThisBackEndReadsTheDocumentsCSharpWrites() throws {

        for bridge in bridges {

            let expected = documents[bridge.name] as! JsonObject

            for (name, hex) in try vectors(bridge.vectorFile) {

                let message = try bridge.parseJSON(expected[name]!)

                XCTAssertEqual(try bridge.encodeAny(message), bytes(hex),
                               "\(bridge.name)/\(name): the bytes changed on the way through JSON")
            }
        }
    }


    /// Every set's `@context` is its XSD target namespace, and every document carries it.
    func testEveryDocumentCarriesItsVocabulary() throws {

        for bridge in bridges {

            let context = (documents[bridge.name] as! JsonObject)["@context"]!

            for (name, hex) in try vectors(bridge.vectorFile) {
                let produced = try bridge.toJSON(try bridge.decodeAny(bytes(hex)))
                XCTAssertEqual(produced["@context"]?.jsonString, context.jsonString,
                               "\(bridge.name)/\(name)")
            }
        }
    }
}
