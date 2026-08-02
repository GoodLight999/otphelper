#!/usr/bin/env bash
set -euo pipefail

PACKAGE="io.github.jd1378.otphelper"
FIXTURE_PACKAGE="io.github.jd1378.otphelper.fixture"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
REPORT_DIR="app/build/reports/manual-instrumentation/api-${SDK}"
mkdir -p "$REPORT_DIR"

./gradlew --no-daemon \
  :notification-fixture:assembleDebug \
  :app:assembleNormalDebug \
  :app:assembleNormalDebugAndroidTest

APP_APK="$(find app/build/outputs/apk/normal/debug -type f -name '*.apk' | head -n 1)"
TEST_APK="$(find app/build/outputs/apk/androidTest/normal/debug -type f -name '*.apk' | head -n 1)"
FIXTURE_APK="notification-fixture/build/outputs/apk/debug/notification-fixture-debug.apk"

test -f "$APP_APK"
test -f "$TEST_APK"
test -f "$FIXTURE_APK"

adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb install -r "$FIXTURE_APK"
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
adb shell pm grant "$FIXTURE_PACKAGE" android.permission.POST_NOTIFICATIONS

cleanup() {
  adb shell cmd appops set --user current "$PACKAGE" RECEIVE_SENSITIVE_NOTIFICATIONS default || true
  adb shell cmd notification disallow_listener \
    "$PACKAGE/$PACKAGE.NotificationListener" || true
  adb uninstall "$FIXTURE_PACKAGE" || true
}
trap cleanup EXIT

run_test() {
  local class_name="$1"
  local log_name="$2"
  local log_file="$REPORT_DIR/$log_name.log"

  # Some emulator/API combinations return a non-zero shell status even when AndroidJUnitRunner
  # reports OK. Capture the complete output and use the runner's result as the source of truth.
  set +e
  adb shell am instrument -w -r -e class "$class_name" "$RUNNER" | tee "$log_file"
  set -e

  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$log_file"; then
    return 1
  fi
  grep -Eq 'OK \([0-9]+ tests?\)' "$log_file"
}

run_test \
  "io.github.jd1378.otphelper.ResilienceManifestTest" \
  "resilience"

# Negative control: default AppOp and a fresh OTP Helper process must not expose the fixture OTP.
adb shell cmd appops set --user current "$PACKAGE" RECEIVE_SENSITIVE_NOTIFICATIONS default
adb shell am force-stop "$PACKAGE"
run_test \
  "io.github.jd1378.otphelper.NotificationBodyAccessTest#thirdPartyOtpIsNotReadableWithDefaultAppOp" \
  "notification-body-default"

# Positive control: match the documented ADB procedure—set AppOp, stop the process, then relaunch.
adb shell cmd appops set --user current "$PACKAGE" RECEIVE_SENSITIVE_NOTIFICATIONS allow
adb shell am force-stop "$PACKAGE"
run_test \
  "io.github.jd1378.otphelper.NotificationBodyAccessTest#thirdPartyOtpIsReadableAfterAllowedAppOpAndProcessRestart" \
  "notification-body-allowed"
