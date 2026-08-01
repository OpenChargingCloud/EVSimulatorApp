# `ev-ws-bridge` — WebSocket to TCP

Forwards WebSocket connections to a station over TCP, **one V2GTP frame per message**, so a browser —
which cannot open a TCP socket — can drive and watch a session (`docs/CONCEPT.md` B1).

```bash
dotnet run --project tools/EVSimulatorApp.WsBridge -- --allow 192.168.4.1:15118
```

```
ws://127.0.0.1:9000/?host=192.168.4.1&port=15118
```

```
--listen <address:port>   where to accept connections            (default 127.0.0.1:9000)
--allow  <host:port>      a station that may be reached; repeatable
--tls                     speak TLS to the station
--root   <sha256-hex>     the station root to pin. Required with --tls.
```

## A bridge, not a listener in the SECC

The station is unmodified — ours, Josev's, or a real one — and what crosses the wire to it is exactly
what an EVCC would have sent. A WebSocket listener grafted into a SECC would be a second transport
inside the implementation being tested; this is a second transport in front of it.

**What crosses to the browser is not ISO 15118 on the wire.** V2GTP-over-WebSocket is a development
and inspection transport. Conformance evidence it is not, and nothing produced through it should be
filed as such.

## One frame per message

This is the whole reason the bridge is more than a byte pump.

TCP is a stream: a read returns whatever has arrived, which may be half a frame or three of them.
WebSocket is message-framed: a receive returns exactly what a send sent. So the station→browser
direction has to **re-frame**, and the only thing that says where a frame ends is V2GTP's own 8-byte
header — which is what `V2GFrameReader` reads. Without it a browser would receive arbitrary chunks
and have to do the framing itself, in the place least able to test it.

`BridgeTests` drives all four recorded EIM sessions through the bridge against a station that answers
**three bytes at a time**, and requires one message per recorded frame, each holding the whole frame.
Replacing the reader with the obvious byte pump keeps every byte intact and fails all four.

## What a browser may reach through it

A WebSocket server has no same-origin protection: once this is listening, any page the browser
happens to load can open a connection. If the target came straight out of the query string, this
would be an open proxy into whatever network it stands on, reachable by any tab.

So the target is subject to the same rule the confirmation sheet applies to a scanned code, and it is
literally the same code — `PairingWarnings.IsPrivateTarget`. A private or link-local address, or a
`.local` name, and nothing else. `--allow` narrows it further to exact host/port pairs. The listener
binds to loopback unless told otherwise, and every connection — accepted or refused — is logged with
its target and its `Origin`.

None of that makes it safe to run on a machine full of other people's services. It makes it a
development tool with one lock rather than none.

## `--tls` is where the bridge earns its keep

A browser validates `wss://` against its own trust store and gives JavaScript no way to see the
chain, so a web front end **cannot** honour the pairing code's `root` fingerprint — the check the
Kotlin and Swift transports apply where the socket opens.

Terminating TLS here puts it back: the chain is rebuilt with the pinned certificate as the only
anchor (`X509ChainTrustMode.CustomRootTrust`), not against the platform store, because a V2G root is
not a web CA. `--tls` without `--root` is refused at startup — a TLS session nobody validated is a
plaintext session with extra steps.

The browser's own hop is `ws://` on loopback. Making that `wss://` would need a certificate the
browser trusts for `127.0.0.1`, which is a separate problem and not solved here.

## What is still missing for a web target

The transport is only half of it. A session in a browser also needs the EVCC state machines in
TypeScript, and they exist in three languages, not four — only the codec is ported. Until then a
Capacitor web implementation could replay recorded sessions but not drive a live one, which is still
useful for the inspector and for Chargy, and is a much smaller job.
