#!/usr/bin/env bash
set -euo pipefail

./gradlew --no-daemon :notification-fixture:assembleDebug
adb install -r notification-fixture/build/outputs/apk/debug/notification-fixture-debug.apk
./gradlew --no-daemon :app:connectedNormalDebugAndroidTest --stacktrace
