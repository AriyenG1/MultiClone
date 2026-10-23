#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${MULTICLONE_JAVA_HOME:-$PWD/.tools/jdk/Contents/Home}"
export ANDROID_HOME="${MULTICLONE_SDK:-$PWD/.tools/android-sdk}"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
mkdir -p .tools/downloads
if [ ! -f .tools/downloads/bundletool.jar ]; then
    curl -fL --retry 2 https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar -o .tools/downloads/bundletool.jar
fi
python3 - <<'PY'
import hashlib
from pathlib import Path
assert hashlib.sha256(Path('.tools/downloads/bundletool.jar').read_bytes()).hexdigest() == 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29', 'bundletool checksum mismatch'
Path('.tools/split-spec.json').write_text('{"supportedAbis":["arm64-v8a"],"supportedLocales":["en","fr"],"screenDensity":320,"sdkVersion":36}')
PY
./gradlew :test-app:assembleDebug -PfixtureVersion=2
cp test-app/build/outputs/apk/debug/test-app-debug.apk .tools/fixture-v2.apk
./gradlew :test-app:bundleDebug :test-app:assembleDebug -PfixtureVersion=1
"$JAVA_HOME/bin/java" -jar .tools/downloads/bundletool.jar build-apks \
    --bundle=test-app/build/outputs/bundle/debug/test-app-debug.aab \
    --output=.tools/fixture-splits.apks --device-spec=.tools/split-spec.json --overwrite
if [ ! -d .tools/compose-samples ]; then
    git clone --depth 1 --branch v2026.08.00 https://github.com/android/compose-samples.git .tools/compose-samples
fi
"$JAVA_HOME/bin/java" -version
./.tools/gradle-9.6.0/bin/gradle -p .tools/compose-samples/Jetchat :app:assembleDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
