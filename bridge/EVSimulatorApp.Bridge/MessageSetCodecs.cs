using System.Text.Json.Nodes;

namespace EVSimulatorApp.Bridge;

/// <summary>
/// Which message set a V2GTP frame belongs to, and how to read it as JSON-LD.
/// </summary>
/// <remarks>
/// <para>
/// <b>The payload type is not enough, and that is a fact about ISO 15118 rather than about this
/// code.</b> <c>0x8001</c> carries both the SupportedAppProtocol handshake and every ISO 15118-2
/// message — the handshake happens before a protocol has been agreed, so it cannot have a payload
/// type of its own. The dispatcher in <c>Vanaheimr.V2G.Exi.Dispatch</c> resolves it by position: SAP
/// is what comes first, and everything after it is -2. Here the message's own name resolves it,
/// because the events are built from a record of the session rather than from a live socket.
/// </para>
/// <para>
/// A frame this cannot place is an error event, never a silently skipped one.
/// </para>
/// </remarks>
public static class MessageSetCodecs
{

    /// <summary>The V2GTP header: version, payload type, and the payload's length.</summary>
    public const int V2GTPHeaderBytes = 8;


    /// <summary>
    /// Decodes a complete V2GTP frame and returns the message as JSON-LD.
    /// </summary>
    /// <param name="frame">The whole frame, header included.</param>
    /// <param name="payloadType">As recorded, e.g. <c>0x8001</c>.</param>
    /// <param name="messageName">Used only to tell SAP from ISO 15118-2 — see the type's remarks.</param>
    public static JsonObject ToJSON(ReadOnlySpan<byte> frame, string payloadType, string messageName)
    {

        if (frame.Length <= V2GTPHeaderBytes)
            throw new ArgumentException($"a V2GTP frame is longer than its {V2GTPHeaderBytes}-byte header.",
                                        nameof(frame));

        var payload = frame[V2GTPHeaderBytes..].ToArray();

        return (payloadType, messageName.StartsWith("SupportedAppProtocol", StringComparison.Ordinal)) switch
        {
            ("0x8001", true)  => Vanaheimr.V2G.AppProtocol.Generated.SupportedAppProtocolCodecJson.ToJSON(
                                     Vanaheimr.V2G.AppProtocol.Generated.SupportedAppProtocolCodec.DecodeAny(payload, out _)),

            ("0x8001", false) => Vanaheimr.V2G.Iso15118_2.Generated.Iso2CodecJson.ToJSON(
                                     Vanaheimr.V2G.Iso15118_2.Generated.Iso2Codec.DecodeAny(payload, out _)),

            ("0x8002", _)     => Vanaheimr.V2G.Iso15118_20.CommonMessages.Generated.CommonMessagesCodecJson.ToJSON(
                                     Vanaheimr.V2G.Iso15118_20.CommonMessages.Generated.CommonMessagesCodec.DecodeAny(payload, out _)),

            ("0x8003", _)     => Vanaheimr.V2G.Iso15118_20.AC.Generated.AcCodecJson.ToJSON(
                                     Vanaheimr.V2G.Iso15118_20.AC.Generated.AcCodec.DecodeAny(payload, out _)),

            ("0x8004", _)     => Vanaheimr.V2G.Iso15118_20.DC.Generated.DcCodecJson.ToJSON(
                                     Vanaheimr.V2G.Iso15118_20.DC.Generated.DcCodec.DecodeAny(payload, out _)),

            _ => throw new NotSupportedException(
                     $"payload type '{payloadType}' is not a message set this build carries."),
        };
    }

}
