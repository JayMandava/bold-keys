"""Compose long synthetic utterances from the original fixed speech fixtures."""
import argparse
import hashlib
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    metadata = {row["id"]: row for row in json.loads((args.source / "fixtures.json").read_text())}
    order = ["short", "names", "numbers", "technical", "dictation"]
    fixtures = []
    for ident, sequence in [("long", order), ("near-limit", order * 2 + ["short"])]:
        audio = bytes(8000).join((args.source / metadata[name]["file"]).read_bytes() for name in sequence)
        if not 32000 * 10 < len(audio) <= 32000 * 30:
            raise ValueError("Stress fixture outside capture duration limits")
        (args.output / f"{ident}.pcm").write_bytes(audio)
        fixtures.append({"id": ident, "file": f"{ident}.pcm", "language": "en",
                         "reference": " ".join(metadata[name]["reference"] for name in sequence),
                         "kind": "concatenated synthetic speech with 250 ms gaps; not user speech",
                         "sha256": hashlib.sha256(audio).hexdigest(), "sample_rate": 16000,
                         "samples": len(audio) // 2, "dataset_version": "synthetic-stress-2026-11-06-v1"})
    (args.output / "fixtures.json").write_text(json.dumps(fixtures, indent=2) + "\n")
    (args.output / "all.txt").write_text("".join(f"{f['id']} /data/local/tmp/bold-voice-bench/stress/{f['file']} en\n" for f in fixtures))


if __name__ == "__main__":
    main()
