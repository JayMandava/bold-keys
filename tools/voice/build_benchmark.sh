#!/bin/sh
set -eu
if [ "$#" -ne 3 ]; then
    echo "usage: $0 ANDROID_NDK WHISPER_BUILD_DIR OUTPUT" >&2
    exit 2
fi
bench_ndk=$1
bench_libs=$2
bench_output=$3
bench_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
case "$(uname -s)" in
    Darwin) bench_host=darwin-x86_64 ;;
    Linux) bench_host=linux-x86_64 ;;
    *) echo "Unsupported build host" >&2; exit 2 ;;
esac
"$bench_ndk/toolchains/llvm/prebuilt/$bench_host/bin/aarch64-linux-android33-clang++" \
    -O3 -std=c++17 -static-libstdc++ \
    -I"$bench_root/third_party/whisper.cpp/include" \
    -I"$bench_root/third_party/whisper.cpp/ggml/include" \
    "$bench_root/tools/voice/benchmark.cpp" -Wl,--start-group \
    "$bench_libs/src/libwhisper.a" "$bench_libs/ggml/src/libggml.a" \
    "$bench_libs/ggml/src/libggml-base.a" "$bench_libs/ggml/src/libggml-cpu.a" \
    -Wl,--end-group -llog -landroid -latomic -o "$bench_output"
