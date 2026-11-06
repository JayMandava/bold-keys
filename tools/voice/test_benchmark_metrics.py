import unittest
import csv
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from summarize_benchmark import edits, tokens


class WordErrorTests(unittest.TestCase):
    def test_exact_and_case_punctuation(self) -> None:
        self.assertEqual(0, edits(tokens("Please, send the report."), tokens("please send the report")))

    def test_insertion_deletion_substitution(self) -> None:
        self.assertEqual(1, edits(["send", "report"], ["send", "the", "report"]))
        self.assertEqual(1, edits(["send", "the", "report"], ["send", "report"]))
        self.assertEqual(1, edits(["send", "report"], ["send", "coffee"]))

    def test_empty_reference_and_hypothesis(self) -> None:
        self.assertEqual(0, edits([], []))
        self.assertEqual(2, edits([], ["the", "end"]))
        self.assertEqual(2, edits(["the", "report"], []))

    def test_repetition_can_exceed_one_hundred_percent_wer(self) -> None:
        self.assertEqual(4, edits(["bonjour"], ["bonjour"] * 5))

    def test_unicode_is_preserved(self) -> None:
        self.assertEqual(["café", "früh"], tokens("CAFÉ, früh."))

    def test_numeric_forms_are_not_silently_equated(self) -> None:
        self.assertEqual(4, edits(tokens("twelve thousand five hundred"), tokens("12,500")))

    def test_total_and_inference_statistics_are_distinct(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixtures = root / "fixtures.json"
            fixtures.write_text(json.dumps([{"id": "sample", "reference": "hello", "language": "en"}]))
            results = root / "timings.tsv"
            with results.open("w") as stream:
                writer = csv.DictWriter(stream, delimiter="\t", fieldnames=["id", "text", "status", "load_ms", "infer_ms", "total_ms", "pss_kb", "maxrss_kb"])
                writer.writeheader()
                for inference, total in [(50, 200), (100, 400)]:
                    writer.writerow({"id": "sample", "text": "hello", "status": 0, "load_ms": 20,
                                     "infer_ms": inference, "total_ms": total, "pss_kb": 100, "maxrss_kb": 120})
            output = subprocess.check_output([sys.executable, str(Path(__file__).with_name("summarize_benchmark.py")), str(fixtures), str(results)], text=True)
            row = json.loads(output)["results"][0]
            self.assertEqual(300, row["median_native_total_ms"])
            self.assertEqual(75, row["median_inference_ms"])
            self.assertEqual(400, row["english_p95_native_total_ms_nearest_rank"])
            self.assertEqual(0, row["wer"]["en"]["rate"])


if __name__ == "__main__":
    unittest.main()
