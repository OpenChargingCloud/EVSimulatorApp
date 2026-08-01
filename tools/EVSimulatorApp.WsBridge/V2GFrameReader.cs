using Vanaheimr.V2G.Tp;

namespace EVSimulatorApp.WsBridge;

/// <summary>
/// Reads whole V2GTP frames off a byte stream.
/// </summary>
/// <remarks>
/// <para>
/// <b>This is the whole reason the bridge is more than a byte pump.</b> TCP is a stream: a read
/// returns whatever has arrived, which may be half a frame or three of them. WebSocket is
/// message-framed: a receive returns exactly what a send sent. So the station→browser direction has
/// to *re-frame*, and the only thing that says where a frame ends is V2GTP's own 8-byte header.
/// </para>
/// <para>
/// Without it a browser would receive arbitrary chunks and have to reassemble them itself — which
/// throws away the one thing that makes WebSocket a better fit for this protocol than TCP, and puts
/// the framing logic in the place least able to test it.
/// </para>
/// </remarks>
public sealed class V2GFrameReader(Stream source, int maximumPayloadBytes = V2GFrameReader.DefaultMaximumPayloadBytes)
{

    /// <summary>
    /// The largest payload this will allocate for.
    /// </summary>
    /// <remarks>
    /// The header's length field is a 32-bit count supplied by the peer, so believing it means
    /// letting whoever is on the other end choose an allocation of up to 4 GiB. ISO 15118 messages
    /// are hundreds of bytes; a certificate chain in a -20 <c>AuthorizationReq</c> is the largest
    /// thing that ever crosses and is far under this. A frame that declares more is a peer that is
    /// broken or hostile, and either way not one to allocate for.
    /// </remarks>
    public const int DefaultMaximumPayloadBytes = 1 << 20;


    /// <summary>
    /// The next whole frame, header included, or null at a clean end of stream.
    /// </summary>
    /// <exception cref="V2GFramingException">
    /// on a malformed header, a payload longer than <see cref="DefaultMaximumPayloadBytes"/>, or a
    /// peer that closed mid-frame. All three are unrecoverable for a session peer, which is why none
    /// of them is a null.
    /// </exception>
    public async Task<byte[]?> ReadFrameAsync(CancellationToken cancel = default)
    {

        var header = new byte[V2GTP.HeaderSize];

        var read = await ReadAtLeastAsync(header, 0, header.Length, allowNothing: true, cancel);
        if (read == 0) return null;

        if (!V2GTP.TryReadHeader(header, out var payloadType, out var payloadLength))
            throw new V2GFramingException(
                "the 8 bytes where a V2GTP header should be are not one: " +
                Convert.ToHexString(header).ToLowerInvariant());

        if (payloadLength > (uint) maximumPayloadBytes)
            throw new V2GFramingException(
                $"a frame of payload type 0x{payloadType:x4} declares {payloadLength} payload bytes; " +
                $"this bridge forwards at most {maximumPayloadBytes}.");

        var frame = new byte[V2GTP.HeaderSize + (int) payloadLength];
        header.CopyTo(frame, 0);

        if (payloadLength > 0)
            await ReadAtLeastAsync(frame, V2GTP.HeaderSize, (int) payloadLength, allowNothing: false, cancel);

        return frame;

    }


    /// <param name="allowNothing">
    /// Whether zero bytes is an ending rather than a truncation. True only for the header's first
    /// byte: a peer may close between frames, and that is how a session ends normally — but a peer
    /// that closes *inside* one has cut a message in half.
    /// </param>
    private async Task<int> ReadAtLeastAsync(byte[] into, int offset, int count,
                                             bool allowNothing, CancellationToken cancel)
    {

        var read = 0;

        while (read < count)
        {

            var n = await source.ReadAsync(into.AsMemory(offset + read, count - read), cancel);

            if (n == 0)
            {
                if (read == 0 && allowNothing) return 0;

                throw new V2GFramingException(
                    $"the peer closed inside a frame (got {read} of {count} bytes).");
            }

            read += n;

        }

        return read;

    }

}


/// <summary>A byte stream that is not a sequence of V2GTP frames.</summary>
public sealed class V2GFramingException(String message) : Exception(message);
