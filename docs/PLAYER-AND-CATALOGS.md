# The built-in player and catalog customization: evidence and design

This document covers three things. It explains how VLC, the other CloudStream desktop
app for Linux and the online subtitle services do what version 4 needed, with file and
line references. It shows what CloudStream for Linux took from each. And it describes
how the new built-in player and the catalog editor are designed.

Sources read (all cloned or downloaded on 2026-10-27):

| Source | Version |
|---|---|
| VLC (`videolan/vlc`, branch `3.0.x`) | commit `0d75105a`. Ubuntu/Debian ship 3.0.20, the same API. |
| vlcj, Java bindings for libVLC | `uk.co.caprica:vlcj:4.12.1` sources (the 4.x line targets libVLC 3) |
| vlcj-javafx | commit `2647fb38` |
| CloudStream-Desktop-Linux (AMRGAMAL-1) | commit `b12593a2` |
| Wyzie Subs (`itzCozi/wyzie-subs`, `itzCozi/wyzie-lib`) | `9737d11e`, `cc7bd285` |

---

## 1. Why the old player failed on your machine

| Symptom | Cause |
|---|---|
| mpv opened in its own full-screen window | Version 3 embedded mpv by passing the app's X11 window id (`--wid`). The screenshots are from ChromeOS, where Linux apps run in Crostini and reach the screen through a Wayland bridge. Embedding a window that way isn't reliable there, so mpv opened its own window instead. |
| The menu showed `" also appear in the right-click menu (uosc).` | The first line of `mpv/input.conf` was a comment containing `#!`. uosc treats `#!` as "put this in the menu". Fixed in `app/src/main/resources/mpv/input.conf:1`. |
| ffplay was laggy | ffplay is FFmpeg's test player: it draws with SDL and has no hardware decoding. |

Version 4 therefore doesn't embed a native window at all. Video frames are drawn by the
app's own UI toolkit (JavaFX), so they work anywhere the app's window works, including
X11, Wayland/XWayland and ChromeOS.

## 2. How VLC can play inside another program (VLC source)

libVLC, the library inside VLC, can hand each decoded frame to the host program instead
of opening a window. This is the "vmem" (video memory) output:

| What | Evidence |
|---|---|
| The host registers lock/display callbacks | `include/vlc/libvlc_media_player.h:307` (`libvlc_video_lock_cb`), `:338` (`libvlc_video_display_cb`), `:366` (`libvlc_video_format_cb`) |
| Registering them switches VLC's video output to `vmem` | `lib/media_player.c:1103-1129` sets `var_SetString(mp, "vout", "vmem")` (line 1114) and `vmem-chroma` (line 1129) |
| vmem asks the host for a buffer before each frame and gives it back after | `modules/video_output/vmem.c:116` `Open` reads `vmem-setup`/`vmem-lock`/`vmem-display` (124-133); `Prepare` calls `lock` (251), `Display` calls `display` (271) |
| **Subtitles are drawn into the frame** | `src/video_output/video_output.c:1000-1002`: subtitles go to the display separately only if it declares `subpicture_chromas`. vmem doesn't (`grep -c subpicture_chromas modules/video_output/vmem.c` → 0), so VLC renders them (`spu_Render`, 1057) and blends them into the picture (`picture_BlendSubpicture`, 957 and 1078). The host gets frames with subtitles already in them. |
| Hardware decoding is independent of the output | `modules/codec/avcodec/avcodec.c:140` `avcodec-hw` (VAAPI/VDPAU, falling back to software); VAAPI frames are copied to memory by `modules/hw/vaapi/chroma.c` |
| Controls the player needs | speed `libvlc_media_player_set_rate` (h:925), seek `set_time` (805), aspect `libvlc_video_set_aspect_ratio` (1180), subtitle track `libvlc_video_set_spu` (1244), subtitle delay (1268), audio track/language `libvlc_audio_set_track` (1842), add a subtitle file while playing `libvlc_media_player_add_slave` (1019) |
| **VLC's HTTP only sends User-Agent and Referer** | `modules/access/http/access.c:185-186` and options at 301/306; HLS/DASH segments use the same two, `modules/demux/adaptive/http/HTTPConnection.cpp:246,252`. There's no option for other headers (Origin, cookies, tokens), which many extensions need. |

vlcj wraps this API for Java: `CallbackVideoSurface.java:83-84` calls
`libvlc_video_set_format_callbacks` and `libvlc_video_set_callbacks`.
vlcj-javafx shows the zero-copy route into JavaFX. It asks for `BGRA` frames
(`PixelBufferBufferFormat.java:40`), wraps VLC's own buffer in a JavaFX `PixelBuffer`
(`ImageViewVideoSurface.java:94`) and redraws it on each frame (`:106`).

## 3. How the other Linux app does it (CloudStream-Desktop-Linux)

| Feature | Their approach | Evidence |
|---|---|---|
| In-app player | libmpv's render API (`vo=libmpv`) drawing into a GTK OpenGL area, with custom C++ code, and **software decoding forced on Linux** | `desktop-app/.../player/DesktopMpvEngine.kt:114-118` (vo=libmpv), `:128-135` (`gpu-api=opengl`, `hwdec=no`); native code `desktop-app/src/main/cpp/linux/surface_linux.cpp`; it needs X11: `ComposeNativeWebPlayer.kt:549` ("an X11-compatible DISPLAY is required for the embedded GTK bridge") |
| VLC option | Launches the **external** VLC program and drives it over VLC's remote-control socket | `player-abstraction/.../VlcPlayer.kt:119-120` (`--extraintf=rc`, `--rc-host`), `:161` (`ProcessBuilder`) |
| Headers | Local proxy plus an HLS playlist rewriter | `player-abstraction/.../proxy/LocalStreamProxy.kt:285-315` (sessions with headers), `proxy/HlsRewriter.kt:21,73` |
| Catalog customization | Per extension: a set of disabled catalog names and a list giving their order, saved under `disabled_catalogs_<provider>` / `ordered_catalogs_<provider>`. Home filters `mainPage` by the set and sorts by the list. | `core/preference/PreferenceKeys.kt:23-24`; `ui/screens/HomeScreen.kt:85-97`; dialog `ui/screens/home/HomeManagementDialog.kt:114` ("Enable, disable, and drag to reorder catalogs."), `:139` ("Enable Multi-Provider Feed"), `:499` ("N catalogs"); saving `DesktopHomeViewModel.kt:150-169` |
| Online subtitles | Stremio subtitle addons (OpenSubtitles v3). IMDb ids come from a Cinemeta title search when the extension has none. | `desktop/stremio/StremioAddonManager.kt:469-515` (search), `:579-608` (Cinemeta `catalog/<type>/top/search=<q>.json`); URL format `StremioTransport.kt:38-43` (`<addon>/subtitles/<type>/<id>.json`) |

**Wyzie Subs** looked like a keyless open subtitle API, but its README now says
*"This project is now closed source"* (`wyzie-subs/README.md:10`), and the client library
sends an API key (`wyzie-lib/src/funcs.ts:66-67, 142-145`). So it isn't used.

## 4. What CloudStream for Linux 4.0 does

### Built-in player (`ui/BuiltinPlayer.kt`, `ui/VlcVideo.kt`)

```
extension → ExtractorLink(url, headers)
              │  headers other than UA/Referer? ──► StreamProxy (127.0.0.1) adds them to the
              │                                     playlist, every variant, segment and key
              ▼
       libVLC (vlcj 4.12) ── decode (hardware if possible) ── blend subtitles ── scale to screen
              │ vmem callbacks: BGRA frame written into a shared native buffer
              ▼
       JavaFX PixelBuffer → ImageView (Fit/Fill/Stretch/Original/16:9/4:3/21:9)
              + CloudStream controls drawn on top (seek bar, menus, OSD)
```

| Design point | Where |
|---|---|
| libVLC, loaded once. Hardware decoding on. Battery saver skips H.264 deblocking (`--avcodec-skiploopfilter=4`), a large CPU saving on weak PCs. ARM library folders added because vlcj only searches x86 ones. | `VlcVideo.kt:28-55` (hw 43, saver 47, ARM 31) |
| Frames go straight from VLC's buffer to the screen, with no Java copy. If the UI falls behind, frames are dropped rather than queued, so playback never lags behind. | `VlcVideo.kt:93-137` (format 95, buffers 113, display 126) |
| A 4K stream on a 1080p screen is scaled down inside VLC before it reaches the UI, moving a quarter of the pixels | `VlcVideo.kt:147` `fitToScreen` |
| Correct shape for non-square pixels, plus the zoom modes | `VlcVideo.kt:100-111`, `:157` `layoutChildren` |
| Headers: User-Agent and Referer as VLC options; anything else through the proxy | `BuiltinPlayer.kt:202-219` (`mrl`), `StreamProxy.kt:45` (`wrap`), `:61` (`handle`, Range for seeking at 66), `:121` (`rewrite`) |
| Source switching keeps the position (`:start-time`). A failing source falls back to the next one automatically, or waits for more sources. | `BuiltinPlayer.kt:191`, `:311` (`onError`), `:340` (`switchTo`) |
| Menus: Sources, Subtitles, Audio language, Speed (0.25–2×), Zoom | `BuiltinPlayer.kt` `showSources`, `showSubtitles`, `:428` `showAudio`, `:407` `showSpeed`, `:414` `showZoom`; panel class `PlayerMenu` (742) |
| Subtitles: the extension's (downloaded with the source's Referer), **online (OpenSubtitles)**, or from a file, with a delay control | `BuiltinPlayer.kt:535` (extension), `:472` (online), `loadSubtitleFile` |
| Seek bar with time preview, auto-hiding controls and cursor, keyboard shortcuts | `SeekBar` (681), `hideControls` (640), `handle` (594) |
| Stopping VLC never freezes the UI | `BuiltinPlayer.kt:241` |
| Resume, Continue Watching and auto next episode, as before | `record`, `finish` |

### Online subtitles (`OnlineSubtitles.kt`)

The same open protocol as the other app and Stremio, with no account or key:
1. IMDb id from the extension (`getImdbId`, or an IMDb URL), otherwise a Cinemeta title
   search that matches the name and year (`:55`).
2. `GET https://opensubtitles-v3.strem.io/subtitles/<movie|series>/<tt…>[:S:E].json` (`:41`).
3. The chosen file is downloaded to the cache, with the HTTP status checked (`:73`), and
   added to VLC as a subtitle track.
4. Languages shown by name, with your preferred language first (`:91`; preference under Settings → Playback).

### Catalogs per extension and the combined feed (`Catalogs.kt`, `ui/HomeManager.kt`)

Same data model as the other app: disabled names plus an order list per extension.
Storing names rather than positions means an extension update that adds a catalog shows
it at the end, and one that removes a catalog just drops it.

| | Where |
|---|---|
| Catalogs in the user's order, with on/off state; the enabled ones | `Catalogs.kt:35`, `:44` |
| Save all edits at once, so Home reloads once | `Catalogs.kt:53` |
| Combined feed (several extensions on one Home) | `Catalogs.kt:73`; Home shows a **My feed** chip and tags each row with its extension |
| Home loads only enabled catalogs, in order, from one or several extensions | `Engine.kt:84` `home(apis)` |
| Dialog: pick an extension or build the feed (filter by Movies / Series / Anime / Docs / Live); the tune button opens a catalog editor with switches, arrows, **drag-and-drop**, All on/off and Default order | `HomeManager.kt:63`, `:126`, `:175`, `:194` |

## 5. How this was tested

`./gradlew :app:test` runs 19 tests. The new ones are in `PlaybackFeaturesTest.kt`:
- **libVLC plays an HLS stream that needs a custom header on every request**, through
  the proxy. It checks that frames arrive, time advances, the width is right, a subtitle
  file attaches, and seeking works. This is the built-in player's exact path, run headless.
- The proxy adds headers to the playlist, its variant and all 12 segments, and passes Range through (`206`, exact `Content-Range`).
- Playlist rewriting handles relative URIs, spaces and `#EXT-X-KEY` URIs.
- Online subtitles: title → IMDb id → five results → language names → download.
- Catalogs: hide and reverse, persist, and `Engine.home` loads exactly the enabled catalogs in order.

There is also a GUI tour (`CS_SCREENSHOT=…`), run against the demo extension, that drives
the real app. It covers:
- playing, opening Sources and switching to the token-protected HLS source;
- searching online subtitles and adding one, which then shows on the video;
- the audio menu and zoom (Stretch);
- the Home manager, the catalog editor, and the combined feed.

The demo extension (`tests/DemoProvider`) gained that token-protected HLS source, and the
mock site (`tests/mock-archive/server.py`) gained stand-ins for Cinemeta and the OpenSubtitles addon.

**Not verified here.** The sandbox has no GPU and no internet access to the real
subtitle services. Real hardware decoding, 4K power use, and live OpenSubtitles/Cinemeta
responses were therefore not tested; the subtitle client follows the protocol shown
above and was tested against a mock.

## 6. New logo

`packaging/linux/cloudstream-linux.svg`: the CloudStream cloud with a play button cut
through it, on a rounded crimson-to-violet tile with three "stream" lines underneath.
It's distinct from the Android app's plain cloud but clearly related, and stays
readable down to 32 px. `scripts/render-icons.js` renders it to the window/taskbar
icon (`ui/icon.png`) and the package icon (`cloudstream-linux.png`).
