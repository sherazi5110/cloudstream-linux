# How CloudStream for Android works — reverse-engineering analysis

All references point into the upstream sources fetched by `scripts/fetch-upstream.sh`:

- `recloudstream/cloudstream` at commit `a7ac454` (22 Oct 2026), paths relative to `upstream/cloudstream/`
- `recloudstream/gradle` (the extension build plugin), paths relative to its repo root
- `recloudstream/TestPlugins` (the official extension template) and its published `builds` branch

## 1. The big picture

CloudStream contains **no content sources of its own**. It is a host for third-party extensions, plus a player:

```
 repo.json ──► plugins.json ──► Extension.cs3 ──► PathClassLoader ──► Plugin.load(context)
                                                                         │ registerMainAPI(...)
                                                                         ▼
     UI:  home / search ──► MainAPI.getMainPage / search ──► MainAPI.load ──► MainAPI.loadLinks
                                                                                  │ ExtractorLink(url, headers, referer, quality, type)
                                                                                  ▼
                                                                    ExoPlayer (androidx.media3)
```

Two modules matter:

| Module | Role | Evidence |
|---|---|---|
| `library/` | The **extension API**: `MainAPI`, `BasePlugin`, `ExtractorLink`, ~200 built-in extractors, the `app` HTTP client (NiceHttp/OkHttp). Kotlin Multiplatform with `android`, `jvm` and `web` targets. | `library/build.gradle.kts` lines 23-45 (`android {}`, `jvm()`), source sets `commonMain`, `jvmCommonMain`, `jvmMain` |
| `app/` | Android UI, plugin loader, repository manager, ExoPlayer integration, settings storage. | `app/src/main/java/com/lagradost/cloudstream3/plugins/*.kt` |

## 2. Repositories (`repo.json` → `plugins.json`)

`app/.../plugins/RepositoryManager.kt`

- **`Repository`** (line 34): `{ name, description, iconUrl, manifestVersion, pluginLists: [url, ...] }`.
- **`SitePlugin`** (line 50): one entry in a plugin list: `url` (the `.cs3`), `version`, `status` (0 down, 1 ok, 2 slow, 3 beta), `internalName`, `language`, `tvTypes`, `fileHash` (`sha256-<hex>`), …
- **`parseRepoUrl`** (line 132): accepts plain https URLs, `cloudstreamrepo://host/path` deep links and `https://cs.repo/…`, and short codes resolved through `cutt.ly/<code>` or `py.md/<code>` redirects.
- **`convertRawGitUrl`** (line 125): optionally rewrites `raw.githubusercontent.com` to the jsDelivr CDN.
- **`getRepoPlugins`** (line 183): fetch `repo.json`, then fetch every `pluginLists` entry in parallel.
- **`downloadPluginToFile`** (line 193): downloads to a temp file, **verifies the sha256** (line 216), then atomically moves it into place.
- The Android manifest registers the `cloudstreamrepo` URL scheme (`app/src/main/AndroidManifest.xml` line 201), so web links can add repos.

Installed plugins are stored under `filesDir/Extensions/<sanitized repo url>/<sanitized internalName>.cs3` (`PluginManager.kt` line 754).

## 3. What a `.cs3` file really is

Dissection of the official example published at `TestPlugins/builds/ExampleProvider.cs3` (sha256 `800296fa…5421f`, 7,311 bytes):

```
$ unzip -l ExampleProvider.cs3
      109  manifest.json
    10200  classes.dex                ← Dalvik bytecode (magic "dex\n035")
      896  res/drawable/ic_android_24dp.xml
     1120  res/layout/fragment_blank.xml
     1592  resources.arsc             ← compiled Android resources (only if requiresResources)

$ unzip -p ExampleProvider.cs3 manifest.json
{"requiresResources":true,"version":1,"pluginClassName":"com.example.ExamplePlugin","name":"ExampleProvider"}
```

So a `.cs3` is **a zip of Android DEX bytecode plus a manifest**. That is why it cannot run on a desktop JVM as-is.

How it is produced (`recloudstream/gradle`):

- `tasks/Tasks.kt` line 49: `compileDex` → `classes.dex` (D8, `CompileDexTask.kt` line 58).
- `CompileDexTask.kt` line 102: the plugin class is found by scanning for the `@CloudstreamPlugin` annotation.
- `tasks/Tasks.kt` line 121: `manifest.json` is generated from that class name, the project version and `requiresResources`.
- `WriteCacheEntryTask.kt` lines 58-74: writes the `plugins.json` entry, including `fileHash = sha256(cs3)`.
- **Cross-platform mode**: `CloudstreamExtension.kt` line 92 (`isCrossPlatform`) makes `CompilePluginJarTask` also publish a plain **JVM `.jar`**, and `EnsureJarCompatibilityTask` runs `jdeps` to reject jars that import `android.*`. The plugin list then also gets `jarUrl`/`jarHash` (`PluginEntry.kt` lines 20-22). Upstream is clearly preparing for desktop.

Extensions are compiled for **JVM 1.8 bytecode** (`TestPlugins/build.gradle.kts` line 60) against `compileOnly` stubs of the app (`cloudstream("com.lagradost:cloudstream3:pre-release")`, line 75). The library and Kotlin stdlib are provided by the host and never bundled.

## 4. Loading an extension

`app/.../plugins/PluginManager.kt`, `loadPlugin` (line 593):

1. Mark the file read-only (Android 14 refuses writable dex files), line 603.
2. `PathClassLoader(filePath, context.classLoader)` at line 611: the app's classes are the **parent**, so the extension links against the host's `library` classes.
3. Read `manifest.json` from the loader (line 613) and `loadClass(manifest.pluginClassName).newInstance()` (lines 632-634).
4. If `requiresResources`, create an `AssetManager` via reflection and `addAssetPath(cs3)` to give the plugin its own `Resources` (lines 645-656).
5. Call `plugin.load(context)` for `Plugin` subclasses, or `load()` for cross-platform `BasePlugin`s (lines 669-673).

Inside `load`, extensions call `registerMainAPI(provider)` / `registerExtractorAPI(extractor)` (`library/.../plugins/BasePlugin.kt` lines 20 and 31). These tag the object with `sourcePlugin = filename` and add it to the global `APIHolder.allProviders` / `extractorApis` lists. `unloadPlugin` (line 689) removes everything with that `sourcePlugin`.

The Android-only `Plugin` class (`app/.../plugins/Plugin.kt`) adds `load(Context)`, `resources` and `openSettings` (a settings screen, usually an Android `Fragment`).

## 5. The provider contract (`MainAPI`)

`library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt`, `abstract class MainAPI` (line 494):

| Method | Line | Called when |
|---|---|---|
| `getMainPage(page, MainPageRequest)` | 633 | Home screen, once per `mainPage` entry |
| `search(query, page)` / `search(query)` | 641 / 651 | Search screen; fanned out to all providers |
| `load(url)` → `LoadResponse` | 665 | Opening a title: Movie / TvSeries / Anime / LiveStream / Torrent |
| `loadLinks(data, isCasting, subtitleCallback, callback)` | 692 | Pressing play; streams `ExtractorLink`s and `SubtitleFile`s back via callbacks |

The UI wraps these with per-provider timeouts. For example `app/.../ui/APIRepository.kt` lines 204-213 wrap `api.loadLinks` in `withTimeout(api.loadLinksTimeoutMs)`.

Inside `loadLinks`, providers usually scrape an embed page and call `loadExtractor(url, …)` (`utils/ExtractorApi.kt` line 917). This dispatches to whichever registered `ExtractorApi` matches the host.

## 6. Playback

An `ExtractorLink` (`utils/ExtractorApi.kt` line 703) carries `url`, `referer`, `headers`, `quality`, `type` (`VIDEO`, `M3U8`, `DASH`, `TORRENT`, `MAGNET`, line 417) and optional `audioTracks`. `getAllHeaders()` (line 742) merges the referer into the headers.

The player is ExoPlayer (androidx.media3 1.9.3, `gradle/libs.versions.toml` line 40). It is built in `app/.../ui/player/CS3IPlayer.kt` (`ExoPlayer.Builder`, line 1096), with an `OkHttpDataSource` that applies the link's User-Agent and headers (lines 749-813). Subtitles from `subtitleCallback` are side-loaded into the same player.

## 7. Settings storage extensions use

`CloudStreamApp.getKey<T>()` / `setKey()` (`app/.../CloudStreamApp.kt`) are **`inline reified`** functions. Their bodies are compiled *into the extension*: they call `DataStore.getSharedPrefs(Context)` → `SharedPreferences.getString()` → `parseJson` (`app/.../utils/DataStore.kt` line 221). So any host must provide those exact classes and method signatures, not just similar ones.

## 8. What this means for a Linux port

| Android piece | Portable? | Linux replacement in this project |
|---|---|---|
| `library` (MainAPI, extractors, HTTP) | Yes, it already has a `jvm()` target | Compiled from upstream source (`cs-library/`) |
| Extension `.jar` (`isCrossPlatform`) | Yes, plain JVM | Loaded directly with a `URLClassLoader` |
| Extension `.cs3` (Dalvik) | No | Translated DEX → JVM bytecode with dex2jar, cached by sha256 |
| `Plugin`, `Context`, `SharedPreferences`, `Log`, `Toast`, `Base64`, `Uri`, `CloudStreamApp`, `DataStore` | No (Android/app classes) | Re-implemented with identical names/signatures (`android-shim/`) |
| Other Android UI classes (Fragments, Views, `R`) | No | Generated empty placeholder classes so extensions still load |
| ExoPlayer | No | mpv (or VLC/ffplay), given the same url/headers/referer/subs/audio |
| `AssetManager` resources | No | Not decoded; `resources` is an empty `Resources` |
| WebView (Cloudflare) | Upstream's own JVM `WebViewResolver` is `TODO` | Not supported yet |

Upstream itself has a `desktopApp/` Compose module that is currently a "Hello, World!" window (`desktopApp/src/jvmMain/kotlin/com/lagradost/cloudstream4/Main.kt`). The migration plan is in `COMPOSE.md` (KMP-only libraries, MVI). This project takes a practical route in the meantime.
