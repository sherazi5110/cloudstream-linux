#!/usr/bin/env python3
"""Minimal stand-in for the archive.org endpoints InternetArchiveProvider uses.

Serves /advancedsearch.php, /metadata/<id>, /download/<id>/<file>, /services/img/<id>.
Downloads require a Referer header, like many real streaming hosts, so tests can prove
that headers flow from the extension's ExtractorLink to the player.

Also: /hls/ (an HLS stream that needs an "X-CS-Token: demo" header on every request,
like token-protected hosts), and /stremio/ stand-ins for the Cinemeta and OpenSubtitles
addons the player's online subtitle search uses.

/iptv/ (an M3U playlist + XMLTV guide), /xtream/ (an Xtream Codes server), /addon-tv/ (a Stremio
TV add-on), /tmdb/3/ (TMDB, key "0123456789abcdef0123456789abcdef") and /anilist (GraphQL) are the
Live TV and metadata stand-ins.

/addon/ is a whole Stremio add-on ("Test Add-on"): manifest, catalogs (with search and
skip paging), metadata (a film and a series with episodes), streams (with proxy headers,
plus a torrent-only stream the app must skip) and subtitles.
"""
import json, os, sys, time, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
FILMS = [
    ("night_of_the_test_pattern", "Night of the Test Pattern", "1968", "<p>A public-domain <b>test</b> film.</p>"),
    ("the_general_test", "The General Test", "1926", "A silent comedy about color bars."),
    ("nosferatest", "Nosferatest", "1922", "A vampire haunts an empty broadcast schedule."),
    ("metropolis_bars", "Metropolis Bars", "1927", "A city built entirely out of SMPTE color bars."),
    ("his_girl_pixel", "His Girl Pixel", "1940", "Fast-talking editors argue over compression artifacts."),
    ("charade_signal", "Charade Signal", "1963", "Nobody is who they seem, least of all the carrier wave."),
    ("detour_noise", "Detour Noise", "1945", "A hitchhiker meets white noise on a lonely highway."),
    ("carnival_of_tones", "Carnival of Tones", "1962", "A 440 Hz sine wave will not stop following her."),
    ("the_kid_frame", "The Kid Frame", "1921", "A tramp raises an orphaned keyframe."),
    ("sherlock_jr_sync", "Sherlock Jr. Sync", "1924", "A projectionist dreams his way into an out-of-sync reel."),
]
ITEMS = {i: {"title": t, "year": y, "description": d} for i, t, y, d in FILMS}
FILES = [{"name": "test_film_640.mp4", "format": "h.264", "height": "360"},
         {"name": "test_film.en.srt", "format": "SubRip"}]

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.startswith("/anilist"):
            title = body.get("variables", {}).get("s", "")
            if "pixel" in title.lower():
                return self.send(200, json.dumps({"data": {"Media": {"averageScore": 84, "bannerImage": None, "description": "Anime <b>Pixel</b>.", "title": {"romaji": "Pikuseru", "english": "Pixel City"}}}}))
            return self.send(200, json.dumps({"data": {"Media": None}}))
        return self.send(404, "{}")

    def channels(self):
        root = f"http://localhost:{self.server.server_port}"
        return [
            ("news24", "News 24", "News", "GB", f"{root}/hls/master.m3u8|X-CS-Token=demo"),
            ("sport1", "Sport One", "Sports", "US", f"{root}/hls/master.m3u8|X-CS-Token=demo"),
            ("cartoon", "Cartoon Fun", "Kids", "US", f"{root}/hls/master.m3u8|X-CS-Token=demo"),
            ("movies", "Movie Max", "Movies", "PK", f"{root}/download/night_of_the_test_pattern/test_film_640.mp4"),
            ("docs", "Planet Docs", "Documentary", "IN", f"{root}/hls/master.m3u8|X-CS-Token=demo"),
        ]

    def iptv(self, parts):
        root = f"http://localhost:{self.server.server_port}"
        if parts == ["playlist.m3u"]:
            lines = ["#EXTM3U"]
            for i, (cid, name, group, country, url) in enumerate(self.channels()):
                lines.append(f'#EXTINF:-1 tvg-id="{cid}" tvg-logo="{root}/media/poster.png" tvg-country="{country}" group-title="{group}",{name}')
                if cid == "movies":
                    lines.append(f"#EXTVLCOPT:http-referrer={root}/")
                lines.append(url)
            return self.send(200, "\n".join(lines) + "\n", "audio/x-mpegurl")
        if parts == ["guide.xml"]:
            now = int(time.time()) // 1800 * 1800
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            progs = []
            for cid, name, *_ in self.channels():
                for k in range(-1, 5):
                    start, stop = now + k * 1800, now + (k + 1) * 1800
                    progs.append(f'<programme start="{fmt(start)}" stop="{fmt(stop)}" channel="{cid}"><title lang="en">{name} show {k + 2}</title><desc>About {name}.</desc></programme>')
            xml = '<?xml version="1.0" encoding="UTF-8"?><tv>' + "".join(f'<channel id="{c[0]}"><display-name>{c[1]}</display-name></channel>' for c in self.channels()) + "".join(progs) + "</tv>"
            return self.send(200, xml, "application/xml")
        return self.send(404, "not found", "text/plain")

    def xtream(self, path, qs):
        if qs.get("username", [""])[0] != "demo" or qs.get("password", [""])[0] != "secret":
            return self.send(200, json.dumps({"user_info": {"auth": 0}}))
        action = qs.get("action", [""])[0]
        if path.endswith("xmltv.php"):
            return self.iptv(["guide.xml"])
        if action == "get_live_categories":
            return self.send(200, json.dumps([{"category_id": "1", "category_name": "News"}, {"category_id": "2", "category_name": "Kids"}]))
        if action == "get_live_streams":
            return self.send(200, json.dumps([{"num": 1, "name": "News 24", "stream_id": 11, "category_id": "1", "epg_channel_id": "news24", "stream_icon": ""},
                                              {"num": 2, "name": "Cartoon Fun", "stream_id": 12, "category_id": "2", "epg_channel_id": "cartoon", "stream_icon": ""}]))
        return self.send(200, json.dumps({"user_info": {"auth": 1, "status": "Active"}, "server_info": {}}))

    def addon_tv(self, parts):
        root = f"http://localhost:{self.server.server_port}"
        chans = [{"id": f"tvtest:{c[0]}", "type": "tv", "name": c[1], "poster": f"{root}/media/poster.png", "genres": [c[2]]} for c in self.channels()]
        if parts == ["manifest.json"]:
            return self.send(200, json.dumps({"id": "org.test.tv", "version": "1.0.0", "name": "Test TV", "types": ["tv"], "idPrefixes": ["tvtest"],
                "resources": ["catalog", "meta", "stream"], "catalogs": [{"type": "tv", "id": "channels", "name": "Channels", "extra": [{"name": "skip"}]}]}))
        if len(parts) >= 3 and parts[0] == "catalog":
            skip = int(parts[3].removesuffix(".json").removeprefix("skip=")) if len(parts) > 3 else 0
            return self.send(200, json.dumps({"metas": chans[skip:]}))
        if len(parts) == 3 and parts[0] == "meta":
            mid = urllib.parse.unquote(parts[2].removesuffix(".json"))
            meta = next((c for c in chans if c["id"] == mid), None)
            return self.send(200 if meta else 404, json.dumps({"meta": meta} if meta else {}))
        if len(parts) == 3 and parts[0] == "stream":
            return self.send(200, json.dumps({"streams": [{"name": "Test TV", "title": "Live 480p", "url": f"{root}/hls/master.m3u8",
                                                           "behaviorHints": {"proxyHeaders": {"request": {"X-CS-Token": "demo"}}}}]}))
        return self.send(404, "{}")

    TMDB_KEY = "0123456789abcdef0123456789abcdef"

    def tmdb(self, path, qs):
        if qs.get("api_key", [""])[0] != self.TMDB_KEY and self.headers.get("Authorization") != "Bearer test-read-token":
            return self.send(401, json.dumps({"status_message": "Invalid API key"}))
        root = f"http://localhost:{self.server.server_port}"
        movie = {"id": 501, "title": "Night of the Test Pattern", "original_title": "Night of the Test Pattern", "release_date": "1968-10-01",
                 "vote_average": 7.9, "vote_count": 1234, "backdrop_path": "/backdrop_night_of_the_test_pattern.jpg", "poster_path": "/poster_night_of_the_test_pattern.jpg",
                 "overview": "TMDB: colour bars rise from their graves.", "media_type": "movie"}
        show = {"id": 601, "name": "Pixel City", "original_name": "Pixel City", "first_air_date": "2024-01-01", "vote_average": 8.2, "vote_count": 12000,
                "backdrop_path": "/backdrop_pixel_city.jpg", "poster_path": "/poster_pixel_city.jpg", "overview": "TMDB: a detective chases a missing frame.", "media_type": "tv"}
        if path == "/configuration":
            return self.send(200, json.dumps({"images": {}}))
        if path.startswith("/search/"):
            q = qs.get("query", [""])[0].lower()
            hits = [h for h in (movie, show) if q in (h.get("title") or h.get("name")).lower()]
            if path == "/search/movie": hits = [h for h in hits if h["media_type"] == "movie"]
            if path == "/search/tv": hits = [h for h in hits if h["media_type"] == "tv"]
            return self.send(200, json.dumps({"results": hits}))
        if path.startswith("/find/"):
            return self.send(200, json.dumps({"movie_results": [movie] if path.endswith("tt9000002") else [], "tv_results": [show] if path.endswith("tt9000001") else []}))
        full = dict(movie if path.startswith("/movie/") else show)
        full.update({
            "external_ids": {"imdb_id": "tt9000002" if path.startswith("/movie/") else "tt9000001"},
            "images": {"logos": [{"file_path": "/logo.png", "iso_639_1": "en"}]},
            "credits": {"cast": [{"name": "Ada Scanline", "character": "Detective Vale", "profile_path": "/poster_signal_lost.jpg"},
                                 {"name": "Vic Raster", "character": "The Editor", "profile_path": None}]},
            "videos": {"results": [{"site": "YouTube", "type": "Trailer", "official": True, "key": "abc123"}]},
            "recommendations": {"results": [{"title": "Metropolis Bars", "release_date": "1927-01-01", "poster_path": "/poster_metropolis_bars.jpg", "vote_average": 7.2}]},
            "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [{"certification": "PG"}]}]},
            "content_ratings": {"results": [{"iso_3166_1": "US", "rating": "TV-14"}]},
        })
        return self.send(200, json.dumps(full))

    def addon(self, parts):
        root = f"http://localhost:{self.server.server_port}"
        def meta(fid, full=False):
            m = {"id": f"tst:{fid}", "type": "movie", "name": ITEMS[fid]["title"], "releaseInfo": ITEMS[fid]["year"],
                 "poster": f"{root}/media/poster_{fid}.jpg", "imdbRating": "7.4"}
            if full:
                m.update({"background": f"{root}/media/backdrop_{fid}.jpg", "description": ITEMS[fid]["description"].replace("<p>", "").replace("</p>", "").replace("<b>", "").replace("</b>", ""),
                          "genres": ["Classic", "Comedy"], "cast": ["Ada Scanline", "Vic Raster"], "runtime": "94 min", "certification": "PG"})
            return m
        series = {"id": "tst:pixel_city", "type": "series", "name": "Pixel City", "releaseInfo": "2024–", "poster": f"{root}/media/poster_pixel_city.jpg",
                  "background": f"{root}/media/backdrop_pixel_city.jpg", "description": "A detective chases a missing frame.", "genres": ["Mystery"],
                  "certification": "TV-14",
                  "videos": [{"id": f"tst:pixel_city:{s}:{e}", "title": f"Episode {e}", "season": s, "episode": e, "thumbnail": f"{root}/media/ep_{e}.jpg"}
                             for s in (1, 2) for e in (1, 2, 3)]}
        if parts == ["manifest.json"]:
            return self.send(200, json.dumps({
                "id": "org.test.addon", "version": "1.2.0", "name": "Test Add-on", "description": "A Stremio add-on for tests.",
                "logo": f"{root}/media/poster.png", "types": ["movie", "series"], "idPrefixes": ["tst"],
                "resources": ["catalog", {"name": "meta", "types": ["movie", "series"], "idPrefixes": ["tst"]}, "stream", "subtitles"],
                "catalogs": [
                    {"type": "movie", "id": "top", "name": "Top films", "extra": [{"name": "search"}, {"name": "skip"}]},
                    {"type": "series", "id": "popular", "name": "Popular"},
                    {"type": "movie", "id": "searchonly", "name": "Search only", "extra": [{"name": "search", "isRequired": True}]},
                ]}))
        if len(parts) >= 3 and parts[0] == "catalog":
            kind, cid = parts[1], parts[2].removesuffix(".json")
            extra = parts[3].removesuffix(".json") if len(parts) > 3 else ""
            if kind == "series":
                return self.send(200, json.dumps({"metas": [{k: v for k, v in series.items() if k != "videos"}]}))
            metas = [meta(fid) for fid in ITEMS]
            if extra.startswith("search="):
                q = urllib.parse.unquote(extra.removeprefix("search=")).lower()
                metas = [m for m in metas if q in m["name"].lower()]
            elif extra.startswith("skip="):
                skip = int(extra.removeprefix("skip="))
                metas = metas[skip:skip + 6]
            else:
                metas = metas[:6]
            return self.send(200, json.dumps({"metas": metas}))
        if len(parts) == 3 and parts[0] == "meta":
            mid = urllib.parse.unquote(parts[2].removesuffix(".json"))
            if mid == series["id"]:
                return self.send(200, json.dumps({"meta": series}))
            fid = mid.removeprefix("tst:")
            if fid in ITEMS:
                return self.send(200, json.dumps({"meta": meta(fid, full=True)}))
            return self.send(404, "{}")
        if len(parts) == 3 and parts[0] == "stream":
            vid = urllib.parse.unquote(parts[2].removesuffix(".json"))
            fid = vid.split(":")[1] if ":" in vid else vid
            film = fid if fid in ITEMS else "night_of_the_test_pattern"
            return self.send(200, json.dumps({"streams": [
                {"name": "Test Add-on\n360p", "title": "Direct MP4", "url": f"{root}/download/{film}/test_film_640.mp4",
                 "behaviorHints": {"notWebReady": True, "proxyHeaders": {"request": {"Referer": f"{root}/"}}}},
                {"name": "Test Add-on 720p", "title": "HLS", "url": f"{root}/hls/master.m3u8",
                 "behaviorHints": {"proxyHeaders": {"request": {"X-CS-Token": "demo"}}}},
                {"name": "Torrent", "title": "1080p torrent", "infoHash": "0123456789abcdef0123456789abcdef01234567"},
            ]}))
        if len(parts) == 3 and parts[0] == "subtitles":
            return self.send(200, json.dumps({"subtitles": [{"id": "s1", "lang": "eng", "url": f"{root}/stremio/subs/file/eng.srt"}]}))
        return self.send(404, "{}")

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(url.query)
        parts = [urllib.parse.unquote(p) for p in url.path.split("/") if p]
        if url.path == "/advancedsearch.php":
            q = qs.get("q", [""])[0].lower()
            words = q.split("title:(")[1].rstrip(")").split() if "title:(" in q else []
            docs = [{"identifier": k, "title": v["title"], "year": v["year"]} for k, v in ITEMS.items()
                    if all(w in v["title"].lower() for w in words)]
            return self.send(200, json.dumps({"response": {"numFound": len(docs), "docs": docs}}))
        if len(parts) == 2 and parts[0] == "metadata" and parts[1] in ITEMS:
            return self.send(200, json.dumps({"metadata": ITEMS[parts[1]], "files": FILES}))
        if len(parts) == 3 and parts[:2] == ["services", "img"]:
            poster = os.path.join(MEDIA, f"poster_{parts[2]}.jpg")
            if not os.path.exists(poster):
                poster = os.path.join(MEDIA, "poster.png")
            return self.send(200, open(poster, "rb").read(), "image/jpeg")
        if len(parts) == 2 and parts[0] == "media":
            path = os.path.join(MEDIA, os.path.basename(parts[1]))
            if os.path.exists(path):
                return self.send(200, open(path, "rb").read(), "image/jpeg")
        if len(parts) == 2 and parts[0] == "hls":
            if self.headers.get("X-CS-Token") != "demo":
                sys.stderr.write(f"REJECTED {self.path}: missing X-CS-Token\n")
                return self.send(403, "token required", "text/plain")
            if parts[1] == "master.m3u8":
                return self.send(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=480x270\nindex.m3u8\n",
                                 "application/vnd.apple.mpegurl")
            path = os.path.join(MEDIA, "hls", os.path.basename(parts[1]))
            if not os.path.exists(path):
                return self.send(404, "not found", "text/plain")
            ctype = "application/vnd.apple.mpegurl" if path.endswith(".m3u8") else "video/mp2t"
            return self.send(200, open(path, "rb").read(), ctype)
        if parts[:3] == ["stremio", "meta", "catalog"] and len(parts) == 6:
            # Cinemeta: /catalog/<type>/top/search=<query>.json
            query = parts[5].removeprefix("search=").removesuffix(".json").lower()
            metas = [{"id": "tt9000001", "type": "series", "name": "Pixel City", "releaseInfo": "2024"},
                     {"id": "tt9000002", "type": "movie", "name": "Night of the Test Pattern", "releaseInfo": "1968"}]
            return self.send(200, json.dumps({"metas": [m for m in metas if query in m["name"].lower()]}))
        if parts[:3] == ["stremio", "subs", "subtitles"] and len(parts) == 5:
            # OpenSubtitles v3: /subtitles/<type>/<imdb>[:season:episode].json
            video = parts[4].removesuffix(".json")
            base = f"http://localhost:{self.server.server_port}/stremio/subs/file"
            subs = [{"id": f"{video}-{i}", "url": f"{base}/{lang}.srt?n={i}", "lang": lang}
                    for i, lang in enumerate(["eng", "eng", "spa", "fre", "ara"])]
            return self.send(200, json.dumps({"subtitles": subs}))
        if parts[:3] == ["stremio", "subs", "file"] and len(parts) == 4:
            lang = parts[3].removesuffix(".srt")
            text = {"eng": "Online subtitle", "spa": "Subtítulo en línea", "fre": "Sous-titre en ligne", "ara": "ترجمة"}.get(lang, lang)
            srt = "".join(f"{n}\n00:{m:02d}:00,000 --> 00:{m:02d}:59,000\n{text} ({lang}) — minute {m}\n\n" for n, m in enumerate(range(0, 3), 1))
            return self.send(200, srt, "application/x-subrip")
        if parts and parts[0] == "addon":
            return self.addon(parts[1:])
        if parts and parts[0] == "iptv":
            return self.iptv(parts[1:])
        if parts and parts[0] == "xtream":
            return self.xtream(url.path, qs)
        if parts and parts[0] == "addon-tv":
            return self.addon_tv(parts[1:])
        if parts[:2] == ["tmdb", "3"]:
            return self.tmdb("/" + "/".join(parts[2:]), qs)
        if parts[:2] == ["tmdbimg", "w1280"] or (parts and parts[0] == "tmdbimg"):
            path = os.path.join(MEDIA, os.path.basename(parts[-1]))
            if os.path.exists(path):
                return self.send(200, open(path, "rb").read(), "image/png" if path.endswith(".png") else "image/jpeg")
            return self.send(404, "", "text/plain")
        if parts[:3] == ["stremio", "meta", "meta"] and len(parts) == 5:
            return self.send(200, json.dumps({"meta": {"id": parts[4].removesuffix(".json"), "imdbRating": "8.4"}}))
        if url.path == "/slow":
            # A slow extractor: answers after a few seconds.
            time.sleep(float(qs.get("s", ["8"])[0]))
            return self.send(200, "ok", "text/plain")
        if url.path == "/hang":
            # A dead extractor host: accepts the connection and never answers.
            time.sleep(600)
            return self.send(504, "gave up", "text/plain")
        if len(parts) == 3 and parts[0] == "download" and parts[1] in ITEMS:
            path = os.path.join(MEDIA, os.path.basename(parts[2]))
            if not os.path.exists(path):
                return self.send(404, "not found", "text/plain")
            if not self.headers.get("Referer", "").startswith(("http://localhost:", "http://127.0.0.1:")):
                sys.stderr.write(f"REJECTED {self.path}: missing/bad Referer {self.headers.get('Referer')!r}\n")
                return self.send(403, "referer required", "text/plain")
            sys.stderr.write(f"SERVED {self.path} Referer={self.headers.get('Referer')} UA={self.headers.get('User-Agent','')[:40]}\n")
            ctype = "video/mp4" if path.endswith(".mp4") else "text/plain"
            data = open(path, "rb").read()
            rng = self.headers.get("Range", "")
            if rng.startswith("bytes="):
                # Range requests, like real video hosts: players seek with these.
                start_s, _, end_s = rng[6:].partition("-")
                start = int(start_s or 0)
                end = min(int(end_s) if end_s else len(data) - 1, len(data) - 1)
                chunk = data[start:end + 1]
                self.send_response(206)
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.send_header("Content-Length", str(len(chunk)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(chunk)
                return
            return self.send(200, data, ctype)
        self.send(404, "not found", "text/plain")

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.base = f"http://localhost:{port}"
    print(f"mock archive.org on {server.base}", flush=True)
    server.serve_forever()
