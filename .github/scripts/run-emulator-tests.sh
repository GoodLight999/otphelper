#!/usr/bin/env bash
set -euo pipefail

PACKAGE="io.github.jd1378.otphelper"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
REPORT_DIR="app/build/reports/manual-instrumentation/api-${SDK}"
LOG_FILE="$REPORT_DIR/resilience.log"
mkdir -p "$REPORT_DIR"

./gradlew --no-daemon \
  :app:assembleNormalDebug \
  :app:assembleNormalDebugAndroidTest

APP_APK="$(find app/build/outputs/apk/normal/debug -type f -name '*.apk' | head -n 1)"
TEST_APK="$(find app/build/outputs/apk/androidTest/normal/debug -type f -name '*.apk' | head -n 1)"

test -f "$APP_APK"
test -f "$TEST_APK"

adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS

cleanup() {
  adb shell cmd notification disallow_listener \
    "$PACKAGE/$PACKAGE.NotificationListener" || true
  adb shell cmd appops set --user current "$PACKAGE" ACCESS_RESTRICTED_SETTINGS default || true
}
trap cleanup EXIT

# Some emulator/API combinations return a non-zero shell status even when AndroidJUnitRunner
# reports OK. Capture all output and use the runner result as the source of truth.
set +e
adb shell am instrument -w -r \
  -e class io.github.jd1378.otphelper.ResilienceManifestTest \
  "$RUNNER" | tee "$LOG_FILE"
set -e

if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$LOG_FILE"; then
  exit 1
fi
grep -Eq 'OK \([0-9]+ tests?\)' "$LOG_FILE"
