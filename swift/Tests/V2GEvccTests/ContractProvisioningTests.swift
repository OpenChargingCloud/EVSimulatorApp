import CryptoKit
import Foundation
import XCTest

@testable import V2GEvcc

import ExiIso2
import ExiIso20Common
import V2GCertificates

/// Contract provisioning on both protocols, against the corpus C# generates and is itself held to.
///
/// ## What this test is really checking
///
/// Two things, and the second is the one no other test in this package can reach.
///
/// The first is the familiar verdict: does this port judge a provisioning response the way C# does —
/// four references on -2, one on -20, digests, signature, grammar. The verdict never travels, so no
/// recorded session can pin it.
///
/// The second is the **unwrapped scalar**. What the car ends up holding after provisioning is a
/// private key it never saw transmitted: the station ran an ECDH, a KDF, and a cipher, and the car
/// has to arrive at the same 32 (or 66) bytes independently. Nothing is echoed, acknowledged, or
/// checked by the peer. `recoveredKeyD` in the corpus is the only place in this repository where that
/// property can be stated at all, and `testTheUnwrappedKeyIsTheOneCSharpUnwrapped` is where this port
/// is held to it. A KDF that put the counter on the wrong side of Z passes every other test here.
final class ContractProvisioningTests: XCTestCase {

    private struct Case {
        let name: String
        let frame: [UInt8]
        let receiverKeyD: [UInt8]
        let expected: [String: Any]
    }

    private static func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map { i in
            let start = s.index(s.startIndex, offsetBy: i)
            return UInt8(s[start...s.index(start, offsetBy: 1)], radix: 16)!
        }
    }

    private static func hexString(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func corpus(_ set: String) throws -> [Case] {

        var dir = URL(fileURLWithPath: #filePath)
        for _ in 0..<12 {
            dir.deleteLastPathComponent()
            if FileManager.default.fileExists(atPath:
                dir.appendingPathComponent("EVSimulatorApp.slnx").path) { break }
        }

        let url = dir.appendingPathComponent("vectors/Contract.provisioning.vectors.json")
        guard let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let raw = json[set] as? [[String: Any]]
        else { throw XCTSkip("contract-provisioning corpus not found at \(url.path)") }

        return raw.map { c in
            Case(name:         c["name"] as! String,
                 frame:        hex(c["frame"] as! String),
                 receiverKeyD: hex(c["receiverKeyD"] as! String),
                 expected:     c["expected"] as! [String: Any])
        }
    }


    // ── ISO 15118-2 ─────────────────────────────────────────────────────────────────────────────

    func testEveryIso2CaseReachesTheVerdictCSharpReached() throws {

        for c in try Self.corpus("iso2") {

            let decoded = try Iso15118_2Codec.decodeAny(c.frame)
            guard let message = decoded as? V2G_Message
            else { return XCTFail("\(c.name): not a V2G_Message") }

            let verdict = Iso2ContractCheck.evaluate(message.body.bodyElement,
                                                     headerSignature: message.header.signature)

            XCTAssertEqual(verdict.signaturePresent, c.expected["signaturePresent"] as! Bool, "\(c.name): signaturePresent")
            XCTAssertEqual(verdict.references,       c.expected["references"] as! Int,        "\(c.name): references")
            XCTAssertEqual(verdict.digestOk,         c.expected["digestOk"] as! Bool,         "\(c.name): digestOk")
            XCTAssertEqual(verdict.signatureOk,      c.expected["signatureOk"] as! Bool,      "\(c.name): signatureOk")
            XCTAssertEqual(verdict.signatureGrammar, c.expected["signatureGrammar"] as! String, "\(c.name): signatureGrammar")

            let payload = try XCTUnwrap(Iso2ContractCheck.unpack(message.body.bodyElement), c.name)
            XCTAssertEqual(payload.emaid.value, c.expected["emaid"] as! String, "\(c.name): emaid")
        }
    }

    /// The load-bearing one. Every -2 case names the scalar C# unwrapped from these exact bytes with
    /// this exact key, and this port must produce it — including for `install-wrong-receiver`, where
    /// the right answer is 32 bytes of nonsense rather than an error.
    func testTheUnwrappedKeyIsTheOneCSharpUnwrapped() throws {

        for c in try Self.corpus("iso2") {

            let message = try Iso15118_2Codec.decodeAny(c.frame) as! V2G_Message
            let payload = try XCTUnwrap(Iso2ContractCheck.unpack(message.body.bodyElement), c.name)

            let receiver  = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(c.receiverKeyD))
            let recovered = try ContractProvisioning2.recoverContractKey(
                receiver: receiver,
                dhPublicKey: payload.dhPublicKey.value,
                encryptedPrivateKey: payload.encryptedKey.value)

            XCTAssertEqual(Self.hexString(Array(recovered.rawRepresentation)),
                           c.expected["recoveredKeyD"] as! String,
                           "\(c.name): the unwrapped scalar")

            let issued = try V2GCertificate(der: payload.contractChain.certificate)
            let issuedKey = try XCTUnwrap(issued.p256VerificationKey, c.name)

            XCTAssertEqual(ContractProvisioning2.matches(recovered, issuedKey),
                           c.expected["keyMatchesCertificate"] as! Bool,
                           "\(c.name): keyMatchesCertificate")
        }
    }

    /// CBC authenticates nothing, so the wrong key does not fail — it hands over a usable private key
    /// belonging to nobody. The only thing between that and an installed contract is the certificate
    /// check, and this states it on its own rather than as one assertion among many.
    func testAWrongReceiverUnwrapsSuccessfullyAndIsCaughtByTheCertificate() throws {

        guard let c = try Self.corpus("iso2").first(where: { $0.name == "install-wrong-receiver" })
        else { return XCTFail("the install-wrong-receiver case is gone") }

        let message = try Iso15118_2Codec.decodeAny(c.frame) as! V2G_Message
        let payload = try XCTUnwrap(Iso2ContractCheck.unpack(message.body.bodyElement))
        let verdict = Iso2ContractCheck.evaluate(message.body.bodyElement,
                                                 headerSignature: message.header.signature)

        // Everything the signature can say about this response is "fine".
        XCTAssertTrue(verdict.digestOk)
        XCTAssertTrue(verdict.signatureOk)

        let receiver = try P256.KeyAgreement.PrivateKey(rawRepresentation: Data(c.receiverKeyD))
        let recovered = try ContractProvisioning2.recoverContractKey(
            receiver: receiver,
            dhPublicKey: payload.dhPublicKey.value,
            encryptedPrivateKey: payload.encryptedKey.value)

        let issued = try V2GCertificate(der: payload.contractChain.certificate)
        XCTAssertFalse(ContractProvisioning2.matches(recovered, try XCTUnwrap(issued.p256VerificationKey)),
                       "an unwrap with the wrong key must not pass for the real one")
    }

    func testAnIso2FieldOfTheWrongWidthIsRefusedBeforeAnythingIsDecrypted() throws {

        let receiver = P256.KeyAgreement.PrivateKey()

        XCTAssertThrowsError(try ContractProvisioning2.recoverContractKey(
            receiver: receiver,
            dhPublicKey: [UInt8](repeating: 0, count: 64),
            encryptedPrivateKey: [UInt8](repeating: 0, count: 48)))

        var point = [UInt8](repeating: 0, count: 65)
        point[0] = 0x04
        XCTAssertThrowsError(try ContractProvisioning2.recoverContractKey(
            receiver: receiver,
            dhPublicKey: point,
            encryptedPrivateKey: [UInt8](repeating: 0, count: 47)))
    }


    // ── ISO 15118-20 ────────────────────────────────────────────────────────────────────────────

    func testEveryIso20CaseReachesTheVerdictCSharpReached() throws {

        for c in try Self.corpus("iso20") {

            let decoded = try CommonMessagesCodec.decodeAny(c.frame)
            guard let res = decoded as? CertificateInstallationRes
            else { return XCTFail("\(c.name): not a CertificateInstallationRes") }

            let verdict = Iso20ContractCheck.evaluate(res, headerSignature: res.header.signature)

            XCTAssertEqual(verdict.signaturePresent, c.expected["signaturePresent"] as! Bool, "\(c.name): signaturePresent")
            XCTAssertEqual(verdict.references,       c.expected["references"] as! Int,        "\(c.name): references")
            XCTAssertEqual(verdict.digestOk,         c.expected["digestOk"] as! Bool,         "\(c.name): digestOk")
            XCTAssertEqual(verdict.signatureOk,      c.expected["signatureOk"] as! Bool,      "\(c.name): signatureOk")
        }
    }

    /// The -20 half of the load-bearing check — and the one place the two protocols differ in outcome
    /// rather than in bytes: a wrong receiver here is refused by GCM's tag, where -2's CBC handed over
    /// nonsense.
    func testTheUnwrappedIso20KeyIsTheOneCSharpUnwrapped() throws {

        for c in try Self.corpus("iso20") {

            let res = try CommonMessagesCodec.decodeAny(c.frame) as! CertificateInstallationRes
            let data = res.signedInstallationData
            let oemKey = try P521.KeyAgreement.PrivateKey(rawRepresentation: Data(c.receiverKeyD))

            let recovered = try? ContractProvisioning20.recoverContractKey(
                oemKey: oemKey,
                dhPublicKey: data.dHPublicKey,
                encryptedPrivateKey: try XCTUnwrap(data.sECP521_EncryptedPrivateKey, c.name))

            XCTAssertEqual(recovered != nil, c.expected["keyRecovered"] as! Bool, "\(c.name): keyRecovered")

            guard let recovered else { continue }
            XCTAssertEqual(Self.hexString(Array(recovered.rawRepresentation)),
                           c.expected["recoveredKeyD"] as! String,
                           "\(c.name): the unwrapped scalar")
        }
    }

    func testAWrongIso20ReceiverIsRefusedByTheTagRatherThanYieldingNonsense() throws {

        guard let c = try Self.corpus("iso20").first(where: { $0.name == "install-wrong-receiver" })
        else { return XCTFail("the install-wrong-receiver case is gone") }

        let res = try CommonMessagesCodec.decodeAny(c.frame) as! CertificateInstallationRes
        let oemKey = try P521.KeyAgreement.PrivateKey(rawRepresentation: Data(c.receiverKeyD))

        XCTAssertThrowsError(try ContractProvisioning20.recoverContractKey(
            oemKey: oemKey,
            dhPublicKey: res.signedInstallationData.dHPublicKey,
            encryptedPrivateKey: try XCTUnwrap(res.signedInstallationData.sECP521_EncryptedPrivateKey))) {
            XCTAssertEqual($0 as? ContractProvisioningError, .authenticationFailed)
        }
    }

    func testAnIso20FieldOfTheWrongWidthIsRefusedBeforeAnythingIsDecrypted() throws {

        let oemKey = P521.KeyAgreement.PrivateKey()

        XCTAssertThrowsError(try ContractProvisioning20.recoverContractKey(
            oemKey: oemKey,
            dhPublicKey: [UInt8](repeating: 0, count: 132),
            encryptedPrivateKey: [UInt8](repeating: 0, count: 94)))

        var point = [UInt8](repeating: 0, count: 133)
        point[0] = 0x04
        XCTAssertThrowsError(try ContractProvisioning20.recoverContractKey(
            oemKey: oemKey,
            dhPublicKey: point,
            encryptedPrivateKey: [UInt8](repeating: 0, count: 93)))
    }


    func testTheCorpusStillCarriesItsNegatives() throws {

        let iso2 = Set(try Self.corpus("iso2").map(\.name))
        for required in ["install-signed", "install-standalone", "install-unsigned",
                         "install-digest-tampered", "install-three-references",
                         "install-wrong-key", "install-wrong-receiver", "update-signed"] {
            XCTAssertTrue(iso2.contains(required), "the -2 \(required) case is gone")
        }

        let iso20 = Set(try Self.corpus("iso20").map(\.name))
        for required in ["install-signed", "install-unsigned", "install-digest-tampered",
                         "install-wrong-uri", "install-wrong-key", "install-wrong-receiver"] {
            XCTAssertTrue(iso20.contains(required), "the -20 \(required) case is gone")
        }
    }
}
