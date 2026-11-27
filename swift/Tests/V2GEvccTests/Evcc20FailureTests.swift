import CryptoKit
import Foundation
import XCTest

import ExiIso20Common
import ExiIso20DC
import V2GDispatch

@testable import V2GEvcc

/// What this EVCC does when the station answers with a `FAILED` code.
///
/// **Found live, not by reasoning, and not by this suite.** Until 2026-08-01 no -20 EVCC in this
/// repository read a response code at all, in any of the three languages. eVDriveFlow answered
/// `DC_CableCheckRes` with `FAILED` and the car went on charging
/// (`../../docs/interop-runs/2026-08-01-edf-iso20-dc-notls/`).
///
/// The trace corpus — this port's entire oracle — could not have shown it: every recorded response is
/// one our own SECC produced, and our own SECC never says FAILED. The corpus is silent here by
/// construction, and this file is the station it does not contain.


final class Evcc20FailureTests: XCTestCase {

    private func evcc() -> Evcc20Dc {
        // The transport is never touched: refuseOnFailure inspects a decoded message and nothing else.
        Evcc20Dc(V2GTPStream(SilentTransport()), clock: { 1_767_225_600 })
    }

    private func cableCheck(_ code: ExiIso20DC.ResponseCode) -> DC_CableCheckRes {
        DC_CableCheckRes(header: ExiIso20DC.MessageHeaderType(sessionID: [UInt8](repeating: 0, count: 8),
                                                              timeStamp: 1_767_225_600,
                                                              signature: nil),
                         responseCode: code,
                         eVSEProcessing: .Finished)
    }


    /// The ordering the `rawValue >= FAILED` comparison rests on.
    ///
    /// The check is a range test, sound only while the schema keeps its three families contiguous and
    /// in order. A regenerated enum that interleaved them would quietly turn failures into successes —
    /// the very shape of bug this file exists because of.
    func testResponseCodeFamiliesAreContiguousAndOrdered() {
        for code in ExiIso20Common.ResponseCode.allCases {
            let name = String(describing: code)
            if name.hasPrefix("FAILED") {
                XCTAssertGreaterThanOrEqual(code.rawValue, ExiIso20Common.ResponseCode.FAILED.rawValue,
                                            "\(name) sorts below FAILED")
            } else {
                XCTAssertLessThan(code.rawValue, ExiIso20Common.ResponseCode.FAILED.rawValue,
                                  "\(name) is not a failure but sorts at or above FAILED")
            }
        }

        // The DC message set carries its own copy, generated separately from the same schema.
        XCTAssertEqual(ExiIso20DC.ResponseCode.FAILED.rawValue,
                       ExiIso20Common.ResponseCode.FAILED.rawValue)
    }


    func testAFailedResponseEndsTheSession() {
        XCTAssertThrowsError(try evcc().refuseOnFailure(cableCheck(.FAILED))) { error in
            let message = (error as? SessionAborted)?.description ?? "\(error)"
            // Both halves have to be in the message: which message failed, and with what.
            XCTAssertTrue(message.contains("DC_CableCheckRes"), message)
            XCTAssertTrue(message.contains("FAILED"), message)
        }
    }


    /// A WARNING is not a failure. The specification has three families because `WARNING*` means
    /// "something is off and the session continues"; aborting on it would turn an expiring certificate
    /// into a refused charge.
    func testAWarningDoesNotEndTheSession() throws {
        try evcc().refuseOnFailure(cableCheck(.WARNING_CertificateExpired))
    }


    // ── AuthorizationRes, where a WARNING does end it ───────────────────────────────────────────

    private func header() -> ExiIso20Common.MessageHeaderType {
        ExiIso20Common.MessageHeaderType(sessionID: [UInt8](repeating: 0x22, count: 8), timeStamp: 1_767_225_600)
    }

    /// A Plug & Charge station up to its verdict on the contract, which is `verdict`.
    private func pncStation(_ verdict: ExiIso20Common.ResponseCode) -> ScriptedStation {
        ScriptedStation([
            ScriptedStation.sapOk(),
            ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
                SessionSetupRes(header: header(), responseCode: .OK_NewSessionEstablished, eVSEID: "DE*ABC*E1"))),
            ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
                AuthorizationSetupRes(header: header(), responseCode: .OK,
                                      authorizationServices: [.PnC],
                                      certificateInstallationService: false,
                                      pnC_ASResAuthorizationMode: PnC_ASResAuthorizationModeType(
                                          genChallenge: [UInt8](repeating: 7, count: 16))))),
            ScriptedStation.framed(.iso20CommonMessages, CommonMessagesCodec.encode(
                AuthorizationRes(header: header(), responseCode: verdict, eVSEProcessing: .Finished))),
        ])
    }

    /// EVerest 2026.10.0, 2026-11-27: `WARNING_CertificateValidationError` and `Finished` for a P-256
    /// contract. Our car went on to ServiceDiscovery and was refused with `FAILED_SequenceError`.
    /// [V2G20-1583] gives four options after such a warning, and that is not one of them.
    func testAWarningAtAuthorizationEndsTheSession() throws {

        let station = pncStation(.WARNING_CertificateValidationError)
        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)

        let evcc = Evcc20Dc(station.stream, clock: { 1_767_225_600 })
        evcc.pnc = try PncMaterial.options
        XCTAssertThrowsError(try evcc.run()) { error in
            let text = String(describing: error)
            XCTAssertTrue(text.contains("AuthorizationRes"), text)
            XCTAssertTrue(text.contains("WARNING_CertificateValidationError"), text)
        }

        let requests = try station.sessionRequestPayloads().map { try CommonMessagesCodec.decodeAny($0) }
        XCTAssertFalse(requests.contains { $0 is ServiceDiscoveryReq }, "the car asked for services after the warning")
        XCTAssertTrue(requests.last.map { $0 is AuthorizationReq } ?? false, "the last request was not AuthorizationReq")
    }

    /// The response that is not a warning still goes on: the stop is a range, and OK is below it.
    func testAnOkAtAuthorizationDoesNotStop() throws {
        try evcc().stopOnAuthorizationWarning(
            AuthorizationRes(header: header(), responseCode: .OK, eVSEProcessing: .Finished))
    }

    /// The -20 `AuthorizationReq` declares `ecdsa-sha512` over a SHA-512 digest ([V2G20-2473],
    /// [V2G20-2475]), and its signature verifies under SHA-512. Josev's SHA-256 pair stays available
    /// as ``XmlDsigSuite/josevSha256``. EVerest 2026.10.0 refused the SHA-256 form with
    /// `FAILED_SignatureError`.
    func testThePncSignatureIsEcdsaSha512ByDefault() throws {

        let station = pncStation(.WARNING_CertificateValidationError)
        try SapHandshake.runEvccSide(station.stream, .iso15118_20, .dc)
        let evcc = Evcc20Dc(station.stream, clock: { 1_767_225_600 })
        evcc.pnc = try PncMaterial.options
        XCTAssertThrowsError(try evcc.run())

        let req = try XCTUnwrap(try station.sessionRequestPayloads()
            .map { try CommonMessagesCodec.decodeAny($0) }
            .compactMap { $0 as? AuthorizationReq }
            .first)
        let signature = try XCTUnwrap(req.header.signature)
        let reference = try XCTUnwrap(signature.signedInfo.reference.first)
        let mode      = try XCTUnwrap(req.pnC_AReqAuthorizationMode)
        let fragment  = CommonMessagesCodec.encodeFragment_PnC_AReqAuthorizationMode(mode)

        XCTAssertEqual(signature.signedInfo.signatureMethod.algorithm,
                       "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512")
        XCTAssertEqual(reference.digestMethod.algorithm, "http://www.w3.org/2001/04/xmlenc#sha512")
        XCTAssertEqual(reference.digestValue, Array(SHA512.hash(data: Data(fragment))))

        let octets = XmlDsigInterop.standaloneOctets(signature.signedInfo)
        let key    = PncMaterial.key.publicKey
        XCTAssertTrue(XmlDsigInterop.verify(octets, signature.signatureValue.value, key,
                                            signatureMethod: XmlDsigSuite.iso20.signatureMethod))
        XCTAssertFalse(XmlDsigInterop.verify(octets, signature.signatureValue.value, key,
                                             signatureMethod: XmlDsigSuite.josevSha256.signatureMethod),
                       "the -20 signature also verified under SHA-256")
    }

}
