import XCTest
@testable import V2GPairing

/// The TOTP port against the C#-generated corpus.
///
/// This is the half where a silent divergence is worst. A pairing payload that drifts fails visibly —
/// a field is missing, a warning is absent, something on screen looks wrong. A TOTP that drifts fails
/// as **"pairing does not work"**, with no way to see why from either end: every code is rejected, and
/// both sides are certain they are right. A hash either agrees exactly or it agrees not at all.
final class PairingTotpTests: XCTestCase {

    private struct Corpus: Decodable {
        let secret: String
        let alphabet: String
        let slots: [Slot]
        let verifier: [Step]

        struct Slot: Decodable {
            let name: String
            let at: Int64
            let validitySeconds: Int64
            let length: Int
            let previous: String
            let current: String
            let next: String
            let remainingSeconds: Int64
        }

        struct Step: Decodable {
            let what: String
            let atUnixSeconds: Int64
            let presented: String
            let expected: String
            let spentAfter: Int
        }
    }

    private lazy var corpus: Corpus = {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("EVSimulatorApp.slnx").path) {
            let parent = directory.deletingLastPathComponent()
            precondition(parent != directory, "repository root not found")
            directory = parent
        }

        let file = directory.appendingPathComponent(
            "pairing/EVSimulatorApp.Pairing.Tests/Vectors/Pairing.totp.vectors.json")
        return try! JSONDecoder().decode(Corpus.self, from: try! Data(contentsOf: file))
    }()


    func testEverySlotVectorMatchesThisPort() {

        XCTAssertEqual(String(PairingTotpGenerator.defaultAlphabet), corpus.alphabet,
                       "the alphabet is part of the algorithm, not a preference")
        XCTAssertGreaterThanOrEqual(corpus.slots.count, 9, "the corpus looks truncated")

        for vector in corpus.slots {

            let produced = PairingTotpGenerator.slots(
                sharedSecret: corpus.secret,
                at: Date(timeIntervalSince1970: TimeInterval(vector.at)),
                validitySeconds: vector.validitySeconds,
                length: vector.length)

            XCTAssertEqual(produced.previous, vector.previous, "\(vector.name): previous slot")
            XCTAssertEqual(produced.current,  vector.current,  "\(vector.name): current slot")
            XCTAssertEqual(produced.next,     vector.next,     "\(vector.name): next slot")
            XCTAssertEqual(produced.remainingSeconds, vector.remainingSeconds,
                           "\(vector.name): remaining seconds")
        }
    }

    /// The verifier script, replayed. Stateful — accepting a code changes what happens next — so it
    /// runs in order and shares one verifier. Replay is only visible as a *sequence*, which is why
    /// this is a script rather than a set of cases.
    func testTheVerifierScriptStillHolds() {

        final class Clock: @unchecked Sendable { var now = Date(timeIntervalSince1970: 1_700_000_025) }
        let clock = Clock()
        let verifier = PairingTotpVerifier(sharedSecret: corpus.secret, validitySeconds: 30,
                                           clock: { clock.now })

        for step in corpus.verifier {

            clock.now = Date(timeIntervalSince1970: TimeInterval(step.atUnixSeconds))

            XCTAssertEqual(verifier.verify(step.presented).rawValue, step.expected, step.what)
            XCTAssertEqual(verifier.spentCount, step.spentAfter, "\(step.what) (spent count)")
        }
    }

    /// The script is only worth running if it still contains a replay and the window edges. A corpus
    /// that lost them would go on passing forever.
    func testTheScriptStillCoversReplayAndTheWindowEdges() {

        let outcomes = corpus.verifier.map(\.expected)

        XCTAssertTrue(outcomes.contains("Replayed"),
                      "a script without a replay proves nothing about the one-shot rule")
        XCTAssertTrue(outcomes.contains("Unknown"))
        XCTAssertTrue(outcomes.contains("Malformed"))
        XCTAssertEqual(outcomes.filter { $0 == "Accepted" }.count, 3,
                       "previous, current and next — the ±1 window, no wider")
    }

    /// The slot number is derived from UTC alone.
    ///
    /// `Date` is an absolute instant, so this is nearly free in Swift — but "nearly" is why it is
    /// asserted: a port that reached for `Calendar` or `DateFormatter` on the way to a slot number
    /// would work perfectly on the developer's machine and fail for every user in another timezone.
    func testTheSlotNumberIsDerivedFromUtcAlone() {

        let at = Date(timeIntervalSince1970: 1_700_000_025)
        let code = PairingTotpGenerator.current(sharedSecret: corpus.secret, at: at)

        // Same instant, expressed via a component-based construction in a distant timezone.
        var components = DateComponents()
        components.timeZone = TimeZone(identifier: "Pacific/Kiritimati")
        components.year = 2023; components.month = 11; components.day = 15
        components.hour = 12;   components.minute = 13; components.second = 45   // UTC+14

        let sameInstant = Calendar(identifier: .gregorian).date(from: components)!
        XCTAssertEqual(sameInstant.timeIntervalSince1970, at.timeIntervalSince1970, accuracy: 0.5,
                       "the test's own arithmetic is wrong if this fails")
        XCTAssertEqual(PairingTotpGenerator.current(sharedSecret: corpus.secret, at: sameInstant), code)
    }

    /// An instant before 1970 must land in the slot below zero, not above it. Truncating a negative
    /// `Double` rounds towards zero, which silently merges the first slot on each side of the epoch —
    /// a bug no realistic clock reaches, and one that a `-1` in an offset calculation would.
    func testSlotArithmeticIsCorrectBelowTheEpoch() {

        let justBefore = PairingTotpGenerator.current(
            sharedSecret: corpus.secret, at: Date(timeIntervalSince1970: -1))
        let justAfter = PairingTotpGenerator.current(
            sharedSecret: corpus.secret, at: Date(timeIntervalSince1970: 1))

        XCTAssertNotEqual(justBefore, justAfter, "the slot below the epoch collapsed into the one above")
    }
}
