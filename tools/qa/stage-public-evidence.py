#!/usr/bin/env python3
"""Copy an explicit evidence allowlist, never application/package binaries, for public QA."""
import hashlib
import io
import json
from pathlib import Path
import stat
import zipfile

ROOTS = (
    'qa-artifacts',
    'app/build/reports/tests',
    'app/build/reports/androidTests',
    'app/build/test-results',
    'app/build/outputs/androidTest-results',
)
TEXT = {'.txt', '.log', '.json', '.jsonl', '.xml', '.html', '.css', '.js'}
PNG_END = b'\x00\x00\x00\x00IEND\xaeB`\x82'


def permitted(data, suffix):
    # APK/AAB/JAR files are ZIP containers; reject even renamed/polyglot archives.
    if zipfile.is_zipfile(io.BytesIO(data)) or data.startswith((b'# v2 git bundle', b'# v3 git bundle', b'PACK')):
        return False
    if suffix in TEXT:
        # Package containers may be renamed; a suffix alone is not sufficient.
        if b'\0' in data or data.startswith((b'PK', b'\x7fELF', b'dex\n')):
            return False
        try:
            data.decode('utf-8')
            return True
        except UnicodeDecodeError:
            return False
    if suffix == '.png':
        return data.startswith(b'\x89PNG\r\n\x1a\n') and data.endswith(PNG_END)
    if suffix == '.mp4':
        # A normal screenrecord ISO-BMFF file must contain complete top-level boxes.
        offset = 0
        boxes = []
        while offset < len(data):
            if offset + 8 > len(data):
                return False
            size = int.from_bytes(data[offset:offset + 4], 'big')
            kind = data[offset + 4:offset + 8]
            header = 8
            if size == 1:
                if offset + 16 > len(data):
                    return False
                size = int.from_bytes(data[offset + 8:offset + 16], 'big')
                header = 16
            elif size == 0:
                size = len(data) - offset
            if size < header or offset + size > len(data) or kind not in {b'ftyp', b'free', b'mdat', b'moov', b'wide'}:
                return False
            boxes.append(kind)
            offset += size
        return bool(boxes) and boxes[0] == b'ftyp' and b'mdat' in boxes and b'moov' in boxes
    return False


def stage(root, destination):
    root = root.resolve()
    destination = destination.resolve()
    if not destination.is_relative_to(root) or destination == root:
        raise ValueError('Evidence destination must be a new directory in the workspace')
    destination.mkdir(parents=False, exist_ok=False)
    included, excluded = [], []
    candidates = []
    def linked(relative):
        return any((root / parent).is_symlink() for parent in (relative, *relative.parents))
    for name in ROOTS:
        source = root / name
        if linked(Path(name)):
            excluded.append({'path': name, 'reason': 'symlink root'})
            continue
        if source.exists():
            candidates.extend(source.rglob('*'))
    if not linked(Path('app/build/reports')):
        candidates.extend((root / 'app/build/reports').glob('lint-results-*.html'))
    for path in sorted(set(candidates)):
        relative = path.relative_to(root)
        # Reject symlinked ancestors as well as symlink files; never read external bytes.
        if linked(relative):
            excluded.append({'path': str(relative), 'reason': 'symlink'})
            continue
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            excluded.append({'path': str(relative), 'reason': 'not regular'})
            continue
        if path.suffix.lower() not in TEXT | {'.png', '.mp4'}:
            excluded.append({'path': str(relative), 'reason': 'not evidence allowlist'})
            continue
        data = path.read_bytes()
        if not permitted(data, path.suffix.lower()):
            excluded.append({'path': str(relative), 'reason': 'content does not match evidence type'})
            continue
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        included.append({'path': str(relative), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    manifest = {'policy': 'text reports, real PNG screenshots and screenrecord MP4 only; no APK/AAB/package/bundle binaries',
                'included': included, 'excluded': excluded}
    (destination / 'public-evidence-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    result = stage(Path.cwd(), Path.cwd() / 'qa-public-evidence')
    print(f"Staged {len(result['included'])} evidence files; excluded {len(result['excluded'])} non-allowlisted files")
