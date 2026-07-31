import CryptoKit
import XCTest
@testable import V2GKeystore

/// The key store, and the asymmetry `docs/CONCEPT.md` §3.4 asks to be designed around rather than
/// discovered.
///
/// > iOS Secure Enclave: P-256 only. Android StrongBox/TEE: P-256, RSA. So -2 PnC keys can be
/// > enclave-backed and -20 keys cannot… Be explicit about it in the UI — a simulator that quietly
/// > pretends its keys are hardware-protected is worse than one that says they aren't.
///
/// Everything below exists to make "quietly pretends" impossible: the protection level is a value
/// rather than an implementation detail, so it can be displayed, asserted, and got wrong loudly.
final class V2GKeystoreTests: XCTestCase {

    private func softwareKey(_ id: String = "k1", label: String = "Contract") throws -> V2GStoredKey {
        V2GStoredKey(id: id, label: label, signer: try InMemoryP256Signer(P256.Signing.PrivateKey()))
    }

    // ── the asymmetry itself ──────────────────────────────────────────────

    /// The table from §3.4, as a value. P-256 can go in a secure element; the -20 curves cannot, on
    /// either platform, for reasons no application code can work around.
    func testOnlyP256CanBeHardwareBacked() {
        XCTAssertTrue(V2GKeyCurve.p256.canBeHardwareBacked)
        XCTAssertFalse(V2GKeyCurve.p521.canBeHardwareBacked)
        XCTAssertFalse(V2GKeyCurve.ed448.canBeHardwareBacked)
    }

    /// A -20 curve is refused hardware **even on a device that has a secure element**, and says why
    /// in terms a user can act on. The alternative — silently storing it in software — is the exact
    /// failure §3.4 names.
    func testAMinus20CurveIsRefusedHardwareEvenWhereHardwareExists() {

        let store = InMemoryKeystore(hardwareAvailableOnThisDevice: true)

        for curve in [V2GKeyCurve.p521, .ed448] {
            let availability = store.availability(for: curve)
            XCTAssertFalse(availability.hardwareAvailable, "\(curve.rawValue) must not claim hardware")
            XCTAssertNotNil(availability.reason, "a refusal without a reason is a disabled control")
            XCTAssertTrue(availability.reason!.contains("P-256"),
                          "the reason should name the actual constraint: \(availability.reason!)")
        }

        XCTAssertTrue(store.availability(for: .p256).hardwareAvailable)
        XCTAssertNil(store.availability(for: .p256).reason)
    }

    /// The other half: a device without a secure element refuses P-256 hardware too, and for a
    /// different reason. Two causes that look identical from a call site must not read identically to
    /// a user — one is about the curve forever, the other about this phone today.
    func testTheTwoReasonsForRefusingHardwareAreDistinguishable() {

        let noHardware = InMemoryKeystore(hardwareAvailableOnThisDevice: false)
        let hardware   = InMemoryKeystore(hardwareAvailableOnThisDevice: true)

        let p256OnPlainDevice = noHardware.availability(for: .p256)
        let p521OnGoodDevice  = hardware.availability(for: .p521)

        XCTAssertFalse(p256OnPlainDevice.hardwareAvailable)
        XCTAssertFalse(p521OnGoodDevice.hardwareAvailable)
        XCTAssertNotEqual(p256OnPlainDevice.reason, p521OnGoodDevice.reason)
        XCTAssertTrue(p256OnPlainDevice.reason!.contains("device"))
    }

    /// A software signer cannot describe itself as hardware-backed — and the reason given is *that it
    /// holds bytes*, not the curve. P-256 can live in an enclave; it simply cannot live in an object
    /// this code was handed one, and reporting the curve would send someone looking in the wrong place.
    func testASoftwareSignerCannotClaimHardwareBacking() {

        XCTAssertThrowsError(try InMemoryP256Signer(P256.Signing.PrivateKey(),
                                                    protection: .hardware(element: "Secure Enclave"))) {
            XCTAssertEqual($0 as? V2GKeyError, .softwareSignerCannotClaimHardware)
        }
    }

    // ── disclosure ────────────────────────────────────────────────────────

    /// The sentence shown next to a key must never be more reassuring than the truth. Asserted
    /// literally, because this is the one string the whole §3.4 note is about.
    func testSoftwareKeysNeverDescribeThemselvesAsProtectedByHardware() throws {

        for protection in [V2GKeyProtection.softwareInSecureStorage, .softwareInMemory] {
            let text = protection.disclosure.lowercased()
            XCTAssertFalse(text.contains("secure enclave"))
            XCTAssertFalse(text.contains("cannot be read"))
            XCTAssertFalse(protection.isHardwareBacked)
        }

        let hardware = V2GKeyProtection.hardware(element: "Secure Enclave")
        XCTAssertTrue(hardware.disclosure.contains("cannot be read"))
        XCTAssertTrue(hardware.isHardwareBacked)
    }

    /// Gating on **use** is a separate promise from gating on storage, and §3.4 asks for the former.
    /// It reads as an extra sentence rather than replacing the storage disclosure, because a key can
    /// be biometrically gated and still be software.
    func testAuthenticationOnUseIsDisclosedSeparatelyFromStorage() throws {

        let plain = try softwareKey()

        // Secure storage rather than memory: gating use implies the key persists, so an in-memory
        // gated key is a combination that does not occur. An earlier version of this test asserted
        // it anyway and failed on wording — the test was wrong, not the disclosure.
        let gated = V2GStoredKey(
            id: "k2", label: "Contract",
            signer: try InMemoryP256Signer(P256.Signing.PrivateKey(),
                                           protection: .softwareInSecureStorage),
            requiresUserAuthenticationToUse: true)

        XCTAssertFalse(plain.disclosure.contains("authenticate"))
        XCTAssertTrue(gated.disclosure.contains("authenticate"))
        XCTAssertTrue(gated.disclosure.contains("software"),
                      "a gated key is still a software key and must still say so")
    }

    // ── the store ─────────────────────────────────────────────────────────

    func testSoftwareOnlyKeysIsTheListAWalletScreenNeeds() throws {

        var store = InMemoryKeystore()
        store.add(try softwareKey("a"))
        store.add(try softwareKey("b"))

        XCTAssertEqual(store.softwareOnlyKeys.count, 2,
                       "every key here is software, and a wallet should be able to say so in one call")
    }

    func testAddingByTheSameIdReplacesRatherThanDuplicates() throws {

        var store = InMemoryKeystore()
        store.add(try softwareKey("a", label: "first"))
        store.add(try softwareKey("a", label: "second"))

        XCTAssertEqual(store.keys.count, 1)
        XCTAssertEqual(store.key(id: "a")?.label, "second")

        store.remove(id: "a")
        XCTAssertTrue(store.keys.isEmpty)
    }

    /// Selecting by curve is the §3.4 split as a query: a -2 session wants P-256 and may get a
    /// hardware-backed one, a -20 session wants P-521 or Ed448 and cannot.
    func testKeysCanBeSelectedByCurve() throws {

        var store = InMemoryKeystore()
        store.add(try softwareKey("a"))

        XCTAssertEqual(store.keys(forCurve: .p256).count, 1)
        XCTAssertTrue(store.keys(forCurve: .p521).isEmpty)
    }

    /// A signer signs, and that is all the EVCC needs of it — which is the point of the shape. The
    /// signature is raw `r‖s`, 64 bytes for P-256, never DER.
    func testASignerProducesRawRsWithoutRevealingTheKey() throws {

        let signer = try InMemoryP256Signer(P256.Signing.PrivateKey())
        let signature = try signer.signature(over: Array("hello".utf8))

        XCTAssertEqual(signature.count, 64, "P-256 raw r‖s; a DER signature would be longer and vary")
        XCTAssertFalse(signer.publicKeyDer.isEmpty)
    }
}
