using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using NUnit.Framework;

namespace EVSimulatorApp.Bridge.Tests;

/// <summary>
/// The one thing that travels <em>into</em> the bridge, and the corpus the ports are held to.
/// </summary>
/// <remarks>
/// <para>
/// The event stream is a record: a page can only watch it. This is the opposite direction — a value
/// a WebView hands to native code, which then opens a socket because of it. So the interesting half
/// of this file is the refusals, and the refusals are pinned <b>by message</b>: three back ends that
/// all say no for three different reasons are three different products, and the difference only ever
/// shows up in front of a user who cannot act on it.
/// </para>
/// <para>
/// <b>Nothing here resolves a host name</b>, by the same rule as the pairing sheet: a lookup on
/// behalf of a code nobody has agreed to trust is already a callback to whoever printed it.
/// </para>
/// </remarks>
[TestFixture]
public class SessionConfigTests
{

    private const string CorpusFile = "Bridge.config.json";

    private static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true };


    private static string RepositoryRoot
    {
        get
        {
            var directory = new DirectoryInfo(TestContext.CurrentContext.TestDirectory);
            while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, "libs/Vanaheimr.V2G.Exi")))
                directory = directory.Parent;

            return directory?.FullName ?? throw new DirectoryNotFoundException("repository root not found");
        }
    }

    private static string CorpusPath =>
        Path.Combine(RepositoryRoot, "bridge/EVSimulatorApp.Bridge.Tests/Vectors", CorpusFile);


    #region The cases

    /// <summary>Configurations a session may start from.</summary>
    private static readonly (string Name, string Json)[] Accepted = [

        ("minimal", """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("everything", """
            {"host":"10.1.2.3","port":15119,"transport":"tcp","protocol":"iso15118-20","mode":"dc",
             "authorization":"pnc","totp":"481923",
             "rootFingerprint":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}
            """),

        // A name, never an address. `.local` is the only name shape that counts as reachable-but-local,
        // and it is judged as text — see PairingWarnings.IsPrivateTarget.
        ("mdns-name", """
            {"host":"secc-1.local","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        // ISO 15118 runs over link-local IPv6 in the wired case, zone identifier and all.
        ("ipv6-link-local", """
            {"host":"fe80::1%en0","port":15118,"transport":"tls","protocol":"iso15118-20","mode":"dc","authorization":"eim"}
            """),

        ("ipv6-unique-local", """
            {"host":"fd12:3456::1","port":15118,"transport":"tls","protocol":"iso15118-20","mode":"ac","authorization":"pnc"}
            """),

        ("loopback", """
            {"host":"127.0.0.1","port":1,"transport":"tcp","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("highest-port", """
            {"host":"172.16.0.1","port":65535,"transport":"tls","protocol":"iso15118-2","mode":"dc","authorization":"eim"}
            """),

        // Absent and explicitly null mean the same thing, because the writer omits rather than nulls
        // and a null can therefore only come from somebody else's writer.
        ("explicit-nulls", """
            {"host":"169.254.10.1","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac",
             "authorization":"eim","totp":null,"rootFingerprint":null}
            """),

    ];


    /// <summary>Configurations no session starts from, and the words the user is shown.</summary>
    private static readonly (string Name, string Json)[] Refused = [

        ("not-an-object",        """ ["192.168.1.42", 15118] """),
        ("null-document",        """ null """),

        // A key this build does not read is either a newer front end or somebody probing. Ignoring it
        // would mean a setting shown on the confirmation sheet silently did not happen.
        ("unknown-property",     """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac",
             "authorization":"eim","tlsVersion":"1.2"}
            """),

        // B1's private-range restriction, applied where the socket is opened rather than where the
        // sheet was drawn.
        ("public-address",       """
            {"host":"93.184.216.34","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("resolvable-name",      """
            {"host":"station.example.com","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        // 010.0.0.1 is 8.0.0.1 to a C resolver and 10.0.0.1 to a reader in a hurry. Neither reading
        // is used: a leading zero makes it not an address literal at all, so it is judged as a name
        // and refused for not ending in .local.
        ("octal-looking-host",   """
            {"host":"010.0.0.1","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("public-ipv6",          """
            {"host":"2001:db8::1","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("missing-host",         """
            {"port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("empty-host",           """
            {"host":"","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("host-not-a-string",    """
            {"host":42,"port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("missing-port",         """
            {"host":"192.168.1.42","transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("port-zero",            """
            {"host":"192.168.1.42","port":0,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("port-above-range",     """
            {"host":"192.168.1.42","port":65536,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("port-as-string",       """
            {"host":"192.168.1.42","port":"15118","transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("port-fractional",      """
            {"host":"192.168.1.42","port":15118.5,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("unknown-transport",    """
            {"host":"192.168.1.42","port":15118,"transport":"quic","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        // Exact match, not case-insensitive. The producer is a program, not a person.
        ("transport-uppercased", """
            {"host":"192.168.1.42","port":15118,"transport":"TLS","protocol":"iso15118-2","mode":"ac","authorization":"eim"}
            """),

        ("unknown-protocol",     """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"din70121","mode":"ac","authorization":"eim"}
            """),

        ("unknown-mode",         """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-20","mode":"wpt","authorization":"eim"}
            """),

        ("unknown-authorization","""
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac","authorization":"none"}
            """),

        // An empty optional is a value somebody meant to supply and did not — unlike an absent one.
        ("empty-totp",           """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac",
             "authorization":"eim","totp":""}
            """),

        ("totp-not-a-string",    """
            {"host":"192.168.1.42","port":15118,"transport":"tls","protocol":"iso15118-2","mode":"ac",
             "authorization":"eim","totp":481923}
            """),

    ];

    #endregion


    /// <summary>The corpus, as this back end produces it.</summary>
    private static JsonObject Produce()
    {

        var accepted = new JsonArray();

        foreach (var (name, text) in Accepted)
        {
            var config = SessionConfig.Parse(JsonNode.Parse(text));

            accepted.Add((JsonNode?) new JsonObject {
                ["name"]      = name,
                ["input"]     = JsonNode.Parse(text),
                ["canonical"] = config.ToJSON(),
            });
        }

        var refused = new JsonArray();

        foreach (var (name, text) in Refused)
        {
            string message;
            try
            {
                SessionConfig.Parse(JsonNode.Parse(text));
                message = "(accepted — this case no longer refuses anything)";
            }
            catch (SessionConfigException e)
            {
                message = e.Message;
            }

            refused.Add((JsonNode?) new JsonObject {
                ["name"]    = name,
                ["input"]   = JsonNode.Parse(text),
                ["message"] = message,
            });
        }

        return new JsonObject {
            ["note"]     = "Generated by SessionConfigTests.RegenerateTheCorpus. What a WebView may ask "
                         + "the native side to connect to, and — the larger half — what it may not. "
                         + "The refusal messages are part of the corpus: a port that says no for a "
                         + "different reason is a different product.",
            ["accepted"] = accepted,
            ["refused"]  = refused,
        };
    }


    [Test, Explicit("Rewrites the checked-in configuration corpus. Run deliberately, and read the diff.")]
    public void RegenerateTheCorpus()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(CorpusPath)!);
        File.WriteAllText(CorpusPath, Produce().ToJsonString(Pretty));

        TestContext.Out.WriteLine($"-> {CorpusPath}");
    }


    [Test]
    public void TheCorpusIsUnchanged()
    {

        Assert.That(File.Exists(CorpusPath), Is.True, $"the configuration corpus is missing at {CorpusPath}");

        var expected = JsonNode.Parse(File.ReadAllText(CorpusPath))!.AsObject();
        var actual   = Produce();

        foreach (var section in new[] { "accepted", "refused" })
            Assert.That(actual[section]!.ToJsonString(Pretty),
                        Is.EqualTo(expected[section]!.ToJsonString(Pretty)), section);
    }


    /// <summary>
    /// Every accepted configuration survives its own JSON: parse, write, parse again, same value.
    /// </summary>
    /// <remarks>
    /// The round trip is what makes the canonical form usable as an audit record. It is deliberately
    /// <em>not</em> the only check here — a round trip is blind to what the properties are called,
    /// because the writer and the reader rename together. That is what the corpus above pins, and
    /// what <see cref="TheTypeScriptSideDeclaresTheSameProperties"/> holds to the front end.
    /// </remarks>
    [Test]
    public void EveryAcceptedConfigurationSurvivesItsOwnJson()
    {

        foreach (var (name, text) in Accepted)
        {
            var once  = SessionConfig.Parse(JsonNode.Parse(text));
            var twice = SessionConfig.Parse(once.ToJSON());

            Assert.That(twice, Is.EqualTo(once), name);
        }
    }


    /// <summary>
    /// The properties this reads are the properties the TypeScript front end writes.
    /// </summary>
    /// <remarks>
    /// <para>
    /// The producer and the consumer of this shape live in different languages, in different
    /// processes, and are never compiled together — which is precisely the arrangement in which a
    /// property quietly stops being read. Since unknown properties are <em>refused</em>, a front end
    /// that adds one does not degrade gracefully: it fails to start a session at all. So the drift is
    /// worth catching here rather than on a phone.
    /// </para>
    /// <para>
    /// Read out of the TypeScript source rather than duplicated, because a second list is the thing
    /// that would go stale.
    /// </para>
    /// </remarks>
    [Test]
    public void TheTypeScriptSideDeclaresTheSameProperties()
    {

        var source = File.ReadAllText(Path.Combine(RepositoryRoot, "typescript/src/bridge/plugin.ts"));
        var body   = Regex.Match(source, @"export interface SessionConfig \{(.*?)\n\}", RegexOptions.Singleline);

        Assert.That(body.Success, Is.True, "the SessionConfig interface was not found in plugin.ts");

        var declared = Regex.Matches(body.Groups[1].Value, @"readonly\s+([A-Za-z0-9_]+)\??\s*:")
                            .Select(m => m.Groups[1].Value)
                            .ToList();

        var known = SessionConfig.Parse(JsonNode.Parse(Accepted[1].Json)).ToJSON().Select(p => p.Key).ToList();

        Assert.That(declared, Is.EquivalentTo(known),
                    "typescript/src/bridge/plugin.ts and SessionConfig.cs no longer describe the same value");
    }

}
