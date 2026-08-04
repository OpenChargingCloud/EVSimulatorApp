/*
 * Copyright (c) 2021-2026 GraphDefined GmbH <achim.friedland@graphdefined.com>
 * This file is part of WWCP ISO/IEC 15118 <https://github.com/OpenChargingCloud/WWCP_ISO15118>
 *
 * Licensed under the Affero GPL license, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/agpl.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
/// type of its own. The dispatcher in <c>WWCP_ISO15118_EXI_Dispatch</c> resolves it by position: SAP
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
            ("0x8001", true)  => cloud.charging.open.protocols.ISO15118.AppProtocol.Generated.SupportedAppProtocolCodecJson.ToJSON(
                                     cloud.charging.open.protocols.ISO15118.AppProtocol.Generated.SupportedAppProtocolCodec.DecodeAny(payload, out _)),

            ("0x8001", false) => cloud.charging.open.protocols.ISO15118_2.Generated.Iso2CodecJson.ToJSON(
                                     cloud.charging.open.protocols.ISO15118_2.Generated.Iso2Codec.DecodeAny(payload, out _)),

            ("0x8002", _)     => cloud.charging.open.protocols.ISO15118_20.CommonMessages.Generated.CommonMessagesCodecJson.ToJSON(
                                     cloud.charging.open.protocols.ISO15118_20.CommonMessages.Generated.CommonMessagesCodec.DecodeAny(payload, out _)),

            ("0x8003", _)     => cloud.charging.open.protocols.ISO15118_20.AC.Generated.AcCodecJson.ToJSON(
                                     cloud.charging.open.protocols.ISO15118_20.AC.Generated.AcCodec.DecodeAny(payload, out _)),

            ("0x8004", _)     => cloud.charging.open.protocols.ISO15118_20.DC.Generated.DcCodecJson.ToJSON(
                                     cloud.charging.open.protocols.ISO15118_20.DC.Generated.DcCodec.DecodeAny(payload, out _)),

            _ => throw new NotSupportedException(
                     $"payload type '{payloadType}' is not a message set this build carries."),
        };
    }


    /// <summary>
    /// The message's name, read out of the document.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The recorder derives this from the decoded object's <em>type</em>, which a live session cannot
    /// do without reflection in three languages that name types three different ways. It does not
    /// have to: the JSON-LD emitter writes the type name as <c>@type</c>, so the same answer is
    /// already in the document, and reading it there is language-neutral.
    /// </para>
    /// <para>
    /// The rule is structural rather than a table. ISO 15118-2 wraps everything in a
    /// <c>V2G_Message</c>, so the interesting name is the body element's; the -20 sets and the
    /// SupportedAppProtocol handshake decode straight to the message, so the document is the message.
    /// <c>SessionEventStreamTests.TheNameInTheDocumentIsTheNameTheRecorderGave</c> holds that claim to
    /// all 196 recorded events rather than to this paragraph.
    /// </para>
    /// </remarks>
    public static string MessageName(JsonObject json)

        => json["body"] is JsonObject body

               ? body["bodyElement"] is JsonObject element
                     ? element["@type"]!.GetValue<string>()
                     : "V2G_Message(empty body)"

               : json["@type"]!.GetValue<string>();

}
