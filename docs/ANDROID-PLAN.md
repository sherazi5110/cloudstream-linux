# Android app: approved plan (start here in a new chat)

The owner wants a **new Android app** (phones with touch + Android TV with a remote) with the same look,
features and simplicity as this Linux app, **not** a fork of the official CloudStream Android app (they find
its UI and settings too busy, and prefer this app's player). The design below was approved on 2026-10-31.

## Design (approved): `docs/android/`

- `mock-phone.png`: Home (swipeable banner, Continue Watching, rows with TMDB scores, bottom bar
  Home · Live TV · Search · My list · More; long-press a card for ✓ watched / ✕ remove) and the title page
  (TMDB + IMDb pills, one big Resume button, My list / Watched / Trailer / Sources, seasons and episodes).
- `mock-phone-player.png`: VLC player, landscape: Sources, Subtitles, Audio, Speed, Fit/Zoom, Next episode;
  left-side swipe = brightness, right-side = volume, double-tap sides = ±10 s, pinch = zoom, lock button.
- `mock-tv.png`: Android TV Home: the Linux icon rail, banner follows the focused title, focused card has a
  white ring and grows; arrows move, OK opens, Back goes back, Menu = ✓ / ✕.
- `mock-tv-live.png`: Android TV Live TV, same as Linux; CH+ / CH− switch channel while watching.

## Decisions

- **Same repository** (`sherazi5110/cloudstream-linux`), in its own folder **`android/`** (a separate Gradle build,
  so the Linux build and CI are untouched). The `.apk` is built by a new job in `.github/workflows/build.yml` and
  attached to the **same GitHub release** as the `.deb`, `.flatpak` and `.tar` (one tag, e.g. `V7.0.0`, ships all).
  App name "CloudStream" with the CS logo (`packaging/linux/cloudstream-linux.svg`), application id
  `io.github.sherazi5110.cloudstream` (installs next to the official app). GPL-3.0 like CloudStream.
- Shared logic: start by copying the plain-Kotlin files into `android/` and adapting them; later, if both apps
  stay in step, move them into a shared Gradle module used by both builds.
- Kotlin + Jetpack Compose (phone) and Compose for TV (`androidx.tv`), one app with both layouts
  (`android.software.leanback` not required, `android.hardware.touchscreen` not required, TV banner 320×180,
  LEANBACK_LAUNCHER intent).
- Player: **libVLC for Android** (`org.videolan.android:libvlc-all`, on Maven Central) with this app's controls.
- Extensions load natively (DexClassLoader, like the official app); the CloudStream library is built from the
  pinned upstream source as in this repo (`scripts/fetch-upstream.sh`, `cs-library`), since JitPack is not reachable.
- Port the plain-Kotlin logic from this app: `Profiles.kt` (profiles, kids filter, parent PIN, NSFW setting),
  `Stremio.kt`, `LiveTv.kt` (+ `ChannelLogos`), `Metadata.kt` (TMDB/Cinemeta/AniList), `Library.kt`
  (history, mark watched), `Catalogs.kt`, `OnlineSubtitles.kt`, `StreamProxy.kt` (likely not needed with libVLC
  headers). Replace `Platform.context` prefs with Android SharedPreferences/DataStore.
- Phases: (1) Home, Search, title page, VLC player, extensions, phone + TV; (2) profiles, My list, Live TV,
  Stremio, TMDB, Settings, themes; (3) polish, the `.apk` job in CI, signed release builds
  (a release keystore kept as GitHub Actions secrets; never committed), README and INSTALL sections for Android.

## Environment

- The Android SDK downloads from `dl.google.com`: it must be allowed in the cloud environment's network settings
  (it was blocked in the session that wrote this plan). `maven.google.com` and Maven Central were reachable;
  `jitpack.io` and `artifacts.videolan.org` were not.
- The owner tests by installing the `.apk` on a phone and/or Android TV and sends screen recordings.

## Status

### Phase 1 (built 2026-10-31, `android/`, version 7.0.0-alpha1)

See [android/README.md](../android/README.md) for building and the code layout.

- **Build:** separate Gradle build in `android/` (AGP 8.13, Kotlin 2.4.0, Gradle 8.14.4, compileSdk 36, minSdk 23).
  `cs-library` compiles upstream's `library` for Android from `../upstream` (Kotlin Multiplatform + the Android
  KMP library plugin), with `androidMain` + `jvmCommonMain` (minus NewPipe). Compose BOM 2026.03.00 (Compose
  1.10): later BOMs need compileSdk 37 and AGP 9.1.
- **Extensions:** `.cs3` loaded with a `PathClassLoader` whose parent is the app (as upstream), read-only for
  Android 14+, plugin resources via `AssetManager.addAssetPath`. The app class is
  `com.lagradost.cloudstream3.CloudStreamApp` (upstream's name), and `DataStore`, `plugins.Plugin`,
  `CommonActivity`, `AcraApplication` keep upstream's signatures. Repositories by URL, `cloudstreamrepo://`
  (also opened from links), shortcode; install, update, uninstall, install all, install from a `.cs3` file.
- **Screens:** Home (swipeable banner with backdrop/plot loaded per title, extension picker, Continue
  Watching with long-press ✓/✕, catalog rows, "See all" with endless paging), Search (all extensions, streamed),
  title page (Resume button, My list, Watched, Trailer, Sources, seasons/dubs, episodes with progress and
  long-press watched/not watched, More like this), My list + History, Extensions, More (layout Auto/Phone/TV).
- **TV:** icon rail, banner follows the focused card, white ring + grow on focus, Menu key = ✓/✕ on Continue
  Watching, OK/←/→ in the player (play-pause, ±10 s), media keys.
- **Player (libVLC 3.6.5):** loading screen (source count, Play now, Choose source, Cancel), auto-play at the
  preferred quality, failover to the next source, Sources / Subtitles (embedded + extension) / Audio / Speed /
  Zoom (Fit, Fill, Stretch, Original, 16:9, 4:3), Next episode, clock + "ends at", lock; phone gestures
  (brightness, volume, double-tap ±10 s, pinch zoom on a TextureView). Headers other than Referer/User-Agent
  go through `StreamProxy` (socket-based port of the Linux one). Progress saved every 10 s and on exit.
- **APKs:** one per ABI (arm64-v8a ~42 MB, armeabi-v7a ~39 MB, x86/x86_64) plus universal (~106 MB);
  libVLC dominates. Not minified. Release builds are signed with the debug key until phase 3.

Not in phase 1 (by plan): profiles, Live TV, Stremio add-ons, TMDB/IMDb/AniList, Settings and themes, online
subtitles (phase 2); CI job, release keystore, README/INSTALL sections (phase 3). Not done yet either: the
`androidx.tv` library is not used (focus handling is plain Compose), Cast, downloads, extension settings screens.
