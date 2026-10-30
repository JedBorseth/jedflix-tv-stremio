#!/usr/bin/env python3
"""Serve a local test clip or open a direct URL in the retained APK player."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote
import zlib

ROOT = Path(__file__).resolve().parents[1]
ADB = str(ROOT / "scripts/adb.sh")
SERIAL = os.environ.get("JEDFLIX_SERIAL", "emulator-5554")

class ClipHandler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_clip(head_only=True)

    def do_GET(self):
        self.send_clip()

    def send_clip(self, head_only=False):
        if self.path != "/test.mp4":
            self.send_error(404)
            return
        path = self.server.clip_path
        size = path.stat().st_size
        start, end = 0, size - 1
        partial = self.headers.get("Range")
        if partial:
            try:
                first, last = partial.removeprefix("bytes=").split("-")
                start = int(first)
                end = min(int(last), end) if last else end
                assert 0 <= start <= end
            except (ValueError, AssertionError):
                self.send_error(416)
                return
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head_only:
            return
        try:
            with path.open("rb") as source:
                source.seek(start)
                remaining = end - start + 1
                while remaining:
                    block = source.read(min(65536, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError):
            pass

parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["serve", "play"])
parser.add_argument("--url", default="http://127.0.0.1:8765/test.mp4")
parser.add_argument("--bind", default="127.0.0.1", help="HTTP listen address; use the LAN address for the Windows emulator")
parser.add_argument("--port", type=int, default=8765)
parser.add_argument("--file", type=Path, default=ROOT / ".local/spike-media/test.mp4")
args = parser.parse_args()
if args.mode == "serve":
    server = ThreadingHTTPServer((args.bind, args.port), ClipHandler)
    server.clip_path = args.file
    server.serve_forever()
else:
    stream = {"url": args.url, "name": "Jedflix feasibility test", "title": "Generated test clip"}
    data = base64.b64encode(zlib.compress(json.dumps(stream).encode())).decode()
    link = "stremio:///player/" + quote(data, safe="")
    subprocess.run([ADB, "-s", SERIAL, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", link, "-n", "com.stremio.one/com.stremio.tv.MainActivity"], check=True)
