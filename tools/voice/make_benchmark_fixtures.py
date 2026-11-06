"""Generate explicitly synthetic fixtures locally; never record a microphone."""
import argparse
import hashlib
import json
import random
import struct
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    phrases = [
        ("short", "Aman", "en", "Please send the report tomorrow morning."),
        ("names", "Aman", "en", "Jeyanth Mandava is meeting Priya in Hyderabad."),
        ("numbers", "Aman", "en", "The invoice is for twelve thousand five hundred rupees."),
        ("technical", "Aman", "en", "Create a pull request and deploy the Kotlin API."),
        ("dictation", "Aman", "en", "I will call you after the meeting at five thirty."),
        ("french", "Thomas", "fr", "Bonjour, je voudrais un café demain matin."),
        ("german", "Anna", "de", "Bitte schicken Sie den Bericht morgen früh."),
    ]
    metadata = []
    for ident, voice, language, reference in phrases:
        aiff, pcm = args.output / f"{ident}.aiff", args.output / f"{ident}.pcm"
        subprocess.run(["say", "-v", voice, "-r", "165", "-o", str(aiff), reference], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(aiff), "-ar", "16000", "-ac", "1", "-f", "s16le", str(pcm)], check=True)
        if pcm.stat().st_size < 8000:
            raise RuntimeError(f"Speech generation produced no usable audio: {ident}")
        aiff.unlink()
        metadata.append({"id": ident, "file": pcm.name, "language": language,
                         "reference": reference, "source": "macOS say", "voice": voice,
                         "kind": "synthetic, not user speech"})
    rng = random.Random(20261106)
    clean = (args.output / "short.pcm").read_bytes()
    samples = struct.unpack(f"<{len(clean)//2}h", clean)
    noisy = [max(-32768, min(32767, sample + rng.randint(-1800, 1800))) for sample in samples]
    (args.output / "noise.pcm").write_bytes(struct.pack(f"<{len(noisy)}h", *noisy))
    metadata.append({**metadata[0], "id": "noise", "file": "noise.pcm", "kind": "synthetic speech plus seeded uniform noise"})
    (args.output / "silence.pcm").write_bytes(bytes(16000 * 2 * 2))
    metadata.append({"id": "silence", "file": "silence.pcm", "language": "en", "reference": "", "kind": "digital silence"})
    for fixture in metadata:
        data = (args.output / fixture["file"]).read_bytes()
        fixture.update({"sha256": hashlib.sha256(data).hexdigest(), "sample_rate": 16000,
                        "samples": len(data) // 2, "dataset_version": "synthetic-2026-11-06-v1"})
    (args.output / "fixtures.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n")
    for name, selected in [("all", metadata), ("english", [f for f in metadata if f["language"] == "en"])]:
        (args.output / f"{name}.txt").write_text("".join(f"{f['id']} /data/local/tmp/bold-voice-bench/audio/{f['file']} {f['language']}\n" for f in selected))


if __name__ == "__main__":
    main()
