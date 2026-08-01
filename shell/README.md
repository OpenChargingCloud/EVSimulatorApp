# `shell/` — the Capacitor project

Where the three pieces become two applications: the web app in `app/`, the plugin in `capacitor/`,
and the generated `android/` and `ios/` projects they are built into.

```bash
cd shell
npm install
npx cap sync            # copy app/ into both projects and wire the plugin in
```

```bash
cd shell/android && ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```

```bash
cd shell/ios/App && xcodebuild -scheme App -destination 'generic/platform=iOS Simulator' build
```

Both produce a real artifact today: `android/app/build/outputs/apk/debug/app-debug.apk`, and an iOS
app bundle for the simulator. Neither has been run on a device — that is the exit criterion, and it
needs a phone and the Pi.

## Its own directory, not the repository root

Capacitor wants a project root with `package.json`, `capacitor.config.json` and the generated native
projects beside them. Putting that at the top would have made the repository look like a Capacitor
app that happens to contain four back ends, which is the wrong way round. `webDir` points at
`../app`, and `cap copy` follows it.

## What was edited in the generated projects

`cap add` produces these, and they are ours to keep. Three changes, each for a reason worth writing
down:

**`android/build.gradle` — the Kotlin plugin on the buildscript classpath.** The plugin module's
Android half is Kotlin, and AGP 8 has no Kotlin of its own, so whichever build compiles that module
has to supply the compiler. The version is declared in the `buildscript` block rather than in
`variables.gradle`, because that file is applied *after* the block that needs it.

**`android/settings.gradle` — `includeBuild('../../kotlin')`.** The plugin depends on
`cloud.charging.v2g:v2g-bridge` and `:v2g-session`, and nothing publishes those anywhere; they are
substituted from source. Without the line the build looks on Maven Central and fails at
`checkDebugAarMetadata`. The plugin's own `settings.gradle.kts` says the same thing for its standalone
build — only one of the two files is ever read, and which one depends on who is building.

**Nothing in `ios/`.** The SwiftPM integration found the plugin on its own once `Package.swift` sat
at the plugin package's root, which is where `cap sync` looks.

## Two names the tooling chose

`cap sync` derives the Swift package and product name from the npm package name, in PascalCase, and
writes it into `ios/App/CapApp-SPM/Package.swift` — a file marked DO NOT MODIFY and rewritten on
every sync. So `capacitor/Package.swift` declares itself
`OpenChargingCloudCapacitorEvSimulator`. The target, the class and `jsName` are unaffected.

The same file pins `platforms: [.iOS(.v15)]`, which is why `swift/Package.swift` declares iOS 15 and
not 16: **a package that asks for more cannot be a Capacitor plugin's dependency at all.** Nothing in
the Swift back end needs more — swift-certificates declares no floor of its own, and the newest
platform API it uses is `SecTrustCopyCertificateChain`, which is iOS 15.

## The web target

`app/` runs in a browser today, and most of what the application does works there: scanning, parsing,
the warnings, the refusals, the manual form. What does not is a session — `Capacitor.Plugins
.EvSimulator` is absent, and the app says so rather than failing quietly.

A Capacitor *web implementation* of the plugin is possible in principle and would need two things
this repository does not have: the EVCC state machines in TypeScript (only the codec is ported), and
a transport a browser can open. See `docs/CONCEPT.md` B1 for what a WebSocket transport would and
would not buy.

## A note on the npm advisory

`npm audit` reports one moderate advisory against `uuid`, reached through `@capacitor/cli`. It is
build-time tooling and ships in nothing; `audit fix --force` would move `@capacitor/cli` across a
major version. Left as it is, and named here rather than discovered later.
