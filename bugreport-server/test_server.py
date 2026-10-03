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
