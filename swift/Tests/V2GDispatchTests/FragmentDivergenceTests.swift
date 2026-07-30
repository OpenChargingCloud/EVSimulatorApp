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
    private func commonSignedInfo() -> ExiIso20Common.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"),
              reference: [.init(uRI: "#id1",
                                transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                                digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                                digestValue: [UInt8](repeating: 0xAB, count: 64))])
    }

    private func acSignedInfo() -> ExiIso20AC.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"),
              reference: [.init(uRI: "#id1",
                                transforms: .init(transform: [.init(algorithm: "http://www.w3.org/TR/canonical-exi/")]),
                                digestMethod: .init(algorithm: "http://www.w3.org/2001/04/xmlenc#sha512"),
                                digestValue: [UInt8](repeating: 0xAB, count: 64))])
    }

    private func dcSignedInfo() -> ExiIso20DC.SignedInfoType {
        .init(canonicalizationMethod: .init(algorithm: "http://www.w3.org/TR/canonical-exi/"),
              signatureMethod: .init(algorithm: "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512"),
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
