package cloud.charging.v2g.evcc

import cloud.charging.v2g.iso20.ac.MessageHeaderType as AcHeader
import cloud.charging.v2g.iso20.common.MessageHeaderType as CommonHeader
import cloud.charging.v2g.iso20.dc.MessageHeaderType as DcHeader

/**
 * ISO 15118-20's per-schema-set modules are self-contained — CommonMessages, AC and DC do not
 * reference each other — so `MessageHeaderType` is a structurally identical but **distinct type**
 * three times over. This holds the session's actual state (the station-assigned SessionID, a
 * timestamp) and renders it into whichever module's header the next outgoing message needs.
 *
 * A port of the C# `SessionContext`, and the same answer to the same problem: a -20 session crosses
 * from a CommonMessages phase into a DC/AC one and back several times, and without this every
 * crossing would be a hand-written conversion.
 *
 * [clock] returns Unix seconds. It is injected rather than read from the system because the header
 * timestamp goes on the wire: a replayed session can only be compared byte for byte if the clock is
 * the one the recording used.
 */
class SessionContext(private val clock: () -> ULong) {

    var sessionId: ByteArray = ByteArray(8)

    fun toCommonHeader() = CommonHeader(sessionId, clock(), null)
    fun toAcHeader()     = AcHeader(sessionId, clock(), null)
    fun toDcHeader()     = DcHeader(sessionId, clock(), null)
}
