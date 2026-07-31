using System.Text.Json.Nodes;

namespace EVSimulatorApp.Bridge;

/// <summary>
/// One event on the bridge between the session and whatever is watching it — the WebView inspector,
/// Chargy, a log file (<c>docs/CONCEPT.md</c> B1).
/// </summary>
/// <remarks>
/// <para>
/// <b>Every message goes out twice: as JSON-LD and as the raw EXI frame.</b> That is B1's wording and
/// it is not redundancy. The JSON-LD is what a person or a tool can read; the frame is what actually
/// crossed the wire. Either alone is a claim — together they are a claim and its evidence, and anyone
/// holding the event can check one against the other without asking this application anything.
/// </para>
/// <para>
/// The events are a <b>record of what happened</b>, never a channel for making things happen. There
/// is no event that asks the far side to do something, which is what keeps the stream safe to hand to
/// a WebView: a page that receives it can display, diff and export, and cannot drive a charging
/// session by replying.
/// </para>
/// </remarks>
public abstract record BridgeEvent
{

    /// <summary>Position in the stream, from 0. A consumer that sees a gap has lost events.</summary>
    public required int Seq { get; init; }

    /// <summary>
    /// Milliseconds since the session started, from a monotonic clock.
    /// </summary>
    /// <remarks>
    /// Relative rather than absolute, and monotonic rather than wall-clock, because these are
    /// <em>timings</em> — what B1 asks the stream to carry. A wall clock can step backwards over NTP
    /// or a timezone change and would then show a response arriving before its request.
    /// </remarks>
    public required long AtMillis { get; init; }

    public abstract string Kind { get; }


    /// <summary>The session began. Carries what the whole stream is about, so a consumer joining at
    /// the top needs no configuration of its own.</summary>
    public sealed record SessionStarted : BridgeEvent
    {
        public override string Kind => "sessionStarted";

        public required string Name { get; init; }

        /// <summary><c>iso15118-2</c> or <c>iso15118-20</c>.</summary>
        public required string Protocol { get; init; }

        /// <summary><c>ac</c> or <c>dc</c>.</summary>
        public required string Mode { get; init; }
    }


    /// <summary>A message crossed the wire.</summary>
    public sealed record Message : BridgeEvent
    {
        public override string Kind => "message";

        /// <summary><c>out</c> for a message this EV sent, <c>in</c> for one it received.</summary>
        public required string Direction { get; init; }

        /// <summary>The V2GTP payload type, e.g. <c>0x8001</c> — which message set the frame is in.</summary>
        public required string PayloadType { get; init; }

        /// <summary>The message's name, for a reader who wants neither of the two forms below.</summary>
        public required string MessageName { get; init; }

        /// <summary>The complete V2GTP frame, header included, as lower-case hex.</summary>
        public required string Exi { get; init; }

        /// <summary>The same message as JSON-LD — the generated form, not a summary of it.</summary>
        public required JsonObject Json { get; init; }
    }


    /// <summary>The session ended, and how.</summary>
    public sealed record SessionFinished : BridgeEvent
    {
        public override string Kind => "sessionFinished";

        public required int Exchanges { get; init; }

        /// <summary><c>completed</c>, or <c>failed</c> when an error event preceded this one.</summary>
        public required string Outcome { get; init; }
    }


    /// <summary>
    /// Something went wrong, and the session may or may not continue.
    /// </summary>
    /// <remarks>
    /// It carries the frame that caused it when there was one. A decode failure whose bytes are not
    /// in the stream is a report nobody can act on — the one thing a reader needs is the input that
    /// produced it.
    /// </remarks>
    public sealed record Error : BridgeEvent
    {
        public override string Kind => "error";

        /// <summary>What went wrong, in terms a reader can act on.</summary>
        /// <remarks>
        /// Named <c>Detail</c> rather than <c>Message</c> because <c>Message</c> is already an event
        /// kind here, and a property that shadows a sibling type is a name that will be misread.
        /// </remarks>
        public required string Detail { get; init; }

        /// <summary>The frame being processed when this happened, as lower-case hex, if there was one.</summary>
        public string? Exi { get; init; }
    }

}
