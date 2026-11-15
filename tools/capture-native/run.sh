#!/usr/bin/env bash
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_SERIAL=emulator-5554
export ANDROID_AVD_HOME="$RUNNER_TEMP/bold-film-avd"
mkdir -p "$ANDROID_AVD_HOME" capture-output
emulator_pid=''
cleanup() {
  result=$?
  trap - EXIT
  if [[ -n "$emulator_pid" ]]; then
    timeout 10 adb emu kill || true
    for n in {1..20}; do kill -0 "$emulator_pid" 2>/dev/null || break; sleep .5; done
    if kill -0 "$emulator_pid" 2>/dev/null; then kill -TERM "$emulator_pid"; wait "$emulator_pid" || true; fi
  fi
  python3 tools/capture-native/kvm.py cleanup || exit 1
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name bold-film --package 'system-images;android-35;google_apis;x86_64' --device pixel_2
cat >> "$ANDROID_AVD_HOME/bold-film.avd/config.ini" <<'CFG'
hw.lcd.width=720
hw.lcd.height=1600
hw.lcd.density=320
hw.keyboard=no
CFG
python3 tools/capture-native/kvm.py grant
"$ANDROID_HOME/emulator/emulator" -accel-check
"$ANDROID_HOME/emulator/emulator" -avd bold-film -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -accel on -gpu swiftshader -feature -Vulkan -camera-back none -camera-front none -memory 3072 -cores 2 > "$RUNNER_TEMP/emulator.log" 2>&1 &
emulator_pid=$!
echo "$emulator_pid" > "$RUNNER_TEMP/bold-film-emulator.pid"
deadline=$((SECONDS+600))
until [[ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  kill -0 "$emulator_pid"
  if ((SECONDS>=deadline)); then tail -100 "$RUNNER_TEMP/emulator.log"; exit 1; fi
  sleep 4
done
adb shell getprop ro.product.cpu.abilist | tee capture-output/abi.txt | grep arm64-v8a
adb shell wm size 720x1600
adb shell wm density 320
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb shell settings put system show_touches 1
adb install "$RUNNER_TEMP/bold-keys-0.7.1.apk"
adb shell ime enable ai.boldkeys/.BoldKeysService
adb shell ime set ai.boldkeys/.BoldKeysService
CAPTURE_OUTPUT="$PWD/capture-output" bash tools/capture-native/capture.sh
