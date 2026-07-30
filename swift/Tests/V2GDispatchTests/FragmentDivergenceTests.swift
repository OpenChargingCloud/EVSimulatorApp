import XCTest

import ExiIso20AC
import ExiIso20Common
import ExiIso20DC

/// Why every -20 set carries its own `V2GSignature` instead of sharing one.
///
/// Each set embeds its own copy of the XMLDSig schema, and the fragment grammar's element selector
/// is sized by the whole set — so the *same* `SignedInfo` lands on a different event code in each:
/// 230 over 9 bits under CommonMessages, 135 over 8 under AC, 129 over 8 under DC. Different
/// octets, therefore different signed bytes.
///
/// This test target is the only one that sees more than one set at a time, which is what makes the
/// claim checkable at all. Without it the per-set duplication is an assertion in a comment; with
/// it, collapsing those helpers into one shared implementation fails here rather than on a charger.
final class FragmentDivergenceTests: XCTestCase {

    /// The same content, expressed in each set's own generated types.
    private func commonSignedInfo(algorithm: String = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512") -> ExiIso20Common.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: algorithm),
              reference: [.init(uRI: "#id1",
                                transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                                digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                                digestValue: [UInt8](repeating: 0xAB, count: 64))])
    }

    private func acSignedInfo(algorithm: String = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512") -> ExiIso20AC.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: algorithm),
              reference: [.init(uRI: "#id1",
                                transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                                digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                                digestValue: [UInt8](repeating: 0xAB, count: 64))])
    }

    private func dcSignedInfo(algorithm: String = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512") -> ExiIso20DC.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: algorithm),
              reference: [.init(uRI: "#id1",
                                transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                                digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                                digestValue: [UInt8](repeating: 0xAB, count: 64))])
    }

    func testTheSameSignedInfoEncodesDifferentlyInEachSet() {
        let common = CommonMessagesCodec.encodeFragment_SignedInfo(commonSignedInfo())
        let ac     = ACCodec.encodeFragment_SignedInfo(acSignedInfo())
        let dc     = DCCodec.encodeFragment_SignedInfo(dcSignedInfo())

        XCTAssertNotEqual(common, ac, "CommonMessages and AC would sign the same octets")
        XCTAssertNotEqual(common, dc, "CommonMessages and DC would sign the same octets")
        XCTAssertNotEqual(ac, dc, "AC and DC would sign the same octets")
    }

    /// The consequence, stated in the currency that actually matters: signatures.
    ///
    /// Different fragment octets are an argument; a signature made under one set's helper being
    /// *rejected* by another's is the thing a charger would do. One Ed448 seed, the same logical
    /// `SignedInfo`, three sets — three different signatures, and each verifies only at home.
    ///
    /// This is Ed448 rather than P-521 because Ed448 is deterministic: with ECDSA two signatures
    /// over identical octets differ anyway, so inequality would prove nothing. Here inequality can
    /// only come from the octets.
    func testASignatureFromOneSetIsRejectedByAnother() throws {
        let seed = [UInt8](repeating: 0x42, count: 57)

        let commonKey = try ExiIso20Common.Ed448PrivateKey(rawRepresentation: seed)
        let acKey     = try ExiIso20AC.Ed448PrivateKey(rawRepresentation: seed)
        let dcKey     = try ExiIso20DC.Ed448PrivateKey(rawRepresentation: seed)

        let ed448 = "http://www.w3.org/2021/04/xmldsig-more#eddsa-ed448"
        let common = commonSignedInfo(algorithm: ed448)
        let ac     = acSignedInfo(algorithm: ed448)
        let dc     = dcSignedInfo(algorithm: ed448)

        let commonSig = try ExiIso20Common.V2GSignature.sign(common, with: commonKey)
        let acSig     = try ExiIso20AC.V2GSignature.sign(ac, with: acKey)
        let dcSig     = try ExiIso20DC.V2GSignature.sign(dc, with: dcKey)

        // Same key, same content, deterministic algorithm — so these can only differ because the
        // signed octets do.
        XCTAssertNotEqual(commonSig, acSig)
        XCTAssertNotEqual(commonSig, dcSig)
        XCTAssertNotEqual(acSig, dcSig)

        // Each verifies at home...
        XCTAssertTrue(try ExiIso20Common.V2GSignature.verify(common, signature: commonSig,
                                                             with: commonKey.publicKey))
        XCTAssertTrue(try ExiIso20AC.V2GSignature.verify(ac, signature: acSig, with: acKey.publicKey))

        // ...and nowhere else. This is what a peer would see if the helpers were shared.
        XCTAssertFalse(try ExiIso20Common.V2GSignature.verify(common, signature: acSig,
                                                              with: commonKey.publicKey),
                       "AC's signature verified under CommonMessages — the helpers are interchangeable, "
                     + "which means one of them is signing octets its peers do not expect")
        XCTAssertFalse(try ExiIso20AC.V2GSignature.verify(ac, signature: dcSig, with: acKey.publicKey))
        XCTAssertFalse(try ExiIso20DC.V2GSignature.verify(dc, signature: commonSig, with: dcKey.publicKey))
    }

    /// And they diverge in the very first bits after the EXI header — the fragment selector itself,
    /// not somewhere in the content.
    func testTheyDivergeInTheFragmentSelector() {
        let common = CommonMessagesCodec.encodeFragment_SignedInfo(commonSignedInfo())
        let ac     = ACCodec.encodeFragment_SignedInfo(acSignedInfo())
        let dc     = DCCodec.encodeFragment_SignedInfo(dcSignedInfo())

        for f in [common, ac, dc] { XCTAssertEqual(f[0], 0x80, "EXI header") }

        // 230 over 9 bits, 135 over 8, 129 over 8 — the documented codes, in the first byte after
        // the header.
        XCTAssertEqual(common[1], 0b01110011)
        XCTAssertEqual(ac[1],     0b10000111)
        XCTAssertEqual(dc[1],     0b10000001)
    }
}
