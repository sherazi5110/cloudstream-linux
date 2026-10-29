# Handover notes (for the next development session)

Read this first when continuing work on CloudStream for Linux.

## State (2026-10-29)

- `main` = release **V5.0.0** (published with .deb, .flatpak and .tar). Tags use a capital V (`V5.0.0`);
  CI publishes a release for any tag starting with `v` or `V`.
- Branch **`v6`** = the next version, **6.0.0** (not released yet). Done on it:
  1. **New shell** (`ui/AppWindow.kt`, `ui/Shell.kt`): icon rail, `TopBar` with `Section`s (Home, Movies, Series,
     Anime, Cartoons, Documentaries, Live TV: shown when an extension serves them), search pill, profile menu.
     Menus are `Popover`s drawn in the window (not popup windows) so they follow the theme. Pages implementing
     `TopBarActions` put controls in the bar (Home's source picker). Home's `Hero` is full-width under the bar,
     loads each featured title's details for backdrop/logo/plot, and fades out with a gradient clip.
  2. **Logo** (`ui/Logo.kt`, `packaging/linux/cloudstream-linux.svg|png`): "SC" (Inter Display Black outlines)
     over a white cloud on black; letters take the accent colour in the app and the window icon.
  3. **Profiles** (`Profiles.kt`, `ui/ProfilesUi.kt`, `ui/Avatars.kt`): per-profile prefs files `library`,
     `home_catalogs`, `profile_settings` (default profile = the original files; others `<file>@<id>`).
     `Settings.migrateToProfiles()` moves v5's theme/player keys into the first profile once. Kids filter
     (`ContentFilter`) is applied in `Engine.providers()/home()/search()`, `GridPage` and `DetailsPage`.
     `Engine.allProviders()` ignores the profile (extension management, tests).
  4. **Stremio add-ons** (`Stremio.kt`): `StremioAddons` store + `StremioApi : MainAPI` per add-on; streams are
     collected from every enabled add-on that serves the id (like Stremio). Extensions page has a "Stremio
     add-ons" tab. Mock add-on: `tests/mock-archive/server.py` `/addon/`. Tests: `StremioProfilesTest`.
  5. **Fonts**: Inter (OFL, subset) in `resources/ui/fonts`, loaded in `DesktopApp.start`.
  6. Fixed: `Shelf` rows could stay squashed when cards arrived after the first layout (viewport now measures
     its row instead of binding to its height).
- Previous version (`v5`, now released) did:
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
- Tests: `./gradlew test`, 22 tests, all passing (libVLC must be installed for the playback test).

## How the owner works

- They push from their own computer (Ubuntu). Unless the Claude GitHub App is installed for the repo,
  hand over work as a **git bundle** plus the exact commands to fetch and push it.
- Commits are authored as the owner (the project's existing identity). Don't re-author pushed commits.
- They want the app to look **its own**, not a copy of AMRGAMAL-1/CloudStream-Desktop-Linux. That app is
  a feature reference only.
- They value: VLC built-in player only; polish; screenshots as proof that things work.

## Stremio add-ons: done in v6 (original plan below, for reference)

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
javafx-web adds a lot of size), downloads for offline viewing, Discord "now watching", AniSkip
(skip intro), a TV guide grid for Live TV, "wrong match? search TMDB" on the title page, details page
redesign like the Home banner, rename catalog rows, UI zoom, extension tabs with update history, more subtitle providers
(SubDL/Subsource need API keys), open a local video file, RPM/AppImage packages.

## Upkeep (see the owner's question about the app ageing)

- Flatpak runtime is Freedesktop **24.08**, end of life around now: move to 25.08 in
  `packaging/flatpak/*.yml` and the CI container image.
- VLC source for the Flatpak comes from Ubuntu's archive (`vlc_3.0.24.orig.tar.xz`); add VideoLAN's
  `download.videolan.org` as a mirror URL in case Ubuntu drops it.
- GitHub warns that Node 20 actions are deprecated: bump action versions in `.github/workflows/build.yml`.
- Pinned CloudStream library: `scripts/fetch-upstream.sh` (`CS_COMMIT`).

## Dev environment tips

- GUI tour: `CS_SCREENSHOT=/tmp/shot CS_SCREENSHOT_SEARCH=pixel cloudstream-linux` (`CS_SCREENSHOT_ONLY=v6` or
  `=glass` runs a short part). Needs a display, e.g.
  `Xvfb :99`, plus `tests/mock-archive/server.py 8766` and a data home with the Demo extension installed.
- `-Dprism.order=sw` for software rendering under Xvfb. VLC frames are part of the JavaFX scene, so
  window snapshots include the video.
- Don't run downloaded build scripts (e.g. VLC's configure) locally; verify via CI instead.
