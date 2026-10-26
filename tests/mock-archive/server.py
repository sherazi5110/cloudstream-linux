#!/usr/bin/env python3
"""Minimal stand-in for the archive.org endpoints InternetArchiveProvider uses.

Serves /advancedsearch.php, /metadata/<id>, /download/<id>/<file>, /services/img/<id>.
Downloads require a Referer header, like many real streaming hosts, so tests can prove
that headers flow from the extension's ExtractorLink to the player.
"""
import json, os, sys, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
ITEMS = {
    "night_of_the_test_pattern": {"title": "Night of the Test Pattern", "year": "1968",
        "description": "<p>A public-domain <b>test</b> film.</p>"},
    "the_general_test": {"title": "The General Test", "year": "1926",
        "description": "A silent comedy about color bars."},
}
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
            return self.send(200, open(os.path.join(MEDIA, "poster.png"), "rb").read(), "image/png")
        if len(parts) == 3 and parts[0] == "download" and parts[1] in ITEMS:
            path = os.path.join(MEDIA, os.path.basename(parts[2]))
            if not os.path.exists(path):
                return self.send(404, "not found", "text/plain")
            if not self.headers.get("Referer", "").startswith(self.server.base + "/"):
                sys.stderr.write(f"REJECTED {self.path}: missing/bad Referer {self.headers.get('Referer')!r}\n")
                return self.send(403, "referer required", "text/plain")
            sys.stderr.write(f"SERVED {self.path} Referer={self.headers.get('Referer')} UA={self.headers.get('User-Agent','')[:40]}\n")
            ctype = "video/mp4" if path.endswith(".mp4") else "text/plain"
            return self.send(200, open(path, "rb").read(), ctype)
        self.send(404, "not found", "text/plain")

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.base = f"http://localhost:{port}"
    print(f"mock archive.org on {server.base}", flush=True)
    server.serve_forever()
