#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
    echo "usage: $0 BENCHMARK_WORK_DIR true|false" >&2
    exit 2
fi
bench_source=$1
bench_qat=$2
case "$bench_qat" in true|false) ;; *) exit 2 ;; esac
bench_package=ai.boldkeys.voice.review
adb shell run-as "$bench_package" mkdir -p files/voice-benchmark
adb exec-in run-as "$bench_package" sh -c 'cat > files/voice-benchmark/tiny-q5_1.bin' < "$bench_source/models/tiny-q5_1.bin"
for bench_file in "$bench_source"/audio/*.pcm "$bench_source"/audio/fixtures.json; do
    bench_name=$(basename "$bench_file")
    case "$bench_name" in *[!a-zA-Z0-9._-]*) exit 2 ;; esac
    adb exec-in run-as "$bench_package" sh -c "cat > files/voice-benchmark/$bench_name" < "$bench_file"
done
if [ "$bench_qat" = true ]; then
    adb exec-in run-as "$bench_package" sh -c 'cat > files/voice-benchmark/qat.gguf' < "$bench_source/models/gemma-3-270m-it-qat-Q4_0.gguf"
fi
adb shell run-as "$bench_package" rm -f files/voice-benchmark/result.json
bench_log="$bench_source/app-run-$bench_qat.log"
adb shell am instrument -w -e qat "$bench_qat" -e repeats 2 "$bench_package/ai.boldkeys.VoiceFileBenchmark" > "$bench_log"
cat "$bench_log"
# Android can exit zero for a failed instrumentation result. Never reuse stale JSON.
if ! grep -q '^INSTRUMENTATION_RESULT: result=passed' "$bench_log"; then
    exit 1
fi
adb exec-out run-as "$bench_package" cat files/voice-benchmark/result.json > "$bench_source/app-qat-$bench_qat.json"
