#!/usr/bin/env bash
set -euo pipefail

PACKAGE="io.github.jd1378.otphelper"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
REPORT_DIR="app/build/reports/manual-instrumentation/api-${SDK}"
LOG_FILE="$REPORT_DIR/instrumentation.log"
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

# Run the complete instrumentation APK. Restricting this command to ResilienceManifestTest made the
# other security/intent/notification extraction tests compile without ever executing, which is not
# a meaningful API 35/36 gate. Individual tests remain responsible for restoring any state they
# change; the outer cleanup is a final safety net for listener/AppOp state.
#
# Some emulator/API combinations return a non-zero shell status even when AndroidJUnitRunner reports
# OK. Capture all output and use the runner result as the source of truth.
set +e
adb shell am instrument -w -r "$RUNNER" | tee "$LOG_FILE"
set -e

if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$LOG_FILE"; then
  exit 1
fi
grep -Eq 'OK \([0-9]+ tests?\)' "$LOG_FILE"
