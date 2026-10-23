#!/usr/bin/env python3
"""Local packaging/permission checks. This is not an antivirus verdict."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
apk = (root / sys.argv[1]).resolve()
sdk = Path(os.environ.get("MULTICLONE_SDK", str(root / ".tools/android-sdk")))
build_tools = sdk / "build-tools/36.0.0"
env = os.environ | {"JAVA_HOME": os.environ.get("MULTICLONE_JAVA_HOME", str(root / ".tools/jdk/Contents/Home"))}

def run(tool, *args):
    return subprocess.run([str(build_tools / tool), *args, str(apk)], env=env,
                          check=True, capture_output=True, text=True).stdout

permissions = sorted(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", run("aapt2", "dump", "permissions")))
expected = {
    "android.permission.INTERNET",
    "android.permission.QUERY_ALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_DELETE_PACKAGES", "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
}
# AndroidX Core's signature permission protects its internal dynamic receivers.
expected.add("dev.multiclone.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
assert set(permissions) == expected, f"Review changed permissions: {permissions}"
badging = run("aapt2", "dump", "badging")
assert "package: name='dev.multiclone.app'" in badging
assert "application-debuggable" not in badging
assert apk.read_bytes()[:4] == b"PK\x03\x04", "Unexpected APK header"
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert len(names) == len(set(names)), "Duplicate ZIP entries"
    assert all(not n.startswith("/") and ".." not in Path(n).parts for n in names)
    assert archive.testzip() is None, "APK ZIP CRC failure"
certificates = run("apksigner", "verify", "--verbose", "--print-certs")
run("zipalign", "-c", "-P", "16", "4")
report = {
    "apk": apk.name,
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "package": badging.splitlines()[0],
    "permissions": permissions,
    "certificateSha256": re.findall(r"certificate SHA-256 digest: (\w+)", certificates),
    "zipHeadersCrcPathsAndDuplicates": "passed",
    "apkSignatureAndZipAlignment": "passed",
    "debuggable": False,
    "limitation": "Local packaging and permission inspection only. No Google Play Protect verdict or malware-free certification; third-party SDK behavior is not exhaustively assessed.",
}
print(json.dumps(report, indent=2))
