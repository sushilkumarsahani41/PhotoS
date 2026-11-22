#!/usr/bin/env python3
"""Build a signed release using a private JSON credentials file outside the repo."""
import json
import os
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 tools/build_release.py /private/path/signing-credentials.json")
credentials = Path(sys.argv[1]).expanduser().resolve()
config = json.loads(credentials.read_text())
env = os.environ.copy()
env.update({
    "PHOTOS_KEYSTORE": str((credentials.parent / config["keystore"]).resolve()),
    "PHOTOS_STORE_PASSWORD": config["store_password"],
    "PHOTOS_KEY_ALIAS": config["alias"],
    "PHOTOS_KEY_PASSWORD": config["key_password"],
})
root = Path(__file__).resolve().parent.parent
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
subprocess.run([str(wrapper), "--no-configuration-cache", "assembleRelease"], cwd=root, env=env, check=True)
print("Signed APK: app/build/outputs/apk/release/app-release.apk")
