#!/usr/bin/env python3
"""Minimal MeshMessenger bug-report receiver. Configure secrets via environment only."""
import json
import os
import re
import secrets
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = os.environ.get("BUGREPORT_HOST", "127.0.0.1")
PORT = int(os.environ.get("BUGREPORT_PORT", "8765"))
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
GITHUB_REPO = os.environ.get("GITHUB_REPO", "kinodoc/MeshMessenger")
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
REPORT_DIR = Path(os.environ.get("REPORT_DIR", "/var/lib/mesh-bugreports"))
MAX_BYTES = int(os.environ.get("MAX_UPLOAD_BYTES", str(10 * 1024 * 1024)))
WINDOW_SECONDS = 3600
MAX_PER_WINDOW = 5
MAX_PER_DAY = 20
requests = defaultdict(deque)
daily = defaultdict(deque)

class Handler(BaseHTTPRequestHandler):
    server_version = "MeshBugReport/1.0"

    def respond(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/health":
            return self.respond(404, {"ok": False, "error": "not_found"})
        self.respond(200, {"ok": True, "service": "mesh-bugreport"})

    def do_POST(self):
        if self.path != "/api/bugreports":
            return self.respond(404, {"ok": False, "error": "not_found"})
        if not GITHUB_TOKEN or not PUBLIC_BASE_URL:
            return self.respond(503, {"ok": False, "error": "service_not_configured"})
        ip = self.client_address[0]
        now = time.time()
        hour = requests[ip]
        while hour and now - hour[0] > WINDOW_SECONDS: hour.popleft()
        day = daily[ip]
        while day and now - day[0] > 86400: day.popleft()
        if len(hour) >= MAX_PER_WINDOW or len(day) >= MAX_PER_DAY:
            return self.respond(429, {"ok": False, "error": "rate_limited"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond(400, {"ok": False, "error": "invalid_content_length"})
        if length < 4 or length > MAX_BYTES:
            return self.respond(413, {"ok": False, "error": "invalid_upload_size"})
        content_type = self.headers.get("Content-Type", "").split(";")[0].strip().lower()
        if content_type != "application/zip":
            return self.respond(415, {"ok": False, "error": "expected_zip"})
        body = self.rfile.read(length)
        if len(body) != length or not (body.startswith(b"PK\\x03\\x04") or body.startswith(b"PK\\x05\\x06")):
            return self.respond(400, {"ok": False, "error": "invalid_zip"})
        version = re.sub(r"[^A-Za-z0-9._+-]", "", self.headers.get("X-Mesh-Version", "unknown"))[:40] or "unknown"
        report_id = secrets.token_urlsafe(18)
        REPORT_DIR.mkdir(parents=True, exist_ok=True, mode=0o750)
        target = REPORT_DIR / (report_id + ".zip")
        target.write_bytes(body)
        try:
            os.chmod(target, 0o640)
            report_url = PUBLIC_BASE_URL + "/reports/" + report_id + ".zip"
            title = "MeshMessenger bugreport (" + version + ")"
            issue_body = (
                "Автоматический багрепорт из MeshMessenger.\\n\\n"
                "- Версия приложения: " + version + "\\n"
                "- Архив диагностики: " + report_url + "\\n\\n"
                "Архив содержит техническую диагностику устройства. Не публикуйте в нём "
                "личные данные; доступ к ссылке следует ограничить сроком хранения."
            )
            payload = json.dumps({"title": title, "body": issue_body}).encode()
            req = urllib.request.Request(
                "https://api.github.com/repos/" + GITHUB_REPO + "/issues",
                data=payload,
                headers={
                    "Authorization": "Bearer " + GITHUB_TOKEN,
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28",
                    "Content-Type": "application/json",
                    "User-Agent": "MeshMessenger-BugReport-Receiver",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=15) as response:
                result = json.loads(response.read(1024 * 1024))
            requests[ip].append(now)
            daily[ip].append(now)
            self.respond(201, {"ok": True, "issue_url": result.get("html_url", ""), "issue_number": result.get("number")})
        except urllib.error.HTTPError as exc:
            # Never expose the token or raw upstream response to the client.
            target.unlink(missing_ok=True)
            self.respond(502, {"ok": False, "error": "github_rejected_report", "status": exc.code})
        except Exception:
            target.unlink(missing_ok=True)
            self.respond(502, {"ok": False, "error": "upstream_unavailable"})

    def log_message(self, fmt, *args):
        # Avoid logging uploaded data or request headers.
        print("%s - %s" % (self.client_address[0], fmt % args), flush=True)

if __name__ == "__main__":
    if not GITHUB_TOKEN or not PUBLIC_BASE_URL.startswith("https://"):
        raise SystemExit("Set GITHUB_TOKEN and HTTPS PUBLIC_BASE_URL in the service environment")
    REPORT_DIR.mkdir(parents=True, exist_ok=True, mode=0o750)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
