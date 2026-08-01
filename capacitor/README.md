# `capacitor/` — the adapter

The Capacitor plugin that carries the ISO 15118 session runner's three commands in and its event
stream out (`docs/CONCEPT.md` B1). **Done: all three halves compile and are tested — the TypeScript
adapter against a stand-in native side, the Android module against the real `JSObject`, and the iOS
module against the real Capacitor framework.**

```
capacitor/
  src/          the TypeScript adapter — registerPlugin, and the difference between the
                contract and what the bridge really carries
  android/      an Android library module (com.capacitorjs:core 8.5.0)
  ios/          a SwiftPM package (capacitor-swift-pm 8.5.0)
```

## Run

```bash
cd capacitor && npm install && npm test
```

```bash
cd capacitor/android && ANDROID_HOME=~/Library/Android/sdk gradle testDebugUnitTest
```

```bash
cd capacitor/ios && xcodebuild -scheme EvSimulatorPlugin -destination 'generic/platform=iOS Simulator' build
```

Each needs its own toolchain, which is why none of them is part of `kotlin/`, `swift/` or
`typescript/`. Those three still build and test with nothing but a JDK, a Swift toolchain and Node —
a property the project keeps deliberately, next to *`dotnet test` needs no C toolchain, no Java and
no network*. An Android library needs the Android SDK; anything linking Capacitor can only be built
for iOS. Both arrive here as separate builds that include the shared sources from source, so there is
no copy to drift.

## The adapter is transport, and nothing else

There is no session logic in these three files and there should never be. They are the only files in
the project that no test on a laptop can exercise *as they actually run*, so anything decided here is
decided where nothing checks it. What a session is, what an event is and what a configuration may say
all live in `v2g-bridge` / `V2GBridge`, where four back ends agree on them character for character.

What is genuinely the adapter's own is two translations, and both are in a `BridgeCodec` with no
Capacitor types in its signatures, so a unit test can reach them.

## An event crosses as text

The one real decision here, and it was made by measuring rather than by taste.

Capacitor's `notifyListeners` takes a dictionary. Building one means going through the platform's
JSON library — `org.json.JSObject` on Android, `JSONSerialization` on iOS — and both are hash-map
backed, so neither preserves member order. Measured over all 196 events of
`Vectors/Bridge.events.json`:

| | events changed by a round trip |
|---|---|
| Android, `com.getcapacitor.JSObject` | **196 of 196** |
| iOS, `Foundation.JSONSerialization` | **196 of 196**, in a different order from Android's |

Neither library is wrong. Member order is semantically insignificant in JSON and both are entitled to
drop it. But the JSON-LD documents in this repository are pinned as **text** across four back ends —
`@context` first, `@type` second, properties in schema order — so a document that arrives in a third
order on Android and a fourth on iOS is no longer the document the corpus agreed on. An inspector
would render one message three ways; anything hashing it would get three hashes.

So the string the back ends already agree on crosses unaltered, and `JSON.parse` — whose object key
order the language specification fixes — rebuilds it on the far side. One JSON implementation in the
path instead of three.

Both measurements are kept as tests rather than as claims in a comment:
`EvSimulatorPluginTest.an event does not survive being marshalled as a JSObject` and
`SessionConfigTests.testAnEventDoesNotSurviveJSONSerialization`. If a future platform library ever
did preserve order, the text payload would become ceremony rather than protection, and those are
where it would be noticed.

## A configuration crosses as an object, nested under its own key

The asymmetry is deliberate. An event *contains* a JSON-LD document whose member order is pinned; a
configuration is eight flat properties read by name, where order means nothing and there is nothing
nested to lose.

The nesting is not cosmetic: Capacitor puts its own `callbackId` into a call's options, and
`SessionConfig` **refuses unknown properties** — deliberately, since a setting the user saw on the
confirmation sheet and silently did not take effect is the failure the whole confirm-before-connect
design exists to prevent. A flat call would fail on Capacitor's own key, on a phone.

## Installing a session runner

`start` needs something that turns an approved configuration into events. That is a `SessionRunner`,
installed by the host application rather than constructed here, because where the frames come from is
a packaging question:

```kotlin
EvSimulatorPlugin.runner = TraceSessionRunner({ config -> traceAsset(config) })
```

```swift
EvSimulatorPlugin.runner = TraceSessionRunner(trace: { config in traceResource(config) })
```

`TraceSessionRunner` replays a recorded session, which makes the whole path — from the WebView's
command to the events it renders — demonstrable on a real phone with no station in the room. The
live runner, which opens a socket and drives `Evcc2`, is B1's session half and is not here yet.

## The name is repeated three times

`@CapacitorPlugin(name = "EvSimulator")` on Android, `jsName` on iOS, and
`registerPlugin("EvSimulator")` in TypeScript. Nothing checks that the three agree, which is why each
appears exactly once per platform and nowhere else.
