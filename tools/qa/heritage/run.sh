#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa-artifacts/device
cleanup() {
 result=$?; trap - EXIT
 adb logcat -d -v threadtime > qa-artifacts/logcat.txt 2>&1 || true
 adb pull /sdcard/Download/bold-keys-qa qa-artifacts/device/ || true
 adb exec-out screencap -p > qa-artifacts/last-screen.png || true
 exit "$result"
}
trap cleanup EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 720x1280
adb shell wm density 320
adb shell am instrument -w -r -e class ai.boldkeys.ColourUiTest ai.boldkeys.test/androidx.test.runner.AndroidJUnitRunner | tee qa-artifacts/instrumentation.txt
grep -q 'OK (.* tests)' qa-artifacts/instrumentation.txt
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=' qa-artifacts/instrumentation.txt
