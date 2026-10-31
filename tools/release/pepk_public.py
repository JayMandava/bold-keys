#!/usr/bin/env python3
"""Hash public PEPK inputs only. No downloading, extraction, compilation, keys or passwords."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

RECORD = Path(__file__).with_name('pepk-export-review.json')


def verify_public_file(path, expected_sha256, expected_size=None):
    import re
    if not isinstance(expected_sha256, str) or not re.fullmatch(r'[0-9a-f]{64}', expected_sha256):
        raise ValueError('Reviewed public-input SHA256 is missing or invalid')
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Public input must be a regular non-symlink file')
    if expected_size is not None and path.stat().st_size != expected_size:
        raise ValueError('Public input size mismatch')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != expected_sha256:
        raise ValueError('Public input SHA256 mismatch')
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--source', type=Path)
    group.add_argument('--source-built-tool', type=Path)
    group.add_argument('--console-pem', type=Path)
    args = parser.parse_args()
    try:
        record = json.loads(RECORD.read_text())
        if args.source:
            verify_public_file(args.source, record['official_source_sha256'], record['official_source_size_bytes'])
        elif args.source_built_tool:
            verify_public_file(args.source_built_tool, record['source_built_tool_sha256'])
        else:
            verify_public_file(args.console_pem, record['console_encryption_pem_sha256'])
        print('Public input matches reviewed byte pin; this does not authorize key export.')
    except (ValueError, OSError, KeyError) as error:
        print(f'Public input verification blocked: {error}', file=sys.stderr)
        sys.exit(1)
