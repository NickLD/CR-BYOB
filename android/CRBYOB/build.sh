#!/usr/bin/env bash
# Build (and by default install + launch) CR-BYOB through Gradle.
#   ./build.sh                      github flavor, debug: build + install + launch
#   ./build.sh --no-install         build only
#   VARIANT=PlayDebug ./build.sh    another variant (GithubRelease/PlayRelease need release signing)
# ADB_SERIAL selects the phone (e.g. 192.168.1.50:5555); without it adb uses the only device.
# ANDROID_SDK (or ANDROID_HOME) points at the SDK; default is the repo-local ../../android-sdk.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HERE/../../android-sdk}}"
SDK="$(cd "$SDK" && pwd)"
export ANDROID_HOME="$SDK"
[ -d "$SDK/jdk17" ] && export JAVA_HOME="$SDK/jdk17"
VARIANT="${VARIANT:-GithubDebug}"
ADB="$SDK/platform-tools/adb"

cd "$HERE/.."
if [ "${1:-}" = "--no-install" ]; then
    ./gradlew ":CRBYOB:assemble$VARIANT"
else
    [ -n "${ADB_SERIAL:-}" ] && export ANDROID_SERIAL="$ADB_SERIAL"
    ./gradlew ":CRBYOB:install$VARIANT"
    "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell am start -n com.crbyob.bridge/.MainActivity >/dev/null
fi
FLAVOR="$(echo "$VARIANT" | sed -E 's/^(Github|Play).*/\1/' | tr 'A-Z' 'a-z')"
TYPE="$(echo "$VARIANT" | sed -E 's/^(Github|Play)//' | tr 'A-Z' 'a-z')"
ls "$HERE/build/outputs/apk/$FLAVOR/$TYPE/"*.apk
