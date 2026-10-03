#!/usr/bin/env python3
"""Private MeshMessenger bug-report receiver with persistent deduplication."""
import hashlib
import io
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import urllib.error
import urllib.request
import zipfile
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = os.environ.get("BUGREPORT_HOST", "127.0.0.1")
PORT = int(os.environ.get("BUGREPORT_PORT", "8765"))
TOKEN = os.environ.get("GITHUB_TOKEN", "")
REPO = os.environ.get("GITHUB_REPO", "kinodoc/MeshMessenger")
REPORT_DIR = Path(os.environ.get("REPORT_DIR", "/var/lib/mesh-bugreports"))
DB_PATH = os.environ.get("BUGREPORT_DB", "/var/lib/mesh-bugreports/reports.sqlite3")
MAX_BYTES = min(max(int(os.environ.get("MAX_UPLOAD_BYTES", str(10 * 1024 * 1024))), 1024), 10 * 1024 * 1024)
MAX_ENTRY_BYTES = 2_000_000
ARCHIVE_RETENTION_SECONDS = 14 * 86400
WINDOW = 3600
MAX_HOUR, MAX_DAY = 5, 20
rates = defaultdict(deque)
rate_lock = threading.Lock()
issue_lock = threading.Lock()

Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
with sqlite3.connect(DB_PATH) as db:
    db.execute("""CREATE TABLE IF NOT EXISTS reports (
        fingerprint TEXT PRIMARY KEY, issue_number INTEGER NOT NULL,
        created_at INTEGER NOT NULL, archive_name TEXT NOT NULL)""")

def fingerprint_zip(data):
    """Hash normalized diagnostic text, not ZIP metadata or timestamps."""
    parts = []
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            infos = zf.infolist()
            if not infos or len(infos) > 500:
                raise ValueError("invalid_zip")
            for info in sorted(infos, key=lambda x: x.filename):
                if info.is_dir():
                    continue
                if info.file_size > MAX_ENTRY_BYTES:
                    continue
                name = info.filename.lower()
                if not name.endswith((".txt", ".log", ".json", ".xml", ".kt", ".trace")):
                    continue
                try:
                    text = zf.read(info).decode("utf-8", "replace")
                except (KeyError, OSError, RuntimeError, zipfile.BadZipFile):
                    continue
                text = re.sub(r"(?m)^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?\|", "<TIMESTAMP>|", text)
                text = re.sub(r"(?im)^.*(?:timestamp|time|date|device.?id|node.?id|mac|ip address)\s*[:=].*$", "", text)
                text = re.sub(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}\b", "<UUID>", text)
                text = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "<IP>", text)
                text = re.sub(r"\b\d{10,13}\b", "<TIME>", text)
                text = re.sub(r"\s+", " ", text).strip().lower()
                if text:
                    parts.append(name + ":" + text[:200_000])
    except (zipfile.BadZipFile, OSError, ValueError) as exc:
        raise ValueError("invalid_zip") from exc
    basis = "\n".join(parts) if parts else "opaque:" + hashlib.sha256(data).hexdigest()
    return hashlib.sha256(basis.encode("utf-8", "replace")).hexdigest()

def cleanup_archives(now=None):
    """Delete private ZIPs after 14 days while retaining dedupe fingerprints."""
    cutoff = (int(time.time()) if now is None else int(now)) - ARCHIVE_RETENTION_SECONDS
    REPORT_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    with sqlite3.connect(DB_PATH, timeout=10) as db:
        rows = db.execute("SELECT fingerprint, created_at, archive_name FROM reports WHERE created_at < ?", (cutoff,)).fetchall()
        for fingerprint, created_at, archive_name in rows:
            # Names are generated server-side, but reject paths defensively.
            if archive_name and Path(archive_name).name == archive_name:
                (REPORT_DIR / archive_name).unlink(missing_ok=True)
            db.execute("UPDATE reports SET archive_name='' WHERE fingerprint=?", (fingerprint,))
    # Also remove orphaned old ZIPs left by interrupted uploads.
    for path in REPORT_DIR.glob("*.zip"):
        try:
            if path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)
        except OSError:
            pass

def retention_worker():
    while True:
        try:
            cleanup_archives()
        except Exception as exc:
            print("archive cleanup failed: " + type(exc).__name__, flush=True)
        time.sleep(24 * 3600)

def request_ip(handler):
    """Trust X-Forwarded-For only from loopback, where Nginx overwrites it."""
    peer = handler.client_address[0]
    if peer in ("127.0.0.1", "::1"):
        forwarded = handler.headers.get("X-Forwarded-For", "").strip()
        if forwarded and len(forwarded) <= 64 and re.fullmatch(r"[0-9a-fA-F:.]+", forwarded):
            return forwarded
    return peer

class Handler(BaseHTTPRequestHandler):
    server_version = "MeshBugReport/2.1"

    def respond(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"ok": True, "service": "mesh-bugreport"})
        return self.respond(404, {"ok": False, "error": "not_found"})

    def do_POST(self):
        if self.path != "/api/bugreports":
            return self.respond(404, {"ok": False, "error": "not_found"})
        if not TOKEN:
            return self.respond(503, {"ok": False, "error": "service_not_configured"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond(400, {"ok": False, "error": "invalid_content_length"})
        if length < 4 or length > MAX_BYTES:
            return self.respond(413, {"ok": False, "error": "invalid_upload_size"})
        if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/zip":
            return self.respond(415, {"ok": False, "error": "expected_zip"})
        body = self.rfile.read(length)
        if len(body) != length:
            return self.respond(400, {"ok": False, "error": "invalid_upload"})
        ip = request_ip(self)
        now = int(time.time())
        with rate_lock:
            q = rates[ip]
            while q and now - q[0] > 86400:
                q.popleft()
            hour_count = sum(1 for stamp in q if now - stamp <= WINDOW)
            if hour_count >= MAX_HOUR or len(q) >= MAX_DAY:
                return self.respond(429, {"ok": False, "error": "rate_limited"})
            q.append(now)
        try:
            fp = fingerprint_zip(body)
        except ValueError:
            return self.respond(400, {"ok": False, "error": "invalid_zip"})
        version = re.sub(r"[^A-Za-z0-9._+-]", "", self.headers.get("X-Mesh-Version", "unknown"))[:40] or "unknown"
        REPORT_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
        archive_name = secrets.token_urlsafe(18) + ".zip"
        target = REPORT_DIR / archive_name
        with issue_lock:
            with sqlite3.connect(DB_PATH, timeout=10) as db:
                row = db.execute("SELECT issue_number FROM reports WHERE fingerprint=?", (fp,)).fetchone()
            if row:
                return self.respond(200, {"ok": True, "duplicate": True, "status": "duplicate"})
            try:
                fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as archive:
                    archive.write(body)
                title = "MeshMessenger bugreport (" + version + ")"
                body_text = (
                    "Автоматический багрепорт MeshMessenger.\n\n"
                    "- Версия приложения: " + version + "\n"
                    "- Идентификатор отчёта: " + fp[:16] + "\n\n"
                    "Архив диагностики хранится приватно на сервере и не публикуется."
                )
                payload = json.dumps({"title": title, "body": body_text}).encode("utf-8")
                req = urllib.request.Request(
                    "https://api.github.com/repos/" + REPO + "/issues",
                    data=payload,
                    headers={"Authorization": "Bearer " + TOKEN,
                             "Accept": "application/vnd.github+json",
                             "X-GitHub-Api-Version": "2022-11-28",
                             "Content-Type": "application/json",
                             "User-Agent": "MeshMessenger-BugReport-Receiver"},
                    method="POST")
                with urllib.request.urlopen(req, timeout=15) as response:
                    result = json.loads(response.read(1024 * 1024))
                number = int(result["number"])
                with sqlite3.connect(DB_PATH, timeout=10) as db:
                    db.execute("INSERT INTO reports(fingerprint,issue_number,created_at,archive_name) VALUES(?,?,?,?)",
                               (fp, number, now, archive_name))
                return self.respond(201, {"ok": True, "duplicate": False, "status": "sent"})
            except urllib.error.HTTPError as exc:
                target.unlink(missing_ok=True)
                code = "github_rejected_report" if exc.code in (400, 401, 403, 404, 422) else "upstream_unavailable"
                return self.respond(502, {"ok": False, "error": code})
            except Exception:
                target.unlink(missing_ok=True)
                return self.respond(502, {"ok": False, "error": "upstream_unavailable"})

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.client_address[0], fmt % args), flush=True)

if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("Set GITHUB_TOKEN in service environment")
    REPORT_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    cleanup_archives()
    threading.Thread(target=retention_worker, daemon=True, name="archive-retention").start()
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
