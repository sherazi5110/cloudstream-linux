# Handover notes (for the next development session)

Read this first when continuing work on CloudStream for Linux.

## State (2026-10-31)

- Branch **`v7`** (from main after the Android app was merged, PR #10), version **7.0.0** for Linux and Android
  (`AppWindow.VERSION`, metainfo, `android/app/build.gradle.kts` default; CI uses the tag). Release notes for the
  release body: `docs/releases/7.0.0.md` (CI puts `docs/releases/<version>.md` above `.github/release-install.md`).
  Each feature exists twice: Linux (`app/…/linux`) and Android (`android/…/cloudstream`), the logic files copied
  with only package/logging/paths changed. Keep the copies in step.
  1. **"Wrong match?"** (`Metadata.searchCandidates/chosenMatch/chooseMatch`, stored as `match:<hash>` in the metadata
     prefs; `(null, 0)` = "no match"). UI: `MatchSheet` in `ui/DetailsPage.kt`; Android `ui/MatchDialog.kt`.
  2. **Skip intro + countdown** (`SkipTimes.kt`): AniSkip v2 by MAL id (extension's, else AniList `idMal` by title;
     `-Dcs.aniskip=` for the mock). Settings `skip_buttons` (on) and `auto_skip` (off), per profile. Linux
     `BuiltinPlayer` (`skipButton`, `nextCard`, `updateCountdown`); Android `PlayerController.segments/activeSegment/
     countdownStart` + `NextEpisodeCard` in `PlayerScreen`. Countdown only for episodes over 5 min
     (`-Dcs.countdown.min=` for tours), from the credits (if inside the second half) or 20 s before the end.
  3. **TV guide grid** (`LiveTv.programmes/hasGuide`): Linux `ui/GuideGrid.kt` (GridPane: fixed names + ruler kept in
     step with one ScrollPane, now line, 12 h, max 250 rows) toggled by *TV guide* in `LiveTvPage`'s top bar; Android
     `ui/GuideGrid.kt` (rows in a LazyColumn sharing one horizontal ScrollState).
  4. **Downloads** (`Downloads.kt`): JSON state `downloads.json` (per item: profile, state, bytes/segments for resume,
     resolved source + headers). 2 at a time. Picks the best ranked VIDEO/M3U8 link; direct files resume with Range
     into `<file>.part`; HLS picks the variant at the preferred quality, joins segments (fMP4 init → .mp4, else .ts),
     AES-128 (IV or sequence number), byte ranges, retries; DRM/live refused. On an error with a saved source it asks
     the extension again once (`retried`). `OfflineApi(apiName)` plays files under the extension's name (so progress
     is shared); `Engine.links` emits the local file first and `Engine.rank` puts it first. Linux folder: XDG
     Downloads/CloudStream or `Settings.downloadFolder`; UI `ui/DownloadsUi.kt` (button, episode badge, Library tab).
     Android folder: `getExternalFilesDir(MOVIES)/CloudStream`; `DownloadService` (foreground, dataSync, progress
     notification, Pause all) started by `Downloads.onChange` in `App.init`; UI `ui/DownloadsUi.kt`.
  5. **Flatpak runtime 25.08**: `ffmpeg-full` extension removed (25.08's `codecs-extra` installs with the runtime),
     `cflags: -std=gnu17` for libdvbpsi and VLC (25.08's GCC defaults to C23), CI image `freedesktop-25.08`.
     Not built locally: watch the first CI run.
  6. `SiteOverrides.originals` is keyed per loaded instance (a reloaded extension's new address is picked up).
  - Tests: `./gradlew test`, 25 tests (new `DownloadsTest`; guide and skip-time checks in `LiveTvMetadataTest`).
    Tours: `CS_SCREENSHOT_ONLY=v7` (match sheet, skip button, countdown), `downloads`, `guide`. Android: compiled and
    release APKs built; no emulator here (no KVM), so check on a device.

- `main` = release **V5.0.0** (published with .deb, .flatpak and .tar). Tags use a capital V (`V5.0.0`);
  CI publishes a release for any tag starting with `v` or `V`.
- Branch **`v6`** = the next version, **6.0.0** (not released yet). Done on it:
  1. **New shell** (`ui/AppWindow.kt`, `ui/Shell.kt`): icon rail, `TopBar` with `Section`s (Home, Movies, Series,
     Anime, Cartoons, Documentaries, Live TV: shown when an extension serves them), search pill, profile menu.
     Menus are `Popover`s drawn in the window (not popup windows) so they follow the theme. Pages implementing
     `TopBarActions` put controls in the bar (Home's source picker). Home's `Hero` is full-width under the bar,
     loads each featured title's details for backdrop/logo/plot, and fades out with a gradient clip.
  2. **Logo** (`ui/Logo.kt`, `packaging/linux/cloudstream-linux.svg|png`; redrawn in 6.0.1 as a big "C" with an "s"
     inside, see below): letters from Inter Display Black outlines
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
- Branch **`v6.0.1`** (on top of `v6`), version **6.0.1**:
  1. **Top bar without the section tabs** (the `Section` enum is gone). Pages put controls in the bar via
     `TopBarActions` (`hasOwnSearch` hides the global Search button). **Live TV** tab (`Tab.LIVE`) below Home.
  2. **Live TV** (`LiveTv.kt`, `ui/LiveTvPage.kt`): sources in prefs `livetv_sources`: M3U (address or `file:` URL,
     optional XMLTV guide), Xtream Codes, plus Stremio add-ons whose catalogs are `tv`/`channel` (those catalogs are
     excluded from `StremioApi`, so they never reach Home/Search). HTML pages are refused as playlists; GitHub blob
     links become raw links. Channel lists cached in `cache/livetv/<id>.json`; guides parsed with StAX (gz ok),
     now/next only (no grid yet). Favourites/recent per profile (`livetv@<profile>`). Playback via `LiveApi`
     (data = channel JSON) or the add-on's `StremioApi`; the player doesn't record Live in history. Kids see Live TV
     only if allowed, and then only child-like groups.
     **Channel logos** load via `Images.image()` (OkHttp, WebP) with a fallback to the iptv-org database
     (`ChannelLogos`: by tvg-id, then name; iptv-org.github.io, else raw.githubusercontent.com/iptv-org/api/gh-pages;
     cached a week). A Kodi add-on zip importer was tried and removed at the owner's request.
  3. **Watched controls**: `Library.markWatched/markUnwatched/isWatched`; `WideCard` takes `CardAction`s
     (✓ / ✕ / ⋯ popover); history rows and the details page toggle.
  4. **Metadata hub** (`Metadata.kt`, Settings → Metadata & integrations, sub-page `tmdb`): user's TMDB key (v3 key
     or v4 token), `cleanTitle()` strips release-name junk, matching by IMDb id (/find) or title+year search, 7-day
     disk cache incl. misses, 4 requests at a time. Cinemeta for IMDb ratings, AniList GraphQL for anime. Used by
     poster cards (lazy badge), the Home banner and the details page (rating pills, logo, cast photos, trailer →
     YouTube in the browser, similar titles → Search).
  5. **Parent PIN** (`Profiles.setParentPin/checkParentPin/unlockFor`, salted SHA-256): created in the profile
     editor when the first kids profile is made; needed to leave a kids profile ("Exit kids profile" in the menu and
     picker) unless the grown-up target has its own PIN. The last grown-up profile can't become a kids profile.
     Profiles stuck as kids from 6.0.0 (no parent PIN) can exit freely; if no grown-up exists, it turns back.
  6. **Logo** redrawn: a big "C" with a small "s" inside over the cloud (black outline drawn under the letters).
  7. Icons whose paths start with two invisible corner dots keep their proportions (`Icons.MORE`, chevrons, `EXPAND`).
  8. Mocks in `server.py`: `/iptv/`, `/xtream/`, `/addon-tv/`, `/tmdb/3/` (key `0123…cdef`), `/anilist`, `/logos-api/`,
     `/webpage`, Cinemeta meta. Tests: `LiveTvMetadataTest` (23 tests in all). Short tours: `CS_SCREENSHOT_ONLY=v601`,
     `kids`, `logos` (+ `-Dcs.tmdb.base=…` etc. pointing at the mock). Note: JUnit skips test functions that don't
     return Unit (`= runBlocking { … }` ending in a non-Unit expression).
- **6.0.1 update** (branch `v6.0.1-nsfw`, same version number, released by re-tagging V6.0.1): `Settings.showNsfw` (per profile, off by default) like Android's NSFW setting;
  `ContentFilter.nsfwAllowed` hides NSFW extensions/items/details, adult Live TV groups, and NSFW extensions in the
  Extensions list (installed ones stay). Turning it on: confirm + parent PIN if set.
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
     applied in `Engine.providers()`), update check (`Updates`, GitHub releases of sherazi5110/cloudstream-Linxdroid),
     reset settings / erase all data.
  6. **"View all" scrolls endlessly** (`GridPage`): next pages load by themselves near the bottom until the
     extension says `hasNext = false` (Demo's "Endless catalog" has 4 pages for testing). **Posters on slow
     connections** (`Images`): disk cache read without waiting for a download slot, 12 slots, posters that left
     the screen give their slot up, TMDB images fetched at the shown width, one retry, shorter timeouts.
- Tests (v5): `./gradlew test`, 22 tests, all passing (libVLC must be installed for the playback test).

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
javafx-web adds a lot of size), Discord "now watching", downloads of DASH / separate-audio HLS, details page
redesign like the Home banner, rename catalog rows, UI zoom, extension tabs with update history, more subtitle providers
(SubDL/Subsource need API keys), open a local video file, RPM/AppImage packages.

## Upkeep (see the owner's question about the app ageing)

- Flatpak runtime is Freedesktop **25.08** (since 7.0.0); next move to 26.08 around August 2027.
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
