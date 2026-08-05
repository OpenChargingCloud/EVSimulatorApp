import XCTest
@testable import V2GPairing

/// This port against the corpus the C# side generates.
///
/// The corpus is the *only* thing tying the two together: the Pi renders the code and the phone reads
/// it, in different languages, in different processes, on different machines, and neither can observe
/// the other. Two readings of a written format drift silently — and a pairing format that drifts does
/// not break loudly, it accepts something it should have refused.
///
/// The refusal cases are the substance here. That a well-formed code parses is table stakes; that a
/// code with its parameters in the *query* is rejected outright, in every back end, is the property
/// worth pinning.
final class PairingCorpusTests: XCTestCase {

    private struct Case: Decodable {
        let name: String
        let input: String
        let outcome: String
        let error: String?
        let payload: Expected?
        let warnings: [Warning]

        struct Warning: Decodable { let kind: String; let blocking: Bool }

        struct Expected: Decodable {
            let version: Int
            let host: String
            let port: Int
            let transport: String
            let `protocols`: [String]
            let crypto: String?
            let nonConformant: Bool
            let nonConformanceReason: String?
            let rootFingerprint: String?
            let meter: String?
            let totp: String?
            let evseId: String?
            let tariffId: String?
            let currency: String?
            let uiLanguage: String?
            let wifiSsid: String?
            let wifiPsk: String?
            let extra: [String: String]
        }
    }

    private struct Corpus: Decodable { let cases: [Case] }

    private lazy var cases: [Case] = {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("EVSimulatorApp.slnx").path) {
            let parent = directory.deletingLastPathComponent()
            precondition(parent != directory, "repository root not found")
            directory = parent
        }

        let file = directory.appendingPathComponent(
            "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.payload.vectors.json")
        let data = try! Data(contentsOf: file)
        return try! JSONDecoder().decode(Corpus.self, from: data).cases
    }()


    func testEveryCaseMatchesThisPort() throws {

        XCTAssertGreaterThanOrEqual(cases.count, 20, "the corpus looks truncated")

        for testCase in cases {

            let name = testCase.name
            var payload: PairingPayload?

            do {
                payload = try PairingUri.parse(testCase.input)
                XCTAssertEqual(payload == nil ? "notAPairingCode" : "parsed", testCase.outcome, name)
            } catch let error as PairingFormatError {
                XCTAssertEqual(testCase.outcome, "malformed", "\(name): \(error)")
                // The message too, not just the fact of refusal: it is what the user is shown, and a
                // refusal nobody can act on is only marginally better than none.
                XCTAssertEqual(error.description, testCase.error, "\(name): refusal message")
                continue
            }

            guard let payload, let expected = testCase.payload else { continue }

            XCTAssertEqual(payload.version, expected.version, "\(name): version")
            XCTAssertEqual(payload.host, expected.host, "\(name): host")
            XCTAssertEqual(payload.port, expected.port, "\(name): port")
            XCTAssertEqual(payload.transport.rawValue, expected.transport, "\(name): transport")
            XCTAssertEqual(payload.protocols, expected.protocols, "\(name): protocols")
            XCTAssertEqual(payload.crypto, expected.crypto, "\(name): crypto")
            XCTAssertEqual(payload.nonConformant, expected.nonConformant, "\(name): nonConformant")
            XCTAssertEqual(payload.nonConformanceReason, expected.nonConformanceReason, "\(name): ncwhy")
            XCTAssertEqual(payload.rootFingerprint, expected.rootFingerprint, "\(name): root")
            XCTAssertEqual(payload.meter, expected.meter, "\(name): meter")
            XCTAssertEqual(payload.totp, expected.totp, "\(name): totp")
            XCTAssertEqual(payload.evseId, expected.evseId, "\(name): evseId")
            XCTAssertEqual(payload.tariffId, expected.tariffId, "\(name): tariffId")
            XCTAssertEqual(payload.currency, expected.currency, "\(name): currency")
            XCTAssertEqual(payload.uiLanguage, expected.uiLanguage, "\(name): uiLanguage")
            XCTAssertEqual(payload.wifiSsid, expected.wifiSsid, "\(name): wifi ssid")
            XCTAssertEqual(payload.wifiPsk, expected.wifiPsk, "\(name): wifi psk")
            XCTAssertEqual(payload.extra, expected.extra, "\(name): extra")

            XCTAssertEqual(payload.warnings.map(\.kind.rawValue), testCase.warnings.map(\.kind),
                           "\(name): warnings (order included — it is the order a sheet lists them in)")
            XCTAssertEqual(payload.warnings.map(\.isBlocking), testCase.warnings.map(\.blocking),
                           "\(name): which warnings block")
        }
    }

    /// The corpus is only worth running if it still contains the cases it was built for. A corpus that
    /// quietly lost its refusals would go on passing forever — the failure mode of every
    /// generated-fixture scheme, and cheap to rule out.
    func testTheCorpusStillCoversTheRefusals() {

        let byName = Dictionary(uniqueKeysWithValues: cases.map { ($0.name, $0) })

        for required in ["query-instead-of-fragment", "repeated-parameter",
                         "hostname-is-not-resolved", "public-target"] {
            XCTAssertNotNil(byName[required], "the corpus no longer covers '\(required)'")
        }

        XCTAssertEqual(byName["query-instead-of-fragment"]?.outcome, "malformed")
        XCTAssertEqual(byName["repeated-parameter"]?.outcome, "malformed")
    }

    /// Judging a host is a decision about text.
    ///
    /// The Kotlin port asserts this mechanically, with a resolver installed that fails the test if
    /// anything asks it a question — the JVM needs that, because `InetAddress.getByName` resolves and
    /// looks like the obvious call. Foundation has no equivalent hook, so what is asserted here is the
    /// observable consequence: `localhost` is judged **public**. It is a name, and whether it happens
    /// to map to 127.0.0.1 on this device is not knowable without asking. Any implementation that
    /// resolved would get this one wrong in the reassuring direction.
    func testJudgingAHostResolvesNothing() {

        XCTAssertFalse(PairingWarnings.isPrivateTarget("localhost"))
        XCTAssertFalse(PairingWarnings.isPrivateTarget("charger.example.com"))
        XCTAssertTrue(PairingWarnings.isPrivateTarget("pi.local"))

        for isPrivate in ["127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.4.1", "169.254.1.1",
                          "::1", "fe80::1%wlan0", "fd00::1"] {
            XCTAssertTrue(PairingWarnings.isPrivateTarget(isPrivate), isPrivate)
        }
        for isPublic in ["8.8.8.8", "172.32.0.1", "1.1.1.1", "2001:db8::1", "not a host at all"] {
            XCTAssertFalse(PairingWarnings.isPrivateTarget(isPublic), isPublic)
        }
    }
}
