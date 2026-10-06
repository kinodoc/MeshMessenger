#!/usr/bin/env python3
import hashlib
import json
import os
import re
import tempfile
import urllib.request
from html import unescape

REPO = "kinodoc/MeshMessenger"
ROOT = "/var/www/html/mesh-update"
RELEASE_PAGE = f"https://github.com/{REPO}/releases/latest"
USER_AGENT = "MeshMessenger-VPS-Updater/1.0"


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def atomic_write(path: str, data: bytes) -> None:
    fd, tmp = tempfile.mkstemp(prefix=".mesh-", dir=ROOT)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def main() -> None:
    os.makedirs(ROOT, mode=0o755, exist_ok=True)

    html = get(RELEASE_PAGE).decode("utf-8", errors="replace")
    tag_match = re.search(r'/kinodoc/MeshMessenger/releases/tag/([^"\\?]+)', html)
    if not tag_match:
        raise RuntimeError("Latest GitHub release tag not found")
    tag = unescape(tag_match.group(1)).removeprefix("v")
    apk_name = f"MeshMessenger-v{tag}.apk"
    apk_url = f"https://github.com/{REPO}/releases/download/v{tag}/{apk_name}"

    state_path = os.path.join(ROOT, "state.json")
    if os.path.exists(state_path):
        try:
            state = json.loads(open(state_path, encoding="utf-8").read())
            if state.get("tag") == tag and os.path.isfile(os.path.join(ROOT, state.get("apkName", ""))):
                return
        except Exception:
            pass

    gradle_url = f"https://raw.githubusercontent.com/{REPO}/v{tag}/app/build.gradle.kts"
    gradle = get(gradle_url).decode("utf-8")
    match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not match:
        raise RuntimeError("versionCode not found in release source")
    version_code = int(match.group(1))

    apk_bytes = get(apk_url)
    if len(apk_bytes) < 100_000:
        raise RuntimeError("Downloaded APK is unexpectedly small")

    sha256 = hashlib.sha256(apk_bytes).hexdigest()
    final_apk = os.path.join(ROOT, apk_name)
    atomic_write(final_apk, apk_bytes)

    update = {
        "version": tag,
        "versionCode": version_code,
        "apkName": apk_name,
        "apkUrl": f"https://194.87.186.159/mesh-update/{apk_name}",
        "sha256": sha256,
        "size": len(apk_bytes),
    }
    atomic_write(os.path.join(ROOT, "update.json"), (json.dumps(update, indent=2) + "\n").encode())
    atomic_write(os.path.join(ROOT, "state.json"), (json.dumps({"tag": tag, "apkName": apk_name}) + "\n").encode())

    print(f"Published MeshMessenger {tag} ({version_code}), {len(apk_bytes)} bytes")


if __name__ == "__main__":
    main()
