#!/usr/bin/env python3
"""No-cache static server for the YAMP design canvas.

Plain `python3 -m http.server` sends no cache headers, so browsers apply
heuristic caching and serve stale HTML/JSX on a normal reload. This server sends
no-store on every response, so a normal reload (no hard-refresh needed) always
gets the fresh files.

It serves the folder this script lives in, whatever directory it is started
from, plus one read-only alias: /shots/<name>.png → the app's golden test
screenshots (android/app/src/test/screenshots), used by the "As built" sections.

Standard library only: no venv, nothing to install.

    python3 Design/serve.py [port]      (default port 5520)

then open http://localhost:5520/YAMP.html
"""

import os
import re
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5520
ROOT = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.normpath(os.path.join(ROOT, "..", "android", "app", "src", "test", "screenshots"))
SHOT_URL = re.compile(r"^/shots/([A-Za-z0-9_.-]+\.png)$")


class NoCacheHandler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        # Only a bare file name is accepted, so the alias cannot leave the screenshots folder.
        m = SHOT_URL.match(unquote(urlsplit(path).path))
        if m:
            return os.path.join(SHOTS, m.group(1))
        return super().translate_path(path)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()


if __name__ == "__main__":
    try:
        server = ThreadingHTTPServer(("", PORT), partial(NoCacheHandler, directory=ROOT))
    except OSError as e:
        sys.exit(f"Cannot listen on port {PORT}: {e.strerror}. "
                 f"Another server is probably running; stop it or pass a different port.")
    print(f"Serving {ROOT} (no-cache) on http://localhost:{PORT}/YAMP.html  —  Ctrl+C to stop")
    server.serve_forever()
