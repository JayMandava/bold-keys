#!/usr/bin/env python3
"""Reclaim zero-only extents of a downloaded SDK image; require identical logical bytes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

path = Path(sys.argv[1])
if not path.is_file() or path.is_symlink() or path.name != 'system.img':
    raise SystemExit('Expected a regular SDK system.img file')

def snapshot():
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b''):
            digest.update(block)
    stat = path.stat()
    return {'size': stat.st_size, 'sha256': digest.hexdigest(), 'allocated_bytes': stat.st_blocks * 512}

before = snapshot()
subprocess.run(['fallocate', '--dig-holes', str(path)], check=True)
after = snapshot()
result = {'before': before, 'after': after, 'logical_bytes_unchanged': before['size'] == after['size'] and before['sha256'] == after['sha256']}
print(json.dumps(result, indent=2), flush=True)
if not result['logical_bytes_unchanged']:
    raise SystemExit('Image integrity changed; do not boot this image')
