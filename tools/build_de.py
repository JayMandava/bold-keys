#!/usr/bin/env python3
"""Build only the pinned FrequencyWords de dictionary; legacy sentence/pair arguments are rejected.

Usage: python3 tools/build_de.py <de_50k.txt> <output words_de.tsv>
Next-word tables are built exclusively by data_pipeline.py from pinned CC0 inputs.
"""
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from data_pipeline import registry, sha, word_rows, word_tsv

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('frequency_input', type=Path)
parser.add_argument('word_output', type=Path)
args = parser.parse_args()
data = args.frequency_input.read_bytes()
if sha(data) != registry()['sources']['words_de']['sha256']:
    parser.error('Input must match the pinned FrequencyWords 2018 de SHA-256')
args.word_output.write_bytes(word_tsv(word_rows('de', data.decode())))
