#!/usr/bin/env bash
set -euo pipefail
# Run only inside the separately authorized disposable emulator.
mkdir -p capture-output
adb shell dumpsys package ai.boldkeys > capture-output/installed-package.txt
adb shell am start -W -n ai.boldkeys/ai.boldkeys.SetupActivity | tee capture-output/setup-launch.txt
chosen=''
for n in $(seq 1 30); do
  adb shell ime list -a -s | tr -d '\r' > capture-output/ime-registry.txt
  chosen=$(grep -E '^ai\.boldkeys/(\.BoldKeysService|ai\.boldkeys\.BoldKeysService)$' capture-output/ime-registry.txt || true)
  if [[ -n "$chosen" ]]; then break; fi
  sleep 2
done
if [[ -z "$chosen" || "$chosen" == *$'\n'* ]]; then
  adb shell dumpsys input_method > capture-output/ime-failure.txt
  adb logcat -d -t 1000 > capture-output/registration-logcat.txt
  echo 'Expected installed service not uniquely registered; capture not started.' >&2
  exit 1
fi
printf '%s\n' "$chosen" > capture-output/resolved-ime.txt
adb shell ime enable "$chosen"
adb shell ime set "$chosen"
selected=$(adb shell settings get secure default_input_method | tr -d '\r')
printf '%s\n' "$selected" > capture-output/selected-ime.txt
[[ "$selected" == "$chosen" ]]
adb shell dumpsys input_method > capture-output/ime-before-capture.txt
