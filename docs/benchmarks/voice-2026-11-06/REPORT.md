# Offline voice comparison — 6 November 2026

Selected experimental change: retain the existing multilingual Whisper Tiny Q5_1 model, but reduce padded encoder work for English. The final native English median fell from **4421 ms to 1209 ms (72.7%)**. This is a small synthetic-corpus result, not a general accuracy claim or measured release-to-insert improvement. The production installation remains unchanged; only the separate review package was installed.

The standard `testDebugUnitTest assembleDebug lintDebug` check completed successfully on the final app/harness source. All **124 unit tests** passed, and **7 benchmark-metric tests** passed. Independent review found no production-code blocker and requested final evidence and precise timing labels; this report and accompanying raw artifacts address those requests.

Device: OnePlus Nord CE3 Lite 5G, CPH2467, 8 GB. Production `ai.boldkeys` v0.6.4/code12 and active keyboard remain untouched. Native executable/file inputs run under `/data/local/tmp/bold-voice-bench`; no microphone was opened. No audio or editor data was uploaded.

## Comparable English results

Each English result uses the identical seven fixtures, repeated twice: six speech/noise fixtures plus digital silence (14 timings, 12 speech transcripts). Values are native model load/inference/free, excluding app SHA validation, capture, JNI and editor insertion. They are **not release-to-insert measurements**. Full-context multilingual runs additionally include French/German sanity fixtures. Raw rows are retained alongside this report.

| Candidate | English median native total | English WER | Observed peak PSS |
|---|---:|---:|---:|
| Multilingual Tiny Q5_1, cold, 2 threads | 4421 ms | 25.5% | 143 MiB |
| **Same model, final English adaptive context** | **1209 ms** | **21.3%** | **122 MiB** |
| Same model, warm, 2 threads | 4236 ms | 25.5% | 142 MiB |
| Same model, cold, 4 threads | 5072 ms | 25.5% | 142 MiB |
| Tiny English Q5_1, cold, 2 threads | 4337 ms | 19.1% | 123 MiB |
| Base English Q5_1, cold, 2 threads | 9183 ms | 19.1% | 171 MiB |
| Moonshine Tiny Streaming, cold batch/file API | 547 ms | 31.9% | 146 MiB |

Native total means model load + inference + model free, excluding PCM file loading/conversion. Cold means a freshly loaded model context per utterance; OS page caches were not flushed. Warm initial load is outside per-utterance timings. PSS in this table is sampled after native inference before freeing the model, and is not a continuous peak trace. The selected row uses the English subset (native full-run PSS including French/German peaked at 123 MiB). Descriptive English nearest-rank p95 fell from **4554 ms to 1280 ms**, with 14 timings per case. Process peak RSS and separately named total/inference statistics are in `summary.json`. Small repeated synthetic sample counts do not establish general performance or confidence intervals.

Corpus: **9 unique short fixtures**, locally synthesized macOS `say`, English voice Aman (en_IN), French Thomas, German Anna; seeded uniform noise; digital silence. Each full multilingual run has 18 timings (two repeats); English-only runs have 14 (six speech/noise fixtures plus one silence, twice). English WER covers **12 speech/noise transcripts and 94 reference words**: baseline 24 edits, selected change 20 edits. French/German cover four transcripts/28 reference words and retain 10 edits in both cases. This is **not real user speech or proof of accuracy on the user's accent**. WER uses Unicode casefold/word tokens and counts numeric rendering differences (e.g. spoken words versus digits) as errors; inspect raw transcripts before interpreting it as semantic correctness. Fixture metadata includes PCM SHA256, sample counts, language, source and dataset version. PCM stays local; regeneration can differ with OS/voice versions, so compare its hashes before claiming the same corpus.

## Chosen change and regression checks

`voice.cpp` changes only `params.audio_ctx` when the existing language is exactly `en`:

```cpp
std::min(1500, std::max(512, (n + 319) / 320 + 150))
```

There are 50 encoder-context frames per second; the formula uses the entire recording plus three seconds of headroom, with a 512-frame minimum and the original 1500-frame maximum. It does not trim audio, force English, change model/download/permission/UI behavior, cache handles, or alter cancellation/audio wiping. French/German and other language values keep Whisper's original default. The final `adaptive-all.tsv` French/German transcripts match baseline exactly in all four comparisons. Both noisy-English outputs match baseline. Raw silence is nonempty in both baseline and optimized multilingual Tiny; the unchanged application's existing duration/peak gate is still required before inference.

Two additional composed English fixtures are **12.975 s and 28.376 s**, each tested twice under baseline and adaptive settings. All four transcript pairs match exactly; WER is 58 edits/258 reference words (22.5%) in both. The shorter long clip benefits from a reduced context; the near-limit clip uses the original maximum, without truncation. Raw rows and separate stress metadata/summary are retained.

## App and QAT integration

The debug-only instrumentation refuses any package except `ai.boldkeys.voice.review`. It reads only staged synthetic files, validates model SHA, calls the real JNI wrapper, closes and wipes buffers, checks that cancellation produces no text, then reopens a model. It neither selects the IME nor opens the microphone. QAT mode copies the pinned public model into the isolated app, selects it through `ContextModels`, loads it and scores candidates before voice inference.

| Final app path | English timings | Median | p95 |
|---|---:|---:|---:|
| Without QAT | 14 | 1230 ms | 1284 ms |
| QAT selected, loaded and scored | 14 | 1246 ms | 1324 ms |

Each app run also includes four French/German timings (18 total). These app times include validation/load/JNI/inference/free, stored under the legacy raw JSON key `compute_ms`; **capture and editor insertion are excluded**. Both instrumentation runs report `passed`, including cancellation/reopening. QAT-mode sampled PSS was **560,981 KiB** after QAT load and **643,080 KiB (628 MiB)** in an external snapshot during coexistence; this is sampled process memory, not a continuous peak trace. Thermal status at that snapshot was 0. The QAT artifact reports exact pre/post-load PSS. No sustained heat, battery-drain or concurrency-with-active-QAT-scoring claim is made (QAT was resident during voice; scoring was verified before it).

Production v0.6.4/code12, its update timestamp and active `ai.boldkeys/.BoldKeysService` were verified unchanged after testing. The isolated app's microphone permission was never requested or granted.

## Findings and rejected options

- More threads made this phone slower; retain two unless further evidence changes that conclusion.
- Warm reuse saved about 185 ms on the English subset, while whole-corpus p95 did not improve (4768 ms cold vs 4988 ms warm). This does not justify a complex cache yet. Native cancel is sticky and requires discarding canceled handles.
- Base English was over twice as slow as Tiny English with identical measured WER here. It has no demonstrated advantage on these fixtures.
- Moonshine's official Android SDK 0.1.6 ran successfully with 45,398,523 bytes of public model files. Each file was verified against SDK manifest size/CRC32C. It was much faster but worse on technical terms and the dictated time in this corpus. It is an English comparator, not a replacement for multilingual support. This run used the batch API with a streaming architecture, not speech-time incremental processing.
- A 256-frame Whisper audio context reduced English median to 712 ms and WER to 21.3%, but caused severe repeated German output (multilingual WER 435.7%, versus 35.7% baseline). **Do not ship that global setting.** Raw result `tiny-ctx256.tsv` is retained as a rejected experiment.
- Raw multilingual Whisper emitted text for digital silence. The application's existing peak/duration silence check must remain; this harness intentionally bypasses capture preprocessing to expose raw-model behavior. English Whisper/Moonshine returned empty silence transcripts.
- Baseline battery observation was 28.4°C; sampled during later runs at 30.0°C and 32.0°C. One sampled system thermal status was 0 (no throttling); CPU readings were around 43–45°C at that point. These sparse samples are not a sustained heat/battery study.

## Inputs and reproduction

Whisper uses the repository-pinned `4979e04f5dcaccb36057e059bbaed8a2f5288315` engine, matching the existing native build. Models come from Hugging Face `ggerganov/whisper.cpp`, commit `f281eb45af861ab5e5297d23694b7d46e090c02c`:

- Tiny multilingual SHA256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`
- Tiny English SHA256 `c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b`
- Base English SHA256 `4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`

Generate fixtures with `python3 tools/voice/make_benchmark_fixtures.py OUTPUT` on a Mac with these voices and ffmpeg. The script rejects empty synthesized files; sandboxed macOS speech generation returned empty files and was retried with normal system access before any measured run. Build the native harness with `sh tools/voice/build_benchmark.sh ANDROID_NDK WHISPER_BUILD_DIR OUTPUT`. Build inputs are the pinned whisper static libraries built with the app's release native settings.

Stage PCM files/manifests and models under the harness paths, then run:

```sh
adb shell /data/local/tmp/bold-voice-bench/benchmark MODEL MANIFEST cold 2 2 LABEL
adb shell /data/local/tmp/bold-voice-bench/benchmark MODEL MANIFEST warm 2 2 LABEL
```

`benchmark.cpp` accepts optional final audio-context frames: omit for baseline defaults, use `-1` for the final English-only adaptive rule. Exact final invocations (all files staged under the documented device directory):

```sh
adb shell /data/local/tmp/bold-voice-bench/benchmark-adaptive MODEL audio/all.txt cold 2 2 adaptive-multilingual -1
adb shell /data/local/tmp/bold-voice-bench/benchmark-adaptive MODEL stress/all.txt cold 2 2 baseline-stress
adb shell /data/local/tmp/bold-voice-bench/benchmark-adaptive MODEL stress/all.txt cold 2 2 adaptive-stress -1
```

Replace `MODEL`, `audio/all.txt` and `stress/all.txt` with their absolute staged paths; manifest entries already contain absolute PCM paths. Create long fixtures with `python3 tools/voice/make_stress_fixtures.py ORIGINAL_AUDIO STRESS_OUTPUT`. The debug app check is `ANDROID_SERIAL=DEVICE sh tools/voice/run_app_benchmark.sh BENCHMARK_WORK_DIR false` and then `true` for QAT; install the isolated APK first. Build it with `./gradlew -PvoiceReviewBuild=true -PunsignedRelease=true testDebugUnitTest assembleDebug lintDebug`. Metric checks: `python3 -m unittest discover -s tools/voice -p test_benchmark_metrics.py`.

For the Moonshine comparator, download the official Maven AAR `ai.moonshine:moonshine-voice:0.1.6`, use its ARM64 `libmoonshine.so`/`libonnxruntime.so`, and the matching public `moonshine-c-api.h`; compile `benchmark_moonshine.cpp` with the Android NDK and link `-lmoonshine`. The SDK's `catalog` argument emits the checked-in `moonshine-manifest.json`; `download_moonshine_benchmark.py` downloads and verifies that public manifest. Run with `LD_LIBRARY_PATH` set to the staged SDK library directory and arguments `MODEL_DIR MANIFEST cold 2 LABEL`.

Summarize with `python3 tools/voice/summarize_benchmark.py fixtures.json RESULTS.tsv ...`. `summary.json` covers the baseline, candidates and final short corpus; `stress-summary.json` covers long inputs. All raw TSVs, both app JSON results, fixture metadata, Moonshine manifest and verification log summaries accompany this report. The 256-frame experiment remains explicitly rejected.

## Limits and choice

The chosen change improves the existing model without adding an English-only download or affecting multilingual recognition. Tiny English is a promising accuracy option for a later explicit model choice, but is not silently substituted. Base English adds latency without improving this corpus's WER; Moonshine Tiny trades substantial speed for worse errors here. Moonshine Small was not measured. No overall-model-quality, actual-user-accent, sustained battery/thermal or real release-to-insert claim is justified. The QAT file was SHA-verified against the repository pin: `5f4b2e17722e510122c464573b880587f4983347a40e5472b858d5a3c1ab8095`.

Official sources: [Whisper models/MIT](https://huggingface.co/ggerganov/whisper.cpp), [Moonshine Android setup](https://github.com/moonshine-ai/moonshine/blob/main/docs/using/adding-the-library.md), [Moonshine licensing](https://github.com/moonshine-ai/moonshine/blob/main/docs/license.md).
