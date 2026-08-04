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

using EVSimulatorApp.Pairing;

namespace EVSimulatorApp.Bridge;

/// <summary>
/// What a session needs to know before it can start — the one thing that travels <em>into</em> the
/// bridge (<c>docs/CONCEPT.md</c> B1).
/// </summary>
/// <remarks>
/// <para>
/// This is the scanned pairing code's content plus the choices a human made on the confirmation
/// sheet. Deliberately so: the only path into a session is one somebody approved, and nothing here
/// re-parses a QR code — <see cref="PairingUri"/> already did that, in front of the user.
/// </para>
/// <para>
/// <b>It arrives from a WebView, so it is untrusted, so it is parsed rather than deserialised.</b>
/// The events go out to a page that can only watch; this comes back from a page that can be
/// navigated, injected into, or replaced by a different page altogether. A reflected deserialiser
/// would accept whatever shape it was handed and leave the checking to whoever remembered to write
/// it; this refuses, by name, everything it was not built for.
/// </para>
/// <para>
/// <b>Unknown properties are refused rather than ignored.</b> A key this build does not read is
/// either a newer front end or somebody probing, and the two are indistinguishable here. Ignoring it
/// means a setting the user saw on the sheet, and believes they approved, silently did not happen —
/// which is the failure this whole confirm-before-connect design exists to prevent.
/// </para>
/// </remarks>
public sealed record SessionConfig
{

    /// <summary>Where the station is: an address literal or a <c>.local</c> name.</summary>
    /// <remarks>Never resolved — see <see cref="PairingWarnings.IsPrivateTarget"/>.</remarks>
    public required String  Host             { get; init; }

    public required Int32   Port             { get; init; }

    /// <summary><c>tls</c> or <c>tcp</c>.</summary>
    public required String  Transport        { get; init; }

    /// <summary><c>iso15118-2</c> or <c>iso15118-20</c>.</summary>
    public required String  Protocol         { get; init; }

    /// <summary><c>ac</c> or <c>dc</c>.</summary>
    public required String  Mode             { get; init; }

    /// <summary><c>eim</c> for external payment, <c>pnc</c> for Plug &amp; Charge.</summary>
    public required String  Authorization    { get; init; }

    /// <summary>
    /// The TOTP read off the station's display, if the code carried one.
    /// </summary>
    /// <remarks>
    /// Passed on as it was read, never recomputed from this device's clock: the phone's time is not
    /// trustworthy and the station is the one that decides (§4.6).
    /// </remarks>
    public          String? Totp             { get; init; }

    /// <summary>The root certificate fingerprint the pairing code pinned, if it carried one.</summary>
    public          String? RootFingerprint  { get; init; }


    #region The permitted values

    private static readonly String[] Transports     = ["tls", "tcp"];
    private static readonly String[] Protocols      = ["iso15118-2", "iso15118-20"];
    private static readonly String[] Modes          = ["ac", "dc"];
    private static readonly String[] Authorizations = ["eim", "pnc"];

    private static readonly String[] Known = [
        "host", "port", "transport", "protocol", "mode", "authorization", "totp", "rootFingerprint"
    ];

    #endregion


    /// <summary>Reads a configuration a WebView sent, or explains why it is not one.</summary>
    /// <exception cref="SessionConfigException">on anything this build would not act on.</exception>
    public static SessionConfig Parse(JsonNode? node)
    {

        var json = node as JsonObject
                       ?? throw new SessionConfigException("a session configuration is a JSON object.");

        foreach (var property in json)
            if (!Known.Contains(property.Key))
                throw new SessionConfigException($"'{property.Key}' is not a configuration property " +
                                                 $"this build reads. Known: {String.Join(", ", Known)}.");

        var host = Text(json, "host");

        // The one rule here that is about safety rather than shape. B1 restricts a session to a
        // private-range target, and the restriction has to hold at the point the socket is opened
        // rather than at the point the sheet was shown — the sheet is a different process's memory,
        // and this is the last place that can say no.
        if (!PairingWarnings.IsPrivateTarget(host))
            throw new SessionConfigException($"'{host}' is not a private or link-local address. " +
                                              "A session is only offered to a counterpart that could " +
                                              "plausibly be the one in front of you.");

        var port = json["port"] as JsonValue
                       ?? throw new SessionConfigException("'port' is missing.");

        if (!port.TryGetValue<Int32>(out var portNumber) || portNumber is < 1 or > 65535)
            throw new SessionConfigException($"'port' is {port.ToJsonString()}, which is not a TCP port.");

        return new SessionConfig {
            Host            = host,
            Port            = portNumber,
            Transport       = OneOf(json, "transport",     Transports),
            Protocol        = OneOf(json, "protocol",      Protocols),
            Mode            = OneOf(json, "mode",          Modes),
            Authorization   = OneOf(json, "authorization", Authorizations),
            Totp            = Optional(json, "totp"),
            RootFingerprint = Optional(json, "rootFingerprint"),
        };

    }


    /// <summary>The configuration as JSON, in the order every back end writes it.</summary>
    /// <remarks>
    /// Hand-written and ordered for the same reason <see cref="SessionEventStream.ToJSON"/> is: the
    /// moment a WebView writes this shape it is a wire format, and a property a serializer setting
    /// renamed would be a breaking change nobody wrote down. Absent optionals are omitted rather than
    /// written as null, matching the JSON-LD side.
    /// </remarks>
    public JsonObject ToJSON()
    {

        var json = new JsonObject {
            ["host"]          = Host,
            ["port"]          = Port,
            ["transport"]     = Transport,
            ["protocol"]      = Protocol,
            ["mode"]          = Mode,
            ["authorization"] = Authorization,
        };

        if (Totp            is not null) json["totp"]            = Totp;
        if (RootFingerprint is not null) json["rootFingerprint"] = RootFingerprint;

        return json;

    }


    #region (private) Text/OneOf/Optional(json, property)

    private static String Text(JsonObject json, String property)
    {

        if (json[property] is not JsonValue value || !value.TryGetValue<String>(out var text))
            throw new SessionConfigException($"'{property}' is missing or is not a string.");

        if (text.Length == 0)
            throw new SessionConfigException($"'{property}' is empty.");

        return text;

    }

    private static String OneOf(JsonObject json, String property, String[] permitted)
    {

        var text = Text(json, property);

        if (!permitted.Contains(text))
            throw new SessionConfigException($"'{property}' is '{text}'. Known: " +
                                             $"{String.Join(", ", permitted)}.");

        return text;

    }

    /// <summary>An optional property — absent or explicitly null both mean absent.</summary>
    /// <remarks>
    /// The two are folded together because the serialiser omits rather than nulls, so a null can only
    /// come from something else's writer, and refusing it would make this stricter than it can
    /// justify being. An <em>empty</em> string is refused, though: that is a value somebody meant to
    /// supply and did not.
    /// </remarks>
    private static String? Optional(JsonObject json, String property)
        => json[property] is null
               ? null                       // absent, or present as JSON null: JsonObject reports both this way
               : Text(json, property);

    #endregion

}


/// <summary>A session configuration that will not be acted on, and why.</summary>
/// <remarks>
/// Its own type rather than a generic argument exception, because the message goes back over the
/// bridge to a page that will show it to somebody: it is a user-facing refusal, not an assertion.
/// </remarks>
public sealed class SessionConfigException(String message) : Exception(message);
