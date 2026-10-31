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

Needs JDK 17+ and the Android SDK (platform 36). Release builds are signed with the debug key until the
release keystore is set up in phase 3.

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
