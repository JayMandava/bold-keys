#!/usr/bin/env bash
# Run only on the already-authorized disposable Android emulator, never a personal device.
set -euo pipefail
cd "$(dirname "$0")/../../.."
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required' >&2; exit 1; }
OUT=${QA_OUTPUT:-qa-artifacts/standardization}
RUN_ID=$(date -u +%Y%m%dT%H%M%S)-$$
OUT="$OUT/$RUN_ID"
mkdir -p "$OUT"
# Build separately when desired: SKIP_BUILD=1 still installs the existing APKs.
if [[ ${SKIP_BUILD:-0} != 1 ]]; then
  ./gradlew --no-daemon -I tools/qa/standardization/init.gradle \
    -PunsignedRelease=true -PtestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
original_size=$(adb shell wm size | tr -d '\r' | sed -n 's/^Override size: //p')
original_density=$(adb shell wm density | tr -d '\r' | sed -n 's/^Override density: //p')
original_font=$(adb shell settings get system font_scale | tr -d '\r')
cleanup() {
  adb shell wm size "${original_size:-reset}" >/dev/null || true
  adb shell wm density "${original_density:-reset}" >/dev/null || true
  if [[ $original_font == null ]]; then
    adb shell settings delete system font_scale >/dev/null || true
  else
    adb shell settings put system font_scale "$original_font" >/dev/null || true
  fi
}
trap cleanup EXIT
failed=0
for spec in normal:720:1.0 narrow-130:640:1.3 large-200:720:2.0; do
  IFS=: read -r profile width scale <<< "$spec"
  adb shell wm size "${width}x1280"
  adb shell wm density 320
  adb shell settings put system font_scale "$scale"
  adb shell am force-stop ai.boldkeys
  # No clear-data, permission grants, model downloads, or external links.
  adb shell am instrument -w -r \
    -e class ai.boldkeys.SettingsStandardizationCapture -e profile "$profile" -e runId "$RUN_ID" \
    ai.boldkeys.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/$profile-instrumentation.log"
  adb pull "/sdcard/Android/data/ai.boldkeys/files/settings-standardization/$RUN_ID/$profile" "$OUT/" || failed=1
  grep -q 'OK (1 test)' "$OUT/$profile-instrumentation.log" || failed=1
  [[ -f "$OUT/$profile/coverage-complete.txt" ]] || failed=1
  [[ ! -s "$OUT/$profile/layout-issues.txt" ]] || failed=1
done
exit "$failed"
