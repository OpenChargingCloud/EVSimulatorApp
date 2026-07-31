import ExiIso20AC
import ExiIso20Common
import ExiIso20DC

/// ISO 15118-20's per-schema-set modules are self-contained — CommonMessages, AC and DC do not
/// reference each other — so `MessageHeaderType` is a structurally identical but **distinct type**
/// three times over. Swift makes that unusually visible: the three are ambiguous by bare name and
/// have to be written module-qualified, which is not noise but the situation stated honestly.
///
/// This holds the session's actual state (the station-assigned SessionID, a timestamp) and renders
/// it into whichever module's header the next outgoing message needs. A port of the C#
/// `SessionContext`, and the same answer to the same problem: a -20 session crosses from a
/// CommonMessages phase into a DC/AC one and back several times, and without this every crossing
/// would be a hand-written conversion.
///
/// `clock` returns Unix seconds. It is injected rather than read from the system because the header
/// timestamp goes on the wire: a replayed session can only be compared byte for byte if the clock is
/// the one the recording used.
public final class SessionContext {

    public var sessionId = [UInt8](repeating: 0, count: 8)

    private let clock: () -> UInt64

    public init(clock: @escaping () -> UInt64) {
        self.clock = clock
    }

    public func toCommonHeader() -> ExiIso20Common.MessageHeaderType {
        ExiIso20Common.MessageHeaderType(sessionID: sessionId, timeStamp: clock())
    }

    public func toAcHeader() -> ExiIso20AC.MessageHeaderType {
        ExiIso20AC.MessageHeaderType(sessionID: sessionId, timeStamp: clock())
    }

    public func toDcHeader() -> ExiIso20DC.MessageHeaderType {
        ExiIso20DC.MessageHeaderType(sessionID: sessionId, timeStamp: clock())
    }
}
