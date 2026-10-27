#!/usr/bin/env bash
# Approved job runtime: normal runner UID; permission lifecycle belongs to the outer ACL guard.
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME required}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/bold-approved-colours-avd"
mkdir -p "$ANDROID_AVD_HOME" qa-artifacts/device
"$ANDROID_HOME/emulator/emulator" -accel-check | tee qa-artifacts/acceleration.txt
emulator_pid=''
cleanup() {
  result=$?
  trap - EXIT
  set +e
  if [[ -n "$emulator_pid" ]]; then
    timeout 25 adb logcat -d -v threadtime > qa-artifacts/logcat.txt 2>&1
    timeout 25 adb shell dumpsys input_method > qa-artifacts/input-method.txt 2>&1
    timeout 25 adb shell dumpsys window > qa-artifacts/windows.txt 2>&1
    timeout 25 adb exec-out screencap -p > qa-artifacts/last-screen.png 2>/dev/null
    timeout 30 adb pull /sdcard/Android/data/ai.boldkeys/files/qa qa-artifacts/device/ > qa-artifacts/pull-captures.txt 2>&1
    timeout 45 adb pull /sdcard/Download/bold-keys-qa qa-artifacts/device/ >> qa-artifacts/pull-captures.txt 2>&1
    timeout 15 adb emu kill >/dev/null 2>&1
  fi
  # The validated guard bounds termination and restores access even on test failure.
  python3 tools/qa/approved-kvm-access.py cleanup > qa-artifacts/runtime-access-cleanup.txt 2>&1
  cleanup_result=$?
  if (( cleanup_result != 0 )); then result=1; fi
  exit "$result"
}
trap cleanup EXIT

# No new licence acceptance: 'no' answers only avdmanager's custom-hardware question.
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --force --name bold-approved-colours-35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
python3 - <<'PYCONFIG'
import os
from pathlib import Path
p = Path(os.environ['ANDROID_AVD_HOME']) / 'bold-approved-colours-35.avd/config.ini'
settings = {'hw.lcd.width': '720', 'hw.lcd.height': '1280', 'hw.lcd.density': '320',
            'hw.keyboard': 'no', 'hw.ramSize': '3072', 'vm.heapSize': '256'}
lines = [line for line in p.read_text().splitlines() if line.partition('=')[0].strip() not in settings]
p.write_text('\n'.join(lines + [f'{key}={value}' for key, value in settings.items()]) + '\n')
PYCONFIG
"$ANDROID_HOME/emulator/emulator" -avd bold-approved-colours-35 -port 5554 -accel on \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan \
  -camera-back none -camera-front none -memory 3072 -cores 2 > qa-artifacts/emulator.txt 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > "$RUNNER_TEMP/bold-approved-kvm-access/emulator.pid"
boot_deadline=$((SECONDS + 600))
until [[ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then echo 'Emulator exited before Android boot'; exit 1; fi
  if (( SECONDS >= boot_deadline )); then echo 'Android did not complete boot within the ten-minute KVM startup gate'; exit 1; fi
  sleep 5
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
{
  echo "Source $(git rev-parse HEAD)"
  adb shell getprop ro.build.fingerprint
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.product.cpu.abilist
  adb shell getprop ro.dalvik.vm.native.bridge
  adb shell wm size
  adb shell wm density
} > qa-artifacts/device-properties.txt
abis="$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
[[ ",$abis," == *',arm64-v8a,'* ]] || { echo 'The image lacks ARM64 translation required for the real APK'; exit 1; }
[[ "$(adb shell id -u | tr -d '\r')" == 2000 ]] || { echo 'Guest adb must remain the normal shell'; exit 1; }
readiness_budget=$((boot_deadline - SECONDS))
if (( readiness_budget > 180 )); then readiness_budget=180; fi
(( readiness_budget > 0 )) || { echo 'No startup budget remains for healthy UI'; exit 1; }
python3 tools/qa/wait-software-ui-ready.py --timeout "$readiness_budget"
# Build tasks were completed before the guest started; this executes the actual test APK.
set -o pipefail
timeout 20m ./gradlew --no-daemon --max-workers=2 -PunsignedRelease=true \
  connectedDebugAndroidTest 2>&1 | tee qa-artifacts/instrumentation.txt
