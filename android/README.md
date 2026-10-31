# CloudStream for Android (phones and Android TV)

A new Android app with the Linux app's look and simplicity: not a fork of the official app. One APK serves
phones/tablets (touch: bottom bar, swipeable banner, gestures in the player) and Android TV (remote: icon
rail, focus ring, banner that follows the focused title). Plan and status: [../docs/ANDROID-PLAN.md](../docs/ANDROID-PLAN.md).

## Build

This is its own Gradle build (the Linux build in the repository root is untouched).

```bash
../scripts/fetch-upstream.sh      # the pinned CloudStream library sources (shared with the Linux app)
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease         # app/build/outputs/apk/release/app-<abi>-release.apk
```

Needs JDK 17+ and the Android SDK (platform 36). `-PappVersion=7.0.0` sets the version (CI passes the release tag).

## Release signing

Android only installs an update over an existing app when both are signed with the same key, so releases must always
use one key. It lives in GitHub Actions secrets, never in the repository. Create it once (keep the file and passwords
safe: if the key is lost, users have to uninstall to move to a new one):

```bash
keytool -genkeypair -v -keystore cloudstream-release.jks -alias cloudstream -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 cloudstream-release.jks > keystore.b64
```

Then in the repository: Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the contents of `keystore.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `cloudstream` |
| `ANDROID_KEY_PASSWORD` | the key password (if different from the keystore password) |

Locally, the same key is used when `CS_KEYSTORE`, `CS_KEYSTORE_PASSWORD`, `CS_KEY_ALIAS` (and `CS_KEY_PASSWORD`) are
set. Without them, release builds are signed with the machine's debug key (fine for testing, but they can't update or
be updated by builds from other machines).

## CI and releases

The `android` job in `.github/workflows/build.yml` builds the APKs on every push and pull request (artifact
`cloudstream-android`) and, for a tag such as `V7.0.0`, attaches them to the same release as the `.deb`, `.flatpak`
and `.tar`, named `cloudstream-android-<abi>.apk` so `releases/latest/download/…` links keep working. Install
instructions for every file are added to the release notes from `.github/release-install.md`.

APKs: `arm64-v8a` (most phones, newer TVs), `armeabi-v7a` (many Android TV boxes and Fire TV sticks),
`x86_64`/`x86` (emulators, a few Chromebooks) and `universal` (all of them, larger).

## Layout

- `cs-library/`: CloudStream's `library` module (the extension API) compiled for Android from `../upstream`,
  like the official app. NewPipe (jitpack-only) is left out; YouTube links are handed to VLC.
- `app/src/main/java/com/lagradost/cloudstream3/`: the app-module classes extensions link against
  (`CloudStreamApp`, `DataStore`, `plugins.Plugin`, `CommonActivity`), with upstream's names and signatures.
- `app/src/main/java/io/github/sherazi5110/cloudstream/`: the app. The plain-Kotlin parts are copied from the Linux
  app (`Engine`, `PluginManager`, `Repositories`, `Library`, `Catalogs`, `Network`, `StreamProxy`), adapted to
  Android. Extensions (`.cs3`) load natively with a `PathClassLoader`, as in the official app.
- `ui/`: Jetpack Compose screens (Home, Search, title page, My list, Extensions, player). `player/`: libVLC.

Nothing is minified: extensions call into the library and its dependencies by name at runtime.
