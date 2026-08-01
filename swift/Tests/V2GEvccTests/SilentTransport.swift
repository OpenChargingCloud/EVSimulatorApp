@testable import V2GEvcc

/// A transport that is never used, for the checks that inspect a decoded message and touch no octets.
///
/// Shared by the -2 and -20 failure tests: `refuseOnFailure` looks at a message and nothing else, so a
/// stream that would throw on any actual use is exactly the right stand-in.
final class SilentTransport: V2GByteStream {
    func read(maxLength: Int) throws -> [UInt8] { [] }
    func write(_ bytes: [UInt8]) throws { }
}
