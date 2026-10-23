#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${MULTICLONE_JAVA_HOME:-$PWD/.tools/jdk/Contents/Home}"
export ANDROID_HOME="${MULTICLONE_SDK:-$PWD/.tools/android-sdk}"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
mkdir -p private artifacts
chmod 700 private
if [ ! -f private/release-signing.p12 ]; then
    if [ -f private/release-password.txt ]; then
        echo 'A password file exists without its keystore. Restore the release keystore before packaging an update.' >&2
        exit 1
    fi
    python3 - <<'PY'
from pathlib import Path
import secrets
p = Path('private/release-password.txt')
p.write_text(secrets.token_urlsafe(48))
p.chmod(0o600)
PY
    "$JAVA_HOME/bin/keytool" -genkeypair -storetype PKCS12 -keystore private/release-signing.p12 \
        -storepass:file private/release-password.txt -keyalg RSA -keysize 3072 \
        -alias multiclone-release -dname 'CN=MultiClone local release' -validity 14600
    chmod 600 private/release-signing.p12
fi
./gradlew :app:assembleRelease :test-app:assembleDebug
release_version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
case "$release_version" in ''|*[!0-9.]*) echo 'Invalid release version' >&2; exit 1;; esac
release_apk="artifacts/MultiClone-v$release_version.apk"
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -P 16 -f 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk artifacts/MultiClone-aligned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks private/release-signing.p12 \
    --ks-key-alias multiclone-release --ks-pass file:private/release-password.txt \
    --out "$release_apk" artifacts/MultiClone-aligned.apk
rm artifacts/MultiClone-aligned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$release_apk"
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$release_apk"
cp test-app/build/outputs/apk/debug/test-app-debug.apk artifacts/MultiClone-TestApp.apk
if [ -f .tools/fixture-v2.apk ]; then cp .tools/fixture-v2.apk artifacts/MultiClone-TestApp-v2.apk; fi
shasum -a 256 "$release_apk" artifacts/MultiClone-TestApp*.apk > artifacts/SHA256SUMS.txt
echo 'Release APK created. Preserve private/ securely to sign future MultiClone updates.'
