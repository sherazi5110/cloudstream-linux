# CloudStream for Linux and Android

Run **CloudStream extensions and Stremio add-ons on Linux, Android phones and Android TV**, from the same repositories the
official Android app uses, with no changes to the extensions.

**[Download](https://github.com/sherazi5110/cloudstream-linux/releases/latest)** · **[How to install (.deb, Flatpak, .apk)](INSTALL.md)**

| Linux | Android |
|---|---|
| `.deb` for Ubuntu, Debian, Mint, ChromeOS · `.flatpak` for any distro · `.tar` with your own Java | `.apk` for phones, tablets, Android TV and Fire TV (in testing) |

> **Android app (in testing):** a new app for **phones (touch)** and **Android TV (remote)** with this app's look,
> profiles, Live TV, Stremio add-ons, TMDB and the VLC player. [More below](#android-app-in-testing).

- **New in 7.0 (Linux and Android):**
  - **Downloads** for watching offline: *Download* on a movie, a download button on each episode (or *Download these* for a
    whole season). Direct files and HLS streams (joined into one file, AES-128 decrypted), with subtitles saved next to
    them. Downloads resume after a pause, a restart or a dropped connection, and a downloaded episode plays from the file
    even when you're offline. Library → **Downloads** lists them. Linux saves to `~/Downloads/CloudStream` (changeable in
    Settings → Storage); Android keeps a notification with progress while it downloads.
  - **Skip intro** for anime (times from the community AniSkip database): *Skip intro*, *Skip recap* and *Skip credits*
    buttons, or skip automatically (Settings → Playback).
  - **Next-episode countdown:** at the credits (or 20 seconds before the end) a card counts down to the next episode, with
    *Play now* and *Cancel*.
  - **TV guide grid** for Live TV: channels down the side, times across the top, a line at "now"; click a programme to
    watch the channel.
  - **"Wrong match?"** on title pages: when ratings or artwork belong to a different film, search TMDB and pick the right
    one (or *No match*).
  - The Flatpak moves to the Freedesktop **25.08** runtime.
- **New look (6.0):** a slim icon rail, a clean top bar (source picker, search, your profile) and a full-width featured banner with backdrop, rating, "#1 in …", plot, *Play* and *More Info*. A new logo (a big C with an s inside, over a cloud) whose letters follow your theme colour.
- **Live TV (6.0.1):** a Live TV page of its own (below Home in the rail) for IPTV: **M3U playlists with XMLTV guides**, **Xtream Codes** logins, and **Stremio / Nuvio TV add-ons** (add-ons with TV channel catalogs move here automatically) and playlist files. Channel logos come from the playlist, or from the open iptv-org database when missing. What's on now and next, categories from the playlist's groups, favourites, recently watched, channel search and a country filter. Live TV sources never appear on Home.
- **Metadata & integrations (6.0.1):** add your free **TMDB** API key for ratings on posters, wide backdrops and title logos, cast with photos, trailers and similar titles on every extension; **IMDb ratings via Cinemeta** and **AniList** scores for anime need no key.
- **Watched controls (6.0.1):** mark films and episodes as watched (or not), and remove titles from Continue Watching, right from the card, My list, history or the title page.
- **Profiles:** "Who's watching?", twelve built-in avatars in eight colours (or your own picture), an optional PIN, and each profile's own history, list, home layout, theme and player settings.
- **Adult content (6.0.1):** like the Android app, NSFW extensions and titles are hidden until you turn on *Show adult (NSFW) content* in Settings → Profiles (per profile, confirmed, parent PIN if set; never in kids profiles).
- **Kids profiles:** only the extensions you allow (NSFW never), chosen types (e.g. cartoons and anime), a maximum age rating and blocked words; no Settings or Extensions pages, and a PIN keeps grown-up profiles locked.
- **Stremio add-ons:** install any add-on by its manifest address (or `stremio://` link). Its catalogs appear on Home, in Search and in the catalog editor; details come from the add-on or Cinemeta; streams from every add-on that has them play in the built-in player (torrent-only add-ons aren't supported).
- **Same extensions:** add repositories by URL, `cloudstreamrepo://` link or shortcode. Android `.cs3` files are translated from Dalvik to JVM bytecode automatically; cross-platform `.jar` builds are used directly.
- **Fast, streaming UI:** home sections, search results and video sources appear as each one arrives. A slow or dead site never blocks the rest, and the window opens immediately while extensions load in parallel.
- **Built-in player, VLC inside the app:** libVLC decodes (every format VLC plays, hardware-accelerated when available) and the video is drawn inside the app window, so it works on X11, Wayland and ChromeOS alike. It has its own controls:
  - switch sources mid-playback, keeping your place; a failed source falls back to the next one automatically;
  - subtitles from the extension, **from the internet (OpenSubtitles, no account)** or from a file, with timing adjustment;
  - audio language (picked automatically if you set a preference), an **equalizer**, speed 0.25–2×, and zoom (Fit, Fill, Stretch, Original, 16:9, 4:3, 21:9);
  - subtitle styling (size, colour, outline, background box) with a live preview;
  - a seek bar with time preview, a clock with "ends at" time, **screenshots**, volume up to 200%, next episode, full screen and keyboard shortcuts.

  A header proxy lets streams that need tokens or cookies play. Battery saver suits low-end PCs.
- **Catalogs your way:** for each extension, turn its home catalogs on or off and reorder them (drag or arrows), or **combine several extensions into one feed**.
- **Loading screen:** shows artwork, a live source count, *Play now* (at your preferred quality), *Choose source* and *Cancel*.
- **Your look:** **Liquid glass**, Dark, AMOLED black, Midnight blue and Light themes, nine one-click presets, any accent colour, and your own wallpaper behind the app with dim and blur. A featured slider on Home and an **anti-spoiler mode** for episodes.
- **Upkeep:** an update check, new site addresses for extensions whose site moved, and reset options.
- **Library:** bookmarks (My List), history, per-episode progress, *Resume* and *Continue Watching*.
- **Network:** DNS-over-HTTPS (Cloudflare, Google, AdGuard, Quad9…) with automatic fallback to system DNS, persistent cookies, IPv4-first, and an HTTP cache.
- **Developer tools:** per-extension test (home → search → details → sources), a static Linux compatibility check, a log viewer, and storage/cache management.
- A full CLI is included for scripting.

| | |
|---|---|
| ![Downloads in the Library](docs/screenshots/library-downloads.png) | ![Download buttons on episodes](docs/screenshots/details-downloads.png) |
| ![Skip intro](docs/screenshots/player-skip.png) | ![Next-episode countdown](docs/screenshots/player-next.png) |
| ![TV guide grid](docs/screenshots/livetv-guide.png) | ![Wrong match? Choose the right title](docs/screenshots/match.png) |
| ![Home](docs/screenshots/home.png) | ![Who's watching](docs/screenshots/profiles.png) |
| ![Profile editor](docs/screenshots/profile-editor.png) | ![Kids profile settings](docs/screenshots/profile-editor-kids.png) |
| ![Kids home](docs/screenshots/kids-home.png) | ![Stremio add-ons](docs/screenshots/addons.png) |
| ![Live TV](docs/screenshots/livetv.png) | ![Live TV sources](docs/screenshots/settings-livetv.png) |
| ![Metadata & integrations](docs/screenshots/settings-metadata.png) | ![Title page with TMDB](docs/screenshots/details-tmdb.png) |
| ![Continue Watching actions](docs/screenshots/continue-menu.png) | ![Source menu](docs/screenshots/source-menu.png) |
| ![Liquid glass](docs/screenshots/theme-glass.png) | ![Details](docs/screenshots/details.png) |
| ![Player](docs/screenshots/player.png) | ![Sources in the player](docs/screenshots/player-sources.png) |
| ![Online subtitles](docs/screenshots/player-online.png) | ![Subtitle on screen](docs/screenshots/player-subtitle.png) |
| ![Zoom](docs/screenshots/player-zoom.png) | ![Loading](docs/screenshots/loading.png) |
| ![Home manager](docs/screenshots/home-manager.png) | ![Catalog editor](docs/screenshots/home-catalogs.png) |
| ![Combined feed](docs/screenshots/home-feed.png) | ![Extensions](docs/screenshots/extensions.png) |
| ![Settings](docs/screenshots/settings.png) | ![Light theme](docs/screenshots/theme-light.png) |

*(Screenshots use the offline demo extension in `tests/DemoProvider` and the mock Stremio add-on in `tests/mock-archive`.)*

See **[docs/ANALYSIS.md](docs/ANALYSIS.md)** for how the Android app works, and **[docs/PLAYER-AND-CATALOGS.md](docs/PLAYER-AND-CATALOGS.md)** for how the built-in player, online subtitles and catalogs work (VLC source, the other Linux app, and this code), all with file and line references.

## Android app (in testing)

The Android app is a new app (not a fork of the official one) with the same look, features and simplicity as the Linux
app. It's in testing: releases from V7 include `.apk` files, and test builds can be downloaded from the latest
[Build workflow run](https://github.com/sherazi5110/cloudstream-linux/actions/workflows/build.yml) (artifact
`cloudstream-android`). The pictures below are the approved design previews; on landscape tablets and TV the app uses
the Linux app's icon rail, top bar, banner and title page.

| | |
|---|---|
| ![Phone: Home and title page (design preview)](docs/android/mock-phone.png) | ![Phone: VLC player (design preview)](docs/android/mock-phone-player.png) |
| ![Android TV: Home with remote focus (design preview)](docs/android/mock-tv.png) | ![Android TV: Live TV (design preview)](docs/android/mock-tv-live.png) |

**Layouts**
- **Phone and tablet (touch):** a swipeable featured banner, Continue Watching, catalog rows, and a bottom bar
  (Home · Live TV · Search · My list · More). Long-press a card to mark it watched or remove it. Title pages have
  TMDB/IMDb ratings, one big Resume button, seasons and episodes.
- **Player (VLC):** sources, subtitles (also online), audio, speed and zoom, like on Linux, plus phone gestures:
  swipe left side for brightness, right side for volume, double-tap for ±10 s, pinch to zoom, and a lock button.
- **Android TV (remote):** the Linux icon rail, a banner that follows the focused title, a white ring on the focused
  card, large text. Arrows move, OK opens, Back goes back, Menu marks watched/removes. CH+/CH− switch Live TV channels.

**How it works:** CloudStream extensions load natively (no translation step, unlike on Linux). Stremio add-ons, Live TV
(M3U/XMLTV, Xtream, Stremio TV add-ons) with the TV guide grid, TMDB/IMDb/AniList metadata with "Wrong match?", profiles,
kids profiles with a parent PIN, the adult-content setting, downloads, skip intro and the next-episode countdown come
over from the Linux app.

**New in 7.0 on Android:** **Downloads** (the *Download* button on a movie; on episodes, the download icon in the
phone list, or hold OK / long-press an episode and choose *Download*). They continue in the background with a progress
notification (*Pause all* in it), and are listed under My list → **Downloads**. Files are kept in the app's own folder
(`Android/data/io.github.sherazi5110.cloudstream/files/Movies/CloudStream`), so no storage permission is needed;
uninstalling the app deletes them. **Skip intro** (anime) and the **next-episode countdown** appear in the player (on a
TV, OK presses the button, Back cancels the countdown). **TV guide:** the clock button next to the Live TV search.

**Install:** see [INSTALL.md → Android](INSTALL.md#android-phones-tablets-and-android-tv) (which `.apk` to pick, phones,
Android TV / Fire TV with Downloader, adb). Building it yourself: [android/README.md](android/README.md). Plan and status:
[docs/ANDROID-PLAN.md](docs/ANDROID-PLAN.md).

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
| `app/` | Repository manager, `.cs3` translator, parallel plugin loader, streaming engine, network (DoH/cache), header proxy, online subtitles, catalogs, library store, CLI, and the JavaFX UI (`ui/`, including the libVLC player). |
| `examples/InternetArchiveProvider/` | A complete extension (public-domain films from archive.org), built **both** as a real Dalvik `.cs3` and as a `.jar`, plus a ready-to-host `repo.json`. |
| `tests/` | Offline demo extension (series, slow host, dead host, token-protected HLS), mock site (with stand-ins for the subtitle services) — used by the tests and screenshot tour. |

## Install

**Ready-made packages** (.deb for Ubuntu/Debian, Flatpak for any distro, other Linux, and the Android `.apk`): see **[INSTALL.md](INSTALL.md)**.

### Build from source (Ubuntu 22.04+)

```bash
sudo apt install openjdk-21-jdk git libvlc5 vlc-plugin-base
git clone <this repo> cloudstream-linux && cd cloudstream-linux
./scripts/fetch-upstream.sh        # pinned CloudStream + NiceHttp sources
./scripts/install.sh               # ~/.local install, app-menu entry, cloudstreamrepo:// handler
```

Or build a self-contained `.deb` with a bundled Java runtime:

```bash
./scripts/build-deb.sh                 # build/cloudstream-linux_<version>_amd64.deb
./scripts/uninstall-user-install.sh    # only if you used install.sh before (keeps your data)
./scripts/build-flatpak.sh             # build/cloudstream-linux.flatpak (needs flatpak-builder)
sudo apt install ./build/cloudstream-linux_6.0.1_amd64.deb   # pulls in libVLC
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
- **YouTube** links (mostly trailers) depend on VLC's own YouTube support, which breaks often. Upstream uses NewPipe, which is only published on JitPack.
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
./gradlew :app:test                 # extensions, Android shims, sources with a dead host, header proxy, online subtitles, catalogs and libVLC playback
python3 -I tests/mock-archive/server.py 8766   # local mock site for the demo/example extensions

# Scripted screenshot tour (any X display or xvfb-run):
CS_SCREENSHOT=/tmp/shot CS_SCREENSHOT_SEARCH=pixel cloudstream-linux   # player shots need libVLC and xdotool
node scripts/render-icons.js        # re-render the app icons from packaging/linux/cloudstream-linux.svg
```

CI (`.github/workflows/build.yml`) runs the tests on every push and publishes the `.deb` and tarball for every `v*` tag.

The upstream versions are pinned in `scripts/fetch-upstream.sh` (`CS_COMMIT`, `NICEHTTP_TAG`).

## License

GPL-3.0, the same as CloudStream, whose library sources this project compiles.

The built-in player uses [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL-2.1, from your system's VLC packages) through [vlcj](https://github.com/caprica/vlcj) (GPL-3.0). Online subtitles come from the OpenSubtitles Stremio addon.

Made by Tennant Creek.
