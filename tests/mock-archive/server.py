#!/usr/bin/env python3
"""Minimal stand-in for the archive.org endpoints InternetArchiveProvider uses.

Serves /advancedsearch.php, /metadata/<id>, /download/<id>/<file>, /services/img/<id>.
Downloads require a Referer header, like many real streaming hosts, so tests can prove
that headers flow from the extension's ExtractorLink to the player.

Also: /hls/ (an HLS stream that needs an "X-CS-Token: demo" header on every request,
like token-protected hosts), and /stremio/ stand-ins for the Cinemeta and OpenSubtitles
addons the player's online subtitle search uses.
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
