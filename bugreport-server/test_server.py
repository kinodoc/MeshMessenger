import importlib.util
import io
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

TEMP = tempfile.TemporaryDirectory()
os.environ["BUGREPORT_DB"] = str(Path(TEMP.name) / "reports.sqlite3")
os.environ["REPORT_DIR"] = str(Path(TEMP.name) / "archives")
spec = importlib.util.spec_from_file_location(
    "bugreport_server", Path(__file__).with_name("server.py"))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)

def archive(log_text, extra_name="diagnostics.txt"):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(extra_name, log_text)
    return out.getvalue()

class FingerprintTests(unittest.TestCase):
    def test_same_error_ignores_device_and_timestamp_changes(self):
        first = archive(
            "device_id: phone-A\n2026-10-01 12:00:00| ERROR MeshRouter: connect failed\n"
            "java.lang.IllegalStateException: no peer\n at MeshRouter.send(MeshRouter.kt:42)\n")
        second = archive(
            "device_id: phone-B\n2026-10-02 18:30:55| ERROR MeshRouter: connect failed\n"
            "java.lang.IllegalStateException: no peer\n at MeshRouter.send(MeshRouter.kt:42)\n")
        self.assertEqual(server.fingerprint_zip(first), server.fingerprint_zip(second))

    def test_repeated_status_snapshots_and_created_time_are_ignored(self):
        first = archive(
            "created=2026-10-04T10:00:00Z\n"
            "2026-10-04 10:00:01| STATUS|ble=0,relay=0\n"
            "2026-10-04 10:00:02| STATUS|ble=0,relay=0\n"
            "2026-10-04 10:00:03| ERROR|GATT connection timeout\n")
        second = archive(
            "created=2026-10-05T11:30:00Z\n"
            "2026-10-05 11:30:01| STATUS|ble=0,relay=0\n"
            "2026-10-05 11:30:03| ERROR|GATT connection timeout\n")
        self.assertEqual(server.fingerprint_zip(first), server.fingerprint_zip(second))

    def test_distinct_status_transition_is_not_removed(self):
        first = archive("2026-10-04 10:00:01| STATUS|ble=0,relay=0\n")
        second = archive(
            "2026-10-04 10:00:01| STATUS|ble=0,relay=0\n"
            "2026-10-04 10:00:02| STATUS|ble=1,relay=0\n")
        self.assertNotEqual(server.fingerprint_zip(first), server.fingerprint_zip(second))

    def test_different_stack_is_not_deduplicated(self):
        first = archive("ERROR: connection refused\n at MeshRouter.send(MeshRouter.kt:42)")
        second = archive("ERROR: connection refused\n at MeshRouter.send(MeshRouter.kt:99)")
        self.assertNotEqual(server.fingerprint_zip(first), server.fingerprint_zip(second))

    def test_invalid_zip_is_rejected(self):
        with self.assertRaises(ValueError):
            server.fingerprint_zip(b"not a zip archive")

    def test_opaque_archives_are_conservative(self):
        first = archive("binary payload", "payload.bin")
        second = archive("different binary payload", "payload.bin")
        self.assertNotEqual(server.fingerprint_zip(first), server.fingerprint_zip(second))

    def test_private_archives_expire_but_dedupe_record_remains(self):
        report_dir = Path(os.environ["REPORT_DIR"])
        report_dir.mkdir(parents=True, exist_ok=True)
        old_archive = report_dir / "old.zip"
        old_archive.write_bytes(b"private")
        old_time = 1_000_000
        os.utime(old_archive, (old_time, old_time))
        with __import__("sqlite3").connect(os.environ["BUGREPORT_DB"]) as db:
            db.execute("INSERT INTO reports VALUES(?,?,?,?)",
                       ("fingerprint-test", 7, old_time, "old.zip"))
        server.cleanup_archives(now=old_time + server.ARCHIVE_RETENTION_SECONDS + 1)
        self.assertFalse(old_archive.exists())
        with __import__("sqlite3").connect(os.environ["BUGREPORT_DB"]) as db:
            row = db.execute("SELECT issue_number, archive_name FROM reports WHERE fingerprint=?",
                             ("fingerprint-test",)).fetchone()
        self.assertEqual(row, (7, ""))

if __name__ == "__main__":
    unittest.main()


# HTTP-level integration tests. The outbound GitHub API is always mocked;
# these tests can never create real GitHub issues.
import http.client
import json
import threading
import urllib.error
from unittest import mock


class MockGitHubResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self, limit=-1):
        return self.payload[:limit]


class HttpHandlerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.httpd.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        self.old_token = server.TOKEN
        server.TOKEN = "test-token-not-a-real-secret"
        server.rates.clear()
        report_dir = Path(os.environ["REPORT_DIR"])
        report_dir.mkdir(parents=True, exist_ok=True)
        for path in report_dir.glob("*.zip"):
            path.unlink()
        with __import__("sqlite3").connect(os.environ["BUGREPORT_DB"]) as db:
            db.execute("DELETE FROM reports")

    def tearDown(self):
        server.TOKEN = self.old_token

    def post(self, payload):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            connection.request(
                "POST", "/api/bugreports", body=payload,
                headers={"Content-Type": "application/zip",
                         "Content-Length": str(len(payload)),
                         "X-Mesh-Version": "0.99-test"})
            response = connection.getresponse()
            return response.status, json.loads(response.read().decode("utf-8"))
        finally:
            connection.close()

    def test_post_success_creates_one_issue_and_private_archive(self):
        payload = archive("ERROR MeshRouter: mocked success\\n at MeshRouter.send(MeshRouter.kt:7)\\n")
        with mock.patch.object(
            server.urllib.request, "urlopen",
            return_value=MockGitHubResponse(b'{"number":123}')) as github:
            status, body = self.post(payload)
        self.assertEqual(status, 201)
        self.assertEqual(body, {"ok": True, "duplicate": False, "status": "sent"})
        self.assertEqual(github.call_count, 1)
        saved = list(Path(os.environ["REPORT_DIR"]).glob("*.zip"))
        self.assertEqual(len(saved), 1)
        self.assertEqual(saved[0].read_bytes(), payload)
        self.assertEqual(saved[0].stat().st_mode & 0o777, 0o600)

    def test_duplicate_returns_existing_result_without_second_github_call(self):
        payload = archive("ERROR MeshRouter: duplicate test\\n at MeshRouter.send(MeshRouter.kt:8)\\n")
        with mock.patch.object(
            server.urllib.request, "urlopen",
            return_value=MockGitHubResponse(b'{"number":124}')) as github:
            first_status, first_body = self.post(payload)
            second_status, second_body = self.post(payload)
        self.assertEqual(first_status, 201)
        self.assertEqual(second_status, 200)
        self.assertTrue(first_body["ok"])
        self.assertTrue(second_body["duplicate"])
        self.assertEqual(github.call_count, 1)

    def test_invalid_zip_returns_400_without_calling_github(self):
        with mock.patch.object(server.urllib.request, "urlopen") as github:
            status, body = self.post(b"not a zip archive")
        self.assertEqual(status, 400)
        self.assertEqual(body["error"], "invalid_zip")
        github.assert_not_called()
        self.assertEqual(list(Path(os.environ["REPORT_DIR"]).glob("*.zip")), [])

    def test_github_api_rejection_returns_502_and_removes_archive(self):
        payload = archive("ERROR MeshRouter: upstream rejection\\n at MeshRouter.send(MeshRouter.kt:9)\\n")
        error = urllib.error.HTTPError(
            "https://api.github.com/repos/kinodoc/MeshMessenger/issues",
            403, "mock forbidden", {}, None)
        with mock.patch.object(server.urllib.request, "urlopen", side_effect=error) as github:
            status, body = self.post(payload)
        self.assertEqual(status, 502)
        self.assertFalse(body["ok"])
        self.assertEqual(body["error"], "github_rejected_report")
        github.assert_called_once()
        self.assertEqual(list(Path(os.environ["REPORT_DIR"]).glob("*.zip")), [])
        with __import__("sqlite3").connect(os.environ["BUGREPORT_DB"]) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM reports").fetchone()[0], 0)
