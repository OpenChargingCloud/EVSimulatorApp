using System.Text.Json;
using System.Text.Json.Nodes;

namespace EVSimulatorApp.Bridge;

/// <summary>
/// Turns a recorded session into the event stream the bridge emits (<c>docs/CONCEPT.md</c> B1).
/// </summary>
/// <remarks>
/// <para>
/// <b>A recorded session, not a live one, and that is what makes the stream checkable at all.</b>
/// The traces under <c>Vectors/Session.*.trace.json</c> are whole EV↔station sessions captured frame
/// by frame, so the event stream they produce is deterministic and can be pinned by a corpus — which
/// a stream built from a socket never could. The live runner emits the same events from the same
/// frames; what differs is where the frames come from.
/// </para>
/// <para>
/// The clock is injected for the same reason. Timings are part of what B1 asks the stream to carry,
/// and a corpus generated from a real clock would regenerate differently every run and pin nothing.
/// </para>
/// </remarks>
public sealed class SessionEventStream
{

    private readonly Func<long> clock;
    private int seq;
    private long start;

    /// <param name="monotonicMillis">
    /// A monotonic millisecond clock. Monotonic rather than wall-clock because a wall clock can step
    /// backwards over NTP or a timezone change, and would then show a response arriving before the
    /// request that caused it.
    /// </param>
    public SessionEventStream(Func<long> monotonicMillis)
    {
        clock = monotonicMillis;
    }


    /// <summary>Every event of one recorded session, in order.</summary>
    public IEnumerable<BridgeEvent> Replay(JsonNode trace)
    {

        var root      = trace.AsObject();
        var exchanges = root["exchanges"]!.AsArray();

        start = clock();
        seq   = 0;

        yield return new BridgeEvent.SessionStarted {
            Seq      = seq++,
            AtMillis = clock() - start,
            Name     = root["name"]!.GetValue<string>(),
            Protocol = root["protocol"]!.GetValue<string>(),
            Mode     = root["mode"]!.GetValue<string>(),
        };

        var failed = false;

        foreach (var exchange in exchanges)
        {
            foreach (var (side, direction) in new[] { ("request", "out"), ("response", "in") })
            {
                var frame = exchange![side];
                if (frame is null) continue;

                var evt = Describe(frame.AsObject(), direction);
                if (evt is BridgeEvent.Error) failed = true;

                yield return evt;
            }
        }

        yield return new BridgeEvent.SessionFinished {
            Seq       = seq++,
            AtMillis  = clock() - start,
            Exchanges = exchanges.Count,
            Outcome   = failed ? "failed" : "completed",
        };
    }


    /// <summary>
    /// One recorded frame as an event — the message twice over, or an error naming the frame.
    /// </summary>
    private BridgeEvent Describe(JsonObject frame, string direction)
    {

        var payloadType = frame["payloadType"]!.GetValue<string>();
        var messageName = frame["message"]!.GetValue<string>();
        var hex         = frame["frame"]!.GetValue<string>();

        try
        {
            var json = MessageSetCodecs.ToJSON(Convert.FromHexString(hex), payloadType, messageName);

            return new BridgeEvent.Message {
                Seq         = seq++,
                AtMillis    = clock() - start,
                Direction   = direction,
                PayloadType = payloadType,
                MessageName = messageName,
                Exi         = hex.ToLowerInvariant(),
                Json        = json,
            };
        }
        catch (Exception ex)
        {
            // The frame goes out with the error. A decode failure whose bytes are not in the stream
            // is a report nobody can act on — the one thing a reader needs is the input that
            // produced it.
            return new BridgeEvent.Error {
                Seq      = seq++,
                AtMillis = clock() - start,
                Detail   = $"{messageName} ({payloadType}) could not be read: {ex.Message}",
                Exi      = hex.ToLowerInvariant(),
            };
        }
    }


    /// <summary>An event as the JSON a consumer receives.</summary>
    /// <remarks>
    /// Hand-written rather than reflected, because this shape is a wire format the moment a WebView
    /// reads it: a property renamed by a serializer setting somewhere would be a breaking change
    /// nobody wrote down. The same reason the JSON-LD naming rule is a rule rather than a convention.
    /// </remarks>
    public static JsonObject ToJSON(BridgeEvent evt)
    {

        var json = new JsonObject {
            ["seq"]      = evt.Seq,
            ["atMillis"] = evt.AtMillis,
            ["kind"]     = evt.Kind,
        };

        switch (evt)
        {
            case BridgeEvent.SessionStarted started:
                json["name"]     = started.Name;
                json["protocol"] = started.Protocol;
                json["mode"]     = started.Mode;
                break;

            case BridgeEvent.Message message:
                json["direction"]   = message.Direction;
                json["payloadType"] = message.PayloadType;
                json["messageName"] = message.MessageName;
                json["exi"]         = message.Exi;
                json["json"]        = message.Json.DeepClone();
                break;

            case BridgeEvent.SessionFinished finished:
                json["exchanges"] = finished.Exchanges;
                json["outcome"]   = finished.Outcome;
                break;

            case BridgeEvent.Error error:
                json["detail"] = error.Detail;
                if (error.Exi is not null) json["exi"] = error.Exi;
                break;

            default:
                throw new NotSupportedException($"unmapped event kind '{evt.Kind}'.");
        }

        return json;
    }

}


/// <summary>A clock that advances a fixed amount per reading. The corpus would not be a corpus
/// otherwise, and a live session gets the real one.</summary>
public sealed class SteppingClock(long stepMillis = 1)
{
    private long now;

    public long Read()
    {
        var value = now;
        now += stepMillis;
        return value;
    }
}
