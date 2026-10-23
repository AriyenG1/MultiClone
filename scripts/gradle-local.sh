#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${MULTICLONE_JAVA_HOME:-$PWD/.tools/jdk/Contents/Home}"
export ANDROID_HOME="${MULTICLONE_SDK:-$PWD/.tools/android-sdk}"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
exec ./gradlew "$@"
