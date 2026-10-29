# Handover notes (for the next development session)

Read this first when continuing work on CloudStream for Linux.

## State (2026-10-29)

- `main` = release **V4.0.1** (published with .deb, .flatpak and .tar). Tags use a capital V (`V4.0.1`);
  CI publishes a release for any tag starting with `v` or `V`.
- Branch **`v5`** = the next version, **5.0.0** (not released yet). Done on it:
  1. **VLC is the only player.** mpv (+ uosc UI, IPC), and the external VLC/ffplay launchers were removed.
     The CLI `play` command went with them. The player is `ui/BuiltinPlayer.kt` + `ui/VlcVideo.kt`.
  2. **Liquid glass theme** (`ThemeMode.GLASS`) + nine presets (`ThemePreset`), in `ui/Theme.kt`; glass CSS
     at the end of `resources/ui/theme.css`. JavaFX can't blur behind a node, so glass = translucent panels +
     bright gradient borders over a soft backdrop (`Theme.glassBackdropStyle()`, or the blurred wallpaper).
  3. **Player:** equalizer (VLC presets), volume to 200 %, clock and "ends at", screenshots (Shift+S, saved to
     ~/Pictures/CloudStream), preferred audio languages, subtitle style (size, colour, outline, box).
     Subtitle style and battery saver are VLC *engine* options: `Vlc.optionsChanged()` restarts the engine
     (after playback if one is running).
  4. **Home slider** (`HeroSlider` in `ui/HomePage.kt`) and **anti-spoiler** (`DetailsPage.episodeCard`).
  5. **Settings:** searchable Keyboard shortcuts page, Site addresses (`SiteOverrides` in `Maintenance.kt`,
     applied in `Engine.providers()`), update check (`Updates`, GitHub releases of sherazi5110/cloudstream-linux),
     reset settings / erase all data.
  6. **"View all" scrolls endlessly** (`GridPage`): next pages load by themselves near the bottom until the
     extension says `hasNext = false` (Demo's "Endless catalog" has 4 pages for testing). **Posters on slow
     connections** (`Images`): disk cache read without waiting for a download slot, 12 slots, posters that left
     the screen give their slot up, TMDB images fetched at the shown width, one retry, shorter timeouts.
- Tests: `./gradlew test`, 17 tests, all passing (libVLC must be installed for the playback test).

## How the owner works

- They push from their own computer (Ubuntu). Unless the Claude GitHub App is installed for the repo,
  hand over work as a **git bundle** plus the exact commands to fetch and push it.
- Commits are authored as the owner (the project's existing identity). Don't re-author pushed commits.
- They want the app to look **its own**, not a copy of AMRGAMAL-1/CloudStream-Desktop-Linux. That app is
  a feature reference only.
- They value: VLC built-in player only; polish; screenshots as proof that things work.

## Next: Stremio add-on support (the owner's next request)

Stremio add-ons are plain HTTP + JSON (protocol: github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md):
`<base>/manifest.json`, `<base>/catalog/<type>/<id>[/extra].json`, `<base>/meta/<type>/<id>.json`,
`<base>/stream/<type>/<id>.json`, `<base>/subtitles/<type>/<id>.json`. `OnlineSubtitles.kt` already speaks
the subtitles part (OpenSubtitles v3 + Cinemeta). Suggested design:

1. `StremioAddons` store (install by manifest URL, enable/disable, order), shown as a tab on the Extensions page.
2. A synthetic `MainAPI` per add-on (catalogs → `mainPage`/`getMainPage`, search via the `search` extra,
   `load` → meta (series → episodes `tt…:S:E`), `loadLinks` → streams). Then Home, Search, the catalog editor,
   the feed and the player all work unchanged.
3. Streams: `url` → ExtractorLink (with `behaviorHints.proxyHeaders` as headers → `StreamProxy`);
   `ytId` → YouTube; `infoHash` → needs a torrent engine: skip at first and show "torrent (not supported)".
4. Cinemeta (`https://v3-cinemeta.strem.io/manifest.json`) as the default metadata add-on: also gives
   HD backdrops, logos, cast and IMDb ratings for a richer details page.
5. Test against a mock add-on in `tests/mock-archive/server.py` (it already mocks Cinemeta search and OpenSubtitles).

## Remaining ideas (from the owner's recording of the other app)

Not done yet, roughly by value: Cloudflare solver (JavaFX WebView implementing `WebViewResolver`; note
javafx-web adds a lot of size), downloads for offline viewing, profiles, Discord "now watching", AniSkip
(skip intro), richer details page (cast, trailers, similar: easiest via Cinemeta once Stremio support
exists), rename catalog rows, UI zoom, fonts, extension tabs with update history, more subtitle providers
(SubDL/Subsource need API keys), open a local video file, RPM/AppImage packages.

## Upkeep (see the owner's question about the app ageing)

- Flatpak runtime is Freedesktop **24.08**, end of life around now: move to 25.08 in
  `packaging/flatpak/*.yml` and the CI container image.
- VLC source for the Flatpak comes from Ubuntu's archive (`vlc_3.0.24.orig.tar.xz`); add VideoLAN's
  `download.videolan.org` as a mirror URL in case Ubuntu drops it.
- GitHub warns that Node 20 actions are deprecated: bump action versions in `.github/workflows/build.yml`.
- Pinned CloudStream library: `scripts/fetch-upstream.sh` (`CS_COMMIT`).

## Dev environment tips

- GUI tour: `CS_SCREENSHOT=/tmp/shot CS_SCREENSHOT_SEARCH=pixel cloudstream-linux` (needs a display, e.g.
  `Xvfb :99`, plus `tests/mock-archive/server.py 8766` and a data home with the Demo extension installed).
- `-Dprism.order=sw` for software rendering under Xvfb. VLC frames are part of the JavaFX scene, so
  window snapshots include the video.
- Don't run downloaded build scripts (e.g. VLC's configure) locally; verify via CI instead.
