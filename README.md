# CloudStream for Linux

Run **CloudStream extensions on Ubuntu/Linux**, from the same repositories the Android app uses, with no changes to the extensions.

- **Same extensions:** add repositories by URL, `cloudstreamrepo://` link or shortcode. Android `.cs3` files are translated from Dalvik to JVM bytecode automatically; cross-platform `.jar` builds are used directly.
- **Fast, streaming UI:** home sections, search results and video sources appear as each one arrives. A slow or dead site never blocks the rest, and the window opens immediately while extensions load in parallel.
- **Built-in player, VLC inside the app:** libVLC decodes (every format VLC plays, hardware-accelerated when available) and the video is drawn inside the app window, so it works on X11, Wayland and ChromeOS alike. It has its own controls:
  - switch sources mid-playback, keeping your place; a failed source falls back to the next one automatically;
  - subtitles from the extension, **from the internet (OpenSubtitles, no account)** or from a file, with timing adjustment;
  - audio language, speed 0.25–2×, and zoom (Fit, Fill, Stretch, Original, 16:9, 4:3, 21:9);
  - a seek bar with time preview, volume up to 150%, next episode, full screen and keyboard shortcuts.

  A header proxy lets streams that need tokens or cookies play. Battery saver suits low-end PCs. mpv, VLC and ffplay are still available as separate windows.
- **Catalogs your way:** for each extension, turn its home catalogs on or off and reorder them (drag or arrows), or **combine several extensions into one feed**.
- **Loading screen:** shows artwork, a live source count, *Play now* (at your preferred quality), *Choose source* and *Cancel*.
- **Your look:** Dark, AMOLED black, Midnight blue and Light themes, seven accent colours or any hex colour, and your own wallpaper behind the app with dim and blur.
- **Library:** bookmarks (My List), history, per-episode progress, *Resume* and *Continue Watching*.
- **Network:** DNS-over-HTTPS (Cloudflare, Google, AdGuard, Quad9…) with automatic fallback to system DNS, persistent cookies, IPv4-first, and an HTTP cache.
- **Developer tools:** per-extension test (home → search → details → sources), a static Linux compatibility check, a log viewer, and storage/cache management.
- A full CLI is included for scripting.

| | |
|---|---|
| ![Home](docs/screenshots/home.png) | ![Details](docs/screenshots/details.png) |
| ![Player](docs/screenshots/player.png) | ![Sources in the player](docs/screenshots/player-sources.png) |
| ![Online subtitles](docs/screenshots/player-online.png) | ![Subtitle on screen](docs/screenshots/player-subtitle.png) |
| ![Zoom](docs/screenshots/player-zoom.png) | ![Loading](docs/screenshots/loading.png) |
| ![Home manager](docs/screenshots/home-manager.png) | ![Catalog editor](docs/screenshots/home-catalogs.png) |
| ![Combined feed](docs/screenshots/home-feed.png) | ![Extensions](docs/screenshots/extensions.png) |
| ![Settings](docs/screenshots/settings.png) | ![Light theme](docs/screenshots/theme-light.png) |

*(Screenshots use the offline demo extension in `tests/DemoProvider`.)*

See **[docs/ANALYSIS.md](docs/ANALYSIS.md)** for how the Android app works, and **[docs/PLAYER-AND-CATALOGS.md](docs/PLAYER-AND-CATALOGS.md)** for how the built-in player, online subtitles and catalogs work (VLC source, the other Linux app, and this code), all with file and line references.

## How it works

```
repo.json → plugins.json → Extension.cs3 ──dex2jar──► JVM classes ─┐
                         → Extension.jar (isCrossPlatform) ────────┤
                                                                   ▼
          PluginClassLoader ── parent ──► cs-library   (CloudStream's own `library`, compiled for JVM)
                                         android-shim  (Context, SharedPreferences, Plugin, CloudStreamApp, Log, Base64, Uri…)
                             ── last resort ──► AndroidStubLoader (empty placeholders for UI-only Android classes)
          Plugin.load(context) → registerMainAPI(...) → getMainPage / search / load / loadLinks → built-in player (libVLC)
```

| Module | What it is |
|---|---|
| `cs-library/` | Builds upstream `library/` (`commonMain` + `jvmCommonMain` + `jvmMain`) and NiceHttp **from source**. Extensions link against the exact classes they were compiled for. |
| `android-shim/` | Linux implementations of the Android and app-module classes extensions call, with identical names and signatures. Extensions inline `getKey<T>()`, so signatures must match exactly. |
| `app/` | Repository manager, `.cs3` translator, parallel plugin loader, streaming engine, network (DoH/cache), header proxy, online subtitles, catalogs, mpv IPC bridge, library store, CLI, and the JavaFX UI (`ui/`, including the libVLC player). |
| `examples/InternetArchiveProvider/` | A complete extension (public-domain films from archive.org), built **both** as a real Dalvik `.cs3` and as a `.jar`, plus a ready-to-host `repo.json`. |
| `tests/` | Offline demo extension (series, slow host, dead host, token-protected HLS), mock site (with stand-ins for the subtitle services), fake mpv — used by the tests and screenshot tour. |

## Install (Ubuntu 22.04+)

```bash
sudo apt install openjdk-21-jdk git libvlc5 vlc-plugin-base   # + mpv if you want the mpv player
git clone <this repo> cloudstream-linux && cd cloudstream-linux
./scripts/fetch-upstream.sh        # pinned CloudStream + NiceHttp sources
./scripts/install.sh               # ~/.local install, app-menu entry, cloudstreamrepo:// handler
```

Or build a self-contained `.deb` with a bundled Java runtime:

```bash
./scripts/build-deb.sh                 # build/cloudstream-linux_<version>_amd64.deb
./scripts/uninstall-user-install.sh    # only if you used install.sh before (keeps your data)
sudo apt install ./build/cloudstream-linux_4.0.0_amd64.deb   # pulls in libVLC
# remove later with: sudo apt remove cloudstream-linux
```

## Use

Run `cloudstream-linux` (or open **CloudStream** from the app menu). Go to **Extensions**, paste a repository link, install extensions, then use **Home** and **Search**. Shortcuts: `/` or `Ctrl+F` to search, `Esc` to go back, `F11` for full screen.

In the player: `Space`/`K` pause, `←/→` seek 10 s (`Shift` 1 min), `↑/↓` volume, `M` mute, `F` full screen, `S` sources, `C` subtitles, `A` audio language, `Z` zoom, `[`/`]` speed, `G`/`H` subtitle timing, `0`–`9` jump, `N` next episode, `Esc` back. Click pauses, double-click is full screen, the wheel changes volume.

On Home, **Customize** picks the extension (or a combined feed) and, with the tune button, which catalogs show and in what order.

If a site doesn't load, try **Settings → Network & DNS**. If an extension misbehaves, **Settings → Developer & logs → Test** shows at which step it fails. Include that, together with the log, in bug reports.

CLI:

```bash
cloudstream-linux repo add https://example.com/repo.json     # or cloudstreamrepo://…, or a shortcode
cloudstream-linux plugins                                    # what your repos offer (* installed, U update)
cloudstream-linux install <InternalName>|all
cloudstream-linux update
cloudstream-linux sideload ./SomeExtension.cs3
cloudstream-linux providers
cloudstream-linux home "<Provider>"
cloudstream-linux search "big buck bunny"
cloudstream-linux info  "<Provider>" <url>
cloudstream-linux links "<Provider>" <url> --item 3
cloudstream-linux play  "<Provider>" <url> --item 3 --player mpv
cloudstream-linux key set <key> <value>                     # extension settings (see below)
cloudstream-linux doctor                                    # diagnostics to include in bug reports
```

Data lives in `~/.local/share/cloudstream-linux` (override with `CLOUDSTREAM_HOME`). It uses the Android layout: `files/Extensions/<repo>/<plugin>.cs3` and a `prefs/` key store.

## Compatibility notes

- **Works:** extensions that use the CloudStream API (`MainAPI`, `app.get/post`, Jsoup, Jackson, built-in extractors, `loadExtractor`), `CloudStreamApp.getKey/setKey`, `android.util.Log/Base64`, `android.net.Uri`, `Toast`.
- **Settings screens:** an extension's `openSettings` is an Android Fragment and can't be shown. Most extensions read their settings via `getKey`, so set them with `cloudstream-linux key set`.
- **`requiresResources`:** Android resource tables aren't decoded; such extensions get an empty `Resources`.
- **WebView-based extensions** (e.g. Cloudflare challenges) don't work yet. Upstream's own JVM `WebViewResolver` is still unimplemented; the Sources panel says so when this is the cause.
- Logs are in `~/.local/share/cloudstream-linux/logs/` (Settings → Storage → Logs); please attach `cloudstream.log` and `cloudstream-linux doctor` output to bug reports.
- **YouTube** links: VLC's own YouTube support breaks often; choose the mpv player (Settings → Playback), which resolves them with yt-dlp. Upstream uses NewPipe, which is only published on JitPack.
- **Torrents** need `webtorrent-cli`.
- Any other Android class an extension touches gets a placeholder. The extension loads, but calling that code throws. Run with `CS_LOG_LEVEL=3` for debug logs.

## Write an extension that runs on both Android and Linux

Use `examples/InternetArchiveProvider` as a template. It is ordinary CloudStream extension code (`@CloudstreamPlugin class … : Plugin()`).

```bash
./gradlew :examples:InternetArchiveProvider:makeRepo -PrepoBaseUrl=https://you.github.io/repo
# build/repo/{repo.json, plugins.json, InternetArchive.cs3, InternetArchive.jar}
```

In a real recloudstream/gradle project, set `cloudstream { isCrossPlatform = true }` so your repo also publishes a `.jar`.

## Development

```bash
./gradlew :app:installDist && app/build/install/cloudstream-linux/bin/cloudstream-linux
./gradlew :app:test                 # extensions, Android shims, sources with a dead host, header proxy, online subtitles, catalogs, libVLC playback, mpv IPC
python3 -I tests/mock-archive/server.py 8766   # local mock site for the demo/example extensions

# Scripted screenshot tour (any X display or xvfb-run):
CS_SCREENSHOT=/tmp/shot CS_SCREENSHOT_SEARCH=pixel cloudstream-linux   # player shots need libVLC and xdotool
node scripts/render-icons.js        # re-render the app icons from packaging/linux/cloudstream-linux.svg
```

CI (`.github/workflows/build.yml`) runs the tests on every push and publishes the `.deb` and tarball for every `v*` tag.

The upstream versions are pinned in `scripts/fetch-upstream.sh` (`CS_COMMIT`, `NICEHTTP_TAG`).

## License

GPL-3.0, the same as CloudStream, whose library sources this project compiles.

The built-in player uses [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL-2.1, from your system's VLC packages) through [vlcj](https://github.com/caprica/vlcj) (GPL-3.0). Online subtitles come from the OpenSubtitles Stremio addon. The mpv player's UI is [uosc](https://github.com/tomasklaen/uosc) 5.13.0 (LGPL-2.1), bundled in `app/src/main/resources/mpv/scripts/uosc` with two small robustness fixes listed in `VENDORED.txt`.

Made by Tennant Creek.
