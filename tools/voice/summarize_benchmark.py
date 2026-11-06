"""Summarize measured file-input results; never label these end-to-end latency."""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import statistics
from pathlib import Path


def tokens(text: str) -> list[str]:
    return re.findall(r"\w+", text.casefold(), flags=re.UNICODE)


def edits(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for index, word in enumerate(reference, 1):
        current = [index]
        for offset, candidate in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[offset] + 1,
                               previous[offset - 1] + (word != candidate)))
        previous = current
    return previous[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", type=Path)
    parser.add_argument("results", type=Path, nargs="+")
    args = parser.parse_args()
    fixtures = {row["id"]: row for row in json.loads(args.fixtures.read_text())}
    summaries = []
    for path in args.results:
        with path.open() as stream:
            rows = list(csv.DictReader(stream, delimiter="\t"))
        if not rows:
            raise ValueError(f"No measured results: {path}")
        if any(row["status"] != "0" for row in rows):
            raise ValueError(f"Inference failed: {path}")
        latency = sorted(float(row["total_ms"]) for row in rows)
        inference = sorted(float(row["infer_ms"]) for row in rows)
        english_latency = sorted(float(row["total_ms"]) for row in rows
                                 if fixtures[row["id"]]["language"] == "en")
        counts: dict[str, list[int]] = {"en": [0, 0], "multilingual": [0, 0]}
        hallucinations = 0
        for row in rows:
            fixture = fixtures[row["id"]]
            reference, hypothesis = tokens(fixture["reference"]), tokens(row["text"])
            if not reference:
                hallucinations += bool(hypothesis)
                continue
            group = "en" if fixture["language"] == "en" else "multilingual"
            counts[group][0] += edits(reference, hypothesis)
            counts[group][1] += len(reference)
        summaries.append({
            "file": path.name, "samples": len(rows),
            "median_native_total_ms": statistics.median(latency),
            "p95_native_total_ms_nearest_rank": latency[math.ceil(len(latency) * .95) - 1],
            "median_inference_ms": statistics.median(inference),
            "p95_inference_ms_nearest_rank": inference[math.ceil(len(inference) * .95) - 1],
            "english_timing_samples": len(english_latency),
            "english_median_native_total_ms": statistics.median(english_latency) if english_latency else None,
            "english_p95_native_total_ms_nearest_rank": english_latency[math.ceil(len(english_latency) * .95) - 1] if english_latency else None,
            "median_load_ms": statistics.median(float(row["load_ms"]) for row in rows),
            "peak_observed_pss_kb": max(int(row["pss_kb"]) for row in rows),
            "process_peak_rss_kb": max(int(row["maxrss_kb"]) for row in rows),
            "wer": {group: {"edits": value[0], "reference_words": value[1],
                             "rate": value[0] / value[1] if value[1] else None}
                    for group, value in counts.items()},
            "raw_silence_nonempty": hallucinations,
        })
    print(json.dumps({"latency_scope": "native load/inference/free; excludes app validation, capture, JNI, editor insertion",
                      "wer_normalization": "Unicode casefold and word tokens; numeric forms are not equated",
                      "corpus": "synthetic macOS voices; not user speech or accent evidence",
                      "results": summaries}, indent=2))


if __name__ == "__main__":
    main()
