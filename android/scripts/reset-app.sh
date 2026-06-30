#!/bin/sh
# Dev iteration loop: wipe app state, reinstall the debug APK, launch.
# Onboarding runs every time — that's intentional; the AVD is our source of
# truth for contacts and call history (see scripts/seed-avd.py).

set -eu

APP_ID=io.github.frosty110.orbit.debug
APK_PATH=app/build/outputs/apk/debug/app-debug.apk

cd "$(dirname "$0")/.."

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found at $APK_PATH — run './gradlew :app:assembleDebug' first." >&2
  exit 1
fi

started=$(date +%s)

echo "→ clearing $APP_ID app data"
adb shell pm clear "$APP_ID" >/dev/null

echo "→ installing debug APK"
adb install -r "$APK_PATH" >/dev/null

echo "→ launching"
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

elapsed=$(( $(date +%s) - started ))
echo "ready in ${elapsed}s · onboarding should be showing"
