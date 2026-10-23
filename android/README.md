# AgentPilot Android

Native Android client for AgentPilot.

## Stack

- Kotlin
- Jetpack Compose
- Material 3
- Android Gradle Plugin

## Open In Android Studio

1. Install Android Studio.
2. Open this `android/` directory.
3. Let Android Studio install the requested Android SDK and sync Gradle.
4. Connect an Android phone with USB debugging enabled.
5. Run the `app` configuration.

## Install 2.3.2

Download the Android APK from the [release download table](../README.md#下载与入口).
The client supports Android 8.0+ (API 26), sessions, devices, runtime configuration,
approvals, files and terminals through the v2 API. No server is bundled: enter
your own service address in the login flow. A private build can pin a default
address with the `agentPilot.officialServerUrl` Gradle property, which also
enables the official-cloud entry; see
[Private build vs public release](#private-build-vs-public-release).

## Build from the command line

Use JDK 17 and the Android SDK matching `compileSdk` in `app/build.gradle.kts`
(currently 36). Set `ANDROID_HOME` or the local SDK path in ignored
`local.properties`, then run from this directory:

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

On Windows use `gradlew.bat`. Outputs are under `app/build/outputs/apk/`.
The checked-in release build type does not define a signing configuration;
`assembleRelease` alone does not produce a distributable signed update. Apply the
release signing process with the existing keystore and keep that identity for
future updates. Do not commit passwords or keystores. Changing the signature
prevents an in-place update of an installed app signed with another key.

`local.properties` remains machine-specific and ignored. The manifest allows
HTTP for local/self-hosted addresses; use HTTPS for public service endpoints.

### Private build vs public release

`BuildConfig.OFFICIAL_SERVER_URL` decides whether the app starts with a pinned
server and shows the official-cloud entry. Two build intents share this project:

- **Public release** — `assembleRelease`, and `assembleDebug` with no extra
  property, ships with **no server pinned**. Users type their own address in the
  login flow. Never publish such an artifact with a private endpoint baked in.
- **Private/self-use build** — pin your own address explicitly per invocation:

```bash
./gradlew assembleDebug -PagentPilot.officialServerUrl=https://your-host
```

The pinned address comes from that Gradle property **only**.
`local.properties` is deliberately not read for it: it is machine-local and
gitignored, so relying on it would bake whatever one workstation happens to hold
into any artifact built there, including one meant for publication.

To confirm an artifact is safe to publish, check the generated file:

```bash
grep OFFICIAL_SERVER_URL \
  app/build/generated/source/buildConfig/release/com/agentpilot/app/BuildConfig.java
```

It must read `""`.

## Application updates

After entering the signed-in app, Android reads the saved server's
`/api/v2/health` and compares its `version` with `BuildConfig.VERSION_NAME`
numerically. This also runs on launch with an existing login and when the saved
server changes. Login screens and sessions without a saved server never check
for updates; no default server is substituted. Signing out cancels pending
checks and downloads and clears the visible update state.
`AppConfig.UPDATE_DOWNLOAD_URL` is the fixed APK address; the operator hosts the
APK at that URL on their server (e.g. behind an nginx alias) and points the
constant at it. The filename encodes the version and must match
`BuildConfig.VERSION_NAME` so each release has its own URL.

**Ignore this version** saves the target version per server in the private
`app-updates` SharedPreferences file. The same version stays quiet on later
launches; a newer version prompts again. Settings keeps a download icon with a
red dot beside **Check for updates** whenever a newer version exists. Tapping it
reopens the dialog even for an ignored version. Outside taps and Back do not
close the prompt. Downloads show progress and continue through the existing APK
installer flow.
