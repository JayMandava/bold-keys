#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
OUT="${CAPTURE_OUTPUT:-$PWD/capture-output}"
mkdir -p "$OUT"
adb install -r build/editor.apk
# All following changes are restricted to this disposable emulator; no app content is prefilled.
adb shell settings put system screen_off_timeout 1800000
adb shell settings put system show_touches 1
adb shell wm dismiss-keyguard
adb shell input keyevent KEYCODE_WAKEUP
adb shell am force-stop demo.draft
adb shell rm -f /sdcard/native-story.mp4
adb shell screenrecord --bit-rate 6000000 --size 720x1600 --time-limit 120 /sdcard/native-story.mp4 >"$OUT/screenrecord.txt" 2>&1 &
record_client=$!
sleep 1
python3 -c 'import time;print(time.time())' > "$OUT/recording-start-host.txt"
set +e
timeout 110 adb shell am instrument -w demo.draft/.Capture > "$OUT/instrumentation.txt" 2>&1
status=$?
set -e
# Graceful SIGINT allows Android screenrecord to finalize its MP4, even on assertion failure.
adb shell 'pkill -INT screenrecord' || true
wait "$record_client" || true
sleep 2
adb pull /sdcard/native-story.mp4 "$OUT/story-raw.mp4" || true
adb pull /sdcard/Android/data/demo.draft/files/. "$OUT/" || true
adb shell dumpsys input_method > "$OUT/ime-state.txt"
adb shell dumpsys package ai.boldkeys | grep -E 'versionName|versionCode|primaryCpuAbi' > "$OUT/app-version.txt"
cat "$OUT/instrumentation.txt"
[[ "$status" == 0 ]] && grep -q $'\tPASS' "$OUT/phases.tsv"
