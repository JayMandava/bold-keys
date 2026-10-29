#!/usr/bin/env bash
# Normal runner/controller only. Device ACL lifecycle belongs to the outer guard.
set -euo pipefail
: "${ANDROID_HOME:?ANDROID_HOME required}"
: "${RUNNER_TEMP:?RUNNER_TEMP required}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="$RUNNER_TEMP/bold-teaser-avd"
mkdir -p "$ANDROID_AVD_HOME" qa-artifacts capture-output
emulator_pid=''
cleanup() {
  result=$?
  trap - EXIT
  set +e
  # Never let an artifact pull delay cancellation-time device cleanup.
  if [[ -n "$emulator_pid" ]]; then
    printf '%s\n' "$emulator_pid" >"$RUNNER_TEMP/bold-teaser-kvm-access/emulator.pid"
  fi
  python3 tools/capture/kvm_access.py cleanup >qa-artifacts/capture-cleanup.log 2>&1
  if (( $? != 0 )); then result=1; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$ANDROID_HOME/emulator/emulator" -accel-check >qa-artifacts/acceleration.txt
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --force --name bold-teaser-capture-35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
python3 - <<'PY'
import os
from pathlib import Path
p = Path(os.environ['ANDROID_AVD_HOME']) / 'bold-teaser-capture-35.avd/config.ini'
settings = {'hw.lcd.width': '1080', 'hw.lcd.height': '1920', 'hw.lcd.density': '420',
            'hw.keyboard': 'no', 'hw.ramSize': '3072', 'vm.heapSize': '256'}
lines = [line for line in p.read_text().splitlines() if line.partition('=')[0].strip() not in settings]
p.write_text('\n'.join(lines + [f'{k}={v}' for k,v in settings.items()]) + '\n')
PY
"$ANDROID_HOME/emulator/emulator" -avd bold-teaser-capture-35 -port 5554 -accel on \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan \
  -camera-back none -camera-front none -memory 3072 -cores 2 >qa-artifacts/capture-emulator.log 2>&1 &
emulator_pid=$!
printf '%s\n' "$emulator_pid" >"$RUNNER_TEMP/bold-teaser-kvm-access/emulator.pid"
boot_deadline=$((SECONDS + 600))
until [[ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then echo 'Emulator exited before boot'; exit 1; fi
  if (( SECONDS >= boot_deadline )); then echo 'Ten-minute accelerated boot deadline exceeded'; exit 1; fi
  sleep 5
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
[[ "$(adb shell id -u | tr -d '\r')" == 2000 ]] || { echo 'Guest shell must remain UID 2000'; exit 1; }
abis="$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
[[ ",$abis," == *',arm64-v8a,'* ]] || { echo 'ARM64 translation unavailable'; exit 1; }
readiness_budget=$((boot_deadline - SECONDS))
if (( readiness_budget > 180 )); then readiness_budget=180; fi
(( readiness_budget > 0 )) || exit 1
# Unchanged successful fifth-job gate. At 1080p its bounded 720p-only ANR recovery
# fails closed rather than guessing coordinates or weakening the health assertions.
python3 tools/qa/wait-software-ui-ready.py --timeout "$readiness_budget"
python3 tools/capture/preflight.py --phase guest
python3 - <<'PY'
import json,subprocess
from pathlib import Path
def adb(*a): return subprocess.check_output(['adb',*a],text=True).strip()
report = {'sdk':adb('shell','getprop','ro.build.version.sdk'),
          'fingerprint':adb('shell','getprop','ro.build.fingerprint'),
          'abis':adb('shell','getprop','ro.product.cpu.abilist'),
          'native_bridge':adb('shell','getprop','ro.dalvik.vm.native.bridge'),
          'display':adb('shell','wm','size'),'density':adb('shell','wm','density'),
          'guest_uid':adb('shell','id','-u'),'recording':'real screenrecord, no audio',
          'editor':'native debug EditText; neutral Notes title; dark appearance',
          'physical_haptics_audio_inference_verified':False}
Path('qa-artifacts/capture-device.json').write_text(json.dumps(report,indent=2)+'\n')
PY
set -o pipefail
capture_result=0
timeout 20m ./gradlew --no-daemon --no-configuration-cache --max-workers=2 --console=plain \
  -PunsignedRelease=true -Pkotlin.compiler.execution.strategy=in-process \
  --init-script tools/capture/capture.gradle \
  '-Pandroid.testInstrumentationRunnerArguments.class=ai.boldkeys.TeaserSupplementCaptureTest#i_configureAndRunLinkShortcut' \
  connectedDebugAndroidTest 2>&1 | tee qa-artifacts/capture-instrumentation.log || capture_result=$?
pull_result=0
timeout 90 adb pull /sdcard/Download/bold-keys-qa capture-output/ >qa-artifacts/capture-pull.log 2>&1 || pull_result=$?
python3 - "$capture_result" "$pull_result" <<'PY'
import json,sys
from pathlib import Path
report = {'instrumentation_exit_code':int(sys.argv[1]), 'pull_exit_code':int(sys.argv[2]),
          'complete_collection':sys.argv[2]=='0',
          'collected_regular_files':sum(p.is_file() and not p.is_symlink() for p in Path('capture-output').rglob('*'))}
Path('qa-artifacts/capture-collection.json').write_text(json.dumps(report,indent=2)+'\n')
PY
if (( pull_result != 0 )); then
  echo 'Capture collection failed; do not claim complete exported media.'
  exit 1
fi
exit "$capture_result"
