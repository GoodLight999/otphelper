#!/usr/bin/env bash
set -euo pipefail

build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
apk_analyzer="$(command -v apkanalyzer || true)"
if [ -z "$apk_analyzer" ]; then
  apk_analyzer="$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer -perm -u+x | head -n 1)"
fi
if [ -z "$apk_analyzer" ] || [ ! -x "$apk_analyzer" ]; then
  echo "Unable to locate apkanalyzer in Android SDK" >&2
  exit 1
fi

mkdir -p app/build/reports/apk-inspection
found=0
for apk in app/build/outputs/apk/*/debug/*.apk; do
  [ -f "$apk" ] || continue
  found=1
  name="$(basename "$apk")"
  unzip -tq "$apk"
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" \
    > "app/build/reports/apk-inspection/${name}.signature.txt"
  "$apk_analyzer" manifest print "$apk" \
    > "app/build/reports/apk-inspection/${name}.manifest.xml"
  manifest="app/build/reports/apk-inspection/${name}.manifest.xml"
  grep -q 'io.github.jd1378.otphelper.PersistenceService' "$manifest"
  grep -q 'io.github.jd1378.otphelper.NotificationListener' "$manifest"
  grep -q 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' "$manifest"

  if grep -Eqi 'leakcanary|LeakLauncherActivity|LeakActivity' "$manifest"; then
    echo "LeakCanary components must not be shipped in $apk" >&2
    exit 1
  fi
  if grep -Eqi 'shizuku|moe\.shizuku|rikka\.shizuku' "$manifest"; then
    echo "Shizuku components or permissions must not be shipped in $apk" >&2
    exit 1
  fi
  if grep -q 'io.github.jd1378.otphelper.AccessibilityNotificationService' "$manifest"; then
    echo "Obsolete Accessibility notification reader is still packaged in $apk" >&2
    exit 1
  fi
  if grep -q 'io.github.jd1378.otphelper.fixture' "$manifest"; then
    echo "CI notification fixture leaked into distributed APK $apk" >&2
    exit 1
  fi

  python3 - "$manifest" <<'PY'
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
application = root.find("application")
if application is None:
    raise SystemExit("Merged APK Manifest has no application element")
main = next(
    (
        activity
        for activity in application.findall("activity")
        if activity.get(android + "name") == "io.github.jd1378.otphelper.MainActivity"
    ),
    None,
)
if main is None:
    raise SystemExit("MainActivity is missing from merged APK Manifest")
if main.get(android + "excludeFromRecents") == "true":
    raise SystemExit("MainActivity is excluded from Recents")
PY
done

test "$found" -eq 1
